package com.pocketagent.provider.gateway.http

import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.TokenUsage
import kotlinx.serialization.json.Json

/**
 * 服务端 SSE 写出 —— `Flow<ChatChunk>` → `data: {...}\n\n` 文本。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ `[DONE]` 必须**恰好一个**
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是 BYOK §4.3 单独列出来的坑，而且它有一个真实来源：
 * `OpenAiCompatProvider.chat` 的注释里记录过"Done 可能发两次或零次"。
 *
 * 两种错法的后果不同但**都静默**：
 *
 * | | 现象 |
 * |---|---|
 * | 零个 | 客户端一直等流结束，直到超时。用户看到"卡住了" |
 * | 两个 | 客户端把第二个 `[DONE]` 当成新流开始，可能报 JSON 解析错，也可能直接忽略 |
 *
 * 所以本类**不依赖上游的 Done 数量**：它把 `ChatChunk.Done` 翻译成
 * "发最后一个内容 chunk（带 usage）"，而 `[DONE]` 由
 * [SseResponseWriter.finish] **在流结束时无条件发一次**。
 *
 * ⚠️ 换句话说：**`[DONE]` 的发出点是"流结束"，不是"收到 Done chunk"**。
 *    这个改动让"上游 Done 重复/缺失"都无法污染我们的协议正确性。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是纯函数式的，因为要能离线测
 * ═══════════════════════════════════════════════════════════════
 *
 * 本类**不做任何 IO** —— 输入是 chunk，输出是字符串。
 * 「把字符串写进 socket」是 `HttpGatewayServer` 的事。
 *
 * 这个切分不是为了好看：SSE 的协议细节（恰好一个 DONE、空 delta、
 * finish_reason 的位置）全是**逻辑**，而它们恰恰是最容易错、
 * 也最容易离线钉死的部分。把 IO 混进来会让它们只能靠真机验证。
 */
