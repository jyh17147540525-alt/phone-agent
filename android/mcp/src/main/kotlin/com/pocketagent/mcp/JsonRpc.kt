package com.pocketagent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON-RPC 2.0 的**最小核心** —— 只实现 MCP 用到的子集。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么手写而不是拿一个 JSON-RPC 库
 * ═══════════════════════════════════════════════════════════════
 *
 * 与 `HttpGatewayServer` 手写 HTTP 是同一条理由：**这一层的输入是不可信数据**
 * （哪怕客户端是 dsh）。依赖一个我们不控制的解析器，等于把它对"畸形输入"的
 * 判断当作我们的判断 —— 而畸形输入恰恰是本层唯一要做对的事。
 *
 * 而且规则很少：三条字段校验（jsonrpc / method / id）+ 两种消息（请求 / 通知）。
 * 少到可以全部背下来、全部离线钉死。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 版本协商：回显客户端要的版本（在其支持清单内）
 * ═══════════════════════════════════════════════════════════════
 *
 * 官方 SDK 的 `Client.connect()` 会校验**服务端**回包的 `protocolVersion`
 * 必须 ∈ 它自己的 `SUPPORTED_PROTOCOL_VERSIONS`，否则直接抛
 * "Server's protocol version is not supported"。而我们实现的子集
 * （tools 只有 list/call，无 SSE、无 session）在清单内各版本之间**没有行为差异**，
 * 所以最稳的策略是：客户端要哪个（只要在清单内）就给哪个；不在清单内才回落。
 */
object McpProtocol {

    /**
     * 客户端支持的协议版本清单。
     *
     * ⚠️ **逐字抄自**官方 SDK 1.30.0 的 `dist/esm/types.js`：
     * `SUPPORTED_PROTOCOL_VERSIONS = ['2025-11-25', '2025-06-18', '2025-03-26',
     * '2024-11-05', '2024-10-07']`。改这个清单前先核对新版本 SDK ——
     * 客户端发的是 `LATEST_PROTOCOL_VERSION`（1.30.0 = `2025-11-25`）。
     */
    val SUPPORTED_PROTOCOL_VERSIONS: List<String> = listOf(
        "2025-11-25",
        "2025-06-18",
        "2025-03-26",
        "2024-11-05",
        "2024-10-07",
    )

    /** 请求的版本不在清单内时的回落值。选一个所有在役客户端都认识的版本。 */
    const val FALLBACK_PROTOCOL_VERSION: String = "2025-06-18"

    /** `initialize` 结果里我们申报的服务器名（只作展示，不参与命名）。 */
    const val SERVER_NAME: String = "pocketagent-mcp"

    /** 服务器版本，随包发布更新。 */
    const val SERVER_VERSION: String = "0.1.0"

    // 方法名常量 —— 字符串在分派器与测试之间共用，避免"一处拼错、静默落到 404"。
    const val METHOD_INITIALIZE: String = "initialize"
    const val METHOD_PING: String = "ping"
    const val METHOD_TOOLS_LIST: String = "tools/list"
    const val METHOD_TOOLS_CALL: String = "tools/call"
    const val NOTIFICATION_INITIALIZED: String = "notifications/initialized"

    /**
     * 协商协议版本。
     *
     * @param requested 客户端在 `initialize.params.protocolVersion` 里要的版本；null = 没带
     * @return 要回给客户端的版本。**一定在 [SUPPORTED_PROTOCOL_VERSIONS] 内**。
     */
    fun negotiate(requested: String?): String =
        requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: FALLBACK_PROTOCOL_VERSION
}

/** JSON-RPC 2.0 标准错误码。数值是规范固定的，不要改。 */
object JsonRpcErrorCode {
    /** 连 JSON 都不是 */
    const val PARSE_ERROR: Int = -32700

    /** 是 JSON 但不符合请求结构（含我们不支持的批处理数组） */
    const val INVALID_REQUEST: Int = -32600

    /** 方法不存在 */
    const val METHOD_NOT_FOUND: Int = -32601

    /** 参数不合法（含未知工具名） */
    const val INVALID_PARAMS: Int = -32602

    /** 服务端内部错误 */
    const val INTERNAL_ERROR: Int = -32603
}

/**
 * 一条**合法的**入站消息。
 *
 * 请求（有 `id`，必须回包）与通知（无 `id`，回 202）分开成两个类型 ——
 * 合并成一个"可能没有 id"的类，迟早会有人忘了判空，而那条路径的表现是
 * "通知也被回了响应体"，客户端把它当成一条无主的消息丢掉，**静默**。
 */
sealed interface IncomingMessage {

    val method: String
    val params: JsonObject?

    /** 请求：必须回一个带同 `id` 的响应。 */
    data class Request(
        val id: JsonElement,
        override val method: String,
        override val params: JsonObject?,
    ) : IncomingMessage

    /** 通知：不得回响应体（HTTP 层回 202）。 */
    data class Notification(
        override val method: String,
        override val params: JsonObject?,
    ) : IncomingMessage
}

