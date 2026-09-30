package com.pocketagent.personalogic

import kotlin.math.abs

/**
 * 各语气轴在"人格距离"里的权重。
 *
 * ## 权重差了一倍的依据
 *
 * | 轴 | 权重 | 理由 |
 * |---|---|---|
 * | 简洁 / 主动程度 | **1.5** | 直接改变"助理做了什么"，用户每轮都能感到 |
 * | 温度 / 幽默 | 1.0 | 影响感受，但不太改变事情 |
 * | 正式度 / 表情 | **0.5** | 表面风格。偏离了也不太算"不像我了" |
 *
 * 权重若一律取 1，"表情轴从 20 调到 80"会与"简洁轴从 50 调到 110"
 * 贡献同样的距离 —— 而后者才是用户会惊呼"你怎么变了"的那种改变。
 */
val TONE_AXIS_WEIGHTS: Map<ToneAxis, Double> = mapOf(
    ToneAxis.BREVITY to 1.5,
    ToneAxis.PROACTIVITY to 1.5,
    ToneAxis.WARMTH to 1.0,
    ToneAxis.HUMOR to 1.0,
    ToneAxis.FORMALITY to 0.5,
    ToneAxis.EMOJI to 0.5,
)

/**
 * 反漂移报告。
 *
 * @property distance 当前人格与基线的距离，0..1（一般远小于 1）
 * @property threshold 触发锚定的阈值
 * @property dominant 贡献最大的一根轴；全部为 0 时为 null
 * @property needsAnchoring 是否应当主动发起"要不要回到最初"的询问
 * @property suggestion 询问文案；[needsAnchoring] 为 false 时为空串
 */
data class DriftReport(
    val distance: Double,
    val threshold: Double,
    val dominant: ToneAxis?,
    val needsAnchoring: Boolean,
    val suggestion: String,
)

/**
 * 人格距离 —— 「反漂移锚定」的度量。
 *
 * ## 为什么需要一把尺子，而不是"感觉变了很多"
 *
 * 「我好像变得不像我了」如果靠模型自由心证，它会在**每一次**长对话后
 * 都这么说 —— 于是用户学会忽略它，锚定机制等于不存在。
 *
 * 距离给的是一个**可复现、可测试、可解释**的数字：同样的账本，
 * 在任何设备上算出同一个值；用户问"哪里变了"，能答出具体是哪根轴。
 *
 * ## 距离口径
 *
 * ```
 * d = Σ wᵢ·|当前ᵢ − 基线ᵢ| / Σ(wᵢ·100)  +  文本字段惩罚
 * ```
 *
 * 分母是"全部轴都拉满"的加权总量，所以 d 天然落在 0..1。
 * 这里**只用当前值与基线比**，不用账本条数比 —— 用户改了又改回来
 * 意味着人格确实回到了最初，账本再长也不该触发锚定。
 */
object PersonaDistance {

    /** 触发锚定的距离阈值 */
    const val ANCHORING_THRESHOLD = 0.15

    /**
     * 触发锚定所需的**已生效 delta 条数**下限。
     *
     * ★ 加这一条是因为纯距离会误报：用户明确说「以后叫我老张」「别用
     *   感叹号」，两条显式指令就能把距离推过阈值 —— 而这时去问
     *   「我好像变得不像我了，要回到最初吗」，显得它**没在听**。
     *
     *   两条以上的**累积**变化才是"我好像渐渐变得不像我了"这句话
     *   真正在描述的体验：单条改动用户记得，累积的偏移才是失控感。
     */
    const val ANCHORING_MIN_ACTIVE_DELTAS = 2

    /** 每一条已生效的文本字段变更（称呼 / 方言 / 口头禅）贡献的距离 */
    const val TEXT_PENALTY_PER_CHANGE = 0.06

    /**
     * 文本字段惩罚的上限。
     *
     * ⚠️ 封顶是为了**不让文本字段单独触发锚定**：0.06 × 3 = 0.18 > 0.15，
     *    封顶后最多贡献 0.18 —— 嗯，仍能单独越线。所以这里的约束是
     *    "至少也要有 2 条 ACTIVE"（见 [ANCHORING_MIN_ACTIVE_DELTAS]），
     *    加上封顶保证**再多文本变更也不会把距离推到失控**（比如
     *    用户换了 8 次口头禅，不该显得比"简洁轴被拉满"还严重）。
     */
    const val TEXT_PENALTY_CAP = 0.18

    /**
     * 只比语气轴的加权归一距离。
     *
     * @return 0..1
     */
    fun axisDistance(current: ToneAxes, baseline: ToneAxes): Double {
        val totalWeight = TONE_AXIS_WEIGHTS.values.sum()
        val weighted = TONE_AXIS_WEIGHTS.entries.sumOf { (axis, weight) ->
            weight * abs(current.axisOf(axis) - baseline.axisOf(axis))
        }
        return weighted / (totalWeight * ToneAxes.MAX)
    }

    /** 文本字段变更带来的距离惩罚 */
    fun textPenalty(activeTextDeltaCount: Int): Double {
        require(activeTextDeltaCount >= 0) { "条数不能为负" }
        return (activeTextDeltaCount * TEXT_PENALTY_PER_CHANGE).coerceAtMost(TEXT_PENALTY_CAP)
    }

    /**
     * 完整的距离。
     *
     * @param activeDeltas 已生效的变更（调用方负责先滤掉 CANDIDATE/ROLLED_BACK）
     */
    fun distance(current: ToneAxes, baseline: ToneAxes, activeDeltas: List<PersonaDelta>): Double {
        val textCount = activeDeltas.count { it.field.kind == ValueKind.TEXT }
        return axisDistance(current, baseline) + textPenalty(textCount)
    }

    /** 贡献最大的一根轴；所有轴都等于基线时返回 null */
    fun dominantAxis(current: ToneAxes, baseline: ToneAxes): ToneAxis? =
        TONE_AXIS_WEIGHTS.entries
            .map { (axis, weight) -> axis to weight * abs(current.axisOf(axis) - baseline.axisOf(axis)) }
            .filter { it.second > 0.0 }
            .maxByOrNull { it.second }
            ?.first

    /**
     * 生成报告。
     *
     * @param spec 当前人格快照（**自带 baseline**，所以不需要额外传锚点）
     */
    fun report(spec: PersonaSpec, activeDeltas: List<PersonaDelta>): DriftReport {
        val d = distance(spec.tone, spec.baseline, activeDeltas)
        val dominant = dominantAxis(spec.tone, spec.baseline)
        val needsAnchoring = d >= ANCHORING_THRESHOLD &&
            activeDeltas.size >= ANCHORING_MIN_ACTIVE_DELTAS

        return DriftReport(
            distance = d,
            threshold = ANCHORING_THRESHOLD,
            dominant = dominant,
            needsAnchoring = needsAnchoring,
            suggestion = if (needsAnchoring) buildSuggestion(dominant) else "",
        )
    }

    /**
     * 锚定询问文案。
     *
     * ⚠️ 必须**说清是哪根轴**。只说"我好像变得不像我了"，用户无法判断
     *    要不要点"回到最初" —— 他得先回想自己改过什么。说出主导轴
     *    之后，这句话才是一个可回答的问题，而不是一句需要用户自己做
     *    diff 的提示。
     */
    private fun buildSuggestion(dominant: ToneAxis?): String {
        val where = dominant?.let { "（主要在「${it.label}」上）" } ?: ""
        return "我好像变得不像一开始的我了$where，要回到最初的样子吗？"
    }
}