package com.pocketagent.agentlogic

/**
 * 定位消歧策略 —— 候选有多个时该怎么办。
 *
 * ## 为什么需要它（D-AT）
 *
 * 现有 `Grounder` 契约里 `GroundResult.Ambiguous` 是"返回候选列表，
 * 由调用方消歧"，但**调用方是谁、怎么消歧**从来没定义。
 *
 * 未定义的分支必然会各自演化：某处取第一个、某处抛异常、某处问用户 ——
 * 而"取第一个"在列表顺序变化时会**静默点错元素**。
 *
 * ## 判据：置信度差距阈值（D-AT 建议 0.2）
 *
 * | 情形 | 处理 | 理由 |
 * |---|---|---|
 * | 最高分比次高分高 ≥ 0.2 | 自动选第一个 | 差距够大，模型其实已经表达了倾向 |
 * | 差距 < 0.2 | **问用户** | 差距太小，猜错要重来一整步 |
 *
 * > 与项目其它地方一致：**能自动判断的自动判断，不能的明确交给用户，
 * > 不做静默猜测。**
 *
 * ⚠️ 注意"问用户"必须有次数上限 —— 反复问同一个问题会让人弃用。
 * 见 [maxAskBacks]。
 */
class AmbiguityResolver(
    /**
     * 自动选择所需的置信度差距。
     *
     * 0.2 的含义：若候选是 0.85 与 0.60，差 0.25 ≥ 0.2 → 自动选；
     * 若是 0.70 与 0.65，差 0.05 → 问用户。
     */
    private val minConfidenceGap: Float = DEFAULT_MIN_GAP,
    /** 同一个目标最多反问用户几次，超过则转手动引导 */
    private val maxAskBacks: Int = DEFAULT_MAX_ASK_BACKS,
) {

    init {
        require(minConfidenceGap > 0f && minConfidenceGap < 1f) {
            "minConfidenceGap 必须在 (0, 1) 之间，当前为 $minConfidenceGap"
        }
        require(maxAskBacks > 0) { "maxAskBacks 必须为正数，当前为 $maxAskBacks" }
    }

    /** 每个目标已反问的次数 */
    private val askCounts = mutableMapOf<String, Int>()

    /**
     * 决定如何消歧。
     *
     * @param targetDescription 目标描述（作为反问次数的键）
     * @param candidates 候选列表，含置信度与可读标签
     * @return 决策
     */
    fun resolve(targetDescription: String, candidates: List<AmbiguousCandidate>): AmbiguityResolution {
        // 空列表不是"歧义"，是"没找到"。这两者的处理完全不同 ——
        // 空列表能走到这里说明上游的状态映射有误，明确报出来。
        if (candidates.isEmpty()) {
            return AmbiguityResolution.GiveUp("候选列表为空 —— 这不是歧义，上游应当返回 NotFound")
        }

        // 只有一个候选：直接选定。
        // ⚠️ 但仍然要检查置信度下限 —— 一个 0.1 置信度的唯一候选
        //    不值得照做，不如交给用户。
        if (candidates.size == 1) {
            val only = candidates.first()
            return if (only.confidence >= LOW_CONFIDENCE_FLOOR) {
                AmbiguityResolution.PickFirst(
                    chosen = only,
                    reason = "仅有一个候选且置信度 ${fmt(only.confidence)} 可接受",
                )
            } else {
                AmbiguityResolution.GiveUp(
                    "唯一候选的置信度仅 ${fmt(only.confidence)}，低于可接受下限 ${fmt(LOW_CONFIDENCE_FLOOR)}",
                )
            }
        }

        val sorted = candidates.sortedByDescending { it.confidence }
        val top = sorted[0]
        val second = sorted[1]
        val gap = top.confidence - second.confidence

        // ── 差距够大 → 自动选 ─────────────────────────────────
        //
        // ★★ 必须用 [atLeast] 而不是 `gap >= minConfidenceGap`（实测踩过）。
        //
        // Float 减法不等于十进制减法：`0.8f - 0.6f` 得到的不是 `0.2f`，
        // 而是 `0.199999988`（因为 f32 里 `0.8f ≈ 0.8000000119`、
        // `0.6f ≈ 0.6000000238`）。而 `0.2f ≈ 0.2000000030`。
        // 于是 `0.199999988 >= 0.200000003` 是 **false** ——
        // 「差值恰好等于阈值」这个本该自动选的情形，永远落到问用户那边。
        //
        // 这个 bug 的隐蔽之处：**大多数输入都看不出来**。
        // 0.95 与 0.5 差 0.45，稳稳自动选；只有构造出"差正好是阈值"
        // 的数据才暴露，而那恰是测试最该覆盖的边界。
        if (atLeast(gap, minConfidenceGap)) {
            return AmbiguityResolution.PickFirst(
                chosen = top,
                reason = "最高置信度 ${fmt(top.confidence)} 领先次高 ${fmt(second.confidence)} " +
                    "达 ${fmt(gap)}（阈值 ${fmt(minConfidenceGap)}）",
            )
        }

        // ── 差距太小 → 问用户，但要限次 ────────────────────────
        val asked = askCounts[targetDescription] ?: 0
        if (asked >= maxAskBacks) {
            return AmbiguityResolution.GiveUp(
                "已就「$targetDescription」反问用户 $asked 次仍未确定，转手动引导",
            )
        }
        askCounts[targetDescription] = asked + 1

        return AmbiguityResolution.AskUser(
            options = sorted.map { it.label },
            reason = "最高与次高置信度仅差 ${fmt(gap)}（阈值 ${fmt(minConfidenceGap)}），" +
                "差距不足以自动决定",
        )
    }

    /** 用户选定后，清掉该目标的反问计数 */
    fun onUserResolved(targetDescription: String) {
        askCounts.remove(targetDescription)
    }

    /** 已反问过的目标数（诊断用） */
    val pendingAsks: Int get() = askCounts.size

    private fun fmt(v: Float): String = String.format(java.util.Locale.ROOT, "%.2f", v)

    companion object {
        /** D-AT 建议的阈值 */
        const val DEFAULT_MIN_GAP = 0.2f

        /** 同一目标最多反问 2 次。第 3 次就该转手动引导了 */
        const val DEFAULT_MAX_ASK_BACKS = 2

        /**
         * 唯一候选的置信度下限。
         *
         * 0.4 是一个偏低的门槛 —— 定位模型在"只有它一个候选"时
         * 通常也确实是它，过度严格反而会把可用的场景推给用户。
         */
        const val LOW_CONFIDENCE_FLOOR = 0.4f

        /**
         * 浮点比较的容差。
         *
         * 1e-4 远大于 f32 在 0.2 附近的表示误差（约 3e-9），
         * 又远小于任何有实际意义的置信度差距 ——
         * 也就是说它只吸收"十进制上相等、二进制上差一点"的情形，
         * 不会把真正的 0.19 误判成 0.2。
         */
        const val EPSILON = 1e-4f

        /**
         * `a >= b` 的容差版本 —— 用于**必须按十进制语义比较**的地方。
         *
         * ★ 只在"差值恰好等于阈值"这类边界上才有区别，但那正是
         * 判据的分水岭。用裸 `>=` 会让浮点误差决定用户看到的行为。
         */
        fun atLeast(a: Float, b: Float): Boolean = a >= b - EPSILON
    }
}

