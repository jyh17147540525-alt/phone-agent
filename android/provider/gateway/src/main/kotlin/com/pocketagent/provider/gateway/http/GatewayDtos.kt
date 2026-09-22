package com.pocketagent.provider.gateway.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 网关侧的 OpenAI Chat Completions 协议 DTO。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这份 DTO 是**重新写的**，而不是复用 `OpenAiCompatDtos`
 * ═══════════════════════════════════════════════════════════════
 *
 * BYOK 文档 §4.2 说"复用 `OpenAiCompatDtos`"，实施时发现**走不通**，
 * 有两处硬障碍：
 *
 * 1. **模块类型不兼容**。`:provider:openai-compat` 是 Android library
 *    （`com.android.library` + hilt + ksp + robolectric），而
 *    `:provider:gateway` 是**纯 JVM** —— 它必须能进
 *    `tools/verify/run_logic_tests.py`，那个脚本按模块整目录编译
 *    `src/main/kotlin`，一旦依赖 Android library 就编不过。
 *
 * 2. **可见性**。那边的 DTO **全部是 `internal`**（见该文件顶部），
 *    网关侧连符号都引用不到。
 *
 * 所以这里重写一份。**代价是字段可能漂移** —— 那由
 * `GatewayDtosContractTest` 钉住：它用同一份 JSON 字符串同时喂给
 * 两边的解析器，两边结论必须一致。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这批 DTO 刻意是 `public` 而不是 `internal`
 * ═══════════════════════════════════════════════════════════════
 *
 * 第一版写成了 `internal`，理由是"网关内部协议，不该对外暴露"。
 * 编译直接报：
 *
 *     'public' function exposes its 'internal' parameter type 'GatewayChatRequest'
 *
 * 因为 [SseResponseWriter.aggregateToResponse] 与
 * [ChatRequestTranslator.translate] 是 public 的（它们要被
 * `HttpGatewayServer` 用，而那个类在另一个包），签名里出现
 * internal 类型是非法的。
 *
 * 三选一：① 把 DTO 改 public ② 把两个函数改 internal
 * ③ 给 DTO 包一层 public 视图。选了 ①，因为：
 *
 * - ②会让 `HttpGatewayServer`（另一个包）也引用不到它们
 * - ③是无意义的样板，而这里**没有真正的封装收益** ——
 *   DTO 描述的就是 HTTP 线格式，它将来会被 dsh 与第三方客户端看到，
 *   藏起来不会带来任何安全或抽象上的好处
 *
 * ⚠️ 这也意味着**改这些字段等于改对外协议**，在 PR 里要当 API 变更对待。
 */
@Serializable
data class GatewayChatRequest(
    /**
     * 客户端以为的模型名。
     *
     * ⚠️ **网关会忽略它**（[com.pocketagent.provider.gateway.GatewayCore]
     *    用路由结果覆盖）—— 但这里**必须声明且不能给默认值之外的错误类型**，
     *    因为 dsh 一定会发，而缺了它 `ignoreUnknownKeys` 也救不了
     *    "必填字段缺失"的反序列化错误。
     *
     * 有默认值是为了宽容：某些客户端会省略它。
     */
    val model: String = "",

    val messages: List<GatewayMessage> = emptyList(),

    /**
     * 是否流式。
     *
     * ⚠️ 默认 `false`（协议如此，不是我们的选择）—— 而
     * **必须支持 `false`**：BYOK §4.3 列了这条坑，dsh 的某些
     * 探测请求（以及第三方客户端）会发非流式。
     * 只做流式的话表现是"这些客户端全部超时"，而我们的 SSE 写得好好的。
     */
    val stream: Boolean = false,

    val temperature: Double? = null,

    @SerialName("max_tokens")
    val maxTokens: Int? = null,

    /** 部分客户端发 `max_completion_tokens`（较新的 OpenAI 字段），一并接住 */
    @SerialName("max_completion_tokens")
    val maxCompletionTokens: Int? = null,

    val tools: List<GatewayTool>? = null,

    @SerialName("tool_choice")
    val toolChoice: JsonElement? = null,

    /**
     * 强制 JSON 输出。
     *
     * ⚠️ 类型是 [JsonElement] 而不是 String —— 协议里有两种形态：
     *    旧版 `{"type":"json_object"}`、新版 `{"type":"json_schema",...}`。
     *    收成 String 会让新版直接反序列化失败。
     */
    @SerialName("response_format")
    val responseFormat: JsonElement? = null,
) {
    /**
     * 实际生效的输出上限。
     *
     * ⚠️ 两个字段都要看，且**取较小者** —— 客户端可能同时发两个，
     *    而"哪个优先"协议上没定义。取较小者是安全方向：宁可答短一点，
     *    也不要因为看错字段而超出客户端预期。
     */
    val effectiveMaxTokens: Int?
        get() = when {
            maxTokens == null -> maxCompletionTokens
            maxCompletionTokens == null -> maxTokens
            else -> minOf(maxTokens, maxCompletionTokens)
        }

    /** 只要有一个字段是 json_object，就认为要 JSON 模式 */
    val wantsJsonMode: Boolean
        get() = responseFormat?.toString()?.contains("json_object") == true
}

