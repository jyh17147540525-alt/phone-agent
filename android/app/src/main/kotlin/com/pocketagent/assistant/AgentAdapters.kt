package com.pocketagent.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import com.pocketagent.agentlogic.AgentLlmPort
import com.pocketagent.agentlogic.AgentMessage
import com.pocketagent.agentlogic.AgentToolCall
import com.pocketagent.agentlogic.AgentToolResult
import com.pocketagent.agentlogic.AgentToolRunner
import com.pocketagent.agentlogic.AgentToolSpec
import com.pocketagent.agentlogic.AgentTurnReply
import com.pocketagent.mcp.McpTool
import com.pocketagent.mcp.ToolOutcome
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.ToolSpec
import timber.log.Timber

/**
 * `:agentlogic` 的循环 ←→ 真实世界的**适配层**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么适配放在 `:app`，而不是让 `:agentlogic` 直接依赖两边
 * ═══════════════════════════════════════════════════════════════
 *
 * `:agentlogic` 是纯 Kotlin（在 `PURE_KOTLIN` 里，280+ 条离线测试）。
 * 它一旦 `import com.pocketagent.provider.api.*`，就会掉出离线验证器 ——
 * 而它承载的恰恰是那些**判错了不报错**的逻辑（重复检测、安全拒绝不许重试、
 * 预算中止）。
 *
 * ⇒ 循环只认 [AgentMessage] / [AgentToolResult] 这些**纯形状**；
 *   本文件是**唯一**同时知道两边类型的地方，负责翻译。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 工具执行**直接调 `McpTool.call()`**，不走 HTTP
 * ═══════════════════════════════════════════════════════════════
 *
 * 这一点是刻意的，它买到三件事：
 *
 *  1. **安全判定天然一致**：`:safety` 的六步判定、频率闸、盲点文案
 *     全在 `McpTool.call()` 里面。走 HTTP 要重新经过 dispatcher
 *     （多一层 JSON-RPC 编解码 + 超时），而**结果完全一样**；
 *  2. **限流器是同一个实例**（见 `AppContainer.agentTools` 的注释）——
 *     否则两条链路的节奏会叠加成 4 倍；
 *  3. **少一层失败面**：HTTP 那条路要处理 loopback 端口、token 校验、
 *     连接超时 —— 那些在"应用内自己调自己"的场景里全是白搭的风险。
 */
object AgentAdapters {

    // ═══════════════════════════════════════════════════════════
    //  工具声明：MCP → 模型
    // ═══════════════════════════════════════════════════════════

    /**
     * 把 MCP 工具清单翻成模型能看的声明。
     *
     * ⚠️ schema **原样透传**（`inputSchema()` 的 JSON 字符串）——
     *    不在这里解析再重新序列化。理由：工具 schema 的解释权在 `:mcp`
     *    （它自己的测试钉着），这里重新序列化一次就出现了"同一份 schema
     *    的两份理解"，而漂移的方向是**模型看到的参数与工具真正接受的参数
     *    不一致** —— 那种错误在真机上表现为"它总是说参数不对"。
     */
    fun toolSpecs(tools: List<McpTool>): List<AgentToolSpec> = tools.map { t ->
        AgentToolSpec(
            name = t.name,
            description = t.description,
            parametersJsonSchema = t.inputSchema().toString(),
        )
    }

