package com.pocketagent.personalogic

/**
 * **表演指导** —— 后台 AI 写的一句话「这一轮该怎么演」。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么它是一句话，不是一段提示词，更不是一组数值
 * ═══════════════════════════════════════════════════════════════
 *
 * 这件事在 `Director.kt` 的类注释里已经为「事件」论证过一次，这里是同一个
 * 问题的另一半：
 *
 * **错的做法**：让后台 AI 每轮重写前台的人格提示词。
 *   ⇒ L0–L2 的**逐字节稳定前缀**立刻失效（KV-cache 全废，每轮贵一倍）；
 *   ⇒ 两套提示词互相矛盾时没人知道该信哪套；
 *   ⇒ 没法离线断言，也没法复现。
 *
 * **错的做法二**：让后台输出 `{ "warmth": 0.2, "brevity": 0.7 }`。
 *   ⇒ 和 [Hormones.toneDelta] 打架（那是确定性算出来的），两份真相；
 *   ⇒ 而且它凭什么比公式更准？公式至少有半衰期和文献在背后。
 *
 * **对的**：只让它写**这一句话怎么落地**——
 *   要不要短、要不要先承认自己不对、要不要反问、要不要提自己在忙。
 *   那是公式算不出来的东西：公式只能给六轴数字，
 *   而「你现在不该追问，该先把话说短」是**场景知识**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条硬约束（都在 [PerformanceGate] 里，不是靠提示词自律）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **不许出现数值与术语** —— 前台模型读到「皮质醇 72」会立刻出戏，
 *    用户也会从语气里感觉到"被测量"。禁词表见 [PerformanceGate.BANNED]。
 * 2. **不许出现元语言** —— 「扮演」「人设」「作为 AI」一旦进入前台提示词，
 *    等于当着演员的面念导演笔记。
 * 3. **不许索取** —— 「你应该安慰我」是 §4.6 明令禁止的情绪操纵，
 *    它比没有指导更糟：那是在教角色勒索用户。
 *
 * @param line 这一句表演指导（已规范化、已过闸）
 * @param atMs 生成时刻
 * @param hormonesKey **生成时的激素签名**。激素没变时不允许换措辞 ——
 *   同一个状态两句话，模型会以为自己被要求"演出变化"，于是越演越夸张。
 */
data class Performance(
    val line: String,
    val atMs: Long,
    val hormonesKey: String,
) {

    /**
     * 是否仍然有效。
     *
     * ⚠️ 有效期刻意短（20 分钟）：表演指导跟着**状态**走，
     *    状态按新半衰期半小时就换了一轮。一条挂一天会变成新的"人设"。
     */
    fun isFresh(nowMs: Long): Boolean {
        val age = nowMs - atMs
        return age in 0..TTL_MS
    }

    companion object {
        /** 有效期：20 分钟。比最短的那条行为半衰期（多巴胺 30 分钟）略短。 */
        const val TTL_MS: Long = 20 * 60 * 1000L

        /**
         * 最长 60 字。
         *
         * 依据：前台 L3 层整段只有两三句，指导比状态本身还长会喧宾夺主 ——
         * 而"提示词里最长的那段最容易被遵守"，会让表演盖过人格。
         */
        const val MAX_CHARS: Int = 60
    }
}

/** [PerformanceGate.accept] 的结果。 */
sealed interface PerformanceVerdict {

    data class Accepted(val performance: Performance) : PerformanceVerdict

    /**
     * 被拒。
     *
     * ⚠️ 拒绝**必须带原因**：静默丢弃等于"后台 AI 在乱写"这件事没人知道
     *    （与 `DirectorGate.droppedEvents` 同一条纪律）。
     */
    data class Rejected(val reason: String) : PerformanceVerdict
}

/**
 * 表演指导的闸门。
 *
 * ⚠️ 它是**纯逻辑**，可离线断言 —— 提示词里的"不许写数字"是请求，
 *    这里的禁词表才是保证。
 */
object PerformanceGate {