class SseResponseWriter(
    /**
     * 写回给客户端的虚拟模型名。
     *
     * ⚠️ 与 [SseResponseWriter] 的 `GatewayModelInfo.id` 必须一致 ——
     *    否则客户端会发现"我请求的模型，响应里变成了另一个名字"，
     *    某些 SDK 会据此判定请求失败。
     */
    private val modelName: String,
    /** 分片 id 前缀；同一次请求内所有 chunk 共享一个 id */
    private val responseId: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val json: Json = defaultJson(),
) {

    // ═══════════════════════════════════════════════════════════
    //  流式
    // ═══════════════════════════════════════════════════════════

    /**
     * 把一个上游 chunk 翻成**零到一行** SSE 文本。
     *
     * 返回空串表示"这个 chunk 不产生任何输出"。
     *
     * - `Delta` → 一个 content 分片
     * - `Reasoning` → 一个 reasoning_content 分片（透传，让 dsh 能显示思考过程）
     * - `ToolCallDelta` → 一个 tool_calls 分片
     * - `Done` → 一个**带 usage 与 finish_reason 的收尾分片**（⚠️ 不是 `[DONE]`）
     *
     * ⚠️ **不产 `[DONE]`** —— 那是 [finish] 的职责，见类注释。
     */
    fun chunkToSse(chunk: ChatChunk): String = when (chunk) {
        is ChatChunk.Delta ->
            // ⚠️ 空文本的 Delta 直接跳过。协议的 SSE 帧里 `content: ""`
            //    是合法的，但没有任何信息量，而它会让客户端多一次
            //    "收到数据了"的回调 —— 在高频流式下是纯开销。
            if (chunk.text.isEmpty()) "" else frame(
                GatewayChunkChoice(delta = GatewayChunkDelta(content = chunk.text))
            )

        is ChatChunk.Reasoning ->
            if (chunk.text.isEmpty()) "" else frame(
                GatewayChunkChoice(delta = GatewayChunkDelta(reasoningContent = chunk.text))
            )

        is ChatChunk.ToolCallDelta -> frame(
            GatewayChunkChoice(
                delta = GatewayChunkDelta(
                    toolCalls = listOf(
                        GatewayChunkToolCall(
                            // ⚠️ 用"当前已发出的工具调用片数"当 index。
                            //    上游给的 id 可能是 null（第一片才有 id），
                            //    所以不能拿 id 派生 index。
                            index = toolCallIndex.getAndIncrement(),
                            id = chunk.id,
                            type = "function",
                            function = GatewayFunctionCall(
                                name = chunk.name,
                                arguments = chunk.argumentsFragment,
                            ),
                        )
                    )
                )
            )
        )

        is ChatChunk.Done -> frame(
            GatewayChunkChoice(
                delta = GatewayChunkDelta(),
                finishReason = chunk.finishReason ?: DEFAULT_FINISH_REASON,
            ),
            usage = chunk.usage.toGatewayUsage(),
        )
    }

    /**
     * 流结束标记。**只在流真正结束时调用一次。**
     *
     * ⚠️ `[DONE]` 后面**不能**再有空行以外的内容 —— 部分客户端的
     *    解析器在读到 `[DONE]` 后仍会尝试解析后续 data 行。
     */
    fun finish(): String = "data: [DONE]\n\n"

    /**
     * 流中错误事件。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么走"流内错误"而不是"非 200 状态码"
     * ═══════════════════════════════════════════════════════════
     *
     * HTTP 状态码在**首字节发出时**就定了。上游在第 3 个 chunk 之后
     * 报 401 时，我们已经回了 `200 OK` —— 改不了了。只能：
     *
     * 1. 发一个 OpenAI 风格的 error 载荷（`{"error": {...}}`）
     * 2. 然后发 `[DONE]`，让客户端知道流结束了
     *
     * ⚠️ **顺序不能反**：先 `[DONE]` 的话，客户端会认为流正常结束，
     *    后续的 error 载荷被当成"意外的数据" —— 有些 SDK 直接丢弃，
     *    用户看到的是"回答突然截断了"，而没有任何错误提示。
     *
     * ⚠️ [message] **必须已经脱敏**（BYOK §4.4 第 ⑥ 条）。
     *    本类不做脱敏 —— 因为脱敏规则在 `:core:network`，
     *    而那个模块的依赖关系与这里相反（它是 Android library）。
     *    调用方（`HttpGatewayServer`）负责脱敏后再传进来。
     */
    fun errorEvent(message: String, code: String): String {
        val payload = GatewayErrorResponse(
            error = GatewayErrorDetail(
                message = message,
                // 固定 "upstream_error"，**不暴露上游的类型名** ——
                // 那会让 dsh 能推断出背后是哪家（呼应安全加固第 ⑤ 条）
                type = "upstream_error",
                code = code,
            )
        )
        return "data: " + json.encodeToString(GatewayErrorResponse.serializer(), payload) + "\n\n"
    }

    // ═══════════════════════════════════════════════════════════
    //  非流式
    // ═══════════════════════════════════════════════════════════

    /**
     * 把一串 chunk **聚合成一个非流式响应体**。
     *
     * ⚠️ 必须支持非流式 —— BYOK §4.3 列的坑之一。
     *    dsh 的某些探测请求会发 `stream: false`，只做流式的网关
     *    会让这些请求全部超时，而日志上看不出任何异常。
     *
     * 聚合规则：
     * - 所有 `Delta` 的文本**按顺序拼接**（这是唯一正确的做法；
     *   只取最后一个是错的，因为上游是增量发文本的）
     * - `Reasoning` 同样拼接
     * - `ToolCallDelta` 按 index/id **合并**（同一调用的参数分片要拼起来）
     * - `Done` 提供 usage 与 finish_reason
     *
     * @param chunks 上游全部 chunk（已收集完）
     */
    fun aggregateToResponse(chunks: List<ChatChunk>): GatewayChatResponse {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = ToolCallAccumulator()
        var usage: TokenUsage? = null
        var finishReason: String? = null

        for (c in chunks) {
            when (c) {
                is ChatChunk.Delta -> text.append(c.text)
                is ChatChunk.Reasoning -> reasoning.append(c.text)
                is ChatChunk.ToolCallDelta -> toolCalls.accept(c)
                is ChatChunk.Done -> {
                    usage = c.usage
                    finishReason = c.finishReason
                }
            }
        }

        val message = GatewayResponseMessage(
            role = "assistant",
            // ⚠️ 工具调用场景下 content 可以是 null（协议允许）。
            //    这里在有工具调用时**仍然给空串**而不是 null ——
            //    因为部分客户端的 `content.length` 会 NPE。
            content = text.toString().ifEmpty { if (toolCalls.isEmpty) "" else null },
            reasoningContent = reasoning.toString().ifEmpty { null },
            toolCalls = toolCalls.build().ifEmpty { null },
        )

        return GatewayChatResponse(
            id = responseId,
            created = nowMillis() / 1000,
            model = modelName,
            choices = listOf(
                GatewayChoice(
                    index = 0,
                    message = message,
                    // ⚠️ 有工具调用但上游没给 finish_reason 时，补 "tool_calls" ——
                    //    否则客户端不知道该去执行工具，会认为"回答完了"。
                    finishReason = finishReason
                        ?: if (toolCalls.isEmpty) DEFAULT_FINISH_REASON else "tool_calls",
                )
            ),
            usage = usage?.toGatewayUsage(),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    /** 已发出的工具调用片数 —— 用作协议里的 `index` */
    private val toolCallIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private fun frame(choice: GatewayChunkChoice, usage: GatewayUsage? = null): String {
        val payload = GatewayChatChunk(
            id = responseId,
            // ⚠️ 这两个字段**没有默认值**，必须显式传 —— 见 DTO 里的注释：
            //    encodeDefaults=false 下，等于默认值的字段不会出现在 JSON 里，
            //    而 `object` 与 `delta` 恰恰是客户端第一时间读的两个字段。
            objectType = OBJECT_TYPE,
            created = nowMillis() / 1000,
            model = modelName,
            choices = listOf(choice),
            usage = usage,
        )
        return "data: " + json.encodeToString(GatewayChatChunk.serializer(), payload) + "\n\n"
    }

    private class ToolCallAccumulator {
        /** 按归并键记录出现顺序，保证输出顺序与上游一致 */
        private val order = mutableListOf<String>()
        private val byKey = mutableMapOf<String, MutableEntry>()

        private class MutableEntry(
            var id: String?,
            val name: StringBuilder = StringBuilder(),
            val arguments: StringBuilder = StringBuilder(),
        )

        val isEmpty: Boolean get() = byKey.isEmpty()

        fun accept(delta: ChatChunk.ToolCallDelta) {
            // ═══════════════════════════════════════════════════════
            //  ⚠️ 归并键只在**有 id 时**才按 id 归并
            // ═══════════════════════════════════════════════════════
            //
            // 第一版写成"没有 id 就按 name 归并，再没有就每片独立"，
            // 然后被测试抓住：上游的**首片给 name、后续片 name 为 null**，
            // 于是首片键是 `name:click`、第二片键是 `anon:1` ——
            // 一个调用被拆成了两个，参数拼不起来。
            //
            // 正确语义："没有 id"意味着我们**根本无法判断哪几片属于
            // 同一个调用**。此时按"连续同名"归并：只有当前片给了 name
            // 且没有 id 时，新的名字才开一个新的调用；name 为 null 的片
            // 一律归到**上一个**调用上（这正符合 OpenAI 的分片方式：
            // 首片带 name+id，后续片只带参数增量）。
            val key = delta.id ?: currentKey ?: delta.name?.let { "name:$it" } ?: ANON_KEY

            val entry = byKey.getOrPut(key) {
                order += key
                MutableEntry(id = delta.id)
            }

            // 记录"当前正在拼的调用"，供无 id 的后续片归位
            currentKey = key

            delta.name?.let { entry.name.append(it) }
            delta.argumentsFragment?.let { entry.arguments.append(it) }
        }

        /** 上一个片落在哪个键上 —— 无 id 的后续片归到这里 */
        private var currentKey: String? = null

        fun build(): List<GatewayResponseToolCall> = order.mapNotNull { key ->
            val e = byKey.getValue(key)
            GatewayResponseToolCall(
                // ⚠️ 客户端要靠 id 把 tool 结果回传（`tool_call_id`），
                //    所以不能是 null。上游没给就自己造一个 —— 造出来的
                //    对这个会话是自洽的（我们回传时用同一个）。
                id = e.id ?: "call_$key",
                function = GatewayFunctionCall(
                    name = e.name.toString().ifEmpty { null },
                    arguments = e.arguments.toString().ifEmpty { "{}" },
                ),
            )
        }
    }

    companion object {
        /**
         * 协议里 `object` 字段的固定值。
         *
         * ⚠️ 单独抽成常量是因为 Kotlin 里 `object` 是软关键字，
         *    属性名只能叫 `objectType` —— 见 DTO 的注释。
         */
        const val OBJECT_TYPE = "chat.completion.chunk"

        /**
         * 上游没给 finish_reason 时的默认值。
         *
         * ⚠️ 不能留 null：不少客户端把 null 当成"流异常中断"，
         *    于是把已经收到的完整回答丢掉重试。
         */
        const val DEFAULT_FINISH_REASON = "stop"

        /** 无 id 且无 name 的工具调用片的兜底键 */
        private const val ANON_KEY = "anon"

        /**
         * 默认 JSON 配置。
         *
         * ═══════════════════════════════════════════════════════════
         *  ⚠️ 这里开 `encodeDefaults = true`，与最初的设计**相反**
         * ═══════════════════════════════════════════════════════════
         *
         * 第一版开的是 `false`，理由听起来很对："少发没有信息的字段"。
         * 但它被 `HttpGatewayServerTest` 抓出一个**静默**故障：
         *
         * `GatewayChoice.finishReason` 的默认值是 `null`，而
         * `SseResponseWriter` 在 `Delta` 路径上**不传它**。于是
         * `encodeDefaults = false` 把 `finish_reason` **整个从 JSON
         * 里删掉** —— 而 OpenAI 协议要求这个字段**每一帧都在**
         * （值为 `null` 表示"还没结束"）。大量 SDK 靠 `"finish_reason"
         * in chunk` 判断该不该继续读，字段缺失会被当成流异常。
         *
         * 症状的迷惑性在于：**流式看起来完全正常**（文本一帧帧到达），
         * 只有做"非流式聚合"或换用严格 SDK 的客户端才会崩 ——
         * 而那时排查方向会指向聚合逻辑，不是 JSON 配置。
         *
         * 修法是开 `true`。代价是每帧多几个 `"index":0` 之类的字段
         * （几字节）。**用几字节换取"协议字段永不意外消失"是划算的**，
         * 因为后者是一种**会静默破坏客户端**的失效模式。
         *
         * ⚠️ `explicitNulls = false` 仍然必须：开 `encodeDefaults`
         *    不带它的话 `"content": null` 会被显式写出来，而部分
         *    客户端的 `content.trim()` 会 NPE。两者要一起开 ——
         *    "默认值照发" + "null 不发" 才是协议要的形态。
         *
         * ⚠️ 不设 `prettyPrint`：SSE 的每个 data 载荷必须是**单行**。
         *    开了它会让 JSON 里出现换行，把一个事件拆成两个 ——
         *    而症状是"客户端 JSON 解析失败"，指向完全不在配置上。
         */
        fun defaultJson(): Json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

/** [TokenUsage] → 协议里的 usage 结构 */
internal fun TokenUsage.toGatewayUsage(): GatewayUsage = GatewayUsage(
    promptTokens = inputTokens,
    completionTokens = outputTokens,
    totalTokens = total,
)
