package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **协议闸门**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个闸门最怕的两类错误
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **误杀**：把一个其实能用的模型标成"用不了"。
 *    用户明明有这个模型、明明能用，却被拦在门外 —— 比放进来更烦人。
 *
 * ② **漏放**：把一个其实用不了的模型放进来。
 *    用户选中、发一句、收到 400 —— 而那正是他刚才报的那个 bug 的同类。
 *
 * ⚠️ 特别注意：这些路由是 **OpenCode 的**，不是通用的。
 *    `gpt-5` 直连 OpenAI 时走 chat/completions 完全正常。
 */
class ProtocolGateTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ OpenCode 的真实路由（来自官方 Endpoint 表）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `OpenCode 的 Claude 走 anthropic`() {
        assertEquals(
            ModelProtocol.ANTHROPIC_MESSAGES,
            ProtocolGate.protocolFor("opencode-go", "claude-haiku-5-5"),
        )
    }

    @Test
    fun `OpenCode 的 Qwen3_8 走 anthropic`() {
        assertEquals(
            ModelProtocol.ANTHROPIC_MESSAGES,
            ProtocolGate.protocolFor("opencode-go", "qwen3.8-max"),
        )
        assertEquals(
            ModelProtocol.ANTHROPIC_MESSAGES,
            ProtocolGate.protocolFor("opencode-go", "qwen3.7-plus"),
        )
    }

    @Test
    fun `OpenCode 的 MiniMax M3 走 anthropic`() {
        assertEquals(
            ModelProtocol.ANTHROPIC_MESSAGES,
            ProtocolGate.protocolFor("opencode-go", "minimax-m3"),
        )
        // ⚠️ M3.1 Flash 走的是 chat/completions —— 不能因为前缀相近就误杀
        assertEquals(
            ModelProtocol.CHAT_COMPLETIONS,
            ProtocolGate.protocolFor("opencode-go", "minimax-m3.1-flash"),
        )
    }

    @Test
    fun `OpenCode 的 GPT 和 Grok 走 responses`() {
        assertEquals(
            ModelProtocol.RESPONSES,
            ProtocolGate.protocolFor("opencode-go", "gpt-6-luna"),
        )
        assertEquals(
            ModelProtocol.RESPONSES,
            ProtocolGate.protocolFor("opencode-go", "grok-4.7"),
        )
        assertEquals(
            ModelProtocol.RESPONSES,
            ProtocolGate.protocolFor("opencode-go", "muse-spark-1.3-contributor"),
        )
    }

    @Test
    fun `OpenCode 的常见模型是 chat_completions`() {
        // ★ 这些必须能通过 —— 它们是主力，误杀就是灾难
        val ok = listOf(
            "glm-5.3-flash", "glm-5.3", "glm-5.2",
            "kimi-k3", "kimi-k2.7-code",
            "deepseek-v4.1-flash", "deepseek-v4-pro",
            "mimo-v2.6-flash", "minimax-m3.1-flash",
            "longcat-2.0", "step-5-preview", "hy3", "hy4-preview",
            "beam-free", "iceman-contributor-free",
        )
        for (id in ok) {
            assertEquals(
                "「$id」走的是 chat/completions，不该被拦",
                ModelProtocol.CHAT_COMPLETIONS,
                ProtocolGate.protocolFor("opencode-go", id),
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 知识不能外溢到别的厂商
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `别的厂商的 gpt 不受影响`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是"知识外溢"
        // ══════════════════════════════════════════════════════
        // `gpt-5` 在 OpenCode 上走 /responses，但直连 OpenAI 时
        // 走 chat/completions 是**完全正常**的。
        // 如果闸门只看模型名，所有 OpenAI 直连用户都会用不了。
        assertEquals(
            ModelProtocol.CHAT_COMPLETIONS,
            ProtocolGate.protocolFor("openai", "gpt-5"),
        )
        assertEquals(
            ModelProtocol.CHAT_COMPLETIONS,
            ProtocolGate.protocolFor("deepseek", "claude-sonnet-4"),
        )
        assertEquals(
            ModelProtocol.CHAT_COMPLETIONS,
            ProtocolGate.protocolFor("openrouter", "grok-code"),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  给用户看的原因
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `能用的模型没有原因`() {
        assertNull(ProtocolGate.unsupportedReason("opencode-go", "glm-5.2"))
        assertNull(ProtocolGate.unsupportedReason("openai", "gpt-4o"))
    }

    @Test
    fun `用不了的模型给出可读原因`() {
        val r = ProtocolGate.unsupportedReason("opencode-go", "claude-haiku-5-5")
        assertNotNull("必须说明为什么，不能静默丢弃", r)
        assertTrue("原因里要提到具体接口：$r", r!!.contains("anthropic"))
        assertTrue("原因里要说明我们只实现了什么：$r", r.contains("chat/completions"))
    }

    @Test
    fun `原因里不提内部类名`() {
        // ⚠️ 用户不该看到 `ModelProtocol.ANTHROPIC_MESSAGES` 这种字符串。
        val r = ProtocolGate.unsupportedReason("opencode-go", "gpt-6-luna")!!
        assertTrue("不该出现枚举全大写：$r", !r.contains("RESPONSES"))
        assertTrue("不该出现类名：$r", !r.contains("ModelProtocol"))
    }
}
