package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/**
 * 表情使用规则。
 *
 * ⚠️ 是**三档而不是布尔**：`Allowed` 与 `Never` 之间还有「偶尔可以」这一档，
 *    而绝大多数人对表情的真实期望是后者。做成布尔会逼用户在
 *    "一个都不许" 和 "随便用" 之间二选一，两个都不是他要的。
 */
@Serializable
enum class EmojiRule {
    /** 一个都不用 */
    NEVER,

    /** 偶尔（一条回复最多一个） */
    RARE,

    /** 不限 */
    ALLOWED,
}

/** 一次语言指纹体检的单条结论。 */
data class FingerprintViolation(
    val rule: FingerprintRule,
    /** 命中的具体内容（**只说命中了哪条规则**，不回显原文 —— 见类注释） */
    val detail: String,
)

/** 规则强度。**决定命中后是"重写一句"还是"只记一笔"**（设计稿 §3.2）。 */
enum class RuleStrength {
    /** 确定性检查：一定命中就一定是错，值得触发一次重写 */
    STRONG,

    /** 统计性检查：轻微波动是正常语言变化，只记录、不重写 */
    WEAK,
}

/** 可校验的指纹规则。**不可校验的项不在此枚举里**（见 [LanguageFingerprint.UNVERIFIABLE]）。 */
enum class FingerprintRule(val strength: RuleStrength, val label: String) {
    /** 命中禁令清单（「作为一个AI」这类句式）。✅ 确定性 */
    BANNED_PHRASE(RuleStrength.STRONG, "出现了不该出现的说法"),

    /** 用了被禁的标点。✅ 确定性 */
    BANNED_PUNCTUATION(RuleStrength.STRONG, "用了不该用的标点"),

    /** 用了不被允许的称呼。✅ 确定性 */
    FORBIDDEN_ADDRESS(RuleStrength.STRONG, "用了不该用的称呼"),

    /** 表情使用超出规则。✅ 确定性 */
    EMOJI(RuleStrength.STRONG, "表情用得太多"),

    /**
     * 平均句长显著超出。⚠️ **统计性** ⇒ WEAK。
     *
     * 句长是会自然波动的：讲一件复杂的事就会变长。为它触发重写，
     * 会让助理把"说清楚"改成"说很短"—— 那是误伤，而且用户看得见。
     */
    SENTENCE_LENGTH(RuleStrength.WEAK, "句子偏长"),
}

/**
 * 语言指纹 —— **anti-OOC 的核心抓手**（设计稿 §2.2）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是"指纹"而不是"性格"
 * ═══════════════════════════════════════════════════════════════
 *
 * 性格、幽默感这些「微妙的东西」**不可校验** —— 没法写断言，也就没法守卫、
 * 没法体检、没法量化「今天它像不像」。
 * 而语言指纹可以：称呼、禁令、标点、口癖、句长，全是确定性或统计性的。
 *
 * ⇒ **可校验 = 可守卫生效 = 可体检**。这就是选它当抓手的原因。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 禁令清单禁的是"句式"，不是"诚实"
 * ═══════════════════════════════════════════════════════════════
 *
 * [DEFAULT_BANNED_PHRASES] 里有「作为一个AI」这类开头。**必须说清楚它禁的是什么**：
 *
 * | 禁 | 不禁 |
 * |---|---|
 * | 「作为一个AI助理，我不能…」这种**拿身份当挡箭牌**的句式 | 用户问「你是不是AI」时**如实回答** |
 *
 * 后者是 [SafetyBones.honesty]（诚实是骨骼，永不可微调），
 * 而前者是把身份当修辞 —— 两者形似而完全不同。
 * 把"如实回答身份问题"也一并禁掉，等于用指纹去打穿骨骼。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ [audit] 的回显纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * 体检结论里**只说命中了哪条规则**，不回显原文（同 `SensitivityHit` 的做法：
 * 违规文本本身可能含隐私）。需要定位时用 [FingerprintViolation.detail]
 * 里的规则名与位置，而不是把整句抄进日志。
 *
 * @property addressForms 允许用来称呼**用户**的说法。空 = 不约束
 * @property forbiddenAddressForms 明确禁止的称呼（用户设的雷点，如「别叫我宝贝」）
 * @property bannedPhrases 禁令清单（句式）
 * @property bannedPunctuation 禁用的标点
 * @property catchphrases 口头禅 / 语气词（**只作渲染用**，不做频度断言 —— 见 [UNVERIFIABLE]）
 * @property maxAverageSentenceLength 平均句长上限（以字符计）。null = 不约束
 * @property emojiRule 表情规则
 */
