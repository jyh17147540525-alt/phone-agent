package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **机制词不许进提示词**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这条规矩的来由
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户："系统又信誉分你也不能让模型说出来呀，背后偷偷记就行了"
 *
 * 泄漏路径是真的：
 * ```
 *   旧记忆「用一套信誉分/记账机制督促他吃饭」
 *     → 记忆进提示词 → 模型看见「记账」→ 说出来「行，记账上了」
 * ```
 */
class PromptHygieneTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ 含机制词的句子整句丢掉
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `含记账的句子被丢掉`() {
        val s = PromptHygiene.scrub("他建立了一套记账机制来管我吃饭。")
        assertEquals("", s)
    }

    @Test
    fun `只丢含机制词的那一句 同段其它句子保留`() {
        // ⚠️ 整段丢会损失有效记忆
        val s = PromptHygiene.scrub("他爱吃白米饭。他给我记分。汤圆要黑芝麻的。")
        assertTrue("有效内容要留下：$s", s.contains("白米饭"))
        assertTrue(s.contains("黑芝麻"))
        assertFalse("机制句要丢掉：$s", s.contains("记分"))
    }

    @Test
    fun `换行分隔也能逐句判断`() {
        val s = PromptHygiene.scrub("今天下雨。\n信誉分扣光了。\n他忘了带伞。")
        assertTrue(s.contains("下雨"))
        assertTrue(s.contains("忘了带伞"))
        assertFalse(s.contains("信誉分"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 三组词都要拦
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `账务词被拦`() {
        for (w in listOf("记账", "记分", "信誉分", "额度", "账本", "积分")) {
            assertEquals("「${w}」必须被拦", "", PromptHygiene.scrub("他有一套$w。"))
        }
    }

    @Test
    fun `实现词被拦`() {
        for (w in listOf("提示词", "上下文", "系统", "模型", "字段")) {
            assertEquals("「${w}」必须被拦", "", PromptHygiene.scrub("关于${w}的事。"))
        }
    }

    @Test
    fun `表演管理词被拦`() {
        for (w in listOf("人设", "设定", "扮演", "出戏")) {
            assertEquals("「${w}」必须被拦", "", PromptHygiene.scrub("我按${w}来。"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不能误伤
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `干净的记忆原样返回`() {
        val clean = "他手被烫伤过，油炸的那些我一口都不让他碰。"
        assertEquals(clean, PromptHygiene.scrub(clean))
    }

    @Test
    fun `口语化的正常用词不被误判`() {
        // ⚠️ 「这事我记着」是正常台词，不是机制词
        val s = PromptHygiene.scrub("这事我记着。你别想赖。")
        assertEquals("这事我记着。你别想赖。", s)
    }

    @Test
    fun `空文本不炸`() {
        assertEquals("", PromptHygiene.scrub(""))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 丢弃必须可观测
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `批量过滤会报告丢了几条`() {
        val (kept, dropped) = PromptHygiene.scrubAll(
            listOf(
                "他爱吃白米饭。",
                "他有一套记账机制。",
                "汤圆要黑芝麻。",
                "按人设我该嘴硬。",
            ),
        )
        assertEquals(2, kept.size)
        assertEquals("丢了几条必须报出来，否则是静默少做一件事", 2, dropped)
    }

    @Test
    fun `hits 能指出命中了什么`() {
        val h = PromptHygiene.hits("他给我记分，还说要扣信誉分")
        assertTrue(h.contains("记分"))
        assertTrue(h.contains("信誉分"))
    }

    @Test
    fun `hits 对干净文本返回空`() {
        assertTrue(PromptHygiene.hits("行行行，听你的。").isEmpty())
    }
}
