package com.pocketagent.voicelogic

/**
 * 语音标签库：厂商规定的那几个"可以被念成情绪"的记号，例如 `[laughs]`、`(sighs)`。
 *
 * 一条硬规矩：**标签只往语音合成送，永不进用户能看到的正文。**
 * 文案里出现标签就是缺陷 —— [visibleText] 负责把它切掉。
 */
data class VoiceTagSet(
    val vendorId: String,
    val tags: List<String>,
    val fetchedAtMs: Long = 0L,
    val source: String = "",
) {
    val isEmpty: Boolean get() = tags.isEmpty()
}

object VoiceTags {

    /** 给文本模型看的最多几条。太多会把它训成一个到处塞方括号的东西。 */
    const val MAX_IN_PROMPT = 12

    /** 单个标签最长字符数，超过的当成噪声丢掉。 */
    private const val MAX_TAG_CHARS = 24

    /** 各家内置兜底：拉不到官方文档时也能用，且这几条是我们实测过认得的。 */
    private val BUILTIN = mapOf(
        "breezeblue-cloud-tts" to listOf("[laughs]", "[sighs]", "[whispers]"),
        "breezeblue-tts" to listOf("(laughs)", "(sighs)"),
        "elevenlabs" to listOf("[laughs]", "[sighs]", "[whispers]", "[excited]"),
    )

    fun builtinFor(vendorId: String): VoiceTagSet =
        VoiceTagSet(vendorId = vendorId, tags = BUILTIN[vendorId].orEmpty(), source = "builtin")

    /**
     * 从官方文档/接口的文本里把标签捞出来。
     *
     * 不假定格式：方括号、圆括号、尖括号、`"tag": "value"` 都试一遍。
     * 捞不到就返回空 —— 空比编造安全。
     */
    fun parse(vendorId: String, raw: String, fetchedAtMs: Long = 0L, source: String = ""): VoiceTagSet {
        if (raw.isBlank()) return VoiceTagSet(vendorId, emptyList(), fetchedAtMs, source)
        val found = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("\\[[a-zA-Z][a-zA-Z _-]{0,20}]"),
            Regex("\\([a-zA-Z][a-zA-Z _-]{0,20}\\)"),
            Regex("<[a-zA-Z][a-zA-Z _-]{0,20}>"),
        )
        for (p in patterns) {
            for (m in p.findAll(raw)) {
                val tag = m.value.replace(Regex("\\s+"), " ")
                if (tag.length <= MAX_TAG_CHARS) found += tag
            }
        }
        return VoiceTagSet(vendorId, found.take(40).toList(), fetchedAtMs, source)
    }

    /**
     * 拼进**文本模型**提示词的那一小段。
     *
     * 空库返回空串 —— 没有标签的厂商，一个字都不该加，
     * 否则模型会以为"必须写方括号"，然后在正文里撒标签。
     */
    fun promptSection(set: VoiceTagSet?, callName: String = "你"): String {
        if (set == null || set.tags.isEmpty()) return ""
        val list = set.tags.take(MAX_IN_PROMPT).joinToString("、")
        return buildString {
            appendLine("## 语音标签")
            appendLine("能用这些标签让声音带上情绪：$list")
            appendLine("规则：")
            appendLine("- 一个气泡里最多一个，放在最前面，比如「[laughs] 你回来了」。")
            appendLine("- 只在情绪真的到了的时候用。平淡的一句不要加。")
            appendLine("- 用户看到的是纯台词；标签只给语音合成看，不要解释它们。")
        }.trimEnd()
    }

    /**
     * 用户能看到的正文：把标签切干净。
     *
     * 括号里的**动作**要留（`（轻轻笑了一下）`），方括号里的**标签**要去 ——
     * 这是用户定下的分界：眼前只有台词和括号动作。
     */
    fun visibleText(text: String, set: VoiceTagSet? = null): String {
        if (text.isEmpty()) return text
        var out = text
        val known = set?.tags.orEmpty().map { it.lowercase() }
        out = Regex("\\[[^\\[\\]\\n]{1,24}]").replace(out) { m ->
            if (known.isEmpty() || m.value.lowercase() in known) "" else m.value
        }
        out = out.replace(Regex("[ \\t]{2,}"), " ")
        out = out.replace(Regex("^[ \\t]+"), "")
        return out.trim()
    }
}
