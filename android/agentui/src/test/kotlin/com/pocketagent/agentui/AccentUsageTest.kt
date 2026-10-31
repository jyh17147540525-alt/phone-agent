package com.pocketagent.agentui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 强调色用途规则的测试。
 *
 * ⚠️ 这些测试**不能**检查真实源码（那是编译期/静态分析的事）。
 *    它们钉的是"规则本身自洽"：review 时按这个口径看，
 *    而不是每次凭感觉判断。
 */
class AccentUsageTest {

    @Test
    fun `文字必须用 AccentText`() {
        // ★ 这是唯一一个**真正违反 WCAG** 的用法。
        assertTrue(
            AccentUsage.isMisuse(AccentUsage.Purpose.TEXT, AccentUsage.Token.ACCENT),
        )
        assertFalse(
            AccentUsage.isMisuse(AccentUsage.Purpose.TEXT, AccentUsage.Token.ACCENT_TEXT),
        )
    }

    @Test
    fun `背景必须用 Accent`() {
        // ⚠️ 反用不违反规则，但会显闷 —— 所以仍算"用错"。
        assertFalse(
            AccentUsage.isMisuse(AccentUsage.Purpose.BACKGROUND, AccentUsage.Token.ACCENT),
        )
        assertTrue(
            AccentUsage.isMisuse(
                AccentUsage.Purpose.BACKGROUND,
                AccentUsage.Token.ACCENT_TEXT,
            ),
        )
    }

    @Test
    fun `图形用原色`() {
        // ★ 最反直觉的一条：图标不是"内容"，WCAG 对非文本只要 3.0。
        //   用加深版会让图标沉闷。
        assertFalse(
            AccentUsage.isMisuse(AccentUsage.Purpose.GRAPHIC, AccentUsage.Token.ACCENT),
        )
    }

    @Test
    fun `用错时的后果必须说清具体数字`() {
        // ★ 只说"用错了"没用。要说清"会怎样" ——
        //   否则对方无法判断严重性，可能就放着不改。
        val c = AccentUsage.consequence(
            AccentUsage.Purpose.TEXT,
            AccentUsage.Token.ACCENT,
        )
        assertTrue("要给出后果", c != null)
        assertTrue("要提到对比度数字：$c", "3.49" in c!! || "4.5" in c)
        assertTrue("要给出建议：$c", "AccentText" in c)
    }

    @Test
    fun `用对时不报后果`() {
        assertTrue(
            AccentUsage.consequence(
                AccentUsage.Purpose.TEXT,
                AccentUsage.Token.ACCENT_TEXT,
            ) == null,
        )
    }

    @Test
    fun `每种用途都有确定答案`() {
        // ⚠️ 不允许"两种都行"—— 那等于没有规则。
        for (p in AccentUsage.Purpose.entries) {
            val t = AccentUsage.colorFor(p)
            assertTrue("${p} 必须有确定的令牌", t == AccentUsage.Token.ACCENT ||
                t == AccentUsage.Token.ACCENT_TEXT)
        }
    }
}