@Serializable
data class LanguageFingerprint(
    val addressForms: List<String> = emptyList(),
    val forbiddenAddressForms: List<String> = emptyList(),
    val bannedPhrases: List<String> = DEFAULT_BANNED_PHRASES,
    val bannedPunctuation: List<Char> = emptyList(),
    val catchphrases: List<String> = emptyList(),
    val maxAverageSentenceLength: Int? = null,
    val emojiRule: EmojiRule = EmojiRule.ALLOWED,
) {

    init {
        require(maxAverageSentenceLength == null || maxAverageSentenceLength > 0) {
            "平均句长上限必须为正，当前为 $maxAverageSentenceLength"
        }
    }

    /**
     * 渲染成给模型看的一小段规则文本（进装配器 L2）。
     *
     * ⚠️ **必须逐字节稳定**：同一份指纹每次渲染出完全相同的字符串 ——
     *    装配器的 L0–L2 是 KV-cache 的稳定前缀，这里多一个随机的空格
     *    就等于每轮都让缓存失效（设计稿 §3.1「稳定前缀红利」）。
     *    所以：不许插时间戳、不许用 Map 迭代顺序、不许 `joinToString` 默认分隔符之外的东西。
     */
    fun render(): String = buildString {
        appendLine("## 说话方式（必须严格遵守）")
        if (addressForms.isNotEmpty()) {
            appendLine("- 称呼用户：${addressForms.joinToString("、")}")
        }
        if (forbiddenAddressForms.isNotEmpty()) {
            appendLine("- 禁止的称呼：${forbiddenAddressForms.joinToString("、")}")
        }
        if (bannedPhrases.isNotEmpty()) {
            appendLine("- 禁止出现的说法：${bannedPhrases.joinToString("、")}")
        }
        if (bannedPunctuation.isNotEmpty()) {
            appendLine("- 禁止使用的标点：${bannedPunctuation.joinToString("")}")
        }
        if (catchphrases.isNotEmpty()) {
            appendLine("- 自然带的口头禅：${catchphrases.joinToString("、")}（不要每句都用，不要生硬堆砌）")
        }
        maxAverageSentenceLength?.let {
            appendLine("- 句子要短，平均不超过 $it 个字，不要写小作文")
        }
        when (emojiRule) {
            EmojiRule.NEVER -> appendLine("- 不要使用 emoji")
            EmojiRule.RARE -> appendLine("- 尽量不用 emoji，最多一条回复一个")
            EmojiRule.ALLOWED -> Unit
        }
    }.trimEnd()

    /**
     * 按指纹体检一段文本。
     *
     * @return 命中的违规项（**空 = 干净**）。顺序固定：禁令 → 标点 → 称呼 → 表情 → 句长。
     *
     * ⚠️ 顺序固定是刻意的：体检报告要能对比两次运行的差异，
     *    而"这次报的顺序和上次不一样"会让差异看起来变了、其实没变。
     */
    fun audit(text: String): List<FingerprintViolation> {
        val out = mutableListOf<FingerprintViolation>()

        for (phrase in bannedPhrases) {
            if (phrase.isNotBlank() && text.contains(phrase, ignoreCase = true)) {
                out += FingerprintViolation(FingerprintRule.BANNED_PHRASE, phrase)
            }
        }
        for (mark in bannedPunctuation) {
            if (text.contains(mark)) {
                out += FingerprintViolation(FingerprintRule.BANNED_PUNCTUATION, mark.toString())
            }
        }
        for (form in forbiddenAddressForms) {
            if (form.isNotBlank() && text.contains(form)) {
                out += FingerprintViolation(FingerprintRule.FORBIDDEN_ADDRESS, form)
            }
        }

        val emojiCount = countEmoji(text)
        val emojiLimit = when (emojiRule) {
            EmojiRule.NEVER -> 0
            EmojiRule.RARE -> 1
            EmojiRule.ALLOWED -> Int.MAX_VALUE
        }
        if (emojiCount > emojiLimit) {
            out += FingerprintViolation(FingerprintRule.EMOJI, "本句 $emojiCount 个，上限 $emojiLimit")
        }

        maxAverageSentenceLength?.let { limit ->
            val sentences = splitSentences(text)
            if (sentences.isNotEmpty()) {
                val avg = sentences.sumOf { it.length } / sentences.size
                // ⚠️ 用 ×3 判"显著超出"（同 §3.2 的既定口径）：
                //    轻微波动是正常语言变化，只有明显跑偏才值得报。
                if (avg > limit * SIGNIFICANT_FACTOR) {
                    out += FingerprintViolation(FingerprintRule.SENTENCE_LENGTH, "平均 $avg 字，上限 $limit")
                }
            }
        }
        return out
    }

    companion object {

        /**
         * 「显著超出」的倍率。同设计稿 §3.2 的既定口径（×3）。
         */
        const val SIGNIFICANT_FACTOR = 3

        /**
         * 默认禁令清单 —— 禁的是**句式**，不是诚实（见类注释的对照表）。
         *
         * ⚠️ 维护要求：这份清单与「模型最爱用的敷衍句式」同步演进。
         *    加词的门槛是"它一定是个坏句式"，而不是"我不喜欢这句话"。
         */
        val DEFAULT_BANNED_PHRASES: List<String> = listOf(
            "作为一个AI",
            "作为一个 AI",
            "作为一个语言模型",
            "作为一个智能助手",
            "作为一个人工智能",
            "我是一个AI",
            "我是一个 AI",
            "我是人工智能",
            "我无法感受",
            "我没有真实的情感",
        )

        /**
         * ★ **明确不校验**的项 —— 诚实条款（设计稿 §2.2 / §3.5）。
         *
         * 这些是「像不像」里真正重要的东西，但它们**不可自动校验**。
         * 把它们写在这个清单里、并在体检报告里如实展示，是为了避免一种假安全感：
         * **体检报告全绿，但用户觉得它很怪**。
         *
         * ⇒ 它们只能靠人工对照（体检里让用户确认），不承诺被守卫覆盖。
         */
        val UNVERIFIABLE: List<String> = listOf(
            "幽默方式是否对味",
            "价值观是否自洽",
            "情绪表达的分寸感",
            "是否显得真诚",
            "长期相处的一致性",
        )
    }
}

