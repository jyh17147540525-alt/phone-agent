package com.pocketagent.provider.openaicompat

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SseParser] 单元测试。
 *
 * 这个解析器是整个 Provider 层最容易被厂商差异搞坏的地方 —— 它面对的
 * 是十几家"自称 OpenAI 兼容"但各写各的实现。所以测试用例基本是按
 * **已知的厂商怪癖** 一条条列出来的，而不是按 SSE 规范写的。
 *
 * 新增厂商适配时，**先在这里加一条用例，再改实现**。
 */
class SseParserTest {

    // 注意：SseParser 只是「有一个 iterator() 运算符」，并不是 Iterable，
    // 所以不能直接 .asSequence()，必须先取 .iterator()。
    private fun events(raw: String): List<SseEvent> =
        SseParser(Buffer().writeUtf8(raw)).iterator().asSequence().toList()

    private fun payloads(raw: String): List<String> =
        events(raw).filterIsInstance<SseEvent.Data>().map { it.payload }

    private fun sawDone(raw: String): Boolean = events(raw).any { it is SseEvent.Done }

    // ═══════════════════════════════════════════════════════════
    //  基本形态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `标准 SSE 形态：event 字段被忽略，只取 data`() {
        val raw = """
            event: message
            data: {"a":1}

            event: message
            data: {"b":2}

        """.trimIndent()

        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), payloads(raw))
    }

    @Test
    fun `裸 data 行形态：一行一个 JSON，没有双换行分隔`() {
        // 相当一部分国内厂商就是这样发的
        val raw = "data: {\"a\":1}\ndata: {\"b\":2}\ndata: {\"c\":3}\n"

        assertEquals(listOf("""{"a":1}""", """{"b":2}""", """{"c":3}"""), payloads(raw))
    }

    @Test
    fun `CRLF 行尾不会在载荷末尾留下回车`() {
        val raw = "data: {\"a\":1}\r\ndata: {\"b\":2}\r\n"

        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), payloads(raw))
    }

    @Test
    fun `冒号后没有空格也能解析`() {
        assertEquals(listOf("""{"a":1}"""), payloads("data:{\"a\":1}\n"))
    }

    @Test
    fun `载荷前后的空白被裁掉`() {
        assertEquals(listOf("""{"a":1}"""), payloads("data:   {\"a\":1}   \n"))
    }

    // ═══════════════════════════════════════════════════════════
    //  需要忽略的内容
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `keep-alive 注释行被忽略`() {
        // 部分厂商用 `: ping` 保活，必须丢掉而不是当成 JSON 去解析
        val raw = ": ping\ndata: {\"a\":1}\n: keep-alive\ndata: {\"b\":2}\n"

        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), payloads(raw))
    }

    @Test
    fun `空 data 行被跳过`() {
        val raw = "data: \ndata:\ndata: {\"a\":1}\n"

        assertEquals(listOf("""{"a":1}"""), payloads(raw))
    }

    @Test
    fun `id 与 retry 字段被忽略`() {
        val raw = "id: 42\nretry: 3000\ndata: {\"a\":1}\n"

        assertEquals(listOf("""{"a":1}"""), payloads(raw))
    }

    @Test
    fun `完全空白的输入产出零个事件`() {
        assertEquals(emptyList<SseEvent>(), events(""))
        assertEquals(emptyList<SseEvent>(), events("\n\n\n"))
        assertEquals(emptyList<SseEvent>(), events(": ping\n: ping\n"))
    }

    // ═══════════════════════════════════════════════════════════
    //  结束标记
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `DONE 标记之后的载荷不再被读取`() {
        // 对端理论上不会在 [DONE] 之后继续发数据；真发了也不能让它污染结果
        val raw = "data: {\"a\":1}\ndata: [DONE]\ndata: {\"b\":2}\n"

        assertEquals(listOf("""{"a":1}"""), payloads(raw))
        assertTrue(sawDone(raw))
    }

    @Test
    fun `DONE 的多种写法都被识别`() {
        for (marker in listOf("[DONE]", "[done]", "DONE", "done")) {
            assertTrue("`$marker` 应被识别为结束标记", sawDone("data: $marker\n"))
        }
    }

    /**
     * 回归测试 —— 这条曾经是 bug。
     *
     * 早先的 DONE_MARKERS 里带着 `{}` 和 `null`。问题是有些厂商会在流中途
     * 发一个空的 data 载荷（保活或空 delta），一旦被当成结束，响应会被
     * **静默截断** —— agent 会拿到半份动作计划，而且没有任何报错。
     *
     * 所以现在的规则是：空载荷交给上层反序列化（结果是个空 chunk，什么都不会发出），
     * 结束只由 `[DONE]` 或对端关闭连接触发。
     */
    @Test
    fun `空 JSON 载荷不被当作结束标记`() {
        val raw = "data: {}\ndata: null\ndata: {\"a\":1}\n"

        assertFalse("`{}` 与 `null` 不能终结流", sawDone(raw))
        assertEquals(listOf("{}", "null", """{"a":1}"""), payloads(raw))
    }

    @Test
    fun `对端直接关连接时流正常结束，不产出 Done 事件`() {
        // 厂商既不发 [DONE] 也不发 usage 的情况（Ollama 就是这样）。
        // 结束信号由上层在流末尾兜底补发，解析器这一层不需要伪造一个 Done。
        val raw = "data: {\"a\":1}\n"

        assertEquals(listOf("""{"a":1}"""), payloads(raw))
        assertFalse(sawDone(raw))
    }

    @Test
    fun `已结束后再调 next 抛 NoSuchElementException`() {
        val it = SseParser(Buffer().writeUtf8("data: [DONE]\n")).iterator()

        assertTrue(it.hasNext())
        assertEquals(SseEvent.Done, it.next())
        assertFalse(it.hasNext())
        assertThrows(NoSuchElementException::class.java) { it.next() }
    }

    // ═══════════════════════════════════════════════════════════
    //  已知限制（用测试把行为钉住，避免被误当成 bug 修错方向）
    // ═══════════════════════════════════════════════════════════

    /**
     * SSE 规范允许一个事件用多条 `data:` 行承载，客户端应把它们用 `\n` 拼起来。
     *
     * **本实现故意不这么做。** 因为"一行一个完整 JSON"的厂商远比
     * "多行拼一个 JSON"的多，如果按规范拼，前者的每一条 JSON 会被粘成
     * 一大坨，全崩。两者无法同时满足，只能选覆盖率高的那个。
     *
     * 后果：真遇到多行 data 的厂商，载荷会被逐行独立解析，除了首行之外
     * 全部解析失败并被跳过 —— 表现为"回复不完整"。届时应在这里加一条
     * 按厂商开关的解析模式，而不是改默认行为。
     */
    @Test
    fun `多行 data 不会被拼接（已知限制）`() {
        val raw = "data: {\"a\":\ndata: 1}\n"

        assertEquals(listOf("""{"a":""", "1}"), payloads(raw))
    }

    // ═══════════════════════════════════════════════════════════
    //  looksLikeSseStream —— 用于识别"假流式"
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Content-Type 为 text 或 event-stream 时判定为流式`() {
        assertTrue(looksLikeSseStream("text/event-stream", null))
        assertTrue(looksLikeSseStream("text/event-stream; charset=utf-8", null))
        assertTrue(looksLikeSseStream("TEXT/EVENT-STREAM", null))
    }

    @Test
    fun `Content-Type 不可信时靠首行内容判断`() {
        // 有些厂商给的是 text/plain，但内容确实是 SSE
        assertTrue(looksLikeSseStream("text/plain", "data: {\"a\":1}"))
        assertTrue(looksLikeSseStream(null, "  data: {\"a\":1}"))
    }

    @Test
    fun `完整 JSON 响应不会被误判为流式`() {
        // stream=true 但厂商返回了一整个 JSON —— 需要整体解析，走非流式分支
        assertFalse(looksLikeSseStream("application/json", "{\"choices\":[]}"))
        assertFalse(looksLikeSseStream(null, "{\"choices\":[]}"))
        assertFalse(looksLikeSseStream(null, null))
    }
}
