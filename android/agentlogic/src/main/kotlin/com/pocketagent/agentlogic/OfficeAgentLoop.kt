package com.pocketagent.agentlogic

import com.pocketagent.filelogic.OfficeRequest
import com.pocketagent.filelogic.OfficeTool
import com.pocketagent.filelogic.OfficeToolCall
import com.pocketagent.filelogic.OfficeToolParser
import com.pocketagent.filelogic.OfficeToolValidator
import com.pocketagent.filelogic.ToolParseOutcome
import com.pocketagent.filelogic.ToolValidation
import com.pocketagent.filelogic.WorkspacePolicy

/**
 * 办公任务的 **agent 循环** —— 计划 → 调工具 → 观察 → 继续。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 它与 `:agent` 的 `AgentOrchestrator` 是**两条线**，不要合并
 * ═══════════════════════════════════════════════════════════════
 *
 * | | `AgentOrchestrator`（`:agent`） | 本类（`:agentlogic`） |
 * |---|---|---|
 * | 干什么 | **操作手机**（点击、滑动、输入） | **操作文件**（读、写、生成） |
 * | 输入 | `ScreenSnapshot`（屏幕快照） | 工作区路径 |
 * | 定位 | 需要 `Grounder`（坐标定位脑） | **不需要** —— 路径就是路径 |
 * | 校验 | 需要 `Verifier`（对比前后快照） | 工具自己返回结果 |
 * | 依赖 | 无障碍 / Shizuku / 屏幕 | **零 Android 依赖** |
 *
 * ⇒ 合并的代价：办公任务会被迫拖进无障碍权限、屏幕快照、Grounder ——
 *    而**它们一个都用不到**。而更实际的是：**本类能进离线验证器，
 *    `AgentOrchestrator` 不能。** 把两者混在一起，办公这条线就失去了
 *    它最大的优势（可离线测、不依赖任何权限）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 四条保护，缺一条都可能跑飞
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **预算守卫**（[BudgetGuard]）。项目既有约束：横切组件必须从循环
 *    第一版就长在里面，不能"最后统一加" —— 后加的表现是
 *    "任务悄悄跑了 40 轮"，而且没有任何地方会报错。
 * 2. **步数上限**（[OfficeTask.maxSteps]）。与预算的轮次是两回事：
 *    预算是"资源"，步数是"这个任务允许多长"。
 * 3. **重复调用检测**。模型卡住时的典型表现是**发出完全相同的调用**
 *    （它以为上次没成功）。没有这条，循环会一直转到预算耗尽，
 *    而用户看到的是"助理转了 12 轮什么也没做"。
 * 4. **解析失败也回给模型**。不把"你的输出看不懂"告诉它，
 *    它下一轮只会重复同样的错误输出。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 拒绝**不是**终止，是反馈
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具被 [OfficeToolValidator] 拒绝时（路径穿越、产出目录不对、超大小），
 * 本循环**不中止任务**，而是把拒绝原因写进 [OfficeRunResult.trace]，
 * 让模型下一轮看到并改正。
 *
 * 为什么不中止：拒绝的理由绝大多数是**模型可以自己修的**
 * （"路径必须是相对的"、"只能写到 输出/ 下"）。
 * 中止会让"模型写错一次路径"变成"整个任务失败" —— 而它下一轮
 * 明明能改对。**把可修复的错误当成终止条件，是循环设计里最贵的错法。**
 *
 * ⚠️ 但有一条**例外**：连续发出完全相同的调用 → 判定卡死 → 中止。
 *    因为"同样的调用被拒了两次"说明模型没在读反馈。
 */
