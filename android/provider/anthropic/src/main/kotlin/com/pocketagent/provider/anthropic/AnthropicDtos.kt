package com.pocketagent.provider.anthropic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * **Anthropic Messages 协议**的 DTO 与映射。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：为什么要有这个模块
 * ═══════════════════════════════════════════════════════════════
 *
 * `:provider:anthropic` 原本是个**空壳模块**：
 * `settings.gradle.kts` 里声明着、`:app` 里注释掉、`src/` 目录根本不存在。
 *
 * 而 OpenCode 的官方 Endpoint 表显示，下面这些模型**只走这个协议**：
 *   Claude / MiniMax M3 / Qwen3.8 Max / Qwen3.8 Flash / Qwen3.7 Plus
 * ⇒ 本项目的 OpenAI 兼容 provider 对它们**注定失败**。
 *   之前我在 `ProtocolGate` 里把它们拦住了，但那只是"拦住"，不是"支持"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 与 OpenAI 协议的六处**结构性**差异（不是改个字段名就行）
 * ═══════════════════════════════════════════════════════════════
 *
 * | | OpenAI | Anthropic |
 * |---|---|---|
 * | 系统提示 | `messages[0].role="system"` | **顶层 `system` 字段** |
 * | 角色 | system/user/assistant/tool | **只有 user / assistant** |
 * | 工具结果 | `role="tool"` 消息 | **`user` 消息里的 `tool_result` 块** |
 * | 工具定义 | `parameters` | **`input_schema`** |
 * | 输出上限 | `max_tokens` 可选 | **必填** |
 * | 连续同角色 | 允许 | **要求交替**（否则 400） |
 *
 * ⚠️ 最后两条是最容易踩的：漏了 `max_tokens` 直接 400；
 *    连续两条 user 消息在多数厂商能跑，在 Anthropic 会被拒。
 *
 * @see buildAnthropicRequest
 */

// ═══════════════════════════════════════════════════════════════
//  请求
// ═══════════════════════════════════════════════════════════════

@Serializable
internal data class AnthropicRequest(
    val model: String,
    /**
     * ⚠️ 顶层字段，不是 messages 里的一条。null 时不发。
     *
     * ★ 可以是**字符串**，也可以是**内容块数组**。
     *   要挂 `cache_control` 就必须用数组形式 —— 见 [AnthropicCacheControl]。
     */
    val system: JsonElement? = null,
    val messages: List<AnthropicMessage>,
    /** ⚠️ **必填**。Anthropic 没有默认值，漏了就 400。 */
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false,
    val tools: List<AnthropicTool>? = null,
)

@Serializable
internal data class AnthropicMessage(
    /** ⚠️ 只能是 "user" 或 "assistant"。 */
    val role: String,
    val content: List<AnthropicBlock>,
)

/**
 * 内容块。
 *
 * ⚠️ 用**单个**数据类 + 可空字段，而不是 sealed class：
 *   `type` 是厂商给的可变枚举，sealed 会在遇到新 type 时**反序列化失败** ——
 *   而"厂商加了个新块类型"不该让整个对话崩掉。
 */
@Serializable
internal data class AnthropicBlock(
    val type: String,
    val text: String? = null,
    /** type = "image" */
    val source: AnthropicImageSource? = null,
    /** type = "tool_use" */
    val id: String? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    /** type = "tool_result" */
    @SerialName("tool_use_id") val toolUseId: String? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    /** 缓存断点。见 [AnthropicCacheControl]。 */
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null,
    /** type = "tool_result" 的内容 */
    val content: JsonElement? = null,
)

@Serializable
internal data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

@Serializable
internal data class AnthropicTool(
    val name: String,
    val description: String,
    /**
     * ⚠️ 叫 `input_schema`，不是 OpenAI 的 `parameters`。
     *    发错字段名不会报错 —— 工具会被**静默忽略**，模型永远不调用它。
     *    这是最难查的一类 bug。
     */
    @SerialName("input_schema") val inputSchema: JsonElement,
    /**
     * 缓存断点。
     *
     * ★ 工具定义是**大块且几乎不变**的内容，是缓存的最佳目标之一。
     *   一次请求最多 4 个断点，工具用掉一个很划算。
     */
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null,
)

