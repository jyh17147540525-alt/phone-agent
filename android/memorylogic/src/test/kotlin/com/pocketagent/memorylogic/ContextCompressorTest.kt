package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextCompressor] 的离线钉子。
 *
 * ★ 这些测试绝大多数在钉"**不该丢的东西没丢**"：
 *   压缩的故障形态几乎都是**丢得太多**，而丢多了在界面上完全看不出来 ——
 *   模型只是开始重复做同一件已经失败过的事，或者把否定听成肯定。
 */
class ContextCompressorTest {

    private fun user(text: String, at: Long = 1_700_000_000_000L, id: String = "u$text".take(12)) =
        Turn(id = id, sessionId = "s", role = TurnRole.USER, text = text, at = at)

    private fun ai(text: String, at: Long = 1_700_000_000_000L, id: String = "a$text".take(12)) =
        Turn(id = id, sessionId = "s", role = TurnRole.ASSISTANT, text = text, at = at)

    @Test
    fun `轮次太少时什么都不压`() {
        val turns = listOf(user("你好"), ai("你好呀"))
        val r = ContextCompressor.compress(turns, keepRecent = 8)
        assertEquals("不该产生任何压缩段", 0, r.segments.size)
        assertEquals("最近几轮原样保留", 2, r.keptRecent.size)
        assertEquals(0, r.savedChars)
    }

    @Test
    fun `超过阈值且轮次够多才建议压缩`() {
        val few = (1..5).map { user("很长的内容".repeat(100)) }
        assertFalse("轮次不够时不压", ContextCompressor.shouldCompress(few))

        val many = (1..20).map { user("很长的内容".repeat(100)) }
        assertTrue("又多又长才压", ContextCompressor.shouldCompress(many))
    }

    @Test
    fun `最近若干轮永远原样保留`() {
        val turns = (1..30).map { user("第 $it 句话，内容长一点以便触发压缩".repeat(10), id = "t$it") }
        val r = ContextCompressor.compress(turns, keepRecent = 8)
        assertEquals("最近 8 轮必须逐字保留 —— 那是接得上话的关键", 8, r.keptRecent.size)
        assertEquals("t30", r.keptRecent.last().id)
        assertTrue("老的部分进了压缩段", r.segments.isNotEmpty())
    }

    @Test
    fun `压缩结果确实更短`() {
        val turns = (1..30).map { ai("嗯嗯嗯那个就是说，其实吧，这件事我确实已经做过了。".repeat(6), id = "t$it") }
        val r = ContextCompressor.compress(turns, keepRecent = 8)
        assertTrue("压完必须真的更短，实际省了 ${r.savedChars}", r.worthIt)
        assertTrue(r.afterChars < r.beforeChars)
    }

    @Test
    fun `失败那一轮必须带感叹号标记`() {
        // ★ 压缩最容易犯的错：把失败压成"尝试过X"，于是模型下一轮又去试。
        val line = ContextCompressor.line(ai("打开微信失败：无障碍权限未开启"))
        assertTrue("失败必须显式标出来：$line", line.contains("!"))
    }

    @Test
    fun `成功那一轮不带失败标记`() {
        val line = ContextCompressor.line(ai("已经帮你打开了微信"))
        assertFalse("别乱标失败 —— 误标会让模型不敢再试：$line", line.contains("!"))
    }

    @Test
    fun `否定词不能被当成噪声删掉`() {
        // ⚠️ 这是整套压缩里最危险的一处：删掉"不"字，意思会反过来。
        val out = ContextCompressor.normalize("我不去，也不要那个")
        assertTrue("否定词必须留着：$out", out.contains("不"))
    }

    @Test
    fun `语气词会被抹掉`() {
        val out = ContextCompressor.normalize("嗯嗯，哈哈哈，其实吧，我觉得啦")
        assertFalse("噪声词应被删：$out", out.contains("哈哈哈"))
        assertTrue("实义部分要留：$out", out.contains("我觉得"))
    }

    @Test
    fun `重复标点被压成一个`() {
        assertEquals("好。", ContextCompressor.normalize("好。。。。。"))
        assertEquals("真的吗？", ContextCompressor.normalize("真的吗？？？"))
    }

    @Test
    fun `每个压缩段都能回溯到源轮次`() {
        val turns = (1..20).map { user("第 $it 句".repeat(50), id = "t$it") }
        val r = ContextCompressor.compress(turns, keepRecent = 8)
        val traced = r.segments.flatMap { it.sourceTurnIds }
        assertEquals("源轮次不能重复也不能丢", traced.size, traced.distinct().size)
        assertEquals("老的部分 12 轮全都要有溯源", 12, traced.size)
        assertFalse("最近几轮不该出现在压缩段的溯源里", traced.contains("t20"))
    }

    @Test
    fun `压缩段是按时间切开的，不是压成一坨`() {
        val turns = (1..20).map { user("第 $it 句".repeat(50), id = "t$it") }
        val r = ContextCompressor.compress(turns, keepRecent = 8)
        assertTrue("用户要求分段融入记忆，所以段数要大于 1，实际 ${r.segments.size}", r.segments.size > 1)
        // 段内顺序必须与时间一致
        val firstIds = r.segments.first().sourceTurnIds
        assertEquals("t1", firstIds.first())
    }

    @Test
    fun `提示词段落为空时不写任何东西`() {
        val empty = ContextCompressor.compress(listOf(user("你好")), keepRecent = 8)
        assertEquals("没有压缩记忆就返回空串", "", ContextCompressor.promptSection(empty))
    }

    @Test
    fun `提示词段落里说清这些不是刚说的`() {
        val turns = (1..20).map { ai("这是很长的一段说明文字".repeat(30), id = "t$it") }
        val section = ContextCompressor.promptSection(ContextCompressor.compress(turns, keepRecent = 8))
        assertTrue("要说明这是已经聊过的事", section.contains("已经聊过"))
        assertTrue("要给记号表，否则模型读不懂", section.contains("U>"))
        assertTrue("要提醒不要复述成原文", section.contains("别当成对方刚说的"))
    }

    @Test
    fun `空文本的轮次不会让压缩崩掉`() {
        val turns = (1..12).map { user("") } + listOf(user("x"))
        val r = ContextCompressor.compress(turns, keepRecent = 2)
        assertTrue("空文本也要产出可用的记号行", r.segments.isNotEmpty())
    }
}
