package com.pocketagent.provider.anthropic

import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.Cost
import com.pocketagent.provider.api.KeyValidationResult
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ModelInfo
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.ProviderException
import com.pocketagent.provider.api.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * **Anthropic Messages API 的 provider 实现**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：这个模块原来是空的
 * ═══════════════════════════════════════════════════════════════
 *
 * `:provider:anthropic` 此前零源码。补它的理由很具体：
 * OpenCode 上 **Claude / MiniMax M3 / Qwen3.8 Max / Qwen3.8 Flash /
 * Qwen3.7 Plus 只走这个协议**，用 OpenAI 兼容 provider 对它们注定失败。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 与 OpenAI 兼容 provider 的三处关键差异
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 鉴权头是 `x-api-key`，不是 `Authorization: Bearer`**
 * **② 必须带 `anthropic-version`** —— 不带会被拒（400）。
 *    这个头是**协议版本**，不是模型版本，写死即可。
 * **③ SSE 是具名事件**（`event:` + `data:` 两行 + 空行分隔），
 *    而 OpenAI 是匿名 `data:` 行。解析方式不同。
 *
 * ⚠️ 协议翻译全部委托 [AnthropicPayload] —— 那是纯函数，有 22 条离线测试。
 *    本类只做"收发 + 错误翻译"，不含任何翻译规则。
 */
