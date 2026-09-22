package com.pocketagent.core.database.entity

/**
 * 凭据的用途。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么要区分用途，而不是"一个 Key 走天下"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户实际会准备**性质完全不同的两类凭据**：
 *
 * | 用途 | 典型来源 | 计费 | 泄漏后果 |
 * |---|---|---|---|
 * | [LLM] | DeepSeek / OpenRouter / 自建 | 按 token，可能很贵 | 余额被刷光 |
 * | [TTS] | 讯飞 / Azure / 火山 | 按字符 | 额度被消耗 |
 *
 * 混在一起会有两个具体问题：
 *
 * 1. **误用**：TTS 的 Key 被拿去发对话请求，结果是 401；用户看到"Key 无效"，
 *    但那个 Key 在 TTS 场景明明好着 —— 制造一个用户无法自救的困境
 *  （与 `CredentialCheckStatus.UNREACHABLE` 要分开是同一类问题）。
 * 2. **校验方式不同**：LLM 可以用 `/models` 探活，TTS 不一定有等价端点。
 *    混在一起就只能用最弱的那种校验。
 *
 * ⚠️ 刻意**不把"调度 Key"单列一类**。
 *
 *    调度模型也是 LLM，走同样的协议、同样的鉴权、同样的 `/models` 校验。
 *    它和普通 LLM Key 的区别是**被哪个角色使用**，而那是
 *    `ModelConfigEntity.role` 的职责，不是凭据的职责。
 *    在这里单列会让"同一个 Key 既当调度又当执行"无法表达 ——
 *    而那恰恰是最常见的用法（用户通常只有一个 Key）。
 */
enum class CredentialPurpose {
    /** 大语言模型（对话、推理、工具调用） */
    LLM,

    /** 语音合成。只用于 TTS，不参与任何对话请求 */
    TTS,
    ;

    val displayName: String
        get() = when (this) {
            LLM -> "模型接口"
            TTS -> "语音合成"
        }

    /** 该用途的凭据能否被用于发起对话请求 */
    val canServeChatRequests: Boolean
        get() = this == LLM
}
