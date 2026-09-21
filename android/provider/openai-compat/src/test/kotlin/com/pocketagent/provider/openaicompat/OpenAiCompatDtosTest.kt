package com.pocketagent.provider.openaicompat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO 反序列化测试。
 *
 * 关注点不是"标准响应能不能解析"（那太容易），而是**厂商的脏数据能不能扛住**：
 * 多字段、少字段、类型不一致、错误结构不统一。
 *
 * 所有用例的 JSON 都取自各厂商真实响应的形态（截断过的）。
 */
class OpenAiCompatDtosTest {

    /**
     * 直接用 Provider 自己的 Json 实例，而不是在测试里复制一份配置。
     *
     * 复制配置有个隐蔽问题：**配置漂移后测试照样绿**，因为它测的是副本的
     * 行为而不是线上真正用的那个。这里必须引用同一个实例。
     */
    private val json: Json = OpenAiCompatProvider.PROVIDER_JSON

    private fun chunk(raw: String): ChatCompletionChunk =
        json.decodeFromString(raw)

    // ═══════════════════════════════════════════════════════════
    //  流式分片
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `解析标准流式分片`() {
        val c = chunk(
            """{"id":"chatcmpl-1","object":"chat.completion.chunk","created":1,
                "model":"gpt-4o","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}"""
        )

        assertEquals("gpt-4o", c.model)
        assertEquals("你好", c.choices.first().delta?.content)
        assertNull(c.choices.first().finishReason)
    }

    @Test
    fun `首片只有 role，content 为 null 时不崩`() {
        val c = chunk("""{"choices":[{"index":0,"delta":{"role":"assistant"}}]}""")

        assertNull(c.choices.first().delta?.content)
        assertEquals("assistant", c.choices.first().delta?.role)
    }

    @Test
    fun `解析 DeepSeek 的 reasoning_content`() {
        // DeepSeek-R1 等推理模型走的是非标准字段，必须单独识别
        val c = chunk(
            """{"choices":[{"index":0,"delta":{"reasoning_content":"让我想想…"}}]}"""
        )

        assertEquals("让我想想…", c.choices.first().delta?.reasoningContent)
        assertNull(c.choices.first().delta?.content)
    }

    @Test
    fun `解析带用量的结束分片，此时 choices 为空数组`() {
        val c = chunk(
            """{"id":"x","choices":[],
                "usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165,
                         "prompt_tokens_details":{"cached_tokens":64}}}"""
        )

        assertTrue(c.choices.isEmpty())
        assertEquals(120, c.usage?.promptTokens)
        assertEquals(45, c.usage?.completionTokens)
        assertEquals(64, c.usage?.promptDetails?.cachedTokens)
    }

    @Test
    fun `usage 里没有 prompt_tokens_details 对象时该字段为 null`() {
        // 注意：`cachedTokens` 的默认值 0 只在"对象存在但字段缺失"时生效；
        // 整个对象缺失时 promptDetails 是 null，由 Provider 用 `?: 0` 兜底。
        val c = chunk("""{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}""")

        assertNull(c.usage?.promptDetails)
        assertEquals(0, c.usage?.promptDetails?.cachedTokens ?: 0)
    }

    @Test
    fun `prompt_tokens_details 存在但缺少 cached_tokens 时默认 0`() {
        val c = chunk(
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,
                "prompt_tokens_details":{}}}"""
        )

        assertEquals(0, c.usage?.promptDetails?.cachedTokens)
    }

    @Test
    fun `解析被切成多片的 tool_calls 增量`() {
        // 参数是逐字符流式下发的，上层要按 index 拼接
        val first = chunk(
            """{"choices":[{"index":0,"delta":{"tool_calls":[
                {"index":0,"id":"call_1","function":{"name":"open_app","arguments":""}}]}}]}"""
        )
        val second = chunk(
            """{"choices":[{"index":0,"delta":{"tool_calls":[
                {"index":0,"function":{"arguments":"{\"pkg\":"}}]}}]}"""
        )

        val tc = first.choices.first().delta?.toolCalls?.first()
        assertEquals("call_1", tc?.id)
        assertEquals("open_app", tc?.function?.name)
        assertNull(tc?.function?.arguments?.takeIf { it.isNotEmpty() })

        // 第二片只有 arguments，没有 id 与 name —— 这正是上层必须按 index 合并的原因
        val tc2 = second.choices.first().delta?.toolCalls?.first()
        assertNull(tc2?.id)
        assertNull(tc2?.function?.name)
        assertEquals("""{"pkg":""", tc2?.function?.arguments)
    }

    @Test
    fun `厂商新增未知字段不会导致解析失败`() {
        val c = chunk(
            """{"id":"x","choices":[{"index":0,"delta":{"content":"a"}}],
                "system_fingerprint":"fp_abc","service_tier":"scale","vendor_extension":{"x":1}}"""
        )

        assertEquals("a", c.choices.first().delta?.content)
    }

    @Test
    fun `finish_reason 为字符串 stop`() {
        val c = chunk("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""")

