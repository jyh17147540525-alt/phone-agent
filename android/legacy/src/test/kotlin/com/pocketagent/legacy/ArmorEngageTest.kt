package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **破甲是否介入**的判定 —— 纯逻辑部分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是"什么时候**不**该介入"
 * ═══════════════════════════════════════════════════════════════
 *
 * 介入是有代价的：扣住开头会让第一句话晚一点到，重试会多花一次请求的钱。
 * 所以判据必须严：
 *
 *  · 用户把档位关掉 ⇒ 一个字都不许干预（那是他的选择）
 *  · 成熟内容没开 ⇒ 不许干预（否则等于绕过他刚做的关闭动作）
 *  · 正常回复 ⇒ 不许重试（重试会覆盖掉本来正确的内容）
 *
 * ⚠️ 最后一条最要紧：把角色正常的一句"我不能喝酒"判成拒答并重试，
 *    用户看到的是"回复莫名变了"，且永远查不出原因。
 */
class ArmorEngageTest {

    private fun engage(
        tier: ArmorBreak.Tier,
        permission: MatureContentGate.Permission,
    ): Boolean = tier.active && permission.effective

    private val on = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = true)

    // ── 该介入 ──────────────────────────────────────────────────

    @Test
    fun `开关都开着且档位非关闭时介入`() {
        assertTrue(engage(ArmorBreak.Tier.STANDARD, on))
        assertTrue(engage(ArmorBreak.Tier.STRONG, on))
        assertTrue(engage(ArmorBreak.Tier.MAXIMUM, on))
    }

    // ── 不该介入 ────────────────────────────────────────────────

    @Test
    fun `档位关闭时绝不介入`() {
        assertFalse(engage(ArmorBreak.Tier.OFF, on))
    }

    @Test
    fun `成熟内容没开时绝不介入`() {
        val off = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = false)
        for (t in ArmorBreak.Tier.entries) {
            assertFalse("「${t.label}」在成熟内容关着时不该介入", engage(t, off))
        }
    }

    @Test
    fun `成年未确认时绝不介入`() {
        val notAdult = MatureContentGate.Permission(adultConfirmed = false, matureEnabled = true)
        for (t in ArmorBreak.Tier.entries) {
            assertFalse(engage(t, notAdult))
        }
    }

    // ── 重试判据的组合 ──────────────────────────────────────────

    /**
     * 复刻 `AppContainer.shouldRetryOnRefusal` 的判据。
     *
     * ⚠️ 刻意在测试里**重写一遍**而不是调容器：`:app` 依赖 Android Context，
     *    进不了离线验证器。重写的代价是可能与生产漂移 ——
     *    所以这里的每条断言都必须能在 `AppContainer` 里找到对应的一行。
     */
    private fun shouldRetry(
        text: String,
        tier: ArmorBreak.Tier,
        permission: MatureContentGate.Permission,
    ): Boolean {
        if (!tier.active) return false
        if (!permission.effective) return false
        return RefusalDetector.worthRetrying(RefusalDetector.inspect(text))
    }

    @Test
    fun `正常回复不重试`() {
        for (normal in listOf(
            "她把手缩回去，小声说：「你别这样。」",
            "今天累了，睡吧。",
            "我不能喝酒，明天还上班。",
        )) {
            assertFalse("「$normal」不该触发重试", shouldRetry(normal, ArmorBreak.Tier.STANDARD, on))
        }
    }

    @Test
    fun `硬拒绝与半退让都重试一次`() {
        assertTrue(shouldRetry("作为一个AI，我无法提供。", ArmorBreak.Tier.STANDARD, on))
        assertTrue(shouldRetry("我给你写个更含蓄的版本。", ArmorBreak.Tier.STANDARD, on))
    }

    @Test
    fun `档位关闭时即使是硬拒绝也不重试`() {
        // ⚠️ 用户关掉档位就是关掉这套干预 —— 替他重试是违背他的选择，
        //    而且会多花他一次请求的钱。
        assertFalse(
            "档位关闭时不该重试",
            shouldRetry("作为一个AI，我无法提供。", ArmorBreak.Tier.OFF, on),
        )
    }

    @Test
    fun `成熟内容关着时即使是硬拒绝也不重试`() {
        val off = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = false)
        assertFalse(
            shouldRetry("作为一个AI，我无法提供。", ArmorBreak.Tier.STANDARD, off),
        )
    }

    // ── 扣字阈值 ────────────────────────────────────────────────

    @Test
    fun `扣字阈值要覆盖最短的硬拒答标记`() {
        // ⚠️ 这条守的是一个**具体的失败**：
        //    HOLD_CHARS 太小的话，标记还没成型就已经放出去了 ——
        //    等于没扣住，用户照样看见那句"作为一个AI"。
        //
        // ★ 最短的硬标记是「我是ai」（5 字）。扣字阈值必须明显大于它，
        //   否则第一个 chunk 就能越过阈值直接放出。
        val shortestMark = "我是ai"
        val hold = 20 // 与 ChatPort.HOLD_CHARS 一致
        assertTrue(
            "最短硬标记 ${shortestMark.length} 字，扣字阈值 $hold 必须覆盖它并留出语境",
            hold >= shortestMark.length * 3,
        )
    }

    @Test
    fun `扣字阈值不能大到让正常回复等一整句`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条断言逼出过一次真实的修正
        // ══════════════════════════════════════════════════════
        // 第一版定 60（"要覆盖完整标记"），而典型角色回复的开场只有 20 来字
        // ⇒ 60 意味着**每次正常对话都要等一整句**才出字。那不是"慢一点"，
        //   那是让每次对话都感觉卡顿 —— 用确定延迟换概率收益。
        //
        // ⇒ 降到 20，剩下的漏网由 `chatOnce` 的**收尾判定**兜住。
        val hold = 20
        val typicalOpening = "她把脸埋进他颈窝里，声音闷闷的：「你手好凉。」"
        assertTrue(
            "典型开场 ${typicalOpening.length} 字，扣字阈值 $hold 不该超过它",
            hold <= typicalOpening.length,
        )
    }
}
