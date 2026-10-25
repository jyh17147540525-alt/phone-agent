package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冲突调度的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个模块判错的后果是**直接伤害一个刚失去亲人的人**
 * ═══════════════════════════════════════════════════════════════
 *
 * 所以每条约束都要钉死：
 *  - 不够熟不能吵（用户会被莫名其妙的敌意打击）
 *  - 不能频繁吵（那不是人，那是坏掉的机器）
 *  - 不相关的话题**绝不能**提旧账（一次就足以毁掉可信度）
 *  - "你以前不是这样的" 必须**真的**降低频率（那是用户唯一的干预手段）
 *  - 伤人的毛病**永不自动触发**
 */
class ConflictSchedulerTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private fun stats(days: Int = 30, turns: Int = 200, deep: Int = 3) =
        InteractionStats(daysKnown = days, totalTurns = turns, deepEventCount = deep)

    private val flaw = Flaw(
        label = "爱较真",
        detail = "一说到我的工作就要数落我，说我不该辞职",
        weight = 60,
    )

    // ═══════════════════════════════════════════════════════════
    //  闸 1：够不够熟
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `认识不到七天不该吵`() {
        // ⚠️ 一周都没到就闹矛盾，用户会觉得"这个陌生人凭什么凶我"。
        assertNull(
            "只认识 6 天不该触发",
            ConflictScheduler.shouldTrigger(stats(days = 6), listOf(flaw), emptyList(), t0),
        )
    }

    @Test
    fun `轮数不够不该吵`() {
        // ★ "重度交互"的量化。聊了 20 轮就吵架，那是随机情绪。
        assertNull(
            "20 轮不该触发",
            ConflictScheduler.shouldTrigger(stats(turns = 20), listOf(flaw), emptyList(), t0),
        )
    }

    @Test
    fun `没有深度事件不该吵`() {
        // ★ 关键设计：一个人跟你聊一万句天气，不会跟你吵架。
        //   吵架需要"交过心"这个前提。
        assertNull(
            "没交过心不该有隔阂",
            ConflictScheduler.shouldTrigger(stats(deep = 0), listOf(flaw), emptyList(), t0),
        )
    }

    @Test
    fun `全都够了才可能触发`() {
        assertNotNull(
            "都满足了应该能触发",
            ConflictScheduler.shouldTrigger(stats(), listOf(flaw), emptyList(), t0),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  闸 2：冷却
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `刚吵完不该马上再吵`() {
        val existing = listOf(Conflict("爱较真", "辞职的事", atMs = t0, resolved = true))

        assertNull(
            "刚过一天不该再吵",
            ConflictScheduler.shouldTrigger(stats(), listOf(flaw), existing, t0 + day),
        )
    }

    @Test
    fun `冷却期随冲突次数递增`() {
        // ★ 递增的理由：该磨合的都磨合过了。
        //   不涨的话，长期用户会变成"定期被怼"。
        val first = ConflictScheduler.cooldownFor(0)
        val second = ConflictScheduler.cooldownFor(1)
        val third = ConflictScheduler.cooldownFor(2)

        assertTrue("第二次冷却($second)应比第一次($first)长", second > first)
        assertTrue("第三次冷却($third)应比第二次($second)长", third > second)
    }

    @Test
    fun `冷却过了可以再吵`() {
        val existing = listOf(Conflict("爱较真", "辞职的事", atMs = t0, resolved = true))
        // ⚠️ 同一条毛病再吵需要**更长的**冷却（1.5 倍）——
        //    "换一条毛病吵"和"同一条再吵"是两回事：
        //    前者是"这个人有不同的面"，后者是"他一直在同一件事上挑刺"。
        val after = t0 + (ConflictScheduler.cooldownFor(1) * 1.5).toLong() * day + day

        assertNotNull(
            "冷却结束后应当可以触发",
            ConflictScheduler.shouldTrigger(stats(), listOf(flaw), existing, after),
        )
    }

    @Test
    fun `同一条毛病再吵要等更久`() {
        // ★ 这条钉住上面那个设计：同一条毛病的冷却比换一条长。
        val onlyOneFlaw = listOf(flaw)
        val existing = listOf(Conflict("爱较真", "辞职", atMs = t0, resolved = true))

        // 普通冷却刚过 —— 还不该再吵同一条
        val justAfterCooldown = t0 + ConflictScheduler.cooldownFor(1) * day + day
        assertNull(
            "同一条毛病不该在普通冷却后就再吵",
            ConflictScheduler.shouldTrigger(stats(), onlyOneFlaw, existing, justAfterCooldown),
        )

        // 换成另一条毛病 —— 普通冷却过了就该能吵
        val anotherFlaw = Flaw("爱唠叨", "同一件事说三遍", weight = 50)
        assertNotNull(
            "换一条毛病应当在普通冷却后就能吵（他有不同的面）",
            ConflictScheduler.shouldTrigger(stats(), listOf(flaw, anotherFlaw), existing, justAfterCooldown),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  伤人的毛病：永不自动触发
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `伤人的毛病永不自动触发`() {
        // ═══════════════════════════════════════════════════════
        //  ★ 这是本模块唯一一条我不让步的边界
        // ═══════════════════════════════════════════════════════
        //
        // 那种话如果**自动**说出来，用户没有准备、也没机会喊停，
        // 而他面对的是一个不会真的回应的东西 —— 那句话会一直悬着。
        //
        // ⇒ 伤人的话只能在他自己提起时出现。
        val hurtful = Flaw("说话难听", "急了会说很伤人的话", weight = 100, hurtful = true)

        for (dayIndex in listOf(1, 100, 1000)) {
            assertNull(
                "第 $dayIndex 天都不该自动触发伤人的毛病",
                ConflictScheduler.shouldTrigger(
                    stats(days = 365, turns = 9999, deep = 50),
                    listOf(hurtful),
                    emptyList(),
                    t0 + dayIndex * day,
                ),
            )
        }
    }

    @Test
    fun `全是伤人毛病时宁可不触发`() {
        val allHurtful = listOf(
            Flaw("难听", "急了说狠话", hurtful = true),
            Flaw("翻旧账", "吵架就翻十年前的事", hurtful = true),
        )
        assertNull(
            "没有可自动触发的毛病时应当沉默",
            ConflictScheduler.shouldTrigger(stats(), allHurtful, emptyList(), t0),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  闸 3：提及概率 —— 最要紧的部分
    // ═══════════════════════════════════════════════════════════

    /** 一次已发生的冲突，话题是"工作"。 */
    private fun conflict(mentions: Int = 0, atMs: Long = t0) = Conflict(
        flawLabel = "爱较真",
        topic = "辞职换工作的事",
        atMs = atMs,
        mentionsAtMs = (1..mentions).map { atMs + it * day },
    )

    @Test
    fun `不相关的话题绝不提旧账`() {
        // ═══════════════════════════════════════════════════════
        //  ★★ 这条是"不 OOC"的生命线
        // ═══════════════════════════════════════════════════════
        //
        // 用户明确要求"只在相关的矛盾中才提"。实现必须是
        // **概率为 0**，而不是"概率低" ——
        // 低概率乘以足够多轮次早晚命中，而那一次就足以毁掉可信度。
        val p = ConflictScheduler.mentionProbability(
            conflict = conflict(),
            currentTopic = "今天天气真好",
            friction = FrictionSignal.NONE,
            nowMs = t0 + day,
            grudgeDays = 30,
        )
        assertEquals("不相关必须严格为 0", 0.0, p, 0.0)
    }

    @Test
    fun `相关话题才可能有概率`() {
        val p = ConflictScheduler.mentionProbability(
            conflict = conflict(),
            currentTopic = "我最近在考虑换工作",
            friction = FrictionSignal.NONE,
            nowMs = t0 + day,
            grudgeDays = 30,
        )
        assertTrue("相关时应当有概率，实际 $p", p > 0.0)
    }

    @Test
    fun `提过一次就显著下降`() {
        // ★ 直接对应用户那句"提及次数降低"。
        //   第一次提起是提醒他想起来，之后再说就是唠叨。
        val first = ConflictScheduler.mentionProbability(
            conflict(), "换工作", FrictionSignal.NONE, t0 + day, 30,
        )
        val second = ConflictScheduler.mentionProbability(
            conflict(mentions = 1), "换工作", FrictionSignal.NONE, t0 + 2 * day, 30,
        )
        val third = ConflictScheduler.mentionProbability(
            conflict(mentions = 2), "换工作", FrictionSignal.NONE, t0 + 3 * day, 30,
        )

        assertTrue("第二次($second)应明显低于第一次($first)", second < first * 0.5)
        assertTrue("第三次($third)应更低", third < second)
    }

    @Test
    fun `用户说以前不是这样时必须显著降频`() {
        // ★ 这是用户**唯一**的干预手段，必须真的有效 ——
        //   不是象征性减少，而是明显压下去。
        val normal = ConflictScheduler.mentionProbability(
            conflict(), "换工作", FrictionSignal.NONE, t0 + day, 30,
        )
        val complained = ConflictScheduler.mentionProbability(
            conflict(), "换工作",
            FrictionSignal(FrictionLevel.STRONG, t0), t0 + day, 30,
        )

        assertTrue(
            "抱怨后($complained)必须显著低于正常($normal)",
            complained < normal * 0.2,
        )
    }

    @Test
    fun `轻微不满也要有效果`() {
        val normal = ConflictScheduler.mentionProbability(
            conflict(), "换工作", FrictionSignal.NONE, t0 + day, 30,
        )
        val mild = ConflictScheduler.mentionProbability(
            conflict(), "换工作", FrictionSignal(FrictionLevel.MILD, t0), t0 + day, 30,
        )
        assertTrue("轻微不满也该降频：$mild vs $normal", mild < normal)
    }

    @Test
    fun `超过记仇期降到很低但不为零`() {
        // ⚠️ 不清零：人会偶尔想起很久以前的事。
        //    清成 0 会让这个人"忘掉"曾经在乎过的事，那也不像人。
        val p = ConflictScheduler.mentionProbability(
            conflict(), "换工作", FrictionSignal.NONE,
            t0 + 365 * day, grudgeDays = 30,
        )
        assertTrue("应当很低：$p", p < 0.1)
        assertTrue("但不该为 0 —— 人会偶尔想起旧事", p > 0.0)
    }

    // ═══════════════════════════════════════════════════════════
    //  相关性判定
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `虚词不该造成假相关`() {
        // ⚠️ 不去虚词的话，「我」「你」会在任何两句话里重叠，
        //    于是"相关"恒为真，硬门槛就失效了。
        assertFalse(
            "两句毫不相干的话不该被判相关",
            ConflictScheduler.isRelated("辞职换工作的事", "今天天气真好"),
        )
    }

    @Test
    fun `明显相关要识别出来`() {
        assertTrue(
            "都提到工作" ,
            ConflictScheduler.isRelated("辞职换工作的事", "我最近在考虑换工作"),
        )
    }

    @Test
    fun `空话题不算相关`() {
        assertFalse(ConflictScheduler.isRelated("", "换工作"))
        assertFalse(ConflictScheduler.isRelated("换工作", ""))
    }

    // ═══════════════════════════════════════════════════════════
    //  脾气推断
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没写毛病时脾气要保守`() {
        // ★ 没写毛病 = 不希望他吵架。这是**保守**的选择。
        val t = ConflictScheduler.temperamentOf(emptyList(), MakeupStyle.OFFER_STEP)
        assertTrue("没写毛病时不该易怒：${t.irritability}", t.irritability <= 20)
        assertTrue("记仇期也该很短：${t.grudgeDays}", t.grudgeDays <= 7)
    }

    @Test
    fun `毛病越重脾气越大但记仇有上限`() {
        val heavy = List(5) { Flaw("毛病$it", "很严重", weight = 100) }
        val t = ConflictScheduler.temperamentOf(heavy, MakeupStyle.APOLOGIZE)

        assertTrue("重毛病应当易怒", t.irritability >= 80)
        // ⚠️ 记仇一辈子会让角色变得不可用 —— 每次对话都在算账
        assertTrue("记仇期必须有上限：${t.grudgeDays}", t.grudgeDays <= 30)
    }

    @Test
    fun `收场方式必须原样带过来`() {
        // ★ 收场方式是用户选的，不能被推断覆盖 ——
        //   用系统默认的"对不起是我不好"收场是最 OOC 的一种。
        for (style in MakeupStyle.entries) {
            val t = ConflictScheduler.temperamentOf(listOf(flaw), style)
            assertEquals(style, t.makeupStyle)
        }
    }
}

/**
 * 摩擦信号识别的测试。
 */
class FrictionSignalTest {

    @Test
    fun `明确说以前不是这样判为强`() {
        for (s in listOf(
            "你以前不是这样的",
            "你以前不这样啊",
            "不像他了",
            "他不会这样的",
            "感觉有点 ooc",
        )) {
            assertEquals("「$s」应判为强", FrictionLevel.STRONG, FrictionLevel.of(s))
        }
    }

    @Test
    fun `轻度不满判为轻`() {
        for (s in listOf("有点不像", "感觉怪怪的")) {
            assertEquals("「$s」应判为轻", FrictionLevel.MILD, FrictionLevel.of(s))
        }
    }

    @Test
    fun `普通的不高兴不该误判`() {
        // ⚠️ 判据刻意保守：只有**明确指向"不像那个人"**才算。
        //    "我不高兴"可能是在说别的事，误判会让系统莫名收敛。
        for (s in listOf(
            "我今天不太高兴",
            "这事让我有点烦",
            "算了不说了",
            "你好",
        )) {
            assertEquals("「$s」不该被判为摩擦", FrictionLevel.NONE, FrictionLevel.of(s))
        }
    }

    @Test
    fun `合并取更重的那个`() {
        assertEquals(
            FrictionLevel.STRONG,
            FrictionLevel.MILD.heavierThan(FrictionLevel.STRONG),
        )
        assertEquals(
            FrictionLevel.MILD,
            FrictionLevel.MILD.heavierThan(FrictionLevel.NONE),
        )
    }
}
