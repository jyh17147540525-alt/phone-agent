package com.pocketagent.agentlogic.orchestration

/**
 * 任务执行器 —— 把"一句话"变成"一串动作 + 每次验收"。
 *
 * ## 它负责什么
 *
 * 只负责**控制流**，不碰手机：计划从 [PlannerPort] 来，动作由 [StepRunnerPort] 执行，
 * 屏幕摘要由执行方顺带返回。这样它能被离线测得很彻底 —— 真机上那些
 * "点了没反应""页面还没加载完""权限被撤了"都能用剧本演出来。
 *
 * ## 六条规则，每条都对应一种真机上见过的失败
 *
 * 1. **每一步都要验收**（[Expectation]）。不验收的 agent 会"以为"自己成功了，
 *    最后交一份编出来的结果。
 * 2. **被拦下就停**（[StepStatus.REFUSED]）。安全闸门与档位网关的拒绝是**最终结论**，
 *    重试只会把同一条路再撞一次，而且会让日志里看不出"到底谁在拦"。
 * 3. **只有幂等的才重试**。点一下按钮不幂等，重试就是双击。
 * 4. **同一步骤签名出现太多次 → 判定打转**。模型卡住时的典型表现是
 *    反复"读屏 → 发现不对 → 再读屏"，不设上限就是烧钱。
 * 5. **不可逆的动作必须用户点过**。这条不在执行器里判断"该不该"，只判断
 *    "用户点过没有"——判断"该不该"是 [ToolSpec.reversible] 与安全层的事。
 * 6. **预算是硬的**。步数、时长、重规划次数都不许超 —— 一次跑飞的任务
 *    比一次失败的任务更糟，因为用户不知道它在干什么。
 *
 * ## 与 Claude Code / Codex 的对应
 *
 * 它们的循环是「改代码 → 跑测试 → 看输出 → 再改」。手机上"跑测试"的位置换成了
 * 「读屏摘要对不对」。区别只有一点：手机上每一步的**代价**不只是时间，
 * 还可能占住用户的屏幕、改掉系统设置 —— 所以这里比它们多两道闸
 * （[RunBudget] 与确认闸），而不是更自由。
 */