class OfficeAgentLoop(
    private val llm: OfficeLlmPort,
    private val executor: OfficeToolExecutor,
    private val budgetGuard: BudgetGuard,
    /** 注入时钟，便于离线测试"时长"这一维预算而不真的等 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 跑一个任务。
     *
     * ⚠️ **本方法不抛异常**。任何内部失败都转成 [OfficeRunResult] 的一个分支 ——
     *    调用链上没有任何人能处理异常，而抛出去会把"任务没做成"
     *    这个**可解释的业务状态**变成崩溃。
     */
    suspend fun run(task: OfficeTask): OfficeRunResult {
        val started = clock()
        val trace = mutableListOf<String>()
        var usage = BudgetUsage()
        var lastCallKey: String? = null
        var identicalRepeats = 0

        while (true) {
            // ── 保护 1：预算 ────────────────────────────────────
            val usageWithTime = usage.at(elapsedMs = clock() - started, energyMicroAh = usage.energyMicroAh)
            when (val verdict = budgetGuard.check(usageWithTime)) {
                is BudgetVerdict.Exceeded -> {
                    return OfficeRunResult.Stopped(
                        "预算超限（${verdict.dimension}）：${verdict.detail}",
                        trace,
                    )
                }
                // Warn / NotMeasurable / Ok 都继续 —— 警戒线不是中止线
                else -> Unit
            }

            // ── 保护 2：步数 ────────────────────────────────────
            if (usage.turns >= task.maxSteps) {
                return OfficeRunResult.StepLimitReached(usage.turns, trace)
            }

            // ── 调用模型 ────────────────────────────────────────
            val raw = try {
                llm.complete(buildPrompt(task, trace))
            } catch (e: Exception) {
                return OfficeRunResult.Stopped("模型调用失败：${e.message ?: e::class.simpleName}", trace)
            }
            usage = usage.nextTurn()

            when (val step = parseStep(raw)) {
                is ParsedStep.Answer -> return OfficeRunResult.Answered(step.text, usage.turns, trace)

                is ParsedStep.Unparseable -> {
                    // ── 保护 4：解析失败要回给模型 ───────────────
                    trace += "（你的输出无法解析。请只输出「一个」 JSON 对象，" +
                        "形如 {\"tool\":\"read_text\",\"args\":{\"path\":\"a.md\"}} 或 {\"answer\":\"...\"}。" +
                        "原文开头：${step.raw.take(60)}）"
                }

                is ParsedStep.Call -> {
                    // ── 保护 3：重复调用检测 ─────────────────────
                    val key = callKey(step.call)
                    if (key == lastCallKey) {
                        identicalRepeats++
                        if (identicalRepeats >= MAX_IDENTICAL_REPEATS) {
                            return OfficeRunResult.Stopped(
                                "连续 ${identicalRepeats + 1} 次发出完全相同的调用（${step.call.tool.id}），判定为卡死",
                                trace,
                            )
                        }
                    } else {
                        identicalRepeats = 0
                        lastCallKey = key
                    }

                    when (val validation = OfficeToolValidator.validate(step.call, task.policy)) {
                        is ToolValidation.Allowed -> {
                            val outcome = try {
                                executor.execute(validation.request)
                            } catch (e: Exception) {
                                OfficeToolOutcome.Failed(e.message ?: e::class.simpleName ?: "未知错误")
                            }
                            trace += when (outcome) {
                                is OfficeToolOutcome.Ok -> "${step.call.tool.id} → ${outcome.summary}"
                                is OfficeToolOutcome.Failed -> "${step.call.tool.id} 失败：${outcome.reason}"
                            }
                        }

                        is ToolValidation.Denied -> {
                            // ★ 拒绝是反馈，不是终止（见类注释）
                            trace += "${step.call.tool.id} 被拒绝：${validation.reason}"
                        }
                    }
                }
            }
        }
    }

    // ── 提示词 ──────────────────────────────────────────────────

    /**
     * 组装提示词。
     *
     * ⚠️ 工具说明**从 [OfficeTool] 枚举生成**，不手写 —— 手写的那份
     *    会在加工具时漂移，而漂移的表现是"模型不知道有这个工具"，
     *    于是它永远不用那个工具，而没有人会发现。
     */
    internal fun buildPrompt(task: OfficeTask, trace: List<String>): String = buildString {
        appendLine("你是一个手机办公助理。你可以在用户授权的工作区里读写文件，完成用户的办公需求。")
        appendLine()
        appendLine("## 可用工具")
        for (tool in OfficeTool.entries) {
            appendLine("- `${tool.id}`：${tool.description}")
        }
        appendLine()
        appendLine("## 输出格式（必须严格遵守）")
        appendLine("每次只输出「一个」 JSON 对象，不要任何解释、不要代码围栏：")
        appendLine("- 要调用工具：`{\"tool\":\"工具名\",\"args\":{\"参数\":\"值\"}}`")
        appendLine("- 任务已完成：`{\"answer\":\"给用户的答复\"}`")
        appendLine()
        appendLine("## 规则")
        appendLine("1. 路径一律用「相对路径」，不要以 / 开头，不要出现 ..")
        appendLine("2. 产出只能写到「${task.policy.outputSubdir}/」目录下 —— 写到别处会被拒绝")
        appendLine("3. 一次只做一步。做完一步后根据结果决定下一步")
        appendLine("4. 被拒绝时「读拒绝原因并改正」，不要原样重试")
        appendLine("5. 信息够了就输出 answer 结束，不要为了多做而多做")
        appendLine()
        appendLine("## 当前工作区")
        appendLine("- 名称：${task.workspaceName}")
        appendLine("- 产出目录：${task.policy.outputSubdir}/")
        appendLine("- 可写入：${if (task.policy.allowWrite) "是" else "否"}")
        appendLine()
        appendLine("## 用户任务")
        appendLine(task.userInput)
        if (trace.isNotEmpty()) {
            appendLine()
            appendLine("## 已执行的操作")
            trace.forEachIndexed { i, line -> appendLine("${i + 1}. $line") }
            appendLine()
            append("请根据上面的结果决定下一步。")
        }
    }

    // ── 解析 ────────────────────────────────────────────────────

    /** 模型一轮输出的归宿。三态 —— 见 [parseStep]。 */
    internal sealed interface ParsedStep {
        data class Call(val call: OfficeToolCall) : ParsedStep
        data class Answer(val text: String) : ParsedStep
        data class Unparseable(val raw: String) : ParsedStep
    }

    /**
     * 解析模型输出。
     *
     * ⚠️ **三态，不是两态。** 「要调工具」与「任务完成」必须分开：
     *    合并的话，一个把 `{"answer":...}` 当成"未知工具"的实现会让循环
     *    一直重试到步数耗尽，而模型其实已经做完了。
     */
    internal fun parseStep(raw: String): ParsedStep {
        val text = stripCodeFence(raw).trim()
        if (text.isEmpty()) return ParsedStep.Unparseable(raw)

        // 先看是不是"最终答复"。用宽松匹配而不是严格 JSON 解析：
        // 模型经常在 answer 里带引号或换行，严格解析会把**已经完成的**
        // 任务判成"看不懂"，然后循环继续跑
        extractAnswer(text)?.let { return ParsedStep.Answer(it) }

        return when (val parsed = OfficeToolParser.parse(text)) {
            is ToolParseOutcome.Ready -> ParsedStep.Call(parsed.call)
            is ToolParseOutcome.Invalid -> ParsedStep.Unparseable(raw)
        }
    }

    /**
     * 从输出里取 `answer` 字段的值。
     *
     * 刻意**不用严格的 JSON 解析**：模型把答复写成
     * `{"answer":"第一行\n第二行"}` 或带未转义引号的情况非常常见，
     * 而严格解析会把这些**已经完成**的任务判成"看不懂"。
     * 这里的取舍是：宁可把一段含 `"answer"` 的文本当成答复，
     * 也不要让一个做完的任务被当成没做完。
     */
    private fun extractAnswer(text: String): String? {
        val idx = text.indexOf("\"answer\"")
        if (idx < 0) return null
        val colon = text.indexOf(':', idx)
        if (colon < 0) return null
        val rest = text.substring(colon + 1).trim()
        if (!rest.startsWith("\"")) return null

        // 手工扫描到配对的收尾引号（处理 \" 转义）
        val sb = StringBuilder()
        var i = 1
        while (i < rest.length) {
            val ch = rest[i]
            if (ch == '\\' && i + 1 < rest.length) {
                when (val next = rest[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    else -> sb.append(next)
                }
                i += 2
                continue
            }
            if (ch == '"') return sb.toString()
            sb.append(ch)
            i++
        }
        // 没有收尾引号（被截断）→ 也接受，总比丢掉答复好
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** 剥掉 ``` 围栏。模型输出 JSON 时经常带。 */
    private fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.contains("```")) return trimmed
        val open = trimmed.indexOf("```")
        val bodyStart = trimmed.indexOf('\n', open)
        if (bodyStart < 0) return trimmed
        val close = trimmed.lastIndexOf("```")
        if (close <= bodyStart) return trimmed
        return trimmed.substring(bodyStart + 1, close).trim()
    }

    /** 用于重复检测的键：工具 + **排序后**的参数。参数顺序不同不算重复。 */
    private fun callKey(call: OfficeToolCall): String =
        call.tool.id + "|" + call.args.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }

    companion object {
        /**
         * 连续多少次**完全相同**的调用判定为卡死。
         *
         * 取 3（即第 3 次重复时中止）：第 1 次是正常尝试，第 2 次可能是
         * 模型没看到反馈，第 3 次就基本可以确定它在原地打转。
         * 定得太高会浪费预算，定得太低会误杀"第一次失败、第二次重试成功"。
         */
        const val MAX_IDENTICAL_REPEATS: Int = 3
    }
}

