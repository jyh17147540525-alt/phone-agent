package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryDiaryPrompt] 的离线钉子。
 *
 * ⚠️ 这些测试**不能证明"模型写得好"**。它们钉的是提示词的**契约**：
 *   几条不可省的指令在不在、内部编码有没有被喂给模型、
 *   模型输出的装饰有没有被清掉。
 */
class MemoryDiaryPromptTest {

    private fun prompt(
        raw: String = "U>我饿了\nA<那去吃面吧",
        kind: String = "事实",
        who: String = "阿泽",
    ) = MemoryDiaryPrompt.build(
        rawMemory = raw,
        kindLabel = kind,
        userAddress = who,
        personaBrief = "你叫小鱼，是一只嘴硬心软的猫。",
        dayHint = "今天",
    )

    @Test
    fun `提示词要求第一人称、总结性、称呼在前`() {
        val p = prompt()
        assertTrue("必须是第一人称：$p", p.contains("第一人称"))
        assertTrue("必须要求总结性：$p", p.contains("总结"))
        assertTrue("要说清顺序（先称呼）：$p", p.contains("先提到阿泽"))
    }

    @Test
    fun `提示词禁止写成对话记录`() {
        val p = prompt()
        assertTrue("要明说不要旁白/对话记录：$p", p.contains("不要写成旁白"))
        assertTrue(p.contains("不要逐句复述"))
    }

    @Test
    fun `允许吐槽与情绪`() {
        val p = prompt()
        assertTrue("要放开情绪：$p", p.contains("吐槽"))
        assertTrue(p.contains("可以嫌弃") || p.contains("心里发软"))
    }

    @Test
    fun `内部编码不能喂给模型`() {
        // ⚠️ 让模型去解读 U> / A< 这种内部符号，它会在日记里讨论这些符号。
        val p = prompt(raw = "U>我饿了\nA<那去吃面吧")
        assertFalse("不能出现 U>：$p", p.contains("U>"))
        assertFalse("不能出现 A<：$p", p.contains("A<"))
        assertTrue("但要保留内容：$p", p.contains("我饿了"))
    }

    @Test
    fun `技术细节不能出现在提示词素材里`() {
        val p = prompt(raw = "U>（我附了一个图片：content://media/x/1 —— 这是路径）")
        assertFalse("不能把 URI 喂给模型：$p", p.contains("content://"))
    }

    @Test
    fun `字数上限要写进提示词`() {
        val p = prompt()
        assertTrue("要给具体字数：$p", p.contains("120 字"))
    }

    @Test
    fun `提示词禁止类别标签混进正文`() {
        // ⚠️ 真机抓到："…耳朵到现在还烫着。阶段结论：规矩不能乱定。"
        //    类别是我们内部的分类，不该出现在她的本子上。
        val p = prompt(kind = "阶段结论")
        assertTrue("要明说不要写分类标签：$p", p.contains("不要**写「阶段结论：」") || p.contains("阶段结论："))
    }

    @Test
    fun `提示词要求只用称呼指代用户`() {
        // ⚠️ 真机抓到：模型用「他」指代了用户，立刻出戏。
        val p = prompt(who = "小鱼干")
        assertTrue("要禁用他/她/对方/用户：$p", p.contains("不要用「他」「她」"))
        assertTrue(p.contains("小鱼干"))
    }

    @Test
    fun `清洗会去掉正文中间的类别标签`() {
        assertEquals(
            "耳朵还烫着。规矩不能乱定。",
            MemoryDiaryPrompt.clean("耳朵还烫着。阶段结论：规矩不能乱定。"),
        )
        assertEquals("今天很好。", MemoryDiaryPrompt.clean("事实：今天很好。"))
    }

    @Test
    fun `清洗会去掉标题、引号包裹与「日记：」前缀`() {
        assertEquals("今天你来了。", MemoryDiaryPrompt.clean("日记：今天你来了。"))
        assertEquals("今天你来了。", MemoryDiaryPrompt.clean("「今天你来了。」"))
        assertEquals("正文", MemoryDiaryPrompt.clean("# 标题\n正文"))
        assertEquals("正文", MemoryDiaryPrompt.clean("---\n正文\n---"))
    }

    @Test
    fun `清洗会去掉（完）这类收尾`() {
        assertEquals("今天很好。", MemoryDiaryPrompt.clean("今天很好。\n（完）"))
        assertEquals("今天很好。", MemoryDiaryPrompt.clean("今天很好。\n——完——"))
    }

    @Test
    fun `清洗会在句末截断，不砍半句`() {
        val long = "第一句话在这里。" + "很长很长的第二句".repeat(40) + "。"
        val c = MemoryDiaryPrompt.clean(long)
        assertTrue("不能超上限太多：${c.length}", c.length <= MemoryDiaryPrompt.MAX_CHARS)
        assertFalse("不能砍在半句里还留个逗号：$c", c.endsWith("，"))
    }

    @Test
    fun `空输出返回空串`() {
        assertEquals("", MemoryDiaryPrompt.clean(""))
        assertEquals("", MemoryDiaryPrompt.clean("   "))
    }
}
