package com.pocketagent.provider.anthropic

import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.ProviderException
import com.pocketagent.provider.api.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Anthropic 协议的翻译规则**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件存在的理由
 * ═══════════════════════════════════════════════════════════════
 *
 * 协议翻译**不会在编译期报错**——发错了是运行时 400，或者更糟：
 * 静默失效（比如工具定义字段名发错，模型永远不调用那个工具）。
 *
 * ⇒ 六条结构性差异，每条都要有一组测试盯着。
 */
class AnthropicPayloadTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(s: String): JsonObject = json.parseToJsonElement(s).jsonObject

    private fun req(
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        maxTokens: Int? = null,
        temperature: Double? = null,
    ) = ChatRequest(
        model = "claude-haiku-5-5",
        messages = messages,
        tools = tools,
        temperature = temperature,
        maxTokens = maxTokens,
    )

    // ═══════════════════════════════════════════════════════════
    //  ① 系统提示提到顶层
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `系统提示放到顶层字段`() {
        val body = parse(
            AnthropicPayload.buildBody(
                req(listOf(ChatMessage.system("你是助手"), ChatMessage.user("你好"))),
                stream = false,
            ),
        )
        assertEquals("你是助手", body["system"]?.jsonPrimitive?.content)
        // ⚠️ 系统消息不该出现在 messages 里 —— Anthropic 拒绝 role="system"
        val roles = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertFalse("messages 里不能有 system 角色：$roles", roles.contains("system"))
        assertEquals(listOf("user"), roles)
    }

    @Test
    fun `多条系统提示合并成一条`() {
        // ══════════════════════════════════════════════════════
        //  ★ 不合并会**丢掉**除第一条外的全部内容
        // ══════════════════════════════════════════════════════
        // 而人格设定、安全约束往往散在多条系统消息里。
        val body = parse(
            AnthropicPayload.buildBody(
                req(
                    listOf(
                        ChatMessage.system("人格：温柔"),
                        ChatMessage.system("安全：不越界"),
                        ChatMessage.user("在吗"),
                    ),
                ),
                stream = false,
            ),
        )
        val system = body["system"]!!.jsonPrimitive.content
        assertTrue("人格必须保留：$system", system.contains("人格"))
        assertTrue("安全约束也必须保留：$system", system.contains("安全"))
    }

    @Test
    fun `没有系统提示时不发该字段`() {
        val body = parse(AnthropicPayload.buildBody(req(listOf(ChatMessage.user("嗨"))), false))
        assertNull("空的 system 字段会让部分厂商 400", body["system"])
    }

    // ═══════════════════════════════════════════════════════════
    //  ② max_tokens 必填
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `缺省 max_tokens 会兜底`() {
        // ⚠️ Anthropic 的 max_tokens **必填**，漏了直接 400。
        //    而我们的 ChatRequest.maxTokens 是可选的。
        val body = parse(AnthropicPayload.buildBody(req(listOf(ChatMessage.user("嗨"))), false))
        assertEquals(
            AnthropicPayload.DEFAULT_MAX_TOKENS,
            body["max_tokens"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `显式 max_tokens 会被采用`() {
        val body = parse(
            AnthropicPayload.buildBody(
                req(listOf(ChatMessage.user("嗨")), maxTokens = 128),
                false,
            ),
        )
        assertEquals(128, body["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 工具定义用 input_schema
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `工具用 input_schema 而不是 parameters`() {
        // ══════════════════════════════════════════════════════
        //  ★ 发错字段名**不会报错** —— 工具被静默忽略，模型永不调用。
        //    这是最难查的一类 bug。
        // ══════════════════════════════════════════════════════
        val body = parse(
            AnthropicPayload.buildBody(
                req(
                    listOf(ChatMessage.user("天气")),
                    tools = listOf(
                        ToolSpec(
                            name = "get_weather",
                            description = "查天气",
                            parametersJsonSchema = """{"type":"object","properties":{"city":{"type":"string"}}}""",
                        ),
                    ),
                ),
                false,
            ),
        )
        val tool = body["tools"]!!.jsonArray.first().jsonObject
        assertNotNull("必须有 input_schema", tool["input_schema"])
        assertNull("不能出现 OpenAI 的 parameters", tool["parameters"])
        assertEquals("get_weather", tool["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `非法 schema 不炸 退化成空对象`() {
        val body = parse(
            AnthropicPayload.buildBody(
                req(
                    listOf(ChatMessage.user("嗨")),
                    tools = listOf(ToolSpec("bad", "坏 schema", "not json at all")),
                ),
                false,
            ),
        )
        val schema = body["tools"]!!.jsonArray.first().jsonObject["input_schema"]!!.jsonObject
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `没有工具时不发 tools 字段`() {
        val body = parse(AnthropicPayload.buildBody(req(listOf(ChatMessage.user("嗨"))), false))
        assertNull(body["tools"])
    }

    // ═══════════════════════════════════════════════════════════
    //  ④ 连续同角色必须合并
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `连续两条用户消息会被合并`() {
        // ⚠️ Anthropic 要求 user/assistant **交替**。
        //    用户连发两句在别的厂商能跑，在这里会被拒。
        val body = parse(
            AnthropicPayload.buildBody(
                req(
                    listOf(
                        ChatMessage.user("第一句"),
                        ChatMessage.user("第二句"),
                    ),
                ),
                false,
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals("必须合并成一条", 1, messages.size)
        val blocks = messages.first().jsonObject["content"]!!.jsonArray
        assertEquals("但内容要都在", 2, blocks.size)
    }

    @Test
    fun `正常交替不会被合并`() {
        val body = parse(
            AnthropicPayload.buildBody(
                req(
                    listOf(
                        ChatMessage.user("问"),
                        ChatMessage.assistant("答"),
                        ChatMessage.user("再问"),
                    ),
                ),
                false,
            ),
        )
        assertEquals(3, body["messages"]!!.jsonArray.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑤ 工具结果翻成 user 消息里的 tool_result 块
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `工具结果变成 tool_result 块`() {
        // ⚠️ Anthropic **没有** role="tool"。
        val body = parse(
            AnthropicPayload.buildBody(
                req(
                    // ★ 真实序列：user → assistant(tool_use) → tool_result
                    //   ⚠️ 如果 tool_result 前面也是 user，它们会被合并成一条
                    //      （Anthropic 要求交替），那是**正确**行为。
                    listOf(
                        ChatMessage.user("查一下"),
                        ChatMessage(
                            role = ChatMessage.Role.ASSISTANT,
                            content = listOf(
                                ContentPart.ToolCall(
                                    id = "call_abc",
                                    name = "get_weather",
                                    argumentsJson = """{"city":"上海"}""",
                                ),
                            ),
                        ),
                        ChatMessage(
                            role = ChatMessage.Role.TOOL,
                            content = listOf(ContentPart.Text("""{"temp":25}""")),
                            toolCallId = "call_abc",
                        ),
                    ),
                ),
                false,
            ),
        )
        val arr = body["messages"]!!.jsonArray
        assertEquals("序列应当是 user / assistant / user", 3, arr.size)
        val last = arr.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        val block = last["content"]!!.jsonArray.first().jsonObject
        assertEquals("tool_result", block["type"]!!.jsonPrimitive.content)
        assertEquals("call_abc", block["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `空的工具输出给占位符`() {
        // ⚠️ 空 content 会被 Anthropic 拒 —— 给一句人话占位。
        val body = parse(
            AnthropicPayload.buildBody(
                req(
                    listOf(
                        ChatMessage(
                            role = ChatMessage.Role.TOOL,
                            content = emptyList(),
                            toolCallId = "c1",
                        ),
                    ),
                ),
                false,
            ),
        )
        val block = body["messages"]!!.jsonArray.first().jsonObject["content"]!!
            .jsonArray.first().jsonObject
        assertTrue(
            "不能是空字符串",
            block["content"]!!.jsonPrimitive.content.isNotBlank(),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑥ 流式事件
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `text_delta 翻成 Delta`() {
        val chunks = AnthropicPayload.parseStreamEvent(
            "content_block_delta",
            """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
        )
        assertEquals(1, chunks.size)
        assertEquals("你好", (chunks.first() as ChatChunk.Delta).text)
    }

    @Test
    fun `thinking_delta 翻成 Reasoning 而不是正文`() {
        // ★ 推理内容混进正文会让用户看到内部思考。
        val chunks = AnthropicPayload.parseStreamEvent(
            "content_block_delta",
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"嗯…"}}""",
        )
        assertEquals(1, chunks.size)
        assertEquals("嗯…", (chunks.first() as ChatChunk.Reasoning).text)
    }

    @Test
    fun `content_block_start 的 tool_use 给出 id 和名字`() {
        val chunks = AnthropicPayload.parseStreamEvent(
            "content_block_start",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"get_weather"}}""",
        )
        val d = chunks.first() as ChatChunk.ToolCallDelta
        assertEquals("tu_1", d.id)
        assertEquals("get_weather", d.name)
        assertNull("参数还没来", d.argumentsFragment)
    }

    @Test
    fun `input_json_delta 给出参数分片`() {
        val chunks = AnthropicPayload.parseStreamEvent(
            "content_block_delta",
            """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}""",
        )
        val d = chunks.first() as ChatChunk.ToolCallDelta
        assertNull(d.id)
        assertEquals("""{"city":""", d.argumentsFragment)
    }

    @Test
    fun `message_delta 给出 Done 与用量`() {
        val chunks = AnthropicPayload.parseStreamEvent(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}""",
        )
        val done = chunks.first() as ChatChunk.Done
        assertEquals(42, done.usage.outputTokens)
        assertEquals("end_turn", done.finishReason)
    }

    @Test
    fun `缓存 token 计入输入总量`() {
        // ⚠️ Anthropic 的 input_tokens 不含缓存部分，缓存单列。
        //    漏加会让成本核算偏低。
        val chunks = AnthropicPayload.parseStreamEvent(
            "message_delta",
            """{"type":"message_delta","usage":{"input_tokens":10,"cache_read_input_tokens":90,"output_tokens":5}}""",
        )
        val done = chunks.first() as ChatChunk.Done
        assertEquals(100, done.usage.inputTokens)
        assertEquals(90, done.usage.cachedInputTokens)
    }

    @Test
    fun `DONE 哨兵与空行不产生任何 chunk`() {
        assertTrue(AnthropicPayload.parseStreamEvent("content_block_delta", "[DONE]").isEmpty())
        assertTrue(AnthropicPayload.parseStreamEvent(null, "").isEmpty())
        assertTrue(AnthropicPayload.parseStreamEvent(null, "   ").isEmpty())
    }

    @Test
    fun `无法解析的 data 不炸`() {
        // ⚠️ 厂商加新事件类型不该让整个对话崩掉。
        assertTrue(AnthropicPayload.parseStreamEvent("weird_event", "not json").isEmpty())
        assertTrue(
            AnthropicPayload.parseStreamEvent(
                "content_block_delta",
                """{"type":"content_block_delta","delta":{"type":"brand_new_delta"}}""",
            ).isEmpty(),
        )
    }

    @Test
    fun `error 事件抛 ProtocolError 带出原文`() {
        val e = runCatching {
            AnthropicPayload.parseStreamEvent(
                "error",
                """{"type":"error","error":{"type":"overloaded_error","message":"服务过载"}}""",
            )
        }.exceptionOrNull()
        assertTrue("应当是 ProviderException", e is ProviderException)
        assertTrue("要带出厂商原文：${e?.message}", e!!.message!!.contains("服务过载"))
    }

    @Test
    fun `以 data 里的 type 为准`() {
        // ⚠️ 代理可能把 `event:` 行丢掉。此时只有 data 里的 type 可信。
        val chunks = AnthropicPayload.parseStreamEvent(
            eventName = null,
            data = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"嗯"}}""",
        )
        assertEquals(1, chunks.size)
    }
}
