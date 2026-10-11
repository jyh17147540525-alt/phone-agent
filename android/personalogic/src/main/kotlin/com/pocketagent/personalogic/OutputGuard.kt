package com.pocketagent.personalogic

/**
 * 反 OOC 防线二：**输出守卫**（事中，设计稿 §3.2）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  判定流程
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * 模型输出 → 规则校验 ──确定性命中──→ 附「修正指令」重写（仅一次）──仍命中──→ 放行 + 记录
 *                     └──未命中────→ 正常输出
 *                     └──统计性命中─→ 放行 + 记录（只提示，不重写）
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  三条纪律（设计稿 §3.2，逐条都有测试）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **只重写一次。** 无限重试会把延迟推向不可用，而第二次仍失败说明问题
 *    不在措辞（可能是模型挡不住）—— 放行并记录，交给体检暴露。
 * 2. **每条规则必须配负样本。** 误伤比漏网更伤体验：漏网只是没拦住，
 *    误伤是正常的话被系统改掉，而用户看得见。
 * 3. **规则宁少勿滥。** 能确定性检查的才进"重写"档；统计性的只记录。
 *    「幽默不对味」这种进不了规则，进体检的人工对照（§3.4）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 修正指令里**不回显违规原文**
 * ═══════════════════════════════════════════════════════════════
 *
 * 两个理由，都不是洁癖：
 * - 把违规内容抄进指令 = 让模型**再看一遍**那个说法，有把它复述回去的风险；
 * - 这段指令可能进日志/审计，回显等于制造一条内容外泄路径
 *   （同 `screen_read` 拒绝文案的纪律）。
 *
 * 所以指令只说"哪一类规则被触犯了"，由模型自己去改。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 本类**无状态**
 * ═══════════════════════════════════════════════════════════════
 *
 * 「已经重写过几次」由调用方显式传入（[inspect] 的 `rewritesSoFar`），
 * 不用内部计数器。理由：内部计数会让"这一轮的次数"与"跨轮的次数"混在一起，
 * 而它们含义完全不同 —— 前者是"这次没改好"，后者是"这个模型一直改不好"。
 */
class OutputGuard(
    /** 最多重写几次。默认 1（设计稿的硬规定）；调大它等于放弃延迟。 */
    private val maxRewrites: Int = DEFAULT_MAX_REWRITES,
) {

    init {
        require(maxRewrites >= 0) { "maxRewrites 不能为负：$maxRewrites" }
    }

    /**
     * 检查一段模型输出。
     *
     * @param rewritesSoFar 本轮**已经**重写过几次。调用方维护 —— 见类注释。
     * @return 见 [GuardDecision]。
     */
    fun inspect(
        text: String,
        fingerprint: LanguageFingerprint,
        rewritesSoFar: Int = 0,
    ): GuardDecision {
        val violations = fingerprint.audit(text)
        if (violations.isEmpty()) return GuardDecision.Pass

        val strong = violations.filter { it.rule.strength == RuleStrength.STRONG }

        // 只有统计性命中：记录，但**不重写**。为句长波动去改一句正常的话，
        // 是把"说清楚"改成"说很短" —— 误伤且可见。
        if (strong.isEmpty()) return GuardDecision.PassThrough(violations)

        // 确定性命中，且还有重写额度 → 给一次修正指令
        if (rewritesSoFar < maxRewrites) {
            return GuardDecision.Rewrite(
                instruction = buildInstruction(strong),
                violations = violations,
            )
        }

        // 额度用尽仍然命中 → **放行 + 记录**（纪律 1）。
        // 不放行的后果是把一轮对话卡死在一个改不好的句子上。
        return GuardDecision.PassThrough(violations)
    }

    /**
     * 构造修正指令。
     *
     * ⚠️ 措辞里必须包含「不要提到这条要求本身」—— 否则模型常常会在改写后
     * 加一句「（已按你的要求调整）」，而那本身又是一次破环（它在跟用户
     * 讨论自己的设定，这正是不像人的地方）。
     */
    private fun buildInstruction(strong: List<FingerprintViolation>): String {
        val kinds = strong.map { it.rule.label }.distinct()
        return "刚才那句话需要改一下：${kinds.joinToString("；")}。" +
            "请只改写措辞，保持原意不变，不要提到这条要求本身。"
    }

    companion object {
        /** 默认重写上限。**1 是硬规定**，见类注释纪律 1。 */
        const val DEFAULT_MAX_REWRITES = 1
    }
}

