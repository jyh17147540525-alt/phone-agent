package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeformPulseTest {

    private fun facts(
        hourOfDay: Double = 15.0,
        idleHours: Long = 4,
        sentToday: Int = 0,
        seed: Long = 0L,
        hasWeb: Boolean = false,
        hasBeatNow: Boolean = false,
        hasBeatEarlier: Boolean = false,
        lastReason: LifeformPulse.Reason? = null,
    ) = LifeformPulse.Facts(
        hourOfDay = hourOfDay,
        idleHours = idleHours,
        sentToday = sentToday,
        seed = seed,
        hasWeb = hasWeb,
        hasBeatNow = hasBeatNow,
        hasBeatEarlier = hasBeatEarlier,
        lastReason = lastReason,
    )

    // ── 该不该发 ────────────────────────────────────────────────────────

    @Test
    fun `今天已经发满两条就不再发`() {
        assertNull(LifeformPulse.plan(facts(hasWeb = true, sentToday = LifeformPulse.MAX_PER_DAY)))
    }

    @Test
    fun `还没发满就还能发`() {
        assertNotNull(LifeformPulse.plan(facts(hasWeb = true, sentToday = 1)))
    }

    @Test
    fun `没有任何由头也没有久到能说想他时什么都不发`() {
        assertNull(LifeformPulse.plan(facts(idleHours = 3)))
    }

    @Test
    fun `久到能说想他时这句话本身成为由头`() {
        val plan = LifeformPulse.plan(facts(idleHours = LifeformPulse.MIN_IDLE_HOURS_FOR_MISSING))
        assertEquals(LifeformPulse.Reason.MISS, plan?.reason)
    }

    // ── 由头怎么选 ──────────────────────────────────────────────────────

    @Test
    fun `只有网上见闻时就讲网上见闻`() {
        val plan = LifeformPulse.plan(facts(hasWeb = true))
        assertEquals(LifeformPulse.Reason.WEB, plan?.reason)
    }

    @Test
    fun `差一小时选中的由头就不一样`() {
        val all = facts(hourOfDay = 9.0, hasWeb = true, hasBeatNow = true, hasBeatEarlier = true, idleHours = 9)
        val at9 = LifeformPulse.plan(all)
        val at10 = LifeformPulse.plan(all.copy(hourOfDay = 10.0))
        assertNotEquals(at9?.reason, at10?.reason)
    }

    @Test
    fun `不会连着两次用同一个由头`() {
        val two = facts(hourOfDay = 9.0, hasWeb = true, hasBeatNow = true)
        val first = LifeformPulse.plan(two)
        assertNotNull(first)
        val again = LifeformPulse.plan(two.copy(lastReason = first!!.reason))
        assertNotEquals(first.reason, again?.reason)
    }

    @Test
    fun `只有一个由头时重复也得用它`() {
        val only = facts(hasWeb = true, lastReason = LifeformPulse.Reason.WEB)
        assertEquals(LifeformPulse.Reason.WEB, LifeformPulse.plan(only)?.reason)
    }

    @Test
    fun `同一份事实两次算出来一样`() {
        val f = facts(seed = 42L, hasWeb = true, hasBeatNow = true, hasBeatEarlier = true, idleHours = 8)
        assertEquals(LifeformPulse.plan(f), LifeformPulse.plan(f))
    }

    // ── 那一段提示词 ────────────────────────────────────────────────────

    @Test
    fun `提示词带上了现在几点`() {
        val text = LifeformPulse.promptText(LifeformPulse.Reason.WEB, 9.5)
        assertTrue(text, text.contains("现在是 ${LifeformDay.clockOf(9.5)}"))
        assertTrue(text.contains("9 点半"))
    }

    @Test
    fun `提示词说出了这一句的由头`() {
        val text = LifeformPulse.promptText(LifeformPulse.Reason.EARLIER, 15.0)
        assertTrue(text, text.contains(LifeformPulse.Reason.EARLIER.what))
    }

    @Test
    fun `想他那一句不假装有由头`() {
        val text = LifeformPulse.promptText(LifeformPulse.Reason.MISS, 22.0)
        assertTrue(text, text.contains("这一句没什么由头"))
        assertTrue(text, !text.contains("这一句的由头："))
    }

    @Test
    fun `四条要求一条都不少`() {
        val text = LifeformPulse.promptText(LifeformPulse.Reason.NOW, 11.0)
        assertTrue(text, text.contains("一到两句就够"))
        assertTrue(text, text.contains("不解释你为什么突然发消息"))
        assertTrue(text, text.contains("也不催"))
        assertTrue(text, text.contains("只输出这句话本身"))
    }

    @Test
    fun `提示词不催也不讨债`() {
        val text = LifeformPulse.promptText(LifeformPulse.Reason.MISS, 21.0)
        assertTrue(text, !text.contains("怎么不回"))
        assertTrue(text, !text.contains("你是不是不想理我"))
    }

    @Test
    fun `同一个种子不同时刻能选出不同由头`() {
        // ⚠️ 变异验证抓出来的空洞：原来只查"同一天两次相同输入结果相同"，
        //    那把 `hourOfDay` 从 key 里去掉仍然全绿。
        //    真实后果是**她一天到晚都拿同一个由头开话**（同一条网上见闻、
        //    同一件手上的事），比不发还假。
        val seen = HashSet<LifeformPulse.Reason>()
        for (h in 0 until 24) {
            val f = facts(hourOfDay = h.toDouble(), hasBeatNow = true, hasBeatEarlier = true, seed = 3L)
            LifeformPulse.plan(f)?.let { seen += it.reason }
        }
        assertTrue("一天 24 个时刻只选出一种由头：$seen", seen.size >= 2)
    }

    @Test
    fun `四个由头的提示词都过体检`() {
        for (reason in LifeformPulse.Reason.entries) {
            val report = PromptVoice.audit(LifeformPulse.promptText(reason, 14.0))
            assertEquals("$reason 有响的：${report.breakdown()}", 0, report.loud)
            assertEquals("$reason 有腔调：${report.breakdown()}", 0, report.tone)
        }
    }

    @Test
    fun `每个由头都说得清是什么`() {
        for (reason in LifeformPulse.Reason.entries) {
            assertTrue(reason.what.isNotBlank())
            assertTrue(reason.what, reason.what.length <= 20)
        }
    }

    @Test
    fun `计划里的提示词和单独拼的一致`() {
        val f = facts(hourOfDay = 19.25, hasWeb = true, seed = 7L)
        val plan = LifeformPulse.plan(f)!!
        assertEquals(LifeformPulse.promptText(plan.reason, f.hourOfDay), plan.promptText)
    }
}
