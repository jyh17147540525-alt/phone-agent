package com.pocketagent.agentui

/**
 * **自主度** —— 用户在多大程度上愿意被一步步拦下来问。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是四档，而不是一个"要不要确认"的开关
 * ═══════════════════════════════════════════════════════════════
 *
 * 一个布尔开关只能表达"全问"和"全不问"，而这两端都不好用：
 *
 * - 全问：一次点外卖要弹五张卡，用户会条件反射点允许，
 *   结果是**授权疲劳**，等于没拦。
 * - 全不问：点到银行页面、点了付款、把聊天记录发出去，也没人拦。
 *
 * 真实的分界线不在"要不要问"，而在**这件事失败后能不能收回**：
 * 点错一个按钮能重来，转出去的钱和发出去的身份信息不能。
 * ⇒ 四档提供的正是这条梯子：从"每步都问"到"只兜住不可逆的那两件"。
 *
 * ⚠️ [YOLO] **不是**"完全无限制"。MONEY 与 IDENTITY 默认仍然问一次 ——
 *    这不是替用户做安全判断，而是这两类的错误**没有第二次机会**：
 *    钱付错了要打电话给银行，身份证号发出去收不回来。
 *    真要连这一步也放开，是设置里另一个显式开关
 *    （[AutonomyPolicy.decide] 的 `allowIrreversible`），不藏在档位里。
 */
enum class Autonomy(
    /** 设置页上的名字。 */
    val label: String,
    /** 一句话说清它放过什么、拦什么。 */
    val hint: String,
) {
    /** 每一步都要点一下允许（默认）。 */
    ASK(
        label = "每步都问",
        hint = "任何会改动东西的动作都先问你",
    ),

    /** 本机读写自己来；要往外发东西时问。 */
    NOTIFY(
        label = "本机随便动",
        hint = "点按、输入、改设置不问；把内容发给别人时问你",
    ),

    /** 连"发给别人"也自己来；花钱与身份信息问一次。 */
    AUTO(
        label = "自己看着办",
        hint = "整段任务跑完只汇报结果；花钱与身份信息仍问一次",
    ),

    /** 只兜住不可逆的两件（默认）。 */
    YOLO(
        label = "放开跑",
        hint = "只在你开口关掉之前，拦住花钱与身份信息",
    ),
}

/** [AutonomyPolicy.decide] 的结论里额外要告诉界面的东西。 */
object AutonomyPolicy {

    /**
     * 判定一次动作要不要问。
     *
     * @param level 当前自主度
     * @param risk 本次动作的风险
     * @param remembered 该 (能力, 应用) 已记住的答复（仅 [Autonomy.ASK] 用得上）
     * @param allowIrreversible 设置里那个"连不可逆的也别问"的显式开关
     */
    fun decide(
        level: Autonomy,
        risk: RiskLevel,
        remembered: Consent,
        allowIrreversible: Boolean = false,
    ): ConsentDecision {
        // 只读动作在任何档位都不该走询问 —— 否则"每步都问"会把读屏也问一遍。
        if (!risk.requiresConsent) return ConsentDecision.Allowed

        return when (level) {
            Autonomy.ASK -> PermissionPolicy.decide(risk, remembered)
            Autonomy.NOTIFY ->
                if (risk == RiskLevel.EXTERNAL || risk == RiskLevel.MONEY || risk == RiskLevel.IDENTITY) {
                    ask(risk)
                } else {
                    ConsentDecision.Allowed
                }
            Autonomy.AUTO ->
                if (risk == RiskLevel.MONEY || risk == RiskLevel.IDENTITY) ask(risk) else ConsentDecision.Allowed
            Autonomy.YOLO ->
                if (!allowIrreversible && (risk == RiskLevel.MONEY || risk == RiskLevel.IDENTITY)) {
                    ask(risk)
                } else {
                    ConsentDecision.Allowed
                }
        }
    }

    /**
     * 这些动作即使在放开档也要问一次 —— 因为它们不可逆。
     *
     * ★ 判断标准是"能不能收回"，不是"可不可怕"：
     *   卸载一个 App 可以重装，删一段对话可以重来；
     *   付出去的钱、发出去的身份证号没有撤回按钮。
     */
    private fun ask(risk: RiskLevel): ConsentDecision =
        ConsentDecision.Ask(PermissionPolicy.optionsFor(risk))
}
