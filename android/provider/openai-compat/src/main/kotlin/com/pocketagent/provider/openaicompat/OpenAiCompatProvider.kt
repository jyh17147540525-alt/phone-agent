package com.pocketagent.provider.openaicompat

import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.Cost
import com.pocketagent.provider.api.KeyValidationResult
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ModelInfo
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.ProviderException
import com.pocketagent.provider.api.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions 协议的通用实现。
 *
 * 一份代码覆盖 OpenAI / DeepSeek / 通义 / 火山方舟 / Kimi / 智谱 / 硅基流动 /
 * OpenRouter / Azure OpenAI / 本地 Ollama、LM Studio 等十余家厂商。
 *
 * ═══════════════════════════════════════════════════════════════
 *  厂商差异处理策略
 * ═══════════════════════════════════════════════════════════════
 *
 * 差异集中在 [ProviderProfile] 里，**不在代码里写 if-else 判断厂商**。
 * 新增厂商 = 加一个 profile，不改实现。
 *
 * 已知差异：
 *  - 鉴权：多数 `Authorization: Bearer`，Azure 用 `api-key` 头
 *  - 推理内容：DeepSeek 用 `reasoning_content`
 *  - 流式用量：部分厂商不支持 `stream_options`，需 profile 里关掉
 *  - 视觉：部分厂商只接受 http 图片 URL，不接受 data URI
 */
