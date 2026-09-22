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
                    send(AudioChunk.Audio(buffer.copyOf(read), request.format))
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
        val url = "$baseUrl${profile.speechPath}"

        val payload = buildJsonObject {
            put("model", profile.defaultModel)
            put("input", request.text)
            // 音色：用户没选就用 profile 的默认值。
            // ⚠️ 不省略 voice 字段 —— 部分厂商缺这个字段会报 400，
            //    而报错信息通常只写"参数缺失"，用户看不出是哪一个。
            put("voice", request.voiceId ?: profile.defaultVoiceId)
            put("response_format", request.format.name.lowercase())

            request.rate?.let { put("speed", it) }
        }.toString()

        val builder = Request.Builder().url(url)

        // ── 鉴权 ──────────────────────────────────────────────
        // ⚠️ 明文只在这一行出现，不赋值给变量。与对话侧同一条硬规则。
        when (val scheme = profile.authScheme) {
            is AuthScheme.Bearer ->
                builder.header("Authorization", "Bearer ${credential.apiKeyForRequest()}")

            is AuthScheme.ApiKeyHeader ->
                builder.header(profile.apiKeyHeaderName, credential.apiKeyForRequest())

            is AuthScheme.QueryParam ->
                builder.url("$url?${scheme.paramName}=${credential.apiKeyForRequest()}")

            AuthScheme.None -> Unit
        }

        profile.extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        credential.extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        return builder
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

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
    internal fun renderRequestBody(request: SpeechRequest): String = buildJsonObject {
        put("model", profile.defaultModel)
        put("input", request.text)
        put("voice", request.voiceId ?: profile.defaultVoiceId)
        put("response_format", request.format.name.lowercase())
        request.rate?.let { put("speed", it) }
    }.toString()

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
    val extraHeaders: Map<String, String> = emptyMap(),
)

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
