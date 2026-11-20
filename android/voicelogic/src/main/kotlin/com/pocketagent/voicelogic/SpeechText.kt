package com.pocketagent.voicelogic

/**
 * 把回复正文变成**该念出来的那句话**。
 *
 * 送进语音模型的东西不是"给眼睛看的文本"，而是"给嘴读的台词"。
 * 两者混在一起时，模型会念出不该念的东西，停顿也会落在错的地方：
 * 星号动作、方括号里的情绪标签、表情符号、列表符号、URL —— 它们被当成词读时，
 * 语音模型只能猜"这里要不要停"，于是断句错、语气也跟着错。
 *
 * 这里只做删减与交换，**不改语义**：删掉的都是不该出声的装饰。
 */
object SpeechText {

    /** 念之前最多留多少字。太长会被服务商拒（多数 1000 字符上限）。 */
    const val MAX_CHARS = 2_000

    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val INLINE_CODE = Regex("`([^`]*)`")
    private val URL = Regex("https?://\\S+")
    /** 方括号标签：`[轻笑]`、`[叹气]`、`[soft]` —— 这是我们自己的约定，必须整段消掉。 */
    private val SQUARE_TAG = Regex("\\[[^\\[\\]\\n]{1,20}]")
    /** 星号动作：`*轻轻点头*`。只删星号会把动作当台词念，所以整段连内容一起删。 */
    private val STAR_ACTION = Regex("\\*[^*\\n]{1,40}\\*")
    /** 圆括号里的舞台说明。短的是动作，长的可能是正话，只删短的。 */
    private val PAREN_ACTION = Regex("[（(][^（()）\\n]{1,24}[）)]")
    /** 列表与标题记号。 */
    private val LIST_MARK = Regex("(?m)^\\s{0,3}(?:[-*+>]|\\d{1,2}[.、)])\\s*")
    /** 表情符号：念出来是"微笑的表情"，不如不念。 */
    private val EMOJI = Regex("[\\x{1F300}-\\x{1FAFF}\\x{1F000}-\\x{1F2FF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE0F}\\x{2190}-\\x{21FF}]")

    /**
     * @param text 原始回复
     * @param maxChars 上限，默认 [MAX_CHARS]
     */
    fun toSpeech(text: String, maxChars: Int = MAX_CHARS): String {
        var out = text
        out = CODE_BLOCK.replace(out, "，代码略，")
        out = INLINE_CODE.replace(out) { m -> m.groupValues[1] }
        out = URL.replace(out, "，链接略，")
        out = STAR_ACTION.replace(out, "")
        out = SQUARE_TAG.replace(out, "")
        out = PAREN_ACTION.replace(out, "")
        out = EMOJI.replace(out, "")
        out = LIST_MARK.replace(out, "")
        out = out.replace(Regex("[*_#>~]+"), "")
        // 换行：行尾已经有标点就只当作停顿，没有就补一个句号 —— 否则两句话会连成一口气。
        out = out.split("\n").joinToString("。") { it.trim() }
        out = out.replace(Regex("[。，]{2,}"), "。")
        out = out.replace(Regex("\\s*([，。！？；：、])\\s*"), "$1")
        out = out.replace(Regex("\\s+"), " ")
        out = out.replace(Regex("^[，。！？；：、\\s]+"), "")
        out = out.trim()
        return if (out.length > maxChars) out.take(maxChars) else out
    }
}
