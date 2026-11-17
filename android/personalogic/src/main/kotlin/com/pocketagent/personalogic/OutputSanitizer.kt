package com.pocketagent.personalogic

/**
 * **输出清洗** —— 把中文里夹的外文词剔掉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户报的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * > "在中文场景对话，会在一段话中间出现一两个英文单词，
 * >  或者其他国家的字，而且还是牛头不对马嘴的那种"
 *
 * 这是某些模型的**典型退化**：中英混训、词表对齐错位，
 * 于是在中文句子中间冒出一个毫无关系的英文词，或者干脆冒出西里尔字母。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须在输出层拦，而不是只靠提示词
 * ═══════════════════════════════════════════════════════════════
 *
 * 提示词能降低概率，**但不能消除** —— 这是模型的解码期故障，
 * 不是它"没听话"。用户对这件事的容忍度是零：
 * 一句正常的话中间插个 "interpolate"，整段就废了。
 *
 * ⇒ 两段式：
 * 1. **剔除非中文文字系统**（西里尔、谚文、假名、阿拉伯、泰文…）—— 直接删，无争议
 * 2. **拉丁字母词** —— 按白名单保留，其余删
 *
 * ⚠️ 删词之后要**收拾残局**：多余的空格、连续标点、句首孤零零的逗号。
 *    不做这一步的话，输出会变成「今天天气不错， ，我们出去吧」。
 */
object OutputSanitizer {

    /**
     * 允许保留的拉丁词 —— **只放真正常用、不会被误读的**。
     *
     * ⚠️ 白名单宁短不长：多留一个词，就多一次"牛头不对马嘴"的机会。
     */
    private val LATIN_KEEP = setOf(
        "ok", "okay", "wifi", "app", "id", "url", "qq", "cpu", "gps",
        "ai", "pdf", "excel", "word", "ppt", "usb", "sms", "otp",
    )

    /**
     * 非中文的文字系统 —— 一律视为噪声。
     *
     * 覆盖：西里尔、希腊、希伯来、阿拉伯、泰、谚文、假名、天城文。
     */
    private val FOREIGN_SCRIPTS = Regex(
        "[" +
            "\u0400-\u04FF" + // 西里尔
            "\u0370-\u03FF" + // 希腊
            "\u0590-\u05FF" + // 希伯来
            "\u0600-\u06FF" + // 阿拉伯
            "\u0E00-\u0E7F" + // 泰
            "\uAC00-\uD7AF" + // 谚文
            "\u3040-\u30FF" + // 平假名/片假名
            "\u0900-\u097F" + // 天城文
            "]",
    )

    /** 一串拉丁字母（含词内连字符/撇号）。 */
    private val LATIN_RUN = Regex("[A-Za-z][A-Za-z'’-]{0,30}")

    /** 一个汉字。用来判断"清洗后还剩不剩中文"。 */
    private val CJK = Regex("[\\u4e00-\\u9fff]")

    /**
     * 清洗一段输出。
     *
     * @param text 模型原始返回
     * @param allowLatin 是否允许英文词。**默认 false** ——
     *   中文场景下，一个不该出现的英文词比"少一个词"糟糕得多。
     * @return 清洗后的文本
     */
    fun clean(text: String, allowLatin: Boolean = false): String {
        if (text.isBlank()) return text

        var out = text

        // ── ① 非中文文字系统：直接删 ────────────────────
        out = FOREIGN_SCRIPTS.replace(out, "")

        // ── ② 拉丁词：按白名单删 ────────────────────────
        if (!allowLatin) {
            out = LATIN_RUN.replace(out) { m ->
                if (m.value.lowercase() in LATIN_KEEP) m.value else ""
            }
        }

        // ── ③ 收拾残局 ──────────────────────────────────
        return tidy(out)
    }

