package com.pocketagent.provider.gateway.http

import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SseResponseWriter] 的离线单测。
 *
 * ⚠️ 用 `org.junit.Assert`（Truth 不在离线 classpath）。
 *
 * ⚠️ 断言大量使用**解析 JSON 后看字段**，而不是比字符串 ——
 *    比字符串的话，字段顺序或空格一变测试就红，而那是无害的。
 *    这里要钉的是**语义**（content 是 "你"、finish_reason 是 "stop"）。
 */
class SseResponseWriterTest {

    private val writer = SseResponseWriter(
        modelName = "pocketagent-auto",
        responseId = "chatcmpl-test",
        nowMillis = { 1_700_000_000_000 },
    )

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** 从一行 SSE 文本里取出 data 载荷并解析成 JsonObject */
    private fun payloadOf(sse: String): JsonObject {
        val line = sse.trimEnd('\n').removePrefix("data: ")
        return lenientJson.parseToJsonElement(line).jsonObject
    }

    private fun deltaContentOf(sse: String): String? =
        payloadOf(sse)["choices"]!!.jsonArray[0]
            .jsonObject["delta"]!!.jsonObject["content"]
            ?.jsonPrimitive?.content

    // ─────────────────────────────────────────────────────────────
    //  帧格式
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `每个 chunk 是一行 data 加一个空行`() {
        // ⚠️ 结尾必须是 `\n\n` —— SSE 用空行分隔事件。
        //    只写一个 \n 的话，客户端会把多个 data 行拼成**一个**事件，
        //    而 JSON 拼在一起是解析不了的 —— 表现为"流式回答全是语法错误"。
        val sse = writer.chunkToSse(ChatChunk.Delta("你好"))

        assertTrue("实际：${sse.replace("\n", "\\n")}", sse.startsWith("data: "))
        assertTrue("实际：${sse.replace("\n", "\\n")}", sse.endsWith("\n\n"))
        assertEquals(1, sse.count { it == '\n' } - 1)
    }

    @Test
    fun `内容是单行 JSON`() {
        // ⚠️ 载荷里不能有裸换行 —— 那会把一个事件拆成两个
        val sse = writer.chunkToSse(ChatChunk.Delta("多\n行\n文本"))

        val body = sse.removePrefix("data: ").trimEnd('\n')
        assertFalse("载荷里出现了裸换行：$body", body.contains('\n'))
        // 换行应被 JSON 转义成 \n（两个字符）
        assertTrue(body.contains("""\n"""))
    }

    // ─────────────────────────────────────────────────────────────
    //  文本 / 推理 / 工具调用
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `Delta 翻成 content 分片`() {
        val sse = writer.chunkToSse(ChatChunk.Delta("你好"))

        assertEquals("你好", deltaContentOf(sse))
        assertNull(payloadOf(sse)["choices"]!!.jsonArray[0].jsonObject["finish_reason"])
    }

    @Test
    fun `空的 Delta 不产出任何输出`() {
        // ⚠️ 空文本的帧没有信息量，却会让客户端多一次回调。
        //    高频流式下这是纯开销。
        assertEquals("", writer.chunkToSse(ChatChunk.Delta("")))
    }

    @Test
    fun `Reasoning 翻成 reasoning_content 分片`() {
        // 透传推理内容 —— dsh 的界面会显示它，是产品卖点之一
        val sse = writer.chunkToSse(ChatChunk.Reasoning("让我想想"))

        val delta = payloadOf(sse)["choices"]!!.jsonArray[0].jsonObject["delta"]!!.jsonObject
        assertEquals("让我想想", delta["reasoning_content"]!!.jsonPrimitive.content)
        assertNull(delta["content"])
    }

