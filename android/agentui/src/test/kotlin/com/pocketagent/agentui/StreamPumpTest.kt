package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式泵的测试。
 *
 * ⚠️ 这些断言全部针对**静默失败**：本层出错时不会抛异常、真机上也不会报错，
 * 只会让用户看到一段错的内容，或者一张永远转圈的卡。
 */
class StreamPumpTest {

    // ── 正文 ───────────────────────────────────────────────────────

    @Test
    fun `正文分片变成增量事件`() {
        val pump = StreamPump()
        val out = pump.feed(StreamPump.Chunk.Delta("你好"), 1L)
        assertEquals(1, out.events.size)
        assertEquals(CardEvent.AssistantDelta(1L, "你好"), out.events.single())
    }

    @Test
    fun `空增量不产生事件也不算截断`() {
        val pump = StreamPump()
        val out = pump.feed(StreamPump.Chunk.Delta(""), 1L)
        assertTrue(out.events.isEmpty())
        assertFalse(out.truncated)
    }

    @Test
    fun `连续分片可以拼成完整句子`() {
        val pump = StreamPump()
        val t = listOf("我", "已经", "点好了").fold(Timeline()) { acc, s ->
            pump.feed(StreamPump.Chunk.Delta(s), 1L).events.fold(acc) { a, e ->
                (a.reduce(e) as Reduction.Applied).timeline
            }
        }
        assertEquals("我已经点好了", (t.cards.single() as Card.Say).text)
    }

    // ── 推理分片（最容易悄悄出错的地方）────────────────────────────

    /**
     * ★ 这是本文件最要紧的一条断言。
     *
     * 推理分片混进正文的后果不是"多几个字"，而是用户把模型的**草稿**当成回答读，
     * 并且无法分辨哪句是结论。而且它**不报错**。
     */
    @Test
    fun `推理分片绝不进正文`() {
        val pump = StreamPump()
        val t = listOf(
            pump.feed(StreamPump.Chunk.Reasoning("先看看当前页面…"), 1L),
            pump.feed(StreamPump.Chunk.Delta("已经到微信了"), 2L),
        ).flatMap { it.events }.fold(Timeline()) { acc, e ->
            (acc.reduce(e) as Reduction.Applied).timeline
        }
        assertEquals(1, t.cards.size)
        assertEquals("已经到微信了", (t.cards.single() as Card.Say).text)
    }