    /** 把模型要的工具调用落回 MCP 工具并执行。 */
    fun toolRunner(tools: List<McpTool>): AgentToolRunner {
        val byName = tools.associateBy { it.name }
        return AgentToolRunner { call ->
            val tool = byName[call.name]
                ?: return@AgentToolRunner AgentToolResult.Failed(
                    "没有名为 `${call.name}` 的工具。可用的是：${byName.keys.joinToString("、")}",
                )

            // ⚠️ 参数解析失败 = 模型给的不是合法 JSON。这属于**可以重试**的失败
            //    （模型看到原因后能改），不能算 Blocked。
            val args = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }
                .getOrNull()
                ?: return@AgentToolRunner AgentToolResult.Failed(
                    "参数不是合法的 JSON 对象：${call.argumentsJson.take(120)}",
                )

            when (val outcome = tool.call(args)) {
                is ToolOutcome.Success -> {
                    // ★ 诊断日志：真机上"读不到屏幕"可能是三件完全不同的事 ——
                    //   ① 模型没调工具；② 工具调了但返回空；③ 工具返回了内容，
                    //   模型自己没用上。只靠最终答复无法区分，而三者的修法不同。
                    //   这里只记**长度与前 200 字**，不整段落日志（屏幕内容含隐私）。
                    Timber.i(
                        "工具 %s 成功：%d 字，开头=%s",
                        call.name,
                        outcome.text.length,
                        outcome.text.take(200),
                    )
                    AgentToolResult.Ok(outcome.text)
                }

                // ★★ 三分支的映射是**本文件最要紧**的三行。
                //    `:mcp` 的 `Refused` 语义是「安全策略的最终结论，不要重试」
                //    （见 ToolRegistry 的注释），所以它必须落到 Blocked ——
                //    落到 Failed 会让模型以为"只是没成功"，于是换个坐标再试。
                is ToolOutcome.Refused -> {
                    Timber.w("工具 %s 被安全策略拒绝：%s", call.name, outcome.reason)
                    AgentToolResult.Blocked(outcome.reason)
                }

                is ToolOutcome.Failed -> {
                    Timber.w("工具 %s 失败：%s", call.name, outcome.reason)
                    AgentToolResult.Failed(outcome.reason)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  模型调用：agent 循环 → LlmProvider
    // ═══════════════════════════════════════════════════════════

    /**
     * 一次补全的适配器。
     *
     * @param provider 选好的 provider（按用途选，见 `ChatPort` 的 ②）
     * @param credential 该模型**自己绑的**那条 Key
     *
     * ⚠️ **刻意不收 `systemPrompt` 参数**：系统提示词由循环作为
     *    `AgentMessage.System` 带在消息列表首位（那是它自己组装的，
     *    可以离线断言）。这里再注入一次会让模型收到**两份人格** ——
     *    而两份的措辞略有不同时，模型的行为会变得不可预测，
     *    且排查时完全看不出是哪一份在起作用。
     */
    fun llmPort(
        provider: LlmProvider,
        credential: ProviderCredential,
        model: String,
    ): AgentLlmPort = AgentLlmPort { request ->
        val messages = request.messages.map { it.toChatMessage() }

        val tools = request.tools.map {
            ToolSpec(
                name = it.name,
                description = it.description,
                parametersJsonSchema = it.parametersJsonSchema,
            )
        }

        var text = StringBuilder()
        // 按 id 归并分片（与网关 SseResponseWriter 同一套语义）
        val calls = ToolCallAccumulator()

        provider.chat(
            request = ChatRequest(model = model, messages = messages, tools = tools),
            credential = credential,
        ).collect { chunk ->
            when (chunk) {
                is ChatChunk.Delta -> text.append(chunk.text)

                // 推理过程不进正文（同 ChatPort 的既定纪律）
                is ChatChunk.Reasoning -> Unit

                is ChatChunk.ToolCallDelta -> calls.accept(chunk)

                is ChatChunk.Done -> Unit
            }
        }

        val ready = calls.build()
        // ★ 诊断日志：区分"模型没调工具"与"调了但结果为空"。
        //   前者是提示词/模型能力问题，后者是权限/读屏问题 —— 修法完全不同。
        Timber.i(
            "模型回合：正文 %d 字，工具调用 %d 个%s",
            text.length,
            ready.size,
            if (ready.isEmpty()) "" else "：${ready.joinToString("、") { it.name }}",
        )
        if (ready.isEmpty()) {
            AgentTurnReply.Final(text.toString())
        } else {
            // ⚠️ 工具调用与正文**同时**存在时两个都要留下：
            //    模型常先说"我先看一眼屏幕"再调工具，那句话是给用户看的。
            AgentTurnReply.Calls(ready, text.toString().takeIf { it.isNotBlank() })
        }
    }

    /**
     * 拼接 `tool_calls` 分片。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 归并规则（与 `SseResponseWriter.ToolCallAccumulator` 相同）
     * ═══════════════════════════════════════════════════════════
     *
     * OpenAI 的分片方式是：**首片带 `id` + `name`，后续片只带参数增量**。
     * 所以：
     *  - 有 `id` ⇒ 按 id 归并（同一调用的后续片会再次带上 id）
     *  - 没有 `id` ⇒ 归到**上一个**调用上（这正是参数增量的样子）
     *
     * ⚠️ 不能"没有 id 就每片独立" —— 那会把一个调用拆成好几个，
     *    每个都只有一小段参数，于是**每个都解析失败**。
     *
     * ⚠️ 也不能"没有 id 就按 name 归并"：后续片 name 为 null，
     *    于是首片落一个键、第二片落到匿名键 —— 同样被拆开。
     *    （这个坑网关侧踩过并有注释，这里保持同一套语义。）
     */
    private class ToolCallAccumulator {

        private class Entry(val id: String, var name: String, val args: StringBuilder = StringBuilder())

        private val order = mutableListOf<String>()
        private val byKey = mutableMapOf<String, Entry>()
        private var currentKey: String? = null

        fun accept(delta: ChatChunk.ToolCallDelta) {
            val key = delta.id ?: currentKey ?: "anon-${order.size}"
            currentKey = key

            val entry = byKey.getOrPut(key) {
                order += key
                Entry(id = delta.id ?: key, name = delta.name.orEmpty())
            }
            // 名字可能后到（少数厂商把 name 放在第二片），补上
            delta.name?.let { if (entry.name.isEmpty()) entry.name = it }
            delta.argumentsFragment?.let { entry.args.append(it) }
        }

        fun build(): List<AgentToolCall> = order.mapNotNull { key ->
            val e = byKey.getValue(key)
            // 名字都没有的调用无法执行 —— 丢掉它比猜一个名字安全
            if (e.name.isEmpty()) return@mapNotNull null
            AgentToolCall(id = e.id, name = e.name, argumentsJson = e.args.toString().ifEmpty { "{}" })
        }
    }
}

/** `:agentlogic` 的消息 → `provider.api` 的消息。 */
private fun AgentMessage.toChatMessage(): ChatMessage = when (this) {
    is AgentMessage.System -> ChatMessage.system(text)
    is AgentMessage.User -> ChatMessage.user(text)
    is AgentMessage.Assistant -> ChatMessage(
        role = ChatMessage.Role.ASSISTANT,
        content = buildList {
            if (text.isNotBlank()) add(ContentPart.Text(text))
            calls.forEach { add(ContentPart.ToolCall(it.id, it.name, it.argumentsJson)) }
        },
    )
    // ⚠️ `toolCallId` 必须带上：协议要求工具结果与调用一一对应，
    //    丢了它 OpenAI 会直接报 400（"tool_call_id is required"）。
    is AgentMessage.ToolResult -> ChatMessage(
        role = ChatMessage.Role.TOOL,
        content = listOf(ContentPart.ToolResult(toolCallId = callId, content = text)),
        toolCallId = callId,
    )
}
