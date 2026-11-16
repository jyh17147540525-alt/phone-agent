package com.pocketagent.personalogic

/**
 * **用户自己的设定**（「关于你」那一页填的东西）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ★ 为什么要有这个
 * ══════════════════════════════════════════════════════════════
 *
 * 用户 11-16 的原话：「app 少了一页内容，就是用户自己的设定和习惯这些，
 * 加入这个后能让角色更快地了解用户，不用经过多轮对话才能稍微了解一点用户」。
 *
 * 这句话点出的是一件**结构性**的事：现在的 app 里，角色的人格是用户填的、
 * 角色的身体是用户填的、世界书是用户填的 —— 唯独**对面那个人**（用户自己）
 * 是一张白纸。于是每一段新关系都要从「你是做什么的」开始，
 * 而用户已经回答过很多遍了。
 *
 * ⇒ 所以这一页不是"多一个设置"，是把**已知的那一半**交给角色。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 三条设计约束
 * ══════════════════════════════════════════════════════════════
 *
 * ① **填了才写，空的一条不出现**。
 *    没填「工作」就一个字都不提 —— 不许出现「工作：未填」这种。
 *    角色看到一个"未填"的槽，只会开始盘问，那就正好反了。
 *
 * ② **写成散文短句，不写成条目**。
 *    理由见 [PromptVoice]：提示词的排版也是提示词。一段 `- 工作：产品经理`
 *    会让模型学会用列表回答。这里的每一句都是完整的话。
 *
 * ③ **首尾各一句框定**。
 *    开头「这些是他自己填的」防模型把资料当成"剧情设定"去演；
 *    结尾「这些你已经知道，别再从头问一遍」防它逐条盘问 ——
 *    这一页存在的唯一理由就是省掉那些问题，如果它照着问一遍，等于没做。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 与角色设定的重叠
 * ══════════════════════════════════════════════════════════════
 *
 * 「怎么称呼你」在 [CustomPersona.addressUser] 里已经有一份。
 * 两份都写，模型会收到两个称呼，然后随机挑一个 —— 所以由调用方传
 * [promptSection] 的 `callNameFromCharacter` 进来：**角色那条优先**，
 * 角色已经填了，这里就整行不输出。
 */