        assertEquals("stop", c.choices.first().finishReason)
    }

    // ═══════════════════════════════════════════════════════════
    //  错误响应
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `解析标准错误结构`() {
        val e = json.decodeFromString<ErrorResponse>(
            """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error","code":"invalid_api_key"}}"""
        )

        assertEquals("Incorrect API key provided", e.bestMessage)
        assertEquals("invalid_api_key", e.bestCode)
    }

    @Test
    fun `解析错误放在顶层的厂商`() {
        // 少数厂商不套 error 对象
        val e = json.decodeFromString<ErrorResponse>(
            """{"message":"quota exceeded","code":"QUOTA_EXCEEDED","type":"limit"}"""
        )

        assertEquals("quota exceeded", e.bestMessage)
        assertEquals("QUOTA_EXCEEDED", e.bestCode)
    }

    @Test
    fun `错误响应完全为空时给出兜底文案而不是抛异常`() {
        // 有些厂商在 429/5xx 时返回空 body，这时绝不能崩
        val e = json.decodeFromString<ErrorResponse>("{}")

        assertEquals("未知错误", e.bestMessage)
        assertNull(e.bestCode)
    }

    // ═══════════════════════════════════════════════════════════
    //  模型列表
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `解析 OpenAI 风格的模型列表`() {
        val m = json.decodeFromString<ModelsResponse>(
            """{"object":"list","data":[
                {"id":"gpt-4o","object":"model","owned_by":"openai"},
                {"id":"gpt-4o-mini","object":"model","owned_by":"openai"}]}"""
        )

        assertEquals(2, m.data.size)
        assertEquals("gpt-4o", m.data[0].id)
        assertNull(m.data[0].context_length)
    }

    @Test
    fun `解析 OpenRouter 附加的上下文长度与价格`() {
        // OpenRouter 的价格单位是「每 token 美元」的字符串，上层要乘 1e6
        val m = json.decodeFromString<ModelsResponse>(
            """{"data":[{"id":"anthropic/claude-sonnet-4","context_length":200000,
                "pricing":{"prompt":"0.000003","completion":"0.000015"}}]}"""
        )

        val dto = m.data.first()
        assertEquals(200_000, dto.context_length)
        assertEquals(3.0, dto.pricing?.prompt?.toDouble()?.times(1_000_000) ?: -1.0, 1e-9)
        assertEquals(15.0, dto.pricing?.completion?.toDouble()?.times(1_000_000) ?: -1.0, 1e-9)
    }

    @Test
    fun `模型列表为空时不崩`() {
        assertEquals(0, json.decodeFromString<ModelsResponse>("""{"data":[]}""").data.size)
        assertEquals(0, json.decodeFromString<ModelsResponse>("{}").data.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  请求序列化
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `序列化请求时省略 null 字段`() {
        // 有些厂商对多余的 null 字段会直接 400，所以 explicitNulls 必须关掉
        val req = ChatCompletionRequest(
            model = "gpt-4o",
            messages = listOf(RequestMessage(role = "user", content = null)),
            stream = true,
        )

        val out = json.encodeToString(ChatCompletionRequest.serializer(), req)

        assertTrue("不应出现 temperature", !out.contains("temperature"))
        assertTrue("不应出现 max_tokens", !out.contains("max_tokens"))
        assertTrue("不应出现 tools", !out.contains("tools"))
        assertTrue(out.contains("\"stream\":true"))
    }

    @Test
    fun `序列化时 snake_case 字段名正确`() {
        val req = ChatCompletionRequest(
            model = "m",
            messages = emptyList(),
            maxTokens = 512,
            toolChoice = "auto",
            streamOptions = StreamOptions(true),
        )

        val out = json.encodeToString(ChatCompletionRequest.serializer(), req)

        assertNotNull(out)
        assertTrue(out.contains("\"max_tokens\":512"))
        assertTrue(out.contains("\"tool_choice\":\"auto\""))
    }

    /**
     * 回归测试 —— 这条曾经是 bug。
     *
     * `encodeDefaults = false` 会把"值等于默认值"的字段整个丢掉。
     * `StreamOptions.includeUsage` 的默认值恰好就是 `true`，于是
     * `stream_options` 被序列化成 `{}` —— 语法合法、服务器也接受，
     * 但**再也不会返回 token 用量**。表现为"预算统计永远是 0"，
     * 没有任何报错，极难定位。
     */
    @Test
    fun `stream_options 必须带上 include_usage，不能被默认值吞掉`() {
        val req = ChatCompletionRequest(
            model = "m",
            messages = emptyList(),
            stream = true,
            streamOptions = StreamOptions(true),
        )

        val out = json.encodeToString(ChatCompletionRequest.serializer(), req)

        assertTrue("include_usage 丢了：$out", out.contains("\"include_usage\":true"))
    }

    /**
     * 回归测试 —— 同源 bug 的第二个受害者。
     *
     * `RequestTool.type` 与 `RequestToolCall.type` 的默认值都是 `"function"`，
     * 在 `encodeDefaults = false` 下会被丢掉，OpenAI 收到没有 type 的 tool
     * 定义直接 400。
     */
    @Test
    fun `工具定义必须带上 type 字段`() {
        val req = ChatCompletionRequest(
            model = "m",
            messages = emptyList(),
            tools = listOf(
                RequestTool(
                    function = RequestTool.FunctionDef(
                        name = "open_app",
                        description = "打开应用",
                        parameters = Json.parseToJsonElement("""{"type":"object"}"""),
                    )
                )
            ),
        )

        val out = json.encodeToString(ChatCompletionRequest.serializer(), req)

        assertTrue("tools[].type 丢了：$out", out.contains("\"type\":\"function\""))
        assertTrue(out.contains("\"name\":\"open_app\""))
    }

    @Test
    fun `历史消息里的 tool_calls 必须带上 type 字段`() {
        val msg = RequestMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                RequestToolCall(
                    id = "call_1",
                    function = RequestToolCall.FunctionCall("open_app", "{}"),
                )
            ),
        )

        val out = json.encodeToString(RequestMessage.serializer(), msg)

        assertTrue("tool_calls[].type 丢了：$out", out.contains("\"type\":\"function\""))
        assertTrue(out.contains("\"tool_calls\""))
        assertTrue("arguments 应原样透传：$out", out.contains("\"arguments\":\"{}\""))
    }
}
