package com.pocketagent.modelrouter

/**
 * 任务难度评估的**本地启发式**结果。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么需要本地评估（有模型分类为什么还要它）
 * ═══════════════════════════════════════════════════════════════
 *
 * 调度模型分类是"主路径"，但它有三个必然会发生的失败场景：
 *
 * 1. **调度模型没配** —— 用户只用单模型模式，但任务仍需要个难度判断
 * 2. **调度模型挂了** —— Key 失效、网络不通、超时
 * 3. **调度返回了垃圾** —— 模型编了个不该有的档位，或输出不是 JSON
 *
 * 这三种情况如果直接"默认用 STANDARD"，那所有任务都变 STANDARD，
 * 调度就退化成了摆设。**本地启发式让"降级"仍然是有信息的降级。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  它的定位：保守、可解释、宁高勿低
 * ═══════════════════════════════════════════════════════════════
 *
 * ⚠️ **本地评估刻意偏保守（宁可判高）**。
 *
 *    判高了：多花点钱，但任务能完成。
 *    判低了：任务失败，用户重试，**花的钱更多**，还搭上时间。
 *
 *    在"省钱"和"办成事"之间，办成事优先 —— 省钱是优化，失败是事故。
 */
data class LocalAssessment(
    val tier: ModelTier,

    /**
     * 判定依据，给用户看的。
     *
     * ⚠️ 必须是**可验证的具体事实**，不能是"感觉这个任务挺难的"。
     *    用户看到"涉及 4 个 App 操作，判为均衡"才有可能说"不对，这个很简单"，
     *    进而去手动指定模型 —— 这是纠错的前提。
     */
    val reasons: List<String>,
) {
    val summary: String get() = reasons.joinToString("；")
}

/**
 * 任务难度评估器（纯本地，零网络）。
 *
 * 输入只有任务描述文本 + 可选的上下文线索。**不看屏幕、不发请求。**
 */
