package com.pocketagent.personalogic

/**
 * 反 OOC 防线四：**漂移监测与人格体检**（长期，设计稿 §3.4）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 诚实条款（写进数据结构，不只是写进文档）
 * ═══════════════════════════════════════════════════════════════
 *
 * 体检是**启发式**：低分不等于「人设坏了」，高分也不等于「一定像」。
 * **报告是仪表盘，不是验收单** —— 不允许被做成「人格评分」式的营销口径。
 *
 * ⇒ 所以 [PersonaHealthReport] 里**没有** `score` / `grade` 这种字段，
 *    而是把**原始计数**与**覆盖范围**如实摆出来（[coverage] / [unverifiable]）。
 *    一个可被截图传播的"87 分"，会立刻把仪表盘变成 KPI。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 「没看过」与「完美」必须分开
 * ═══════════════════════════════════════════════════════════════
 *
 * 各项比例在**没有样本**时返回 `null`，不是 `0.0`、也不是 `1.0`。
 * 返 0.0 会让界面显示"0%（很差）"，返 1.0 会显示"100%（完美）"——
 * 两种都是在用数字撒谎。`null` 对应界面上的「暂无数据」。
 */
data class PersonaHealthReport(
    /** 守卫检查过的轮次数 */
    val checkedTurns: Int,
    /** 其中被判定为破环的轮次 */
    val oocTurns: Int,
    /** 触发过几次重写（含最终改好的） */
    val rewriteTriggers: Int,
    /** 语言指纹在近期输出上的通过率。null = 没有样本 */
    val fingerprintPassRate: Double?,
    /** 近期样本里命中最多的规则（用于告诉用户"最近常犯哪一条"） */
    val topViolation: FingerprintRule?,
    /** 锚点新鲜度（§2.3 / §8.3 第 9 条） */
    val anchorStatus: AnchorStatus,
    /** 与基线的人格距离。null = 调用方没提供（**不猜**） */
    val distanceFromBaseline: Double?,
    /** 本周期变化摘要（账本的语言化转写） */
    val changeSummary: List<String>,
    /** ★ 本次体检**没有**覆盖到的项 —— 如实列出，防止"全绿"造成假安全感 */
    val unverifiable: List<String>,
) {

    /** 破环率。null = 没检查过（**不是 0.0**，见类注释）。 */
    val oocRatio: Double?
        get() = if (checkedTurns == 0) null else oocTurns.toDouble() / checkedTurns

    /** 人话版一句话总结。**不带分数**。 */
    fun summaryLine(): String = when {
        checkedTurns == 0 -> "还没有足够的记录可以体检。"
        oocTurns == 0 -> "最近 $checkedTurns 轮里，它的说话方式没有跑偏。"
        else -> "最近 $checkedTurns 轮里，有 $oocTurns 轮没保持住设定" +
            (topViolation?.let { "（主要是「${it.label}」）" } ?: "") + "。"
    }
}

/**
 * 体检报告生成器。
 *
 * 全部输入由调用方提供（守卫计数、近期输出样本、锚点状态、距离、变更账本）——
 * 本类**不自己取数**：取数要碰存储与模型，而那些都不可离线验证。
 */
class PersonaHealthReporter(
    /** 近期样本最多取多少条进指纹统计。样本越多越稳，但报告是"最近像不像"，不需要全量。 */
    private val recentSampleLimit: Int = DEFAULT_SAMPLE_LIMIT,
) {

    fun build(
        guardStats: GuardStats,
        recentOutputs: List<String>,
        fingerprint: LanguageFingerprint,
        anchorStatus: AnchorStatus,
        distanceFromBaseline: Double? = null,
        deltas: List<PersonaDelta> = emptyList(),
    ): PersonaHealthReport {
        val samples = recentOutputs.takeLast(recentSampleLimit)
        val violations = samples.flatMap { fingerprint.audit(it) }

        val passRate = if (samples.isEmpty()) {
            null
        } else {
            val clean = samples.count { fingerprint.audit(it).isEmpty() }
            clean.toDouble() / samples.size
        }

        return PersonaHealthReport(
            checkedTurns = guardStats.checkedTurns,
            oocTurns = guardStats.oocTurns,
            rewriteTriggers = guardStats.rewriteTriggers,
            fingerprintPassRate = passRate,
            topViolation = violations.groupingBy { it.rule }.eachCount()
                .maxByOrNull { it.value }?.key,
            anchorStatus = anchorStatus,
            distanceFromBaseline = distanceFromBaseline,
            changeSummary = deltas.map { PersonaChangeNarrator.narrate(it) },
            unverifiable = LanguageFingerprint.UNVERIFIABLE,
        )
    }

    companion object {
        const val DEFAULT_SAMPLE_LIMIT = 50
    }
}

/**
 * 把一条变更账本条目**转写成一句人话**（设计稿 §3.4「账本的语言化转写」）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这不能只是"把数据库里的行显示出来"
 * ═══════════════════════════════════════════════════════════════
 *
 * 需求②要的体验是「ta 为我做出了改变」，而这句话要成立，就意味着
 * 助理能说出类似「你上次说我讲废话，我现在尽量一句说完」。
 * **数据库里的一行记录不构成这个体验** —— 用户看不见它；
 * 一句「它学会了」才构成。
 *
 * ⚠️ 三种状态的话术必须分开（候选 / 已生效 / 已回滚）：
 *    把候选说成"已经改了"是在替用户拍板，
 *    把回滚说成"学会了"是在抹掉一次"我曾经改过又改回来"的事实 ——
 *    而那恰恰是最该被如实说明的。
 */
object PersonaChangeNarrator {

    fun narrate(delta: PersonaDelta): String = when (delta.state) {
        DeltaState.ACTIVE ->
            "它学会了：${delta.field.label} → 「${delta.newValue}」"

        DeltaState.CANDIDATE ->
            "有个观察还没定下来：${delta.field.label} 可能想改成「${delta.newValue}」"

        DeltaState.ROLLED_BACK ->
            "它曾经把${delta.field.label}改成「${delta.newValue}」，后来撤销了"
    }
}
