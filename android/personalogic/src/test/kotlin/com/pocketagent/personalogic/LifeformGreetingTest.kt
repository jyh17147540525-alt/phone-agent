package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 在场模式页首那句大标题。
 *
 * ⚠️ 这一组测试守的不是"文案好不好看"，是**它不能变成旁白**：
 *    大标题原样返回 beat 文案（第一人称），一旦有人给它加"她"，
 *    这一屏就从"她刚才在干嘛"变成"旁白在讲她"。
 */
class LifeformGreetingTest {

    private val day = LifeformDay.forDay(dayIndex = 20_000L)

    // ── 大标题就是她那一段的原文 ─────────────────────────────

    @Test
    fun `大标题原样是那一句`() {
        val beat = day.beats[2]
        val line = LifeformGreeting.headline(day, beat.atHour)
        assertEquals(line, beat.text)
    }

    @Test
    fun `大标题前面不加她`() {
        for (beat in day.beats) {
            val line = LifeformGreeting.headline(day, beat.atHour)
            assertFalse(line.startsWith("她"))
        }
    }

    @Test
    fun `大标题不套引号`() {
        val beat = day.beats.first()
        val line = LifeformGreeting.headline(day, beat.atHour)
        assertTrue(!line.contains("「"))
        assertTrue(!line.contains("\""))
    }

    @Test
    fun `取到的是当时那一段不是往前数的那一段`() {
        // at() 的语义是"到这一刻为止最近的一条"。13.5 点取的必须是
        // <= 13.5 里最靠后的那条，而不是下一条。
        val line = LifeformGreeting.headline(day, 13.5)
        val expected = day.beats.last { it.atHour <= 13.5 }
        assertEquals(line, expected.text)
    }

    // ── 还没开始 ─────────────────────────────────────────────

    @Test
    fun `第一条之前说实话`() {
        val early = LifeformDay.forDay(dayIndex = 20_000L)
        val firstAt = early.beats.first().atHour
        // 第一条之前（但要躲过同一天可能不止一条落在前面）
        val probe = firstAt - 0.1
        val line = LifeformGreeting.headline(early, probe)
        assertTrue(line.isNotEmpty())
        // 不能编"她正在……"
        assertTrue(!line.contains("正在"))
        assertTrue(!line.contains("她"))
    }

    @Test
    fun `还没开始的那句同一天不会变`() {
        // 空态每次重组都会重算。同一 dayIndex 必须拿到同一句，
        // 否则界面看起来像在闪。
        val a = LifeformGreeting.headline(day, 0.0)
        val b = LifeformGreeting.headline(day, 0.0)
        val c = LifeformGreeting.headline(day.copy(), 0.0)
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `不同天的开场不一样`() {
        // 每天都同一句，第二天就会被认出来是模板。
        val lines = (20_000L until 20_006L).map {
            LifeformGreeting.headline(LifeformDay.forDay(it), 0.0)
        }
        assertTrue(lines.toSet().size >= 2)
    }

    @Test
    fun `负 dayIndex 不会越界`() {
        // % 在 Kotlin 里对负数返回负值 —— 直接用会 IndexOutOfBounds。
        for (d in -5L..5L) {
            val line = LifeformGreeting.headline(LifeformDay.forDay(d), 0.0)
            assertTrue(line.isNotEmpty())
        }
    }

    // ── 小字 ────────────────────────────────────────────────

    @Test
    fun `小字说的是她的今天`() {
        val hint = LifeformGreeting.hint(14.5)
        assertTrue(hint.contains("她的今天"))
        assertTrue(hint.contains("14 点半"))
    }

    @Test
    fun `小字不说第二遍她在做什么`() {
        // 上面那句已经说了她在干嘛。小字再说一遍会变成
        // "一个人待着 / 一个人待着"。
        val beat = day.beats.first()
        val head = LifeformGreeting.headline(day, beat.atHour)
        val hint = LifeformGreeting.hint(beat.atHour)
        assertTrue(!hint.contains(head))
    }

    @Test
    fun `小字里没有机器词`() {
        val hint = LifeformGreeting.hint(9.0)
        for (word in listOf("模型", "提示词", "系统", "AI", "记忆库", "设定", "模式")) {
            assertTrue(!hint.contains(word))
        }
    }

    // ── 时刻兜底 ─────────────────────────────────────────────

    @Test
    fun `NaN 当时刻不会崩`() {
        val line = LifeformGreeting.headline(day, Double.NaN)
        assertTrue(line.isNotEmpty())
        assertTrue(LifeformGreeting.hint(Double.NaN).contains("12 点"))
    }

    @Test
    fun `越界的时刻被夹住`() {
        assertTrue(LifeformGreeting.hint(-3.0).contains("0 点"))
        assertTrue(LifeformGreeting.hint(99.0).contains("23 点"))
    }
}
