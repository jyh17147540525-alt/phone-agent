package com.pocketagent.personalogic

/**
 * **世界书** —— 这个角色所在的世界里，哪些事是真的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：为什么检索**不用** AI
 * ═══════════════════════════════════════════════════════════════
 *
 * 世界书的检索 = 触发词命中 + 优先级 + 预算裁剪。
 * **这是确定性计算**，用模型只会更慢、更贵、更不稳定。
 *
 * ⚠️ 更要紧的是：用模型检索会让"同一句话有时想起、有时想不起"，
 *    而世界书是**权威设定** —— 它必须每轮都给出一致的结果。
 *
 * ⇒ 检索归代码。模型只负责两件需要语义的事（见类尾注释）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 世界书"崩"的四种方式，以及这里怎么防
 * ═══════════════════════════════════════════════════════════════
 *
 * | 崩法 | 防法 |
 * |---|---|
 * | **矛盾**（说"我们从没见过"但书里有） | [WorldEntryKind.SETTING] **不可被 AI 修改** |
 * | **遗忘**（重要设定几轮后消失） | [WorldEntry.alwaysInclude] 常驻 |
 * | **过载**（几百条全注入撑爆上下文） | 分级预算 [RetrievalBudget] |
 * | **漂移**（AI 自己编设定） | AI 只能**提议追加 FACT**，且需用户确认 |
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 最重要的一条：SETTING 与 FACT 必须分开
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   SETTING  用户填的权威设定。AI **永远不能改**。
 *   FACT     对话里发生过的事。AI 可以提议，但**要用户确认**。
 * ```
 *
 * ⚠️ 一旦混在一起，AI 就会为了圆这一轮的台词去改上一轮的设定 ——
 *    那就是世界书崩掉的经典路径。这里用**类型**挡死，
 *    而不是靠提示词说"不要改设定"（那只是句愿望）。
 */
@kotlinx.serialization.Serializable
data class WorldEntry(
    val id: String,
    /** 简短标题，方便用户在世界书面板里认出来 */
    val title: String,
    /**
     * **触发词**。用户这句话里出现任意一个，这条就会被考虑注入。
     *
     * ⚠️ 空列表 + [alwaysInclude] = 永远在场（核心设定）。
     *    空列表 + 不是 alwaysInclude = **永远不会被选中**（写错了），
     *    构造时会拒绝。
     */
    val keys: List<String> = emptyList(),
    /** 注入的正文 */
    val content: String,
    /** 0..100。同一批候选里，分高的先注入 */
    val priority: Int = 50,
    /** 永远在场（核心设定，如"你们是恋人"）。不受触发词影响 */
    val alwaysInclude: Boolean = false,
    /** 设定还是事实 —— 见类注释 */
    val kind: WorldEntryKind = WorldEntryKind.SETTING,
) {

    init {
        require(id.isNotBlank()) { "WorldEntry.id 不能为空" }
        require(content.isNotBlank()) {
            "空正文的条目没有意义 —— 应该被丢弃并记原因，而不是落库"
        }
        require(priority in 0..100) { "priority 必须落在 0..100，当前为 $priority" }
        require(keys.isNotEmpty() || alwaysInclude) {
            "条目「$title」没有触发词又不是常驻 —— 它永远不会被注入。这是配置错误"
        }
    }

    /** 这句话命中了几个词（按词长加权：越长的词越具体）。 */
    fun matchScore(query: String): Int {
        if (query.isEmpty() || keys.isEmpty()) return 0
        var score = 0
        for (k in keys) {
            if (k.isNotBlank() && query.contains(k)) {
                // 长词更具体：「左脚踝」比「脚」更能定位
                score += k.length.coerceAtMost(6)
            }
        }
        return score
    }
}

/** 条目类型 —— 见 [WorldBook] 的类注释。 */
enum class WorldEntryKind {
    /** 用户填的**权威设定**。AI 不得修改。 */
    SETTING,

    /** 对话中发生的**事**。可被记录，需用户确认。 */
    FACT,
}

/**
 * 分级预算。
 *
 * ⚠️ 为什么要分级：用户可能填两百条。全注入会撑爆上下文，
 *    而"最重要的"未必被注入 —— 那正是世界书失效的样子。
 *
 * ⇒ 三段预算，各自独立：
 * ```
 *   核心（alwaysInclude）  永远优先，不参与竞争
 *   场景（命中的）          按分数排
 *   细节（剩余的）          只用剩下的额度
 * ```
 */
data class RetrievalBudget(
    /** 全部条目加起来最多注入多少字 */
    val totalChars: Int = 1200,
    /** 核心条目占多少（它们优先，不受场景挤压）*/
    val coreChars: Int = 500,
) {

    init {
        require(totalChars > 0) { "预算必须为正：$totalChars" }
        require(coreChars in 0..totalChars) { "核心预算不能超过总量" }
    }

    companion object {
        /**
         * 默认值。
         *
         * ⚠️ 1200 字大约是 800 token —— 相对 8000 的上下文预算不算大，
         *    但足够放 8~12 条设定。再多就会把人格挤掉。
         */
        val Default = RetrievalBudget()
    }
}