/**
 * **prompt 缓存断点**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：这是目标 (B)「提升响应速度」的核心手段
 * ═══════════════════════════════════════════════════════════════
 *
 * 厂商在断点处把**前缀**缓存起来。下一次请求只要前缀相同，
 * 就直接读缓存而不是重新计算 —— 于是：
 *  · **首字延迟下降**（不用重新 prefill 整段前缀）
 *  · **成本下降**（缓存读取按约 1/10 计费）
 *
 * ⚠️ 三个必须知道的约束（见官方 Messages API 规格）：
 *  ① **一次请求最多 4 个断点**
 *  ② 前缀必须**完全相同**才命中 —— 所以 persona / 工具定义要放在
 *     断点**之前**，且不要每轮变动
 *  ③ 前缀长度不足门槛时**不报错、也不生效**（静默无事发生）
 *     ⇒ 短对话开了缓存也没坏处，但别指望它有收益
 */
@Serializable
internal data class AnthropicCacheControl(
    /** 目前只有 "ephemeral" 一种。 */
    val type: String = "ephemeral",
    /** `"5m"`（默认）或 `"1h"`。null 时不发，用服务端默认。 */
    val ttl: String? = null,
)

/** 厂商返回的错误体。 */
@Serializable
internal data class AnthropicErrorBody(val error: AnthropicError?)

@Serializable
internal data class AnthropicError(val type: String? = null, val message: String? = null)

// ═══════════════════════════════════════════════════════════════
//  响应（非流式）
// ═══════════════════════════════════════════════════════════════

@Serializable
internal data class AnthropicResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<AnthropicBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage? = null,
)

@Serializable
internal data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    /**
     * 缓存读取的输入 token。
     *
     * ⚠️ 它**已经包含在 [inputTokens] 里**（Anthropic 的 `input_tokens`
     *    不含缓存部分，缓存部分单列）。取值时不要相加，见 [totalInput]。
     */
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
) {
    /** 实际计费的输入 token 总量（含缓存命中与缓存写入）。 */
    val totalInput: Int
        get() = inputTokens + cacheReadInputTokens + cacheCreationInputTokens
}

// ═══════════════════════════════════════════════════════════════
//  SSE 事件
// ═══════════════════════════════════════════════════════════════

/**
 * 流式事件的信封。
 *
 * ⚠️ **流式响应没有 `type` 字段以外的公共结构** ——
 *    Anthropic 的 SSE 是**具名事件**（`event: content_block_delta`），
 *    而 OpenAI 是匿名的 `data: {...}`。
 *    ⇒ 不能复用同一个解析器，见 [AnthropicSse]。
 */
@Serializable
internal data class AnthropicStreamEvent(
    val type: String,
    val index: Int? = null,
    val delta: AnthropicDelta? = null,
    val message: AnthropicStreamMessage? = null,
    val usage: AnthropicUsage? = null,
    // ⚠️ 字段名是 `content_block` —— 不写 @SerialName 会解析成 null，
    //    于是工具调用的 id/name 永远拿不到（测试抓到过）。
    @SerialName("content_block") val contentBlock: AnthropicBlock? = null,
    val error: AnthropicError? = null,
)

@Serializable
internal data class AnthropicDelta(
    /** "text_delta" / "input_json_delta" / "thinking_delta" / "signature_delta" */
    val type: String? = null,
    /** text_delta 用 */
    val text: String? = null,
    /**
     * thinking_delta 用。
     *
     * ⚠️ 推理增量放在 `thinking` 字段里，**不是** `text`。
     *    用 `text` 去读会永远是 null —— 推理内容直接丢失（测试抓到过）。
     */
    val thinking: String? = null,
    /** input_json_delta 用 */
    @SerialName("partial_json") val partialJson: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
internal data class AnthropicStreamMessage(
    val id: String? = null,
    val role: String? = null,
    val usage: AnthropicUsage? = null,
)
