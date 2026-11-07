package com.pocketagent.agentlogic.orchestration

/**
 * 一句话进来，四种出口。
 *
 * ## 为什么先分流再干活
 *
 * "今天好累"和"把昨天那份纪要发给我"在字面上都是句子。前者不需要动手机 ——
 * 让 agent 去点屏幕不只是浪费，还会实打实地**划走用户正在看的东西**。
 * 所以 [TaskIntake] 先判一次，判成聊天就一句话都不动手。
 *
 * ## 为什么链条要串行且短
 *
 * "先把截图存下来，然后发到群上"这种两句以上的指令，一次跑完比让模型自己记着
 * 省事得多。但链条不能长：每一步都要读一次屏幕、都可能需要重规划，
 * 链到第四步时前面任何一步的偏差都会被后一步继承。所以 [MAX_CHAIN] 卡在 4，
 * 超过就让用户再说一次 —— 那时用户自己也知道该拆了。
 */
class SuperAgent(
    private val planner: PlannerPort,
    private val runner: StepRunnerPort,
    private val catalog: ToolCatalog = ToolCatalog(),
    private val budget: RunBudget = RunBudget(),
    private val maxChain: Int = MAX_CHAIN,
) {

    /** 一句话的归宿。 */
    sealed interface SuperReply {

        /**
         * 给用户的那一句话。
         *
         * ★ 提到接口上而不是让调用方 `when` 一遍：界面层、日志层、语音层都要这句话，
         *   每处写一个 `when` 就会有四处不同的措辞 —— 而其中一处漏掉新分支时，
         *   Kotlin 只在**穷尽**的 `when` 上报错，`is` 判断那种写法会静默给空串。
         */
        val spoken: String

        /** 不是在下指令 —— 交给聊天那条路，agent 一步都不动。 */
        data class Chat(val text: String, val reason: String) : SuperReply {
            override val spoken: String get() = text
        }

        /** 话没听清，或者要先确认。 */
        data class Ask(val question: String, val options: List<String> = emptyList()) : SuperReply {
            override val spoken: String get() = question
        }

        /** 用户喊停。 */
        data class Stopped(val note: String) : SuperReply {
            override val spoken: String get() = note
        }

        /** 跑完了（可能成功，也可能中途停下 —— 看 [report]。 */
        data class Finished(
            val report: RunReport,
            override val spoken: String,
        ) : SuperReply
    }

    suspend fun handle(
        text: String,
        context: IntakeContext = IntakeContext(),
        runContext: RunContext = RunContext(),
    ): SuperReply {
        val intake = TaskIntake.route(text, context)
        return when (intake) {
            is Intake.Chat -> SuperReply.Chat(text, intake.reason)

            is Intake.Stop -> SuperReply.Stopped("好，停了。")

            is Intake.Clarify -> SuperReply.Ask(intake.question, intake.options)

            is Intake.Task -> {
                if (intake.needsConfirmation && !runContext.preApproved) {
                    SuperReply.Ask(
                        question = "「${intake.goal.take(60)}」可能会花钱或者删掉东西，做吗？",
                        options = listOf("做", "别做"),
                    )
                } else {
                    runOne(intake.goal, runContext)
                }
            }

            is Intake.Chain -> {
                if (intake.goals.size > maxChain) {
                    SuperReply.Ask("这条链有 ${intake.goals.size} 件事，一次做 ${maxChain} 件以内更稳；先挑最要紧的几件？")
                } else if (intake.goals.any { it.contains(MONEY_HINT) } && !runContext.preApproved) {
                    // 链条里只要有一句碰到钱，整条都先问 —— 不做"前两步先跑着、第三步再问"。
                    SuperReply.Ask("这条链里有花钱的动作，整条先跑还是先跳过那一步？", listOf("整条做", "别做"))
                } else {
                    runChain(intake.goals, runContext)
                }
            }
        }
    }

    private suspend fun runOne(goal: String, runContext: RunContext): SuperReply.Finished {
        val orchestrator = RunOrchestrator(planner = planner, runner = runner, catalog = catalog, budget = budget)
        val report = orchestrator.run(goal, runContext)
        return SuperReply.Finished(report, spokenFor(report))
    }

    private suspend fun runChain(goals: List<String>, runContext: RunContext): SuperReply.Finished {
        val spoken = mutableListOf<String>()
        var last: RunReport? = null
        var doneCount = 0

        for ((index, goal) in goals.withIndex()) {
            if (runContext.isStopped()) {
                spoken += "第 ${index + 1} 件之前你叫停了，剩下的没做。"
                break
            }
            val reply = runOne("$goal（第 ${index + 1}/${goals.size} 件）", runContext)
            last = reply.report
            if (!reply.report.ok) {
                spoken += "第 ${index + 1} 件没成：${reply.spoken}"
                break
            }
            doneCount++
            spoken += "第 ${index + 1} 件做完。"
        }

        val report = last ?: RunReport(
            phase = RunPhase.DONE,
            goal = goals.joinToString("；"),
            plan = null,
            attempts = emptyList(),
            summary = "没有可执行的步骤",
        )
        val summary = if (doneCount == goals.size) {
            "一共 ${goals.size} 件，都做完了。"
        } else {
            "做到第 ${doneCount + 1} 件就停了。"
        }
        return SuperReply.Finished(report, (listOf(summary) + spoken).joinToString(" "))
    }

    /**
     * 给用户听的那一句。
     *
     * 只讲**结论与下一步**，不复述每一步 —— 用户在手机上，长段落等于没读。
     */
    private fun spokenFor(report: RunReport): String = when (report.phase) {
        RunPhase.DONE -> buildString {
            append("做完了。")
            val notes = report.plan?.steps?.mapNotNull { it.note.ifBlank { null } }.orEmpty()
            if (notes.isNotEmpty()) append(notes.take(3).joinToString(" → "))
            if (report.attempts.size > notes.size) append("（中途调整过 ${report.attempts.size - notes.size} 次）")
        }

        RunPhase.NEED_INFO -> report.question ?: "还差一点信息，你再说一句？"

        RunPhase.REFUSED -> "这件事我不做：${report.summary}"

        RunPhase.AWAITING_CONFIRMATION -> {
            val step = report.awaitingStep
            "下一步「${step?.note ?: "这一步"}」${catalog.costOf(step?.tool)}，要继续吗？"
        }

        RunPhase.STOPPED -> "好，停了。"

        RunPhase.LIMIT_REACHED -> report.summary

        RunPhase.LOOPING -> "我好像在原地打转（${report.summary}），先停下听听你的。"

        RunPhase.OUT_OF_BUDGET -> "预算用完了：${report.summary}。要我接着做吗？"

        // 被安全闸门拦下与"技术故障"在编排层都是 FAILED（两条路都不会继续），
        // 但对用户是两件完全不同的事：一个是"我不做"，一个是"没做成"。
        // 文案要是混了，用户下次会把真正的拦截当成 bug 去反馈。
        RunPhase.FAILED -> {
            val last = report.attempts.lastOrNull()
            if (last?.status == StepStatus.REFUSED) {
                "这件事我不做：${last.detail}"
            } else {
                "没做成：${report.summary}"
            }
        }
    }

    private fun ToolCatalog.costOf(tool: String?): String =
        tool?.let { byId(it)?.cost() } ?: "可能有副作用"

    private companion object {
        const val MAX_CHAIN = 4
        val MONEY_HINT = Regex("转账|付款|支付|下单|充值|红包|汇给")
    }
}