    @Test
    fun `ToolCallDelta 翻成 tool_calls 分片且 index 递增`() {
        // ⚠️ index 是协议里**归并分片**的依据。用 id 填它的话，
        //    客户端会把每片当新调用 —— 表现是"工具参数拼不起来"。
        val first = writer.chunkToSse(
            ChatChunk.ToolCallDelta(id = "call_1", name = "click", argumentsFragment = "{\"x\"")
        )
        val second = writer.chunkToSse(
            ChatChunk.ToolCallDelta(id = null, name = null, argumentsFragment = ":1}")
        )

        val idx1 = payloadOf(first)["choices"]!!.jsonArray[0]
            .jsonObject["delta"]!!.jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["index"]!!.jsonPrimitive.content

        val idx2 = payloadOf(second)["choices"]!!.jsonArray[0]
            .jsonObject["delta"]!!.jsonObject["tool_calls"]!!.jsonArray[0]
            .jsonObject["index"]!!.jsonPrimitive.content

        // 同一个 writer 实例内递增
        assertEquals("0", idx1.replace("\"", ""))
        assertEquals("1", idx2.replace("\"", ""))
    }

    // ─────────────────────────────────────────────────────────────
    //  Done 与 [DONE]
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `Done 翻成带 finish_reason 与 usage 的收尾分片`() {
        val sse = writer.chunkToSse(
            ChatChunk.Done(TokenUsage(inputTokens = 10, outputTokens = 5), "stop")
        )

        val choice = payloadOf(sse)["choices"]!!.jsonArray[0].jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)

