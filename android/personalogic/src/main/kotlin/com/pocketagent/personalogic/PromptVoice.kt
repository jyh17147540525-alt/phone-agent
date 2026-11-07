package com.pocketagent.personalogic

/**
 * **提示词自己的文风**检查器。
 *
 * ══════════════════════════════════════════════════════════════
 *  ★ 为什么需要这个东西
 * ══════════════════════════════════════════════════════════════
 *
 * 用户 11-06 的原话：「你的提示词也不能有 ai 味注入」。
 *
 * 这句听着像洁癖，其实是机制问题。我们往 system prompt 里塞的每一段，
 * 对模型来说都是**同语言、同体裁、就在眼前的文本**。它没法执行"照着做、
 * 但别学我的写法"这种切分 —— 它只会从眼前的文本里取节奏。
 *
 * 于是「- 不要每句都完整」这种句子有两层效果：
 *   ① 内容层：告诉它别写太完整；
 *   ② 形式层：用一个**列表符号**示范"回答长这样"。
 * 第二层和第一层打架，而形式层通常赢 —— 因为形式是逐字的、可模仿的，
 * 内容是抽象的、要理解的。
 *
 * ⇒ 结论：**提示词的排版也是提示词。**
 *
 * 这条不是我的猜测，见 `docs\工程\提示词文风-学术依据-2026-11-06.md`：
 *   · LLM 与人在语法/修辞层面有系统性差异（PNAS 2025, 10.1073/pnas.2422455122）
 *   · 从少量样本模仿**非正式**个人风格，恰恰是模型最弱的一档
 *     （EMNLP 2025 Findings, arXiv:2509.14543：news/email 能模仿，blog/forum 不行）
 *   · 提示词的措辞会改变输出的分布（arXiv:2507.21083，情感框架实验）
 *
 * 所以聊天场景（= 非正式）是**最难的一档**，也最禁不起提示词再往里加噪音。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 这个检查器只查**形式**，不查内容
 * ══════════════════════════════════════════════════════════════
 *
 * 它说不出"这段话写得好不好"，只能说"这段话里有几处会被照抄的形状"。
 * 就像 [HumanScore] 说不出"这句话有没有灵魂"，只能数它有没有分点。
 *
 * 两者的区别在于：**这里是给作者用的**（改提示词的人），
 * 不是给模型用的 —— 没人会把检查结果塞回 prompt（那又是一段元话语）。
 */
object PromptVoice {

    /** 有多容易被照抄。 */
    enum class Level(val label: String) {
        /** 直接教坏输出格式：模型看到列表就写列表。 */
        LOUD("会教坏格式"),

        /** 传染腔调：不改变排版，但把语气带偏。 */
        TONE("会传染腔调"),
    }

    /**
     * 九种记号。
     *
     * ⚠️ 每条都必须**能指出原文里的具体位置**（[Finding.evidence]），
     *    否则就成了"我觉得有点 AI 味"—— 那种意见没法改，也没法测。
     */
    enum class Code(val label: String, val level: Level) {
        /** 行首 `- ` `* ` `• ` `1. ` `1、`。**最严重的一条**：它直接示范"回答是列表"。 */
        BULLET("列表符号", Level.LOUD),

        /** `**加粗**`。加粗是"我在强调"的姿态，人聊天不用。 */
        BOLD("加粗", Level.LOUD),

        /** `—` / `——` / `--`。破折号是英文写作的补充节奏，中文口语里几乎不出现。 */
        EMDASH("破折号", Level.LOUD),

        /** emoji。提示词带 emoji，回话就带 emoji。 */
        EMOJI("表情符号", Level.LOUD),

        /** 「不是 A，而是 B」/「并非 A，是 B」。模型最爱的对偶修辞，一句就露馅。 */
        NOT_BUT("不是…是…对偶", Level.TONE),

        /** 三个并列项都以 性/感/度/力/化 结尾 ——「连续性、一致性、情境感」那种。 */
        TRICOLON("抽象三连", Level.TONE),

        /** 「这一段」「以下是」「换句话说」「请注意」「务必」。对模型说话不需要这些。 */
        METATALK("元话语", Level.TONE),

        /** 「关键在于」「这才是」「本质上」「越…越…」。空口号，没信息量。 */
        SLOGAN("口号", Level.TONE),

        /** 连续两行长度几乎一样、结尾标点相同 —— 排比。 */
        PARALLEL("排比行", Level.TONE),
    }

    /**
     * 一条命中。
     *
     * @property evidence 原文片段（**只截取记号附近**，整段塞进日志没意义）
     * @property hint 怎么改。用**动作**描述，不用形容词（"删掉"能执行，"更自然"不能）
     */
    data class Finding(val code: Code, val evidence: String, val hint: String)

