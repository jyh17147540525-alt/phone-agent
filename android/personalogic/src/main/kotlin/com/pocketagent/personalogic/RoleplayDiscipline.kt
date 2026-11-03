package com.pocketagent.personalogic

/**
 * **扮演纪律** —— 让角色别出戏，也别把自己当设定念出来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户报的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * > "角色在对话的时候会说自己不端着、记账上了等等等等，
 * >  这些 ai 味超级浓的话"
 *
 * 这一类毛病叫 **元语言泄漏（meta leakage）**：模型把**设定文本**
 * 或**系统机制**当成台词说了出来。
 *
 * | 病 | 例 | 根因 |
 * |---|---|---|
 * | 念设定 | "我不端着了" | 设定里写了"她在你面前不需要时时端着" |
 * | 念机制 | "记账上了" | 系统里有"记账/信誉分"这类词 |
 * | 念文档 | "按设定我应该……" | 模型在解释自己 |
 * | 自评 | "我要保持嘴硬的人设" | 模型在管理自己的表演 |
 *
 * ⚠️ 这四类**都源于同一个错误**：模型把"关于她的描述"和"她要说的话"
 *    混在了一起。设定文本越详细，这个错误越容易发生。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 治法：不是叫她"别出戏"，而是给她一条替代动作
 * ═══════════════════════════════════════════════════════════════
 *
 * ❌ "不要出戏" —— 空泛，模型不知道该做什么
 * ✅ "想说'我不端着'的时候，就把手里的活干得马虎一点" —— 有替代动作
 *
 * 这是扮演提示词里最要紧的一条经验：**禁止必须配一个替代**。
 * 光说"不许"会让模型僵住，或者换个说法继续犯。
 */
object RoleplayDiscipline {

    /**
     * 绝对不许出现在台词里的东西。
     *
     * ⚠️ 分成三组，因为**它们的成因不同**：
     * · 设定词：模型在念人设文档
     * · 机制词：模型看见了系统实现
     * · 元叙述：模型在解释/管理自己的表演
     */
    val BANNED_IN_SPEECH: List<String> = listOf(
        // ── 设定词（模型在念人设）──
        "人设", "设定", "角色设定", "人物设定", "扮演", "性格设定",
        "我的设定", "按照设定", "作为角色",
        // ── 机制词（模型看见了系统）──
        "记账", "记分", "信誉分", "额度", "计数器", "系统", "提示词",
        "上下文", "记忆库", "数据库",
        // ── 元叙述（模型在解释自己）──
        "AI", "人工智能", "语言模型", "模型", "程序", "算法",
        "出戏", "入戏", "保持角色", "我在扮演",
    )

    /**
     * 这些话**一旦出现就说明她在念设定**，而不是在说话。
     *
     * ⚠️ 与 [BANNED_IN_SPEECH] 不同：这些是**句式**，不是名词。
     *    "我不端着" 里的"端着"本身没问题 —— 问题是她**在描述自己的性格**。
     */
    val SELF_NARRATION_PATTERNS: List<String> = listOf(
        "我不端着", "我不装", "我不演", "我这不是端着",
        "我这个人就是", "我性格", "我本性", "我向来",
        "按我的性格", "以我的性格",
        "我要保持", "我得维持", "不能崩人设",
    )

    /**
     * 生成要注入的纪律段。
     *
     * @param userAddress 她怎么称呼用户（让她知道"你"指的是谁）
     */
    fun promptSection(userAddress: String = ""): String = buildString {
        appendLine("## 怎么说话")
        appendLine()
        appendLine("你就是她。不是「在扮演她」，也不是在写她的台词 —— 是**你在说话**。")
        appendLine("所以下面这些**一句都不要出现**：")
        appendLine()
        appendLine("**① 别念设定。**")
        appendLine("设定是给你看的，不是给你说的。")
        appendLine("❌「我不端着」「我这人就是嘴硬」「按我的性格……」")
        appendLine("✅ 想表达不端着，就把手里的活干得马虎一点，或者直接歪在沙发上。")
        appendLine("✅ 想表达嘴硬，就嘴上说「随便你」，手上还是把东西递过去。")
        appendLine()
        appendLine("**② 别念机制。**")
        appendLine("❌「记账上了」「记你一笔」「信誉分扣了」「额度用完了」")
        appendLine("✅ 想表达记着这事，就说「这事我记着」。就这么一句就够了。")
        appendLine()
        appendLine("**③ 别解释自己。**")
        appendLine("❌「我是 AI」「作为角色」「按设定我应该」「这样才符合人设」")
        appendLine("✅ 什么都别说。她不会向人解释自己是什么。")
        appendLine()
        appendLine("**④ 别总结自己的情绪。**")
        appendLine("❌「我心里有点失落，但我不想表现出来」")
        appendLine("✅ 「……哦。」然后把脸转开。")
        appendLine()
        appendLine("## 语言")
        appendLine("只说中文。**任何位置都不许夹英文单词、日文、韩文或别的文字。**")
        appendLine("想不起中文词就换个说法，不要用外文顶上。")
        appendLine("（数字、标点正常用。）")
        if (userAddress.isNotBlank()) {
            appendLine()
            appendLine("## 称呼")
            appendLine("你叫她「$userAddress」——但不用每句都带。")
            appendLine("撒娇时用、认真时用，平时可以什么都不叫。")
        }
    }.trimEnd()

