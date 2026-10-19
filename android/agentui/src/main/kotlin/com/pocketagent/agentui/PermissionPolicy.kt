package com.pocketagent.agentui

/**
 * 授权记忆。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 授权的最小单位是「(能力, 目标应用)」，不是「能力」
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户允许"点击屏幕"时，他心里想的是"允许你在**这个**应用里点"。
 * 若按能力全局记住，那条授权会在下一次打开银行 App 时悄悄生效 ——
 * 而用户从没同意过那件事。**授权的语义必须与用户当时的心理范围一致。**
 */
enum class Consent {
    /** 还没问过。 */
    UNASKED,

    /** 只这一次。 */
    ONCE,

    /** 以后都行（仅对该能力 + 该应用）。 */
    ALWAYS,

    /** 不要。 */
    DENIED,
}

/** 一条授权记录。 */
data class GrantKey(val capability: String, val app: String) {
    init {
        require(capability.isNotBlank()) { "能力名不能为空" }
        require(app.isNotBlank()) { "应用名不能为空 —— 全局授权是危险的默认值" }
    }
}

/** 一次授权判定的结论。 */
sealed interface ConsentDecision {
    /** 直接放行，不问。 */
    data object Allowed : ConsentDecision

    /**
     * 必须问。`options` 是这次能给的选项 —— 顺序即界面顺序。
     *
     * ★ 选项**由风险决定**，不是常量：`MONEY` / `IDENTITY` 不给「以后都行」。
     */
    data class Ask(val options: List<Consent>) : ConsentDecision

    /** 用户之前明确拒绝过，直接拦下（但仍要给"重新授权"的入口）。 */
    data object Blocked : ConsentDecision
}

/**
 * 授权策略 —— 纯函数，无状态。
 *
 * 状态（已记住的 [Consent]）由调用方持有，本对象只做判定。
 */
object PermissionPolicy {

    /** 一次询问能给的选项。 */
    fun optionsFor(risk: RiskLevel): List<Consent> = when {
        // 只读不问 —— 返回空列表表示"根本不该走到询问这一步"。
        !risk.requiresConsent -> emptyList()
        // 花钱 / 身份：只给"这一次"和"不要"。
        !risk.rememberable -> listOf(Consent.ONCE, Consent.DENIED)
        else -> listOf(Consent.ONCE, Consent.ALWAYS, Consent.DENIED)
    }

    /**
     * 判定。
     *
     * @param risk 本次动作的风险
     * @param remembered 该 (能力, 应用) 已记住的答复
     */
    fun decide(risk: RiskLevel, remembered: Consent): ConsentDecision {
        if (!risk.requiresConsent) return ConsentDecision.Allowed
        return when (remembered) {
            Consent.ALWAYS -> ConsentDecision.Allowed
            Consent.DENIED -> ConsentDecision.Blocked
            // ONCE 用完即弃，下一次仍要问 —— 所以走到这里等于没记住。
            Consent.UNASKED, Consent.ONCE -> ConsentDecision.Ask(optionsFor(risk))
        }
    }

    /**
     * 用户的答复如何落库。
     *
     * ★ 返回 null 表示"不要记住"（[Consent.ONCE] / [Consent.DENIED] 的 ONCE 语义）。
     *   注意 [Consent.DENIED] **要记住** —— 用户明确说过不要，
     *   下次还问一遍是骚扰，而且会训练用户条件反射地点"允许"。
     */
    fun toRemember(risk: RiskLevel, answer: Consent): Consent? = when (answer) {
        Consent.ALWAYS -> if (risk.rememberable) Consent.ALWAYS else null
        Consent.DENIED -> Consent.DENIED
        Consent.ONCE, Consent.UNASKED -> null
    }

    /**
     * 询问卡的标题。
     *
     * ★ 必须写出**能力 + 应用**两件事。只写"需要授权"等于什么都没说。
     */
    fun promptTitle(capability: String, app: String): String = "「$capability」想操作「$app」"

    /** 询问卡底部那句"为什么问"。 */
    fun why(risk: RiskLevel): String = when (risk) {
        RiskLevel.READ -> "只读操作，不会改动任何东西"
        RiskLevel.WRITE -> "这一步会真的改动手机上的内容"
        RiskLevel.EXTERNAL -> "这一步会把内容发给别人"
        RiskLevel.MONEY -> "这一步涉及花钱"
        RiskLevel.IDENTITY -> "这一步涉及你的身份信息"
    }
}
