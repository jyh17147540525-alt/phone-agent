package com.pocketagent.personalogic

/**
 * **进提示词之前，把机制词汇滤掉**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * > "系统又信誉分你也不能让模型说出来呀，背后偷偷记就行了"
 *
 * ⚠️ 说得很准。系统里确实有内部账务（预算守卫、用量记录、审计日志），
 *    那些东西**该留在后端**。可它们曾经通过一条绕路泄漏到模型眼前：
 *
 * ```
 *   旧记忆里写着「用一套信誉分/记账机制督促他好好吃饭」
 *        ↓ 记忆被原样塞进提示词
 *   模型看见了「记账」这个词
 *        ↓ 它就照着说
 *   「行，记账上了。」
 * ```
 *
 * ⇒ 两道防线：
 * 1. **源头**：记忆抽取提示词禁止写机制词（见 `AtomExtractor.buildPrompt`）
 * 2. **兜底**：任何要进提示词的文本，先过这里
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么是"删句子"而不是"替换词"
 * ═══════════════════════════════════════════════════════════════
 *
 * 替换会造出半通不通的句子：
 * ```
 *   "他建立了一套记账机制" → "他建立了一套××机制"   ← 更怪
 * ```
 * 而"这条记忆不值得给模型看"本来就是合理判断 ——
 * 一条讲内部实现的记忆，对角色扮演**没有用处**。
 *
 * ⚠️ 但绝不静默：删了几条要能被调用方记下来。
 */
object PromptHygiene {

    /**
     * 机制的词汇表。
     *
     * ⚠️ 分三组，成因不同：
     * · 账务：系统真的在记账，但那是后端的事
     * · 实现：提示词/上下文/模型，角色不该知道这些词
     * · 表演：人设/设定/扮演，模型在管理自己的表演
     */
    val MECHANISM_WORDS: List<String> = listOf(
        // ── 账务（后端有，但不可见）──
        "记账", "记分", "信誉分", "额度", "账本", "计数器", "积分",
        "扣分", "加分", "信誉",
        // ── 实现 ──
        "提示词", "上下文", "系统", "数据库", "记忆库", "向量", "检索",
        "模型", "接口", "字段", "参数",
        // ── 表演管理 ──
        "人设", "设定", "扮演", "出戏", "入戏", "角色卡",
    )

    /**
     * 滤掉含机制词的句子。
     *
     * @return 滤后的文本。**全被滤掉时返回空串** —— 调用方应当丢弃这条。
     */
    fun scrub(text: String): String {
        if (text.isBlank()) return text
        if (MECHANISM_WORDS.none { text.contains(it) }) return text

        // 按句子切，逐句判断
        val parts = text.split(Regex("(?<=[。！？\\n])"))
        val kept = parts.filter { p ->
            p.isNotBlank() && MECHANISM_WORDS.none { p.contains(it) }
        }
        return kept.joinToString("").trim()
    }

    /** 这段文本里有没有机制词（给日志用）。 */
    fun hits(text: String): List<String> =
        if (text.isBlank()) emptyList()
        else MECHANISM_WORDS.filter { text.contains(it) }

    /**
     * 批量过滤 + 报告丢了几条。
     *
     * @return (保留的, 丢弃的条数)
     */
    fun scrubAll(texts: List<String>): Pair<List<String>, Int> {
        var dropped = 0
        val kept = texts.mapNotNull { t ->
            val s = scrub(t)
            if (s.isBlank() && t.isNotBlank()) {
                dropped++
                null
            } else {
                s
            }
        }
        return kept to dropped
    }
}
