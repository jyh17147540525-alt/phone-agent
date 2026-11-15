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
    fun `多巴胺比催产素落得快`() {
        // 一次被夸之后过一小时：那股兴奋基本没了，亲近还留一点点。
        //
        // ⚠️ 2026-11-14 换了参数之后这条的**量级变了**：旧版是 2h vs 24h，
        //    要求"两小时后多巴胺掉的是催产素的 5 倍以上"。现在按文献是
        //    30min vs 45min —— 差别只在**一小时以内**成立，超过一小时
        //    两条都在中性附近，硬要 5 倍差就是在测参数而不是测行为。
        val s = Hormones(dopamine = 80, oxytocin = 80)
        val after = engine.advance(s, hours(1.0))

        assertTrue(
            "一小时后催产素(${after.oxytocin}) 应仍高于多巴胺(${after.dopamine})",
            after.oxytocin > after.dopamine,
        )
        // 两条都该明显往中性走（不是"还停在高位"）
        assertTrue("多巴胺应已落到 60 以下：${after.dopamine}", after.dopamine < 60)
        assertTrue("催产素应已落到 65 以下：${after.oxytocin}", after.oxytocin < 65)
    }

    @Test
    fun `催产素比多巴胺黏，但也就半小时级`() {
        // ⚠️ 这条测试的**含义在 2026-11-14 被改掉了**，值得记一笔：
        //    旧版叫「催产素是最黏的那一条」，断言"6 小时后亲密还 >65"。
        //    那是 24 小时半衰期的产物。但人类血浆催产素 t½ 只有 4.5 分钟，
        //    受体后的行为余韵也只有 30–60 分钟 —— 一次拥抱影响一整天是错的。
        //    ⇒ 现在断言的是"半小时窗口里它留得更多"，而不是"它黏一整天"。
        val dopamineAfter = engine.advance(Hormones(dopamine = 90), hours(0.5)).dopamine
        val oxytocinAfter = engine.advance(Hormones(oxytocin = 90), hours(0.5)).oxytocin
        assertTrue(
            "半小时后催产素($oxytocinAfter) 应比多巴胺($dopamineAfter) 留得多",
            oxytocinAfter > dopamineAfter,
        )
        // 六小时后必须回到中性附近 —— 差别不该拖到第二天
        assertTrue(
            "6 小时后催产素应回到 52 以内：${engine.advance(Hormones(oxytocin = 90), hours(6.0)).oxytocin}",
            engine.advance(Hormones(oxytocin = 90), hours(6.0)).oxytocin <= 52,
        )
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

    // ═══════════════════════════════════════════════════════════
    //  ★ 2026-11-14：指数衰减
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `半衰期就是半衰期`() {
        // 表里的数字就是配置：走掉一个半衰期，偏差正好剩一半。
        // ⚠️ 这里**不再写死 2h / 8h / 24h / 4h** —— 那是 2026-11-14 换掉的旧值。
        //    写死就意味着"下次按文献再校准一次，这条测试必红"，
        //    而红的原因不是行为错了，只是数字改了。
        val p = HormonePolicy.Default
        assertEquals(70, engine.advance(Hormones(dopamine = 90), hours(p.dopamineHalfLifeHours)).dopamine)
        assertEquals(70, engine.advance(Hormones(serotonin = 90), hours(p.serotoninHalfLifeHours)).serotonin)
        assertEquals(70, engine.advance(Hormones(oxytocin = 90), hours(p.oxytocinHalfLifeHours)).oxytocin)

        // 皮质醇有 HPA 负反馈（规则 8），高位时半衰期会缩短。
        // 想量"纯半衰期"就得把负反馈关掉 —— 那是这条测试唯一需要的对照。
        val plain = HormoneEngine(
            HormonePolicy(
                dopamineHalfLifeHours = p.dopamineHalfLifeHours,
                serotoninHalfLifeHours = p.serotoninHalfLifeHours,
                oxytocinHalfLifeHours = p.oxytocinHalfLifeHours,
                cortisolHalfLifeHours = p.cortisolHalfLifeHours,
                cortisolBlocksOxytocinAt = p.cortisolBlocksOxytocinAt,
                hpaNegativeFeedbackGain = 0.0,
            ),
        )
        assertEquals(70, plain.advance(Hormones(cortisol = 90), hours(p.cortisolHalfLifeHours)).cortisol)
    }

    @Test
    fun `皮质醇越高 回落越快`() {
        // ★ 规则 8（2026-11-14）：HPA 负反馈。
        // 直觉是"越高越难下来"，但实测正相反 —— TSST 之后 60–90 分钟必回基线。
        // ⚠️ 钉的是**走掉的比例**，不是绝对值：起点不同，绝对值不能直接比
        //    （95 落十分钟后的绝对值仍然可能高于 60 落十分钟，那是起点造成的）。
        val elapsed = hours(HormonePolicy.Default.cortisolHalfLifeHours)
        val fromHigh = engine.advance(Hormones(cortisol = 95), elapsed).cortisol
        val fromLow = engine.advance(Hormones(cortisol = 60), elapsed).cortisol

        val highFraction = (95.0 - fromHigh) / 45.0
        val lowFraction = (60.0 - fromLow) / 10.0
        assertTrue(
            "高位走掉 $highFraction，低位走掉 $lowFraction —— 高位应更多",
            highFraction > lowFraction,
        )
        // 95 分出发，一个半衰期后不该还停在 80 以上（那是"没有负反馈"的样子）
        assertTrue("一个半衰期后应低于 80：$fromHigh", fromHigh < 80)
    }

    @Test
    fun `越接近中性走得越慢`() {
        // 线性的错觉：每小时掉一样多。真实是"越靠近平时的自己，回得越慢"。
        val fromHigh = 90 - engine.advance(Hormones(dopamine = 90), hours(0.5)).dopamine
        val fromLow = 60 - engine.advance(Hormones(dopamine = 60), hours(0.5)).dopamine
        assertTrue("高位($fromHigh) 应当比低位($fromLow) 掉得多", fromHigh > fromLow)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 2026-11-14：三条新互锁（规则 4 / 5 / 6）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `紧张时好事也乐不起来`() {
        // 规则 4：高皮质醇 ⇒ 多巴胺正向增益减半。
        // 没有它，角色会出现"刚被凶完，一句夸奖就蹦起来"的失真。
        val calm = engine.step(Hormones(cortisol = 40), HormoneEvent.Praise, 0)
        val tense = engine.step(Hormones(cortisol = 80), HormoneEvent.Praise, 0)
        assertTrue(
            "绷着时多巴胺只该涨一半：${tense.dopamine} vs ${calm.dopamine}",
            tense.dopamine < calm.dopamine,
        )
        assertEquals("平常 +12", 62, calm.dopamine)
        assertEquals("绷着 +6", 56, tense.dopamine)
    }

    @Test
    fun `低血清素时同一件事扎得更深`() {
        // 规则 5：血清素低 + 负面事件 ⇒ 皮质醇增益 ×1.5。
        val steady = engine.step(Hormones(serotonin = 55), HormoneEvent.Conflict, 0)
        val fragile = engine.step(Hormones(serotonin = 30), HormoneEvent.Conflict, 0)
        assertEquals("平常一次争执 +18", 18, steady.cortisol - Hormones.NEUTRAL)
        assertEquals("脆弱时 +27", 27, fragile.cortisol - Hormones.NEUTRAL)
    }

    @Test
    fun `兴奋时对压力钝一点`() {
        // 规则 6：高多巴胺 + 负面事件 ⇒ 皮质醇增益 ×0.7。
        val plain = engine.step(Hormones(), HormoneEvent.Conflict, 0)
        val elated = engine.step(Hormones(dopamine = 80), HormoneEvent.Conflict, 0)
        assertTrue(
            "正高兴时压力涨得少：${elated.cortisol} vs ${plain.cortisol}",
            elated.cortisol < plain.cortisol,
        )
    }

    @Test
    fun `又亢奋又不稳时按脆弱算`() {
        // 规则 5 与 6 可能同时成立（低血清素 + 高多巴胺 = 亢奋但不稳）。
        // 约定 **5 优先** —— "被戳穿"比"被安慰"更常见，也更符合那种状态。
        val both = engine.step(Hormones(dopamine = 80, serotonin = 30), HormoneEvent.Conflict, 0)
        val plain = engine.step(Hormones(), HormoneEvent.Conflict, 0)
        assertTrue(
            "应当落在「更疼」那一边：${both.cortisol} vs ${plain.cortisol}",
            both.cortisol > plain.cortisol,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 2026-11-14：饱和（规则 7）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `高位时同样的好事推动得更少`() {
        // 享乐适应的无状态近似：基线越高，同一份刺激的主观增量越小。
        val mid = engine.step(Hormones(dopamine = 50), HormoneEvent.Novelty, 0).dopamine - 50
        val high = engine.step(Hormones(dopamine = 70), HormoneEvent.Novelty, 0).dopamine - 70
        assertEquals("中位不打折", 15, mid)
        assertTrue("高位只该涨 $high（比 $mid 少）", high in 1 until mid)
    }

    @Test
    fun `往下掉不打折`() {
        // ⚠️ 饱和**只削正向**。往下掉的时候不该打折 ——
        //    否则"乐极生悲"会变成"乐极了所以没那么惨"，正好是反的。
        val mid = 50 - engine.step(Hormones(dopamine = 50), HormoneEvent.Rushed, 0).dopamine
        val high = 90 - engine.step(Hormones(dopamine = 90), HormoneEvent.Rushed, 0).dopamine
        assertEquals("掉多少与起点无关", mid, high)
    }

    @Test
    fun `压力也有天花板`() {
        // 饱和对**每条**激素各自成立：皮质醇已经在 70 时再被催，涨得比 50 时少。
        // ⚠️ 这与规则 6（高多巴胺 ⇒ 压力打折）是两件事：
        //    那条是**跨激素**的钝化，这条是**同一条激素**的天花板。
        val mid = engine.step(Hormones(cortisol = 50), HormoneEvent.Rushed, 0).cortisol - 50
        val high = engine.step(Hormones(cortisol = 70), HormoneEvent.Rushed, 0).cortisol - 70
        assertEquals("中位不打折", 10, mid)
        assertTrue("高位只该涨 $high（比 $mid 少）", high in 1 until mid)
    }

    @Test
    fun `平常状态下 step 与 apply 一致`() {
        // 互锁只在"已经偏了"的时候起作用。中性时两条路径必须等价，
        // 否则调用方换一个方法就得到不同结果 —— 这种 bug 最难查。
        val events = listOf(
            HormoneEvent.Praise,
            HormoneEvent.Novelty,
            HormoneEvent.Intimacy,
            HormoneEvent.BeingNeeded,
        )
        for (e in events) {
            assertEquals("$e", engine.apply(neutral, e), engine.step(neutral, e, 0))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 2026-11-14：文献校准（表就是配置 + 昼夜反应性）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `默认半衰期就是 HormoneKinetics 里那几个文献值`() {
        // 防的是"改了政策忘了改表"或反过来。
        // ⚠️ 这些数字有出处（见 HormoneKinetics），不是随手调的；
        //    真要改就先改那边，再回来改这条。
        val p = HormonePolicy.Default
        assertEquals(0.5, p.dopamineHalfLifeHours, 1e-9)   // 30 分钟（产品量）
        assertEquals(6.0, p.serotoninHalfLifeHours, 1e-9)  // 色氨酸周转 5–7h
        assertEquals(0.75, p.oxytocinHalfLifeHours, 1e-9)  // 45 分钟（行为余韵）
        assertEquals(1.1, p.cortisolHalfLifeHours, 1e-9)   // 66 分钟（血浆 t½）
        assertEquals(
            HormoneKinetics.CORTISOL_PLASMA_HALF_LIFE_MINUTES / 60.0,
            p.cortisolHalfLifeHours,
            1e-9,
        )
    }

    @Test
    fun `夜间应激反应更小`() {
        // ★ 规则 9：HPA 轴在昼夜谷值时分泌能力低。
        // 同样的"被凶"，凌晨两点的皮质醇增量应当比上午十点小。
        val daytime = engine.step(neutral, HormoneEvent.Conflict, 0, hourOfDay = 10.0)
        val nighttime = engine.step(neutral, HormoneEvent.Conflict, 0, hourOfDay = 2.0)

        val dayGain = daytime.cortisol - neutral.cortisol
        val nightGain = nighttime.cortisol - neutral.cortisol
        assertTrue("白天该涨 $dayGain，夜里涨 $nightGain（应更少）", nightGain < dayGain)
        assertTrue("夜里也不该完全没反应：$nightGain", nightGain > 0)
        // 幅度只该差一点点 —— 这是校正，不是"夜里不怕被凶"
        assertTrue("差得太多了：白天 $dayGain / 夜里 $nightGain", dayGain - nightGain <= 3)
    }

    @Test
    fun `不传时刻时不做昼夜校正`() {
        // `hourOfDay = null` 必须与"白天"等价，否则离线回放与线上会算出两套值。
        val noHour = engine.step(neutral, HormoneEvent.Conflict, 0)
        val daytime = engine.step(neutral, HormoneEvent.Conflict, 0, hourOfDay = 10.0)
        assertEquals(daytime, noHour)
    }

    @Test
    fun `stressReactivity 白天是一 夜里最低`() {
        assertEquals(1.0, HormoneKinetics.stressReactivity(10.0), 1e-9)
        assertEquals(1.0, HormoneKinetics.stressReactivity(18.0), 1e-9)
        // 02:00–06:00 是谷底
        assertEquals(HormoneKinetics.NIGHT_STRESS_REACTIVITY, HormoneKinetics.stressReactivity(3.0), 1e-9)
        // 22:00 刚开始下调，但还没到底（平滑过渡，不是硬开关）
        val at22 = HormoneKinetics.stressReactivity(22.0)
        assertEquals(1.0, at22, 1e-9)
        // 00:00 在半路上
        val at0 = HormoneKinetics.stressReactivity(0.0)
        assertTrue("00:00 应介于两者之间：$at0", at0 in 0.85..1.0)
        // 负小时与 24+ 也要能处理（时钟回拨 / 跨时区）
        assertEquals(HormoneKinetics.stressReactivity(23.0), HormoneKinetics.stressReactivity(-1.0), 1e-9)
        assertEquals(HormoneKinetics.stressReactivity(3.0), HormoneKinetics.stressReactivity(27.0), 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 2026-11-14（第二轮）：规则 10 社会支持缓冲
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `有人在的时候 同一句重话扎得浅`() {
        // Thorsteinsson & James 1999：社会支持对皮质醇反应的效应量 d = 0.83，
        // 是心率(0.61)/舒张压(0.51)/皮电(0.25) 里最大的一个。
        // 我们折成 ×0.75：Conflict 的 +18 变成 +14。
        val alone = engine.step(Hormones(), HormoneEvent.Conflict, 0L).cortisol
        val supported = engine.step(Hormones(oxytocin = 70), HormoneEvent.Conflict, 0L).cortisol

        assertEquals("孤身一人时应吃满 +18：$alone", 68, alone)
        assertEquals("催产素在场时应只吃 +14：$supported", 64, supported)
        assertTrue("有支持必须比没支持轻：$supported vs $alone", supported < alone)

        // 常量本身也钉一下：它只在注释与文档里被引用，容易变成"改了没人发现"的死常量。
        assertEquals(0.83, HormoneKinetics.SOCIAL_SUPPORT_CORTISOL_EFFECT_SIZE_D, 1e-9)
        assertTrue(
            "缓冲系数必须真的削弱反应（< 1.0）：${HormoneKinetics.SOCIAL_SUPPORT_CORTISOL_GAIN}",
            HormoneKinetics.SOCIAL_SUPPORT_CORTISOL_GAIN < 1.0,
        )
    }

    @Test
    fun `催产素不够高时 缓冲不生效`() {
        // 门槛 65：差一点就是"没有支持在场"，不能因为 64 分也打七五折。
        val justBelow = engine.step(Hormones(oxytocin = 64), HormoneEvent.Conflict, 0L).cortisol
        assertEquals(68, justBelow)
    }

    @Test
    fun `社会缓冲只削负面事件 不给额外好处`() {
        // ⚠️ 这条边界很关键：规则 10 不能让"她开心"变成免罚，
        //    也不能反过来给正向事件加成 —— 它只是把同一句重话削浅一点。
        val plain = engine.step(Hormones(), HormoneEvent.Praise, 0L)
        val supported = engine.step(Hormones(oxytocin = 70), HormoneEvent.Praise, 0L)

        assertEquals("被夸的增益不该受社会缓冲影响", plain.dopamine, supported.dopamine)
        assertEquals("皮质醇也不该因为被夸而变动", Hormones.NEUTRAL, supported.cortisol)
    }

    @Test
    fun `社会缓冲可以关掉`() {
        // 对照实验：把系数填 1.0，结果应与"没人陪"完全一致。
        val off = HormoneEngine(HormonePolicy.Default.copy(socialBufferGain = 1.0))
        assertEquals(
            engine.step(Hormones(), HormoneEvent.Conflict, 0L).cortisol,
            off.step(Hormones(oxytocin = 70), HormoneEvent.Conflict, 0L).cortisol,
        )
    }
}