        val usage = payloadOf(sse)["usage"]!!.jsonObject
        assertEquals(10, usage["prompt_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, usage["completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(15, usage["total_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `Done 不产生 DONE 标记`() {
        // ★ 这条是"恰好一个 [DONE]"设计的第一半：
        //   [DONE] 的发出点是**流结束**（finish），不是收到 Done chunk。
        //   若这里也发，上游重复给 Done 就会得到两个 [DONE]。
        val sse = writer.chunkToSse(
            ChatChunk.Done(TokenUsage(1, 1), "stop")
        )

        assertFalse("[DONE] 不应由 Done chunk 产生", sse.contains("[DONE]"))
    }

    @Test
    fun `Done 的 delta 字段存在但不含内容`() {
        // ⚠️ 不少客户端的解析器会先读 choices[0].delta，缺了会崩。
        //    协议上它不是必填，实践上是必填。
        val sse = writer.chunkToSse(ChatChunk.Done(TokenUsage(1, 1), "stop"))

        val choice = payloadOf(sse)["choices"]!!.jsonArray[0].jsonObject
        assertTrue("delta 字段必须存在", choice.containsKey("delta"))
        assertNull(choice["delta"]!!.jsonObject["content"])
    }

    @Test
    fun `Done 缺 finish_reason 时补 stop`() {
        // ⚠️ null 的 finish_reason 会让部分客户端当成"流异常中断"，
        //    于是把已经收到的好回答丢掉重试。
        val sse = writer.chunkToSse(ChatChunk.Done(TokenUsage(1, 1), null))

        assertEquals(
            "stop",
            payloadOf(sse)["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `finish 产出一个 DONE 标记`() {
        assertEquals("data: [DONE]\n\n", writer.finish())
    }

    @Test
    fun `DONE 标记后面没有多余内容`() {
        val f = writer.finish()
        assertEquals("data: [DONE]", f.trimEnd('\n'))
    }

    // ─────────────────────────────────────────────────────────────
    //  响应体不泄漏 Provider 信息（安全加固第 5 条）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `所有分片的 model 都是虚拟模型名`() {
        // ★ BYOK §4.4 第 ⑤ 条：让 dsh 知道背后是 DeepSeek 没有好处，
        //   却多一处需要维护的信息泄露面。
        val chunks = listOf(
            ChatChunk.Delta("a"),
            ChatChunk.Reasoning("b"),
            ChatChunk.ToolCallDelta("c", "d", "e"),
            ChatChunk.Done(TokenUsage(1, 1), "stop"),
        )

        for (c in chunks) {
            val sse = writer.chunkToSse(c)
            assertEquals(
                "分片 $c 的 model 应是虚拟名",
                "pocketagent-auto",
                payloadOf(sse)["model"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `错误事件的 type 不暴露上游`() {
        val sse = writer.errorEvent("模型服务暂时不可用", "upstream_error")

        val error = payloadOf(sse)["error"]!!.jsonObject
        // ⚠️ 固定值，不是从上游异常类型名派生的
        assertEquals("upstream_error", error["type"]!!.jsonPrimitive.content)
        assertEquals("模型服务暂时不可用", error["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `错误事件以双换行结尾`() {
        // ⚠️ 顺序上错误事件必须在 [DONE] **之前** —— 见 errorEvent 的注释。
        //    这里只钉住它自己是合法的一帧。
        val sse = writer.errorEvent("x", "y")
        assertTrue(sse.startsWith("data: "))
        assertTrue(sse.endsWith("\n\n"))
    }

    // ─────────────────────────────────────────────────────────────
    //  非流式聚合
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `聚合多个 Delta 时按顺序拼接`() {
        // ⚠️ 只取最后一个是错的 —— 上游是**增量**发文本的
        val response = writer.aggregateToResponse(
            listOf(
                ChatChunk.Delta("你"),
                ChatChunk.Delta("好"),
                ChatChunk.Delta("，世界"),
                ChatChunk.Done(TokenUsage(3, 4), "stop"),
            )
        )

        assertEquals("你好，世界", response.choices[0].message.content)
        assertEquals("stop", response.choices[0].finishReason)
        assertEquals(3, response.usage?.promptTokens)
        assertEquals(4, response.usage?.completionTokens)
    }

    @Test
    fun `聚合时拼接推理内容`() {
        val response = writer.aggregateToResponse(
            listOf(
                ChatChunk.Reasoning("先"),
                ChatChunk.Reasoning("想"),
                ChatChunk.Delta("答案"),
                ChatChunk.Done(TokenUsage(1, 1), "stop"),
            )
        )

        assertEquals("先想", response.choices[0].message.reasoningContent)
        assertEquals("答案", response.choices[0].message.content)
    }

    @Test
    fun `聚合时按 id 归并工具调用分片`() {
        // ★ 这是聚合里唯一有判断的地方：分片要拼成**一个**调用，
        //   而不是三个各带一段参数的调用。
        val response = writer.aggregateToResponse(
            listOf(
                ChatChunk.ToolCallDelta(id = "call_A", name = "click", argumentsFragment = "{\"x\":"),
                ChatChunk.ToolCallDelta(id = "call_A", name = null, argumentsFragment = "1}"),
                ChatChunk.Done(TokenUsage(1, 1), "tool_calls"),
            )
        )

        val calls = response.choices[0].message.toolCalls!!
        assertEquals(1, calls.size)
        assertEquals("click", calls[0].function.name)
        assertEquals("{\"x\":1}", calls[0].function.arguments)
        assertEquals("tool_calls", response.choices[0].finishReason)
    }

    @Test
    fun `无 id 的工具调用分片按 name 归并`() {
        val response = writer.aggregateToResponse(
            listOf(
                ChatChunk.ToolCallDelta(id = null, name = "type", argumentsFragment = "ab"),
                ChatChunk.ToolCallDelta(id = null, name = null, argumentsFragment = "cd"),
                ChatChunk.Done(TokenUsage(1, 1), "tool_calls"),
            )
        )

        val calls = response.choices[0].message.toolCalls!!
        assertEquals(1, calls.size)
        assertEquals("type", calls[0].function.name)
        assertEquals("abcd", calls[0].function.arguments)
    }

    @Test
    fun `工具调用缺 id 时补一个非空 id`() {
        // ⚠️ 客户端要靠 id 把工具结果回传（tool_call_id）——
        //    为 null 的话那个回传会失败，而错误在下游才出现。
        val response = writer.aggregateToResponse(
            listOf(
                ChatChunk.ToolCallDelta(id = null, name = "click", argumentsFragment = "{}"),
                ChatChunk.Done(TokenUsage(1, 1), "tool_calls"),
            )
        )

        val id = response.choices[0].message.toolCalls!![0].id
        assertTrue("id 不应为空：$id", id.isNotBlank())
    }

    @Test
    fun `有工具调用但上游没给 finish_reason 时补 tool_calls`() {
        // ⚠️ 补 "stop" 的话客户端以为"回答完了"，不会去执行工具 ——
        //    表现是"任务停在第一步且没有报错"，最难查的那种。
        val response = writer.aggregateToResponse(
            listOf(
                ChatChunk.ToolCallDelta(id = "c1", name = "click", argumentsFragment = "{}"),
                ChatChunk.Done(TokenUsage(1, 1), null),
            )
        )

        assertEquals("tool_calls", response.choices[0].finishReason)
    }

    @Test
    fun `聚合时 usage 缺失则响应里没有 usage 字段`() {
        val response = writer.aggregateToResponse(listOf(ChatChunk.Delta("x")))

        assertNull(response.usage)
    }

    @Test
    fun `聚合出的响应 model 也是虚拟模型名`() {
        val response = writer.aggregateToResponse(
            listOf(ChatChunk.Delta("x"), ChatChunk.Done(TokenUsage(1, 1), "stop"))
        )

        assertEquals("pocketagent-auto", response.model)
        assertEquals("chatcmpl-test", response.id)
    }

    @Test
    fun `聚合时 assistant 消息的 role 是 assistant`() {
        val response = writer.aggregateToResponse(listOf(ChatChunk.Delta("x")))

        assertEquals("assistant", response.choices[0].message.role)
    }

    @Test
    fun `空流聚合出空的 content 而不是崩溃`() {
        val response = writer.aggregateToResponse(emptyList())

        assertEquals("", response.choices[0].message.content)
        assertEquals("stop", response.choices[0].finishReason)
        assertNull(response.usage)
    }

    // ─────────────────────────────────────────────────────────────
    //  serialization 配置
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `null 字段不出现在 JSON 里`() {
        // ⚠️ explicitNulls=false 是必须的 —— 显式的 "content": null
        //    会让部分客户端的 content.trim() NPE。
        val sse = writer.chunkToSse(ChatChunk.Delta("x"))
        val body = sse.removePrefix("data: ").trimEnd('\n')

        assertFalse("不应出现 null 字段：$body", body.contains(":null"))
    }

    @Test
    fun `中文与 emoji 不被转义`() {
        // ⚠️ 默认 Json 会把非 ASCII 转成 \uXXXX，那会让 dsh 的
        //    原始日志不可读，也让"看流式响应排查问题"变得毫无意义。
        val sse = writer.chunkToSse(ChatChunk.Delta("中文🎉"))

        assertTrue("中文被转义了：$sse", sse.contains("中文"))
        assertTrue("emoji 被转义了：$sse", sse.contains("🎉"))
    }

    @Test
    fun `默认 Json 关掉了 encodeDefaults`() {
        val json = SseResponseWriter.defaultJson()
        val payload = GatewayChunkDelta()
        val encoded = json.encodeToString(GatewayChunkDelta.serializer(), payload)

        // 所有字段都是默认值（null）→ 应当编成 {}
        assertEquals("{}", encoded)
    }

    @Test
    fun `每个分片自带 id 与 created`() {
        val p = payloadOf(writer.chunkToSse(ChatChunk.Delta("x")))

        assertEquals("chatcmpl-test", p["id"]!!.jsonPrimitive.content)
        assertEquals("chat.completion.chunk", p["object"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_000L, p["created"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `id 与 created 在整条流里保持一致`() {
        // ⚠️ id 每条都变会让某些 SDK 把一条流当成多条
        val a = payloadOf(writer.chunkToSse(ChatChunk.Delta("a")))
        val b = payloadOf(writer.chunkToSse(ChatChunk.Delta("b")))

        assertEquals(a["id"]!!.jsonPrimitive.content, b["id"]!!.jsonPrimitive.content)
        assertEquals(
            a["created"]!!.jsonPrimitive.content,
            b["created"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `未知上游 finish_reason 原样透传`() {
        // 上游（如某些厂商）会给 "length" / "content_filter" / "insufficient_system_resource"
        // —— 这些对客户端有意义，不该被我们改写
        val sse = writer.chunkToSse(ChatChunk.Done(TokenUsage(1, 1), "length"))

        assertEquals(
            "length",
            payloadOf(sse)["choices"]!!.jsonArray[0].jsonObject["finish_reason"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `Delta 里不含 finish_reason 字段`() {
        val sse = writer.chunkToSse(ChatChunk.Delta("x"))
        val body = sse.removePrefix("data: ").trimEnd('\n')

        assertFalse("不应出现 finish_reason：$body", body.contains("finish_reason"))
    }
}