data class UserProfile(
    /** 用户希望被怎么叫。与角色的 `addressUser` 重叠时以角色为准。 */
    val callName: String = "",
    /** 只影响这一页用「他」还是「她」。默认「他」—— 全 app 一直是这么称呼用户的。 */
    val gender: UserGender = UserGender.UNSPECIFIED,
    val ageBand: UserAgeBand = UserAgeBand.UNSPECIFIED,
    val livingWith: LivingWith = LivingWith.UNSPECIFIED,
    /** 做什么的（自由文本：职业 / 学业 / 状态）。 */
    val work: String = "",
    /** 最近在忙什么、正在经历什么。这一条最容易让角色"接得上话"。 */
    val nowBusy: String = "",
    val chatStyle: ChatStyle = ChatStyle.UNSPECIFIED,
    val likes: List<String> = emptyList(),
    val dislikes: List<String> = emptyList(),
    /** 别碰的话题。**优先级最高**，预算再紧也不裁。 */
    val taboos: List<String> = emptyList(),
    /** 身体上要知道的（过敏、旧伤、睡不好）。 */
    val bodyNotes: String = "",
    val sleepHour: Int? = null,
    val wakeHour: Int? = null,
    /** 你们之间已经发生过的事（既成事实，不用再问）。 */
    val knownFacts: List<String> = emptyList(),
    /** 想补一句的话。 */
    val extra: String = "",
) {

    /** 一个字段都没填。空档案不进提示词（省几百字，也免得模型对着空白发挥）。 */
    fun isEmpty(): Boolean = promptSection().isBlank()

    /**
     * 拼成一段可以直接注入的提示词。
     *
     * @param callNameFromCharacter 角色设定里已经填的「怎么称呼你」。
     *   非空时这一页的称呼**整行不输出** —— 两份称呼并存，模型会挑错。
     * @param maxChars 预算。超了就按优先级从低往高裁，见 [Line]。
     */
    fun promptSection(
        callNameFromCharacter: String = "",
        maxChars: Int = MAX_CHARS,
    ): String {
        val p = pronoun()
        val lines = mutableListOf<Line>()

        fun add(priority: Int, text: String) {
            if (text.isNotBlank()) lines += Line(priority, text.trim())
        }

        // ── 0：不裁的两条 ────────────────────────────────────────
        // 雷区排最前：说错话的代价比"少知道一点"高得多。
        add(0, taboosLine(p))
        // 身份那一行只有在**含称呼**时才不许裁（称呼说错比少知道年龄难受）；
        // 只剩年龄/同住时它是可以丢的。
        val hasCall = callNameFromCharacter.isBlank() && callName.isNotBlank()
        add(if (hasCall) 0 else 3, identityLine(p, callNameFromCharacter))

        // ── 1：他怎么说话 ────────────────────────────────────────
        add(1, chatStyle.promptLine(p))

        // ── 2：他在做什么 ────────────────────────────────────────
        add(2, labelled("工作", work))
        add(2, labelled("最近", nowBusy))

        // ── 3/4：喜欢与不喜欢 ────────────────────────────────────
        add(4, listLine(p, "喜欢", likes))
        add(4, listLine(p, "不喜欢", dislikes))

        // ── 5：身体 ──────────────────────────────────────────────
        add(5, labelled("身体", bodyNotes))

        // ── 6：作息 ──────────────────────────────────────────────
        add(6, sleepLine(p))

        // ── 7：既成事实 ──────────────────────────────────────────
        add(7, factsLine(knownFacts))

        // ── 8：补充 ──────────────────────────────────────────────
        add(8, extraLine(p, extra))

        if (lines.isEmpty()) return ""
        val kept = trimToBudget(lines, maxChars)
        if (kept.isEmpty()) return ""

        return buildString {
            append("## 关于").append(p).append('\n')
            append('\n')
            append("这些是").append(p).append("自己填的，都是真的。\n")
            kept.forEach { append(it.text).append('\n') }
            append('\n')
            append("这些你已经知道，别再从头问一遍。")
        }
    }

    /**
     * 超预算时反复丢掉**优先级数字最大**的那一行（最后出现的那条）。
     *
     * ⚠️ 从低往高裁，不是"截断到字数"：截断会把一句话切成半句
     *    （「最近：在赶一个上线」），模型会当真。
     *    宁可少一条信息，也不能给一句断了的话。
     */
    private fun trimToBudget(lines: List<Line>, maxChars: Int): List<Line> {
        val kept = lines.toMutableList()
        while (kept.sumOf { it.text.length } > maxChars) {
            val worst = kept.withIndex()
                .filter { it.value.priority > 0 }
                .maxByOrNull { (i, line) -> line.priority * 1_000 + i }
                ?: break
            kept.removeAt(worst.index)
        }
        return kept
    }

    private fun pronoun(): String = when (gender) {
        UserGender.FEMALE -> "她"
        else -> "他"
    }

    private fun identityLine(p: String, fromCharacter: String): String {
        val bits = mutableListOf<String>()
        // 角色那条优先：它已经说了怎么称呼，这里再说一次就是两个答案。
        if (fromCharacter.isBlank() && callName.isNotBlank()) {
            bits += "叫「${callName.trim().take(MAX_CALL_NAME_CHARS)}」"
        }
        ageBand.label.takeIf { it.isNotEmpty() }?.let { bits += it }
        livingWith.label.takeIf { it.isNotEmpty() }?.let { bits += it }
        if (bits.isEmpty()) return ""
        return p + bits.joinToString("，") + "。"
    }

    private fun taboosLine(p: String): String {
        val items = taboos.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ITEMS)
        if (items.isEmpty()) return ""
        // 「别提 A，也别提 B」比「雷区：A、B」更像人话，也不会教出列表格式。
        return "别提" + items.joinToString("，也别提") + "。"
    }

    private fun listLine(p: String, verb: String, items: List<String>): String {
        val clean = items.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ITEMS)
        if (clean.isEmpty()) return ""
        return "$p$verb" + clean.joinToString("、") + "。"
    }

    private fun labelled(label: String, raw: String): String {
        val t = raw.trim().trimEnd('。', '，', '.', ',', '；', ';', ' ')
        if (t.isEmpty()) return ""
        return "$label：${t.take(MAX_FIELD_CHARS)}。"
    }

    private fun sleepLine(p: String): String {
        val s = sleepHour?.coerceIn(0, 23)
        val w = wakeHour?.coerceIn(0, 23)
        return when {
            s != null && w != null -> "${p}一般 $s 点睡，$w 点起。"
            s != null -> "${p}一般 $s 点才睡。"
            w != null -> "${p}一般 $w 点起。"
            else -> ""
        }
    }

    private fun factsLine(items: List<String>): String {
        val clean = items.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_ITEMS)
        if (clean.isEmpty()) return ""
        return "你们之间已经发生过的：" + clean.joinToString("；") + "。"
    }

    private fun extraLine(p: String, raw: String): String {
        val t = raw.trim().trimEnd('。', '，', '.', ',', '；', ';', ' ')
        if (t.isEmpty()) return ""
        return "${p}还想让你知道：${t.take(MAX_FIELD_CHARS)}。"
    }

    /** 一行，带裁剪优先级。数字越小越不能丢。 */
    private data class Line(val priority: Int, val text: String)

    companion object {
        /** 预算。一段完整档案约 300~500 字，900 是"写得很多"的上限。 */
        const val MAX_CHARS: Int = 900

        /** 自由文本单栏上限，防止用户粘一整篇文章进来把预算吃光。 */
        const val MAX_FIELD_CHARS: Int = 120

        /** 列表类（喜欢 / 雷区 / 既成事实）最多几条。 */
        const val MAX_ITEMS: Int = 8

        const val MAX_CALL_NAME_CHARS: Int = 12
    }
}

