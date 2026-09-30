package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4.2.4 验收第⑤条：**人格距离超阈值 → 触发锚定询问**。
 *
 * 这里钉两件事：
 * 1. 距离的**口径**（权重、文本惩罚、上限）—— 口径错了会让
 *    锚定要么从不触发，要么每轮都触发；
 * 2. 触发条件是**两个**（距离 ≥ 阈值 **且** 已生效变更 ≥ 2 条）——
 *    只看距离会把用户明确下的指令误报成"我变得不像我了"。
 */
class PersonaDriftTest {

    /** 基线取预设的语气轴（`PersonaSpec.baseline` 的来源） */
    private val baseline = PersonaPresets.MALE.tone

    private fun tuner() = DefaultPersonaTuner(PersonaPresets.MALE)

    private fun explicit(tuner: PersonaTuner, field: String, value: String, at: Long) =
        tuner.tune(PersonaFieldRefs.resolve(field), value, DeltaSource.EXPLICIT, listOf("用户要求"), at)

    // ── 距离口径 ───────────────────────────────────────────────

    @Test
    fun `与基线一致时距离为零`() {
        assertEquals(0.0, PersonaDistance.axisDistance(baseline, baseline), 1e-9)
        assertNull(PersonaDistance.dominantAxis(baseline, baseline))
    }

    @Test
    fun `简洁轴偏离四十五点距离约零点一一二五`() {
        // 1.5 × 45 / (6.0 × 100) = 0.1125
        val current = baseline.withAxis(ToneAxis.BREVITY, 100)
        assertEquals(0.1125, PersonaDistance.axisDistance(current, baseline), 1e-9)
    }

    @Test
    fun `主动程度拉满恰好等于阈值`() {
        // 1.5 × 60 / 600 = 0.15 —— 刻意选这个值做边界
        val current = baseline.withAxis(ToneAxis.PROACTIVITY, 100)
        assertEquals(PersonaDistance.ANCHORING_THRESHOLD, PersonaDistance.axisDistance(current, baseline), 1e-9)
    }

    @Test
    fun `高权重的轴比低权重的轴更容易推动距离`() {
        // 简洁偏离 45 与正式度偏离 65，前者贡献必须更大 ——
        // 若权重一律取 1.0，"表情轴从 20 调到 80"会与
        // "简洁轴从 50 调到 110"贡献同样的距离，而后者才是
        // 用户会惊呼"你怎么变了"的那种改变。
        val brevityShift = PersonaDistance.axisDistance(baseline.withAxis(ToneAxis.BREVITY, 100), baseline)
        val formalityShift = PersonaDistance.axisDistance(baseline.withAxis(ToneAxis.FORMALITY, 100), baseline)

        assertTrue("简洁(1.5)偏离 45 应大于正式度(0.5)偏离 65", brevityShift > formalityShift)
    }

    @Test
    fun `主导轴报的是加权后偏离最大的那根`() {
        val current = baseline.withAxis(ToneAxis.BREVITY, 100).withAxis(ToneAxis.PROACTIVITY, 100)
        // 简洁 1.5×45=67.5，主动 1.5×60=90 → 主导是主动程度
        assertEquals(ToneAxis.PROACTIVITY, PersonaDistance.dominantAxis(current, baseline))
    }

    @Test
    fun `两根轴偏离时距离累加`() {
        val current = baseline.withAxis(ToneAxis.BREVITY, 100).withAxis(ToneAxis.PROACTIVITY, 100)
        // (67.5 + 90) / 600 = 0.2625
        assertEquals(0.2625, PersonaDistance.axisDistance(current, baseline), 1e-9)
    }

    // ── 文本字段惩罚 ───────────────────────────────────────────

    @Test
    fun `文本字段每变一条加零点零六`() {
        assertEquals(0.06, PersonaDistance.textPenalty(1), 1e-9)
        assertEquals(0.12, PersonaDistance.textPenalty(2), 1e-9)
        assertEquals(0.18, PersonaDistance.textPenalty(3), 1e-9)
    }

    @Test
    fun `文本字段惩罚有上限`() {
        // 用户换了 8 次口头禅，不该显得比"简洁轴被拉满"还严重
        assertEquals(PersonaDistance.TEXT_PENALTY_CAP, PersonaDistance.textPenalty(8), 1e-9)
    }

