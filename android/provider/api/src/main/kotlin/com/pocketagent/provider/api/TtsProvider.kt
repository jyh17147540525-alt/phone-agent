package com.pocketagent.provider.api

import kotlinx.coroutines.flow.Flow

/**
 * 统一的语音合成（TTS）Provider 抽象。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么和 [LlmProvider] 分开，而不是塞进去
 * ═══════════════════════════════════════════════════════════════
 *
 * 两者的差异不止"方法名不同"，而是**契约不同**：
 *
 * | | LlmProvider | TtsProvider |
 * |---|---|---|
 * | 输入 | 消息列表 | 一段文本 |
 * | 输出 | 文本增量（流） | **音频字节** |
 * | 计费 | 按 token | 按字符 / 按秒 |
 * | 失败模式 | 限流、余额、内容审核 | 音色不存在、文本超长、格式不支持 |
 *
 * 硬塞进一个接口会得到两个 `NotImplementedError` 分支，而调用方
 * 每次都要先判断"我手上这个到底能不能合成语音"。
 *
 * ⚠️ 但**共用的部分必须共用**：鉴权（[AuthScheme]）、凭据（[ProviderCredential]）、
 *    校验结论（[KeyValidationResult]）、异常（[ProviderException]）全部复用。
 *    复制一份出来会导致"同一个 Key 在模型页校验通过、在语音页却说无效" ——
 *    而用户完全不知道为什么。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 音频是高敏感数据
 * ═══════════════════════════════════════════════════════════════
 *
 * [synthesize] 产出的音频**回流到用户自己的扬声器**，不上传、不落盘。
 * 实现方与外层都必须遵守：
 *   · 不得写入任何日志（含长度之外的元数据）
 *   · 不得缓存到磁盘 —— 除非用户显式开启"语音缓存"（当前版本没有这个开关）
 *   · 调用方播完即丢，不留句柄
 *
 * 这条与截图（`ContentPart.Image`）同级：它们都是"经过设备麦克风/屏幕、
 * 带有环境信息的原始数据"，泄露的后果远超一段文本。
 */
interface TtsProvider {

    /** 唯一标识，如 "openai-tts" / "edge-tts" */
    val id: String

    /** 展示名，用于 UI */
    val displayName: String

    /** 鉴权方式。复用 [LlmProvider] 的那套 —— 多数厂商的语音接口与对话接口同一把 Key */
    val authScheme: AuthScheme

    /** 默认 BaseUrl；用户可覆盖 */
    val defaultBaseUrl: String

    /** 该 Provider 支持的静态能力 */
    fun capabilities(): Set<TtsCapability>

    /**
     * 校验 Key 是否可用。
     *
     * 实现要求与 [LlmProvider.validateKey] 完全一致：
     * - 必须是**最小代价**的请求（合成最短的一段文本，如单字 "a"）
     * - 必须区分「Key 无效」「余额不足」「限流」「网络不可达」四种结果
     * - 网络不可达时**不得**返回 [KeyValidationResult.Invalid]
     *
     * ⚠️ 与对话接口的区别：TTS 没有 `/models` 这类廉价的探测端点，
     *    所以多数实现只能**真的合成一小段**。这意味着校验是有成本的
     *    （按字符计费），实现方必须把文本压到最短。
     */
    suspend fun validateKey(credential: ProviderCredential): KeyValidationResult

    /** 该 Key 可用的音色；不支持枚举的 Provider 返回 null，由内置静态清单兜底 */
    suspend fun listVoices(credential: ProviderCredential): List<VoiceInfo>?

    /**
     * 合成语音。
     *
     * 返回 [Flow] 而不是 `ByteArray`，理由是**首字节延迟**：
     * 一句话可能有几百 KB，等整段合成完再播会让用户觉得"卡住了"；
     * 流式返回可以让播放器边收边播，开口延迟从"整句时长"降到"首包时长"。
     *
     * 实现要求：
     * - 网络中断时抛 [ProviderException]，**已产出的音频块不回滚**（用户已经听到了）
     * - 不得在异常信息中携带完整 Key（见 core-network/LogSanitizer）
     * - 必须在协程取消时中断底层读取，否则取消后读取会挂到超时
     */
    fun synthesize(request: SpeechRequest, credential: ProviderCredential): Flow<AudioChunk>

