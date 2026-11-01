package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **历史窗口的取舍规则**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"为了省 token 把上下文砍没了"
 * ═══════════════════════════════════════════════════════════════
 *
 * 预算逻辑最容易犯的错不是"没省下来"，而是**省过头**：
 * 砍到模型不知道刚才聊了什么，回答前后不接 ——
 * 而用户会以为是模型变笨了。
 */
class HistoryWindowTest {

    /** 每条消息按固定 token 记，便于精确验算。 */
    private fun fixed(n: Int): (String) -> Int = { n }

    private fun msgs(count: Int): List<String> = (1..count).map { "m$it" }

    // ═══════════════════════════════════════════════════════════
    //  ★ ① 永远保留最近 N 条
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `最近几条永远保留 即使超预算`() {
        // ══════════════════════════════════════════════════════
        //  ★ 空上下文比超预算更糟
        // ══════════════════════════════════════════════════════
        // 砍到模型不知道刚才聊了什么，回答会前后不接，
        // 而用户会以为模型变笨了。
        val r = HistoryWindow.select(
            items = msgs(10),
            budgetTokens = 10,     // 只够 1 条
            keepRecent = 4,
            estimate = fixed(100), // 每条 100
        )
        assertEquals("最近 4 条必须全在", 4, r.items.size)
        assertEquals(listOf("m7", "m8", "m9", "m10"), r.items)
        assertTrue("应当标记为超预算", r.overBudget)
    }

    @Test
    fun `保留的总是最靠后的`() {
        val r = HistoryWindow.select(
            items = msgs(20),
            budgetTokens = 250,
            keepRecent = 2,
            estimate = fixed(100),
        )
        assertEquals(listOf("m19", "m20"), r.items)
    }

    @Test
    fun `keepRecent 大于总数时不越界`() {
        val r = HistoryWindow.select(
            items = msgs(3),
            budgetTokens = 1,
            keepRecent = 99,
            estimate = fixed(1),
        )
        assertEquals(3, r.items.size)
        assertEquals(0, r.droppedCount)
    }

    @Test
    fun `keepRecent 为 0 或负数时仍保留至少一条`() {
        // ⚠️ 返回空列表会让模型收到一个"没有历史"的请求 ——
        //    那比多带一条更糟。
        for (k in listOf(0, -5)) {
            val r = HistoryWindow.select(
                items = msgs(5),
                budgetTokens = 1,
                keepRecent = k,
                estimate = fixed(100),
            )
            assertTrue("至少保留一条（keepRecent=$k）", r.items.isNotEmpty())
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 从新往旧加
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `预算够时能多带几条`() {
        val r = HistoryWindow.select(
            items = msgs(10),
            budgetTokens = 500,
            keepRecent = 2,
            estimate = fixed(100),
        )
        assertEquals("500/100 = 5 条", 5, r.items.size)
        assertEquals(listOf("m6", "m7", "m8", "m9", "m10"), r.items)
        assertFalse(r.overBudget)
    }

    @Test
    fun `顺序保持从旧到新`() {
        // ★ 顺序反了会让模型看到"先答后问"
        val r = HistoryWindow.select(
            items = msgs(8),
            budgetTokens = 400,
            keepRecent = 2,
            estimate = fixed(100),
        )
        assertEquals(r.items.sortedBy { it }, r.items)
    }

    @Test
    fun `预算充裕时全部保留`() {
        val r = HistoryWindow.select(
            items = msgs(5),
            budgetTokens = 100_000,
            keepRecent = 2,
            estimate = fixed(10),
        )
        assertEquals(5, r.items.size)
        assertEquals(0, r.droppedCount)
        assertFalse(r.trimmed)
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 估算
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `长消息会被算得更贵`() {
        // 一条 5000 token 的消息 + 若干短消息
        val items = listOf("长", "短", "短", "短")
        val r = HistoryWindow.select(
            items = items,
            budgetTokens = 100,
            keepRecent = 1,
            estimate = { if (it == "长") 5000 else 10 },
        )
        // 最近 1 条（"短"）在预算内；往前加"短"×2 后到 30；
        // 再加"长"会到 5030 > 100 ⇒ 停
        assertEquals(listOf("短", "短", "短"), r.items)
        // 被丢掉的是最旧那一条（即 "长"）
        assertEquals(1, r.droppedCount)
        assertEquals("长", items.first())
        assertFalse("长消息不该被选中", r.items.contains("长"))
    }

    @Test
    fun `估算返回负数时按零处理`() {
        // ⚠️ 负成本会让 used 变小，接近无限制 —— 防御性归零
        val r = HistoryWindow.select(
            items = msgs(5),
            budgetTokens = 10,
            keepRecent = 1,
            estimate = { -100 },
        )
        assertTrue(r.usedTokens >= 0)
        assertTrue("负成本不该让它无限带", r.items.size <= 5)
    }

    @Test
    fun `usedTokens 是选中项之和`() {
        val r = HistoryWindow.select(
            items = listOf("a", "b", "c"),
            budgetTokens = 100,
            keepRecent = 1,
            estimate = { it.length * 10 },
        )
        assertEquals(r.items.sumOf { it.length * 10 }, r.usedTokens)
    }

    // ═══════════════════════════════════════════════════════════
    //  边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空历史返回空`() {
        val r = HistoryWindow.select(emptyList<String>(), 1000, 4, fixed(1))
        assertEquals(0, r.items.size)
        assertEquals(0, r.droppedCount)
        assertFalse(r.trimmed)
    }

    @Test
    fun `单条历史不会被丢`() {
        val r = HistoryWindow.select(listOf("only"), budgetTokens = 0, keepRecent = 4, estimate = fixed(999))
        assertEquals(listOf("only"), r.items)
    }

}
