package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AgentBudget` / `BudgetGuard` 的测试。
 *
 * 重点覆盖的是**四个维度各自独立触发**（任一超限即中止），
 * 以及"能耗不可测时自动豁免"这一条 —— 后者错了会让整个任务
 * 在部分机型上直接跑不起来。
 */
class AgentBudgetTest {

    private val budget = AgentBudget(
        maxTurns = 8,
        maxDurationMs = 300_000L,
        maxEnergyMicroAh = 30_000L,
        maxUploadBytes = 5_000_000L,
    )
    private val guard = BudgetGuard(budget)

    // ── 构造校验 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `maxTurns 为 0 时构造失败`() {
        AgentBudget(maxTurns = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxDurationMs 为负时构造失败`() {
        AgentBudget(maxDurationMs = -1L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxEnergyMicroAh 为 0 时构造失败`() {
        // 0 会让"能耗超限"永远立即触发，等于任务永远跑不了
        AgentBudget(maxEnergyMicroAh = 0L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxUploadBytes 为 0 时构造失败`() {
        AgentBudget(maxUploadBytes = 0L)
    }

    @Test
    fun `默认值与规划文档一致`() {
        val d = AgentBudget()
        assertEquals(8, d.maxTurns)
        assertEquals(300_000L, d.maxDurationMs)
        assertEquals(30_000L, d.maxEnergyMicroAh)
        assertEquals(5_000_000L, d.maxUploadBytes)
    }

    // ── 正常路径 ───────────────────────────────────────────────

    @Test
    fun `未用任何预算时判定为可继续`() {
        val verdict = guard.check(BudgetUsage())
        assertTrue(verdict is BudgetVerdict.Ok)
    }

    @Test
    fun `Ok 分支携带正确的剩余量`() {
        val usage = BudgetUsage(turns = 3, elapsedMs = 60_000L, energyMicroAh = 10_000L, uploadBytes = 1_000_000L)
        val verdict = guard.check(usage) as BudgetVerdict.Ok

        assertEquals(5, verdict.remaining.turns)
        assertEquals(240_000L, verdict.remaining.elapsedMs)
        assertEquals(20_000L, verdict.remaining.energyMicroAh)
        assertEquals(4_000_000L, verdict.remaining.uploadBytes)
    }

    @Test
    fun `剩余量不会为负`() {
        // 即使传入的 usage 已经超过上限（理论上不该发生），也不应算出负数 ——
        // UI 上显示"还能再做 -3 步"会让用户困惑
        val usage = BudgetUsage(turns = 99, elapsedMs = 999_999_999L)
        val remaining = guard.remaining(usage)

        assertEquals(0, remaining.turns)
        assertEquals(0L, remaining.elapsedMs)
    }

    // ── 四维各自独立触发中止 ───────────────────────────────────

    @Test
    fun `轮次达到上限时中止`() {
        val verdict = guard.check(BudgetUsage(turns = 8))
        assertTrue(verdict is BudgetVerdict.Exceeded)
        assertEquals(BudgetDimension.TURNS, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    @Test
    fun `轮次未达上限时不中止`() {
        // ⚠️ 这里刻意用 3 而不是 7。7/8 = 0.875 已经越过 warnRatio(0.8)，
        //    会返回 Warn 而不是 Ok —— 第一版我用 7 写这条，测试红了。
        //    那次红是**测试写错**，不是源码有问题：7 步确实该报警。
        val verdict = guard.check(BudgetUsage(turns = 3))
        assertTrue("3/8 = 0.375 应远离警戒线", verdict is BudgetVerdict.Ok)
    }

    @Test
    fun `未到警戒线时返回 Ok 而非 Warn`() {
        // 边界对照：6/8 = 0.75 < 0.8 → Ok
        assertTrue(guard.check(BudgetUsage(turns = 6)) is BudgetVerdict.Ok)
    }

    @Test
    fun `时长达到上限时中止`() {
        val verdict = guard.check(BudgetUsage(elapsedMs = 300_000L))
        assertEquals(BudgetDimension.DURATION, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    @Test
    fun `能耗达到上限时中止`() {
        val verdict = guard.check(BudgetUsage(energyMicroAh = 30_000L))
        assertEquals(BudgetDimension.ENERGY, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    @Test
    fun `流量达到上限时中止`() {
        val verdict = guard.check(BudgetUsage(uploadBytes = 5_000_000L))
        assertEquals(BudgetDimension.UPLOAD, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    @Test
    fun `任一维度超限即中止而不是取加权平均`() {
        // 构造一个"三维都很宽裕、只有时长超了"的用法。
        // 若实现是加权综合分，这种情况会被平均成一个看起来还行的数字而不中止 ——
        // 而用户已经等了 10 分钟了。
        val usage = BudgetUsage(turns = 1, elapsedMs = 400_000L, energyMicroAh = 100L, uploadBytes = 10L)
        val verdict = guard.check(usage)

        assertTrue("时长超限必须中止，不能被别的维度平均掉", verdict is BudgetVerdict.Exceeded)
        assertEquals(BudgetDimension.DURATION, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    // ── 检查顺序 ───────────────────────────────────────────────

    @Test
    fun `多维同时超限时优先报轮次`() {
        // 轮次是硬计数、不可能误判；能耗依赖采样，读数可能滞后。
        // 顺序反过来会让"其实轮次早就超了"被误报成"能耗异常"，
        // 而后者会引导用户去查一个根本不存在的问题。
        val usage = BudgetUsage(turns = 8, elapsedMs = 400_000L, energyMicroAh = 99_999L, uploadBytes = 9_999_999L)
        val verdict = guard.check(usage) as BudgetVerdict.Exceeded

        assertEquals(BudgetDimension.TURNS, verdict.dimension)
    }

    @Test
    fun `轮次与时长都超时优先报轮次`() {
        val verdict = guard.check(BudgetUsage(turns = 8, elapsedMs = 300_000L))
        assertEquals(BudgetDimension.TURNS, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    @Test
    fun `时长与能耗都超时优先报时长`() {
        val verdict = guard.check(BudgetUsage(elapsedMs = 300_000L, energyMicroAh = 30_000L))
        assertEquals(BudgetDimension.DURATION, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    @Test
    fun `能耗与流量都超时优先报能耗`() {
        val verdict = guard.check(BudgetUsage(energyMicroAh = 30_000L, uploadBytes = 5_000_000L))
        assertEquals(BudgetDimension.ENERGY, (verdict as BudgetVerdict.Exceeded).dimension)
    }

    // ── 警戒线 ─────────────────────────────────────────────────

    @Test
    fun `用掉八成时给出警戒`() {
        val verdict = guard.check(BudgetUsage(turns = 7))  // 7/8 = 0.875
        assertTrue(verdict is BudgetVerdict.Warn)
        assertEquals(BudgetDimension.TURNS, (verdict as BudgetVerdict.Warn).dimension)
    }

    @Test
    fun `警戒只报最紧的那一维`() {
        // 轮次 7/8 = 0.875，时长 250000/300000 = 0.833，流量 4500000/5000000 = 0.9
        // 最紧的是流量 —— 只应报流量，不该把三维都报出来
        val usage = BudgetUsage(turns = 7, elapsedMs = 250_000L, uploadBytes = 4_500_000L)
        val verdict = guard.check(usage) as BudgetVerdict.Warn

        assertEquals(BudgetDimension.UPLOAD, verdict.dimension)
    }

    @Test
    fun `刚好到警戒线时报警戒`() {
        // 8 * 0.8 = 6.4 → turns = 7 一定超线；这里测边界用别的维度
        // 5000000 * 0.8 = 4000000，恰好等于 → 应报警戒
        val verdict = guard.check(BudgetUsage(uploadBytes = 4_000_000L))
        assertTrue(verdict is BudgetVerdict.Warn || verdict is BudgetVerdict.Ok)
        assertEquals(BudgetDimension.UPLOAD, (verdict as BudgetVerdict.Warn).dimension)
    }

    @Test
    fun `未到警戒线时为 Ok`() {
        val verdict = guard.check(BudgetUsage(turns = 6))  // 6/8 = 0.75
        assertTrue(verdict is BudgetVerdict.Ok)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `warnRatio 为 0 时构造失败`() {
        BudgetGuard(budget, warnRatio = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `warnRatio 为 1 时构造失败`() {
        // 为 1 意味着"用满才报警"，而用满就已经中止了 → 警戒线永远不会触发
        BudgetGuard(budget, warnRatio = 1f)
    }

    // ── 能耗换算（★ 最容易搞反的地方）─────────────────────────

    @Test
    fun `能耗消耗量按起点减当前计算`() {
        // Charge counter 是**剩余**电荷量，任务跑着它变小。
        // 搞反符号会得到负数。
        val baseline = EnergyReading(chargeCounterMicroAh = 3_049L, sampledAtMs = 1_000L)
        val current = EnergyReading(chargeCounterMicroAh = 3_020L, sampledAtMs = 61_000L)

        assertEquals(29L, guard.energySpentMicroAh(baseline, current))
    }

    @Test
    fun `能耗恒为非负`() {
        // 中间充过电 → counter 变大 → 算出来是负数。
        // 这种情况说明读数被充电污染，返回 0（宁可高估剩余，
        // 也不要因为一个假的高消耗而误中止用户的任务）
        val baseline = EnergyReading(chargeCounterMicroAh = 3_000L, sampledAtMs = 1_000L)
        val current = EnergyReading(chargeCounterMicroAh = 3_100L, sampledAtMs = 61_000L)

        assertEquals(0L, guard.energySpentMicroAh(baseline, current))
    }

    @Test
    fun `起点读数缺失时能耗记为不可测`() {
        val baseline = EnergyReading(chargeCounterMicroAh = null, sampledAtMs = 1_000L)
        val current = EnergyReading(chargeCounterMicroAh = 3_000L, sampledAtMs = 61_000L)

        assertEquals(BudgetGuard.ENERGY_NOT_MEASURABLE, guard.energySpentMicroAh(baseline, current))
    }

    @Test
    fun `当前读数缺失时能耗记为不可测`() {
        val baseline = EnergyReading(chargeCounterMicroAh = 3_049L, sampledAtMs = 1_000L)
        val current = EnergyReading(chargeCounterMicroAh = null, sampledAtMs = 61_000L)

        assertEquals(BudgetGuard.ENERGY_NOT_MEASURABLE, guard.energySpentMicroAh(baseline, current))
    }

    @Test
    fun `能耗不可测时该维度自动豁免而不影响任务`() {
        // ★ 关键：部分机型拿不到 Charge counter。若让它抛异常或判定超限，
        //   整个任务在那类机型上直接跑不起来 —— 而能耗只是四个维度之一。
        val reading = EnergyReading(chargeCounterMicroAh = null, sampledAtMs = 1_000L)
        assertFalse(guard.isEnergyMeasurable(reading))

        // 不可测 → 消耗量记 0 → 该维度永不触发
        val usage = BudgetUsage(turns = 1).copy(
            energyMicroAh = guard.energySpentMicroAh(reading, reading),
        )
        assertTrue(guard.check(usage) is BudgetVerdict.Ok)
    }

    @Test
    fun `能耗可测时报告可测`() {
        val reading = EnergyReading(chargeCounterMicroAh = 3_049L, sampledAtMs = 1_000L)
        assertTrue(guard.isEnergyMeasurable(reading))
    }

    // ── BudgetUsage 的累加 ─────────────────────────────────────

    @Test
    fun `nextTurn 只增加轮次不动其它维度`() {
        val usage = BudgetUsage(turns = 2, elapsedMs = 5_000L, uploadBytes = 100L).nextTurn()

        assertEquals(3, usage.turns)
        assertEquals(5_000L, usage.elapsedMs)
        assertEquals(100L, usage.uploadBytes)
    }

    @Test
    fun `plusUpload 累加流量`() {
        val usage = BudgetUsage(uploadBytes = 1_000L).plusUpload(500L).plusUpload(300L)
        assertEquals(1_800L, usage.uploadBytes)
    }

    @Test
    fun `at 覆盖时长与能耗`() {
        val usage = BudgetUsage(elapsedMs = 1L, energyMicroAh = 1L).at(elapsedMs = 9_000L, energyMicroAh = 42L)

        assertEquals(9_000L, usage.elapsedMs)
        assertEquals(42L, usage.energyMicroAh)
    }

    @Test
    fun `BudgetUsage 是不可变的`() {
        // 可变累加器会导致"判定时读到的值和落盘的值不一致"这种极难复现的问题
        val original = BudgetUsage(turns = 1)
        original.nextTurn()
        assertEquals("nextTurn 不得修改原对象", 1, original.turns)
    }

    // ── 宽松预算 ───────────────────────────────────────────────

    @Test
    fun `宽松预算的所有维度都不小于默认`() {
        val d = AgentBudget()
        val r = AgentBudget.RELAXED

        assertTrue(r.maxTurns > d.maxTurns)
        assertTrue(r.maxDurationMs > d.maxDurationMs)
        assertTrue(r.maxEnergyMicroAh > d.maxEnergyMicroAh)
        assertTrue(r.maxUploadBytes > d.maxUploadBytes)
    }
}