    /**
     * 估算这次合成要花多少钱。
     *
     * ⚠️ 与 `LlmProvider.estimateCost` 同样是**本地预算熔断**的输入，
     *    不要求精确 —— 但它必须能回答"这句要不要花钱"，
     *    否则语音会成为一个绕过预算的开销黑洞。
     */
    fun estimateCost(request: SpeechRequest): Cost
}

/**
 * 语音 Provider 的能力。
 *
 * 与 [Capability] 分开定义：那些是"模型能不能看图 / 调工具"，
 * 这些是"语音能不能调语速 / 换音色"，混在一起会让路由层
 * 拿到一堆与当前任务无关的位。
 */
enum class TtsCapability {
    /** 支持流式返回音频（首字节延迟低） */
    STREAM,

    /** 支持调节语速 */
    RATE,

    /** 支持调节音调 */
    PITCH,

    /** 支持调节音量 */
    VOLUME,

    /** 支持多音色枚举 */
    MULTI_VOICE,

    /** 支持声音克隆（⚠️ 高敏感，可能涉及他人声音，默认不启用） */
    VOICE_CLONE,

    /** 支持 SSML 标记（精细控制停顿、重音） */
    SSML,

    /** 端侧本地合成（数据不出设备，无网络延迟） */
    LOCAL,
}

/** 音色元信息 */
data class VoiceInfo(
    /** Provider 侧的音色标识，如 "alloy" / "zh-CN-XiaoxiaoNeural" */
    val id: String,
    val displayName: String,
    /** BCP-47 语言标签，如 "zh-CN"；未知为 null */
    val language: String? = null,
    /** 性别描述；未知为 null。刻意用 String 而非枚举 —— 各厂商的说法不一 */
    val gender: String? = null,
    /** 是否为该 Provider 的推荐默认音色 */
    val recommended: Boolean = false,
)

/**
 * 合成请求。
 *
 * ⚠️ [text] 的长度必须由实现方**二次校验**：不同厂商的上限差异极大
 *    （有的一次几千字符，有的一次只有一百多）。超长的处理方式由实现方决定
 *    （截断 / 分片 / 报错），但**不得静默截断而不告知调用方** ——
 *    用户会以为后半句是应用坏了。
 */
data class SpeechRequest(
    val text: String,
    /** 音色 id；null 表示用 Provider 的默认音色 */
    val voiceId: String? = null,
    /** 语速倍率，1.0 为常速。需 Provider 声明 [TtsCapability.RATE] */
    val rate: Double? = null,
    /** 音调倍率，1.0 为原调。需 Provider 声明 [TtsCapability.PITCH] */
    val pitch: Double? = null,
    /**
     * 输出音频格式。默认 mp3 —— 它是兼容性最好的选择，
     * Android 的 `MediaPlayer` 与 `AudioTrack` 都能直接吃。
     */
    val format: AudioFormat = AudioFormat.MP3,
    /** 请求超时（毫秒） */
    val timeoutMs: Long = 30_000,
)

/** 音频容器格式 */
enum class AudioFormat(val mimeType: String, val extension: String) {
    MP3("audio/mpeg", "mp3"),
    WAV("audio/wav", "wav"),
    OPUS("audio/ogg", "opus"),
    AAC("audio/aac", "aac"),
    PCM("audio/L16", "pcm"),
}

/**
 * 流式音频分片。
 *
 * ⚠️ 与 `ChatChunk` 一样，**恰好一个 [Done]** 的契约必须成立 ——
 *    否则调用方永远等不到结束信号（那个 bug 在对话侧已经踩过一次，
 *    见 `OpenAiCompatProvider.chat` 的长注释）。
 */
sealed interface AudioChunk {
    /** 音频数据增量。**原始字节，不是 base64** —— 解码是 Provider 实现的职责 */
    data class Audio(val bytes: ByteArray, val format: AudioFormat) : AudioChunk {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Audio && format == other.format && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + format.hashCode()
    }

    /** 结束，携带本次合成的计费依据 */
    data class Done(val usage: SpeechUsage) : AudioChunk
}

/**
 * 合成用量。
 *
 * ⚠️ [billableCharacters] 而不是 token —— TTS 按字符计费。
 *    把它命名成 token 会让人以为可以和 `TokenUsage` 相加，
 *    而那是两个量纲不同的东西。
 */
data class SpeechUsage(
    /** 实际送去合成的字符数（可能小于请求的，若实现做了分片截断） */
    val billableCharacters: Int,
    /** 音频总字节数 */
    val audioBytes: Int,
    /** 服务端返回的音频时长（毫秒）；未知为 null */
    val durationMillis: Long? = null,
)
