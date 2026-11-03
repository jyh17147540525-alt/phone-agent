package com.pocketagent.personalogic

/**
 * **要不要清洗这条记忆** —— 先判断价值，再决定花不花模型的钱。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户要求
 * ═══════════════════════════════════════════════════════════════
 *
 * > "那些日常的对话，没有价值，未来不会被记住的会话不需要清洗，
 * >  其他的才需要清洗"
 *
 * ⚠️ 这一层不是"省钱优化"，是**正确性问题**：
 *   把「嗯」「在吗」送去给模型"洗"，模型要么原样返回（白花钱），
 *   要么**给它加上不存在的意味**（"嗯"被改成"嗯……我知道你在想什么"）。
 *   后者是**给记忆注水** —— 比不清洗更糟。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 判据：三层
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 分层权重**（决定下限）
 *   约束永远要洗；事实基本不用。
 *
 * **② 内容信号**（决定加减分）
 *   含设定标记 / 关系断言 / 具体事实 → 加分
 *   纯应承 / 太短 / 纯问候 → 减分
 *
 * **③ 阈值**
 *   低于 [THRESHOLD] 就不洗，**但要记下跳过的原因**
 *   —— 静默跳过等于"清洗没生效"却没人知道。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 宁可漏，不要滥
 * ═══════════════════════════════════════════════════════════════
 *
 * 判断"这句有价值"相对安全（最多是多花一次调用）。
 * 判断"这句没价值"**有风险**：漏洗一条真正与设定冲突的话，
 * 它就会一直污染上下文。
 *
 * ⇒ 所以：
 * · 减分只对**明确无内容**的形态（「嗯」「在吗」「哈哈哈」）
 * · 只要带一点实质内容（长度 ≥ [MIN_SUBSTANTIVE_CHARS]），就不判死
 * · 约束/偏好**永远不判死**
 */
object MemoryCleaning {

    /** 低于这个分数就不清洗。 */
    const val THRESHOLD = 30

    /** 短于这个长度视为"没有实质内容"。 */
    const val MIN_SUBSTANTIVE_CHARS = 6

    /** 一句记忆的价值评估。 */
    data class Assessment(
        val score: Int,
        /** 值不值得洗 */
        val worth: Boolean,
        /** 为什么（给日志用）。**空 = 靠分层权重就够了** */
        val reasons: List<String>,
    )

    /**
     * 纯应承 / 纯语气 —— 这些**永远不值得**洗。
     *
     * ⚠️ 用"整句就是这些"而不是"含这些"：
     *    "嗯，不过我觉得你变了" 是有内容的，不能被判死。
     */
    private val FILLER = setOf(
        "嗯", "哦", "噢", "喔", "啊", "哈", "嘿", "诶", "唉",
        "好的", "好", "行", "可以", "收到", "知道了", "明白", "懂了",
        "哈哈", "哈哈哈", "呵呵", "嘻嘻", "嗯嗯", "哦哦",
        "在", "在吗", "在么", "在不在", "你好", "早安", "晚安", "早安呀",
    )

    /** 关系 / 身份断言 —— 最容易与设定冲突的地方，**加分最多**。 */
    private val IDENTITY_MARKERS = listOf(
        "我是", "我叫", "我是你的", "你是我的", "我们", "咱俩",
        "我是你", "你是我", "叫我", "叫我做", "算是", "关系",
        "青梅竹马", "恋人", "朋友", "同事", "上司", "下属", "兄妹", "姐弟",
    )

    /**
     * **关系变化的信号** —— 这类话最容易与设定冲突，必须洗。
     *
     * ★ 2026-11-03 测试抓到：`「嗯，不过我觉得你变了，跟以前不太一样」`
     *   原本判成"不值得洗"（分数 20 < 30），因为：
     *   · 不含旧名字
     *   · 不含"我是/我叫"这类身份断言
     *   · 太短，事实痕迹不够
     *
     * ⚠️ 但它恰恰是**最该洗的那一类** —— "你变了""跟以前不一样"
     *   直接指向关系与设定。漏洗它，下一轮召回出来就会自我矛盾。
     */
    private val RELATION_SHIFT_MARKERS = listOf(
        "变了", "不一样", "以前", "从前", "最近你", "不像你", "不像他",
        "你以前", "你总是", "你从来", "越来越", "不太对", "怪怪的",
    )