/** 一个歧义候选 */
data class AmbiguousCandidate(
    /** 可读标签，直接展示给用户（如 `"搜索" 按钮 @(120,88)`） */
    val label: String,
    val confidence: Float,
    /** 元素引用（不透明字符串，由 Android 层解析） */
    val refKey: String,
)

/** 消歧决策 */
sealed interface AmbiguityResolution {

    /** 自动选定 */
    data class PickFirst(val chosen: AmbiguousCandidate, val reason: String) : AmbiguityResolution

    /** 请用户选 */
    data class AskUser(val options: List<String>, val reason: String) : AmbiguityResolution

    /** 放弃，转手动引导（**不是"失败"** —— 见规划文档 D-AU） */
    data class GiveUp(val reason: String) : AmbiguityResolution
}

/**
 * 规划失败的降级策略（D-AU 的落地）。
 *
 * ## 为什么"规划失败"不该是"任务失败"
 *
 * 规划文档 D-AU 的建议是**降级为手动引导**，理由：
 * 原则 5 明确"手动引导是正式产品形态"。
 *
 * 也就是说：模型没规划出来，不等于用户做不成这件事 ——
 * 把任务拆解成一段人看得懂的说明，让用户自己点几下，
 * 这件事仍然是**完成了**的（只是由人执行）。
 *
 * 若直接报"失败"，用户得到的是一个"我什么也没得到"的结果 ——
 * 而他本来只需要点三下就能做完。
 *
 * ⚠️ 这个降级**必须由用户确认**，不能自动跳转 ——
 * 用户可能只是想让 agent 试一下，并不想被打断去做手工操作。
 */
sealed interface PlanFailureFallback {

    /** 重试规划（例如换了个模型、或用户补充了信息） */
    data class Retry(val attempt: Int, val reason: String) : PlanFailureFallback

    /**
     * 降级为手动引导 —— **推荐路径**。
     *
     * @param steps 给人看的步骤说明（自然语言，不是动作枚举）
     */
    data class ManualGuide(val steps: List<String>, val reason: String) : PlanFailureFallback

    /**
     * 真的无法处理（例如指令涉及违规操作、或缺少必要能力且无法手工替代）。
     * ⚠️ 这是最后手段，不应当作为默认。
     */
    data class CannotProceed(val reason: String) : PlanFailureFallback
}