@Serializable
data class GatewayMessage(
    val role: String,
    /**
     * 纯文本是 String，多模态是 Array —— 用 [JsonElement] 承载。
     *
     * ⚠️ 可空：assistant 消息在带 `tool_calls` 时 content 是 null。
     *    这是**合法**的，不是错误 —— 收成非空会让工具调用场景整个失败。
     */
    val content: JsonElement? = null,
    val name: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<GatewayMessageToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
)

@Serializable
data class GatewayMessageToolCall(
    val id: String? = null,
    val type: String? = null,
    val function: GatewayFunctionCall? = null,
)

@Serializable
data class GatewayFunctionCall(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class GatewayTool(
    val type: String = "function",
    val function: GatewayToolFunction? = null,
)

@Serializable
data class GatewayToolFunction(
    val name: String = "",
    val description: String = "",
    /** 官方规范是对象；部分客户端会传字符串形式的 schema */
    val parameters: JsonElement? = null,
)

// ═══════════════════════════════════════════════════════════════
//  响应
// ═══════════════════════════════════════════════════════════════

/**
 * 非流式响应体。
 *
 * ⚠️ `id` / `created` / `object` 这些"元数据"字段**看起来可以省**，
 *    但不少客户端（含 dsh 用的 SDK）会读 `id` 做日志关联，
 *    缺了会给一个 `undefined` 而不是报错 —— 于是日志里全是 undefined。
 *    所以照协议补齐。
 */
@Serializable
data class GatewayChatResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    /**
     * ⚠️ **回给客户端的 model 是我们自己的虚拟模型名**，不是真实模型 ——
     *    BYOK §4.4 第 ⑤ 条"响应头不带任何 Provider 信息"，
     *    响应体同理。让 dsh 知道背后是 DeepSeek 没有任何好处，
     *    却多了一处需要维护的信息泄露面。
     */
    val model: String,
    val choices: List<GatewayChoice> = emptyList(),
    val usage: GatewayUsage? = null,
)