/**
 * 世界书本体 —— **纯函数**，无 IO、无模型调用。
 *
 * ★ 这个类只做"给定这句话，选出该注入哪些条目"。
 *   它不负责存盘、不负责调用模型、不负责改条目。
 */
class WorldBook(
    private val entries: List<WorldEntry>,
    private val budget: RetrievalBudget = RetrievalBudget.Default,
) {

    /** 全部条目（只读）。 */
    fun all(): List<WorldEntry> = entries

    /**
     * 这一轮该注入哪些条目。
     *
     * ⚠️ **结果必须确定**：同样的 `query` + 同样的库 ⇒ 同样的输出。
     *    所以最后一级排序键是 `id`（见下面的 `sortedWith`）。
     *
     * @param query 用户这一句。空串 = 只有核心条目在场
     */
    fun select(query: String): List<WorldEntry> {
        if (entries.isEmpty()) return emptyList()

        // ── ① 核心：alwaysInclude 无条件在场，但也要受核心预算约束 ──
        val core = entries.filter { it.alwaysInclude }
            .sortedWith(
                compareByDescending<WorldEntry> { it.priority }
                    // 同级按 id —— **确定性**
                    .thenBy { it.id },
            )
            .let { trimByChars(it, budget.coreChars) }

        // ── ② 场景：命中的按分数排 ──────────────────────────
        val remaining = budget.totalChars - core.sumOf { it.content.length }
        if (remaining <= 0) return core

        val matched = entries
            .asSequence()
            .filter { !it.alwaysInclude }
            .map { it to it.matchScore(query) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<WorldEntry, Int>> { it.second }
                    .thenByDescending { it.first.priority }
                    .thenBy { it.first.id },
            )
            .map { it.first }
            .toList()

        val picked = trimByChars(matched, remaining)
        return core + picked
    }

    /** 按累计字数裁剪，**至少保留一条**（一条都放不下时也不返回空）。 */
    private fun trimByChars(list: List<WorldEntry>, budgetChars: Int): List<WorldEntry> {
        if (budgetChars <= 0 || list.isEmpty()) return emptyList()
        val out = mutableListOf<WorldEntry>()
        var used = 0
        for (e in list) {
            val cost = e.content.length
            // ⚠️ 第一条无条件放行：预算再小，核心设定也该有一条在场。
            //    否则"预算配小了"会表现为"世界书完全没生效"，很难查。
            if (out.isNotEmpty() && used + cost > budgetChars) break
            out += e
            used += cost
        }
        return out
    }

    /**
     * 把选中的条目写成提示词。
     *
     * ⚠️ 只在**有条目时**才写。没人填世界书时不该出现一个空标题。
     */
    fun promptSection(query: String): String {
        val picked = select(query)
        if (picked.isEmpty()) return ""
        return buildString {
            appendLine("## 这个世界的设定")
            appendLine("以下是既成事实，不要与之矛盾，也不要主动复述。")
            for (e in picked) {
                append("- ").appendLine(e.content)
            }
        }.trimEnd()
    }

    // ── 变更（只有明确来源的才允许）──────────────────────────

    /**
     * 加一条**设定**。只应由**用户操作**调用。
     *
     * ⚠️ 刻意不提供"批量替换全部"的方法：那会让一次误操作抹掉
     *    用户写了很久的世界观。
     */
    fun withSetting(entry: WorldEntry): WorldBook {
        require(entry.kind == WorldEntryKind.SETTING) {
            "withSetting 只接受 SETTING —— FACT 请走 withFact"
        }
        return WorldBook(entries.filterNot { it.id == entry.id } + entry, budget)
    }

    /**
     * 加一条**事实**（对话中发生过的事）。
     *
     * @param confirmed 必须由**用户确认**。
     *   ⚠️ 这个参数不是形式主义：它是"AI 不许自己写设定"的执行点。
     *      没有它，AI 就会为了圆台词去造世界。
     */
    fun withFact(entry: WorldEntry, confirmed: Boolean): WorldBook {
        require(entry.kind == WorldEntryKind.FACT) {
            "withFact 只接受 FACT —— 设定请走 withSetting（而且只能由用户调）"
        }
        if (!confirmed) return this
        // ⚠️ 事实**永不允许覆盖设定**：同 id 时保留设定
        entries.firstOrNull { it.id == entry.id && it.kind == WorldEntryKind.SETTING }
            ?.let { return this }
        return WorldBook(entries.filterNot { it.id == entry.id } + entry, budget)
    }

    /** 用户删掉一条。 */
    fun without(id: String): WorldBook =
        WorldBook(entries.filterNot { it.id == id }, budget)
}
