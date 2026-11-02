package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **意愿的规矩** —— 拒绝要罕见、可恢复、不连续。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"做成坏掉的机器"
 * ═══════════════════════════════════════════════════════════════
 *
 * 意愿系统有两种做坏的典型：
 *
 * **① 拒绝太频繁** —— 用户第一次被冷落就卸载。
 * **② 拒绝之后不收口** —— 连着拒绝好几轮，看起来像坏了，不像有性格。
 *
 * 下面每一条都在钉这两件事之一。
 */
class WillingnessTest {

    private val engine = WillingnessEngine()
    private fun hours(h: Double): Long = (h * 3_600_000).toLong()

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 1：正常使用下**不会**被拒绝
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `刚装好时是愿意聊的`() {
        val s = Willingness(lastTouchedAt = 0)
        assertEquals(WillingnessVerdict.EAGER, engine.verdictOf(s, turnIndex = 0))
    }

    @Test
    fun `连着聊二十轮也不会被拒`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这是最要紧的一条 —— 拒绝必须罕见
        // ══════════════════════════════════════════════════════
        // 默认 70 点，每轮 -1（长会话 -2）。二十轮最多掉到 30 上下，
        // 而明确拒绝的门槛是 22。
        var s = Willingness(lastTouchedAt = 0)
        for (i in 0 until 20) {
            s = engine.spend(s, turnIndex = i, isLongSession = true)
        }
        assertTrue("二十轮后仍不该到拒绝线：${s.value}", s.value >= Willingness.DECLINE_BELOW)
        assertEquals(WillingnessVerdict.LOW, engine.verdictOf(s, turnIndex = 20))
    }

    @Test
    fun `要聊很久很久才会真的拒绝`() {
        var s = Willingness(lastTouchedAt = 0)
        var declined = false
        for (i in 0 until 60) {
            s = engine.spend(s, turnIndex = i, isLongSession = true)
            if (engine.verdictOf(s, i) == WillingnessVerdict.DECLINE) { declined = true; break }
        }
        assertTrue("聊到极限时应当会拒绝", declined)
    }

    @Test
    fun `高皮质醇会消耗得更快`() {
        val calm = engine.spend(Willingness(lastTouchedAt = 0), 0, cortisol = 40)
        val tense = engine.spend(Willingness(lastTouchedAt = 0), 0, cortisol = 80)
        assertTrue("绷着说话更累：${tense.value} vs ${calm.value}", tense.value < calm.value)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 2：拒绝之后不会连着拒
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `拒绝之后要隔一阵才会再拒`() {
        // ⚠️ 连续拒绝不是"有性格"，是"坏了"
        val low = Willingness(value = 10, lastTouchedAt = 0)
        // 第一次：可以拒
        assertEquals(WillingnessVerdict.DECLINE, engine.verdictOf(low, turnIndex = 100))

        // 拒绝后记一笔
        val after = engine.afterDecline(low, turnIndex = 100)

        // 紧接着的几轮：即使意愿仍低，也不该再拒
        for (t in 101..(100 + Willingness.DECLINE_COOLDOWN_TURNS - 1)) {
            val v = engine.verdictOf(after, turnIndex = t)
            assertTrue("冷却期内不该再拒（第 $t 轮）：$v", v != WillingnessVerdict.DECLINE)
        }
    }

    @Test
    fun `拒绝之后意愿会回升一点`() {
        // 把"不想聊"说出口之后会轻松些
        val low = Willingness(value = 20, lastTouchedAt = 0)
        val after = engine.afterDecline(low, turnIndex = 5)
        assertTrue("应当回升：${after.value}", after.value > low.value)
    }

    @Test
    fun `冷却结束之后仍然可以再拒`() {
        // 限频不是永久关闭
        val s = Willingness(value = 10, lastTouchedAt = 0, lastDeclineAtTurn = 0)
        val v = engine.verdictOf(s, turnIndex = Willingness.DECLINE_COOLDOWN_TURNS)
        assertEquals(WillingnessVerdict.DECLINE, v)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 3：时间是最好的恢复
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `睡一觉就恢复大半`() {
        val tired = Willingness(value = 10, lastTouchedAt = 0)
        val rested = engine.advance(tired, hours(8.0))
        assertTrue("8 小时应当恢复不少：${rested.value}", rested.value >= 45)
        assertEquals(WillingnessVerdict.EAGER, engine.verdictOf(rested, turnIndex = 0))
    }

    @Test
    fun `恢复有上限 不会超过一百`() {
        val s = Willingness(value = 95, lastTouchedAt = 0)
        assertEquals(100, engine.advance(s, hours(24.0)).value)
    }

    @Test
    fun `时间倒流不会让它更累`() {
        // 时钟被改不该让"意愿"凭空掉
        val s = Willingness(value = 60, lastTouchedAt = 10_000)
        assertEquals(60, engine.advance(s, nowMs = 5_000).value)
    }

    // ═══════════════════════════════════════════════════════════
    //  提示词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `愿意的时候不写任何提示词`() {
        assertNull(engine.promptSection(WillingnessVerdict.EAGER))
    }

    @Test
    fun `低落时写一段极短的提示`() {
        val s = engine.promptSection(WillingnessVerdict.LOW)
        assertNotNull(s)
        assertTrue("必须短 —— 长禁令会让模型变僵：${s!!.length}", s.length < 80)
    }
}

/**
 * **拒绝时说的话**。
 *
 * ⚠️ 这些是**本地文案**，不经过模型 —— 拒绝时省一次请求。
 *    所以措辞必须自己站得住。
 */
class DeclineLinesTest {

    @Test
    fun `拒绝时说的是自己的状态 不是指责对方`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这一条是伦理钉子
        // ══════════════════════════════════════════════════════
        // "你太烦了" 和 "我今天累了" 是完全不同的两件事。
        val banned = listOf("你烦", "别烦我", "你太", "都怪你", "你总是")
        for (cortisol in listOf(20, 50, 90)) {
            for (t in 0..4) {
                val line = DeclineLines.pick(cortisol, t)
                for (b in banned) {
                    assertTrue("拒绝不该指责用户「$b」：$line", !line.contains(b))
                }
            }
        }
    }

    @Test
    fun `拒绝要留门 不说绝话`() {
        val banned = listOf("再也不", "永远", "别找我", "滚")
        for (cortisol in listOf(20, 90)) {
            for (t in 0..4) {
                val line = DeclineLines.pick(cortisol, t)
                for (b in banned) {
                    assertTrue("不该说绝话「$b」：$line", !line.contains(b))
                }
            }
        }
    }

    @Test
    fun `拒绝文案不长`() {
        // 拖长了像在找借口
        for (t in 0..4) {
            val line = DeclineLines.pick(cortisol = 50, turnNumber = t)
            assertTrue("拒绝文案应当短：${line.length} 字", line.length <= 30)
        }
    }

    @Test
    fun `不同轮次会换一句话`() {
        // ⚠️ 每次都是同一句会像自动回复
        val set = (0..3).map { DeclineLines.pick(50, it) }.toSet()
        assertTrue("应当有变化：$set", set.size > 1)
    }

    @Test
    fun `压力大时更想独处`() {
        val tense = DeclineLines.pick(cortisol = 90, turnNumber = 0)
        val calm = DeclineLines.pick(cortisol = 20, turnNumber = 0)
        assertTrue("两种档位的措辞应当不同", tense != calm)
    }
}