class TaskDifficultyAssessor(
    private val signals: DifficultySignals = DifficultySignals(),
) {

    /**
     * 评估任务难度。
     *
     * @param instruction 用户的原始指令
     * @param context 调用方可提供的额外线索（如"已知需要跨 App"）
     */
    fun assess(
        instruction: String,
        context: TaskContext = TaskContext(),
    ): LocalAssessment {
        val reasons = mutableListOf<String>()
        var score = 0

        val text = instruction.trim()

        // ── 信号 1：长度 ──────────────────────────────────────────
        // 长指令通常意味着更多约束条件。阈值取的是"手打时会觉得多"的长度。
        when {
            text.length >= signals.longInstructionChars -> {
                score += 2
                reasons += "指令较长（${text.length} 字）"
            }
            text.length >= signals.mediumInstructionChars -> {
                score += 1
                reasons += "指令中等长度（${text.length} 字）"
            }
        }

        // ── 信号 2：多步骤连接词 ──────────────────────────────────
        // "先…然后…最后"是跨步骤任务的强信号。
        //
        // ⚠️ 权重 2 而不是 1：出现一个连接词就已表明"这不是单步操作"。
        //    给 1 分时它单独不足以跨过 standardThreshold，
        //    于是"先打开微信，然后发消息"这种明确的两步任务会被判成轻量档。
        val connectorHits = signals.sequencingMarkers.count { text.contains(it) }
        if (connectorHits > 0) {
            score += (connectorHits * 2).coerceAtMost(4)
            reasons += "含 $connectorHits 处多步骤连接词"
        }

        // ── 信号 3：推理类动词 ────────────────────────────────────
        // "分析/比较/总结/规划"这类要求输出思考结果，不是简单执行。
        //
        // ⚠️ 权重刻意给到 2（与其他信号同分），理由是实测发现的：
        //    只给 1 分时，"帮我分析一下这几个数据"（1 个推理词）会被判成
        //    LIGHT 档 —— 而"分析"恰恰是最需要强模型的动词之一。
        //    把一个分析类任务派给最弱的模型，是"判低"，代价是任务失败重试，
        //    比多花钱严重得多（见本文件顶部的保守原则）。
        val reasoningHits = signals.reasoningMarkers.count { text.contains(it) }
        if (reasoningHits > 0) {
            score += (reasoningHits * 2).coerceAtMost(3)
            reasons += "含 $reasoningHits 处推理类要求"
        }

        // ── 信号 4：显式列出的操作数 ──────────────────────────────
        // 序号列表（1. 2. 3.）是明确的"这有多步"
        val enumerated = signals.enumerationPattern.findAll(text).count()
        if (enumerated >= 2) {
            score += 2
            reasons += "显式列出 $enumerated 个步骤"
        }

        // ── 信号 5：跨 App 线索 ──────────────────────────────────
        // 提到多个应用名 / "打开…再打开…"
        val appMentions = signals.appMarkers.count { text.contains(it) }
        if (appMentions >= 3) {
            score += 2
            reasons += "提到 $appMentions 个应用"
        } else if (appMentions == 2) {
            score += 1
            reasons += "提到 2 个应用"
        }

        // ── 信号 6：调用方已知的跨 App 事实 ───────────────────────
        // 这是最可靠的信号 —— 它不是猜的，是感知层已经确认的
        if (context.knownAppCount >= 3) {
            score += 2
            reasons += "已知涉及 ${context.knownAppCount} 个 App"
        } else if (context.knownAppCount == 2) {
            score += 1
            reasons += "已知涉及 2 个 App"
        }

        // ── 信号 7：需要看屏幕 ────────────────────────────────────
        // 要读屏意味着视觉理解，比纯文本处理贵
        if (context.requiresVision) {
            score += 1
            reasons += "需要理解屏幕内容"
        }

        val tier = when {
            score >= signals.heavyThreshold -> ModelTier.HEAVY
            score >= signals.standardThreshold -> ModelTier.STANDARD
            else -> ModelTier.LIGHT
        }

        // ⚠️ 一条依据都没有时，必须说清楚这是"没找到依据"，
        //    而不是让界面显示一个空的 reason 列表让用户困惑
        if (reasons.isEmpty()) {
            reasons += "未发现复杂特征，按默认最省档处理"
        }

        return LocalAssessment(tier = tier, reasons = reasons)
    }
}

/**
 * 评估用到的上下文线索。
 *
 * 全部是**调用方已经知道的事实**，不是本模块去探测的。
 * 这样本模块保持零副作用、零 I/O。
 */
data class TaskContext(
    /** 感知层已确认涉及的 App 数量。0 = 未知 */
    val knownAppCount: Int = 0,

    /** 这个任务是否需要看懂屏幕（截图/无障碍树） */
    val requiresVision: Boolean = false,
)

/**
 * 阈值与关键词表。
 *
 * ⚠️ 抽成数据类不是过度设计 —— 它让这些**魔数可以被测试直接引用**，
 *    而不是散落在断言里。改阈值时只需改一处，测试跟着改一处。
 */
data class DifficultySignals(
    val longInstructionChars: Int = 60,
    val mediumInstructionChars: Int = 25,

    /** 多步骤连接词 */
    val sequencingMarkers: List<String> = listOf(
        "然后", "接着", "之后", "最后", "再", "并且", "同时", "先把", "第一", "第二",
    ),

    /** 推理类动词 */
    val reasoningMarkers: List<String> = listOf(
        "分析", "比较", "总结", "规划", "整理", "统计", "推理", "判断",
        "评价", "为什么", "如何", "建议",
    ),

    /** 形如 "1." "2." "3)" 的序号 */
    val enumerationPattern: Regex = Regex("""(?:^|\s)\d+[.、)]"""),

    /** 跨 App 线索：常见应用名与"打开/切换"类词 */
    val appMarkers: List<String> = listOf(
        "微信", "支付宝", "淘宝", "京东", "美团", "抖音", "微博", "知乎",
        "浏览器", "地图", "日历", "邮件", "相册", "设置",
    ),

    /** 分数达到这里判 HEAVY */
    val heavyThreshold: Int = 5,

    /** 分数达到这里判 STANDARD */
    val standardThreshold: Int = 2,
)
