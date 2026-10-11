package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态机：回落、事件、由头必填，以及 ★「状态不进账本 / 不参与漂移」的结构性守卫。
 */
class PersonaStateEngineTest {

    private val engine = PersonaStateEngine()
    private val HOUR = 3_600_000L

    private fun event(
        kind: PersonaEventKind,
        reason: String = "因为一件具体的事",
        intensity: EventIntensity = EventIntensity.NORMAL,
    ) = PersonaEvent(kind, reason, intensity)

    // ── 回落 ────────────────────────────────────────────────────

    @Test
    fun `中性状态随时间流逝不变`() {
        val neutral = PersonaState()
        assertEquals(neutral, engine.advance(neutral, 10 * HOUR))
    }

    @Test
    fun `偏离的情绪向中性回落`() {
        val happy = PersonaState(mood = 90, energy = 50, reason = "刚被夸")
        val later = engine.advance(happy, 2 * HOUR)
        assertTrue("应当往中性的方向走", later.mood < 90)
        assertTrue("但不该一步到位", later.mood > PersonaState.NEUTRAL)
    }

    @Test
    fun `低落的情绪也向中性回升而不是继续往下掉`() {
        // 回落目标是「中性」不是「最低」—— 否则"长期没人理"会无限消沉，
        // 那既不像人，也变成一种情绪胁迫（§4.6 明令禁止）。
        val down = PersonaState(mood = 10, energy = 50)
        val later = engine.advance(down, 3 * HOUR)
        assertTrue("应当回升", later.mood > 10)
    }

    @Test
    fun `足够久之后完全回到中性且由头被清空`() {
        val sad = PersonaState(mood = 5, energy = 5, reason = "被否定")
        val later = engine.advance(sad, 100 * HOUR)
        assertEquals(PersonaState.NEUTRAL, later.mood)
        assertEquals(PersonaState.NEUTRAL, later.energy)
        assertNull("回中之后由头就过期了，留着会答得莫名其妙", later.reason)
    }

    @Test
    fun `未回中时由头保留`() {
        val p = PersonaState(mood = 90, energy = 50, reason = "刚被夸")
        assertEquals("刚被夸", engine.advance(p, 1 * HOUR).reason)
    }

    @Test
    fun `时长为零或负数不改变任何东西`() {
        val s = PersonaState(mood = 80, energy = 30, reason = "x")
        assertEquals(s, engine.advance(s, 0))
        assertEquals("时钟回拨不该制造状态跳变", s, engine.advance(s, -1000))
    }

    // ── 事件 ────────────────────────────────────────────────────

    @Test
    fun `事件按类型调整状态并带上由头`() {
        val after = engine.apply(PersonaState(), event(PersonaEventKind.PRAISED, reason = "你夸我了"))
        assertTrue(after.mood > PersonaState.NEUTRAL)
        assertEquals("你夸我了", after.reason)
    }

    @Test
    fun `只耗精力的事件不改变情绪`() {
        val before = PersonaState(mood = 60, energy = 80)
        val after = engine.apply(before, event(PersonaEventKind.LONG_SESSION))
        assertEquals("情绪不该被无差别拉低", 60, after.mood)
        assertTrue(after.energy < 80)
    }

    @Test
    fun `强度分档影响幅度`() {
        val base = PersonaState()
        val mild = engine.apply(base, event(PersonaEventKind.PRAISED, intensity = EventIntensity.MILD))
        val strong = engine.apply(base, event(PersonaEventKind.PRAISED, intensity = EventIntensity.STRONG))
        assertTrue("强事件涨得更多", strong.mood > mild.mood)
    }

    @Test
    fun `状态不会越界`() {
        var s = PersonaState(mood = 95, energy = 95)
        repeat(10) { s = engine.apply(s, event(PersonaEventKind.PRAISED, intensity = EventIntensity.STRONG)) }
        assertEquals(100, s.mood)
        assertEquals(100, s.energy)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `事件必须带由头`() {
        // 没有由头的情绪就是随机情绪，而随机情绪 = 崩人设（§4.2）。
        PersonaEvent(PersonaEventKind.PRAISED, reason = "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `回落速率不能为负`() {
        StatePolicy(moodDecayPerHour = -1.0, energyDecayPerHour = 1.0)
    }

    // ── ★ 结构性守卫：状态不能被人格微调改到 ────────────────────

    @Test
    fun `★ 状态类字段不在人格白名单里 —— 用户微调不到它`() {
        // 这条测试的作用是**挡住将来的人**：如果谁把 MOOD / ENERGY / CLOSENESS
        // 加进 PersonaField（= 加进可微调白名单），这里立刻红。
        // 状态与人格是两件事：状态是小时级的，人格是长期的；
        // 混在一起会让「今天心情差」被算成「人格变了」（§4.2 的头号不变量）。
        val forbidden = listOf("MOOD", "ENERGY", "CLOSENESS", "STAGE", "RELATIONSHIP")
        val offenders = PersonaField.entries.filter { f -> forbidden.any { f.name.contains(it) } }
        assertTrue("状态/关系类字段不许进人格白名单，实际有：$offenders", offenders.isEmpty())
    }

    @Test
    fun `★ 状态字段名解析后不是白名单字段`() {
        for (raw in listOf("mood", "energy", "closeness", "心情", "精力", "亲密度")) {
            val resolved = PersonaFieldRefs.resolve(raw)
            assertTrue(
                "「$raw」不该是可微调字段，实际解析为 $resolved",
                resolved !is PersonaFieldRef.Whitelisted,
            )
        }
    }

    @Test
    fun `★ 状态变化不影响人格推导`() {
        // PersonaSpec.derive 的入参是 (preset, ledger)，**不含状态** ——
        // 所以这条在类型层面就成立。写出来是为了把"不变量"变成一句可执行的话：
        // 无论状态取什么值，从同一份预设+账本推导出的人格必须完全一致。
        val preset = PersonaPresets.MALE
        val ledger = emptyList<PersonaDelta>()
        val a = PersonaSpec.derive(preset, ledger)
        val b = PersonaSpec.derive(preset, ledger)
        assertEquals(a, b)
        // 距离的入参里**没有状态** —— 这正是"状态不参与漂移"的类型级证明。
        assertEquals(
            "刚推导出来的人与自己的基线距离为 0（与状态无关）",
            0.0,
            PersonaDistance.distance(a.tone, a.baseline, ledger),
            1e-9,
        )
    }
}
