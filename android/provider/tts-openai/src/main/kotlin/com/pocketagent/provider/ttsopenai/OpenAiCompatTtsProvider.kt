package com.pocketagent.provider.ttsopenai

import com.pocketagent.provider.api.AudioChunk
import com.pocketagent.provider.api.AudioFormat
import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Cost
import com.pocketagent.provider.api.KeyValidationResult
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.ProviderException
import com.pocketagent.provider.api.SpeechRequest
import com.pocketagent.provider.api.SpeechUsage
import com.pocketagent.provider.api.TtsCapability
import com.pocketagent.provider.api.TtsProvider
import com.pocketagent.provider.api.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * OpenAI `/audio/speech` 协议的通用语音合成实现。
 *
 * ═══════════════════════════════════════════════════════════════
 *  一份代码覆盖的厂商
 * ═══════════════════════════════════════════════════════════════
 *
 * OpenAI、硅基流动（SiliconFlow）、以及任何照抄这个协议的端点。
 * 差异集中在 [TtsProfile] 里，**不在代码里写 if-else 判断厂商**。
 *
 * ⚠️ 刻意**没有**把阿里 DashScope / 火山引擎放进来。它们的语音合成
 *    主推 WebSocket 实时协议（`wss://.../api-ws/v1/inference`），
 *    非实时 HTTP 端点的行为没有可靠文档可依。宁可先不支持，
 *    也不要写一个"看起来能配、配完必然失败"的服务商 ——
 *    那会让用户以为自己填错了 Key。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 响应体是**裸音频字节**，不是 JSON
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是与对话接口最大的实现差异。`/chat/completions` 返回 SSE 文本流，
 * 而 `/audio/speech` 返回 `audio/mpeg` 的**二进制流**。
 *
 * 由此带来三件必须在代码里做对的事：
 *
 * 1. **成功/失败靠 `response.isSuccessful`，不能靠解析 body** ——
 *    出错时厂商才返回 JSON，成功时返回的是音频。
 *    若先尝试 JSON 解析再看状态码，正常响应会被误判成解析失败。
 * 2. **错误信息必须读 body 的文本**，但成功路径**绝不能碰 body 的文本形式**
 *    （`body.string()` 会把二进制按 UTF-8 解码，几百 KB 音频直接变成
 *    一坨替换字符，还白占内存）。两条路径严格分开。
 * 3. **边收边发**，不要 `body.bytes()` 全读进内存再发。一句话的 mp3
 *    通常几十到几百 KB，峰值不高，但流式能让播放器提前开口 ——
 *    开口延迟从"整句时长"降到"首包时长"。
 */
