package com.pocketagent.assistant

import com.pocketagent.agentlogic.orchestration.Expectation
import com.pocketagent.agentlogic.orchestration.PlannedStep
import com.pocketagent.agentlogic.orchestration.StepEffect
import com.pocketagent.agentlogic.orchestration.StepStatus
import com.pocketagent.mcp.McpTool
import com.pocketagent.mcp.ToolOutcome
import com.pocketagent.mcp.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作之后的核对结论由 runner 产出。
 *
 * 这一层是"agent 到底知不知道自己在干什么"的分水岭：
 * 没核对的 agent 只能相信模型说"我点了"，而有核对的 agent 能说"我点了，界面没变"。
 */
class McpStepRunnerEffectTest {

    private class FakeTool(
        override val name: String,
        private val outcome: ToolOutcome,
    ) : McpTool {
        override val description: String = "测试用假工具"
        var calls = 0

        override fun inputSchema(): JsonObject = buildJsonObject { put("type", "object") }

        override suspend fun call(arguments: JsonObject): ToolOutcome {
            calls++
            return outcome
        }
    }

    private fun screen(texts: List<String>, clickable: Int = 2): ToolOutcome.Success =
        ToolOutcome.Success(
            "【屏幕快照】",
            structured = buildJsonObject {
                put("package", "com.tencent.mm")
                put("nodeCount", clickable)
                put(
                    "nodes",
                    Json.parseToJsonElement(
                        texts.joinToString(",", "[", "]") { "{\"text\":\"$it\",\"clickable\":true}" },
                    ),
                )
            },
        )

    private fun step(
        tool: String = "android_tap",
        expect: Expectation = Expectation.None,
    ) = PlannedStep(id = "step_1", tool = tool, expect = expect, note = "点一下")

    private fun runner(tap: FakeTool, reader: FakeTool) =
        McpStepRunner({ ToolRegistry(listOf(tap, reader)) })

    @Test
    fun `屏上动作即使没写验收也会读一次屏`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Success("已派发"))
        val reader = FakeTool("screen_read", screen(listOf("首页")))
        val report = runner(tap, reader).run(step())

        assertEquals(StepStatus.DONE, report.status)
        assertEquals(1, reader.calls)
        assertEquals(StepEffect.EFFECT, report.effect)
    }

    @Test
    fun `验收条件没满足就是没变化`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Success("已派发"))
        val reader = FakeTool("screen_read", screen(listOf("首页")))
        val report = runner(tap, reader).run(step(expect = Expectation.TextVisible("已发送")))

        assertEquals(StepEffect.NO_EFFECT, report.effect)
        assertTrue(report.detail.contains("已发送"))
    }

    @Test
    fun `读不到屏幕时是看不见界面，不是没变化`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Success("已派发"))
        val reader = FakeTool("screen_read", ToolOutcome.Failed("无障碍没开"))
        val report = runner(tap, reader).run(step(expect = Expectation.TextVisible("已发送")))

        assertEquals(StepEffect.TREE_BLIND, report.effect)
        assertTrue(report.detail.contains("无法核对"))
    }

    @Test
    fun `屏上出现限额文字时立刻判限额并保留原文`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Success("已派发"))
        val reader = FakeTool("screen_read", screen(listOf("该操作过于频繁，请 5 分钟后再试")))
        val report = runner(tap, reader).run(step())

        assertEquals(StepEffect.LIMIT_REACHED, report.effect)
        assertTrue(report.detail.contains("过于频繁"))
    }

    @Test
    fun `不动屏幕的工具不额外读屏，直接算有变化`() = runBlocking {
        val write = FakeTool("setting_write", ToolOutcome.Success("已执行"))
        val reader = FakeTool("screen_read", screen(listOf("首页")))
        val report = runner(write, reader).run(step(tool = "setting_write"))

        assertEquals(StepEffect.EFFECT, report.effect)
        assertEquals(0, reader.calls)
    }
}
