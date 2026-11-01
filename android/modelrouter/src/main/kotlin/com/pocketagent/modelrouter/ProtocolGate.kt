package com.pocketagent.modelrouter

/**
 * **协议闸门** —— 判断"这个模型我们到底能不能聊"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：为什么需要这个
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户报了一个真 bug：OpenCode 请求被拒（400 缺 `x-opencode-session`）。
 * 修完那个去读官方文档，发现文档的 Endpoint 表里写着：
 *
 * | 模型 | 接口 |
 * |---|---|
 * | GLM / DeepSeek / Kimi / MiMo / Hy / Step | `/openai/v1/chat/completions` |
 * | Grok / GPT / Muse Spark | `/openai/v1/responses` |
 * | Claude / MiniMax M3 / Qwen3.8 | `/anthropic/v1/messages` |
 *
 * 而本项目的 OpenAI 兼容 provider **只会 `/chat/completions`**。
 *
 * ⇒ 那些模型补了请求头**照样会失败**，只是错误换个样子。
 *   而"自动导入"已经把 OpenCode Zen 的 28 个模型全建好了 ——
 *   里面有相当一部分是**注定用不了的**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 不在导入时静默丢弃**
 *    用户明确要"填完 Key 自动识别所有模型"。
 *    悄悄少导入几个，他会以为厂商没有那些模型。
 *    ⇒ 返回**原因**，由调用方统计并告知："跳过 N 个，因为它们要另一种接口"。
 *
 * **② 不假装能聊**
 *    把 `claude-haiku-5-5` 导进来，用户选中、发一句、收到 400 ——
 *    那正是本项目最忌讳的"说了但没做"。
 *
 * **③ 知识只放在一家厂商上**
 *    这些路由是 **OpenCode 的**，不是通用的。
 *    `gpt-5` 直连 OpenAI 时走 `/chat/completions` 完全正常 ——
 *    所以闸门按 **providerId** 分派，不能只看模型名。
 */
object ProtocolGate {

    /** 本项目的 provider 实际实现了哪些协议。目前只有一个。 */
    private val SUPPORTED = setOf(ModelProtocol.CHAT_COMPLETIONS)

    /**
     * 这个模型需要哪种协议。
     *
     * ⚠️ 只对**已知厂商**返回非 [ModelProtocol.CHAT_COMPLETIONS] 的值。
     *    未知厂商一律按 CHAT_COMPLETIONS —— 那是 OpenAI 兼容的事实标准，
     *    也是本 provider 的能力。
     */
    fun protocolFor(providerId: String, modelId: String): ModelProtocol {
        if (providerId !in OPENCODE_PROVIDERS) return ModelProtocol.CHAT_COMPLETIONS

        val id = modelId.lowercase()
        return when {
            // ── /anthropic/v1/messages ──────────────────────────
            id.startsWith("claude-") -> ModelProtocol.ANTHROPIC_MESSAGES
            // ⚠️ 必须**精确匹配**：文档里 `minimax-m3` 走 anthropic，
            //    但 `minimax-m3.1-flash` 走 chat/completions。
            //    用 startsWith 会把后者也误杀 —— 测试抓到过。
            id == "minimax-m3" -> ModelProtocol.ANTHROPIC_MESSAGES
            id.startsWith("qwen3.8-") -> ModelProtocol.ANTHROPIC_MESSAGES
            id.startsWith("qwen3.7-") -> ModelProtocol.ANTHROPIC_MESSAGES

            // ── /openai/v1/responses ────────────────────────────
            id.startsWith("gpt-") -> ModelProtocol.RESPONSES
            id.startsWith("grok-") -> ModelProtocol.RESPONSES
            id.startsWith("muse-spark-") -> ModelProtocol.RESPONSES

            else -> ModelProtocol.CHAT_COMPLETIONS
        }
    }

    /**
     * 这个模型能不能用本 provider 聊。
     *
     * @return null = 能用；非 null = **给人看的原因**
     */
    fun unsupportedReason(providerId: String, modelId: String): String? {
        val protocol = protocolFor(providerId, modelId)
        if (protocol in SUPPORTED) return null
        return "它走的是 ${protocol.displayName} 接口，本版本只实现了 " +
            "${ModelProtocol.CHAT_COMPLETIONS.displayName}"
    }

    /**
     * OpenCode 的两个 profile。
     *
     * ⚠️ 用集合而不是 `== "opencode"`：Go 是独立 profile。
     *    以后加 Zen 的其他档位只需在这里补一个 id。
     */
    private val OPENCODE_PROVIDERS = setOf("opencode", "opencode-go")
}

/**
 * 对话协议。
 *
 * ★ 目前只有 [CHAT_COMPLETIONS] 被实现。
 *   另外两个留着是因为**已经知道它们存在** ——
 *   写下来比"以后再说"更容易在实现时对上。
 */
enum class ModelProtocol(val displayName: String) {

    /** `POST {base}/chat/completions` —— OpenAI 兼容的事实标准。**已实现**。 */
    CHAT_COMPLETIONS("chat/completions"),

    /** `POST {base}/responses` —— OpenAI 的新 Responses API。**未实现**。 */
    RESPONSES("responses"),

    /** `POST {base}/messages` —— Anthropic Messages API。**未实现**。 */
    ANTHROPIC_MESSAGES("anthropic messages"),
}
