package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **思考强度的两套映射：发给谁、发什么**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：用户问"滑块真的能调节思考强度吗"
 * ═══════════════════════════════════════════════════════════════
 *
 * 查完的答案是：**在此之前不能。**
 *
 * `ThinkingEffort` 当时只有一条去向 —— [ThinkingEffort.preferredTier]
 * （换个模型），请求体里**从来没有过任何推理参数**。所以：
 *
 * | 情况 | 之前的实际效果 |
 * |---|---|
 * | 只配了一个模型 | 拉条**完全无效**（四个档位都是那个模型）|
 * | `off` 与 `low` | 映射到同一个 `LIGHT` 档，**两档没区别** |
 * | 界面上"深入推理""最长推理链" | 请求侧没有对应字段，纯文案 |
 *
 * ⇒ 现在多了 [ThinkingEffort.wireValue]（真正的 `reasoning_effort`），
 *   发不发由 `ProviderProfile.supportsReasoningEffort` 决定。
 */
class ThinkingEffortWireTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ wireValue：真的发给模型的那一档
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `off 不发推理参数`() {
        // ⚠️ 必须缺席，而不是发 `"reasoning_effort": "none"` ——
        //    后者不是协议里的合法值，部分厂商会直接 400。
        assertNull("off 不该发任何推理参数", ThinkingEffort.OFF.wireValue)
    }

    @Test
    fun `三档分别对应 low medium high`() {
        assertEquals("low", ThinkingEffort.LOW.wireValue)
        assertEquals("medium", ThinkingEffort.HIGH.wireValue)
        assertEquals("high", ThinkingEffort.MAX.wireValue)
    }

    @Test
    fun `四档里有三档真的发出去了 且互不相同`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是"四档压成三档、最高的两档又变成一样"
        // ══════════════════════════════════════════════════════
        // 只有 OFF 是 null（不指定）。其余三档必须**两两不同**。
        val sent = ThinkingEffort.entries
            .mapNotNull { it.wireValue }
        assertEquals("应当有三档真的发出参数", 3, sent.size)
        assertEquals("三档必须互不相同：$sent", 3, sent.toSet().size)
    }

    @Test
    fun `档位越高发出去的值越强`() {
        // 数组顺序就是 UI 从左到右的顺序
        val order = listOf(
            ThinkingEffort.LOW,
            ThinkingEffort.HIGH,
            ThinkingEffort.MAX,
        ).map { it.wireValue }
        assertEquals(listOf("low", "medium", "high"), order)
    }

    // ═══════════════════════════════════════════════════════════
    //  preferredTier：换模型那一条（Keep 原有行为）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `模型档位仍按原样映射`() {
        assertEquals(ModelTier.LIGHT, ThinkingEffort.OFF.preferredTier)
        assertEquals(ModelTier.LIGHT, ThinkingEffort.LOW.preferredTier)
        assertEquals(ModelTier.STANDARD, ThinkingEffort.HIGH.preferredTier)
        assertEquals(ModelTier.HEAVY, ThinkingEffort.MAX.preferredTier)
    }

    @Test
    fun `off 与 low 在换模型上仍然相同 但发出去的参数不同`() {
        // ⚠️ 这是诚实的现状：
        //    · 换模型那一条：两档一样（都想要 LIGHT）
        //    · 发参数那一条：off 不发、low 发 "low"
        //    所以对**支持 reasoning_effort 的厂商**，两档有区别；
        //    对不支持的厂商，两档确实没有区别 —— 这一点写在这里，
        //    免得以后有人以为两档永远等价。
        assertEquals(ThinkingEffort.OFF.preferredTier, ThinkingEffort.LOW.preferredTier)
        assertNull(ThinkingEffort.OFF.wireValue)
        assertEquals("low", ThinkingEffort.LOW.wireValue)
    }

    @Test
    fun `只有最右一档是 apex`() {
        assertEquals(
            listOf(ThinkingEffort.MAX),
            ThinkingEffort.entries.filter { it.isApex },
        )
    }
}