    /**
     * 禁词表。
     *
     * ⚠️ 分四类，每一类拒绝的理由不同（都写进 [VerdictRejected] 的原因里）：
     *  · 术语/数值 → 前台会出戏，用户会觉得被测量
     *  · 系统语     → 前台会开始解释机制（"这是因为你的皮质醇…"）
     *  · 元语言     → 演员听到导演念笔记
     *  · 索取       → 情绪操纵（§4.6）
     */
    val BANNED: List<String> = listOf(
        // 术语与数值
        "皮质醇", "多巴胺", "血清素", "催产素", "激素", "数值", "指标", "nmol", "ng/mL",
        // 系统语
        "检测到", "系统", "模型", "提示词", "指令", "参数", "评分", "设定",
        // 元语言
        "扮演", "人设", "角色扮演", "AI", "人工智能", "语言模型", "虚拟",
        // 索取 / 操纵
        "安慰我", "别离开", "不理我", "你必须", "你应该", "应该对我",
    )

    /**
     * 阿拉伯数字（含全角）。
     *
     * ⚠️ 光拦「数值」这个词是不够的 —— 模型写的是 `紧张度 0.3`，
     *    里面既没有"数值"两个字，也确实是一个数。表演指导里没有任何
     *    需要数字的场合（要短、要认错、别追问），所以**一律拒**。
     *    这条让 [BANNED] 上方那句"不许出现数值"从请求变成保证。
     */
    private val DIGITS = Regex("[0-9０-９]")

    /**
     * 规范化 + 过闸。
     *
     * @param raw 模型给的 `performance` 字段（可能 null / 空 / 很长 / 带换行）
     * @param previous 上一轮生效的那条（用于防抖）
     * @param hormonesKey 当前激素签名
     */
    fun accept(
        raw: String?,
        previous: Performance?,
        nowMs: Long,
        hormonesKey: String,
    ): PerformanceVerdict {
        val text = normalize(raw)
            ?: return PerformanceVerdict.Rejected("空")
        if (text.length > Performance.MAX_CHARS) {
            return PerformanceVerdict.Rejected("太长（${text.length} 字 > ${Performance.MAX_CHARS}）")
        }
        BANNED.firstOrNull { text.contains(it) }?.let {
            return PerformanceVerdict.Rejected("含禁词「$it」")
        }
        if (DIGITS.containsMatchIn(text)) {
            return PerformanceVerdict.Rejected("含数字（导演笔记不该带数值）")
        }
        if (previous != null) {
            if (previous.line == text) {
                return PerformanceVerdict.Rejected("与上一轮完全相同")
            }
            // ⚠️ 同一个激素状态不许换措辞 —— 换了它会以为"要演得不一样"，
            //    于是同一件事越演越夸张（这条在真机上最难被发现）。
            if (previous.isFresh(nowMs) && previous.hormonesKey == hormonesKey) {
                return PerformanceVerdict.Rejected("激素没变，沿用上一条")
            }
        }
        return PerformanceVerdict.Accepted(
            Performance(line = text, atMs = nowMs, hormonesKey = hormonesKey),
        )
    }

    /** 折行压成空格、去掉首尾空白与包裹的引号/破折号。空白返回 null。 */
    fun normalize(raw: String?): String? {
        val t = raw
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.replace(Regex("\\s+"), " ")
            // ⚠️ 折行压成空格后，中文逗号后面会留一个空格（"追问， 把话说短"）——
            //    中文排版不留这个空格，而且前台模型会把那句读成"停顿了一下"。
            //    英文句子里的空格不受影响（这里的标点集合只含全角）。
            ?.replace(Regex("\\s*([，。！？、；：…」』）】》])\\s*"), "$1")
            ?.replace(Regex("\\s*([「『（【《])\\s*"), "$1")
            ?.trim()
            ?.trim('"', '\'', '「', '」', '“', '”', '-', '—', '·')
            ?.trim()
        return t?.takeIf { it.isNotEmpty() }
    }
}