/** 循环需要的 LLM 端口。**只依赖接口，不依赖任何厂商实现**（同网关的既定模式）。 */
fun interface OfficeLlmPort {
    /**
     * 一次补全。
     *
     * @param prompt 由循环自己组装（提示词是**纯逻辑**，可离线断言）
     * @return 模型原始返回。⚠️ 可能带代码围栏、可能不合法，
     *   解析与校验的责任在 [OfficeAgentLoop.parseStep]。
     */
    suspend fun complete(prompt: String): String
}

/** 工具执行端口 —— 由 Android 层实现（真正碰文件系统）。 */
fun interface OfficeToolExecutor {
    suspend fun execute(request: OfficeRequest): OfficeToolOutcome
}

/** 一次工具执行的结果。 */
sealed interface OfficeToolOutcome {
    /** 成功。[summary] 会进 trace 回给模型 —— 所以要**含关键信息**（写了哪个文件、几行）。 */
    data class Ok(val summary: String) : OfficeToolOutcome

    data class Failed(val reason: String) : OfficeToolOutcome
}

/** 一个办公任务。 */
data class OfficeTask(
    val id: String,
    val userInput: String,
    val workspaceName: String,
    val policy: WorkspacePolicy,
    /**
     * 最大步数。
     *
     * ⚠️ 与预算的轮次是**两回事**：预算是"资源允许多少"，
     * 步数是"这个任务本身允许多长"。一个查 3 个文件就够的任务，
     * 不该因为它没超预算就允许它跑 20 步。
     */
    val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    companion object {
        const val DEFAULT_MAX_STEPS: Int = 12
    }
}

/** 循环的结局。**四个分支都带 [trace]** —— 用户要能看到"它到底做了什么"。 */
sealed interface OfficeRunResult {

    /** 模型给出了最终答复。 */
    data class Answered(val text: String, val steps: Int, val trace: List<String>) : OfficeRunResult

    /** 步数用完。**这不是失败** —— 它只是没做完，用户可以选择继续。 */
    data class StepLimitReached(val steps: Int, val trace: List<String>) : OfficeRunResult

    /** 被保护机制中止（预算超限 / 卡死 / 模型调用失败）。 */
    data class Stopped(val reason: String, val trace: List<String>) : OfficeRunResult
}
