package com.pocketagent.provider.anthropic

import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * **把统一的 [ChatRequest] 翻译成 Anthropic 的请求体**，以及把流式事件翻译回来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里是整个协议里最容易出错的地方
 * ═══════════════════════════════════════════════════════════════
 *
 * 做成**纯函数**（不发网络请求）是刻意的：
 *  - 可以离线测到每一条规则
 *  - 协议是"翻译"，不是"业务"，本来就不该和 OkHttp 纠缠
 *
 * 六条翻译规则，每条都有对应测试：
 *
 * | 规则 | 不做会怎样 |
 * |---|---|
 * | 系统提示提到顶层 `system` | Anthropic 拒绝 `role="system"` |
 * | 多条系统提示**合并**成一条 | 只能有一个 system 值 |
 * | 工具结果翻译成 `user` 消息里的 `tool_result` 块 | 没有 `role="tool"` |
 * | `max_tokens` 必填（缺省给 [DEFAULT_MAX_TOKENS]） | 直接 400 |
 * | 连续同角色**合并** | Anthropic 要求 user/assistant 交替 |
 * | 工具 schema 用 `input_schema` | 工具被**静默忽略**，模型永不调用 |
 */
object AnthropicPayload {

    /**
     * 缺省输出上限。
     *
     * ⚠️ 不是"随便给个数"：Anthropic 的 `max_tokens` **必填**，
     *    漏了直接 400。而我们的 [ChatRequest.maxTokens] 是可选。
     * ⇒ 必须有一个兜底值。4096 足够一次正常回复，也不会撞上模型的硬上限。
     */
    const val DEFAULT_MAX_TOKENS: Int = 4096

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ═══════════════════════════════════════════════════════════
    //  请求
    // ═══════════════════════════════════════════════════════════

