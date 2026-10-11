package com.pocketagent.memorylogic

/**
 * 反 OOC 防线三：**记忆闸门**（事后，设计稿 §3.3）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么它是四道防线里**最容易被忽略、后果最重**的一道
 * ═══════════════════════════════════════════════════════════════
 *
 * 记忆系统会**放大**错误：一次 OOC 被写进长期记忆，下次它会被当作
 * **先例引用** —— 崩坏就从"这一次说错了"固化成"它本来就是这样的"。
 *
 * ⇒ **OOC 轮次不允许进入长期记忆。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  两道子闸 + 一条不动的顺序
 * ═══════════════════════════════════════════════════════════════
 *
 * | 闸 | 时机 | 结论 |
 * |---|---|---|
 * | [screenTurns] | **提取之前** | 被守卫标记 OOC 的轮次整轮不进提取 |
 * | [screenAtoms] | 落库之前 | 与人设矛盾的候选原子拒绝落库 |
 * | 隐私过滤顺序 | —— | **照旧**：卸载前过 `PrivacyFilter`（红线，本闸不改它） |
 *
 * ⚠️ 第一道闸必须是**先于提取**，不是"提取完再删"：
 * - 提取是有成本的（一次模型调用），先挡掉就不必付这份钱；
 * - 更重要的：**让模型看见这些内容本身就有风险** —— 它会以"这是要记住的事"
 *   的框架去读它们，进而可能提取出更多变形的版本。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 第二道闸是**窄的**，这不是缺陷
 * ═══════════════════════════════════════════════════════════════
 *
 * "候选事实与人设矛盾"是个模糊判断。这里**只做能确定的那一部分**
 * （规则化的矛盾，由 [PersonaContradictionPort] 注入），
 * 其余交给体检的人工对照（§3.4）。
 *
 * 做一个"什么都想拦"的智能矛盾检测，结果是**误伤**：用户正常的一句偏好
 * 被系统判成"和人设矛盾"而静默丢弃 —— 那比不拦更糟（用户永远不知道
 * 它为什么没记住）。宁可窄而准，并在体检里如实说明覆盖范围。
 */
class MemoryGate(
    /**
     * 人设矛盾端口。默认不检查（[PersonaContradictionPort.None]）——
     * ⚠️ **默认"不检查"而不是"默认放行一切"**：两者结果相同但含义不同，
     * 默认实现写在端口里、注释说明它没做检查，避免"忘了注入"被当成"检查过了"。
     */
    private val personaContradiction: PersonaContradictionPort = PersonaContradictionPort.None,
) {

    /**
     * 第一道闸：提取**之前**筛轮次。
     *
     * @param turns 待提取的轮次（保持原顺序）
     * @param oocTurnIds 被守卫标记为破环的轮次 id（来自 `:personalogic` 的 `GuardLedger`）
     * @return 放行的轮次 + 被挡下的轮次及原因。**两者都要交给调用方**
     *   —— 只报"放行了几条"等于把闸门做成不可观测的。
     */
    fun screenTurns(turns: List<Turn>, oocTurnIds: Set<String>): TurnGateResult {
        val admitted = mutableListOf<Turn>()
        val excluded = mutableListOf<ExcludedTurn>()
        for (turn in turns) {
            if (turn.id in oocTurnIds) {
                excluded += ExcludedTurn(turn.id, ExclusionReason.GUARD_FLAGGED_OOC)
            } else {
                admitted += turn
            }
        }
        return TurnGateResult(admitted = admitted, excluded = excluded)
    }

    /**
     * 第二道闸：落库**之前**筛原子。
     *
     * ⚠️ 只筛不改：被拒的原子原样返回在 [AtomGateResult.rejected] 里，
     *    不在本方法里"顺手改一句让它能过"—— 改过的记忆不是用户的记忆。
     */
    fun screenAtoms(atoms: List<Atom>): AtomGateResult {
        val admitted = mutableListOf<Atom>()
        val rejected = mutableListOf<RejectedAtom>()
        for (atom in atoms) {
            val reason = personaContradiction.conflictingReason(atom.text)
            if (reason == null) {
                admitted += atom
            } else {
                rejected += RejectedAtom(atom, reason)
            }
        }
        return AtomGateResult(admitted = admitted, rejected = rejected)
    }
}

/** 被挡下的轮次。 */
data class ExcludedTurn(val turnId: String, val reason: ExclusionReason)

/** 轮次被挡的原因。**每一类都要能对用户解释** —— 闸门不能是黑箱。 */
enum class ExclusionReason(val label: String) {
    /** 这一轮被输出守卫标记为破环（改了一次仍不达标）。 */
    GUARD_FLAGGED_OOC("这一轮的回答没有保持住设定，不进入长期记忆"),
}

/** 第一道闸的结果。 */
data class TurnGateResult(
    val admitted: List<Turn>,
    val excluded: List<ExcludedTurn>,
)

/** 被拒的原子 + 可展示的理由。 */
data class RejectedAtom(val atom: Atom, val reason: String)

/** 第二道闸的结果。 */
data class AtomGateResult(
    val admitted: List<Atom>,
    val rejected: List<RejectedAtom>,
)

/**
 * 人设矛盾端口 —— 由 `:app` 接上 `:personalogic` 的语言指纹/人格卡。
 *
 * ⚠️ 为什么是端口而不是直接依赖 `:personalogic`：
 *    `:memorylogic` 与 `:personalogic` 都是纯 Kotlin，技术上可以互相依赖 ——
 *    但那样会让"记忆层"依赖"人格层"，而**人格层的微调证据源恰恰来自记忆层**
 *    （L3 画像只认 PREFERENCE）。两个模块互相依赖会成环，Gradle 直接拒绝，
 *    而且它反映的是一个真实的设计问题：**它们只能通过端口单向相认**。
 */
fun interface PersonaContradictionPort {

    /**
     * @return 非 null 表示该文本与人设矛盾，附**可展示给用户**的理由；
     *   null 表示未发现矛盾。**不得抛异常** —— 判定引擎出错时应当向
     *   "不拦"的方向倒（记忆宁可多记，也不要因为引擎故障把用户的记忆全丢）。
     */
    fun conflictingReason(text: String): String?

    companion object {
        /** 什么都不检查。**显式命名**，免得被当成"检查过了"。 */
        val None = PersonaContradictionPort { null }
    }
}
