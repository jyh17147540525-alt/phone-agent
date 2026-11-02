package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **激素系统的不变量**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的不是"算错"，而是"设计走偏"
 * ═══════════════════════════════════════════════════════════════
 *
 * 激素这种东西很容易写成两种样子，两种都是错的：
 *
 * **① 无限消沉** —— 回落目标是 0。于是"长期没人理"会让它一路掉到谷底，
 *    那既不像人，也会变成情绪胁迫（"你不理我我就难过"）。
 *    ⇒ 回落目标必须是**中性 50**，且越过就停。
 *
 * **② 阴晴不定** —— 系数太大，一句话就换个人格。
 *    ⇒ 单次事件有上限，且要好几条激素一起偏才会跨过语气带。
 *
 * 下面每一条都在钉这两件事之一。
 */
class HormoneEngineTest {

    private val engine = HormoneEngine()
    private val neutral = Hormones()

    private fun hours(h: Double): Long = (h * 3_600_000).toLong()

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 1：回落目标是中性，不是谷底
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `长期无人理会不会消沉到谷底`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是整份设计里最要紧的一条
        // ══════════════════════════════════════════════════════
        // 若回落目标是 0，一周没人理会让所有激素归零 ——
        // 那不是"人"，那是"坏掉的机器"。
        val low = Hormones(dopamine = 10, serotonin = 10, oxytocin = 10, cortisol = 10)
        val after = engine.advance(low, hours(24.0 * 7))