    /**
     * 构造请求体。
     *
     * @param stream 是否流式
     */
    fun buildBody(
        request: ChatRequest,
        stream: Boolean,
        /**
         * 是否启用 prompt 缓存。
         *
         * ★ 默认开 —— 它对长前缀（persona + 工具定义）是纯收益：
         *   命中则省延迟省钱，不命中（前缀太短）也**不报错**。
         */
        promptCaching: Boolean = true,
    ): String {
        val systemTexts = request.messages
            .filter { it.role == ChatMessage.Role.SYSTEM }
            .flatMap { m -> m.content.filterIsInstance<ContentPart.Text>().map { it.text } }

        // ⚠️ 多条系统提示要**合并**：Anthropic 只接受一个 `system` 字符串。
        //    不合并就会丢掉除第一条之外的所有内容 —— 而人格、安全约束
        //    往往就散在多条系统消息里。
        val system = systemTexts
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { null }

        val dialogue = mergeAdjacentSameRole(
            request.messages.filter { it.role != ChatMessage.Role.SYSTEM },
        )

        // ══════════════════════════════════════════════════════════
        //  ★ system 的形式决定能不能挂 cache_control
        // ══════════════════════════════════════════════════════════
        // 字符串形式**放不了** cache_control —— 必须是内容块数组。
        // 所以开了缓存就发数组，关了就维持字符串（更省字节）。
        val systemField: JsonElement? = when {
            system == null -> null
            !promptCaching -> JsonPrimitive(system)
            else -> buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", system)
                        // ★ 断点 1：系统提示（persona / 安全约束）。
                        //   这是全请求里**最稳定**的一段，最适合缓存。
                        put(
                            "cache_control",
                            buildJsonObject { put("type", "ephemeral") },
                        )
                    },
                )
            }
        }

        val dto = AnthropicRequest(
            model = request.model,
            system = systemField,
            messages = dialogue,
            maxTokens = request.maxTokens ?: DEFAULT_MAX_TOKENS,
            temperature = request.temperature,
            stream = stream,
            tools = request.tools.takeIf { it.isNotEmpty() }?.let { specs ->
                specs.mapIndexed { i, spec ->
                    AnthropicTool(
                        name = spec.name,
                        description = spec.description,
                        // ⚠️ `input_schema` 而不是 `parameters`。
                        //    发错字段名**不会报错** —— 工具会被静默忽略。
                        inputSchema = parseSchemaOrEmpty(spec.parametersJsonSchema),
                        // ★ 断点 2：只加在**最后一个**工具上。
                        //   缓存是按前缀算的 —— 标在最后一个即可覆盖整组工具定义。
                        //   ⚠️ 每个工具都标会浪费断点配额（一次最多 4 个），
                        //      超过 4 个会被服务端拒绝。
                        cacheControl = if (promptCaching && i == specs.lastIndex) {
                            AnthropicCacheControl()
                        } else {
                            null
                        },
                    )
                }
            },
        )
        return json.encodeToString(AnthropicRequest.serializer(), dto)
    }

    /**
     * 把系统消息之外的消息翻成 Anthropic 的 user/assistant 交替序列。
     *
     * ⚠️ Anthropic **要求交替**。连续两条 user（比如用户连发两句、
     *    或者工具结果紧跟用户消息）会被拒。
     * ⇒ 把相邻同角色的内容**合并成一条**，而不是丢弃。
     */
    private fun mergeAdjacentSameRole(messages: List<ChatMessage>): List<AnthropicMessage> {
        val out = mutableListOf<AnthropicMessage>()

        for (m in messages) {
            val role = when (m.role) {
                ChatMessage.Role.ASSISTANT -> "assistant"
                // ★ TOOL 没有独立角色 —— 它要包成 user 消息里的 tool_result 块
                ChatMessage.Role.TOOL -> "user"
                else -> "user"
            }
            val blocks = blocksOf(m, role)
            if (blocks.isEmpty()) continue

            val last = out.lastOrNull()
            if (last != null && last.role == role) {
                out[out.size - 1] = last.copy(content = last.content + blocks)
            } else {
                out += AnthropicMessage(role = role, content = blocks)
            }
        }
        return out
    }

    private fun blocksOf(m: ChatMessage, role: String): List<AnthropicBlock> {
        // ★ 工具结果：单独成一个块，不能混进 text
        if (m.role == ChatMessage.Role.TOOL) {
            val text = m.content.filterIsInstance<ContentPart.Text>()
                .joinToString("\n") { it.text }
            return listOf(
                AnthropicBlock(
                    type = "tool_result",
                    toolUseId = m.toolCallId ?: "",
                    // ⚠️ 空内容会被 Anthropic 拒；给个占位说明
                    content = JsonPrimitive(text.ifBlank { "(无输出)" }),
                    isError = false,
                ),
            )
        }

        val blocks = mutableListOf<AnthropicBlock>()
        for (part in m.content) {
            when (part) {
                is ContentPart.Text -> if (part.text.isNotEmpty()) {
                    blocks += AnthropicBlock(type = "text", text = part.text)
                }
                is ContentPart.Image -> {
                    blocks += AnthropicBlock(
                        type = "image",
                        source = AnthropicImageSource(
                            mediaType = part.mimeType,
                            data = Base64NoWrap.encode(part.bytes),
                        ),
                    )
                }
                // ★ 助手发起工具调用 → tool_use 块。
                //   ⚠️ `input` 必须是**对象**，而我们的 argumentsJson 是字符串。
                is ContentPart.ToolCall -> {
                    blocks += AnthropicBlock(
                        type = "tool_use",
                        id = part.id,
                        name = part.name,
                        input = runCatching {
                            Json.parseToJsonElement(part.argumentsJson)
                        }.getOrNull() ?: buildJsonObject { },
                    )
                }
                // ★ 工具结果在**这里的消息级判断**已处理（role == TOOL 分支），
                //   但 ContentPart 也可能直接是 ToolResult —— 一并翻成 tool_result 块。
                is ContentPart.ToolResult -> {
                    blocks += AnthropicBlock(
                        type = "tool_result",
                        toolUseId = part.toolCallId,
                        content = JsonPrimitive(part.content.ifBlank { "(无输出)" }),
                    )
                }
            }
        }
        return blocks
    }

    private fun parseSchemaOrEmpty(raw: String): JsonObject =
        runCatching { Json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            // ⚠️ schema 解析失败时给一个"空对象 schema"而不是崩：
            //    工具被忽略好过整个对话发不出去。
            ?: buildJsonObject { put("type", "object") }

    // ═══════════════════════════════════════════════════════════
    //  流式事件
    // ═══════════════════════════════════════════════════════════

    /**
     * 解析一条 SSE 事件。
     *
     * @param eventName `event:` 行的值（Anthropic 是**具名事件**）
     * @param data `data:` 行的值
     * @return 可能要翻译成 0..n 个 [ChatChunk]；`null` 元素不产生
     *
     * ⚠️ 返回 `List` 而不是单个 chunk：一个 `message_delta` 事件同时带
     *    `stop_reason` 和 `usage`，要拆成 Done；而 `content_block_start`
     *    开始一个工具调用时需要先发 ToolCallDelta(id, name)。
     */
    fun parseStreamEvent(eventName: String?, data: String): List<ChatChunk> {
        if (data.isBlank()) return emptyList()
        if (data == "[DONE]") return emptyList()

        val event = runCatching {
            json.decodeFromString(AnthropicStreamEvent.serializer(), data)
        }.getOrNull() ?: return emptyList()

        // ⚠️ 以 `data` 里的 `type` 为准，而不是 `event:` 行 ——
        //    两者理论上一致，但如果代理把 event 行丢了，只有 data 还可信。
        val type = event.type.ifBlank { eventName.orEmpty() }

        return when (type) {
            "content_block_delta" -> deltaChunks(event)
            "content_block_start" -> startChunks(event)
            "message_start" -> emptyList()
            "message_delta" -> {
                val usage = event.usage
                listOf(
                    ChatChunk.Done(
                        usage = TokenUsage(
                            inputTokens = usage?.totalInput ?: 0,
                            outputTokens = usage?.outputTokens ?: 0,
                            cachedInputTokens = usage?.cacheReadInputTokens ?: 0,
                        ),
                        finishReason = event.delta?.stopReason,
                    ),
                )
            }
            "message_stop" -> emptyList()
            "error" -> throw com.pocketagent.provider.api.ProviderException.ProtocolError(
                event.error?.message ?: "Anthropic 流式错误",
            )
            else -> emptyList()
        }
    }

    private fun deltaChunks(event: AnthropicStreamEvent): List<ChatChunk> {
        val d = event.delta ?: return emptyList()
        return when (d.type) {
            "text_delta" -> d.text?.takeIf { it.isNotEmpty() }
                ?.let { listOf(ChatChunk.Delta(it)) } ?: emptyList()

            // ★ 推理内容单独一路，不要混进正文
            // ⚠️ 推理增量在 `thinking` 字段，不在 `text`。
            "thinking_delta" -> d.thinking?.takeIf { it.isNotEmpty() }
                ?.let { listOf(ChatChunk.Reasoning(it)) } ?: emptyList()

            // ★ 工具入参是**分片**到达的 JSON 字符串，由调用方拼接
            "input_json_delta" -> listOf(
                ChatChunk.ToolCallDelta(
                    id = null,
                    name = null,
                    argumentsFragment = d.partialJson.orEmpty(),
                ),
            )

            // signature_delta 等对上层无意义
            else -> emptyList()
        }
    }

    private fun startChunks(event: AnthropicStreamEvent): List<ChatChunk> {
        val block = event.contentBlock ?: return emptyList()
        if (block.type != "tool_use") return emptyList()
        // 工具调用的"开场"：id + name，参数随后由 input_json_delta 分片送来
        return listOf(
            ChatChunk.ToolCallDelta(
                id = block.id,
                name = block.name,
                argumentsFragment = null,
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  非流式响应
    // ═══════════════════════════════════════════════════════════

    /** 把非流式响应体翻成 chunk 列表（与流式走同一条上层通路）。 */
    fun parseFullResponse(body: String): List<ChatChunk> {
        val resp = runCatching {
            json.decodeFromString(AnthropicResponse.serializer(), body)
        }.getOrNull() ?: throw com.pocketagent.provider.api.ProviderException.ProtocolError(
            "Anthropic 响应无法解析",
        )

        val out = mutableListOf<ChatChunk>()
        for (block in resp.content) {
            when (block.type) {
                "text" -> block.text?.takeIf { it.isNotEmpty() }?.let { out += ChatChunk.Delta(it) }
                "tool_use" -> out += ChatChunk.ToolCallDelta(
                    id = block.id,
                    name = block.name,
                    argumentsFragment = block.input?.toString(),
                )
                else -> Unit
            }
        }
        val u = resp.usage
        out += ChatChunk.Done(
            usage = TokenUsage(
                inputTokens = u?.totalInput ?: 0,
                outputTokens = u?.outputTokens ?: 0,
                cachedInputTokens = u?.cacheReadInputTokens ?: 0,
            ),
            finishReason = resp.stopReason,
        )
        return out
    }
}