/** [JsonRpc.parse] 的三态结果。 */
sealed interface IncomingParse {

    data class Parsed(val message: IncomingMessage) : IncomingParse

    /** 连 JSON 都不是 → `-32700`。 */
    data object NotJson : IncomingParse

    /** 是 JSON 但不是合法请求 → `-32600`。[reason] 可回给客户端（不含内部细节）。 */
    data class Invalid(val reason: String) : IncomingParse
}

/**
 * JSON-RPC 的解析与构造。
 *
 * ⚠️ **本对象无状态、纯函数** —— 解析器有状态是"同一份字节在不同时刻解析出
 * 不同结果"的经典来源，而那种 bug 在测试里几乎不可复现。
 */
object JsonRpc {

    /** 解析用配置。宽松读（未知字段忽略），但结构校验全部显式做，不靠它兜底。 */
    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * 解析一条入站消息。
     *
     * 校验顺序刻意固定：JSON 语法 → 对象 → `jsonrpc` → `method` → `params` → `id`。
     * 顺序本身不重要，**固定**才重要 —— 否则同一批畸形输入会随机命中不同的
     * 错误分支，测试就成了掷骰子。
     */
    fun parse(text: String): IncomingParse {
        val element: JsonElement = try {
            lenient.parseToJsonElement(text)
        } catch (e: Exception) {
            // kotlinx.serialization 的解析异常继承自 IllegalArgumentException；
            // 这里刻意用宽 catch：**任何**解析失败都是 -32700，分类它没有意义。
            return IncomingParse.NotJson
        }

        val obj = when (element) {
            is JsonObject -> element
            is JsonArray -> return IncomingParse.Invalid("本服务器不支持批处理（batch）请求")
            else -> return IncomingParse.Invalid("请求必须是 JSON 对象")
        }

        // ⚠️ 只认 **JSON 字符串**：`"method": 123` 里的 123 也是 JsonPrimitive，
        //    用 contentOrNull 会把数字悄悄收成 "123"，而协议要求字符串。
        //    "宽一点"在这里不是宽容，是分派走向被非字符串控制。
        val version = obj["jsonrpc"].stringValueOrNull()
        if (version != "2.0") return IncomingParse.Invalid("\"jsonrpc\" 字段必须是 \"2.0\"")

        val method = obj["method"].stringValueOrNull()?.takeIf { it.isNotBlank() }
            ?: return IncomingParse.Invalid("\"method\" 必须是非空字符串")

        val paramsRaw = obj["params"]
        val params: JsonObject? = when {
            paramsRaw == null || paramsRaw is JsonNull -> null
            paramsRaw is JsonObject -> paramsRaw
            // 规范允许位置参数（数组），但 MCP 全用命名参数 —— 不支持就要说清楚，
            // 不能把数组悄悄当成"没有参数"（那会让一次带参调用静默变成无参调用）。
            else -> return IncomingParse.Invalid("\"params\" 必须是对象")
        }

        val idRaw = obj["id"]
        return when {
            idRaw == null -> IncomingParse.Parsed(IncomingMessage.Notification(method, params))

            // 规范只允许字符串 / 数字 / null 三种 id。
            idRaw !is JsonPrimitive -> IncomingParse.Invalid("\"id\" 必须是字符串或数字")

            // 布尔也是 JsonPrimitive 且 isString=false —— 必须显式排除，
            // 否则 `"id": true` 会被当成合法 id 一路带下去。
            !idRaw.isString && idRaw.booleanOrNull != null ->
                IncomingParse.Invalid("\"id\" 必须是字符串或数字")

            else -> IncomingParse.Parsed(IncomingMessage.Request(idRaw, method, params))
        }
    }

    /** 构造成功响应。`id` 原样回显（字符串/数字/null 都不做转换）。 */
    fun result(id: JsonElement, result: JsonElement = JsonObject(emptyMap())): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    /**
     * 构造错误响应。
     *
     * ⚠️ `id` 为 null（连 JSON 都不是、或 id 本身非法）时回 `"id": null` ——
     *    这是规范对 parse error 的规定形状，客户端靠它知道"这条错误对应不了任何请求"。
     *    [message] **不得**携带内部细节（异常 message、路径、堆栈）——
     *    它是给客户端的，而客户端会把有些内容展示给用户。
     */
    fun error(id: JsonElement?, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            putJsonObject("error") {
                put("code", code)
                put("message", message)
            }
        }
}

/**
 * 只有当元素是 **JSON 字符串** 时取值；数字 / 布尔 / null 一律返回 null。
 *
 * ⚠️ 存在的理由：`JsonPrimitive` 同时包括字符串、数字与布尔 ——
 *    用 `contentOrNull` 会把 `123` 悄悄收成 `"123"`，于是
 *    `"method": 123` 被当成合法方法名、`"jsonrpc": 2.0` 被当成 "2.0"。
 *    协议字段**没有**"宽容一点"的余地：它们决定分派走向。
 */
internal fun JsonElement?.stringValueOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