    /**
     * 一段提示词的体检结果。
     *
     * @property findings 空 = 干净
     */
    data class Report(val findings: List<Finding>) {
        val loud: Int get() = findings.count { it.code.level == Level.LOUD }
        val tone: Int get() = findings.size - loud
        val isClean: Boolean get() = findings.isEmpty()

        /** 一句话日志。**不带原文** —— 提示词内容不进 logcat（同 AppContainer 的纪律）。 */
        fun logLine(): String =
            if (isClean) "提示词文风：干净"
            else "提示词文风：%d 处会教坏格式，%d 处会传染腔调（%s）".format(
                loud,
                tone,
                findings.map { it.code.label }.distinct().joinToString("、"),
            )

        /** 按严重级排好、便于逐条改。 */
        fun breakdown(): String = findings
            .sortedBy { if (it.code.level == Level.LOUD) 0 else 1 }
            .joinToString("\n") { "· ${it.code.label}「${it.evidence}」→ ${it.hint}" }
    }

    // ──────────────────────────────────────────────────────────────
    //  词表与正则
    // ──────────────────────────────────────────────────────────────

    /**
     * 行首列表符号。`1.` / `1、` / `1)` 都要抓：编号列表和项目列表对模型是一回事。
     *
     * ⚠️ 匹配到**行尾**，不是只匹配符号 + 一个字 —— 证据要能让人一眼看出是哪一条。
     *    行首缩进只吃空格与制表符，不能吃 `\s`（那会把换行也吃进去，跨行匹配）。
     */
    private val BULLET_LINE = Regex("""(?m)^[ \t]*(?:[-*•·]|\d+[.、)])\s+\S.*$""")

    /** `**…**` 或 `__…__`。 */
    private val BOLD_MARK = Regex("""\*\*[^*\n]{1,40}\*\*""")

    /**
     * 破折号。
     *
     * ⚠️ 只抓**成对出现**的中文破折号（`——`）与英文 `—`，
     *    不抓单个 `-`（负号、连字符、`-Pandroid` 这类命令行片段会误报）。
     */
    private val DECORATIVE_DASH = Regex("""——|—|--(?=\s|$)""")

    /** 「不是 A，是/而是 B」，「并非 A，而是 B」。 */
    private val NOT_BUT = Regex("""(?:不是|并非)[^。！？\n]{1,14}?[，,]\s*(?:而是|是)""")

    /** 抽象后缀。用来识别「连续性、一致性、情境感」这类三连。 */
    private const val ABSTRACT_TAILS = "性感度力化"

    /** 元话语。对模型讲话时这些词全都可以删。 */
    private val METATALK_MARKS = listOf(
        "这一段", "本文", "本节", "以下", "上面", "下面", "换句话说", "也就是说",
        "需要说明", "需要指出", "请注意", "请记住", "务必", "切记", "值得注意",
        "首先要", "其次", "最后要",
    )

    /** 空口号。删掉之后一句信息都不少。 */
    private val SLOGAN_MARKS = listOf(
        "关键在于", "核心是", "本质上是", "这才是", "真正做到", "不仅仅是", "从而", "进而",
    )

    // ──────────────────────────────────────────────────────────────
    //  API
    // ──────────────────────────────────────────────────────────────

    /**
     * 体检一段提示词。
     *
     * @param section 一段**要注入**的文本（不是注释、不是文档）
     */
    fun audit(section: String): Report {
        if (section.isBlank()) return Report(emptyList())
        val findings = mutableListOf<Finding>()

        BULLET_LINE.find(section)?.let {
            findings += Finding(
                Code.BULLET,
                it.value.trim(),
                "删掉行首符号。换行本身就够分隔了。",
            )
        }

        BOLD_MARK.find(section)?.let {
            findings += Finding(Code.BOLD, it.value, "去掉星号。要强调就换个说法。")
        }

        DECORATIVE_DASH.find(section)?.let {
            findings += Finding(
                Code.EMDASH,
                context(section, it.range.first),
                "拆成两句，或者用一个逗号。",
            )
        }

        firstEmoji(section)?.let {
            findings += Finding(Code.EMOJI, it, "删掉。")
        }

        NOT_BUT.find(section)?.let {
            findings += Finding(
                Code.NOT_BUT,
                it.value,
                "只留后面那半句。否定前半句反而在提醒它。",
            )
        }

        tricolon(section)?.let {
            findings += Finding(Code.TRICOLON, it, "挑一个最要紧的写，其余删掉。")
        }

        firstMark(section, METATALK_MARKS)?.let {
            findings += Finding(Code.METATALK, it, "直接说要什么，不用报幕。")
        }

        firstMark(section, SLOGAN_MARKS)?.let {
            findings += Finding(Code.SLOGAN, it, "换成一件具体的事。")
        }

        parallelLine(section)?.let {
            findings += Finding(Code.PARALLEL, it, "把其中一行改短，或者并成一行。")
        }

        return Report(findings)
    }

