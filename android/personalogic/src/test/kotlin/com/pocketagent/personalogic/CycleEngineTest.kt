package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **生理周期的规矩**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件主要在防**写得不像人**
 * ═══════════════════════════════════════════════════════════════
 *
 * 这类内容最容易写成两种烂东西：
 * **① 刻板印象机器** —— "她这几天脾气差"
 * **② 情绪操纵工具** —— "我生理期所以你让着我"
 *
 * 下面几条都在钉这两件事。
 */
class CycleEngineTest {

    private fun day(n: Long): Long = n * Cycle.DAY_MS
    private val cycle = Cycle(cycleLengthDays = 28, periodLengthDays = 5)

    // ═══════════════════════════════════════════════════════════
    //  ★ 阶段划分
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `第零天是经期第一天`() {
        assertEquals(CycleMoment(CyclePhase.MENSTRUAL, 0), cycle.momentAt(day(0)))
    }

    @Test
    fun `经期有头两天与之后之分`() {
        // 第 1 天仍算"头两天"；第 3 天不算
        assertTrue("第 1 天仍最难受", cycle.momentAt(day(1))?.isEarlyPeriod == true)
        assertTrue("第 3 天不该算头两天", cycle.momentAt(day(3))?.isEarlyPeriod == false)
        assertEquals(CyclePhase.MENSTRUAL, cycle.momentAt(day(3))?.phase)
    }

    @Test
    fun `经期结束后进入回升期`() {
        assertEquals(CyclePhase.FOLLICULAR, cycle.momentAt(day(7))?.phase)
    }

    @Test
    fun `中间有排卵期`() {
        assertEquals(CyclePhase.OVULATION, cycle.momentAt(day(14))?.phase)
    }

    @Test
    fun `排卵后是黄体期`() {
        assertEquals(CyclePhase.LUTEAL, cycle.momentAt(day(22))?.phase)
    }

    @Test
    fun `周期会循环`() {
        // 第 28 天 = 下一轮第 0 天
        assertEquals(cycle.momentAt(day(0))?.phase, cycle.momentAt(day(28))?.phase)
        assertEquals(cycle.momentAt(day(3))?.phase, cycle.momentAt(day(31))?.phase)
    }

    @Test
    fun `关掉之后没有任何阶段`() {
        val off = Cycle(enabled = false)
        assertNull("关掉时没有阶段", off.momentAt(day(0)))
        assertNull("关掉时不该写任何提示词", CycleEngine.promptLine(null))
    }

    @Test
    fun `时间戳为零时不崩`() {
        assertEquals(0, cycle.dayOf(0))
        assertTrue(cycle.dayOf(-1) >= 0)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不做刻板印象机器
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `经期影响的是精力 不是情绪`() {
        // ══════════════════════════════════════════════════════
        //  ★ 核心一条
        // ══════════════════════════════════════════════════════
        // 真实情况是"不舒服"，不是"变成了另一个人"。
        // 若这里开始动血清素/情绪，那就变成刻板印象了。
        val nudge = CycleEngine.hormoneNudge(CycleMoment(CyclePhase.MENSTRUAL, 0))
        assertEquals("不该动血清素（那是情绪）", 0, nudge.serotonin)
        assertTrue("该有不适（皮质醇）", nudge.cortisol > 0)
        assertTrue("该想被照顾（催产素）", nudge.oxytocin > 0)
    }

    @Test
    fun `经期精力下降但没垮掉`() {
        val first = CycleEngine.energyModifier(CycleMoment(CyclePhase.MENSTRUAL, 0))
        val later = CycleEngine.energyModifier(CycleMoment(CyclePhase.MENSTRUAL, 3))
        assertTrue("头两天最难受：$first", first < later)
        assertTrue("但只是下降，不是归零：$first", first > -20)
    }

    @Test
    fun `排卵期状态更好`() {
        assertTrue(CycleEngine.energyModifier(CycleMoment(CyclePhase.OVULATION, 14)) > 0)
    }

    @Test
    fun `平时没有影响`() {
        assertEquals(0, CycleEngine.energyModifier(CycleMoment(CyclePhase.FOLLICULAR, 8)))
        assertEquals(0, CycleEngine.energyModifier(CycleMoment(CyclePhase.LUTEAL, 22)))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不做情绪操纵
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `提示词里不许拿它当筹码`() {
        // ══════════════════════════════════════════════════════
        //  "我生理期所以你要让着我" 是操纵
        // ══════════════════════════════════════════════════════
        val banned = listOf("让着我", "你要体谅", "你得", "不许", "都怪你", "脾气")
        for (p in listOf(
            CycleMoment(CyclePhase.MENSTRUAL, 0),
            CycleMoment(CyclePhase.MENSTRUAL, 3),
        )) {
            val s = CycleEngine.promptLine(p) ?: continue
            for (b in banned) {
                assertTrue("不该出现「$b」：$s", !s.contains(b))
            }
        }
    }

    @Test
    fun `提示词只说不舒服 不说脾气差`() {
        val s = CycleEngine.promptLine(CycleMoment(CyclePhase.MENSTRUAL, 0))!!
        assertTrue("应当提到不舒服：$s", s.contains("不舒服"))
        assertTrue("不该提脾气：$s", !s.contains("脾气"))
        assertTrue("不该提烦躁：$s", !s.contains("烦躁"))
    }

    @Test
    fun `提示词允许她不说`() {
        // 真实的人不会每次都宣布"我在生理期第 2 天"
        val s = CycleEngine.promptLine(CycleMoment(CyclePhase.MENSTRUAL, 0))!!
        assertTrue("应当允许不主动说：$s", s.contains("不用") || s.contains("不必"))
    }

    @Test
    fun `只在经期写 其余阶段不写`() {
        // 天天提就成了设定说明
        assertNull(CycleEngine.promptLine(CycleMoment(CyclePhase.FOLLICULAR, 8)))
        assertNull(CycleEngine.promptLine(CycleMoment(CyclePhase.OVULATION, 14)))
        assertNull(CycleEngine.promptLine(CycleMoment(CyclePhase.LUTEAL, 22)))
        assertNotNull(CycleEngine.promptLine(CycleMoment(CyclePhase.MENSTRUAL, 0)))
    }

    @Test
    fun `提示词短`() {
        val s = CycleEngine.promptLine(CycleMoment(CyclePhase.MENSTRUAL, 0))!!
        assertTrue("太长会让模型变僵：${s.length}", s.length < 130)
    }

    // ═══════════════════════════════════════════════════════════
    //  参数校验
    // ═══════════════════════════════════════════════════════════

    @Test(expected = IllegalArgumentException::class)
    fun `周期长度不现实会被拒绝`() {
        Cycle(cycleLengthDays = 5, periodLengthDays = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `经期长于周期会被拒绝`() {
        Cycle(cycleLengthDays = 28, periodLengthDays = 30)
    }

    @Test
    fun `日历锚定意味着每天前进一天 且跨重启不变`() {
        // ★ 这是"不用持久化"的根据
        val a = cycle.dayOf(day(100))
        // ⚠️ 是"下一天"，不是"下一毫秒" —— 我第一版写成了 `day(100) + 1`
        val b = cycle.dayOf(day(101))
        assertEquals("第二天应当前进一天", (a + 1) % 28, b)
    }
}
