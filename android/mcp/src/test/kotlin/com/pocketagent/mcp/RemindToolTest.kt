package com.pocketagent.mcp

import com.pocketagent.filelogic.Reminder
import com.pocketagent.filelogic.Reminders
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `remind` / `speak` 的工具层。
 *
 * 端口是用例里的假实现 —— 这一层不该碰 AlarmManager 和 TextToSpeech：
 * 真机上"响没响"要靠装机验，这里钉的是**边界**（空文本、超长、条数上限、
 * 取消不存在的那条怎么回话）。
 */
class RemindToolTest {

    private class FakePort(
        private var items: MutableList<Reminder> = mutableListOf(),
        private val failWith: Exception? = null,
    ) : ReminderPort {
        var lastText: String? = null
        var lastAt: Long? = null

        override suspend fun schedule(text: String, atMillis: Long): String {
            failWith?.let { throw it }
            lastText = text
            lastAt = atMillis
            val id = "id${items.size + 1}"
            items.add(Reminder(id, text, atMillis, System.currentTimeMillis()))
            return id
        }

        override suspend fun pending(): List<Reminder> = items

        override suspend fun cancel(id: String): Boolean {
            val hit = items.firstOrNull { it.id == id } ?: return false
            items.remove(hit)
            return true
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
    fun `设一条提醒会把内容与时间交给端口`() = runBlocking {
        val port = FakePort()
        val text = okText(RemindTool(port).call(args("text" to "喝水", "at" to "+30m")))
        assertTrue(text.contains("设好了"))
        assertTrue(text.contains("喝水"))
        assertEquals("喝水", port.lastText)
        val delta = port.lastAt!! - System.currentTimeMillis()
        assertTrue("应该是 30 分钟后，实际差 $delta 毫秒", delta in 29 * 60_000..31 * 60_000)
    }

    @Test
    fun `不给 action 默认就是新增`() = runBlocking {
        val port = FakePort()
        okText(RemindTool(port).call(args("text" to "伸个懒腰", "at" to "+10m")))
        assertEquals(1, port.pending().size)
    }

    @Test
    fun `内容为空时问回去而不是设个空提醒`() = runBlocking {
        val port = FakePort()
        val reason = failText(RemindTool(port).call(args("at" to "+30m")))
        assertTrue(reason.contains("提醒什么"))
        assertEquals(0, port.pending().size)
    }

    @Test
    fun `内容超长被拒`() = runBlocking {
        val port = FakePort()
        val reason = failText(RemindTool(port).call(args("text" to "啊".repeat(201), "at" to "+30m")))
        assertTrue(reason.contains("太长"))
        assertEquals(0, port.pending().size)
    }

    @Test
    fun `不给时间时要写法示例`() = runBlocking {
        val port = FakePort()
        val reason = failText(RemindTool(port).call(args("text" to "喝水")))
        assertTrue(reason.contains("+30m"))
    }

    @Test
    fun `时间读不懂时把原话带回去`() = runBlocking {
        val port = FakePort()
        val reason = failText(RemindTool(port).call(args("text" to "喝水", "at" to "下午茶时间")))
        assertTrue(reason.contains("下午茶时间"))
        assertEquals(0, port.pending().size)
    }

    @Test
    fun `到了上限先拒再排`() = runBlocking {
        val port = FakePort()
        val tool = RemindTool(port)
        repeat(Reminders.MAX_PENDING) { i ->
            okText(tool.call(args("text" to "第${i}条", "at" to "+30m")))
        }
        val reason = failText(tool.call(args("text" to "再来一条", "at" to "+30m")))
        assertTrue(reason.contains("上限"))
        assertEquals(Reminders.MAX_PENDING, port.pending().size)
    }

    @Test
    fun `列出来按时间排序并带 id`() = runBlocking {
        val port = FakePort()
        val tool = RemindTool(port)
        okText(tool.call(args("text" to "晚一点", "at" to "+50m")))
        okText(tool.call(args("text" to "早一点", "at" to "+20m")))
        val text = okText(tool.call(args("action" to "list")))
        assertTrue(text.contains("有 2 条提醒"))
        assertTrue("早的应该在前", text.indexOf("早一点") < text.indexOf("晚一点"))
        assertTrue(text.contains("id="))
    }

    @Test
    fun `没有提醒时如实说没有`() = runBlocking {
        val text = okText(RemindTool(FakePort()).call(args("action" to "list")))
        assertTrue(text.contains("没有"))
    }

    @Test
    fun `取消存在的提醒`() = runBlocking {
        val port = FakePort()
        val tool = RemindTool(port)
        okText(tool.call(args("text" to "喝水", "at" to "+30m")))
        val id = port.pending().first().id
        val text = okText(tool.call(args("action" to "cancel", "id" to id)))
        assertTrue(text.contains("取消了"))
        assertEquals(0, port.pending().size)
    }

    @Test
    fun `取消不存在的提醒要说可能已经响过`() = runBlocking {
        val reason = failText(RemindTool(FakePort()).call(args("action" to "cancel", "id" to "nope")))
        assertTrue(reason.contains("已经响过"))
    }

    @Test
    fun `没给 id 就取消时提示先列一遍`() = runBlocking {
        val reason = failText(RemindTool(FakePort()).call(args("action" to "cancel")))
        assertTrue(reason.contains("list"))
    }

    @Test
    fun `只认三种 action`() = runBlocking {
        val reason = failText(RemindTool(FakePort()).call(args("action" to "snooze")))
        assertTrue(reason.contains("set"))
        assertTrue(reason.contains("cancel"))
    }

    @Test
    fun `排提醒失败时如实转述错误`() = runBlocking {
        val port = FakePort(failWith = IllegalStateException("没有精确闹钟权限"))
        val reason = failText(RemindTool(port).call(args("text" to "喝水", "at" to "+30m")))
        assertTrue(reason.contains("精确闹钟权限"))
    }

    // ── speak ────────────────────────────────────────────────

    private class FakeTts(private val engine: String = "", private val failWith: Exception? = null) : TtsPort {
        var spoken: String? = null
        override suspend fun speak(text: String): String {
            failWith?.let { throw it }
            spoken = text
            return engine
        }
    }

    @Test
    fun `朗读会把文本交给引擎`() = runBlocking {
        val tts = FakeTts(engine = "com.google.android.tts")
        val text = okText(SpeakTool(tts).call(args("text" to "该喝水了")))
        assertEquals("该喝水了", tts.spoken)
        assertTrue(text.contains("com.google.android.tts"))
    }

    @Test
    fun `引擎名为空时说系统默认`() = runBlocking {
        val text = okText(SpeakTool(FakeTts()).call(args("text" to "该喝水了")))
        assertTrue(text.contains("系统默认引擎"))
    }

    @Test
    fun `朗读空文本被拒`() = runBlocking {
        val tts = FakeTts()
        val reason = failText(SpeakTool(tts).call(args("text" to "  ")))
        assertTrue(reason.contains("念什么"))
        assertEquals(null, tts.spoken)
    }

    @Test
    fun `朗读超长被拒并说明会截断`() = runBlocking {
        val reason = failText(SpeakTool(FakeTts()).call(args("text" to "啊".repeat(501))))
        assertTrue(reason.contains("截断"))
    }

    @Test
    fun `引擎报错时转述成人话`() = runBlocking {
        val reason = failText(
            SpeakTool(FakeTts(failWith = IllegalStateException("这台手机没有装中文语音数据")))
                .call(args("text" to "该喝水了")),
        )
        assertTrue(reason.contains("中文语音数据"))
        assertFalse(reason.contains("IllegalState"))
    }
}
