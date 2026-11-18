package com.pocketagent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `media_ops` 的壳：只验"参数形状 + 环境就绪 + 结局三分"，真处理在 `:app` 那份实现里。 */
class MediaOpsToolTest {

    private class FakeOps(
        private val ready: Boolean = true,
        private val explain: String = "内置媒体引擎可用",
        private val result: MediaResult = MediaResult(true, "已生成 /work/a.m4a"),
    ) : MediaOpsPort {
        var calls = 0
        var last: String? = null

        override suspend fun availability() = MediaAvailability(ready, explain)
        override suspend fun probe(input: String): MediaResult { calls++; last = "probe:$input"; return result }
        override suspend fun extractAudio(input: String, output: String): MediaResult {
            calls++; last = "extract:$input->$output"; return result
        }
        override suspend fun transcode(input: String, output: String): MediaResult {
            calls++; last = "transcode:$input->$output"; return result
        }
        override suspend fun snapshot(input: String, atMs: Long, output: String): MediaResult {
            calls++; last = "snapshot:$input@$atMs->$output"; return result
        }
    }

    private fun args(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(builder)

    @Test
    fun `引擎关着时直接说去哪打开`() = runBlocking {
        val ops = FakeOps(ready = false, explain = "去 设置 → Linux 工作区 打开它")
        val outcome = MediaOpsTool(ops).call(args { put("action", "probe"); put("input", "/work/a.mp4") })

        assertTrue(outcome is ToolOutcome.Refused)
        assertTrue((outcome as ToolOutcome.Refused).reason.contains("设置"))
        assertEquals(0, ops.calls)
    }

    @Test
    fun `输入不在 work 下时一个字节都不处理`() = runBlocking {
        val ops = FakeOps()
        val outcome = MediaOpsTool(ops).call(args { put("action", "probe"); put("input", "/sdcard/a.mp4") })

        assertTrue(outcome is ToolOutcome.Failed)
        assertEquals(0, ops.calls)
    }

    @Test
    fun `probe 成功时把信息放进结构化输出`() = runBlocking {
        val ops = FakeOps(result = MediaResult(true, "时长 3.0s", mapOf("durationMs" to "3000")))
        val outcome = MediaOpsTool(ops).call(
            args { put("action", "probe"); put("input", "/work/a.mp4") },
        ) as ToolOutcome.Success

        assertEquals("3000", outcome.structured!!["durationMs"]!!.toString().trim('"'))
        assertEquals("probe:/work/a.mp4", ops.last)
    }

    @Test
    fun `抽音轨把输出路径原样传下去`() = runBlocking {
        val ops = FakeOps()
        MediaOpsTool(ops).call(
            args {
                put("action", "extract_audio")
                put("input", "/work/a.mp4")
                put("output", "/work/voice.m4a")
            },
        )
        assertEquals("extract:/work/a.mp4->/work/voice.m4a", ops.last)
    }

    @Test
    fun `截帧把毫秒传下去`() = runBlocking {
        val ops = FakeOps()
        MediaOpsTool(ops).call(
            args {
                put("action", "snapshot")
                put("input", "/work/a.mp4")
                put("atMs", 1500)
                put("output", "/work/frame.png")
            },
        )
        assertEquals("snapshot:/work/a.mp4@1500->/work/frame.png", ops.last)
    }

    @Test
    fun `不认识的动作由实现方说清，工具不静默当成功`() = runBlocking {
        val ops = FakeOps(result = MediaResult(false, "不认得这个动作「burn」"))
        val outcome = MediaOpsTool(ops).call(args { put("action", "burn"); put("input", "/work/a.mp4") })

        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("不认得"))
    }

    @Test
    fun `动作或输入为空时给例子`() = runBlocking {
        val tool = MediaOpsTool(FakeOps())
        assertTrue(tool.call(args { put("input", "/work/a.mp4") }) is ToolOutcome.Failed)
        assertTrue(tool.call(args { put("action", "probe") }) is ToolOutcome.Failed)
    }

    @Test
    fun `实现方抛异常时说的是没跑起来`() = runBlocking {
        val ops = object : MediaOpsPort {
            override suspend fun availability() = MediaAvailability(true, "ok")
            override suspend fun probe(input: String): MediaResult = throw IllegalStateException("boom")
            override suspend fun extractAudio(input: String, output: String) = MediaResult(false, "")
            override suspend fun transcode(input: String, output: String) = MediaResult(false, "")
            override suspend fun snapshot(input: String, atMs: Long, output: String) = MediaResult(false, "")
        }
        val outcome = MediaOpsTool(ops).call(args { put("action", "probe"); put("input", "/work/a.mp4") })

        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("没有跑起来"))
    }
}
