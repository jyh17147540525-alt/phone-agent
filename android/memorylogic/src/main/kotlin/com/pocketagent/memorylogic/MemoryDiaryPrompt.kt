package com.pocketagent.memorylogic

/**
 * **让角色亲手写那篇日记** —— 提示词构造（纯逻辑，可离线钉住）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户的验收标准（原话）
 * ═══════════════════════════════════════════════════════════════
 *
 * "每以（每）个节点里呈现给用户的内容，都应该由 api 里的角色模型生成的，
 *  就像安排角色写了一篇日记和随笔一样。"
 * "全是以角色第一人称的陈述呀，要带有总结性质的。"
 *
 * ⇒ 提示词必须写清三件事：
 *   ① **第一人称**，她自己的口吻（不是旁白、不是记录）
 *   ② **总结性质**：概括"那天发生了什么"，不复述对话
 *   ③ 称呼 + 发生了什么 + 她的感受
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这一段提示词本身不是"记忆"
 * ═══════════════════════════════════════════════════════════════
 *
 * 它只喂给模型生成**展示用**的随笔。生成结果落在独立缓存里，
 * **不回写原子库** —— 用户明确要求过："底层内核该是什么样还是什么样。"
 *
 * ⚠️ 也不把随笔再当素材喂给抽取（那会让记忆变成"她对自己记忆的复述"）。
 */
object MemoryDiaryPrompt {

    /**
     * 构造一次"写日记"的提示词。
     *
     * @param rawMemory 原始记忆文本（可能带 `U>` / `A<` 记号）
     * @param kindLabel 类别名
     * @param userAddress 她怎么称呼用户；空 = "你"
     * @param personaBrief 角色卡摘要（可以为空；空 = 只靠固有口吻）
     * @param dayHint 相对时间（"今天"/"前几天"…）
     */
    fun build(
        rawMemory: String,
        kindLabel: String,
        userAddress: String,
        personaBrief: String = "",
        dayHint: String = "",
    ): String {
        val who = userAddress.trim().ifBlank { "你" }
        // ★ 喂给模型的是**洗干净的内容**，不是 `U>` / `A<` 那种内部编码。
        //   让模型去解读内部编码，它会开始在日记里讨论这些符号。
        val content = MemoryDiary.readable(rawMemory)
        return buildString {
            if (personaBrief.isNotBlank()) {
                appendLine(personaBrief.trim())
                appendLine()
            }
            appendLine("你要写一篇日记。就写在你自己本子上的那种。")
            appendLine()
            appendLine("【这件事】")
            if (dayHint.isNotBlank()) appendLine("时间：$dayHint")
            appendLine("类别：$kindLabel")
            appendLine("记录：$content")
            appendLine()
            appendLine("【怎么写】")
            appendLine("1. **第一人称**，用你自己的口吻，像你真在写。")
            appendLine("- 不要写成旁白，不要写成对话记录，不要出现「他说」「我说」这种句式。")
            appendLine("2. **总结性**：把这件事概括成「那天发生了什么」，不要逐句复述对话。")
            appendLine("- 三句话以内说清就已经很好，宁可短。")
            appendLine("3. 顺序照这个来：先提到$who → 再说发生了什么 → 最后一句是你自己的感受。")
            appendLine("4. 可以有情绪。可以吐槽、可以嫌弃、可以心里发软 —— 别写成汇报。")
            appendLine("5. 不超过 120 字。")
            appendLine()
            appendLine("【不要】")
            appendLine("- 不要标题、不要日期格式、不要引号包住整篇。")
            appendLine("- 不要解释你在写日记这件事。")
            appendLine("- 不要出现链接、路径、文件名、代码这种技术细节。")
            // ⚠️ 必须写 `${who}` 而不是 `$who` — 后面紧跟汉字时，
            //    Kotlin 会把"who说话"当成一个标识符，报 unresolved reference。
            appendLine("- 不要代表${who}说话。")
            // ⚠️ 真机抓到：模型把类别名当成小标题写进正文了
            //    （"…耳朵到现在还烫着。阶段结论：规矩不能乱定。"）。
            //    类别是我们内部的分类，不该出现在她的本子上。
            appendLine("- **不要**写「${kindLabel}：」这类分类标签，那是内部字段，不是日记内容。")
            // ⚠️ 真机抓到：模型用「他」指代了用户。
            //    她平时是叫$who的，正文里出现"他"会立刻出戏。
            appendLine("- 提到对方时**只用「${who}」**，不要用「他」「她」「对方」「用户」。")
            appendLine()
            append("只输出日记正文。")
        }
    }

    /**
     * 清洗模型的输出。
     *
     * ⚠️ 模型很爱加「日记：」「（完）」「---」这类装饰，
     *   或者把整篇包在引号里。这些都会让界面看起来像"AI 生成物"，
     *   而不是一本本子上的字。
     */
    fun clean(raw: String, maxChars: Int = MAX_CHARS): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        // 去掉整篇包裹的引号 / 书名号 / 直角引号
        // ⚠️ 中文模型很爱用「」—— 漏了它就等于没洗（测试抓到的）。
        s = s.trim('"', '\'', '“', '”', '‘', '’', '《', '》', '「', '」', '『', '』')
        // 去掉 markdown 分隔线与标题**行**
        // ⚠️ 标题要整行删掉，不是只删 `#` —— 只删井号会留下"标题"两个字，
        //    于是日记正文第一行变成标题（测试抓到的）。
        s = s.replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
        s = s.replace(Regex("^#+\\s*.*$", RegexOption.MULTILINE), "")
        // 去掉"日记："这种前缀标签（同一行开头）
        s = s.replace(Regex("^(日记|随笔|记录|心情)\\s*[:：]\\s*"), "")
        // ⚠️ 真机抓到：模型在正文中间插了「阶段结论：」这种内部类别标签。
        //    它不是正文，删掉标签、留下后面的内容。
        s = s.replace(Regex("(?:事实|偏好|约束|阶段结论)\\s*[:：]\\s*"), "")
        s = s.replace(Regex("\\n{3,}"), "\n\n")
        // 「（完）」「——完——」这类收尾
        s = s.replace(Regex("\\n*[（(]?完[）)]?\\s*$"), "")
        s = s.replace(Regex("\\n*—+\\s*完\\s*—+\\s*$"), "")
        val body = s.trim()
        if (body.length <= maxChars) return body
        // 超长就在句末标点处收，不砍半句
        val cut = body.take(maxChars)
        val at = cut.lastIndexOfAny(charArrayOf('。', '！', '？', '…', '\n'))
        return (if (at >= maxChars / 2) cut.take(at + 1) else cut).trim()
    }

    /** 生成的随笔最多多少字。**短是随笔的特征。** */
    const val MAX_CHARS = 200
}
