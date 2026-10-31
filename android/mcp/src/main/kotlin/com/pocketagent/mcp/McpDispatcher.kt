package com.pocketagent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 把一条**已解析**的入站消息分派成响应（或 `null` = 通知，HTTP 层回 202）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它为什么与 HTTP 服务器分开
 * ═══════════════════════════════════════════════════════════════
 *
 * 与网关"协议层 / IO 层"的切法相同：真正的语义（握手、工具调用、错误码）
 * 全部在这里 —— **离线可测**；`McpHttpServer` 只剩 socket 读写与加固，
 * 出错时的排查面小得多。
 *
 * ⚠️ 本类是**无状态**的（除了日志出口）。并发连接各走各的 `dispatch`，
 *    不需要锁。有状态化（比如记住每个客户端的 initialize）前先想清楚：
 *    状态丢失时的降级行为是什么 —— 想不清就别加。
 */
class McpDispatcher(
    private val registry: ToolRegistry,

    /**
     * 单次工具执行的超时。
     *
     * ⚠️ 必须存在：读屏端口可能因为无障碍服务被系统挂起而**永不返回**，
     *    而那条协程会一直占着连接。超时后返回 [ToolOutcome.Failed] 的形态
     *    （isError 结果），让模型看到"这次失败了"，而不是无限沉默。
     *    dsh 侧也有自己的 `toolCallTimeoutMs`（配置里 30s），两层同时存在是
     *    纵深防御：它防"整个调用卡死"，我们防"单个工具泄漏协程"。
     */
    private val toolTimeoutMs: Long = DEFAULT_TOOL_TIMEOUT_MS,

    /** 日志出口。**实现方绝不能把 token / 屏幕内容传进来。**（同网关纪律） */
    private val log: (String) -> Unit = {},
) {

    /**
     * 分派。
     *
     * @return 响应对象；`null` = 这是通知，不需要响应体。
     */
    suspend fun dispatch(message: IncomingMessage): JsonObject? = when (message) {
        // 所有通知一律"已知悉"。包括 notifications/initialized 与
        // notifications/cancelled —— 我们不需要它们的语义（无 session、无长任务），
        // 但**必须**用 202 正确应答，否则客户端会把"未处理"当成"出错"。
        is IncomingMessage.Notification -> null

        is IncomingMessage.Request -> when (message.method) {
            McpProtocol.METHOD_INITIALIZE -> handleInitialize(message)
            McpProtocol.METHOD_PING -> JsonRpc.result(message.id)
            McpProtocol.METHOD_TOOLS_LIST -> JsonRpc.result(message.id, registry.listResult())
            McpProtocol.METHOD_TOOLS_CALL -> handleToolCall(message)
            else -> JsonRpc.error(
                message.id,
                JsonRpcErrorCode.METHOD_NOT_FOUND,
                "本服务器未实现该方法",
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  initialize
    // ─────────────────────────────────────────────────────────────

    private fun handleInitialize(message: IncomingMessage.Request): JsonObject {
        val requested = message.params?.get("protocolVersion").stringValueOrNull()
        val version = McpProtocol.negotiate(requested)

        // ⚠️ 只记版本号，不记 clientInfo 全文 —— 日志会被应用内日志页收集，
        //    而"谁连了上来"这类信息越少越好。
        log("MCP 握手：客户端请求 $requested → 采用 $version")

        return JsonRpc.result(
            message.id,
            buildJsonObject {
                put("protocolVersion", version)
                putJsonObject("capabilities") {
                    // listChanged=false 是**事实声明**：我们不实现服务端主动推送
                    // （那需要 SSE / GET 流），客户端不要等它。
                    putJsonObject("tools") { put("listChanged", false) }
                }
                putJsonObject("serverInfo") {
                    put("name", McpProtocol.SERVER_NAME)
                    put("version", McpProtocol.SERVER_VERSION)
                }
            },
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  tools/call
    // ─────────────────────────────────────────────────────────────

    private suspend fun handleToolCall(message: IncomingMessage.Request): JsonObject {
        val params = message.params
            ?: return JsonRpc.error(message.id, JsonRpcErrorCode.INVALID_PARAMS, "tools/call 缺少参数")

        val rawName = params["name"].stringValueOrNull()
            ?: return JsonRpc.error(message.id, JsonRpcErrorCode.INVALID_PARAMS, "缺少工具名")

        val tool = registry.find(rawName)
            ?: return JsonRpc.error(
                message.id,
                JsonRpcErrorCode.INVALID_PARAMS,
                // 回显名字是有意的：模型拼错工具名时，这条错误是它自我修正的唯一线索。
                // 只回显名字本身（客户端自己发的字符串），不拼接任何内部信息。
                "未知工具：${rawName.take(64)}",
            )

        // arguments 缺席按"空参数"处理（模型经常对无参工具发 {} 或干脆不发）。
        val arguments = (params["arguments"] as? JsonObject) ?: JsonObject(emptyMap())

        val outcome: ToolOutcome = try {
            withTimeoutOrNull(toolTimeoutMs) { tool.call(arguments) }
                ?: return toolErrorResult(
                    message.id,
                    "工具执行超时（${toolTimeoutMs} 毫秒）。请稍后重试；若反复超时，请把这一情况告诉用户。",
                )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ⚠️ 异常 message 不放出去：它可能包含路径、内部状态甚至屏幕片段。
            //    只记类型名，回一条不给排查者添乱、也不泄漏的通用错误。
            log("工具执行异常：${e::class.simpleName}")
            return JsonRpc.error(
                message.id,
                JsonRpcErrorCode.INTERNAL_ERROR,
                "工具执行失败（内部错误）",
            )
        }

        return when (outcome) {
            is ToolOutcome.Success -> JsonRpc.result(
                message.id,
                buildJsonObject {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", outcome.text)
                        }
                        // ══════════════════════════════════════════════
                        //  ★★ 2026-10-31：把截图作为 **image block** 发出去
                        // ══════════════════════════════════════════════
                        //
                        // MCP 的内容项是有类型的：`text` / `image` / `resource`。
                        // 在此之前这里只发 `text` ⇒ 工具**没法返回像素**，
                        // 于是 `screen_read` 只能给 UI 树的文字描述。
                        //
                        // ⚠️ 不能把图片塞进 `text` 做 base64：
                        //    那会让模型收到一坨无法解析的字符，而且白白
                        //    浪费上下文（一张 1MB 的图 base64 是 130 万字符，
                        //    远超多数模型的窗口）。
                        //
                        // ★ 用 `image` 类型后，支持的客户端会把它**当作图像**
                        //   交给多模态模型，而不是当文字。
                        outcome.imagePng?.let { png ->
                            addJsonObject {
                                put("type", "image")
                                // ⚠️ 不能用 `android.util.Base64` ——
                                //    `:mcp` 是**纯 Kotlin 模块**，没有 Android SDK。
                                //    手写一个 10 行的编码器（见文件末），
                                //    而不是引入 java.util.Base64（API 26+，
                                //    而本项目 minSdk 更低）。
                                put("data", Base64NoWrap.encode(png))
                                put("mimeType", "image/png")
                            }
                        }
                    }
                    outcome.structured?.let { put("structuredContent", it) }
                    // isError 刻意**不写**（缺省 false）—— 写了 false 与不写的区别
                    // 只影响字节数，不影响语义（客户端 `result.isError === true` 判断）。
                },
            )

            is ToolOutcome.Refused -> toolErrorResult(message.id, outcome.reason)
            is ToolOutcome.Failed -> toolErrorResult(message.id, outcome.reason)
        }
    }

    /**
     * 工具级错误的结果形态。
     *
     * ⚠️ 它**不是** JSON-RPC 错误：`isError: true` 是 MCP 规范为"工具执行失败"
     *    设计的形状，客户端（dsh）会把它**抛成失败**让模型看到（源码：
     *    `if (result.isError === true) throw new Error(text)`），而 JSON-RPC
     *    错误走的是另一条路（request 直接 reject）。两者对模型的可见性相同，
     *    但对"这是工具说了什么"与"这是协议层坏了"的区分不同 —— 拒绝必须走前者。
     */
    private fun toolErrorResult(id: kotlinx.serialization.json.JsonElement, text: String): JsonObject =
        JsonRpc.result(
            id,
            buildJsonObject {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                }
                put("isError", true)
            },
        )

    companion object {
        /**
         * 默认单工具超时。
         *
         * 取值依据：读屏采集本身有 500ms 的既有上限（`CaptureOptions.timeoutMs`），
         * 而端口的实现可能还要做主线程排队 —— 25 秒是"正常永远够用、
         * 出事（服务挂起）时快速失败"的量级。它**必须小于** dsh 配置里的
         * `toolCallTimeoutMs`（我们写的是 30000），这样"超时"由我们说了算 ——
         * 我们能给出可读的原因，而 dsh 的超时只会是一句协议层的报错。
         */
        const val DEFAULT_TOOL_TIMEOUT_MS: Long = 25_000
    }
}

/**
 * **Base64 编码（无换行）** —— 给 MCP 的 image block 用。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31：为什么手写
 * ═══════════════════════════════════════════════════════════════
 *
 * `:mcp` 是**纯 Kotlin 模块** —— 它的纪律是"零 android.* / androidx.* 依赖"
 * （见模块清单里的 PURE_KOTLIN 声明），否则就进不了离线验证器。
 *
 * ⇒ 可选项只剩两个，都不行：
 *   · `android.util.Base64` —— 依赖 Android SDK
 *   · `java.util.Base64`    —— API 26+，而 minSdk 低于它
 *
 * ⚠️ 手写编码器的风险是"跑得快但算错"，而它错了的表现是
 *    **图片在客户端打不开** —— 不报错，只是看不见。
 *    ⇒ 它必须被测试覆盖（见 `Base64NoWrapTest`）。
 *
 * ★ 不追求性能：截图是偶发操作（一次调用一张），
 *   而正确性比"省几微秒"重要得多。
 */
object Base64NoWrap {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 编码。输出**无换行**（MCP 的 image data 要求单行）。 */
    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n ushr 18) and 0x3F])
            sb.append(ALPHABET[(n ushr 12) and 0x3F])
            sb.append(ALPHABET[(n ushr 6) and 0x3F])
            sb.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n ushr 18) and 0x3F])
                sb.append(ALPHABET[(n ushr 12) and 0x3F])
                sb.append(ALPHABET[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }
}
