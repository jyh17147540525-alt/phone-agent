package com.pocketagent.agentlogic.orchestration

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器的测试 —— 全部用剧本演，不需要真机。
 *
 * 这里每一条都对应真机上的一种失败：页面上其实没有那段文字、点了没反应、
 * 权限被撤、模型打转、用户按停。执行器的价值不在"顺利时能跑完"，
 * 而在**这些情况下它的结论是对的**。
 */
class RunOrchestratorTest {

    // ── 剧本 ────────────────────────────────────────────────────────────────

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

    private fun step(id: String, tool: String = "android_tap", note: String = "点一下", expect: Expectation = Expectation.None) =
        PlannedStep(id = id, tool = tool, args = mapOf("text" to id), expect = expect, note = note)

    private fun done(digest: ScreenDigest = ScreenDigest.EMPTY) = StepOutcomeReport(StepStatus.DONE, "ok", digest)

    private fun okReport(digest: ScreenDigest = ScreenDigest.EMPTY) = PlanResult.Ok(
        RunPlan("打开微信并给张三发消息", listOf(step("step_1"), step("step_2"))),
    )

    // ── 正常路径 ────────────────────────────────────────────────────────────

    @Test
    fun `两步都成功就报完成`() = runBlocking {
        val planner = ScriptedPlanner(okReport())
        val runner = ScriptedRunner(done(), done())
        val report = RunOrchestrator(planner, runner).run("打开微信并给张三发消息")

        assertEquals(RunPhase.DONE, report.phase)
        assertTrue(report.ok)
        assertEquals(2, report.steps)
        assertEquals(2, runner.steps.size)
        assertEquals(1, planner.requests.size)
        assertTrue(report.summary.contains("验收"))
    }