    @Test
    fun `五条文本变更的距离被封顶`() {
        val tuner = tuner()
        // 同一字段改三次（每次一条 ACTIVE）+ 另外两个文本字段各一次 = 5 条
        explicit(tuner, "address_style", "老张", 1_000L)
        explicit(tuner, "address_style", "张总", 2_000L)
        explicit(tuner, "address_style", "老哥", 3_000L)
        explicit(tuner, "dialect", "带点粤语腔", 4_000L)
        explicit(tuner, "catchphrase", "好嘞", 5_000L)

        val report = tuner.driftCheck(tuner.currentSpec())

        assertEquals("5 × 0.06 = 0.30，但必须封顶到 0.18",
            PersonaDistance.TEXT_PENALTY_CAP, report.distance, 1e-9)
    }

    // ── 触发条件：距离 AND 条数 ─────────────────────────────────

    @Test
    fun `单条变更即使距离恰好到阈值也不触发锚定`() {
        val tuner = tuner()
        explicit(tuner, "tone_proactivity", "100", 1_000L)

        val report = tuner.driftCheck(tuner.currentSpec())

        assertEquals("距离确实到了阈值", PersonaDistance.ANCHORING_THRESHOLD, report.distance, 1e-9)
        // ★ 用户明确说过"以后主动一点"，这时去问"我好像变得不像我了"
        //   显得它**没在听**。累积的偏移才是失控感的来源。
        assertFalse("只有一条已生效变更，不该问", report.needsAnchoring)
        assertEquals("", report.suggestion)
    }

    @Test
    fun `累积两条变更且距离越阈时触发锚定`() {
        val tuner = tuner()
        explicit(tuner, "tone_proactivity", "100", 1_000L)
        explicit(tuner, "tone_warmth", "100", 2_000L)

        val report = tuner.driftCheck(tuner.currentSpec())

        assertTrue(report.needsAnchoring)
        assertTrue("建议文案必须能被用户直接回答", report.suggestion.contains("回到最初"))
        assertEquals(PersonaDistance.ANCHORING_THRESHOLD, report.threshold, 1e-9)
    }

    @Test
    fun `建议文案会点名主导轴`() {
        val tuner = tuner()
        explicit(tuner, "tone_proactivity", "100", 1_000L)
        explicit(tuner, "tone_warmth", "100", 2_000L)

        val report = tuner.driftCheck(tuner.currentSpec())

        // 只说"我好像变得不像我了"，用户得先回想自己改过什么才能作答。
        // 点名主导轴之后，它才是一个可回答的问题。
        assertEquals(ToneAxis.PROACTIVITY, report.dominant)
        assertTrue(report.suggestion.contains(ToneAxis.PROACTIVITY.label))
    }

    @Test
    fun `候选区的变更不参与距离计算`() {
        val tuner = tuner()
        // 一条隐式候选：人格没变，距离必须是 0
        tuner.tune(PersonaFieldRefs.resolve("tone_warmth"), "70", DeltaSource.IMPLICIT, listOf("INTERRUPT"), 1_000L)

        val report = tuner.driftCheck(tuner.currentSpec())
        assertEquals(0.0, report.distance, 1e-9)
        assertFalse(report.needsAnchoring)
    }

    @Test
    fun `改回原来的值之后距离重新归零`() {
        // 漂移只看"当前 vs 基线"，不看账本有多长。
        // 用户改了又改回来，人格确实回到了最初 —— 账本再长也不该触发锚定。
        val tuner = tuner()
        explicit(tuner, "tone_warmth", "100", 1_000L)
        explicit(tuner, "tone_warmth", "55", 2_000L)

        assertEquals(0.0, tuner.driftCheck(tuner.currentSpec()).distance, 1e-9)
    }

    @Test
    fun `锚点不随微调移动`() {
        // baseline 必须始终是"最初的我"。它一旦跟着当前值走，
        // 距离永远算成 0，锚定机制静默失效。
        val tuner = tuner()
        val before = tuner.currentSpec().baseline
        explicit(tuner, "tone_brevity", "100", 1_000L)
        val after = tuner.currentSpec().baseline

        assertEquals(before, after)
        assertEquals(PersonaPresets.MALE.tone, after)
    }
}