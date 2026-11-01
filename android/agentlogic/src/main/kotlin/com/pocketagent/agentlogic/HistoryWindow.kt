package com.pocketagent.agentlogic

/**
 * **历史窗口** —— 按 **token 预算**挑出要带进请求的历史。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01（目标 B：响应速度）
 * ═══════════════════════════════════════════════════════════════
 *
 * 此前历史**只按条数**限制（`recentForModel(sid, 41)`）。
 * 条数不是体积。同一条消息可能是 20 个字，也可能是 2 万字
 * （用户粘一份文档、角色回一段长文）。
 *
 * ⚠️ 后果很具体：
 *  · **首字延迟**随前缀线性增长 —— prefill 要先把整段历史算一遍
 *  · **成本**同样线性增长，而用户感觉不到自己在为历史付钱
 *  · 一条超长消息就能把上下文顶爆，触发厂商的截断或报错
 *
 * ⇒ 用 token 预算给历史加一道闸。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条刻意保守的规则
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 永远保留最近的 [keepRecent] 条**
 *    即使它们本身就超预算。空上下文比超预算更糟 ——
 *    模型会不知道刚才聊了什么，回答会前后不接。
 *
 * **② 从新到旧加，不从头截**
 *    对话的连续性在**最近**，不在最早。
 *    （与 `ContextCompressor` 的思路一致：旧的压缩成摘要，新的原样带。）
 *
 * **③ 预算按"估算"算，不追求精确**
 *    各家分词器不同，精确计数要额外一次网络调用。
 *    这里只用调用方给的估算函数。宁可留余量，不追求抠满。
 */
object HistoryWindow {

    /**
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-11-01：估算函数必须**准**，否则这个守卫等于没有
     * ═══════════════════════════════════════════════════════════════
     *
     * 本类只做"按预算取舍"，**估算由调用方给**。于是估算的准确性
     * 直接决定守卫有没有用 —— 而这一点很容易被忽略。
     *
     * 实测：调用方一开始手抄了一份 `字符数 / 3`，而 provider 自己的
     * `countTokens` 是 `中日韩字符 / 1.5 + 其他 / 4 + 每条 4`。
     * 对中文来说差了**整整一倍**（0.33 vs 0.67 token/字）。
     *
     * ⇒ 41 条中文对话约 2 万字：
     *   手抄版 ≈ 6800 token（不到 8000 预算，**不裁**）
     *   正确版 ≈ 13800 token（**该裁**）
     *   也就是说：那个守卫在它专门要防的场景里**根本不会触发**。
     *
     * ★ 结论：**估算要复用 provider 的 `countTokens`**，
     *   不要手抄一份 —— 抄的那份不会被测，也不会随 provider 更新。
     */

    /**
     * 默认预算（token）。
     *
     * ★ 取 8000 是保守值：主流模型上下文都在 32K 以上，
     *   8000 的输入既有足够连续性，又不会让 prefill 变成瓶颈。
     *   ⚠️ 这不是"越大越好"的参数 —— 每翻一倍，首字延迟跟着接近翻倍。
     */
    const val DEFAULT_BUDGET_TOKENS: Int = 8_000

    /** 默认至少保留的最近条数。 */
    const val DEFAULT_KEEP_RECENT: Int = 4

    /**
     * 选择结果。
     *
     * @param items 被选中的消息，**保持原顺序**（从旧到新）
     * @param droppedCount 丢掉了多少条
     * @param usedTokens 选中的总估算 token
     * @param overBudget 是否因为"最近 N 条本身超预算"而被迫超预算
     */
    data class Selection<T>(
        val items: List<T>,
        val droppedCount: Int,
        val usedTokens: Int,
        val overBudget: Boolean,
    ) {
        /** 有没有真的丢掉东西。界面/日志据此决定要不要提示。 */
        val trimmed: Boolean get() = droppedCount > 0
    }

    /**
     * 按预算挑历史。
     *
     * @param items 全部历史，**从旧到新**
     * @param budgetTokens token 预算
     * @param estimate 单条估算
     * @param keepRecent 至少保留最近几条
     */
    fun <T> select(
        items: List<T>,
        budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
        keepRecent: Int = DEFAULT_KEEP_RECENT,
        estimate: (T) -> Int,
    ): Selection<T> {
        if (items.isEmpty()) {
            return Selection(emptyList(), droppedCount = 0, usedTokens = 0, overBudget = false)
        }

        // ⚠️ 预算为负或 0 时仍然要保留最近 N 条 —— 不能返回空。
        val keep = keepRecent.coerceIn(1, items.size)

        // 先无条件保留最近 keep 条
        val tail = items.takeLast(keep)
        var used = tail.sumOf { estimate(it).coerceAtLeast(0) }
        var overBudget = false

        // ⚠️ 即使超预算也要保留它们 —— 连续性比预算重要
        if (used > budgetTokens) {
            overBudget = true
            return Selection(
                items = tail,
                droppedCount = items.size - tail.size,
                usedTokens = used,
                overBudget = true,
            )
        }

        // 再从新往旧加
        var taken = tail
        for (i in items.size - keep - 1 downTo 0) {
            val cost = estimate(items[i]).coerceAtLeast(0)
            if (used + cost > budgetTokens) break
            used += cost
            taken = listOf(items[i]) + taken
        }

        return Selection(
            items = taken,
            droppedCount = items.size - taken.size,
            usedTokens = used,
            overBudget = overBudget,
        )
    }

}