/** 只影响「他 / 她」。 */
enum class UserGender(val label: String) {
    UNSPECIFIED("未填"),
    MALE("男"),
    FEMALE("女"),
    OTHER("都不算"),
    ;
}

/**
 * 年龄段，不是生日。
 *
 * ⚠️ 刻意不要出生年月：那一页是"让角色了解你"，不是填表办证。
 *    知道「二十几岁」就够它说话，而**具体生日**多一个字段就多一次劝退。
 */
enum class UserAgeBand(val label: String) {
    UNSPECIFIED(""),
    TEENS("十几岁"),
    TWENTIES("二十几岁"),
    THIRTIES("三十几岁"),
    FORTIES("四十几岁"),
    FIFTIES_PLUS("五十岁以上"),
}

enum class LivingWith(val label: String) {
    UNSPECIFIED(""),
    ALONE("一个人住"),
    WITH_FAMILY("和家人住"),
    WITH_PARTNER("和伴侣住"),
    SHARED("和室友合住"),
}

/**
 * 他怎么说话。
 *
 * ★ 这一栏的回报率最高：它决定**每条回复的长度形状**，
 *   而长度是"AI 味"最常见的那一半（见 `HumanText` 的禁令表）。
 */
enum class ChatStyle(val label: String) {
    UNSPECIFIED("未填"),
    TERSE("话少"),
    TALKATIVE("话多"),
    JOKER("爱开玩笑"),
    LISTENER("听得多"),
    ;

    fun promptLine(p: String): String = when (this) {
        UNSPECIFIED -> ""
        TERSE -> "$p 话少，累的时候只回一两个字。你也别追问，更别写长回复。"
        TALKATIVE -> "$p 话多，一件事能讲很久。你可以跟着多聊几句，但别抢他的话。"
        JOKER -> "$p 爱开玩笑，认真话也爱绕着说。你接得住梗就接，接不住就顺着问。"
        LISTENER -> "$p 说得少，但会听。你多说一点没关系，别逼他表态。"
    }
}
