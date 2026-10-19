package com.pocketagent.agentui

/**
 * 什么时候必须**先问一句**（预演卡），什么时候直接做。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 判据不是"重不重要"，而是"做完能不能反悔"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户不怕助理"做错一件能撤销的事"，怕的是**撤不回来**。
 * 所以预演的唯一理由就是不可逆 + 对外可见 + 花钱 + 涉及身份。
 *
 * ⚠️ 反过来，给每个动作都加一次确认是**最坏的设计**：用户会在第三次之后
 *    条件反射地点"确定"，于是真正危险的那次也照点。确认的价值来自稀缺。
 */
object IntentGate {

    /**
     * 是否必须先预演。
     *
     * @param risk 该动作的风险等级（由工具声明）
     * @param reversible 做完能不能反悔
     */
    fun needsPreflight(risk: RiskLevel, reversible: Boolean): Boolean = when (risk) {
        // 只读：不问。问一次"我要读你的屏幕了"会让整个产品没法用。
        RiskLevel.READ -> false
        // 写本机：能撤销就不问（输入框里的字可以删，闹钟可以取消）。
        RiskLevel.WRITE -> !reversible
        // 对外发送 / 花钱 / 身份：一律问，与能否撤销无关 ——
        // "撤回"在对方已经看到之后就不是撤销了。
        RiskLevel.EXTERNAL, RiskLevel.MONEY, RiskLevel.IDENTITY -> true
    }

    /**
     * 预演卡上必须出现的确认按钮文案。
     *
     * ★ 不用"确定/取消"：那两个词什么也没说。按钮上必须写着**将要发生的事**。
     */
    fun confirmLabel(risk: RiskLevel): String = when (risk) {
        RiskLevel.READ, RiskLevel.WRITE -> "就这样做"
        RiskLevel.EXTERNAL -> "发送"
        RiskLevel.MONEY -> "确认付款"
        RiskLevel.IDENTITY -> "提交"
    }

    /**
     * 预演卡底部那句风险说明。
     *
     * ★ [RiskLevel.MONEY] 与 [RiskLevel.IDENTITY] 必须明说"这一步我不会替你做"，
     *   而不是让用户以为点了确认就真的会付款 —— 红线是"不代付"，
     *   预演卡是最后一道把话说清楚的地方。
     */
    fun caution(risk: RiskLevel, reversible: Boolean): String? = when (risk) {
        RiskLevel.READ -> null
        RiskLevel.WRITE -> if (reversible) null else "这一步做完撤不回来"
        RiskLevel.EXTERNAL -> "发出后无法撤回"
        RiskLevel.MONEY -> "付款需要你自己点最后一步，我只把界面停在这里"
        RiskLevel.IDENTITY -> "身份信息我不会替你提交，需要你自己确认"
    }
}