    /**
     * 一次体检多段，返回"段名 → 结果"。
     *
     * ★ 给测试用：把生产里所有会进 system prompt 的段落摆成一张表，
     *   一次扫完。新加段落忘了登记，等于没被保护 —— 所以这张表
     *   写在测试里，而不是写在生产代码里。
     */
    fun auditAll(sections: Map<String, String>): Map<String, Report> =
        sections.mapValues { audit(it.value) }

    /**
     * 三连：≥3 个 `、` 分隔项，且**至少三项**以抽象后缀结尾。
     *
     * ⚠️ 第一个并列项通常粘着前面的句子（「角色要有连续性、一致性、情境感」），
     *    长度会超过一个词的量级。所以超长项只看**尾巴三个字**再判后缀 ——
     *    否则真三连会因为第一项太长而漏掉（这一条被测试钉着）。
     */
    private fun tricolon(section: String): String? {
        for (segment in section.split('。', '，', '\n', '；')) {
            val items = segment.split('、').map { it.trim() }.filter { it.isNotEmpty() }
            if (items.size < 3) continue
            val abstractCount = items.count { item ->
                val tail = if (item.length > 6) item.takeLast(3) else item
                tail.length >= 2 && tail.last() in ABSTRACT_TAILS
            }
            if (abstractCount >= 3) return items.takeLast(3).joinToString("、")
        }
        return null
    }

    /**
     * 连续两行等长（差 ≤1）、都以同一个句末标点收尾、**且开头两个字一样** —— 排比。
     *
     * ⚠️ "开头两个字一样"这一条不是原本就有的，是**存量体检自己抓出来的误报救回来的**：
     *    第一次全量跑时，它把
     *      「说完就停。不问「还有什么需要」「还有别的吗」。」
     *      「长度：一般两三句。只有对方明确要解释时才展开。」
     *    判成了排比 —— 这两行只是**碰巧一样长**，没有任何并列结构。
     *    光靠"等长 + 同标点"抓到的是一半的散文，那就成了噪音。
     *
     * ★ 真正的排比一定**共用骨架**（「她现在…」/「别每句都…」），
     *   而骨架在句首。所以加上前缀约束之后，误报消失，真排比仍然抓得到。
     */
    private fun parallelLine(section: String): String? {
        val lines = section.split('\n').map { it.trim() }.filter { it.length >= 10 }
        for (i in 0 until lines.size - 1) {
            val a = lines[i]
            val b = lines[i + 1]
            if (kotlin.math.abs(a.length - b.length) > 1) continue
            if (a.last() != b.last() || a.last() !in "。！？") continue
            val prefix = a.commonPrefixWith(b).take(2)
            if (prefix.length >= 2) return "$a / $b"
        }
        return null
    }

    /**
     * 第一个 emoji 字符（没有则 null）。
     *
     * ⚠️ 不用正则：emoji 大多在基本平面之外，正则需要拿**代理项**写区间
     *    （`[\uD83C-\uDBFF][\uDC00-\uDFFF]`），而代理项在字符类区间里的行为
     *    随实现而变 —— 实测在 kotlinc 2.2.20 + JUnit 的这条链上直接漏检（👍 抓不到）。
     *    改成逐码点判断之后行为是确定的，也不再依赖源文件的编码。
     *
     * ⚠️ 只认 U+1F000 以上与 0x2700 段的 Dingbats，**不认** ✓(U+2713) ✔(U+2714)：
     *    项目注释里到处都是 ✓ 与 ★，把它们算成表情会让检查器天天喊狼来了。
     */
    private fun firstEmoji(section: String): String? {
        var i = 0
        while (i < section.length) {
            val cp = section.codePointAt(i)
            val width = Character.charCount(cp)
            val hit = cp >= 0x1F000 || (cp in 0x2700..0x27BF && cp != 0x2713 && cp != 0x2714)
            if (hit) return section.substring(i, (i + width).coerceAtMost(section.length))
            i += width
        }
        return null
    }

    /** 取第一个命中的标记（列表顺序即优先级）。 */
    private fun firstMark(section: String, marks: List<String>): String? =
        marks.firstOrNull { section.contains(it) }

    /** 截取命中位置前后各若干字，够定位就行。 */
    private fun context(text: String, index: Int, radius: Int = 8): String {
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + radius).coerceAtMost(text.length)
        return text.substring(start, end).replace("\n", " ")
    }
}
