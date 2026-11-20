package com.pocketagent.filelogic

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提醒的时间换算。
 *
 * 这些用例钉的是**边界**，不是"能不能跑"：设完立刻就响、设到两年后、
 * `9:00` 少个零、`+0m` —— 每一种都会让用户觉得"这功能坏了"。
 */
class RemindersTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-20 10:00:00 +08:00 */
    private val now: Long = java.time.LocalDateTime.of(2026, 11, 20, 10, 0)
        .atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `相对时间按单位算准`() {
        val m30 = Reminders.parse("+30m", now, zone) as ReminderTime.Ok
        assertEquals(30L * 60_000, m30.atMillis - now)

        val h2 = Reminders.parse("+2h", now, zone) as ReminderTime.Ok
        assertEquals(2L * 3_600_000, h2.atMillis - now)

        val s45 = Reminders.parse("+45s", now, zone) as ReminderTime.Ok
        assertEquals(45_000L, s45.atMillis - now)

        val d1 = Reminders.parse("+1d", now, zone) as ReminderTime.Ok
        assertEquals(24L * 3_600_000, d1.atMillis - now)
    }

    @Test
    fun `只有时刻时取下一次_今天还没过就是今天`() {
        val at11 = Reminders.parse("11:00", now, zone) as ReminderTime.Ok
        val expected = java.time.LocalDateTime.of(2026, 11, 20, 11, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, at11.atMillis)
    }

    @Test
    fun `只有时刻且已经过了就顺延到明天`() {
        val at9 = Reminders.parse("09:00", now, zone) as ReminderTime.Ok
        val expected = java.time.LocalDateTime.of(2026, 11, 21, 9, 0)
            .atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, at9.atMillis)
    }

    @Test
    fun `不补零的写法也认`() {
        val at = Reminders.parse("9:05", now, zone)
        assertTrue(at is ReminderTime.Ok)
    }

    @Test
    fun `绝对日期两种写法等价`() {
        val a = Reminders.parse("2026-11-21 09:00", now, zone) as ReminderTime.Ok
        val b = Reminders.parse("2026-11-21T09:00", now, zone) as ReminderTime.Ok
        assertEquals(a.atMillis, b.atMillis)
    }

    @Test
    fun `太近的提醒被拒而不是设了立刻响`() {
        val bad = Reminders.parse("+3s", now, zone)
        assertTrue(bad is ReminderTime.Bad)
        assertTrue((bad as ReminderTime.Bad).reason.contains("至少 5 秒"))
    }

    @Test
    fun `回头的时间被拒`() {
        assertTrue(Reminders.parse("2026-11-19 09:00", now, zone) is ReminderTime.Bad)
    }

    @Test
    fun `一年以外被拒`() {
        val bad = Reminders.parse("+400d", now, zone)
        assertTrue(bad is ReminderTime.Bad)
        assertTrue((bad as ReminderTime.Bad).reason.contains("一年"))
    }

    @Test
    fun `零或负的相对时间被拒`() {
        assertTrue(Reminders.parse("+0m", now, zone) is ReminderTime.Bad)
        assertTrue(Reminders.parse("+-5m", now, zone) is ReminderTime.Bad)
    }

    @Test
    fun `单位写错要指出来`() {
        val bad = Reminders.parse("+30x", now, zone) as ReminderTime.Bad
        assertTrue(bad.reason.contains("s/m/h/d"))
    }

    @Test
    fun `读不出时间时给三种写法`() {
        val bad = Reminders.parse("明天早上", now, zone) as ReminderTime.Bad
        assertTrue(bad.reason.contains("09:00"))
        assertTrue(bad.reason.contains("+30m"))
    }

    @Test
    fun `空字符串被拒`() {
        assertTrue(Reminders.parse("   ", now, zone) is ReminderTime.Bad)
    }

    @Test
    fun `describe 会说多久之后`() {
        val at = now + 90L * 60_000
        val text = Reminders.describe(at, now, zone)
        assertTrue(text.contains("1 小时 30 分钟后"))
        assertTrue(text.contains("2026-11-20 11:30"))
    }

    @Test
    fun `describe 对已经过点的不说负数分钟`() {
        val text = Reminders.describe(now - 60_000, now, zone)
        assertTrue(text.contains("不到 1 分钟后"))
    }
}
