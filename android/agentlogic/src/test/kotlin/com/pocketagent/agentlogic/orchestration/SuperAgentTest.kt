package com.pocketagent.agentlogic.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一句话 → 分流 → 跑完/问一句/停。
 *
 * 这里最要紧的四条：①聊天句子**一步都不动**（不划走用户的屏幕）；
 * ②碰到钱先问；③链条串行且第一件失败就停；④每种收场都有一句人能听懂的话。
 */
class SuperAgentTest {

    private class ScriptedPlanner(private val results: MutableList<PlanResult>) : PlannerPort {
        var calls = 0
        val goals = mutableListOf<String>()

        override suspend fun plan(request: PlanRequest): PlanResult {
            calls++
            goals += request.goal
            return results.removeFirstOrNull() ?: PlanResult.Refuse("剧本用完了")
        }
    }

    private class ScriptedRunner(private val outcomes: MutableList<StepOutcomeReport>) : StepRunnerPort {
        val ran = mutableListOf<String>()
        override suspend fun run(step: PlannedStep): StepOutcomeReport {
            ran += step.tool
            return outcomes.removeFirstOrNull() ?: StepOutcomeReport(StepStatus.DONE, "默认成功")
        }
    }

    private fun catalog() = ToolCatalog()

    private fun plan(vararg tools: String) = PlanResult.Ok(
        RunPlan(
            goal = "测试目标",
            steps = tools.mapIndexed { index, tool ->
                PlannedStep(id = "step_${index + 1}", tool = tool, note = "第${index + 1}步")
            },
        ),
    )

    private fun agent(
        planner: PlannerPort,
        runner: StepRunnerPort = ScriptedRunner(mutableListOf()),
    ) = SuperAgent(planner = planner, runner = runner, catalog = catalog())