class AnthropicProvider(
    private val client: OkHttpClient = defaultClient(),
    override val defaultBaseUrl: String = DEFAULT_BASE_URL,
) : LlmProvider {

    override val id: String = "anthropic"

    override val displayName: String = "Anthropic (Claude)"

    override val authScheme: AuthScheme = AuthScheme.ApiKeyHeader

    override fun capabilities(): Set<Capability> = setOf(
        Capability.STREAM,
        Capability.TOOL_CALL,
        Capability.VISION,
        Capability.LONG_CTX,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ═══════════════════════════════════════════════════════════
    //  校验 Key
    // ═══════════════════════════════════════════════════════════

    /**
     * 用 `GET /models` 校验。
     *
     * ★ 这是**最小代价**的请求：不消耗 token，不计费。
     * ⚠️ 网络不可达时返回 [KeyValidationResult.Unreachable]，
     *    **绝不**返回 Invalid —— 否则用户会误删一个好 Key。
     */
    override suspend fun validateKey(credential: ProviderCredential): KeyValidationResult =
        withContext(Dispatchers.IO) {
            try {
                val models = listModels(credential)
                    ?: return@withContext KeyValidationResult.Unreachable("服务端没有返回模型清单")
                if (models.isEmpty()) {
                    // ⚠️ 空清单不等于 Key 无效 —— 可能只是账号没开通模型。
                    //    归为 Unreachable，避免用户误删好 Key。
                    return@withContext KeyValidationResult.Unreachable("这把 Key 下没有可用模型")
                }
                KeyValidationResult.Valid(modelCount = models.size, latencyMs = 0)
            } catch (e: ProviderException.AuthFailed) {
                KeyValidationResult.Invalid
            } catch (e: ProviderException.RateLimited) {
                KeyValidationResult.RateLimited(e.retryAfterSeconds)
            } catch (e: ProviderException.NoBalance) {
                KeyValidationResult.NoBalance("余额不足")
            } catch (e: ProviderException.NetworkError) {
                KeyValidationResult.Unreachable("网络不可达")
            } catch (e: ProviderException.Timeout) {
                KeyValidationResult.Unreachable("请求超时")
            } catch (e: Exception) {
                // ⚠️ 未知异常也归为"不可达"而不是"无效"：
                //    宁可让用户重试，也不要让他删掉好 Key。
                KeyValidationResult.Unreachable(e.message ?: "未知错误")
            }
        }

    // ═══════════════════════════════════════════════════════════
    //  模型清单
    // ═══════════════════════════════════════════════════════════

    override suspend fun listModels(credential: ProviderCredential): List<ModelInfo>? =
        withContext(Dispatchers.IO) {
            val request = newRequest(credential, path = "/models", method = "GET", body = null)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw mapHttpError(response)
                val text = response.body?.string() ?: return@withContext null
                parseModelList(text)
            }
        }

    private fun parseModelList(text: String): List<ModelInfo> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val arr = root["data"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val modelId = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: return@mapNotNull null
            ModelInfo(
                id = modelId,
                displayName = obj["display_name"]?.jsonPrimitive?.contentOrNullSafe() ?: modelId,
                capabilities = setOf(
                    Capability.STREAM,
                    Capability.TOOL_CALL,
                    Capability.VISION,
                    Capability.LONG_CTX,
                ),
                // ⚠️ `/models` 不给上下文长度。200K 是 Claude 家族的保守值。
                contextWindow = 200_000,
                // ⚠️ 不填价格 = **未知**，不是免费（见 ModelConfigMapping 的纪律）。
                inputPricePerMillion = null,
                outputPricePerMillion = null,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  流式对话
    // ═══════════════════════════════════════════════════════════

    override fun chat(
        request: ChatRequest,
        credential: ProviderCredential,
    ): Flow<ChatChunk> = channelFlow {
        val payload = AnthropicPayload.buildBody(request, stream = true)
        val httpRequest = newRequest(credential, path = "/messages", method = "POST", body = payload)
        val call = client.newCall(httpRequest)

        // ★ 协程取消时中断 OkHttp 的阻塞读取。
        //   不做这一步，用户点"中止"之后读取会一直挂到超时。
        invokeOnClose { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw mapHttpError(response)
                val body = response.body
                    ?: throw ProviderException.ProtocolError("响应体为空")

                body.charStream().buffered().use { reader ->
                    // ★ 契约：**恰好发出一个** Done。
                    var done = false
                    var eventName: String? = null
                    val dataBuf = StringBuilder()

                    while (true) {
                        val line = reader.readLine() ?: break

                        when {
                            // 空行 = 一个事件结束
                            line.isBlank() -> {
                                if (dataBuf.isNotEmpty()) {
                                    val chunks = AnthropicPayload.parseStreamEvent(
                                        eventName,
                                        dataBuf.toString(),
                                    )
                                    for (c in chunks) {
                                        if (c is ChatChunk.Done) {
                                            if (!done) {
                                                done = true
                                                send(c)
                                            }
                                        } else {
                                            send(c)
                                        }
                                    }
                                }
                                eventName = null
                                dataBuf.setLength(0)
                            }
                            line.startsWith("event:") -> {
                                eventName = line.removePrefix("event:").trim()
                            }
                            line.startsWith("data:") -> {
                                // ⚠️ 多行 data 要**拼接**（SSE 规范），不是覆盖。
                                if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                                dataBuf.append(line.removePrefix("data:").trim())
                            }
                            // 注释行（`:` 开头）与其他字段忽略
                            else -> Unit
                        }
                    }

                    // ⚠️ 兜底：厂商既没发 message_delta 也没发 [DONE] 时，
                    //    调用方会永远等不到结束信号。必须补一个。
                    if (!done) {
                        send(
                            ChatChunk.Done(
                                usage = TokenUsage(inputTokens = 0, outputTokens = 0),
                                finishReason = null,
                            ),
                        )
                    }
                }
            }
        } catch (e: IOException) {
            // ★ 已产出的 chunk 不回滚（契约要求）。
            if (call.isCanceled()) return@channelFlow
            throw ProviderException.NetworkError(e)
        }
    }.flowOn(Dispatchers.IO)

    // ═══════════════════════════════════════════════════════════
    //  本地估算
    // ═══════════════════════════════════════════════════════════

    /**
     * token 估算 —— 启发式。
     *
     * Claude 的分词器不公开，无法精确。用"汉字 ≈ 1.5 字符 / 英文 4 字符 ≈ 1 token"。
     * ⚠️ 只用于本地预算熔断，**不用于计费**。
     */
    override fun countTokens(messages: List<ChatMessage>, model: String): Int {
        var chars = 0
        for (m in messages) {
            for (part in m.content) {
                chars += when (part) {
                    is com.pocketagent.provider.api.ContentPart.Text -> part.text.length
                    // 图片按 1600 token 粗估（高分辨率图的保守值）
                    is com.pocketagent.provider.api.ContentPart.Image -> 6400
                    is com.pocketagent.provider.api.ContentPart.ToolCall ->
                        part.name.length + part.argumentsJson.length
                    is com.pocketagent.provider.api.ContentPart.ToolResult -> part.content.length
                }
            }
        }
        return (chars / 3).coerceAtLeast(1)
    }

    /**
     * 费用估算。
     *
     * ⚠️ 价格表是**静态**的，会过期。所以：
     *  - 只用于本地预算熔断（宁可估高一点，也不要让用户超支）
     *  - 取不到价格时返回 **0 但标注 unknown**（见 [Cost] 的注释）
     */
    override fun estimateCost(usage: TokenUsage, model: String): Cost? {
        // ⚠️ 未知价格返回 **null**，不是 `Cost(0.0)` ——
        //    "算不出"与"免费"是两件事，混一起会让预算熔断失效。
        val (inputUsd, outputUsd) = priceOf(model) ?: return null
        val usd = usage.inputTokens / 1_000_000.0 * inputUsd +
            usage.outputTokens / 1_000_000.0 * outputUsd
        return Cost(usd = usd)
    }

    /** @return null = 未知价格（**不是免费**） */
    private fun priceOf(model: String): Pair<Double, Double>? {
        val m = model.lowercase()
        return when {
            // Opus 档最贵
            m.contains("opus") -> 15.0 to 75.0
            // Sonnet 档
            m.contains("sonnet") -> 3.0 to 15.0
            // Haiku 档最便宜
            m.contains("haiku") -> 0.8 to 4.0
            else -> null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  HTTP
    // ═══════════════════════════════════════════════════════════

    private fun newRequest(
        credential: ProviderCredential,
        path: String,
        method: String,
        body: String?,
    ): Request {
        val base = (credential.baseUrlOverride?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: defaultBaseUrl.trimEnd('/'))

        val builder = Request.Builder()
            .url(base + path)
            // ★ 鉴权头是 `x-api-key`，不是 Bearer
            .header("x-api-key", credential.apiKeyForRequest())
            // ★ 协议版本头 —— 不带会被 400。这是**协议**版本，不是模型版本。
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .header("User-Agent", USER_AGENT)

        // 会话标识（厂商要求；见 ProviderCredential.sessionId 的注释）
        credential.sessionId?.let { builder.header("x-opencode-session", it) }
        credential.extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        return when (method.uppercase()) {
            "GET" -> builder.get().build()
            else -> builder.post(
                (body ?: "{}").toRequestBody(JSON_MEDIA),
            ).build()
        }
    }

    /** 把 HTTP 状态翻成统一的异常 —— 上层据此决定文案与重试。 */
    private fun mapHttpError(response: Response): ProviderException {
        val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val message = runCatching {
            json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull()

        return when (response.code) {
            401, 403 -> ProviderException.AuthFailed(message ?: "Key 无效或无权限")
            402 -> ProviderException.NoBalance()
            429 -> ProviderException.RateLimited(
                response.header("retry-after")?.toLongOrNull(),
            )
            in 500..599 -> ProviderException.ProtocolError(
                "上游服务出错（${response.code}）${message?.let { "：$it" } ?: ""}",
            )
            else -> ProviderException.ProtocolError(
                "请求被拒绝（${response.code}）${message?.let { "：$it" } ?: ""}",
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.anthropic.com/v1"

        /**
         * 协议版本。
         *
         * ⚠️ **不是模型版本** —— 它是 Messages API 的接口版本，写死即可。
         *    不带这个头会被直接拒绝。
         */
        const val ANTHROPIC_VERSION: String = "2023-06-01"

        private const val USER_AGENT = "PocketAgent/1.0 (Android)"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // ★ 流式：读超时要长（模型思考可能很久）
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** `JsonPrimitive.contentOrNull` 的安全版（`isString=false` 时也取值）。 */
private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    runCatching { content }.getOrNull()?.takeIf { it.isNotEmpty() }
