package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「三源触发 + 置信度门」里的那道门。
 *
 * 这里钉的是**公式**，不是数值手感。手感（N 取 3 还是 4）以后可以调，
 * 但"求和"和"概率或"是两种不同的算法，调参数永远救不回来。
 */
class PersonaCandidateGateTest {

    private val gate = CandidateGate()

    // ── 合并公式 ───────────────────────────────────────────────

    @Test
    fun `合并是概率或而不是求和`() {
        // 求和：0.45 × 3 = 1.35 → 被 coerceAtMost(1) 夹成 1.0
        // 概率或：1 − 0.55³ = 0.833625
        //
        // 差别不只是数值：求和会让"随便攒三条必过"，
        // 而概率或让第 N 条的边际贡献递减 —— 这才是"证据"该有的性质。
        assertEquals(0.833625, gate.combine(listOf(0.45, 0.45, 0.45)), 1e-9)
    }

    @Test
    fun `单条证据的合并值就是它自己`() {
        assertEquals(0.6, gate.combine(listOf(0.6)), 1e-9)
    }

    @Test
    fun `两条不同强度的证据按概率或合并`() {
        // 1 − (0.55 × 0.40) = 0.78
        assertEquals(0.78, gate.combine(listOf(0.45, 0.6)), 1e-9)
    }

    @Test
    fun `两条重说的置信度仍未过阈`() {
        // 1 − 0.45² = 0.7975 已 ≥ 0.7，但条数只有 2 ——
        // 这一条同时验证了"光够分还不行，还得够条数"
        assertEquals(0.7975, gate.combine(listOf(0.55, 0.55)), 1e-9)
        assertFalse("2 条不足以升级", gate.decide(DeltaSource.IMPLICIT, 0.7975, 2).upgrade)
    }

    @Test
    fun `没有任何证据时合并值为零而不是一`() {
        // ★ 折成 1.0 是这里最坏的一种默认值：把"没证据"变成"最强证据"
        assertEquals(0.0, gate.combine(emptyList()), 1e-9)
        assertEquals(0.0, gate.combine(listOf(0.0, 0.0)), 1e-9)
    }

    @Test
    fun `只要有一条完全确定的证据合并即为满值`() {
        assertEquals(1.0, gate.combine(listOf(1.0, 0.5)), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `合并越界的置信度会抛异常`() {
        gate.combine(listOf(1.1))
    }

    // ── 各源的门槛 ─────────────────────────────────────────────

    @Test
    fun `三个触发源所需的证据条数各不相同`() {
        assertEquals(1, gate.requiredCount(DeltaSource.EXPLICIT))
        assertEquals(3, gate.requiredCount(DeltaSource.IMPLICIT))
        assertEquals(5, gate.requiredCount(DeltaSource.BEHAVIORAL))
    }

    @Test
    fun `显式指令置信度恒为一`() {
        // 用户明说的话不需要"证据分"—— 原话本身就是证据
        assertEquals(1.0, gate.confidenceOf(DeltaSource.EXPLICIT, listOf("以后叫我老张")), 1e-9)
    }

    @Test
    fun `单条隐式信号的置信度一定低于阈`() {
        // ★ 这是"防止被一句话改坏"的地基。若某个信号的 baseConfidence
        //   被设成 ≥ 0.7，那么"用户打断一次"就等于"用户下令改人格"。
        EvidenceSignal.entries.forEach { signal ->
            assertTrue(
                "信号 ${signal.name} 的置信度 ${signal.baseConfidence} 不得达到阈值",
                signal.baseConfidence < CandidateGate.DEFAULT_UPGRADE_CONFIDENCE,
            )
        }
    }

    @Test
    fun `行为统计的信号会被打折`() {
        // 0.45 × 0.8 = 0.36
        assertEquals(0.36, gate.confidenceOf(DeltaSource.BEHAVIORAL, listOf("INTERRUPT")), 1e-9)
    }

    @Test
    fun `显式指令一条即升级`() {
        val decision = gate.decide(DeltaSource.EXPLICIT, 1.0, 1)
        assertTrue(decision.upgrade)
        assertEquals(1, decision.requiredCount)
    }

    @Test
    fun `隐式反馈三条一致才升级`() {
        val c = gate.confidenceOf(DeltaSource.IMPLICIT, List(3) { "SELF_CORRECTION" })
        val decision = gate.decide(DeltaSource.IMPLICIT, c, 3)

        assertTrue("3 条 0.60 的合并值应越阈（$c）", decision.upgrade)
        assertEquals(3, decision.observedCount)
    }

    @Test
    fun `置信度够但条数不够时不升级`() {
        val decision = gate.decide(DeltaSource.IMPLICIT, 0.99, 2)
        assertFalse(decision.upgrade)
        assertEquals(3, decision.requiredCount)
    }

    @Test
    fun `条数够但置信度不够时不升级`() {
        // 阈值判定用的是 >=，所以 0.69 必须被挡住
        assertFalse(gate.decide(DeltaSource.IMPLICIT, 0.69, 3).upgrade)
        assertTrue("恰好等于阈值应放行", gate.decide(DeltaSource.IMPLICIT, 0.7, 3).upgrade)
    }

    @Test
    fun `行为统计五条才升级`() {
        // 0.36 五条 → 1 − 0.64⁵ = 0.8926258176
        val c = gate.confidenceOf(DeltaSource.BEHAVIORAL, List(5) { "INTERRUPT" })
        assertEquals(0.8926258176, c, 1e-9)

        assertFalse(gate.decide(DeltaSource.BEHAVIORAL, c, 4).upgrade)
        assertTrue(gate.decide(DeltaSource.BEHAVIORAL, c, 5).upgrade)
    }

    // ── 证据格式 ───────────────────────────────────────────────

    @Test
    fun `行为统计可以带时间条件`() {
        val c = gate.confidenceOf(DeltaSource.BEHAVIORAL, listOf("INTERRUPT@23:00-06:00"))
        assertEquals("时间条件不参与打分", 0.36, c, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `隐式反馈不允许带时间条件`() {
        // 隐式反馈是"这一次的对话里发生了什么"，没有时间窗的概念。
        // 放行它会让调用方以为时间条件生效了，其实没有任何地方在读它。
        gate.confidenceOf(DeltaSource.IMPLICIT, listOf("INTERRUPT@23:00-06:00"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `时间条件为空抛异常`() {
        gate.confidenceOf(DeltaSource.BEHAVIORAL, listOf("INTERRUPT@"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知信号名抛异常`() {
        gate.confidenceOf(DeltaSource.IMPLICIT, listOf("USER_LOOKED_UNHAPPY"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空证据抛异常`() {
        gate.confidenceOf(DeltaSource.IMPLICIT, emptyList())
    }

    @Test
    fun `信号名大小写不敏感`() {
        assertEquals(0.45, gate.confidenceOf(DeltaSource.IMPLICIT, listOf("interrupt")), 1e-9)
    }

    // ── 构造校验 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `阈值为零抛异常`() {
        CandidateGate(upgradeConfidence = 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `阈值满了抛异常`() {
        // 为 1.0 意味着只有"完全确定"才升级，而所有隐式信号都 < 1.0
        // ⇒ 候选区永远升不了级，需求②静默失效
        CandidateGate(upgradeConfidence = 1.0)
    }
}