    @Test
    fun `验收要么通过要么算失败`() = runBlocking {
        val expect = Expectation.TextVisible("张三")
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("给张三发消息", listOf(step("step_1", expect = expect)))),
            PlanResult.Ok(RunPlan("给张三发消息", listOf(step("step_2", expect = Expectation.None)))),
        )
        // 第一次：动作成功，但屏幕上没有「张三」→ 验收失败
        val runner = ScriptedRunner(done(ScreenDigest(texts = listOf("通讯录"))), done())
        val report = RunOrchestrator(planner, runner).run("给张三发消息")

        assertEquals(RunPhase.DONE, report.phase)
        val first = report.attempts.first()
        assertEquals(StepStatus.FAILED, first.status)
        assertEquals(false, first.verified)
        assertTrue(first.detail.contains("验收没过"))
        assertEquals(2, planner.requests.size)
        assertTrue("重规划要带上失败原因", planner.requests[1].previousFailure!!.contains("张三"))
    }

    // ── 被拦下 ──────────────────────────────────────────────────────────────

    @Test
    fun `被安全闸门拦下是最终结论且不重规划`() = runBlocking {
        val planner = ScriptedPlanner(PlanResult.Ok(RunPlan("改设置", listOf(step("step_1", tool = "setting_write")))))
        val runner = ScriptedRunner(StepOutcomeReport(StepStatus.REFUSED, "这一步会改系统设置，需要你点一下"))
        val report = RunOrchestrator(planner, runner).run("改设置")

        assertEquals(RunPhase.FAILED, report.phase)
        assertEquals("绕路等于换个写法撞同一道闸", 1, planner.requests.size)
        assertTrue(report.summary.contains("拦下"))
        assertTrue(report.summary.contains("不是写法问题"))
    }

    // ── 确认闸 ──────────────────────────────────────────────────────────────

    @Test
    fun `有后果的不可逆步骤要用户先点过`() = runBlocking {
        // prop_write 会改系统属性而且撤不回来 —— 这是确认闸该拦的那一类
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("改属性", listOf(step("step_1", tool = "prop_write", note = "改机型伪装")))),
        )
        val runner = ScriptedRunner()
        val report = RunOrchestrator(planner, runner).run("改属性")

        assertEquals(RunPhase.AWAITING_CONFIRMATION, report.phase)
        assertNotNull(report.awaitingStep)
        assertEquals("step_1", report.awaitingStep!!.id)
        assertEquals(0, runner.steps.size)
        assertTrue(report.summary.contains("改机型伪装"))
    }

    @Test
    fun `点屏幕本身不撞确认闸`() = runBlocking {
        // 每一步都要确认的 agent 等于没有自主度：用户会去关掉确认，危险动作也没人看了
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("连点三次", listOf(step("step_1"), step("step_2"), step("step_3")))),
        )
        val runner = ScriptedRunner(done(), done(), done())
        val report = RunOrchestrator(planner, runner).run("连点三次")
        assertEquals(RunPhase.DONE, report.phase)
    }

    @Test
    fun `整趟放行就直接做`() = runBlocking {
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("改属性", listOf(step("step_1", tool = "prop_write")))),
        )
        val runner = ScriptedRunner(done())
        val report = RunOrchestrator(planner, runner).run("改属性", RunContext(preApproved = true))
        assertEquals(RunPhase.DONE, report.phase)
    }

    @Test
    fun `只放行指定的那一步`() = runBlocking {
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("改属性", listOf(step("step_1", tool = "prop_write")))),
        )
        val runner = ScriptedRunner(done())
        val report = RunOrchestrator(planner, runner).run(
            "改属性",
            RunContext(confirmedStepIds = setOf("step_1")),
        )
        assertEquals(RunPhase.DONE, report.phase)
    }

    // ── 预算 ────────────────────────────────────────────────────────────────

    @Test
    fun `步数用完就停`() = runBlocking {
        val plan = RunPlan("三步", listOf(step("step_1"), step("step_2"), step("step_3")))
        val planner = ScriptedPlanner(PlanResult.Ok(plan))
        val runner = ScriptedRunner(done(), done(), done())
        val report = RunOrchestrator(planner, runner, budget = RunBudget(maxSteps = 2)).run("三步")

        assertEquals(RunPhase.OUT_OF_BUDGET, report.phase)
        assertEquals(2, report.steps)
        assertEquals(2, runner.steps.size)
        assertTrue(report.summary.contains("预算上限"))
    }

    @Test
    fun `时间用完就停`() = runBlocking {
        var now = 0L
        val plan = RunPlan("三步", listOf(step("step_1"), step("step_2"), step("step_3")))
        val planner = ScriptedPlanner(PlanResult.Ok(plan))
        val runner = object : StepRunnerPort {
            override suspend fun run(step: PlannedStep): StepOutcomeReport {
                now += 800
                return done()
            }
        }
        val report = RunOrchestrator(
            planner,
            runner,
            budget = RunBudget(maxDurationMs = 1_500),
            clock = { now },
        ).run("三步")

        assertEquals(RunPhase.OUT_OF_BUDGET, report.phase)
        assertEquals(2, report.steps)
    }

    @Test
    fun `时钟走过头会在下一步之前停下`() = runBlocking {
        var now = 0L
        val plan = RunPlan("两步", listOf(step("step_1"), step("step_2")))
        val planner = ScriptedPlanner(PlanResult.Ok(plan))
        // 每步耗时 2 秒，预算 1 秒
        val runner = object : StepRunnerPort {
            override suspend fun run(step: PlannedStep): StepOutcomeReport {
                now += 2_000
                return done()
            }
        }
        val report = RunOrchestrator(
            planner,
            runner,
            budget = RunBudget(maxDurationMs = 1_000),
            clock = { now },
        ).run("两步")

        assertEquals(RunPhase.OUT_OF_BUDGET, report.phase)
        assertEquals(1, report.steps)
    }

    // ── 重试 ────────────────────────────────────────────────────────────────

    @Test
    fun `超时且幂等才重试`() = runBlocking {
        val planner = ScriptedPlanner(PlanResult.Ok(RunPlan("读屏", listOf(step("step_1", tool = "screen_read")))))
        val runner = ScriptedRunner(
            StepOutcomeReport(StepStatus.TIMEOUT, "读屏超时"),
            done(ScreenDigest(texts = listOf("微信"))),
        )
        val report = RunOrchestrator(planner, runner).run("读屏")

        assertEquals(RunPhase.DONE, report.phase)
        assertEquals(2, runner.steps.size)
        assertEquals(1, report.attempts.first().retries)
    }

    @Test
    fun `超时但不幂等就不重试`() = runBlocking {
        // android_tap 不幂等（点两次就是双击）
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("点一下", listOf(step("step_1", tool = "android_tap")))),
            PlanResult.Ok(RunPlan("点一下", listOf(step("step_2", tool = "android_tap")))),
            PlanResult.Ok(RunPlan("点一下", listOf(step("step_3", tool = "android_tap")))),
        )
        val runner = ScriptedRunner(
            StepOutcomeReport(StepStatus.TIMEOUT, "点按超时"),
            StepOutcomeReport(StepStatus.FAILED, "还是没有反应"),
            StepOutcomeReport(StepStatus.FAILED, "第三次仍失败"),
        )
        val report = RunOrchestrator(planner, runner, budget = RunBudget(maxReplans = 1)).run("点一下")

        assertEquals(RunPhase.FAILED, report.phase)
        // 超时那一步不重试（tap 不幂等）→ 第 1 次调用；重规划后第 2 次调用失败即收手
        assertEquals(2, runner.steps.size)
        assertEquals(0, report.attempts.first().retries)
    }

    // ── 打转与放弃 ──────────────────────────────────────────────────────────

    @Test
    fun `同一步骤签名反复出现判为打转`() = runBlocking {
        val sameStep = step("step_1", tool = "screen_read")
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("读屏", listOf(sameStep))),
            PlanResult.Ok(RunPlan("读屏", listOf(sameStep))),
            PlanResult.Ok(RunPlan("读屏", listOf(sameStep))),
        )
        val runner = ScriptedRunner(
            StepOutcomeReport(StepStatus.FAILED, "读不到"),
            StepOutcomeReport(StepStatus.FAILED, "读不到"),
            StepOutcomeReport(StepStatus.FAILED, "读不到"),
        )
        val report = RunOrchestrator(planner, runner, budget = RunBudget(maxRepeats = 3)).run("读屏")

        assertEquals(RunPhase.LOOPING, report.phase)
        assertTrue(report.summary.contains("打转"))
    }

    @Test
    fun `重规划次数用完就带失败提示收手`() = runBlocking {
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("改属性", listOf(step("step_1", tool = "prop_write")))),
            PlanResult.Ok(RunPlan("改属性", listOf(step("step_2", tool = "prop_write")))),
            PlanResult.Ok(RunPlan("改属性", listOf(step("step_3", tool = "prop_write")))),
        )
        val runner = ScriptedRunner(
            StepOutcomeReport(StepStatus.FAILED, "拒绝：属性不存在"),
            StepOutcomeReport(StepStatus.FAILED, "拒绝：属性不存在"),
            StepOutcomeReport(StepStatus.FAILED, "拒绝：属性不存在"),
        )
        val report = RunOrchestrator(planner, runner, budget = RunBudget(maxReplans = 2))
            .run("改属性", RunContext(preApproved = true))

        assertEquals(RunPhase.FAILED, report.phase)
        // 失败提示来自 ToolSpec.failureHint —— 不能只说"失败了"
        assertTrue(report.summary.contains("getprop"))
    }

    // ── 其它结局 ────────────────────────────────────────────────────────────

    @Test
    fun `规划器拒绝就是不做`() = runBlocking {
        val planner = ScriptedPlanner(PlanResult.Refuse("这件事我不能做：它要求绕过另一台设备的鉴权"))
        val runner = ScriptedRunner()
        val report = RunOrchestrator(planner, runner).run("黑进别人的手机")

        assertEquals(RunPhase.REFUSED, report.phase)
        assertTrue(report.summary.contains("绕过"))
        assertEquals(0, runner.steps.size)
    }

    @Test
    fun `规划器缺信息就问一句`() = runBlocking {
        val planner = ScriptedPlanner(PlanResult.NeedInfo("发给谁？"))
        val report = RunOrchestrator(planner, ScriptedRunner()).run("发个消息")
        assertEquals(RunPhase.NEED_INFO, report.phase)
        assertEquals("发给谁？", report.question)
    }

    @Test
    fun `计划里出现不存在的工具要直接指出来`() = runBlocking {
        val planner = ScriptedPlanner(
            PlanResult.Ok(RunPlan("随便", listOf(step("step_1", tool = "magic_wand")))),
        )
        val report = RunOrchestrator(planner, ScriptedRunner()).run("随便")
        assertEquals(RunPhase.FAILED, report.phase)
        assertTrue(report.summary.contains("不存在的工具"))
    }

    @Test
    fun `用户叫停立刻停`() = runBlocking {
        val planner = ScriptedPlanner(okReport())
        val runner = ScriptedRunner(done(), done())
        val report = RunOrchestrator(planner, runner).run("随便", RunContext(isStopped = { true }))
        assertEquals(RunPhase.STOPPED, report.phase)
        assertEquals(0, runner.steps.size)
        assertTrue(report.summary.contains("叫停"))
    }

    // ── 数据结构自检 ────────────────────────────────────────────────────────

    @Test
    fun `空计划与空目标都会被拒`() {
        var threw = false
        try {
            RunPlan("", emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)

        threw = false
        try {
            RunPlan("有目标", emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("空计划该被拒 —— 空计划应当由规划器返回 Refuse", threw)
    }

    @Test
    fun `步骤签名与参数顺序无关`() {
        val a = PlannedStep("s", "android_tap", mapOf("a" to "1", "b" to "2"))
        val b = PlannedStep("s", "android_tap", mapOf("b" to "2", "a" to "1"))
        assertEquals(a.signature, b.signature)
    }

    @Test
    fun `尝试记录里能看出验收没通过`() {
        val attempt = StepAttempt(
            stepId = "step_1",
            tool = "android_tap",
            signature = "android_tap|text=x",
            status = StepStatus.FAILED,
            detail = "动作发出去了，但验收没过：屏幕上没有「张三」",
            elapsedMs = 1_200,
            retries = 0,
            verified = false,
        )
        assertTrue(attempt.describe().contains("验收没通过"))
        assertFalse(attempt.describe().contains("重试"))
        assertNull(null)
    }
}
