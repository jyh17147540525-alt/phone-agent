package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **遗忘的规矩**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"两种都不像人"
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 完美记忆** —— 什么都记得。一眼假。
 * **② 橡皮擦式遗忘** —— 要么完整、要么消失。中间没有东西。
 *
 * 人真实的样子是**第三种**：记得，但记不清；而且**越疼的记得越牢**。
 */
class MemoryDecayTest {

    private fun hours(h: Double): Long = (h * 3_600_000).toLong()

    // ═══════════════════════════════════════════════════════════
    //  ★ 核心：情绪越强，忘得越慢
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `情绪强烈的记忆比平淡的活得久得多`() {
        // ══════════════════════════════════════════════════════
        //  ★ 本文件最重要的一条
        // ══════════════════════════════════════════════════════
        // 人记得住"被骂的那天"，却不记得"上周三中午吃了什么"。
        // 若这一条不成立，所谓"遗忘"就只是定时清理。
        val plain = MemoryDecay.born(confidence = 1.0, emotionalCharge = 0.0, atMs = 0)
        val vivid = MemoryDecay.born(confidence = 1.0, emotionalCharge = 1.0, atMs = 0)

        val plainAfter = MemoryDecay.decay(plain, hours(24.0 * 7))
        val vividAfter = MemoryDecay.decay(vivid, hours(24.0 * 7))

        assertTrue(
            "情绪强烈的那个应当明显更牢：${vividAfter.strength} vs ${plainAfter.strength}",
            vividAfter.strength > plainAfter.strength * 2,
        )
    }

    @Test
    fun `一只平淡的记忆一周后基本没了`() {
        val s = MemoryDecay.born(confidence = 1.0, emotionalCharge = 0.0, atMs = 0)
        val after = MemoryDecay.decay(s, hours(24.0 * 7))
        assertTrue("平淡事实应当已经进入模糊或淡忘：${after.strength}", after.strength < 0.4)
    }

    @Test
    fun `同一段记忆 情绪拉满时一周后仍然清楚`() {
        val s = MemoryDecay.born(confidence = 1.0, emotionalCharge = 1.0, atMs = 0)
        val after = MemoryDecay.decay(s, hours(24.0 * 7))
        assertTrue("强烈的记忆不该一周就糊掉：${after.strength}", after.strength > 0.5)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 三档确定性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `清楚时照实说`() {
        val s = Salience(strength = 0.9, emotionalCharge = 0.5, lastTouchedAt = 0)
        assertEquals(Fidelity.EXACT, s.fidelity())
        assertEquals("你妈在杭州住院", MemoryDecay.render(s.fidelity(), "你妈在杭州住院"))
    }

    @Test
    fun `模糊时必须带不确定措辞`() {
        // ══════════════════════════════════════════════════════
        //  ★ 中间那一档才是人的地方
        // ══════════════════════════════════════════════════════
        // 它同时做了两件事：承认可能记错，又没真的忘。
        val s = Salience(strength = 0.5, emotionalCharge = 0.2, lastTouchedAt = 0)
        assertEquals(Fidelity.HEDGED, s.fidelity())

        val said = MemoryDecay.render(s.fidelity(), "你妈在杭州住院")
        assertNotNull(said)
        assertTrue(
            "模糊的记忆必须带不确定性：$said",
            listOf("好像", "隐约记得", "我记得你提过", "之前是不是说过").any { said!!.contains(it) },
        )
        assertTrue("但不能丢内容：$said", said!!.contains("杭州"))
    }

    @Test
    fun `忘了就不该出现`() {
        val s = Salience(strength = 0.1, emotionalCharge = 0.0, lastTouchedAt = 0)
        assertEquals(Fidelity.GONE, s.fidelity())
        assertNull("忘了的东西不该被塞进提示词", MemoryDecay.render(Fidelity.GONE, "任何内容"))
    }

    @Test
    fun `模糊措辞不是每次都一样`() {
        // ⚠️ 每句都"好像"会让角色像在背台词
        val a = MemoryDecay.render(Fidelity.HEDGED, "短")
        val b = MemoryDecay.render(Fidelity.HEDGED, "稍微长一点的内容")
        val c = MemoryDecay.render(Fidelity.HEDGED, "再长一些的内容内容")
        val set = setOf(a, b, c)
        assertTrue("措辞应当有变化：$set", set.size > 1)
    }

    // ═══════════════════════════════════════════════════════════
    //  加固
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `被想起会加固`() {
        val s = Salience(strength = 0.4, emotionalCharge = 0.2, lastTouchedAt = 0)
        val after = MemoryDecay.reinforce(s, nowMs = 1000)
        assertTrue("应当变强：${after.strength}", after.strength > s.strength)
        assertEquals(1, after.recallCount)
    }

    @Test
    fun `加固量递减 不会无限冲顶`() {
        var s = Salience(strength = 0.9, emotionalCharge = 0.0, lastTouchedAt = 0)
        var last = s.strength
        repeat(10) {
            s = MemoryDecay.reinforce(s, nowMs = 1000)
            assertTrue("不能超过 1.0", s.strength <= 1.0)
            assertTrue("增益应当越来越小", s.strength - last <= 0.05 + 1e-9)
            last = s.strength
        }
    }

    @Test
    fun `加固时可以顺带加深情绪`() {
        val s = Salience(strength = 0.5, emotionalCharge = 0.1, lastTouchedAt = 0)
        val after = MemoryDecay.reinforce(s, nowMs = 1000, extraEmotion = 0.5)
        assertEquals(0.6, after.emotionalCharge, 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  防御
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `时间倒流不会让记忆消失`() {
        // 时钟被改不该造成"记忆凭空消失"
        val s = Salience(strength = 0.8, emotionalCharge = 0.3, lastTouchedAt = 10_000)
        assertEquals(s, MemoryDecay.decay(s, nowMs = 5_000))
    }

    @Test
    fun `强度永远落在 0 到 1`() {
        val s = MemoryDecay.born(confidence = 2.0, emotionalCharge = -1.0, atMs = 0)
        assertTrue(s.strength in 0.0..1.0)
        assertTrue(s.emotionalCharge in 0.0..1.0)
    }

    @Test
    fun `数值上真的会归零 而不是无限逼近`() {
        // ⚠️ 浮点衰减永远到不了 0。若不清零，"忘了"会变成"强度 0.0001 但仍算存在"。
        val s = MemoryDecay.born(confidence = 0.5, emotionalCharge = 0.0, atMs = 0)
        val after = MemoryDecay.decay(s, hours(24.0 * 400))
        assertEquals(0.0, after.strength, 1e-9)
        assertTrue(after.isForgotten)
    }
}