    /** 具体事实的痕迹 —— 有数字/时间/地点就多半有信息量。 */
    private val FACT_MARKERS = listOf(
        "点", "号", "日", "月", "年", "天", "岁", "次", "个", "家", "路",
        "住", "在", "去", "来", "买", "做", "吃", "喝", "睡",
    )

    /**
     * 评估一条记忆该不该洗。
     *
     * @param text 文本
     * @param tier 它属于哪一层
     * @param settingMarkers 旧设定独有的词（含这些**一定要洗**）
     */
    fun assess(
        text: String,
        tier: MemoryTier,
        settingMarkers: List<String> = emptyList(),
    ): Assessment {
        val t = text.trim()
        val reasons = mutableListOf<String>()

        // ── ① 分层权重：这是**下限** ────────────────────────
        var score = when (tier) {
            // 约束改错会直接造成伤害 —— 永远要洗
            MemoryTier.CONSTRAINT -> 100
            // 偏好是"你是谁"的证据
            MemoryTier.PREFERENCE -> 60
            MemoryTier.DIALOGUE -> 20
            MemoryTier.STAGE_CONCLUSION -> 15
            MemoryTier.FACT -> 5
        }
        if (tier == MemoryTier.CONSTRAINT) {
            reasons += "约束类，改错会直接造成伤害"
            return Assessment(100, worth = true, reasons = reasons)
        }
        if (tier == MemoryTier.PREFERENCE && t.length >= MIN_SUBSTANTIVE_CHARS) {
            reasons += "偏好类且够长"
            return Assessment(score, worth = true, reasons = reasons)
        }

        // ── ② 空内容形态：直接判死 ──────────────────────────
        if (t.isEmpty()) {
            return Assessment(0, worth = false, reasons = listOf("空文本"))
        }
        val stripped = t.replace(Regex("[\\s，。！？、,.!?~…\\-—]"), "")
        if (stripped in FILLER) {
            return Assessment(0, worth = false, reasons = listOf("纯应承/语气，没有内容"))
        }
        if (stripped.length < 3) {
            return Assessment(0, worth = false, reasons = listOf("太短（${stripped.length} 字）"))
        }

        // ── ③ 内容信号 ──────────────────────────────────────
        val hitSetting = settingMarkers.firstOrNull { it.isNotBlank() && t.contains(it) }
        if (hitSetting != null) {
            score += 40
            reasons += "含旧设定「$hitSetting」"
        }

        if (IDENTITY_MARKERS.any { t.contains(it) }) {
            score += 25
            reasons += "含关系/身份断言"
        }

        // ★ 关系变化信号：最容易与设定冲突的一类
        val shiftHit = RELATION_SHIFT_MARKERS.firstOrNull { t.contains(it) }
        if (shiftHit != null) {
            score += 25
            reasons += "含关系变化信号「$shiftHit」"
        }

        // 具体事实：命中 ≥2 个不同标记才算，避免"点"这种字误伤
        val factHits = FACT_MARKERS.count { t.contains(it) }
        if (factHits >= 2) {
            score += 15
            reasons += "含具体事实痕迹（$factHits 个）"
        }

        if (t.length >= 20) {
            score += 15
            reasons += "长度足够（${t.length} 字）"
        } else if (t.length < MIN_SUBSTANTIVE_CHARS) {
            score -= 10
            reasons += "偏短（${t.length} 字）"
        }

        // ── ④ 阈值 ──────────────────────────────────────────
        val worth = score >= THRESHOLD
        if (!worth) {
            reasons += "低于阈值 $THRESHOLD，不值得花一次调用"
        }
        return Assessment(score, worth, reasons)
    }
}
