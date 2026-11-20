package com.pocketagent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `listen` 的工具层。
 *
 * 用假端口 —— 真机上"能不能听到"必须装机验；这里钉的是**没听到不是错误**、
 * **超时秒数会被夹住**、**听不了时说的是人话**这三件。
 */
class ListenToolTest {

    private class FakeAsr(
        private val answer: String = "帮我定个闹钟",
        private val failWith: Exception? = null,
    ) : AsrPort {
        var lastMaxMillis: Long? = null

        override suspend fun listen(maxMillis: Long): String {
            lastMaxMillis = maxMillis
            failWith?.let { throw it }
            return answer
        }
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    private fun okText(outcome: ToolOutcome): String {
        assertTrue("应该是成功，实际：$outcome", outcome is ToolOutcome.Success)
        return (outcome as ToolOutcome.Success).text
    }

    private fun failText(outcome: ToolOutcome): String {
        assertTrue("应该是失败，实际：$outcome", outcome is ToolOutcome.Failed)
        return (outcome as ToolOutcome.Failed).reason
    }

    @Test
    fun `听到的内容原样带回去`() = runBlocking {
        val asr = FakeAsr(answer = "明天早上七点叫我")
        val text = okText(ListenTool(asr).call(args()))
        assertTrue(text.contains("明天早上七点叫我"))
        assertEquals(8_000L, asr.lastMaxMillis)
    }

    @Test
    fun `没听到不算故障_要说没听到`() = runBlocking {
        val reason = failText(ListenTool(FakeAsr(answer = "   ")).call(args()))
        assertTrue(reason.contains("没听到"))
        assertTrue("不能把安静说成引擎坏了", reason.contains("再试一次"))
    }

    @Test
    fun `秒数可以被指定`() = runBlocking {
        val asr = FakeAsr()
        okText(ListenTool(asr).call(args("seconds" to "3")))
        assertEquals(3_000L, asr.lastMaxMillis)
    }

    @Test
    fun `秒数上限被夹住`() = runBlocking {
        val asr = FakeAsr()
        okText(ListenTool(asr).call(args("seconds" to "600")))
        assertEquals(20_000L, asr.lastMaxMillis)
    }

    @Test
    fun `负数秒被拒且不会去开麦克风`() = runBlocking {
        val asr = FakeAsr()
        val reason = failText(ListenTool(asr).call(args("seconds" to "-1")))
        assertTrue(reason.contains("正数"))
        assertNull(asr.lastMaxMillis)
    }

    @Test
    fun `没权限时的话术指向设置`() = runBlocking {
        val asr = FakeAsr(failWith = IllegalStateException("没有麦克风权限。去 设置 → 应用 → PocketAgent → 权限 → 麦克风"))
        val reason = failText(ListenTool(asr).call(args()))
        assertTrue(reason.contains("麦克风"))
        assertTrue(reason.contains("设置"))
    }

    @Test
    fun `工具自我描述里提醒别主动开麦克风`() {
        val tool = ListenTool(FakeAsr())
        assertTrue(tool.description.contains("别自己主动开麦克风"))
        assertEquals("listen", tool.name)
    }
}