    /**
     * 收拾删词留下的空格与标点。
     *
     * ⚠️ 三件事都必须做，否则删词比不删更难看：
     * · 中文之间多出来的空格
     * · 连续标点（"，，" "。，"）
     * · 句首/句尾孤立的标点
     */
    private fun tidy(s: String): String {
        var out = s
        // 中文与中文之间的空格（保留两侧都是英文/数字的）
        out = Regex("(?<=[\\u4e00-\\u9fff])[ \\t]+(?=[\\u4e00-\\u9fff])").replace(out, "")
        // 中文紧挨标点时的多余空格
        out = Regex("[ \\t]+(?=[，。！？、；：）」』】])").replace(out, "")
        out = Regex("(?<=[（「『【])[ \\t]+").replace(out, "")
        // ══════════════════════════════════════════════════════
        //  ⚠️ 2026-11-03：连续标点折叠**必须按同一种标点判断**
        // ══════════════════════════════════════════════════════
        //
        // 上一版是 `([，。！？、；：])[，。！？、；：]+` —— 它把
        //   「嗯！？你说什么」 变成 「嗯！你说什么」，**问号被吃了**。
        //
        // ⚠️ 但 `！？` 是正常的中文组合（惊疑），不是重复。
        //    `……` 也是正常的（省略号），不能折成一个 `…`。
        //
        // ⇒ 只折叠**同一种**标点的连续重复，且 `…` 完全不动。
        out = Regex("([，。！？、；：])\\1+").replace(out) { m -> m.groupValues[1] }
        // 行首标点
        out = Regex("(?m)^[，。！？、；：\\s]+").replace(out, "")
        // 多余空格
        out = Regex("[ \\t]{2,}").replace(out, " ")
        return out.trim()
    }

    /**
     * 检测有没有夹外文（清洗**之前**用，给日志）。
     *
     * @return 命中的片段（空 = 干净）
     */
    fun foreignBits(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        FOREIGN_SCRIPTS.findAll(text).forEach { out += it.value }
        LATIN_RUN.findAll(text).forEach {
            if (it.value.lowercase() !in LATIN_KEEP) out += it.value
        }
        return out.distinct()
    }

    /**
     * 清洗之后是不是**几乎没剩下东西**。
     *
     * ⚠️ 这一条是安全阀：如果模型整段都是外文胡话，清洗会把它变成空串。
     *    那时不该返回空回复，该**重试**或**报错** —— 空回复比错回复更让人困惑。
     */
    fun isNearlyEmpty(cleaned: String, original: String): Boolean =
        original.length >= 12 && cleaned.length * 3 < original.length

    /**
     * 从模型原文得到**能显示给人看的那一份**。
     *
     * ⚠️ 2026-11-17 用户报：开启应用分身、换了模型与思考强度之后，**每一轮回复**
     *    都成了红字「外文占了太多，清洗后几乎没剩下中文，请重试一次」。他去重试，
     *    拿到的还是同一句 —— 因为模型下一轮照样整段外文。
     *
     * ⚠️ 一刀切丢弃是最差的处理：他既看不到模型说了什么，也判断不出是模型跑偏、
     *    提示词没生效，还是网关把推理塞进了正文。**把回复扔掉的清洗，比不洗更糟。**
     *
     * ⇒ 三段阶梯，只有走到最后才放弃：
     *   ① 严格清洗（删外文）—— 正常情况走这条
     *   ② 宽松清洗（保住英文词，仍删西里尔/谚文/假名那类乱码）—— 严格清洗几乎删空时
     *   ③ 原文 —— 连宽松清洗都不剩东西（整段俄文、韩文）
     *
     * 注意这不改变「中文场景不该夹外文」这个判断：[clean] 与 [isNearlyEmpty] 都没动，
     * 改动只在于**不再拿"清洗后为空"当作"不给用户看"的理由**。
     *
     * @return 可显示的文本（原文本身为空时返回空串）
     */
    fun displayable(raw: String): String {
        if (raw.isBlank()) return ""
        val strict = clean(raw)
        // ★ 只要严格清洗后**还剩中文**，那就是能给人看的东西 —— 哪怕它比原文短很多。
        //   `isNearlyEmpty` 的 1/3 比例只是"还有没有中文"的近似，遇到
        //   「一句中文 + 一大段英文」会误判成空，于是把整段英文又放回来了。
        if (strict.isNotBlank() && (CJK.containsMatchIn(strict) || !isNearlyEmpty(strict, raw))) {
            return strict
        }
        val loose = clean(raw, allowLatin = true)
        if (loose.isNotBlank()) return loose
        return raw.trim()
    }
}