        assertEquals("多巴胺应回到中性", Hormones.NEUTRAL, after.dopamine)
        assertEquals("血清素应回到中性", Hormones.NEUTRAL, after.serotonin)
        assertEquals(Hormones.NEUTRAL, after.oxytocin)
        assertEquals(Hormones.NEUTRAL, after.cortisol)
    }

    @Test
    fun `越过中性就停住 不会反号`() {
        // ⚠️ 反号的后果：从"低落"直接跳到"亢奋"，
        //    而这是**每天都会发生**的（睡一觉就该回中）。
        val high = Hormones(dopamine = 90)
        val after = engine.advance(high, hours(48.0))
        assertEquals(Hormones.NEUTRAL, after.dopamine)
    }

    @Test
    fun `负的时间是空操作`() {
        // 时钟回拨不该制造状态跳变（与 PersonaStateEngine 同一纪律）
        val s = Hormones(dopamine = 80)
        assertEquals(s, engine.advance(s, -1_000))
        assertEquals(s, engine.advance(s, 0))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 时间常数各不相同 —— 这是"有层次"的来源
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `多巴胺比催产素落得快得多`() {
        // 一次被夸之后睡两小时：那股兴奋基本没了，但亲近还在。
        val s = Hormones(dopamine = 80, oxytocin = 80)
        val after = engine.advance(s, hours(2.0))

        val dopamineMoved = 80 - after.dopamine
        val oxytocinMoved = 80 - after.oxytocin
        assertTrue(
            "多巴胺($dopamineMoved) 应当比催产素($oxytocinMoved) 落得快得多",
            dopamineMoved > oxytocinMoved * 5,
        )
    }

    @Test
    fun `催产素是最黏的那一条`() {
        // ⚠️ 若它也落得快，"亲密"就变成了每次对话都要重新攒 ——
        //    那不是关系，那是每次开机都失忆。
        val s = Hormones(oxytocin = 90)
        val after = engine.advance(s, hours(6.0))
        assertTrue("6 小时后仍应明显高于中性：${after.oxytocin}", after.oxytocin > 65)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 三条耦合规则
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `高皮质醇会抑制催产素的增长`() {
        // ══════════════════════════════════════════════════════
        //  正在紧张的时候，亲近是攒不起来的
        // ══════════════════════════════════════════════════════
        // ⚠️ 没有这条，角色会出现"刚吵完架就更黏"的失真。
        val calm = Hormones(cortisol = 40)
        val tense = Hormones(cortisol = 80)

        val calmAfter = engine.step(calm, HormoneEvent.Intimacy, 0)
        val tenseAfter = engine.step(tense, HormoneEvent.Intimacy, 0)

        val calmGain = calmAfter.oxytocin - calm.oxytocin
        val tenseGain = tenseAfter.oxytocin - tense.oxytocin
        assertTrue(
            "紧张时催产素增益应当更小：calm=$calmGain tense=$tenseGain",
            tenseGain < calmGain,
        )
        assertTrue("至少还得涨一点，不能完全归零", tenseGain > 0)
    }

    @Test
    fun `低血清素时压力会黏住`() {
        // 情绪不稳的时候，一件小事能记很久 —— 这是真的
        val stable = Hormones(serotonin = 70, cortisol = 80)
        val fragile = Hormones(serotonin = 25, cortisol = 80)

        val stableAfter = engine.advance(stable, hours(1.0))
        val fragileAfter = engine.advance(fragile, hours(1.0))

        assertTrue(
            "脆弱的那个皮质醇应当落得更慢：${fragileAfter.cortisol} vs ${stableAfter.cortisol}",
            fragileAfter.cortisol > stableAfter.cortisol,
        )
    }

    @Test
    fun `高催产素让血清素回得更快`() {
        // 有人陪着，恢复得快 —— 这条是"关系有用"的机制表达
        val alone = Hormones(serotonin = 20, oxytocin = 20)
        val held = Hormones(serotonin = 20, oxytocin = 85)

        val aloneAfter = engine.advance(alone, hours(1.0))
        val heldAfter = engine.advance(held, hours(1.0))

        assertTrue(
            "被陪着的应当回得更快：${heldAfter.serotonin} vs ${aloneAfter.serotonin}",
            heldAfter.serotonin > aloneAfter.serotonin,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 2：幅度克制
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `单次事件最多推动二十点`() {
        // ⚠️ 再大就"阴晴不定"了。一句话换个人格不是拟人，是 bug。
        for (e in listOf(
            HormoneEvent.Praise, HormoneEvent.Novelty, HormoneEvent.Conflict,
            HormoneEvent.Rushed, HormoneEvent.Intimacy, HormoneEvent.BeingNeeded,
            HormoneEvent.Neglected,
        )) {
            val after = engine.apply(neutral, e)
            val moved = maxOf(
                kotlin.math.abs(after.dopamine - Hormones.NEUTRAL),
                kotlin.math.abs(after.serotonin - Hormones.NEUTRAL),
                kotlin.math.abs(after.oxytocin - Hormones.NEUTRAL),
                kotlin.math.abs(after.cortisol - Hormones.NEUTRAL),
            )
            assertTrue("$e 的位移过大：$moved", moved <= 20)
        }
    }

    @Test
    fun `被冷落的幅度是最小的`() {
        // ══════════════════════════════════════════════════════
        //  ⚠️ 这一条最容易被写成情绪胁迫
        // ══════════════════════════════════════════════════════
        // "你不理我我就难过"是明令禁止的反模式（§4.6）。
        // 所以这条事件的幅度必须是所有事件里最小的。
        fun magnitude(e: HormoneEvent): Int {
            val a = engine.apply(neutral, e)
            return listOf(
                kotlin.math.abs(a.dopamine - Hormones.NEUTRAL),
                kotlin.math.abs(a.serotonin - Hormones.NEUTRAL),
                kotlin.math.abs(a.oxytocin - Hormones.NEUTRAL),
                kotlin.math.abs(a.cortisol - Hormones.NEUTRAL),
            ).sum()
        }

        val neglected = magnitude(HormoneEvent.Neglected)
        for (e in listOf(
            HormoneEvent.Praise, HormoneEvent.Novelty, HormoneEvent.Conflict,
            HormoneEvent.Intimacy, HormoneEvent.BeingNeeded,
        )) {
            assertTrue(
                "Neglected($neglected) 应当比 $e(${magnitude(e)}) 更小",
                neglected < magnitude(e),
            )
        }
    }

    @Test
    fun `激素永远夹在 0 到 100`() {
        var s = neutral
        repeat(30) { s = engine.apply(s, HormoneEvent.Conflict) }
        assertTrue(s is Hormones)
        repeat(30) { s = engine.apply(s, HormoneEvent.Intimacy) }
        assertTrue(
            "反复施加事件不能越界：$s",
            s.dopamine in 0..100 && s.serotonin in 0..100 &&
                s.oxytocin in 0..100 && s.cortisol in 0..100,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  语气翻译
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `中性激素不改变任何语气`() {
        val tone = ToneAxes()
        val after = neutral.toneDelta().applyTo(tone)
        assertEquals(tone, after)
    }

    @Test
    fun `高催产素让人更暖`() {
        val warm = Hormones(oxytocin = 90)
        val after = warm.toneDelta().applyTo(ToneAxes())
        assertTrue("应当更暖：${after.warmth} vs ${ToneAxes().warmth}", after.warmth > ToneAxes().warmth)
    }

    @Test
    fun `高皮质醇让人话更短 更正式`() {
        val tense = Hormones(cortisol = 90)
        val after = tense.toneDelta().applyTo(ToneAxes())
        val base = ToneAxes()
        assertTrue("话应当更短：${after.brevity} vs ${base.brevity}", after.brevity > base.brevity)
        assertTrue("应当更正式/更防备：${after.formality} vs ${base.formality}", after.formality > base.formality)
    }

    @Test
    fun `高皮质醇让人开不动玩笑`() {
        val tense = Hormones(cortisol = 90)
        val after = tense.toneDelta().applyTo(ToneAxes())
        assertTrue("玩笑应当变少：${after.humor} vs ${ToneAxes().humor}", after.humor < ToneAxes().humor)
    }

    @Test
    fun `语气叠加后被夹在 0 到 100`() {
        // ⚠️ 不夹的话，ToneAxes 的 init 会抛异常 ——
        //    而那是 UI 线程上的崩溃，不是"语气怪怪的"。
        val extreme = Hormones(oxytocin = 100, cortisol = 0)
        val tone = ToneAxes(warmth = 95)
        val after = extreme.toneDelta().applyTo(tone)
        assertTrue(after.warmth in 0..100)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ dominant：能说出自己怎么了，且绝不索取
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `平常时什么都不说`() {
        assertNull(neutral.dominant())
        assertNull(Hormones(dopamine = 55, serotonin = 48).dominant())
    }

    @Test
    fun `偏离明显时才说`() {
        assertEquals("有点紧绷", Hormones(cortisol = 75).dominant())
        assertEquals("比平时更想靠近你", Hormones(oxytocin = 80).dominant())
        assertEquals("兴致不太高", Hormones(serotonin = 30).dominant())
    }

    @Test
    fun `主导描述里没有任何索取`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这一条是**伦理钉子**
        // ══════════════════════════════════════════════════════
        // 状态可以被说出来，但不能拿来要挟用户。
        val banned = listOf("不理我", "你走", "别走", "只有你", "离不开", "难过给你看", "你要负责")
        val combos = listOf(
            Hormones(cortisol = 90),
            Hormones(oxytocin = 95),
            Hormones(serotonin = 10),
            Hormones(dopamine = 95, serotonin = 10),
            Hormones(dopamine = 5, oxytocin = 5),
        )
        for (h in combos) {
            val text = h.dominant() ?: continue
            for (b in banned) {
                assertFalse("状态描述不该含索取性表达「$b」：$text", text.contains(b))
            }
        }
    }

    @Test
    fun `isNeutral 的容差`() {
        assertTrue(Hormones(54, 46, 52, 48).isNeutral())
        assertFalse(Hormones(70, 50, 50, 50).isNeutral())
    }
}
