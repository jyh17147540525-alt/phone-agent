package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **生理系统的规矩** —— 它会累，而且是因为真的到了那个点。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"作息像个开关"
 * ═══════════════════════════════════════════════════════════════
 *
 * 节律做坏有兩種：
 * **① 平的** —— 什么时候都一样，等于没做。
 * **② 断的** —— 22:59 精神、23:01 昏倒。那像个开关，不像人。
 */
class PhysiologyEngineTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ 昼夜节律
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `凌晨四点最困`() {
        val deep = PhysiologyEngine.alertness(4.0)
        for (h in listOf(0.0, 2.0, 6.0, 8.0, 10.0, 14.5, 17.0, 21.0, 23.0)) {
            assertTrue("4 点($deep) 应当比 $h 点(${PhysiologyEngine.alertness(h)}) 更低", deep < PhysiologyEngine.alertness(h))
        }
    }

    @Test
    fun `上午十点最清醒`() {
        val peak = PhysiologyEngine.alertness(10.0)
        assertTrue("10 点应当接近 1：$peak", peak > 0.9)
        for (h in listOf(4.0, 8.0, 14.5, 19.0, 23.0)) {
            assertTrue("$h 点应当低于 10 点", PhysiologyEngine.alertness(h) < peak)
        }
    }

    @Test
    fun `午后有低谷`() {
        // 饭后困是普遍现象，不是编的
        val postLunch = PhysiologyEngine.alertness(14.5)
        assertTrue("午后应当明显低于上午", postLunch < PhysiologyEngine.alertness(10.0))
        assertTrue("也应当低于傍晚", postLunch < PhysiologyEngine.alertness(17.0))
    }

    @Test
    fun `曲线是连续的 不会断`() {
        // ══════════════════════════════════════════════════════
        //  ★ 防"23:59 精神、00:01 昏倒"
        // ══════════════════════════════════════════════════════
        var prev = PhysiologyEngine.alertness(0.0)
        var h = 0.0
        while (h < 24.0) {
            val cur = PhysiologyEngine.alertness(h)
            assertTrue(
                "在 $h 点出现了断崖：$prev → $cur",
                kotlin.math.abs(cur - prev) < 0.12,
            )
            prev = cur
            h += 0.25
        }
    }

    @Test
    fun `跨零点连续`() {
        val before = PhysiologyEngine.alertness(23.9)
        val after = PhysiologyEngine.alertness(0.1)
        assertTrue("跨零点不该断崖：$before → $after", kotlin.math.abs(after - before) < 0.12)
    }

    @Test
    fun `越界小时会被折回 24 小时内`() {
        assertEquals(PhysiologyEngine.alertness(10.0), PhysiologyEngine.alertness(34.0), 1e-9)
        assertEquals(PhysiologyEngine.alertness(10.0), PhysiologyEngine.alertness(-14.0), 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 睡眠债
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `深夜硬聊会累积睡眠债`() {
        var b = Physiology()
        repeat(4) { b = PhysiologyEngine.recordTurn(b, hourOfDay = 2.0, nowMs = it * 60_000L) }
        assertEquals(1.0, b.sleepDebtHours, 1e-9)
    }

    @Test
    fun `白天聊天不累积睡眠债`() {
        var b = Physiology()
        repeat(8) { b = PhysiologyEngine.recordTurn(b, hourOfDay = 15.0, nowMs = it * 60_000L) }
        assertEquals(0.0, b.sleepDebtHours, 1e-9)
    }

    @Test
    fun `睡够会恢复`() {
        val tired = Physiology(sleepDebtHours = 6.0, lastActiveAtMs = 0)
        val rested = PhysiologyEngine.advance(tired, elapsedMs = 8 * 3_600_000L)
        assertEquals("8 小时应当恢复 4 小时债", 2.0, rested.sleepDebtHours, 1e-9)
    }

    @Test
    fun `恢复有下限 不会变成负债`() {
        val b = Physiology(sleepDebtHours = 1.0, lastActiveAtMs = 0)
        assertEquals(0.0, PhysiologyEngine.advance(b, 24 * 3_600_000L).sleepDebtHours, 1e-9)
    }

    @Test
    fun `睡眠债有上限`() {
        var b = Physiology()
        repeat(200) { b = PhysiologyEngine.recordTurn(b, hourOfDay = 3.0, nowMs = it * 1_000L) }
        assertTrue("不该无限累积：${b.sleepDebtHours}", b.sleepDebtHours <= Physiology.MAX_DEBT)
    }

    @Test
    fun `时间倒流不制造睡眠债`() {
        val b = Physiology(sleepDebtHours = 2.0, lastActiveAtMs = 10_000)
        assertEquals(2.0, PhysiologyEngine.advance(b, elapsedMs = -5_000).sleepDebtHours, 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  对精力的修正
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `凌晨的精力修正明显为负`() {
        val at4 = PhysiologyEngine.energyModifier(Physiology(), hourOfDay = 4.0)
        assertTrue("凌晨四点应当明显更差：$at4", at4 < -10)
    }

    @Test
    fun `上午的精力修正为正`() {
        val at10 = PhysiologyEngine.energyModifier(Physiology(), hourOfDay = 10.0)
        assertTrue("上午十点应当更好：$at10", at10 > 10)
    }

    @Test
    fun `欠睡会压掉清醒时段的状态`() {
        val fresh = PhysiologyEngine.energyModifier(Physiology(), hourOfDay = 10.0)
        val tired = PhysiologyEngine.energyModifier(
            Physiology(sleepDebtHours = 5.0, lastActiveAtMs = 0),
            hourOfDay = 10.0,
        )
        assertTrue("欠睡时上午也不该精神：$tired vs $fresh", tired < fresh)
    }

    // ═══════════════════════════════════════════════════════════
    //  提示词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常的下午不写任何东西`() {
        assertNull(
            PhysiologyEngine.promptLine(Physiology(), hourOfDay = 16.0, nowMs = 0),
        )
    }

    @Test
    fun `深夜会写一条`() {
        val s = PhysiologyEngine.promptLine(Physiology(), hourOfDay = 2.0, nowMs = 0)
        assertNotNull(s)
        assertTrue("应当提到凌晨：$s", s!!.contains("凌晨"))
    }

    @Test
    fun `提示词里不含索取`() {
        // ══════════════════════════════════════════════════════
        //  ★ 关心与勒索只差一句话
        // ══════════════════════════════════════════════════════
        val banned = listOf("是不是不在乎", "你都不", "都不理", "你就不能", "你总是")
        for (h in listOf(2.0, 7.0, 14.5, 16.0)) {
            for (debt in listOf(0.0, 5.0)) {
                val s = PhysiologyEngine.promptLine(
                    Physiology(sleepDebtHours = debt, lastActiveAtMs = 0),
                    hourOfDay = h,
                    nowMs = 0,
                ) ?: continue
                for (b in banned) {
                    assertTrue("提示词不该含索取「$b」：$s", !s.contains(b))
                }
            }
        }
    }

    @Test
    fun `提示词都短`() {
        for (h in listOf(2.0, 7.0, 14.5)) {
            val s = PhysiologyEngine.promptLine(Physiology(), hourOfDay = h, nowMs = 0) ?: continue
            assertTrue("太长会让模型变僵：${s.length}", s.length < 90)
        }
    }

    @Test
    fun `连着坐太久会写一条`() {
        val now = 5 * 3_600_000L
        // ⚠️ `sessionStartAtMs = 0L` 是"没有进行中的会话"的哨兵 ——
        //    必须给一个真实时刻，`sittingHours` 才会算出来。
        val b = Physiology(sleepDebtHours = 0.0, lastActiveAtMs = now, sessionStartAtMs = 1L)
        val s = PhysiologyEngine.promptLine(b, hourOfDay = 16.0, nowMs = now)
        assertNotNull("连着 5 小时应当有感觉", s)
        assertTrue(s!!.contains("小时"))
    }
}