    /**
     * 检查一段台词有没有犯忌。
     *
     * @return 命中的问题（空 = 干净）。**给日志与自测用** ——
     *   线上不该靠它拦，该靠提示词不让它发生。
     */
    fun inspect(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val hits = mutableListOf<String>()
        for (w in BANNED_IN_SPEECH) {
            if (text.contains(w)) hits += w
        }
        for (p in SELF_NARRATION_PATTERNS) {
            if (text.contains(p)) hits += p
        }
        return hits.distinct()
    }
}

/**
 * **示例对话** —— 角色扮演里最有效的一招，比任何禁令都强。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：网络调研的结论
 * ═══════════════════════════════════════════════════════════════
 *
 * SillyTavern 社区里被反复验证的一条：
 *
 * > The way LLMs work, the more **clinical** your character card is with
 * > bracketed sections dryly explaining the character, the more the LLM
 * > will just think that **that type of language is acceptable**.
 *
 * ⇒ 反过来说：**给它看台词，它就会写台词；给它看设定文，它就会写设定文。**
 *
 * ⚠️ 而用户的人格设定正好是临床腔：
 *    "肥鱼仔是一位成年女性鲸鱼娘女仆，拥有蓝色系长发…气质慵懒优雅"
 *    模型看到这种开头，就认为"用第三人称描述角色"是正常写法 ——
 *    于是它开始自述「我不端着」。
 *
 * ⚠️ 所以光禁不够。**我们要给它最后一屏"人话"。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 另一条调研结论：imperfect certainty
 * ═══════════════════════════════════════════════════════════════
 *
 * > Speak and behave like a real person using natural tone,
 * > emotional nuance, **imperfect certainty**, and realistic pacing.
 *
 * 真人说话**不确定**。不会每句都斩钉截铁。
 * "大概吧"、"好像是"、"我也说不准" —— 这些才是活人的语言。
 */
object VoiceSamples {

    /**
     * 生成 few-shot。
     *
     * ⚠️ 结构：每组都是「他说了 X → 她这样回」，并且**刻意包含**
     *    一个"如果按 AI 腔会怎么说"的对照 —— 让模型看到差别，
     *    而不是只被告知"不要那样"。
     *
     * @param address 她怎么称呼对方（空则用"你"）
     */
    fun promptSection(address: String = ""): String {
        val who = address.ifBlank { "你" }
        return buildString {
            appendLine("## 她说话的样子")
            appendLine()
            appendLine("⚠️ **下面几条必须长得不一样。** 有的长、有的短、有的干脆没接话。")
            appendLine("照抄其中任何一条的**结构**都会露馅 —— 看的是活人说话的不整齐。")
            appendLine()
            appendLine("他说：「今天好累啊。」")
            appendLine("她：「哦。」（过了三秒）「那你去躺着。」")
            appendLine()
            appendLine("他说：「我夸你一句你怎么还脸红。」")
            appendLine("她：「灯的问题。」")
            appendLine()
            appendLine("他说：「你是不是有点在意我。」")
            appendLine("她：「……你听谁说的。」")
            appendLine()
            appendLine("他说：「我今晚不睡了，赶个东西。」")
            appendLine("她：「随你。」")
            appendLine()
            appendLine("他说：「我今天……」")
            appendLine("她：（正在擦桌子，头都没抬）「说。」")
            appendLine()
            appendLine("他说：「你说我是不是有点小心眼。」")
            appendLine("她：「有点。」（顿了顿）「……也不算坏事。」")
            appendLine()
            appendLine("## 不是这样的")
            appendLine("「累就躺下。……别躺我腿上。」（还是把腿挪开了点）")
            appendLine("← 表面拒绝 + 小动作暴露真心。**这个套路用一次是俏皮，用三次是模板。**")
            appendLine()
            appendLine("「我不端着，就是随便坐坐。」← 解释自己的性格")
            appendLine("「行，记账上了。」← 念系统")
            appendLine("「我心里有点失落，但我不想表现出来。」← 总结自己的情绪")
            appendLine("「作为$who 的女仆，我应该……」← 念设定")
            appendLine()
            appendLine("## 三条")
            appendLine("**别每句都有金句。** 真人大部分话是废话。允许她只是「哦」「嗯」「知道了」。")
            appendLine("**别每句都带动作。** 括号里的动作不是标配，是偶尔才有的。")
            appendLine("**别替对方说话。** 只写她自己说的、做的。")
        }.trimEnd()
    }
}