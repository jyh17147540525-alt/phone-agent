package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonaRuntime]：时间推进、时钟回拨、状态隔离、以及"它不复制账本"。
 *
 * ⚠️ 用假时钟而不是 `Thread.sleep` —— 回落速率是**产品判断**（4 小时回中），
 *    靠真等四小时来验证等于永远没验证过。
 */
class PersonaRuntimeTest {

    private var now = 1_000_000L
    private fun clock() = now

    private fun runtime(
        tuner: PersonaTuner = DefaultPersonaTuner(PersonaPresets.MALE),
    ) = PersonaRuntime(tuner, clock = ::clock)

    // ── ① 起点：中位，且构造时刻不是 0 ─────────────────────────────

    @Test
    fun `初始状态是中位`() {
        val rt = runtime()
        assertEquals(PersonaState.NEUTRAL, rt.currentState().mood)
        assertEquals(PersonaState.NEUTRAL, rt.currentState().energy)
    }

    @Test
    fun `构造时基准点取当下而非 0（否则首次读取会衰减掉自 1970 年以来的全部时间）`() {
        // 若基准点是 0，第一次 currentState() 会传入 elapsed = 1_000_000ms 这种巨大值，
        // 而此刻状态本来就是中位 —— 从"中位"衰减仍然是"中位"，看不出来。
        // 所以先制造一个非中位状态，再验证"构造后立刻读"不会把它抹平。
        val rt = runtime()
        rt.apply(PersonaEvent(PersonaEventKind.PRAISED, reason = "测试"))
        val mood = rt.stateSnapshot().mood
        assertNotEquals(PersonaState.NEUTRAL, mood)
        // 构造后只过了 0ms（时钟没动）⇒ 再读一次，值必须原样。
        assertEquals(mood, rt.currentState().mood)
    }

    // ── ② ★ 读取即推进（这是本类存在的理由）────────────────────────

    @Test
    fun `currentState 会推进时间 —— 冻结的状态是假话`() {
        val rt = runtime()
        rt.apply(PersonaEvent(PersonaEventKind.REPRIMANDED, reason = "被否定了一次"))
        val sad = rt.stateSnapshot().mood
        assertTrue("事件后情绪应低于中位：$sad", sad < PersonaState.NEUTRAL)

        // 过 2 小时再读 —— 情绪必须已经回落了一部分。
        now += 2 * 3_600_000L
        val later = rt.currentState().mood
        assertTrue("2 小时后应比当时更接近中位（$sad → $later）", later > sad)
    }

    @Test
    fun `四小时后情绪回到中位（默认回落速率）`() {
        val rt = runtime()
        rt.apply(PersonaEvent(PersonaEventKind.REPRIMANDED, reason = "被否定了一次"))
        now += 5 * 3_600_000L // 默认 12 点/小时 ⇒ 4 小时足够走完 14 点
        assertEquals(PersonaState.NEUTRAL, rt.currentState().mood)
    }

    @Test
    fun `回中之后由头也过期（留一条对不上的由头会让「你怎么了」答得莫名其妙）`() {
        val rt = runtime()
        rt.apply(PersonaEvent(PersonaEventKind.PRAISED, reason = "陪聊了一会儿"))
        assertTrue(rt.stateSnapshot().reason != null)
        now += 5 * 3_600_000L
        assertTrue("完全回中后由头应为 null", rt.currentState().reason == null)
    }

    // ── ③ ★ 时钟回拨：不推进，也不移动基准点 ──────────────────────

    @Test
    fun `时钟回拨不推进状态、也不移动基准点`() {
        val rt = runtime()
        rt.apply(PersonaEvent(PersonaEventKind.REPRIMANDED, reason = "被否定了一次"))
        val before = rt.stateSnapshot().mood

        now -= 3_600_000L // 时钟被往回拨了一小时
        assertEquals("回拨期间状态不该变", before, rt.currentState().mood)

        // ★ 关键：基准点若被移动，接下来这一小时的真实流逝会被吃掉。
        //   回拨 1 小时后再前进 2 小时，净推进应为 1 小时 —— 若基准点被移到了
        //   "回拨后"的时刻，则这里会推进 2 小时，状态回落得比真实更快。
        now += 2 * 3_600_000L
        val after = rt.currentState().mood
        assertTrue("回拨后应恢复正常推进（$before → $after）", after > before)
    }

    @Test
    fun `同一毫秒内连读两次是幂等的`() {
        val rt = runtime()
        rt.apply(PersonaEvent(PersonaEventKind.PRAISED, reason = "测试"))
        val a = rt.currentState()
        val b = rt.currentState() // 时钟没动
        assertEquals(a, b)
    }

    // ── ④ ★ 状态不进账本（与 PersonaStateEngine 同一条不变量）──────

    @Test
    fun `状态的变化不改变当前人格`() {
        val tuner = DefaultPersonaTuner(PersonaPresets.MALE)
        val rt = PersonaRuntime(tuner, clock = ::clock)
        val specBefore = rt.currentSpec()

        rt.apply(PersonaEvent(PersonaEventKind.REPRIMANDED, reason = "被骂了"))
        now += 10 * 3_600_000L
        rt.currentState()

        val specAfter = rt.currentSpec()
        // ★ 若状态混进漂移计算，「今天心情差」会被算成「人格变了」，
        //   于是锚定询问会在最不该的时候弹出。
        assertEquals("状态变化绝不能影响人格", specBefore, specAfter)
        assertTrue("账本里不该多出任何条目", tuner.ledgerSnapshot().isEmpty())
    }

    // ── ⑤ 它不复制账本：微调必须立刻反映到 currentSpec ─────────────

    @Test
    fun `currentSpec 直接转发 tuner —— 微调立刻可见`() {
        val tuner = DefaultPersonaTuner(PersonaPresets.MALE)
        val rt = PersonaRuntime(tuner, clock = ::clock)
        assertEquals("你", rt.currentSpec().addressStyle)

        tuner.tune(
            ref = PersonaFieldRef.Whitelisted(PersonaField.ADDRESS_STYLE),
            newValue = "老板",
            source = DeltaSource.EXPLICIT,
            evidence = listOf("以后叫我老板"),
            now = 2_000_000L,
        )

        assertEquals("微调后应立刻反映（本类不缓存 spec）", "老板", rt.currentSpec().addressStyle)
    }

    // ── ⑥ stateSnapshot 不推进时间 ────────────────────────────────

    @Test
    fun `stateSnapshot 只读不推进`() {
        val rt = runtime()
        rt.apply(PersonaEvent(PersonaEventKind.REPRIMANDED, reason = "被否定了一次"))
        val snap1 = rt.stateSnapshot()
        now += 2 * 3_600_000L
        val snap2 = rt.stateSnapshot()
        assertEquals("快照不该推进时间", snap1, snap2)
        // 而 currentState 会推进 —— 两者行为必须不同，否则就有一个是多余的。
        assertNotEquals(snap2, rt.currentState())
    }
}
