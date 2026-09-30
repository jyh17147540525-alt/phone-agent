package com.pocketagent.personalogic

/**
 * 隐式 / 行为反馈的**信号种类**及其初始置信度。
 *
 * 这些数字不是拍出来的，是最小可辩解值：
 *
 * | 信号 | 置信度 | 为什么是这个量级 |
 * |---|---|---|
 * | [SELF_CORRECTION] | 0.60 | 用户**明说**"不对，应该是…"—— 最接近显式指令 |
 * | [REPHRASE] | 0.55 | 重说一遍同一件事，说明第一次理解错了 |
 * | [INTERRUPT] | 0.45 | 打断可能只是着急，不一定是助理说错了 |
 * | [NEGATIVE_SENTIMENT] | 0.35 | 情绪可能来自别处，与助理无关 |
 * | [SHORTER_REPLY] | 0.30 | 最弱：用户变忙了也会回复变短 |
 *
 * ⚠️ 全部 < [CandidateGate.DEFAULT_UPGRADE_CONFIDENCE]（0.7）**是刻意的**：
 *    单条隐式信号**永远不能**单独点亮一条微调。如果某个信号被设成 ≥0.7，
 *    那么"用户打断一次"就等于"用户下令改人格" —— 需求②里最要命的
 *    「被一句话改坏」就是这么来的。
 */
enum class EvidenceSignal(val baseConfidence: Double, val label: String) {
    INTERRUPT(0.45, "打断"),
    REPHRASE(0.55, "重说"),
    NEGATIVE_SENTIMENT(0.35, "负面情绪"),
    SHORTER_REPLY(0.30, "回复变短"),
    SELF_CORRECTION(0.60, "主动纠正"),
    ;

    companion object {
        private val BY_NAME: Map<String, EvidenceSignal> = entries.associateBy { it.name }

        fun byName(raw: String): EvidenceSignal? = BY_NAME[raw.trim().uppercase()]
    }
}

/**
 * 候选区闸门的判定结果。
 *
 * @property confidence 与同桶历史证据**合并后**的置信度
 * @property requiredCount 该触发源升级所需的一致证据条数
 * @property observedCount 目前攒到的一致证据条数（含本次）
 * @property upgrade 是否立即升级为 ACTIVE
 */
data class GateDecision(
    val confidence: Double,
    val requiredCount: Int,
    val observedCount: Int,
    val upgrade: Boolean,
)

/**
 * 候选区闸门 —— 「三源触发 + 置信度门」里的那道门。
 *
 * ## 三个问题的分工
 *
 * | 门在问 | 由谁回答 |
 * |---|---|
 * | 这一条证据本身值多少分？ | [confidenceOf] |
 * | 多条证据合起来值多少分？ | [combine] |
 * | 够不够格生效？ | [decide] |
 *
 * 拆成三个纯函数是为了让每条规则都能被单独断言。混在一起写的话，
 * "合并公式错了"会表现为"升级阈值不对"，而调阈值是**永远调不好**的 ——
 * 因为根因不在阈值上。
 *
 * ## 合并为什么是"概率或"而不是"求和"
 *
 * 求和会溢出：3 条 0.45 的证据 = 1.35，再被 `coerceAtMost(1.0)` 一夹
 * 就变成"随便攒 3 条必过"。而概率或 `1 − Π(1 − cᵢ)` 有明确的语义 ——
 * 「**至少有一条证据说对了**的概率」，在证据互相独立的前提下它天然
 * 落在 0..1，且第 N 条证据的边际贡献递减。3 条 0.45 得到 0.834，
 * 恰好越过 0.7，这就是"需要三条"这个手感数字的来源。
 *
 * @param upgradeConfidence 升级阈值。须在 (0, 1) 开区间内
 */
