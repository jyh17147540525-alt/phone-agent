package com.pocketagent.agentlogic.orchestration

import com.pocketagent.agentlogic.AgentLlmPort
import com.pocketagent.agentlogic.AgentTurnReply
import com.pocketagent.agentlogic.AgentTurnRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelPlanner] 的解析与兜底。
 *
 * 这里钉的不是"模型会不会写对"，而是**模型写错时我们怎么反应**：
 * 猜一个计划去点用户手机是最坏的失败形态，所以取不到合法计划时一律停下来问人。
 */
class ModelPlannerTest {

    private val catalog = ToolCatalog()

    private fun request(
        goal: String = "把会议纪要整理成表格",
        previousFailure: String? = null,
        budgetLeft: Int = 0,
    ) = PlanRequest(goal = goal, catalog = catalog, previousFailure = previousFailure, budgetLeft = budgetLeft)

    private class FakeLlm(private val answer: String) : AgentLlmPort {
        var lastRequest: AgentTurnRequest? = null
        override suspend fun complete(request: AgentTurnRequest): AgentTurnReply {
            lastRequest = request
            return AgentTurnReply.Final(answer)
        }
    }

    @Test
    fun `正常计划被逐字翻译成步骤`() = runBlocking {
        val json = """
            {"plan":[
              {"tool":"android_tap","args":{"text":"发送"},"expect":{"text":"已发送"},"note":"点发送"},
              {"tool":"screen_read","args":{},"expect":{"atLeastNodes":3}}
            ]}
        """.trimIndent()
        val result = ModelPlanner(FakeLlm(json)).plan(request())

        val plan = (result as PlanResult.Ok).plan
        assertEquals(2, plan.steps.size)
        assertEquals(listOf("step_1", "step_2"), plan.steps.map { it.id })
        assertEquals("android_tap", plan.steps[0].tool)
        assertEquals(mapOf("text" to "发送"), plan.steps[0].args)
        assertEquals(Expectation.TextVisible("已发送"), plan.steps[0].expect)
        assertEquals("点发送", plan.steps[0].note)
        assertEquals(Expectation.AtLeastNodes(3), plan.steps[1].expect)
        assertEquals(plan.goal, "把会议纪要整理成表格")
    }

    @Test
    fun `代码块与前后解释都能容忍`() = runBlocking {
        val answer = """
            好的，计划如下：
            ```json
            {"plan":[{"tool":"android_tap","args":{"text":"确定"}}]}
            ```
            需要我继续吗？
        """.trimIndent()
        val result = ModelPlanner(FakeLlm(answer)).plan(request())

        val plan = (result as PlanResult.Ok).plan
        assertEquals(1, plan.steps.size)
        assertEquals(Expectation.None, plan.steps[0].expect)
    }

    @Test
    fun `模型说做不到时原样转达`() = runBlocking {
        val result = ModelPlanner(FakeLlm("""{"refuse":"这件事需要你的密码，我不代你输。"}""")).plan(request())
        assertEquals("这件事需要你的密码，我不代你输。", (result as PlanResult.Refuse).reason)
    }

    @Test
    fun `模型要问信息时把问题交给用户`() = runBlocking {
        val result = ModelPlanner(FakeLlm("""{"need":"要整理哪一天的纪要？"}""")).plan(request())
        assertEquals("要整理哪一天的纪要？", (result as PlanResult.NeedInfo).question)
    }

    @Test
    fun `连不上模型时停下来问人，而不是自己编计划`() = runBlocking {
        val llm = AgentLlmPort { throw java.io.IOException("boom") }
        val result = ModelPlanner(llm).plan(request())

        assertTrue(result is PlanResult.NeedInfo)
        assertTrue((result as PlanResult.NeedInfo).question.contains("没连上模型"))
    }

    @Test
    fun `空输出与纯文字都不是计划`() = runBlocking {
        listOf("", "我觉得应该先打开设置", "{}").forEach { answer ->
            val result = ModelPlanner(FakeLlm(answer)).plan(request())
            assertTrue("『$answer』应判成需要再问一次，实际 $result", result is PlanResult.NeedInfo)
        }
    }

    @Test
    fun `用了不存在的工具就拒绝整条计划`() = runBlocking {
        val json = """{"plan":[{"tool":"android_fly","args":{}}]}"""
        val result = ModelPlanner(FakeLlm(json)).plan(request())

        assertTrue(result is PlanResult.Refuse)
        assertTrue((result as PlanResult.Refuse).reason.contains("android_fly"))
    }

    @Test
    fun `步骤超过上限要求先给最要紧的一段`() = runBlocking {
        val json = """{"plan":[${(1..5).joinToString(",") { "{\"tool\":\"android_tap\",\"args\":{\"x\":\"$it\"}}" }}]}"""
        val result = ModelPlanner(FakeLlm(json), maxSteps = 4).plan(request())

        assertTrue(result is PlanResult.Refuse)
        assertTrue((result as PlanResult.Refuse).reason.contains("最要紧"))
    }

    @Test
    fun `空计划数组当作还没想清楚`() = runBlocking {
        val result = ModelPlanner(FakeLlm("""{"plan":[]}""")).plan(request())
        assertTrue(result is PlanResult.NeedInfo)
    }

    @Test
    fun `上一次的失败原因与剩余预算都进提示词`() = runBlocking {
        val llm = FakeLlm("""{"plan":[{"tool":"android_tap","args":{}}]}""")
        ModelPlanner(llm).plan(request(previousFailure = "读屏超时", budgetLeft = 3))

        val prompt = llm.lastRequest!!.messages.joinToString("\n") { it.text }
        assertTrue(prompt.contains("读屏超时"))
        assertTrue(prompt.contains("只剩 3 步"))
        assertTrue(prompt.contains("android_tap"))
    }

    @Test
    fun `expect 的四个变体都能落到对应的验收条件`() = runBlocking {
        val json = """
            {"plan":[
              {"tool":"android_tap","expect":{"packageName":"com.tencent.mm"}},
              {"tool":"android_tap","expect":{"key":"title","value":"周报"}},
              {"tool":"android_tap","expect":{"text":"完成"}}
            ]}
        """.trimIndent()
        val plan = (ModelPlanner(FakeLlm(json)).plan(request()) as PlanResult.Ok).plan

        assertEquals(Expectation.PackageIs("com.tencent.mm"), plan.steps[0].expect)
        assertEquals(Expectation.ValueIs("title", "周报"), plan.steps[1].expect)
        assertEquals(Expectation.TextVisible("完成"), plan.steps[2].expect)
    }

    @Test
    fun `模型直接调工具时退回它附带的文本`() = runBlocking {
        val llm = AgentLlmPort { AgentTurnReply.Calls(calls = emptyList(), text = """{"plan":[{"tool":"screen_read","args":{}}]}""") }
        val result = ModelPlanner(llm).plan(request())

        assertEquals(1, (result as PlanResult.Ok).plan.steps.size)
    }

    @Test
    fun `数字参数被转成字符串而不是丢掉`() = runBlocking {
        val json = """{"plan":[{"tool":"android_key","args":{"key":"BACK","count":3}}]}"""
        val plan = (ModelPlanner(FakeLlm(json)).plan(request()) as PlanResult.Ok).plan

        assertEquals("3", plan.steps[0].args["count"])
        assertFalse(plan.steps[0].args.containsKey("missing"))
    }
}
