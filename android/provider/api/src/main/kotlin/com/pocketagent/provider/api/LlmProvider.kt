package com.pocketagent.provider.api

import kotlinx.coroutines.flow.Flow

/**
 * 统一的模型 Provider 抽象。
 *
 * 设计原则：
 * 1. **上层业务不感知厂商差异**。agent / keymgmt 只依赖本接口，不依赖任何实现模块。
 * 2. **能力用 Capability 声明，而非类型判断**。路由层根据能力筛选，而非 `if (provider is GeminiProvider)`。
 * 3. **鉴权方式显式建模**。不同厂商的鉴权差异（Bearer / x-api-key / QueryParam）在实现内部消化。
 * 4. **流式优先**。所有对话能力以 Flow 暴露，非流式是流式的特例。
 *
 * ⚠️ 本接口所在的模块 `:provider:api` 必须保持零第三方依赖（除 coroutines 与 serialization），
 *    以便任何 Provider 实现都能独立编译与贡献。
 */
interface LlmProvider {

    /** 唯一标识，如 "openai" / "deepseek" / "anthropic" / "local-ollama" */
    val id: String

    /** 展示名，用于 UI */
    val displayName: String

    /** 鉴权方式 */
    val authScheme: AuthScheme

    /** 默认 BaseUrl；用户可在配置中覆盖（自定义 Provider 场景） */
    val defaultBaseUrl: String

    /** 该 Provider 支持的静态能力（与具体模型无关的部分） */
    fun capabilities(): Set<Capability>

    /**
     * 校验 Key 是否可用。
     *
     * 实现要求：
     * - 必须是**最小代价**的请求（如 GET /models，或 max_tokens=1 的最小 chat）
     * - 必须区分「Key 无效」「余额不足」「限流」「网络不可达」四种结果
     * - 网络不可达时**不得**返回 INVALID（否则用户会误删好 Key）
     */
    suspend fun validateKey(credential: ProviderCredential): KeyValidationResult

    /** 拉取该 Key 可用的模型列表；不支持的 Provider 返回 null，由内置静态清单兜底 */
    suspend fun listModels(credential: ProviderCredential): List<ModelInfo>?

    /**
     * 流式对话。
     *
     * 实现要求：
     * - 必须正确解析 SSE，处理 `[DONE]`、多行 data、注释行
     * - 网络中断时抛出 [ProviderException]，已产出的 chunk 不回滚
     * - 不得在异常信息中携带完整 Key（见 core-network/LogSanitizer）
     */
    fun chat(request: ChatRequest, credential: ProviderCredential): Flow<ChatChunk>

    /** token 计数；不支持的 Provider 用启发式估算（1 token ≈ 1.5 汉字 / 4 英文字符） */
    fun countTokens(messages: List<ChatMessage>, model: String): Int

    /** 费用估算；用于本地预算熔断，不要求精确 */
    fun estimateCost(usage: TokenUsage, model: String): Cost
}

/** 鉴权方式 */
sealed interface AuthScheme {
    /** `Authorization: Bearer <key>` */
    data object Bearer : AuthScheme
    /** `x-api-key: <key>`（Anthropic 风格） */
    data object ApiKeyHeader : AuthScheme
    /** `?key=<key>`（Gemini 风格） */
    data class QueryParam(val paramName: String) : AuthScheme
    /** 无需鉴权（本地模型） */
    data object None : AuthScheme
}

/** 能力声明 —— 路由层据此筛选 Provider */
enum class Capability {
    /** 支持图片输入（截图理解必需） */
    VISION,
    /** 支持 function calling / tool use */
    TOOL_CALL,
    /** 支持流式输出 */
    STREAM,
    /** 支持强制 JSON 输出 */
    JSON_MODE,
    /** 上下文窗口 >= 100K */
    LONG_CTX,
    /** 支持音频输入 */
    AUDIO_IN,
    /** 端侧本地模型（数据不出设备） */
    LOCAL,
    /** 支持 prompt caching（可显著降本） */
    CACHE,
}

/**
 * 用户提供的凭据。
 *
 * ⚠️ 安全红线：`apiKey` 以 ByteArray 承载而非 String。
 *    原因：String 进入 JVM 字符串池后无法擦除，内存 dump 可见。
 *    调用方必须在使用后立即 `credential.clear()`。
 */
class ProviderCredential(
    val apiKey: ByteArray,
    /** 用户覆盖的 BaseUrl，null 表示用 Provider 默认值 */
    val baseUrlOverride: String? = null,
    /** 额外请求头（自定义 Provider 场景） */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    /** 使用后清零。任何持有本对象的代码都有义务在 finally 中调用。 */
    fun clear() {
        apiKey.fill(0)
    }

    /**
     * 取出明文用于构造请求头。
     *
     * ⚠️ **只在构造鉴权头的那一行调用，不要赋值给变量、不要缓存、不要传参。**
     *    返回的 String 会进入 JVM 字符串池，无法擦除 —— 这是本设计唯一
     *    无法避免的明文暴露点，因此必须把暴露窗口压到最小。
     *
     * 正确：
     * ```kotlin
     * .header("Authorization", "Bearer ${credential.apiKeyForRequest()}")
     * ```
     * 错误：
     * ```kotlin
     * val key = credential.apiKeyForRequest()   // ❌ 明文驻留内存
     * ```
     */
    fun apiKeyForRequest(): String = apiKey.decodeToString()

    /** 明文长度，用于校验与脱敏显示，不暴露内容 */
    val keyLength: Int get() = apiKey.size

    override fun toString(): String = "ProviderCredential(***, baseUrl=$baseUrlOverride)"
}