class CandidateGate(
    private val upgradeConfidence: Double = DEFAULT_UPGRADE_CONFIDENCE,
) {

    init {
        require(upgradeConfidence > 0.0 && upgradeConfidence < 1.0) {
            "upgradeConfidence 必须在 (0,1) 内，当前为 $upgradeConfidence"
        }
    }

    /** 某触发源升级所需的**一致证据条数** */
    fun requiredCount(source: DeltaSource): Int = when (source) {
        // 显式指令是用户明说的，一条即生效 —— 这也是"立即生效"的实现
        DeltaSource.EXPLICIT -> EXPLICIT_REQUIRED
        DeltaSource.IMPLICIT -> IMPLICIT_REQUIRED
        // 行为统计最弱，也可能被作息变化带偏，所以门槛最高
        DeltaSource.BEHAVIORAL -> BEHAVIORAL_REQUIRED
    }

    /**
     * 计算一组证据的置信度。
     *
     * @param evidence
     * - [DeltaSource.EXPLICIT]：用户原话（内容不限），置信度恒为 1.0
     * - [DeltaSource.IMPLICIT] / [DeltaSource.BEHAVIORAL]：
     *   **必须是 [EvidenceSignal] 的名字**，可选带时间条件后缀
     *   （`INTERRUPT@23:00-06:00`，且只有行为统计允许带）
     *
     * @throws IllegalArgumentException 证据为空、信号名未知、
     *   或非行为统计却带了时间条件。**不做兜底默认值** ——
     *   兜底会让"信号名写错了"表现为"某条微调凭一个不明来源的分数生效了"。
     */
    fun confidenceOf(source: DeltaSource, evidence: List<String>): Double {
        require(evidence.isNotEmpty()) { "证据不能为空" }

        if (source == DeltaSource.EXPLICIT) return 1.0

        val discount = sourceDiscount(source)
        val perSignal = evidence.map { raw -> parseSignal(source, raw).baseConfidence * discount }

        // ⚠️ 单条不 discount 到 0，也不四舍五入 —— 保留原始小数，
        //    让 combine 的中间结果可被测试逐位断言。
        return combine(perSignal)
    }

    /**
     * 概率或：`c = 1 − Π(1 − cᵢ)`。
     *
     * @throws IllegalArgumentException 任一条不在 0..1 内
     */
    fun combine(confidences: List<Double>): Double {
        require(confidences.all { it in 0.0..1.0 }) {
            "置信度必须在 0..1 内，当前为 $confidences"
        }
        // 空集合 → 没有任何证据 → 0.0（而不是 1.0）。折成 1.0 会让
        // "没证据"变成"最强证据"，这是最坏的一种默认值。
        if (confidences.isEmpty()) return 0.0
        return 1.0 - confidences.fold(1.0) { acc, c -> acc * (1.0 - c) }
    }

    /**
     * 判定是否升级。
     *
     * 两个条件**同时**满足才升级：
     * 1. 一致证据条数 ≥ [requiredCount]；
     * 2. 合并置信度 ≥ 阈值。
     *
     * 只看置信度会让"一条极强的证据"直接通关（而这正是要防的
     * 「被一句话改坏」）；只看条数会让"三条互相矛盾的话"通关
     * （而矛盾本身说明用户还没拿定主意）。
     */
    fun decide(
        source: DeltaSource,
        combinedConfidence: Double,
        observedCount: Int,
    ): GateDecision {
        val need = requiredCount(source)
        return GateDecision(
            confidence = combinedConfidence,
            requiredCount = need,
            observedCount = observedCount,
            upgrade = observedCount >= need && combinedConfidence >= upgradeConfidence,
        )
    }

    /** 行为统计源的置信度折扣。见 [DEFAULT_BEHAVIORAL_DISCOUNT] */
    private fun sourceDiscount(source: DeltaSource): Double =
        if (source == DeltaSource.BEHAVIORAL) DEFAULT_BEHAVIORAL_DISCOUNT else 1.0

    /**
     * 解析一条隐式/行为证据。
     *
     * 格式：`信号名` 或 `信号名@时间条件`（时间条件只有行为统计能带）。
     *
     * 时间条件在这里**只做合法性校验、不参与打分** —— 它的语义是
     * "这条偏好只在那个时间段成立"，用于以后组装 prompt 时决定要不要
     * 把这条偏好的话术带进去。打分不该受时间段影响：23:00 的打断
     * 和 14:00 的打断，作为"助理可能说错话了"的证据是等价的。
     */
    private fun parseSignal(source: DeltaSource, raw: String): EvidenceSignal {
        val parts = raw.split('@', limit = 2)
        val signal = EvidenceSignal.byName(parts[0])
            ?: throw IllegalArgumentException(
                "未知的证据信号「$raw」。隐式/行为反馈的证据必须是" +
                    EvidenceSignal.entries.joinToString(" / ") { it.name } +
                    " 之一，可选带 @时间条件（仅行为统计）。",
            )

        if (parts.size == 2) {
            require(parts[1].isNotBlank()) { "证据「$raw」的时间条件为空" }
            require(source == DeltaSource.BEHAVIORAL) {
                "只有行为统计可以带时间条件，当前触发源为 ${source.name}"
            }
        }
        return signal
    }

    companion object {
        /** 升级阈值。见 §4.2.4「连续 N 条一致隐式反馈 → 升 ACTIVE」 */
        const val DEFAULT_UPGRADE_CONFIDENCE = 0.7

        /** 显式指令：一条即生效 */
        const val EXPLICIT_REQUIRED = 1

        /** 隐式反馈：三条一致的才认（U-5 未决项的默认落地值） */
        const val IMPLICIT_REQUIRED = 3

        /** 行为统计：五条一致 + 打折后的置信度才认 */
        const val BEHAVIORAL_REQUIRED = 5

        /** 行为统计的置信度折扣 */
        const val DEFAULT_BEHAVIORAL_DISCOUNT = 0.8
    }
}