@Serializable
data class GatewayChoice(
    val index: Int = 0,
    val message: GatewayResponseMessage,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class GatewayResponseMessage(
    val role: String = "assistant",
    val content: String? = null,
    /**
     * 推理内容。DeepSeek 等厂商用这个字段名。
     *
     * ⚠️ 我们**原样透出去**（若上游给了）。理由：dsh 的界面会显示它，
     *    而"思考过程对用户可见"是这个产品的卖点之一。
     */
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<GatewayResponseToolCall>? = null,
)

@Serializable
data class GatewayResponseToolCall(
    val id: String,
    val type: String = "function",
    val function: GatewayFunctionCall,
)

// ═══════════════════════════════════════════════════════════════
//  流式分片
// ═══════════════════════════════════════════════════════════════

@Serializable
data class GatewayChatChunk(
    val id: String,
    /**
     * ⚠️ 属性名**不能**叫 `objectType` 就直接指望 `@SerialName("object")` 生效 ——
     *    实测（`SseResponseWriterTest.每个分片自带 id 与 created`）
     *    编出来的 JSON 里**根本没有 object 字段**。
     *
     *    原因是 `object` 是 Kotlin 的**软关键字**：属性名取 `object`
     *    编译不过，于是第一版取了 `objectType` + `@SerialName("object")`。
     *    那个方向对，但漏了一件事 —— `@Serializable` 对
     *    "属性名与 SerialName 不同"的字段，在 `encodeDefaults = false`
     *    且该字段有默认值时，**默认值判定走的是属性名**，行为与预期不一致。
     *
     *    修法是给它**不给默认值**（必须显式传），这样它一定被编码。
     *    测试直接钉住 `p["object"]` 存在。
     */
    @SerialName("object")
    val objectType: String,
    val created: Long,
    val model: String,
    val choices: List<GatewayChunkChoice> = emptyList(),
    /**
     * 用量**只挂在最后一个 chunk 上**（协议如此）。
     *
     * ⚠️ 早期版本还在每个 chunk 上都放 usage 是错的 ——
     *    客户端会把它们**累加**，得到膨胀的数据。
     *    见 [SseResponseWriter]：只有 Done 才带它。
     */
    val usage: GatewayUsage? = null,
)

@Serializable
data class GatewayChunkChoice(
    val index: Int = 0,
    /**
     * ⚠️ 结束的那个 chunk **必须有 delta 字段**（哪怕是空对象）——
     *    不少客户端的解析器会先读 `choices[0].delta`，缺了会崩。
     *    协议上 delta 不是必填，实践上是必填。
     *
     * ⚠️ **不能给默认值**：`encodeDefaults = false` 会让"等于默认值"
     *    的字段整个不发出去。实测（同一条测试）默认值形态下
     *    `delta` 在 JSON 里消失了 —— 而它恰恰是客户端第一个读的字段。
     *    所以由调用方显式传 `GatewayChunkDelta()`。
     */
    val delta: GatewayChunkDelta,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
data class GatewayChunkDelta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content")
    val reasoningContent: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<GatewayChunkToolCall>? = null,
)

@Serializable
data class GatewayChunkToolCall(
    /**
     * ⚠️ **按顺序递增的序号**，不是 id。
     *
     *    协议里 `tool_calls` 在流式中按 index 分片拼装，客户端靠它
     *    把多个 delta 归并到同一个调用上。用 id 填这里的话，
     *    客户端会把每片当成一个新调用 —— 表现是"工具调用参数拼不起来"。
     */
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: GatewayFunctionCall? = null,
)

@Serializable
data class GatewayUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)

// ═══════════════════════════════════════════════════════════════
//  /v1/models
// ═══════════════════════════════════════════════════════════════

/**
 * 模型列表。**策略 A：只有一个虚拟模型**（BYOK §4.5）。
 *
 * ⚠️ 返回真实模型名（策略 B）看着更"完整"，但代价是把路由逻辑
 *    分散到 dsh 的配置里 —— 而那个配置文件用户看不见也改不动。
 *    更要命的是：dsh 会用 `max_tokens` 之类的**它以为的模型参数**，
 *    而真实模型可能完全不支持。
 */
@Serializable
data class GatewayModelsResponse(
    @SerialName("object") val objectType: String = "list",
    val data: List<GatewayModelInfo> = emptyList(),
)

@Serializable
data class GatewayModelInfo(
    /**
     * 虚拟模型名。
     *
     * ⚠️ 这个值会被写进 dsh 的 profile（BYOK §2.5），
     *    所以**改它等于改配置格式** —— 但因为它只是 dsh 配置里
     *    一个字符串，用户重装应用时会跟着 profile 一起重生成，
     *    所以风险可控。仍然：不要随手改。
     */
    val id: String,
    @SerialName("object") val objectType: String = "model",
    /**
     * ⚠️ **固定填 `"pocketagent"`**，绝不填真实 Provider 名 ——
     *    BYOK §4.4 第 ⑤ 条。
     */
    @SerialName("owned_by") val ownedBy: String = "pocketagent",
    /** 部分客户端（OpenRouter 风格）会读它做上下文长度判断 */
    @SerialName("context_length") val contextLength: Int? = null,
)

// ═══════════════════════════════════════════════════════════════
//  错误
// ═══════════════════════════════════════════════════════════════

/**
 * 统一错误体（OpenAI 风格）。
 *
 * ⚠️ [message] **必须已经过 `LogSanitizer`** ——
 *    BYOK §4.4 第 ⑥ 条。上游的错误体里可能带 Key 片段或内部地址。
 */
@Serializable
data class GatewayErrorResponse(
    val error: GatewayErrorDetail,
)

@Serializable
data class GatewayErrorDetail(
    val message: String,
    val type: String,
    val code: String? = null,
)