class OpenAiCompatTtsProvider(
    private val profile: TtsProfile,
    private val client: OkHttpClient = defaultTtsClient(),
) : TtsProvider {

    override val id: String get() = profile.id
    override val displayName: String get() = profile.displayName
    override val authScheme: AuthScheme get() = profile.authScheme
    override val defaultBaseUrl: String get() = profile.defaultBaseUrl
    override val requiresCustomEndpoint: Boolean
        get() = profile.userDefined || profile.requiresBaseUrlOverride

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    override fun capabilities(): Set<TtsCapability> = profile.capabilities

    // ═══════════════════════════════════════════════════════════
    //  Key 校验
    // ═══════════════════════════════════════════════════════════

    /**
     * 校验 Key。
     *
     * ⚠️ 与对话接口不同，TTS **没有 `/models` 这类免费的探测端点**
     *    （语音接口的列表端点即使存在，也和校验 Key 权限无关）。
     *    所以这里只能**真的合成一小段** —— 而那是有成本的。
     *
     * 由此定下两条规则：
     *
     * 1. **文本压到最短**：用单个字符 `"a"`。它对应的计费单位是 1
     *    （字符或字节），在任何厂商的定价下都约等于免费。
     * 2. **这是"最小代价"的极限，不是零代价**。用户每点一次"校验"
     *    就真的花掉一次最小合成。这个取舍是有意的：比起"从来不校验、
     *    等到真要用时才发现 Key 是坏的"，花掉一次性的一分钱更划算。
     *
     * ⚠️ 结论映射沿用与对话侧相同的那套：
     *    网络不可达**绝不**返回 [KeyValidationResult.Invalid]——
     *    否则用户会去删一个完好的 Key，然后永远修不好。
     */
    override suspend fun validateKey(credential: ProviderCredential): KeyValidationResult =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val probe = SpeechRequest(
                text = PROBE_TEXT,
                voiceId = profile.defaultVoiceId,
                format = AudioFormat.MP3,
                timeoutMs = PROBE_TIMEOUT_MS,
            )

            val request = buildRequest(credential, probe)
            try {
                client.newCall(request).execute().use { response ->
                    val elapsed = System.currentTimeMillis() - started
                    when {
                        response.isSuccessful -> KeyValidationResult.Valid(
                            // ⚠️ 音频接口没有"可用模型数"这个概念。
                            //    这里填**音色数** —— 它承载的是同一件事：
                            //    "这个 Key 能拿到多少种选择"。填 0 会让界面
                            //    显示"可用（0 个模型）"，看起来像出错了。
                            modelCount = profile.defaultVoices.size,
                            latencyMs = elapsed,
                        )

                        response.code == 401 || response.code == 403 ->
                            KeyValidationResult.Invalid

                        response.code == 402 ->
                            KeyValidationResult.NoBalance(readErrorMessage(response))

                        response.code == 429 -> {
                            val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            KeyValidationResult.RateLimited(retryAfter)
                        }

                        // 400 单独处理：多数厂商在**音色名不认识**或**文本超长**时
                        // 返回 400，而这两种情况都证明"Key 本身是好的"。
                        // 归类成 Unreachable（中性色）而不是 Invalid（红色），
                        // 用户才不会去删一个完好的 Key。
                        response.code == 400 -> KeyValidationResult.Unreachable(
                            "服务商拒绝了这次最小合成（HTTP 400）：${readErrorMessage(response)}"
                        )

                        else -> KeyValidationResult.Unreachable(
                            "HTTP ${response.code}: ${readErrorMessage(response)}"
                        )
                    }
                }
            } catch (e: IOException) {
                // 网络问题 ≠ Key 无效。这一条与对话侧同等重要。
                KeyValidationResult.Unreachable(e.message ?: "网络不可达")
            }
        }

    // ═══════════════════════════════════════════════════════════
    //  音色列表
    // ═══════════════════════════════════════════════════════════

    /**
     * 枚举音色。
     *
     * ⚠️ 目前**恒返回 null**。理由：OpenAI 的 `/audio/speech` 协议
     *    **没有配套的音色列表端点**（`/audio/voices` 不存在）。
     *    硅基流动有 `/audio/voice/list`，但它列的是**用户自己克隆的音色**，
     *    不含系统预置的那 8 个 —— 拿它当完整清单会让用户以为
     *    "只有我上传过的那一个音色可用"。
     *
     *    返回 null 的语义是"我不知道，请用内置静态清单"，由
     *    [TtsProfiles] 的 `defaultVoices` 兜底。这是诚实的做法 ——
     *    比返回一个不完整的列表好。
     */
    override suspend fun listVoices(credential: ProviderCredential): List<VoiceInfo>? = null

    // ═══════════════════════════════════════════════════════════
    //  合成
    // ═══════════════════════════════════════════════════════════

    /**
     * 流式合成。
     *
     * 契约：**恰好发出一个 [AudioChunk.Done]**。
     * 这个契约在对话侧踩过一次坑（`OpenAiCompatProvider.chat` 的长注释），
     * 这里从一开始就写对：所有正常路径都落到同一个 `finally` 前的 emit，
     * 不在多个分支里各发一次。
     */
    override fun synthesize(
        request: SpeechRequest,
        credential: ProviderCredential,
    ): Flow<AudioChunk> = channelFlow {
        val tooLong = profile.maxCharacters?.let { limit ->
            request.text.length > limit
        } ?: false

        // ⚠️ 超长**不静默截断**。截断的结果是用户听到半句话，
        //    而他无从判断是文本被丢了还是播放器坏了。
        //    报错，并告诉他上限是多少、当前是多少 —— 两个数字都要给，
        //    否则他不知道该砍掉多少。
        if (tooLong) {
            throw ProviderException.ProtocolError(
                "这段文本有 ${request.text.length} 个字符，" +
                    "${profile.displayName} 单次上限是 ${profile.maxCharacters}。" +
                    "请拆成几段再合成。"
            )
        }

        val httpRequest = buildRequest(credential, request)

        // ★ 2026-11-20 晚：**请求的是 PCM，但这家给不出 PCM** 时立刻报错。
        //
        // ⚠️ 为什么不能不报、让它降级成 mp3：
        //    调用方（`TtsSpeaker`）拿到 `AudioChunk.Audio(bytes, format)`
        //    之后是把字节**按裸 PCM 直接写进 AudioTrack** 的。给它一段 mp3，
        //    用户听到的是刺耳的噪声 —— 比"没有声音"更难解释，也更容易
        //    被误判成"模型嗓子坏了"。
        //    与其安静地放出噪声，不如明说"这家不适合用来朗读"。
        val requestedFormat = request.format
        val actualFormat = effectiveFormat(requestedFormat)
        if (requestedFormat == AudioFormat.PCM && actualFormat != AudioFormat.PCM) {
            throw ProviderException.ProtocolError(
                "${profile.displayName} 不提供 PCM 音频，" +
                    "而当前播放通道只接受 PCM —— 换一个支持 PCM 的语音服务商，" +
                    "或者用「自定义」指向你自己的端点。"
            )
        }
        val call = client.newCall(httpRequest)

        // 协程取消时中断 OkHttp 的阻塞读取，否则取消后读取会挂到超时。
        invokeOnClose { call.cancel() }

        var audioBytes = 0

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw mapHttpError(response)

                val body = response.body
                    ?: throw ProviderException.ProtocolError("响应体为空")

                // ★ 边收边发。缓冲区 8KB 是权衡：太小会让下游收到大量碎片，
                //   太大则首字节延迟变高（违背流式的意义）。
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                val source: InputStream = body.byteStream()

                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read <= 0) break

                    audioBytes += read
                    // 必须 copyOf —— buffer 是复用的，直接把 buffer 发出去
                    // 会让下游拿到正在被覆盖的数组。这个错误的表现是
                    // **音频里夹杂周期性杂音**，极难定位。
                    //
                    // ⚠️ 带的是**实际请求的**格式（可能已降级），不是
                    //    调用方请求的那个 —— 下游据此决定怎么解释这段字节。
                    send(AudioChunk.Audio(buffer.copyOf(read), actualFormat))
                }
            }
        } catch (e: IOException) {
            throw ProviderException.NetworkError(e)
        }

        send(
            AudioChunk.Done(
                SpeechUsage(
                    // 计费依据是**送去的文本**，不是音频长度。
                    // 用文本长度而不是实际读到的字节数 —— 后者在
                    // "服务端返回了但网络断了"时会算少。
                    billableCharacters = request.text.length,
                    audioBytes = audioBytes,
                )
            )
        )
    }

    /**
     * 费用估算。
     *
     * ⚠️ 与对话侧同一个原则：**未知必须是 null，不能是 0.0**。
     *    把"不知道价格"当成"免费"会让预算熔断对语音完全失效 ——
     *    而语音恰恰是最容易被忽略的开销（"就说句话嘛"）。
     *
     * 计费口径按 [TtsProfile.pricing] 决定，见那个字段的注释。
     */
    override fun estimateCost(request: SpeechRequest): Cost {
        val pricing = profile.pricing ?: return Cost(0.0)

        val units = when (pricing.unit) {
            PricingUnit.PER_MILLION_CHARACTERS -> request.text.length / 1_000_000.0
            PricingUnit.PER_MILLION_BYTES -> utf8ByteCount(request.text) / 1_000_000.0
        }
        return Cost(units * pricing.usdPerMillionUnits)
    }

    // ═══════════════════════════════════════════════════════════
    //  请求构造
    // ═══════════════════════════════════════════════════════════

    private fun buildRequest(credential: ProviderCredential, request: SpeechRequest): Request {
        val baseUrl = (credential.baseUrlOverride ?: profile.defaultBaseUrl).trimEnd('/')
        val format = effectiveFormat(request.format)
        val model = resolveModel(credential)
        val voice = resolveVoice(request, credential)

        // ★ 2026-11-20 晚：协议分派。
        //   两种协议在**四个地方**不同（路径、鉴权头、请求体、格式位置），
        //   所以不能靠"多几个 if 塞字段"糊过去 —— 那样的代码会在
        //   "给 ElevenLabs 发了 response_format" 这类地方安静地失效。
        val url: String
        val payload: String
        when (profile.protocol) {
            TtsProtocol.OPENAI -> {
                url = "$baseUrl${profile.speechPath}"
                payload = openAiBody(request, format, model, voice)
            }

            TtsProtocol.ELEVENLABS -> {
                // 音色在**路径**里，格式在 **query** 里。
                url = "$baseUrl${profile.speechPath}/${encodePathSegment(voice)}" +
                    "?output_format=${elevenLabsFormat(format)}"
                payload = elevenLabsBody(request, model)
            }
        }

        val builder = Request.Builder().url(url)

        // ── 鉴权 ──────────────────────────────────────────────
        // ⚠️ 明文只在这一行出现，不赋值给变量。与对话侧同一条硬规则。
        when (val scheme = profile.authScheme) {
            is AuthScheme.Bearer ->
                builder.header("Authorization", "Bearer ${credential.apiKeyForRequest()}")

            is AuthScheme.ApiKeyHeader ->
                builder.header(profile.apiKeyHeaderName, credential.apiKeyForRequest())

            is AuthScheme.QueryParam ->
                // ⚠️ URL 里可能已经有 query（ElevenLabs 的 output_format）。
                //    无脑拼 `?` 会得到 `...?output_format=pcm_24000?key=xxx` ——
                //    服务端把它当成 output_format 的一部分，报"不支持的格式"，
                //    而真正的 Key 根本没被收到。
                builder.url(
                    url + (if ('?' in url) "&" else "?") +
                        "${scheme.paramName}=${credential.apiKeyForRequest()}",
                )

            AuthScheme.None -> Unit
        }

        profile.extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        credential.extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        return builder
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** OpenAI `/audio/speech` 的请求体。 */
    private fun openAiBody(
        request: SpeechRequest,
        format: AudioFormat,
        model: String,
        voice: String,
    ): String = buildJsonObject {
        put("model", model)
        put("input", request.text)
        // 音色：用户没选就用 profile 的默认值。
        // ⚠️ 不省略 voice 字段 —— 部分厂商缺这个字段会报 400，
        //    而报错信息通常只写"参数缺失"，用户看不出是哪一个。
        put("voice", voice)
        put("response_format", format.name.lowercase())

        request.rate?.let { put("speed", it) }
        // ★ 2026-11-20 晚：vLLM-Omni 系的 PCM 流必须显式声明。
        //   见 TtsProfile.pcmNeedsStreamFlags —— 对不支持它的厂商发这两个
        //   字段会 400，所以按 profile 开关，不按"格式是 PCM"就发。
        if (profile.pcmNeedsStreamFlags && format == AudioFormat.PCM) {
            put("stream", true)
            put("stream_format", "audio")
        }
        // ★ 2026-11-20：说话方式说明（情绪/语气/发声）。
        //
        // ⚠️ 只在 Provider 真的支持时才发。不支持的厂商（如 tts-1）
        //    收到未知字段有的直接 400，有的静默忽略 —— 前者让用户
        //    完全没声音，后者让用户以为标签生效了其实没有。
        //    两种都属于"安静地做错"，所以判断放在这一处，不靠上游自觉。
        if (profile.supportsInstructions) {
            request.instructions?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
        }
    }.toString()

    /** ElevenLabs 系的请求体：`{"text": ..., "model_id": ..., "voice_settings": {...}}`。 */
    private fun elevenLabsBody(request: SpeechRequest, model: String): String = buildJsonObject {
        put("text", request.text)
        put("model_id", model)
        // ★ 2026-11-20 晚：这一支原来只发 text + model_id，因为那是
        //   ElevenLabs 的最小可用体。BreezeBlue 云端用**同一套协议**
        //   （xi-api-key + /v1/text-to-speech/{voice_id}），但它把
        //   语气说明和语速放在 instructions / voice_settings 里 ——
        //   不发就等于"声音标签全线失效"（`VoiceTagRenderer` 判到
        //   支持 instructions 才会走第一档）。
        //
        // ⚠️ 仍然按 profile 开关，不无条件发：ElevenLabs 自己收到
        //    顶层 instructions 会 422，那会让用户"完全没声音"。
        if (profile.supportsInstructions) {
            request.instructions?.takeIf { it.isNotBlank() }?.let { put("instructions", it) }
        }
        request.rate?.let { rate ->
            putJsonObject("voice_settings") { put("speed", rate) }
        }
        // ⚠️ 不发 speed：ElevenLabs 的音色级参数在 voice_settings 里，
        //    传一个顶层 speed 会被拒。宁可不调速，也不要让它整条请求 400。
    }.toString()

    /**
     * 真正要请求的音频格式。
     *
     * ★ 2026-11-20 晚新增：智谱只吐 wav / pcm，我们缺省要 mp3，
     *   结果是**一句也念不出来**（400）。
     *
     * 降级顺序：请求的格式 → PCM → 清单第一项。
     * ⚠️ 优先降到 PCM 而不是"清单第一项"，因为播放端（`TtsSpeaker`）
     *    只吃裸 PCM —— 降到 mp3 的话它会把压缩字节当 PCM 写进
     *    AudioTrack，出来一片刺耳噪声（比没有声音更糟）。
     */
    private fun effectiveFormat(requested: AudioFormat): AudioFormat {
        if (requested in profile.supportedFormats) return requested
        if (AudioFormat.PCM in profile.supportedFormats) return AudioFormat.PCM
        return profile.supportedFormats.firstOrNull() ?: requested
    }

    /**
     * 模型名：**凭据覆盖 > profile 默认**。
     *
     * ★ 2026-11-20 晚新增。这是"自定义端点"能用起来的关键 ——
     *   自建网关 / 聚合转发往往要求一个特定的模型名，而它是
     *   用户私有的、我们不可能预置。
     */
    private fun resolveModel(credential: ProviderCredential?): String =
        credential?.modelOverride?.trim()?.takeIf { it.isNotEmpty() } ?: profile.defaultModel

    /**
     * 音色：**请求 > 凭据覆盖 > profile 默认**。
     *
     * ⚠️ 顺序不能反：`SpeechRequest.voiceId` 是这一次合成的明确选择
     *    （比如界面上的音色试听），凭据上的是"这个 Key 默认用哪个"。
     */
    private fun resolveVoice(request: SpeechRequest, credential: ProviderCredential?): String =
        request.voiceId?.trim()?.takeIf { it.isNotEmpty() }
            ?: credential?.voiceOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: profile.defaultVoiceId

    /** ElevenLabs 的格式码。只映射我们真正会请求的那两个。 */
    private fun elevenLabsFormat(format: AudioFormat): String = when (format) {
        // ⚠️ 必须显式要 24kHz：播放端按 24k/16bit/单声道打开 AudioTrack，
        //    采样率对不上会变成快放/慢放（听起来像"嗓子坏了"）。
        AudioFormat.PCM -> "pcm_24000"
        else -> "mp3_44100_128"
    }

    private fun encodePathSegment(raw: String): String =
        java.net.URLEncoder.encode(raw, "UTF-8")

    /**
     * 把请求体渲染成字符串。
     *
     * ⚠️ 存在的唯一理由是**可测性**：请求体是 `RequestBody` 流式的，
     *    而 `RequestBody.writeTo()` 需要一个 Buffer。测试要验证
     *    "字段名是不是 `input` 而不是 `text`" —— 而写错这个字段名
     *    会让服务端返回 400，报错只说"参数缺失"，不说缺哪个。
     *
     *    所以把一个只读的小工具暴露成 `internal`，而不是把
     *    `buildRequest` 整个开放出去。前者暴露的是一条**读取路径**，
     *    后者会让人能构造任意请求 —— 而那会绕过 [synthesize] 里的
     *    长度检查与格式校验。
     */
    internal fun renderRequestBody(
        request: SpeechRequest,
        credential: ProviderCredential? = null,
    ): String {
        // ★ 与 buildRequest 共用同一组解析函数 —— 两处各写一遍时，
        //   测试断言的是这一处、真机发出去的是那一处（这个坑
        //   在加模型/音色覆盖的第一版真实出现过）。
        val format = effectiveFormat(request.format)
        val model = resolveModel(credential)
        val voice = resolveVoice(request, credential)
        return when (profile.protocol) {
            TtsProtocol.OPENAI -> openAiBody(request, format, model, voice)
            TtsProtocol.ELEVENLABS -> elevenLabsBody(request, model)
        }
    }

    /**
     * 构造请求但不发送。
     *
     * `internal` 而非 `private` —— 让测试能断言 URL 与鉴权头，
     * 同时把"能构造任意请求"这件事限制在模块内。
     */
    internal fun requestFor(
        credential: ProviderCredential,
        request: SpeechRequest,
    ): Request = buildRequest(credential, request)

    // ═══════════════════════════════════════════════════════════
    //  错误映射
    // ═══════════════════════════════════════════════════════════

    /**
     * 把 HTTP 错误映射成统一的 [ProviderException]。
     *
     * ⚠️ 只在**失败**路径读 body 的文本形式。成功路径的 body 是音频，
     *    碰它就会得到一堆乱码。见类注释第 2 条。
     */
    private fun mapHttpError(response: okhttp3.Response): ProviderException = when (response.code) {
        401, 403 -> ProviderException.AuthFailed("Key 被拒绝（HTTP ${response.code}）")
        402 -> ProviderException.NoBalance()
        429 -> {
            val retryAfter = response.header("Retry-After")?.toLongOrNull()
            ProviderException.RateLimited(retryAfter)
        }
        else -> ProviderException.ProtocolError(
            "HTTP ${response.code}: ${readErrorMessage(response)}"
        )
    }

    /**
     * 读错误信息。
     *
     * ⚠️ 只取前 [MAX_ERROR_BODY_CHARS] 个字符。某些厂商在出错时会把
     *    整个请求体回显（包含待合成的文本）—— 那是用户的内容，
     *    不该整段进日志或界面。
     */
    private fun readErrorMessage(response: okhttp3.Response): String = runCatching {
        val raw = response.body?.string().orEmpty()
        val message = runCatching {
            json.parseToJsonElement(raw).jsonObject["error"]
                ?.let { element -> (element as? JsonObject)?.get("message") }
                ?.let { (it as? JsonPrimitive)?.content }
        }.getOrNull() ?: raw

        message.take(MAX_ERROR_BODY_CHARS)
    }.getOrDefault("")

    private fun utf8ByteCount(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    companion object {
        /** 最小代价探测用的文本。单字符，任何定价下都约等于免费 */
        private const val PROBE_TEXT = "a"

        /** 探测超时。语音合成的首包通常比对话慢，给足 15 秒 */
        private const val PROBE_TIMEOUT_MS = 15_000L

        private const val STREAM_BUFFER_BYTES = 8 * 1024

        private const val MAX_ERROR_BODY_CHARS = 300

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 默认 OkHttp 客户端。
         *
         * ⚠️ `readTimeout` 给到 60 秒：语音合成的首包延迟明显高于对话
         *    （服务端要先生成音频才吐第一个字节）。沿用对话侧 30 秒的话，
         *    长句会被误判成超时。
         */
        fun defaultTtsClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * TTS 厂商配置。
 *
 * 与对话侧的 `ProviderProfile` 是**两套独立的表**，刻意不合并 ——
 * 因为同一个厂商在两条线上的差异并不一一对应：
 *  · 硅基流动对话走 `/chat/completions`，语音走 `/audio/speech`
 *  · 厂商可能只提供其中一项（OpenAI 有语音，OpenRouter 没有）
 *  · 鉴权头偶尔也不同（Azure 用 `api-key`，而它的语音端点路径完全不一样）
 *
 * 合并成一张表会得到一堆"这一行对语音无效"的字段。
 */
data class TtsProfile(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val authScheme: AuthScheme,
    /** 合成端点路径 */
    val speechPath: String = "/audio/speech",
    val apiKeyHeaderName: String = "x-api-key",
    /**
     * 请求/响应所使用的**协议形态**。
     *
     * ★ 2026-11-20 晚新增。到这一天为止这张表只收"照抄 OpenAI
     *   `/audio/speech`"的厂商，所以"协议"这个维度根本不需要存在。
     *   收进 ElevenLabs 之后它成了必需项：那家把音色拼在**路径**里、
     *   鉴权头叫 `xi-api-key`、请求体是 `{"text","model_id"}` ——
     *   三处都不一样，靠默认可选字段拼不出来。
     *
     * ⚠️ 默认值是 [TtsProtocol.OPENAI] 而不是"未知"：绝大多数厂商
     *    就是 OpenAI 兼容的，让它们零成本跟上这张表，比逼每一行
     *    都显式声明更实际。真要不同，必须**显式**写出来。
     */
    val protocol: TtsProtocol = TtsProtocol.OPENAI,
    /**
     * 该端点**能吐出来的音频格式**。
     *
     * ★ 加它的原因很具体：智谱只吐 wav / pcm，我们缺省要 mp3
     *   就会被 400 掉 —— 用户看到的是"一句也念不出来"，而报错
     *   只说格式不支持。
     *
     * ⚠️ 默认给全集 = "不知道就都试试"，由厂商自己拒绝。
     *    已知收窄的必须显式列（见 [TtsProfiles.Zhipu]）。
     */
    val supportedFormats: Set<AudioFormat> = AudioFormat.entries.toSet(),
    val capabilities: Set<TtsCapability> = setOf(
        TtsCapability.STREAM, TtsCapability.RATE, TtsCapability.MULTI_VOICE,
    ),
    /** 默认模型 id。协议要求这个字段必填，没有"厂商默认"一说 */
    val defaultModel: String,
    /** 默认音色 id。用户没选时用它 */
    val defaultVoiceId: String,
    /** 内置音色清单 —— 厂商没有枚举端点，只能静态维护 */
    val defaultVoices: List<VoiceInfo> = emptyList(),
    /**
     * 单次合成的字符上限；null 表示未知。
     *
     * ⚠️ 未知与已知都要能表达：填一个大数字假装"没有限制"会让用户
     *    在超限时收到厂商的 400，而那个报错通常不说上限是多少。
     *    null 时 [OpenAiCompatTtsProvider.synthesize] 不做本地长度检查，
     *    由厂商负责拒绝 —— 这是"我不知道就别拦着"的正确用法。
     */
    val maxCharacters: Int? = null,
    /** 价格口径；null = 未知 */
    val pricing: TtsPricing? = null,
    /**
     * 端点是否接受 `instructions` 字段（这一句该怎么说的自然语言说明）。
     *
     * ★ 2026-11-20：声音标签的四档降级里，这是第一档
     *   （见 `:voicelogic` 的 `VoiceTagRenderer.chooseMode`）。
     *
     * ⚠️ 默认 **false**。默认值恰好是错的那一边时，要让它错在"不发"：
     *    发了不支持 = 有的厂商 400（用户完全没声音），
     *    不发 = 只是少一层语气（照样能听见人说话）。
     *    `tts-1` 不支持；支持它的是 `gpt-4o-mini-tts` 那一代。
     */
    val supportsInstructions: Boolean = false,
    /**
     * 正文里可以直接写、会被读成声音而不是字的内嵌标记。
     *
     * ★ CosyVoice 系（硅基流动默认模型）支持 `[uv_break]`、`[laughter]` 等，
     *   这是**不换模型也能听到停顿和笑**的唯一一条路 ——
     *   所以它比 instructions 更值得尽早填对。
     *
     * ⚠️ 空集 = 未知 = 上层降级到纯文本。别为了"看起来支持"随便塞几个进去：
     *    不支持的标记会被**逐字念出来**（"左方括号 u v 下划线 break 右方括号"）。
     */
    val inlineTokens: Set<String> = emptySet(),
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * 这一行是"用户自己填端点"的兜底项，不是某家厂商。
     *
     * ★ 2026-11-20 晚新增。它改变两件事：
     *  1. [defaultBaseUrl] 为空是合法的 —— 但**用户必须填**，
     *     见 `CredentialRepository.add` 的校验；
     *  2. 模型名与音色 id 由用户在 Key 编辑页填，profile 里的
     *     `tts-1` / `alloy` 只是占位提示，不代表"配完就能用"。
     *
     * ⚠️ 单靠一个布尔没法表达"哪些字段必填"，但它足够让界面
     *    与仓储层把"自定义"与其他厂商区分开 —— 这是当前唯一需要的区分。
     */
    val userDefined: Boolean = false,
    /**
     * 地址因机器而异（自建服务），用户**必须**填 —— 但我们不当它是"自定义"。
     *
     * ★ 2026-11-20 晚新增，为 BreezeBlue 这类"模型是确定的、端点在自己机器上"
     *   的服务准备。它与 [userDefined] 的区别很具体：
     *  · [userDefined]：连模型名、音色都得用户填，我们什么都不知道；
     *  · 本字段：模型名、音色、格式、是否支持 instructions 全都确定，
     *    只有 `host:port` 属于用户。
     *
     * ⚠️ 所以它只影响两件事：Key 页要求填地址（见 `CredentialRepository.add`
     *    的 `requiresCustomEndpoint` 判定），以及 [defaultBaseUrl] 可以是空串。
     *    别拿它去放宽"音色清单可以空"之类的规则 —— 那会把两件事搅在一起。
     */
    val requiresBaseUrlOverride: Boolean = false,
    /**
     * 要 PCM 时必须在请求体里显式写 `stream: true` + `stream_format: "audio"`。
     *
     * ★ vLLM-Omni / BreezeBlue 的独有约定：它的 `response_format: "pcm"` 只有在
     *   这两项都写对时才返回**边生成边吐**的裸 PCM；漏掉就退化成"整段算完再给"，
     *   表现是首包要等好几秒 —— 也就是流式白做。
     *
     * ⚠️ 默认 false：OpenAI 与硅基流动收到这两个未知字段有的直接 400，
     *    所以绝不能给所有厂商都加上。
     */
    val pcmNeedsStreamFlags: Boolean = false,
)

/**
 * 语音接口的协议形态。
 *
 * ⚠️ 只区分**真的不一样**的那几种，不追求覆盖所有厂商：
 *    每多一档就要多一条构造分支和一份测试，而这一层的价值
 *    全在"发出去的请求对不对"，不在分类学。
 */
enum class TtsProtocol {
    /** `POST {base}/audio/speech`，body `{model, input, voice, response_format}` */
    OPENAI,

    /**
     * ElevenLabs：`POST {base}/v1/text-to-speech/{voice_id}`，
     * 鉴权头 `xi-api-key`，body `{text, model_id}`，格式走 query `output_format`。
     */
    ELEVENLABS,
}

/**
 * 价格口径。
 *
 * ⚠️ 单位**必须显式区分字符与字节**。硅基流动按 **UTF-8 字节**计费，
 *    而 OpenAI 按 **字符**计费 —— 一个汉字在 UTF-8 里是 3 字节。
 *    把两者当同一回事会让中文场景下的成本低估到三分之一。
 */
data class TtsPricing(
    val unit: PricingUnit,
    val usdPerMillionUnits: Double,
)

enum class PricingUnit {
    /** 按字符数计费（OpenAI 口径） */
    PER_MILLION_CHARACTERS,

    /** 按 UTF-8 字节数计费（硅基流动口径） */
    PER_MILLION_BYTES,
}