    @Test
    fun `推理分片被计数而不是彻底无踪`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.Reasoning("一二三"), 1L)
        val out = pump.feed(StreamPump.Chunk.Reasoning("四五"), 2L)
        assertEquals(5, out.reasoningChars)
        assertTrue(out.events.isEmpty())
    }

    // ── 工具调用拼接 ───────────────────────────────────────────────

    /**
     * ★ 半截调用交出去，调用方就会去执行一个参数不全的动作 ——
     *   而参数解析往往"尽力而为"，于是点到了别的东西。
     */
    @Test
    fun `工具调用分片不会半截交出去`() {
        val pump = StreamPump()
        val first = pump.feed(StreamPump.Chunk.ToolCall("c1", "android_tap", "{\"text\":\"发"), 1L)
        assertTrue("参数没凑齐就交出去了", first.toolCalls.isEmpty())
    }

    @Test
    fun `工具调用按 id 切换时凑齐`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.ToolCall("c1", "android_tap", "{\"text\":"), 1L)
        pump.feed(StreamPump.Chunk.ToolCall("c1", null, "\"发送\"}"), 2L)
        val out = pump.feed(StreamPump.Chunk.ToolCall("c2", "screen_read", null), 3L)
        assertEquals(1, out.toolCalls.size)
        assertEquals("android_tap", out.toolCalls.single().name)
        assertTrue(out.toolCalls.single().argumentsJson.contains("发送"))
    }

    @Test
    fun `结束分片把手里没凑完的调用交出去`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.ToolCall("c1", "android_tap", "{}"), 1L)
        val out = pump.feed(StreamPump.Chunk.Done("tool_calls"), 2L)
        assertEquals(1, out.toolCalls.size)
        assertEquals("android_tap", out.toolCalls.single().name)
    }

    @Test
    fun `名字都没到的调用不交出去`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.ToolCall("c1", null, "{}"), 1L)
        val out = pump.feed(StreamPump.Chunk.Done(null), 2L)
        assertTrue("不知道要调哪个工具，不能交", out.toolCalls.isEmpty())
    }

    @Test
    fun `匿名工具调用分片能拼起来`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.ToolCall(null, "android_tap", "{\"a\":"), 1L)
        pump.feed(StreamPump.Chunk.ToolCall(null, null, "1}"), 2L)
        val out = pump.feed(StreamPump.Chunk.Done(null), 3L)
        assertEquals("{\"a\":1}", out.toolCalls.single().argumentsJson)
    }

    @Test
    fun `工具调用分片不产生任何卡片`() {
        // 卡片由 CardProjection 在"真的去调"时生成，泵不越权替它决定。
        val pump = StreamPump()
        val out = pump.feed(StreamPump.Chunk.ToolCall("c1", "android_tap", "{}"), 1L)
        assertTrue(out.events.isEmpty())
    }

    // ── 收尾 ───────────────────────────────────────────────────────

    /**
     * ★ 真实 provider 的流经常不送 Done（连接被掐、进程被杀）。
     *   没有 end()，最后一张说卡永远是 RUNNING —— 界面上它还在打字，
     *   而实际上没有任何东西还会到来。
     */
    @Test
    fun `流断了也能把说卡收尾`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.Delta("说了一半"), 1L)
        assertTrue(pump.isSpeaking)
        assertEquals(listOf(CardEvent.AssistantDone(2L)), pump.end(2L))
        assertFalse(pump.isSpeaking)
    }

    @Test
    fun `收尾是幂等的`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.Delta("x"), 1L)
        assertEquals(1, pump.end(2L).size)
        assertTrue("第二次收尾不该再产生事件", pump.end(3L).isEmpty())
    }

    @Test
    fun `没有开场时收尾不产生多余事件`() {
        assertTrue(StreamPump().end(1L).isEmpty())
    }

    @Test
    fun `结束后再喂分片是接线错误而报错`() {
        val pump = StreamPump()
        pump.end(1L)
        try {
            pump.feed(StreamPump.Chunk.Delta("迟到的内容"), 2L)
            throw AssertionError("结束后喂分片应当报错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("流已结束"))
        }
    }

    @Test
    fun `断流时半截调用被记为丢弃而不是无声消失`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.ToolCall("c1", "android_tap", "{\"text\":"), 1L)
        pump.end(2L)
        assertEquals(1, pump.droppedToolCalls)
    }

    @Test
    fun `干净收尾时丢弃数是零`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.ToolCall("c1", "android_tap", "{}"), 1L)
        pump.feed(StreamPump.Chunk.Done("tool_calls"), 2L)
        pump.end(3L)
        assertEquals(0, pump.droppedToolCalls)
    }

    @Test
    fun `结束分片本身就把说卡收尾（不必等 end）`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.Delta("说完了"), 1L)
        val out = pump.feed(StreamPump.Chunk.Done("stop"), 2L)
        assertTrue(out.events.contains(CardEvent.AssistantDone(2L)))
        assertFalse(pump.isSpeaking)
    }

    // ── 截断 ───────────────────────────────────────────────────────

    /**
     * ★ 截断必须**说出来**。
     *
     * 安静地停止 append 的结果是：用户以为模型就说到这儿了，
     * 而实际上后半段被这个上限吃掉了。
     */
    @Test
    fun `整片越界时截断而不是吃下`() {
        val pump = StreamPump(sayMaxChars = 5)
        val out = pump.feed(StreamPump.Chunk.Delta("一二三四五六七八"), 1L)
        assertTrue(out.truncated)
        assertTrue(pump.isSpeaking.not())
    }

    @Test
    fun `正好用满上限不算截断`() {
        val pump = StreamPump(sayMaxChars = 3)
        val out = pump.feed(StreamPump.Chunk.Delta("一二三"), 1L)
        assertFalse(out.truncated)
        assertTrue(pump.isSpeaking)
    }

    @Test
    fun `多片累计到上限后再来一片才截断`() {
        val pump = StreamPump(sayMaxChars = 4)
        assertFalse(pump.feed(StreamPump.Chunk.Delta("一二"), 1L).truncated)
        assertFalse(pump.feed(StreamPump.Chunk.Delta("三四"), 2L).truncated)
        val out = pump.feed(StreamPump.Chunk.Delta("五"), 3L)
        assertTrue(out.truncated)
    }

    @Test
    fun `截断时把说卡收尾而不是留着转圈`() {
        val pump = StreamPump(sayMaxChars = 3)
        pump.feed(StreamPump.Chunk.Delta("一二"), 1L)
        val out = pump.feed(StreamPump.Chunk.Delta("三四五六"), 2L)
        assertTrue(out.events.contains(CardEvent.AssistantDone(2L)))
        assertFalse(pump.isSpeaking)
    }

    @Test
    fun `上限必须为正`() {
        try {
            StreamPump(sayMaxChars = 0)
            throw AssertionError("0 应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("必须为正"))
        }
    }

    // ── 复用 ───────────────────────────────────────────────────────

    @Test
    fun `复位后可以喂第二条流`() {
        val pump = StreamPump()
        pump.feed(StreamPump.Chunk.Delta("第一条"), 1L)
        pump.end(2L)
        pump.reset()
        val out = pump.feed(StreamPump.Chunk.Delta("第二条"), 3L)
        assertEquals(listOf(CardEvent.AssistantDelta(3L, "第二条")), out.events)
    }

    @Test
    fun `复位清掉所有计数`() {
        val pump = StreamPump(sayMaxChars = 2)
        pump.feed(StreamPump.Chunk.Reasoning("abc"), 1L)
        pump.feed(StreamPump.Chunk.Delta("一二三"), 2L)
        pump.feed(StreamPump.Chunk.ToolCall("c1", "t", "{}"), 3L)
        pump.end(4L)
        pump.reset()
        assertEquals(0, pump.reasoningChars)
        assertEquals(0, pump.droppedToolCalls)
        assertFalse(pump.truncated)
        assertFalse(pump.isSpeaking)
    }

    // ── 与时间线合流 ───────────────────────────────────────────────

    @Test
    fun `一整条流喂完卡片顺序正确`() {
        val pump = StreamPump()
        val events = mutableListOf<CardEvent>()
        events += pump.feed(StreamPump.Chunk.Delta("我先看一眼"), 1L).events
        events += pump.feed(StreamPump.Chunk.ToolCall("c1", "screen_read", "{}"), 2L).events
        events += pump.feed(StreamPump.Chunk.Done("stop"), 3L).events
        events += pump.end(3L)

        val t = events.fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        // 只有说卡与（还没被投影的）工具调用；说卡应当已收尾。
        assertEquals(1, t.cards.size)
        assertEquals(CardState.DONE, t.cards.single().state)
        assertEquals("我先看一眼", (t.cards.single() as Card.Say).text)
    }

    @Test
    fun `泵不读时钟（时间全由调用方给）`() {
        // 同一串分片、不同时刻喂，事件里的时间必须完全等于喂进去的那个值。
        val pump = StreamPump()
        assertEquals(11L, pump.feed(StreamPump.Chunk.Delta("x"), 11L).events.single().at)
    }

    /**
     * ★ 说卡正文上限与卡片协议里的 `Act.SUMMARY_MAX_CHARS` 是两回事：
     *   前者是"一段话有多长"，后者是"一行摘要有多长"。
     *   这里钉住数值关系，防止有人顺手把两者合并。
     */
    @Test
    fun `正文上限远大于动作摘要上限`() {
        assertTrue(StreamPump.SAY_MAX_CHARS > Card.Act.SUMMARY_MAX_CHARS * 100)
    }
}
