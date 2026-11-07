package com.pocketagent.agentlogic.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 限额信号识别。
 *
 * 这一层的价值全在**不误判**上：把网络错误页当成"到上限了"，用户的任务会白停一天；
 * 漏判则只是多重试两次。所以词表只收"几乎不可能是别的原因"的说法。
 */
class LimitSignalsTest {

    private fun digest(vararg texts: String) = ScreenDigest(texts = texts.toList())

    @Test
    fun `典型限额文案都能命中`() {
        assertEquals("今日已达上限，明天再来", LimitSignals.match(digest("今日已达上限，明天再来")))
        assertEquals("操作过于频繁，请稍后再试", LimitSignals.match(digest("操作过于频繁，请稍后再试")))
        assertEquals("今日次数已用完", LimitSignals.match(digest("今日次数已用完")))
        assertEquals("额度已用尽", LimitSignals.match(digest("额度已用尽")))
    }

    @Test
    fun `命中时返回的是屏上原文而不是我们的词`() {
        val hit = LimitSignals.match(digest("发送失败", "该操作过于频繁，请 5 分钟后再试"))
        assertEquals("该操作过于频繁，请 5 分钟后再试", hit)
    }

    @Test
    fun `普通网络错误不算限额`() {
        // 「请稍后再试」单独出现同样会出现在网络错误页上 —— 刻意不收
        assertNull(LimitSignals.match(digest("网络连接失败，请稍后再试")))
        assertNull(LimitSignals.match(digest("加载失败")))
        assertNull(LimitSignals.match(digest("操作失败，请重试")))
    }

    @Test
    fun `读不到屏幕时不算限额`() {
        assertNull(LimitSignals.match(null))
    }

    @Test
    fun `空白文本不会被当成命中`() {
        assertNull(LimitSignals.match(digest("  ", "")))
    }

    @Test
    fun `建议文案带原文且说明重试没用`() {
        val advice = LimitSignals.advice("今日已达上限")
        assertTrue(advice.contains("今日已达上限"))
        assertTrue(advice.contains("重试没用"))
    }

    @Test
    fun `词表本身不含含糊说法`() {
        val vague = listOf("请稍后再试", "操作失败", "加载失败", "网络错误")
        for (word in vague) {
            assertTrue(
                "词表里不该出现含糊说法：$word",
                LimitSignals.phrases.none { it == word },
            )
        }
    }
}