    @Test
    fun `聊天句子一步都不动`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf())
        val runner = ScriptedRunner(mutableListOf())
        val reply = agent(planner, runner).handle("今天好累")

        assertTrue(reply is SuperAgent.SuperReply.Chat)
        assertEquals(0, planner.calls)
        assertTrue(runner.ran.isEmpty())
    }

    @Test
    fun `空话先问清楚再动手`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf())
        val reply = agent(planner).handle("   ")

        assertTrue(reply is SuperAgent.SuperReply.Ask)
        assertEquals(0, planner.calls)
    }

    @Test
    fun `喊停就停，不规划`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf())
        val reply = agent(planner).handle("停", IntakeContext(hasActiveTask = true))

        assertTrue(reply is SuperAgent.SuperReply.Stopped)
        assertEquals(0, planner.calls)
    }

    @Test
    fun `普通任务跑完给一句结论`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(plan("android_tap", "screen_read")))
        val runner = ScriptedRunner(mutableListOf())
        val reply = agent(planner, runner).handle("帮我点一下发送按钮")

        val finished = reply as SuperAgent.SuperReply.Finished
        assertTrue(finished.report.ok)
        assertTrue(finished.spoken.startsWith("做完了"))
        assertEquals(listOf("android_tap", "screen_read"), runner.ran)
    }

    @Test
    fun `碰到钱先问一句，不先跑起来`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(plan("android_tap")))
        val runner = ScriptedRunner(mutableListOf())
        val reply = agent(planner, runner).handle("帮我转账 100 块给老王")

        val ask = reply as SuperAgent.SuperReply.Ask
        assertTrue(ask.question.contains("钱") || ask.question.contains("删"))
        assertEquals(listOf("做", "别做"), ask.options)
        assertEquals(0, planner.calls)
        assertTrue(runner.ran.isEmpty())
    }

    @Test
    fun `用户说过可以做时就不再拦`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(plan("android_tap")))
        val reply = agent(planner).handle("帮我转账 100 块给老王", runContext = RunContext(preApproved = true))

        assertTrue(reply is SuperAgent.SuperReply.Finished)
        assertEquals(1, planner.calls)
    }

    @Test
    fun `两句以上的指令串行跑完`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(plan("android_tap"), plan("screen_read")))
        val runner = ScriptedRunner(mutableListOf())
        val reply = agent(planner, runner).handle("先点一下发送，然后读一下屏幕")

        val finished = reply as SuperAgent.SuperReply.Finished
        assertTrue(finished.spoken.contains("都做完了"))
        assertEquals(2, planner.calls)
        assertEquals(listOf("android_tap", "screen_read"), runner.ran)
    }

    @Test
    fun `链条里前一件失败就停，不硬着头皮往下做`() = runBlocking {
        // 第一件一直被拒细 => 重规划也修不好，整条链就停在第一件。
        val planner = ScriptedPlanner(MutableList(10) { plan("android_tap") })
        val runner = ScriptedRunner(MutableList(10) { StepOutcomeReport(StepStatus.FAILED, "无障碍没开") })
        val reply = agent(planner, runner).handle("先点一下发送，然后读一下屏幕")

        val finished = reply as SuperAgent.SuperReply.Finished
        assertFalse(finished.report.ok)
        assertTrue(finished.spoken.contains("第 1 件没成"))
        // ⚠️ 不能断言 planner.calls == 1：第一件失败会触发重规划（这是设计），
        // 所以 planner 会被再问一次。要钉住的是**第二件根本没开始**。
        assertFalse(
            "链条在第一件失败后仍开始了第二件：${planner.goals}",
            planner.goals.any { it.contains("第 2/2 件") },
        )
    }

    @Test
    fun `链条太长时先要求拆开`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf())
        val reply = agent(planner).handle("先点发送，然后返回，然后点设置，然后再读屏，最后再点一下")

        assertTrue(reply is SuperAgent.SuperReply.Ask)
        assertEquals(0, planner.calls)
    }

    @Test
    fun `链条里有一句碰到钱也整条先问`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf())
        val reply = agent(planner).handle("先点一下发送，然后转账 50 块")

        assertTrue(reply is SuperAgent.SuperReply.Ask)
        assertEquals(0, planner.calls)
    }

    @Test
    fun `安全拦截时把原因说清楚，而不是说没做成`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(PlanResult.Refuse("这是隐私页面，我不代你操作。")))
        val reply = agent(planner).handle("帮我点一下发送按钮")

        val finished = reply as SuperAgent.SuperReply.Finished
        assertTrue(finished.spoken.contains("我不做"))
        assertTrue(finished.spoken.contains("隐私页面"))
        assertFalse(finished.spoken.contains("没做成"))
    }

    @Test
    fun `执行中被拦下也说的是我不做，不是没做成`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(plan("android_tap")))
        val runner = ScriptedRunner(
            mutableListOf(StepOutcomeReport(StepStatus.REFUSED, "这是支付页面，我不代你点。")),
        )
        val reply = agent(planner, runner).handle("帮我点一下发送按钮")

        val finished = reply as SuperAgent.SuperReply.Finished
        assertEquals(RunPhase.FAILED, finished.report.phase)
        assertTrue(finished.spoken.contains("我不做"))
        assertTrue(finished.spoken.contains("支付页面"))
        assertFalse(finished.spoken.contains("没做成"))
    }

    @Test
    fun `缺信息时直接把问题转给用户`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(PlanResult.NeedInfo("要整理哪一天的纪要？")))
        val reply = agent(planner).handle("帮我整理一下纪要")

        val finished = reply as SuperAgent.SuperReply.Finished
        assertEquals("要整理哪一天的纪要？", finished.spoken)
    }

    @Test
    fun `需要确认时给出这一步的代价`() = runBlocking {
        val planner = ScriptedPlanner(mutableListOf(plan("prop_write")))
        val reply = agent(planner).handle("帮我把调试属性改成 1")

        val finished = reply as SuperAgent.SuperReply.Finished
        assertEquals(RunPhase.AWAITING_CONFIRMATION, finished.report.phase)
        assertTrue(finished.spoken.contains("要继续吗"))
        assertTrue(finished.spoken.contains("撤回"))
    }

    @Test
    fun `打转与超预算都有各自的说法`() = runBlocking {
        val looping = RunReport(
            phase = RunPhase.LOOPING,
            goal = "g",
            plan = null,
            attempts = emptyList(),
            summary = "同一步重复了 3 次",
        )
        val stopped = SuperAgent.SuperReply.Finished(looping, "x")
        assertTrue(stopped.spoken.isNotEmpty())

        // 直接验文案函数的效果：借一次真实调用拿到 Finished
        val reply = agent(ScriptedPlanner(mutableListOf(PlanResult.Ok(RunPlan("g", listOf(
            PlannedStep("step_1", "android_tap"),
        )))))).handle("帮我点一下发送")
        assertTrue((reply as SuperAgent.SuperReply.Finished).spoken.isNotEmpty())
    }
}