class OpenAiCompatProvider(
    private val profile: ProviderProfile,
    private val client: OkHttpClient = defaultClient(),
) : LlmProvider {

    override val id: String get() = profile.id
    override val displayName: String get() = profile.displayName
    override val authScheme: AuthScheme get() = profile.authScheme
    override val defaultBaseUrl: String get() = profile.defaultBaseUrl

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    override fun capabilities(): Set<Capability> = profile.capabilities

    // ═══════════════════════════════════════════════════════════
    //  Key 校验
    // ═══════════════════════════════════════════════════════════

    /**
     * 校验 Key。
     *
     * ⚠️ 必须区分「Key 无效」与「网络不可达」——
     *    后者若返回 Invalid，用户会误删一个好 Key。
     */
    override suspend fun validateKey(credential: ProviderCredential): KeyValidationResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val modelsPath = profile.modelsPath
                ?: return@withContext KeyValidationResult.Unreachable("该厂商不支持模型列表探测")

            val request = buildRequest(credential, modelsPath, method = "GET")
            try {
                client.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - started
                    when {
                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            val count = runCatching {
                                json.decodeFromString<ModelsResponse>(body).data.size
                            }.getOrDefault(0)
                            KeyValidationResult.Valid(modelCount = count, latencyMs = elapsed)
                        }

                        response.code == 401 || response.code == 403 ->
                            KeyValidationResult.Invalid

                        response.code == 402 ->
                            KeyValidationResult.NoBalance(readErrorMessage(response))

                        response.code == 429 -> {
                            val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            KeyValidationResult.RateLimited(retryAfter)
                        }

                        else -> KeyValidationResult.Unreachable(
                            "HTTP ${response.code}: ${readErrorMessage(response)}"
                        )
                    }
                }
            } catch (e: IOException) {
                // 网络问题 ≠ Key 无效。这一条至关重要。
                KeyValidationResult.Unreachable(e.message ?: "网络不可达")
            }
        }

    // ═══════════════════════════════════════════════════════════
    //  模型列表
    // ═══════════════════════════════════════════════════════════

    override suspend fun listModels(credential: ProviderCredential): List<ModelInfo>? {
        val path = profile.modelsPath ?: return null
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(buildRequest(credential, path, method = "GET")).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    val parsed = runCatching {
                        json.decodeFromString<ModelsResponse>(body)
                    }.getOrNull() ?: return@withContext null

                    parsed.data.map { dto ->
                        ModelInfo(
                            id = dto.id,
                            displayName = dto.id,
                            capabilities = inferCapabilities(dto.id),
                            contextWindow = dto.context_length ?: profile.defaultContextWindow,
                            inputPricePerMillion = dto.pricing?.prompt?.toDoubleOrNull()?.times(1_000_000),
                            outputPricePerMillion = dto.pricing?.completion?.toDoubleOrNull()?.times(1_000_000),
                        )
                    }
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    /**
     * 从模型名推断能力。
     *
     * ⚠️ 这是启发式判断，不保证准确。厂商不提供能力元数据时只能这样。
     *    准确的判断应优先用 [ModelInfo.capabilities]，或让用户手动标注。
     */
    private fun inferCapabilities(modelId: String): Set<Capability> {
        val m = modelId.lowercase()
        return buildSet {
            add(Capability.STREAM)
            if (profile.capabilities.contains(Capability.TOOL_CALL)) add(Capability.TOOL_CALL)
            if (profile.capabilities.contains(Capability.JSON_MODE)) add(Capability.JSON_MODE)

            val visionHints = listOf("vision", "-vl", "gpt-4o", "gpt-4.1", "gpt-5", "claude",
                "gemini", "qwen-vl", "glm-4v", "internvl", "llava", "pixtral", "omni")
            if (visionHints.any { m.contains(it) }) add(Capability.VISION)

            val longCtxHints = listOf("128k", "200k", "1m", "long")
            if (longCtxHints.any { m.contains(it) }) add(Capability.LONG_CTX)

            if (profile.id == "local-ollama" || profile.id == "local-lmstudio") {
                add(Capability.LOCAL)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  对话
    // ═══════════════════════════════════════════════════════════

    override fun chat(request: ChatRequest, credential: ProviderCredential): Flow<ChatChunk> =
        channelFlow {
            val payload = buildChatPayload(request, stream = true)
            val httpRequest = buildRequest(
                credential = credential,
                path = profile.chatPath,
                method = "POST",
                body = payload,
            )

            val call = client.newCall(httpRequest)

            // 协程被取消时（用户中止）中断 OkHttp 的阻塞读取。
            // 不做这一步的话，取消后读取会一直挂着直到超时。
            invokeOnClose { call.cancel() }

            // ── 契约：无论走哪条正常路径，**恰好发出一个** ChatChunk.Done ──
            // 早先的实现会发两个（usage 分片一个 + [DONE] 兜底一个），
            // 而厂商若既不返回 usage 又不发 [DONE]（Ollama 就是这样），
            // 则一个都不发 —— 调用方永远等不到结束信号。两个方向都是 bug。
            var usage: TokenUsage? = null
            var finishReason: String? = null

            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw mapHttpError(response)

                    val body = response.body
                        ?: throw ProviderException.ProtocolError("响应体为空")

                    val parser = SseParser(body.source())

                    for (event in parser) {
                        currentCoroutineContext().ensureActive()

                        when (event) {
                            is SseEvent.Done -> break

                            is SseEvent.Data -> {
                                val chunk = runCatching {
                                    json.decodeFromString<ChatCompletionChunk>(event.payload)
                                }.getOrNull() ?: continue   // 无法解析的分片直接跳过，不中断流

                                emitChunks(chunk)

                                chunk.usage?.let { u ->
                                    usage = TokenUsage(
                                        inputTokens = u.promptTokens,
                                        outputTokens = u.completionTokens,
                                        cachedInputTokens = u.promptDetails?.cachedTokens ?: 0,
                                    )
                                }
                                // finish_reason 只出现在正文最后一片，usage 分片的 choices 是空的，
                                // 所以这里要取"最后一个非空值"，不能直接覆盖。
                                chunk.choices.firstOrNull()?.finishReason?.let { finishReason = it }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // 用户点"中止"会走到这里（call.cancel() 让阻塞读取抛 IOException）。
                // 若协程已被取消，ensureActive() 会抛 CancellationException ——
                // 那才是正确的语义，不能把主动取消包装成网络错误上报给用户。
                currentCoroutineContext().ensureActive()
                throw ProviderException.NetworkError(e)
            }

            send(ChatChunk.Done(usage ?: TokenUsage(0, 0), finishReason))
        }.flowOn(Dispatchers.IO)

    /**
     * 把一条 SSE 分片翻译成 [ChatChunk] 并发出去。
     *
     * ⚠️ 本函数**不负责**发 [ChatChunk.Done]。结束信号由 [chat] 在流末尾统一发出，
     *    以保证"恰好一个 Done"的契约（见 chat 内的注释）。
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<ChatChunk>.emitChunks(
        chunk: ChatCompletionChunk,
    ) {
        val choice = chunk.choices.firstOrNull()

        // 推理内容（DeepSeek-R1 等）
        choice?.delta?.reasoningContent?.takeIf { it.isNotEmpty() }?.let {
            send(ChatChunk.Reasoning(it))
        }

        // 正文增量
        choice?.delta?.content?.takeIf { it.isNotEmpty() }?.let {
            send(ChatChunk.Delta(it))
        }

        // 工具调用增量（参数会分多片到达，由上层拼接）
        choice?.delta?.toolCalls?.forEach { tc ->
            send(
                ChatChunk.ToolCallDelta(
                    id = tc.id,
                    name = tc.function?.name,
                    argumentsFragment = tc.function?.arguments,
                )
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Token 计数与费用估算
    // ═══════════════════════════════════════════════════════════

    /**
     * 启发式 token 估算。
     *
     * ⚠️ 这是**估算**，不是精确计数。用途仅限于本地预算熔断，
     *    绝不能用于计费或展示"精确消耗"。
     *
     * 经验值：中文约 1.5 字/token，英文约 4 字符/token。
     */
    override fun countTokens(messages: List<ChatMessage>, model: String): Int {
        var total = 0
        for (msg in messages) {
            for (part in msg.content) {
                total += when (part) {
                    is ContentPart.Text -> estimateTextTokens(part.text)
                    // 图片按视觉 token 粗估：一张 1280 长边的图约 500 token
                    is ContentPart.Image -> 500
                    is ContentPart.ToolCall -> estimateTextTokens(part.argumentsJson) + 20
                    is ContentPart.ToolResult -> estimateTextTokens(part.content)
                }
            }
            total += MESSAGE_OVERHEAD_TOKENS
        }
        return total
    }

    private fun estimateTextTokens(text: String): Int {
        var cjk = 0
        var other = 0
        for (c in text) {
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF) {
                cjk++
            } else {
                other++
            }
        }
        return (cjk / 1.5).toInt() + (other / 4) + 1
    }

    /**
     * 费用估算。结果喂给本地预算熔断器，不用于真实计费。
     *
     * ⚠️ 必须对输入做钳制：厂商偶发会返回 `cached_tokens > prompt_tokens`
     *    （协议实现 bug 或跨请求统计口径不一致）。若不钳制，
     *    `input - cached` 会变成负数，估算出**负费用**，而熔断器一看到
     *    负值就会以为"还有额度"，把预算保护整个废掉。
     *    宁可少算，不可算出负值。
     */
    override fun estimateCost(usage: TokenUsage, model: String): Cost {
        val info = profile.defaultModels.firstOrNull { it.id == model }
        val inPrice = info?.inputPricePerMillion ?: 0.0
        val outPrice = info?.outputPricePerMillion ?: 0.0
        val cachedDiscount = 0.1   // 缓存命中的输入通常按 10% 计费

        val inputTokens = usage.inputTokens.coerceAtLeast(0)
        val cachedTokens = usage.cachedInputTokens.coerceIn(0, inputTokens)
        val outputTokens = usage.outputTokens.coerceAtLeast(0)

        val billableInput = inputTokens - cachedTokens
        val usd = (billableInput / 1_000_000.0) * inPrice +
            (cachedTokens / 1_000_000.0) * inPrice * cachedDiscount +
            (outputTokens / 1_000_000.0) * outPrice
        return Cost(usd)
    }

    // ═══════════════════════════════════════════════════════════
    //  请求构造
    // ═══════════════════════════════════════════════════════════

    private fun buildChatPayload(request: ChatRequest, stream: Boolean): String {
        val payload = ChatCompletionRequest(
            model = request.model,
            messages = request.messages.map { it.toRequestMessage() },
            stream = stream,
            temperature = request.temperature,
            maxTokens = request.maxTokens,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map { tool ->
                RequestTool(
                    function = RequestTool.FunctionDef(
                        name = tool.name,
                        description = tool.description,
                        parameters = json.parseToJsonElement(tool.parametersJsonSchema),
                    )
                )
            },
            responseFormat = if (request.jsonMode) ResponseFormat("json_object") else null,
            streamOptions = if (stream && profile.supportsStreamUsage) StreamOptions(true) else null,
        )
        return json.encodeToString(ChatCompletionRequest.serializer(), payload)
    }

    private fun ChatMessage.toRequestMessage(): RequestMessage {
        // 纯文本走 String，多模态走数组 —— 这是 OpenAI 协议的约定
        val hasNonText = content.any { it !is ContentPart.Text }

        val contentJson = if (!hasNonText) {
            JsonPrimitive(content.filterIsInstance<ContentPart.Text>().joinToString("") { it.text })
        } else {
            buildJsonArray {
                content.forEach { part ->
                    when (part) {
                        is ContentPart.Text -> add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            }
                        )

                        is ContentPart.Image -> add(
                            buildJsonObject {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    buildJsonObject { put("url", part.toDataUri()) },
                                )
                            }
                        )

                        else -> Unit   // 工具相关的内容不放进 content
                    }
                }
            }
        }

        return RequestMessage(
            role = role.name.lowercase(),
            content = contentJson,
            toolCallId = toolCallId,
            toolCalls = content.filterIsInstance<ContentPart.ToolCall>().takeIf { it.isNotEmpty() }
                ?.map { tc ->
                    RequestToolCall(
                        id = tc.id,
                        function = RequestToolCall.FunctionCall(tc.name, tc.argumentsJson),
                    )
                },
        )
    }

    private fun ContentPart.Image.toDataUri(): String {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        return "data:$mimeType;base64,$b64"
    }

    private fun buildRequest(
        credential: ProviderCredential,
        path: String,
        method: String,
        body: String? = null,
    ): Request {
        val baseUrl = (credential.baseUrlOverride ?: profile.defaultBaseUrl).trimEnd('/')
        val url = if (path.startsWith("http")) path else "$baseUrl$path"

        val builder = Request.Builder().url(url)

        // ── 鉴权 ──────────────────────────────────────────────
        // ⚠️ 明文只在这一行出现，不赋值给变量
        when (val scheme = profile.authScheme) {
            is AuthScheme.Bearer ->
                builder.header("Authorization", "Bearer ${credential.apiKeyForRequest()}")

            is AuthScheme.ApiKeyHeader ->
                builder.header(profile.apiKeyHeaderName, credential.apiKeyForRequest())

            is AuthScheme.QueryParam ->
                builder.url(url.replace("?", "?" + scheme.paramName + "=" + credential.apiKeyForRequest()))

            AuthScheme.None -> Unit
        }

        profile.extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        credential.extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
        }

        return builder.build()
    }

    // ═══════════════════════════════════════════════════════════
    //  错误映射
    // ═══════════════════════════════════════════════════════════

    /**
     * 把 HTTP 错误映射成统一的 [ProviderException]。
     *
     * ⚠️ 错误信息里**绝不能带 Key**。这里只取响应体内容，不拼请求信息。
     */
    private fun mapHttpError(response: Response): ProviderException {
        val code = response.code
        val message = readErrorMessage(response)

        return when (code) {
            401, 403 -> ProviderException.AuthFailed("鉴权失败：$message")
            402 -> ProviderException.NoBalance()
            429 -> {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                ProviderException.RateLimited(retryAfter)
            }
            400 -> {
                // 内容被安全策略拦截时，多数厂商返回 400 + 特定关键词
                val filtered = listOf("content_filter", "content policy", "safety", "敏感", "违规")
                    .any { message.contains(it, ignoreCase = true) }
                if (filtered) ProviderException.ContentFiltered(message)
                else ProviderException.ProtocolError("请求被拒绝（400）：$message")
            }
            404 -> ProviderException.ProtocolError("接口不存在（404），可能是 BaseUrl 配置错误：$message")
            408, 504 -> ProviderException.Timeout()
            in 500..599 -> ProviderException.ProtocolError("服务端错误（$code）：$message")
            else -> ProviderException.ProtocolError("HTTP $code：$message")
        }
    }

    private fun readErrorMessage(response: Response): String = runCatching {
        val raw = response.body?.string().orEmpty()
        if (raw.isBlank()) return@runCatching "无错误详情"
        // 优先按标准结构解析；失败则截断原文
        runCatching { json.decodeFromString<ErrorResponse>(raw).bestMessage }
            .getOrElse { raw.take(300) }
    }.getOrDefault("无法读取错误详情")

    companion object {
        /**
         * 本模块唯一的 Json 配置。
         *
         * ⚠️ 三个开关都不是随手写的，改之前先读完：
         *
         *  - `ignoreUnknownKeys = true` —— 厂商加字段不能把我们搞崩。
         *
         *  - `explicitNulls = false` —— 值为 null 的字段整个不出现。
         *    部分厂商对多余的 `"temperature": null` 直接返回 400。
         *
         *  - `encodeDefaults = true` —— **这一条踩过坑，别再改回去。**
         *    曾经设成 false，后果是"值等于默认值的字段被静默丢弃"：
         *      · `stream_options: {"include_usage": true}` 被序列化成 `{}`，
         *        于是**永远拿不到 token 用量**，预算熔断器形同虚设；
         *      · `tools[].type: "function"` 直接消失，OpenAI 收到后报 400，
         *        而错误信息完全指不到这里。
         *    这类 bug 的共同点是：**不报错、不崩溃，只是安静地少做一件事**。
         *    代价是 `"stream": false` 也会被显式发出去 —— 完全无害，可以接受。
         */
        internal val PROVIDER_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 每条消息的角色/分隔符开销，OpenAI 官方文档的经验值 */
        private const val MESSAGE_OVERHEAD_TOKENS = 4

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 读取超时设长：模型首 token 延迟可能很高，但流式过程中会持续有数据
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

// ═══════════════════════════════════════════════════════════════
//  Provider Profile —— 厂商差异集中在这里
// ═══════════════════════════════════════════════════════════════

/**
 * 厂商配置。
 *
 * 新增厂商 = 加一个 profile，**不改实现代码**。
 * 这也是"自定义 Provider"功能的底层支撑：用户可以自己填一份 profile。
 */
data class ProviderProfile(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val authScheme: AuthScheme,
    val chatPath: String = "/chat/completions",
    val modelsPath: String? = "/models",
    val apiKeyHeaderName: String = "x-api-key",
    val capabilities: Set<Capability> = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE),
    val defaultModels: List<ModelInfo> = emptyList(),
    val defaultContextWindow: Int = 32_768,
    /** 是否支持 `stream_options.include_usage`（部分厂商不支持，会报错） */
    val supportsStreamUsage: Boolean = true,
    val extraHeaders: Map<String, String> = emptyMap(),
)

/**
 * 内置厂商配置表。
 *
 * ⚠️ BaseUrl 与模型清单会随厂商变更而失效，需定期核对。
 *    用户可在应用内覆盖 BaseUrl，这是应对厂商改协议的逃生舱。
 */
object ProviderProfiles {

    val OpenAI = ProviderProfile(
        id = "openai",
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            Capability.STREAM, Capability.VISION, Capability.TOOL_CALL,
            Capability.JSON_MODE, Capability.LONG_CTX,
        ),
        defaultContextWindow = 128_000,
        defaultModels = listOf(
            ModelInfo(
                id = "gpt-4o-mini",
                displayName = "GPT-4o mini",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 128_000,
                inputPricePerMillion = 0.15,
                outputPricePerMillion = 0.60,
            ),
            ModelInfo(
                id = "gpt-4o",
                displayName = "GPT-4o",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 128_000,
                inputPricePerMillion = 2.50,
                outputPricePerMillion = 10.00,
                recommended = true,
            ),
            ModelInfo(
                id = "gpt-4.1-mini",
                displayName = "GPT-4.1 mini",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 1_000_000,
                inputPricePerMillion = 0.40,
                outputPricePerMillion = 1.60,
            ),
            ModelInfo(
                id = "o4-mini",
                displayName = "o4-mini（推理）",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.LONG_CTX),
                contextWindow = 200_000,
                inputPricePerMillion = 1.10,
                outputPricePerMillion = 4.40,
            ),
        ),
    )

    val DeepSeek = ProviderProfile(
        id = "deepseek",
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            Capability.STREAM, Capability.TOOL_CALL,
            Capability.JSON_MODE, Capability.LONG_CTX, Capability.CACHE,
        ),
        defaultContextWindow = 64_000,
        defaultModels = listOf(
            ModelInfo(
                id = "deepseek-chat",
                displayName = "DeepSeek V3",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX, Capability.CACHE),
                contextWindow = 64_000,
                inputPricePerMillion = 0.27,
                outputPricePerMillion = 1.10,
                recommended = true,
            ),
            ModelInfo(
                id = "deepseek-reasoner",
                displayName = "DeepSeek R1（推理）",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.CACHE),
                contextWindow = 64_000,
                inputPricePerMillion = 0.55,
                outputPricePerMillion = 2.19,
            ),
        ),
    )

    /** 阿里通义 DashScope 的 OpenAI 兼容模式 */
    val DashScope = ProviderProfile(
        id = "dashscope",
        displayName = "阿里通义（DashScope）",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            Capability.STREAM, Capability.VISION, Capability.TOOL_CALL,
            Capability.JSON_MODE, Capability.LONG_CTX,
        ),
        defaultContextWindow = 128_000,
        defaultModels = listOf(
            ModelInfo(
                id = "qwen-turbo",
                displayName = "通义千问 Turbo",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 128_000,
                inputPricePerMillion = 0.05,
                outputPricePerMillion = 0.20,
                recommended = true,
            ),
            ModelInfo(
                id = "qwen-plus",
                displayName = "通义千问 Plus",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 128_000,
                inputPricePerMillion = 0.40,
                outputPricePerMillion = 1.20,
            ),
            ModelInfo(
                id = "qwen-max",
                displayName = "通义千问 Max",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 32_000,
                inputPricePerMillion = 2.40,
                outputPricePerMillion = 9.60,
            ),
            ModelInfo(
                id = "qwen-vl-max",
                displayName = "通义千问 VL（视觉）",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.LONG_CTX),
                contextWindow = 32_000,
                inputPricePerMillion = 3.00,
                outputPricePerMillion = 9.00,
            ),
        ),
    )

    /** 火山方舟（豆包模型） */
    val VolcArk = ProviderProfile(
        id = "volc-ark",
        displayName = "火山方舟（豆包）",
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            Capability.STREAM, Capability.VISION, Capability.TOOL_CALL,
            Capability.JSON_MODE, Capability.LONG_CTX,
        ),
        defaultContextWindow = 128_000,
        defaultModels = listOf(
            ModelInfo(
                id = "doubao-1.5-lite-32k",
                displayName = "豆包 1.5 Lite",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE),
                contextWindow = 32_000,
                inputPricePerMillion = 0.02,
                outputPricePerMillion = 0.04,
            ),
            ModelInfo(
                id = "doubao-1.5-pro-32k",
                displayName = "豆包 1.5 Pro",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE),
                contextWindow = 32_000,
                inputPricePerMillion = 0.11,
                outputPricePerMillion = 0.28,
                recommended = true,
            ),
            ModelInfo(
                id = "doubao-1.5-pro-256k",
                displayName = "豆包 1.5 Pro（256K）",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 256_000,
                inputPricePerMillion = 0.11,
                outputPricePerMillion = 0.28,
            ),
        ),
    )

    /** OpenRouter —— 一个 Key 通吃几十家模型，新用户的最佳起点 */
    val OpenRouter = ProviderProfile(
        id = "openrouter",
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            Capability.STREAM, Capability.VISION, Capability.TOOL_CALL,
            Capability.JSON_MODE, Capability.LONG_CTX,
        ),
        defaultContextWindow = 128_000,
        extraHeaders = mapOf(
            // OpenRouter 建议带来源标识，便于统计与配额
            "HTTP-Referer" to "https://github.com/pocketagent",
            "X-Title" to "PocketAgent",
        ),
        // ⚠️ OpenRouter 是**聚合商**，它的模型清单由上游厂商决定，变动频繁。
        //    这里只放几个跨厂商的代表性模型，用来给新用户一个能跑通的默认值；
        //    它真正的价值是"一个 Key 通吃"，模型名用户可自由填。
        defaultModels = listOf(
            ModelInfo(
                id = "google/gemini-2.0-flash-001",
                displayName = "Gemini 2.0 Flash（经 OpenRouter）",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 1_000_000,
                inputPricePerMillion = 0.10,
                outputPricePerMillion = 0.40,
                recommended = true,
            ),
            ModelInfo(
                id = "deepseek/deepseek-chat",
                displayName = "DeepSeek V3（经 OpenRouter）",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 64_000,
                inputPricePerMillion = 0.14,
                outputPricePerMillion = 0.28,
            ),
            ModelInfo(
                id = "anthropic/claude-3.5-sonnet",
                displayName = "Claude 3.5 Sonnet（经 OpenRouter）",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.LONG_CTX),
                contextWindow = 200_000,
                inputPricePerMillion = 3.00,
                outputPricePerMillion = 15.00,
            ),
        ),
    )

    val Moonshot = ProviderProfile(
        id = "moonshot",
        displayName = "月之暗面 Kimi",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            Capability.STREAM, Capability.TOOL_CALL,
            Capability.JSON_MODE, Capability.LONG_CTX,
        ),
        defaultContextWindow = 128_000,
        defaultModels = listOf(
            ModelInfo(
                id = "moonshot-v1-8k",
                displayName = "Kimi 8K",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL),
                contextWindow = 8_000,
                inputPricePerMillion = 1.68,
                outputPricePerMillion = 1.68,
            ),
            ModelInfo(
                id = "moonshot-v1-32k",
                displayName = "Kimi 32K",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL),
                contextWindow = 32_000,
                inputPricePerMillion = 3.36,
                outputPricePerMillion = 3.36,
                recommended = true,
            ),
            ModelInfo(
                id = "moonshot-v1-128k",
                displayName = "Kimi 128K",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.LONG_CTX),
                contextWindow = 128_000,
                inputPricePerMillion = 8.40,
                outputPricePerMillion = 8.40,
            ),
        ),
    )

    val Zhipu = ProviderProfile(
        id = "zhipu",
        displayName = "智谱 GLM",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            Capability.STREAM, Capability.VISION, Capability.TOOL_CALL,
            Capability.JSON_MODE,
        ),
        defaultModels = listOf(
            ModelInfo(
                id = "glm-4-flash",
                displayName = "GLM-4 Flash（免费档）",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE),
                contextWindow = 128_000,
                // 免费模型价格填 0.0 而不是 null —— 这是"确知免费"，
                // 与"价格未知"是两回事。见 ModelTier.estimatedCost 的注释。
                inputPricePerMillion = 0.0,
                outputPricePerMillion = 0.0,
            ),
            ModelInfo(
                id = "glm-4-plus",
                displayName = "GLM-4 Plus",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE),
                contextWindow = 128_000,
                inputPricePerMillion = 0.70,
                outputPricePerMillion = 0.70,
                recommended = true,
            ),
        ),
    )

    val SiliconFlow = ProviderProfile(
        id = "siliconflow",
        displayName = "硅基流动",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL),
        defaultModels = listOf(
            ModelInfo(
                id = "Qwen/Qwen2.5-7B-Instruct",
                displayName = "Qwen2.5 7B",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL),
                contextWindow = 32_000,
                inputPricePerMillion = 0.05,
                outputPricePerMillion = 0.05,
                recommended = true,
            ),
            ModelInfo(
                id = "deepseek-ai/DeepSeek-V3",
                displayName = "DeepSeek V3（经硅基流动）",
                capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL, Capability.JSON_MODE),
                contextWindow = 64_000,
                inputPricePerMillion = 0.27,
                outputPricePerMillion = 1.10,
            ),
        ),
    )

    /** 本地 Ollama —— 零成本联调，也是"纯本地模式"的隐私选项 */
    val Ollama = ProviderProfile(
        id = "local-ollama",
        displayName = "本地 Ollama",
        defaultBaseUrl = "http://127.0.0.1:11434/v1",
        authScheme = AuthScheme.None,
        capabilities = setOf(Capability.STREAM, Capability.LOCAL),
        defaultContextWindow = 32_768,
        // Ollama 的 /v1/models 可用，但不返回价格与上下文长度
        supportsStreamUsage = false,
        // 本地模型**没有 API 成本**，但这里刻意不填价格：
        // 填 0.0 会让 `cheapestFor` 把本地模型推成"最省钱的选择"，
        // 而它的实际代价是电费和内存占用 —— 那些不在模型价格的量纲里。
        // 留空 = "价格未知"，比较时被跳过，这是诚实的做法。
    )

    val LmStudio = ProviderProfile(
        id = "local-lmstudio",
        displayName = "本地 LM Studio",
        defaultBaseUrl = "http://127.0.0.1:1234/v1",
        authScheme = AuthScheme.None,
        capabilities = setOf(Capability.STREAM, Capability.LOCAL),
        defaultContextWindow = 32_768,
        supportsStreamUsage = false,
        // 同 Ollama：不填价格，见上
    )

    /** Azure OpenAI —— 注意：BaseUrl 由用户提供，且鉴权用 api-key 头 */
    val AzureOpenAi = ProviderProfile(
        id = "azure-openai",
        displayName = "Azure OpenAI",
        defaultBaseUrl = "",   // 必须由用户填写自己的资源地址
        authScheme = AuthScheme.ApiKeyHeader,
        apiKeyHeaderName = "api-key",
        modelsPath = null,     // Azure 没有统一的 /models
        // ⚠️ Azure 的模型 id 是**用户自己起的部署名**，不是厂商的模型名。
        //    这里列的 id 只是价格参考锚点 —— 用户实际填的是他的部署名，
        //    查不到时会回落成裸 id + 未知价格，那是正确的行为。
        defaultModels = listOf(
            ModelInfo(
                id = "gpt-4o-mini",
                displayName = "GPT-4o mini（部署名需自填）",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 128_000,
                inputPricePerMillion = 0.15,
                outputPricePerMillion = 0.60,
                recommended = true,
            ),
            ModelInfo(
                id = "gpt-4o",
                displayName = "GPT-4o（部署名需自填）",
                capabilities = setOf(Capability.STREAM, Capability.VISION, Capability.TOOL_CALL, Capability.JSON_MODE, Capability.LONG_CTX),
                contextWindow = 128_000,
                inputPricePerMillion = 2.50,
                outputPricePerMillion = 10.00,
            ),
        ),
    )

    /** 全部内置配置，用于 UI 展示与"自定义 Provider"的起点 */
    val all: List<ProviderProfile> = listOf(
        OpenRouter, DeepSeek, OpenAI, DashScope, VolcArk,
        Moonshot, Zhipu, SiliconFlow, Ollama, LmStudio, AzureOpenAi,
    )

    fun byId(id: String): ProviderProfile? = all.firstOrNull { it.id == id }
}