class RunOrchestrator(
    private val planner: PlannerPort,
    private val runner: StepRunnerPort,
    private val catalog: ToolCatalog = ToolCatalog(),
    private val budget: RunBudget = RunBudget(),
    /** 单调时钟（毫秒）。测试里注入假时钟。 */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    suspend fun run(goal: String, context: RunContext = RunContext()): RunReport {
        val startedAt = clock()
        val attempts = mutableListOf<StepAttempt>()
        var replans = 0

        fun elapsed(): Long = clock() - startedAt

        fun budgetExceeded(): Boolean = elapsed() > budget.maxDurationMs || attempts.size >= budget.maxSteps

        fun stopped(): Boolean = context.isStopped()

        fun outOfBudgetPlan(): RunReport = RunReport(
            phase = RunPhase.OUT_OF_BUDGET,
            goal = goal,
            plan = null,
            attempts = attempts.toList(),
            summary = "跑到了预算上限（${budget.maxSteps} 步或 ${budget.maxDurationMs / 1000} 秒）就停了。"
                + "已经做完的步骤不会回滚，你可以先让它读一次屏看现在停在哪。",
        )

        var current = when (val first = planner.plan(PlanRequest(goal, catalog, null, 0, budget.maxSteps))) {
            is PlanResult.Refuse -> return RunReport(
                phase = RunPhase.REFUSED,
                goal = goal,
                plan = null,
                attempts = emptyList(),
                summary = first.reason,
            )
            is PlanResult.NeedInfo -> return RunReport(
                phase = RunPhase.NEED_INFO,
                goal = goal,
                plan = null,
                attempts = emptyList(),
                summary = "我需要你补一句才能开始。",
                question = first.question,
            )
            is PlanResult.Ok -> first.plan
        }

        while (true) {
            var failed: PlannedStep? = null
            var failureDetail = ""

            for (step in current.steps) {
                if (stopped()) {
                    return RunReport(
                        phase = RunPhase.STOPPED,
                        goal = goal,
                        plan = current,
                        attempts = attempts.toList(),
                        summary = "你叫停了。已经做完的 ${attempts.count { it.status == StepStatus.DONE }} 步不会回滚。",
                    )
                }
                if (budgetExceeded()) return outOfBudgetPlan()

                val spec = catalog.byId(step.tool)
                if (spec == null) {
                    // 计划里出现了目录外的工具 —— 这是规划器的 bug，不是环境问题
                    return RunReport(
                        phase = RunPhase.FAILED,
                        goal = goal,
                        plan = current,
                        attempts = attempts.toList(),
                        summary = "计划里用了不存在的工具「${step.tool}」，这条计划本身是错的，停在这里。",
                    )
                }

                // 规则 5：有后果的不可逆动作，用户点过才能做
                if (spec.requiresConfirmation && !context.preApproved && step.id !in context.confirmedStepIds) {
                    return RunReport(
                        phase = RunPhase.AWAITING_CONFIRMATION,
                        goal = goal,
                        plan = current,
                        attempts = attempts.toList(),
                        summary = "下一步是「${step.note.ifBlank { spec.title }}」，${spec.cost()}。你确认之后我再继续。",
                        awaitingStep = step,
                    )
                }

                val (report, retries) = executeWithRetry(step, spec)
                var status = report.status
                var detail = report.detail
                var verified: Boolean? = null

                // 规则 1：做完了也要验收
                if (status == StepStatus.DONE) {
                    val unmet = step.expect.unmetOn(report.digest)
                    if (unmet != null) {
                        status = StepStatus.FAILED
                        detail = "动作发出去了，但验收没过：$unmet"
                        verified = false
                    } else {
                        verified = true
                    }
                }

                attempts += StepAttempt(
                    stepId = step.id,
                    tool = step.tool,
                    signature = step.signature,
                    status = status,
                    detail = detail,
                    elapsedMs = elapsed(),
                    retries = retries,
                    verified = verified,
                )

                if (status == StepStatus.DONE) continue

                // 规则 2：被拦下是最终结论，不许重规划绕路 —— 绕路等于换个写法撞同一道闸
                if (status == StepStatus.REFUSED) {
                    return RunReport(
                        phase = RunPhase.FAILED,
                        goal = goal,
                        plan = current,
                        attempts = attempts.toList(),
                        summary = "第 ${attempts.size} 步被拦下了：$detail。这条路走不通，不是写法问题。",
                    )
                }

                failed = step
                failureDetail = detail
                break
            }

            if (failed == null) {
                return RunReport(
                    phase = RunPhase.DONE,
                    goal = goal,
                    plan = current,
                    attempts = attempts.toList(),
                    summary = "做完了，共 ${attempts.size} 步，用时 ${elapsed() / 1000} 秒。"
                        + "每一步都做过验收。",
                )
            }

            // 规则 4：同一签名出现太多次 → 打转
            val signatureCount = attempts.count { it.signature == failed.signature }
            if (signatureCount >= budget.maxRepeats) {
                return RunReport(
                    phase = RunPhase.LOOPING,
                    goal = goal,
                    plan = current,
                    attempts = attempts.toList(),
                    summary = "在「${failed.note.ifBlank { failed.tool }}」这一步打转了 $signatureCount 次，我停下了。"
                        + "换一种说法或自己看一眼现在停在哪，会比继续试更快。",
                )
            }

            if (replans >= budget.maxReplans) {
                val spec = catalog.byId(failed.tool)
                return RunReport(
                    phase = RunPhase.FAILED,
                    goal = goal,
                    plan = current,
                    attempts = attempts.toList(),
                    summary = "卡在「${failed.note.ifBlank { failed.tool }}」：$failureDetail"
                        + (spec?.failureHint?.let { "。$it" } ?: ""),
                )
            }

            replans += 1
            val next = planner.plan(
                PlanRequest(
                    goal = goal,
                    catalog = catalog,
                    previousFailure = "第 ${attempts.size} 步「${failed.tool}」失败：$failureDetail",
                    attempt = replans,
                    budgetLeft = budget.maxSteps - attempts.size,
                ),
            )
            current = when (next) {
                is PlanResult.Ok -> next.plan
                is PlanResult.Refuse -> return RunReport(
                    phase = RunPhase.REFUSED,
                    goal = goal,
                    plan = current,
                    attempts = attempts.toList(),
                    summary = next.reason,
                )
                is PlanResult.NeedInfo -> return RunReport(
                    phase = RunPhase.NEED_INFO,
                    goal = goal,
                    plan = current,
                    attempts = attempts.toList(),
                    summary = "这条路走不通，我需要你补一句。",
                    question = next.question,
                )
            }
        }
    }

    /**
     * 执行一步，按 [ToolSpec.idempotent] 决定要不要重试。
     *
     * 只有超时才重试：明确失败（参数错、页面没有那个控件）重试一次还是同样的结果，
     * 而超时可能是这一下恰好慢。
     */
    private suspend fun executeWithRetry(step: PlannedStep, spec: ToolSpec): Pair<StepOutcomeReport, Int> {
        var attempt = 0
        var report = runner.run(step)
        while (report.status == StepStatus.TIMEOUT && spec.idempotent && attempt < budget.maxRetries) {
            attempt += 1
            report = runner.run(step)
        }
        return report to attempt
    }
}

/** 规划请求。重规划时带上上一次的失败原因 —— 有用的信息只有这一条。 */
data class PlanRequest(
    val goal: String,
    val catalog: ToolCatalog,
    /** 上一次失败的原因。首次规划是 null。 */
    val previousFailure: String? = null,
    /** 第几次规划（0 = 首次）。 */
    val attempt: Int = 0,
    /** 还剩多少步的预算。规划器应当据此压缩计划。 */
    val budgetLeft: Int = 0,
)

/** 规划结果。三选一：给计划、拒绝、问一句。 */
sealed interface PlanResult {
    data class Ok(val plan: RunPlan) : PlanResult
    data class Refuse(val reason: String) : PlanResult
    data class NeedInfo(val question: String) : PlanResult
}

/** 规划器的接口。模型实现、规则实现、测试剧本实现都实现它。 */
fun interface PlannerPort {
    suspend fun plan(request: PlanRequest): PlanResult
}

/** 执行一步的接口。真实实现会调 `:mcp` 的工具并把屏幕摘要拼出来。 */
fun interface StepRunnerPort {
    suspend fun run(step: PlannedStep): StepOutcomeReport
}

/**
 * 跑一次任务的外部条件。
 *
 * [preApproved] 与 [confirmedStepIds] 的区别：前者是"这一整趟都放行"（用户在设置里
 * 关掉了确认），后者是"只放行这一步"（用户在弹窗上点了那一下）。
 */
data class RunContext(
    val preApproved: Boolean = false,
    val confirmedStepIds: Set<String> = emptySet(),
    val isStopped: () -> Boolean = { false },
)