/** Key 校验结果 */
sealed interface KeyValidationResult {
    /** 有效，附带可用模型数 */
    data class Valid(val modelCount: Int, val latencyMs: Long) : KeyValidationResult
    /** 鉴权失败：Key 错误或已吊销 */
    data object Invalid : KeyValidationResult
    /** Key 有效但余额不足 */
    data class NoBalance(val message: String?) : KeyValidationResult
    /** Key 有效但被限流 */
    data class RateLimited(val retryAfterSeconds: Long?) : KeyValidationResult
    /** 网络不可达 —— 不得据此判定 Key 无效 */
    data class Unreachable(val reason: String) : KeyValidationResult
}

/** 模型元信息 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val capabilities: Set<Capability>,
    val contextWindow: Int,
    /** 每 100 万输入 token 的价格（美元）；未知为 null */
    val inputPricePerMillion: Double? = null,
    val outputPricePerMillion: Double? = null,
    /** 是否为该 Provider 的推荐默认模型 */
    val recommended: Boolean = false,
)

/** 对话请求 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** 强制 JSON 输出；需 Provider 声明 JSON_MODE 能力 */
    val jsonMode: Boolean = false,
    /** 请求超时（毫秒） */
    val timeoutMs: Long = 60_000,
)

/** 对话消息 */
data class ChatMessage(
    val role: Role,
    val content: List<ContentPart>,
    val name: String? = null,
    val toolCallId: String? = null,
) {
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    companion object {
        fun system(text: String) = ChatMessage(Role.SYSTEM, listOf(ContentPart.Text(text)))
        fun user(text: String) = ChatMessage(Role.USER, listOf(ContentPart.Text(text)))
        fun assistant(text: String) = ChatMessage(Role.ASSISTANT, listOf(ContentPart.Text(text)))
    }
}

/** 消息内容片段 —— 多模态的统一表示 */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    /**
     * 图片。以原始字节承载，由各 Provider 实现负责编码（base64 / multipart / inline_data）。
     * ⚠️ 截图属于高敏感数据，实现方不得将其写入任何日志或磁盘。
     */
    data class Image(val bytes: ByteArray, val mimeType: String = "image/webp") : ContentPart {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Image && mimeType == other.mimeType && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    data class ToolCall(val id: String, val name: String, val argumentsJson: String) : ContentPart
    data class ToolResult(val toolCallId: String, val content: String) : ContentPart
}

/** 流式响应分片 */
sealed interface ChatChunk {
    /** 文本增量 */
    data class Delta(val text: String) : ChatChunk
    /** 工具调用增量（可能分多次到达，由调用方拼接） */
    data class ToolCallDelta(val id: String?, val name: String?, val argumentsFragment: String?) : ChatChunk
    /** 推理过程（部分模型支持，如 reasoning_content） */
    data class Reasoning(val text: String) : ChatChunk
    /** 结束，携带用量统计 */
    data class Done(val usage: TokenUsage, val finishReason: String?) : ChatChunk
}

/** token 用量 */
data class TokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedInputTokens: Int = 0,
) {
    val total: Int get() = inputTokens + outputTokens
}

/** 费用 */
data class Cost(val usd: Double) {
    /**
     * ⚠️ 必须显式指定 [java.util.Locale.US]。
     *    默认 locale 下 `%.6f` 在德语/土耳其语等环境会输出 `0,001234`（逗号作小数点），
     *    一旦这个字符串被写进日志或界面，用户会看成"两个数字"，也无法再被解析回来。
     */
    override fun toString(): String =
        "$" + String.format(java.util.Locale.US, "%.6f", usd)
}

/** 工具定义（供 function calling 使用） */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 字符串 */
    val parametersJsonSchema: String,
)

/** Provider 层统一异常 */
sealed class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthFailed(message: String) : ProviderException(message)
    class RateLimited(val retryAfterSeconds: Long?) : ProviderException("rate limited")
    class NoBalance : ProviderException("insufficient balance")
    class NetworkError(cause: Throwable) : ProviderException("network unreachable", cause)
    class Timeout : ProviderException("request timeout")
    /** 响应格式与预期不符（多为厂商改了协议） */
    class ProtocolError(message: String) : ProviderException(message)
    /** 内容被安全策略拦截 */
    class ContentFiltered(message: String) : ProviderException(message)
}