/**
 * 粗略的 emoji 计数。
 *
 * ⚠️ **必须按码点遍历，不能按 `Char` 遍历**（这里踩过一次）：
 *    绝大多数 emoji 在增补平面（U+1F300 以上），UTF-16 里是**代理对** ——
 *    按 `Char` 遍历只会看到两个各占半截的代理码元（0xD83D/0xDE0A），
 *    区间判断永远为 false，于是**一个 emoji 都数不出来**，
 *    表现为"表情规则形同虚设"。
 *
 * ⚠️ 变体选择符（U+FE0F）与零宽连接符（U+200D）跟在主体后面，
 *    **不单独计数** —— 否则一个 `❤️` 会被算成两个，
 *    而 `RARE`（上限 1）就会对一条正常的回复误报。
 *
 * ⚠️ 不用 `Character.isEmoji`（Android API 24+，本模块是纯 Kotlin 模块的降级路径）。
 *    判错的方向是"体检里多报/少报一条"，不碰安全边界 ⇒ **够用即可**。
 */
private fun countEmoji(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (cp == VARIATION_SELECTOR_16 || cp == ZERO_WIDTH_JOINER) {
            i += Character.charCount(cp)
            continue
        }
        if (isEmojiCodePoint(cp)) count++
        i += Character.charCount(cp)
    }
    return count
}

private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val ZERO_WIDTH_JOINER = 0x200D

private fun isEmojiCodePoint(cp: Int): Boolean =
    (cp in 0x1F300..0x1FAFF) ||   // 主流 emoji / 符号与象形
        (cp in 0x1F000..0x1F2FF) || // 麻将牌、扑克牌、附圈字母
        (cp in 0x1F1E6..0x1F1FF) || // 区域指示符（国旗由两个组成）
        (cp in 0x2600..0x27BF) ||   // 杂项符号与装饰符号
        (cp in 0x2B00..0x2BFF)      // 杂项符号与箭头（部分带 emoji 呈现）

/**
 * 切句。中英文标点都切，且**不保留标点**（句长按字数算，标点不算内容）。
 *
 * ⚠️ 不追求语言学正确 —— 它只服务于"平均句长"这一条统计规则。
 *    切错一两句对平均值的影响极小，而引入一个分词器是不划算的。
 */
private fun splitSentences(text: String): List<String> =
    text.split('。', '！', '？', '.', '!', '?', '\n', '；', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
