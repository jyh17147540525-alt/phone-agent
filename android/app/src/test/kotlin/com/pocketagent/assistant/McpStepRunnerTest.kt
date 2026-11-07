package com.pocketagent.assistant

import com.pocketagent.agentlogic.orchestration.Expectation
import com.pocketagent.agentlogic.orchestration.PlannedStep
import com.pocketagent.agentlogic.orchestration.StepStatus
import com.pocketagent.mcp.McpTool
import com.pocketagent.mcp.ToolOutcome
import com.pocketagent.mcp.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一步 → 一次真实工具调用 → 一份可验收的报告。
 *
 * 钉住的是**分工**：工具只说"派发了"，runner 负责"再读一次屏幕看看成了没有"。
 * 少了这一步，整条链路就退化成"相信模型说它做完了"。
 */
class McpStepRunnerTest {

    private class FakeTool(
        override val name: String,
        private val outcome: ToolOutcome,
        private val onCall: (JsonObject) -> ToolOutcome = { outcome },
    ) : McpTool {
        override val description: String = "测试用假工具"
        var calls = 0
        var lastArgs: JsonObject? = null

        override fun inputSchema(): JsonObject = buildJsonObject { put("type", "object") }

        override suspend fun call(arguments: JsonObject): ToolOutcome {
            calls++
            lastArgs = arguments
            return onCall(arguments)
        }
    }

    private fun step(
        tool: String,
        args: Map<String, String> = emptyMap(),
        expect: Expectation = Expectation.None,
    ) = PlannedStep(id = "step_1", tool = tool, args = args, expect = expect)

    private fun registry(vararg tools: FakeTool) = ToolRegistry(tools.toList())

    @Test
    fun `不动屏幕的工具没有验收条件时不额外读屏`() = runBlocking {
        // ⚠️ 屏上动作**会**读一次屏（见 McpStepRunnerEffectTest）：那是"点了没反应"的唯一判据。
        //    这里用 setting_write 钉住另一半：不动屏幕的工具不该为核对付 300ms。
        val write = FakeTool("setting_write", ToolOutcome.Success("已执行"))
        val reader = FakeTool("screen_read", ToolOutcome.Success("不该被调用"))
        val report = McpStepRunner({ registry(write, reader) }).run(step("setting_write"))

        assertEquals(StepStatus.DONE, report.status)
        assertEquals(1, write.calls)
        assertEquals(0, reader.calls)
        assertNull(report.digest)
    }

    @Test
    fun `带验收条件时会再读一次屏幕并给出摘要`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Success("已派发"))
        val reader = FakeTool(
            "screen_read",
            ToolOutcome.Success(
                "【屏幕快照】com.tencent.mm",
                structured = buildJsonObject {
                    put("package", "com.tencent.mm")
                    put("nodeCount", 3)
                    put("nodes", kotlinx.serialization.json.Json.parseToJsonElement("[{\"text\":\"已发送\",\"clickable\":true}]"))
                },
            ),
        )
        val report = McpStepRunner({ registry(tap, reader) })
            .run(step("android_tap", expect = Expectation.TextVisible("已发送")))

        assertEquals(StepStatus.DONE, report.status)
        assertEquals(1, reader.calls)
        assertEquals("com.tencent.mm", report.digest?.packageName)
        assertNull(Expectation.TextVisible("已发送").unmetOn(report.digest))
    }

    @Test
    fun `读屏这一步直接用自己的结构化输出，不多打一次`() = runBlocking {
        val reader = FakeTool(
            "screen_read",
            ToolOutcome.Success("【屏幕快照】com.x", structured = buildJsonObject { put("package", "com.x") }),
        )
        val report = McpStepRunner({ registry(reader) })
            .run(step("screen_read", expect = Expectation.PackageIs("com.x")))

        assertEquals(1, reader.calls)
        assertEquals("com.x", report.digest?.packageName)
    }

    @Test
    fun `读屏失败时摘要为空，验收会判成没读到`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Success("已派发"))
        val reader = FakeTool("screen_read", ToolOutcome.Failed("无障碍没开"))
        val report = McpStepRunner({ registry(tap, reader) })
            .run(step("android_tap", expect = Expectation.TextVisible("已发送")))

        assertEquals(StepStatus.DONE, report.status)
        assertNull(report.digest)
        assertTrue(Expectation.TextVisible("已发送").unmetOn(report.digest)!!.contains("没读到"))
    }

    @Test
    fun `安全拦截是最终结论`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Refused("这是支付页面，我不代你点。"))
        val report = McpStepRunner({ registry(tap) }).run(step("android_tap"))

        assertEquals(StepStatus.REFUSED, report.status)
        assertTrue(report.detail.contains("支付页面"))
    }

    @Test
    fun `技术故障走失败，并且原因原样带出`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Failed("无障碍服务没开，去设置里打开。"))
        val report = McpStepRunner({ registry(tap) }).run(step("android_tap"))

        assertEquals(StepStatus.FAILED, report.status)
        assertTrue(report.detail.contains("无障碍服务没开"))
    }

    @Test
    fun `运行时没有这个工具时如实说这一版没做`() = runBlocking {
        val report = McpStepRunner({ registry() }).run(step("android_tap"))

        assertEquals(StepStatus.FAILED, report.status)
        assertTrue(report.detail.contains("这一版没有"))
    }

    @Test
    fun `目录里没有的工具直接拒绝执行`() = runBlocking {
        val phantom = FakeTool("android_fly", ToolOutcome.Success("飞了"))
        val report = McpStepRunner({ registry(phantom) }).run(step("android_fly"))

        assertEquals(StepStatus.FAILED, report.status)
        assertTrue(report.detail.contains("不在工具目录"))
        assertEquals(0, phantom.calls)
    }

    @Test
    fun `参数被原样透传成 JSON`() = runBlocking {
        val tap = FakeTool("android_tap", ToolOutcome.Success("已派发"))
        McpStepRunner({ registry(tap) }).run(step("android_tap", args = mapOf("text" to "发送", "exact" to "true")))

        assertEquals("发送", tap.lastArgs?.get("text")?.toString()?.trim('"'))
        assertEquals("true", tap.lastArgs?.get("exact")?.toString()?.trim('"'))
    }

    @Test
    fun `长回执被压成一段给报告看`() = runBlocking {
        val long = "很长的回执。".repeat(200)
        // 用不动屏幕的工具：屏上动作的回执会被核对结论替换掉（那是另一条测试的事）
        val write = FakeTool("setting_write", ToolOutcome.Success(long))
        val report = McpStepRunner({ registry(write) }).run(step("setting_write"))

        assertTrue(report.detail.length <= 301)
        assertTrue(report.detail.endsWith("…"))
        assertNotNull(report.detail)
        assertFalse(report.detail.contains("\n"))
    }
}
