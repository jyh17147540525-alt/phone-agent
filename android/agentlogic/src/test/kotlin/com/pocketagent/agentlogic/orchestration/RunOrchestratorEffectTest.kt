package com.pocketagent.agentlogic.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作之后的核对结论（[StepEffect]）怎么影响任务走向。
 *
 * 三种"看起来成功"的假象各对应一条规则：
 * - 点了没反应（[StepEffect.NO_EFFECT]）→ 当失败重规划，不许往下走；
 * - 看不见界面（[StepEffect.TREE_BLIND]）→ 当"未核对"继续，不许判失败重试到天亮；
 * - 被平台限额（[StepEffect.LIMIT_REACHED]）→ 立刻停，并告诉用户重试没用。
 */
class RunOrchestratorEffectTest {

    private class ScriptedPlanner(vararg results: PlanResult) : PlannerPort {
        val requests = mutableListOf<PlanRequest>()
        private val queue = ArrayDeque(results.toList())
        override suspend fun plan(request: PlanRequest): PlanResult {
            requests += request
            return queue.removeFirst()
        }
    }

    private class ScriptedRunner(vararg outcomes: StepOutcomeReport) : StepRunnerPort {
        val steps = mutableListOf<PlannedStep>()
        private val queue = ArrayDeque(outcomes.toList())
        override suspend fun run(step: PlannedStep): StepOutcomeReport {
            steps += step
            return queue.removeFirst()
        }
    }

    private fun step(id: String = "step_1", tool: String = "android_tap") =
        PlannedStep(id = id, tool = tool, args = mapOf("text" to id), note = "点一下")

    private fun plan(vararg steps: PlannedStep) = PlanResult.Ok(RunPlan("试试", steps.toList()))

    @Test
    fun `点了没反应要当失败去重规划，而不是当成功`() = runBlocking {
        val planner = ScriptedPlanner(
            plan(step()),
            plan(step("step_2")),
        )
        val runner = ScriptedRunner(
            StepOutcomeReport(StepStatus.DONE, "ok", ScreenDigest(texts = listOf("首页")), StepEffect.NO_EFFECT),
            StepOutcomeReport(StepStatus.DONE, "ok", ScreenDigest(texts = listOf("首页")), StepEffect.EFFECT),
        )
        val report = RunOrchestrator(planner, runner).run("试试")

        assertEquals(RunPhase.DONE, report.phase)
        assertEquals(2, planner.requests.size)
        val first = report.attempts.first()
        assertEquals(StepStatus.FAILED, first.status)
        assertTrue(first.detail.contains("没有变化"))
        assertEquals(false, first.verified)
    }

    @Test
    fun `看不见界面时不算失败，标成未核对继续`() = runBlocking {
        val planner = ScriptedPlanner(plan(step(), step("step_2")))
        val runner = ScriptedRunner(
            StepOutcomeReport(StepStatus.DONE, "读不到界面", null, StepEffect.TREE_BLIND),
            StepOutcomeReport(StepStatus.DONE, "ok", ScreenDigest(texts = listOf("首页")), StepEffect.EFFECT),
        )
        val report = RunOrchestrator(planner, runner).run("试试")

        assertEquals(RunPhase.DONE, report.phase)
        assertEquals(1, planner.requests.size)
        assertNull("看不见界面时不能判成未通过", report.attempts.first().verified)
        assertEquals(StepStatus.DONE, report.attempts.first().status)
        // 剧本给的 detail 会被原样保留（只有空的时候编排层才补一句）
        assertTrue(report.attempts.first().detail.contains("读不到界面"))
    }

    @Test
    fun `被限额立刻停，并且不重试不重规划`() = runBlocking {
        val planner = ScriptedPlanner(plan(step(), step("step_2")))
        val runner = ScriptedRunner(
            StepOutcomeReport(
                StepStatus.DONE,
                "今日已达上限，明天再来",
                ScreenDigest(texts = listOf("今日已达上限，明天再来")),
                StepEffect.LIMIT_REACHED,
            ),
        )
        val report = RunOrchestrator(planner, runner).run("试试")

        assertEquals(RunPhase.LIMIT_REACHED, report.phase)
        assertEquals("重试一定没用，所以只规划过一次", 1, planner.requests.size)
        assertEquals(1, runner.steps.size)
        assertNotNull(report.limit)
        assertTrue(report.limit!!.contains("今日已达上限"))
        assertTrue(report.summary.contains("重试没用"))
    }

    @Test
    fun `限额发生时把这一步也记进尝试记录，用户能看到停在哪`() = runBlocking {
        val planner = ScriptedPlanner(plan(step()))
        val runner = ScriptedRunner(
            StepOutcomeReport(StepStatus.DONE, "操作过于频繁", ScreenDigest(texts = listOf("操作过于频繁")), StepEffect.LIMIT_REACHED),
        )
        val report = RunOrchestrator(planner, runner).run("试试")

        assertEquals(1, report.attempts.size)
        assertTrue(report.attempts.first().detail.contains("操作过于频繁"))
    }

    @Test
    fun `默认效果是有变化，老调用方不受影响`() {
        // StepOutcomeReport 的 effect 有默认值：没做核对的一方不能被当成"没变化"
        val report = StepOutcomeReport(StepStatus.DONE, "ok")
        assertEquals(StepEffect.EFFECT, report.effect)
    }
}