/**
 * 守卫的判定结果。
 *
 * ⚠️ [PassThrough] 与 [Pass] 必须分开：前者是"有问题但放行了"，
 *    后者是"没问题"。合并成一个会让体检看不出"这个模型经常改不好" ——
 *    而那正是体检最该暴露的东西。
 */
sealed interface GuardDecision {

    /** 干净，正常输出。 */
    data object Pass : GuardDecision

    /**
     * 需要重写一次。`instruction` 可以直接作为追加的用户消息发给模型。
     *
     * ⚠️ [violations] 只用于**本地记录**，不得回显给模型（见类注释）。
     */
    data class Rewrite(
        val instruction: String,
        val violations: List<FingerprintViolation>,
    ) : GuardDecision

    /** 有问题但放行（统计性命中，或重写额度已用尽）。**必须记录**。 */
    data class PassThrough(val violations: List<FingerprintViolation>) : GuardDecision

    /** 是否触发了破环（用于标记轮次，进而挡住记忆闸门 §3.3）。 */
    val isOoc: Boolean
        get() = when (this) {
            is Pass -> false
            // 重写中：还没定论，等重写后的那一遍再看 —— 此刻不算破环。
            is Rewrite -> false
            // 放行但已知有问题：这一轮就是破环轮。
            is PassThrough -> violations.any { it.rule.strength == RuleStrength.STRONG }
        }
}

/**
 * 守卫的逐轮记录 —— **记忆闸门（§3.3）的输入**。
 *
 * 「哪几轮破环了」这件事必须被记下来：破环轮次不允许进入长期记忆，
 * 否则一次破环会被下次当成先例引用，崩坏被固化成新常态。
 *
 * ⚠️ 记的是 **turnId**，不是文本 —— 体检与闸门都只需要 id
 *    （要内容时按 id 回 L0 取），抄一份文本进来等于开了第二个真相来源。
 */
class GuardLedger {

    private val flagged = LinkedHashSet<String>()
    private var checks = 0
    private var rewrites = 0

    /**
     * 记一次守卫结果。
     *
     * @param turnId 这一轮 assistant 输出的轮次 id
     * @param decision 守卫结论
     * @return 这一轮是否被标记为破环（供调用方即时决策）
     */
    @Synchronized
    fun record(turnId: String, decision: GuardDecision): Boolean {
        require(turnId.isNotBlank()) { "turnId 不能为空 —— 闸门按它挡轮次" }
        checks += 1
        if (decision is GuardDecision.Rewrite) rewrites += 1
        if (decision.isOoc) {
            flagged += turnId
            return true
        }
        return false
    }

    /** 被标记为破环的轮次 id（**喂给 `:memorylogic` 的 `MemoryGate`**）。 */
    @Synchronized
    fun oocTurnIds(): Set<String> = flagged.toSet()

    /** 体检计数：检查过几轮 / 触发过几次重写 / 破环几轮。 */
    @Synchronized
    fun stats(): GuardStats = GuardStats(
        checkedTurns = checks,
        rewriteTriggers = rewrites,
        oocTurns = flagged.size,
    )
}

/** 守卫的累计计数（进体检报告 §3.4）。 */
data class GuardStats(
    val checkedTurns: Int,
    val rewriteTriggers: Int,
    val oocTurns: Int,
) {
    /** 破环率。检查过 0 轮时返回 null —— **不返回 0.0**（"没看过"不等于"完美"）。 */
    val oocRatio: Double?
        get() = if (checkedTurns == 0) null else oocTurns.toDouble() / checkedTurns
}
