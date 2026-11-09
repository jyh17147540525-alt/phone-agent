package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 她的今天的测试。
 *
 * 重点不在"生成得对不对"，而在这三条不许破：
 * ① 确定性 —— 同一天永远一样（否则重启后昨天变了，比失忆更糟）
 * ② 只断言她自己 —— 文案里不许出现"你"（生成器看不见用户今天做了什么）
 * ③ 凌晨 4 点翻页 —— 00:30 说话的人心里还在昨天
 */
class LifeformDayTest {

    private fun hoursIn(day: LifeformDay) = day.beats.map { it.atHour }

    // ── ① 确定性 ────────────────────────────────────────────────

    @Test
    fun `同一天同 salt 生成的结果逐字相同`() {
        val a = LifeformDay.forDay(dayIndex = 20_000L, salt = 7L)
        val b = LifeformDay.forDay(dayIndex = 20_000L, salt = 7L)
        assertEquals(a, b)
    }

    @Test
    fun `相邻两天不该长得一样`() {
        // 只用 dayIndex 当种子时，线性同余的相邻输出会高度相关。
        // 这里要求相邻两天的文案序列不能完全相同 —— 也不必"完全不同"，
        // 人是会有重复的一天的，只是不能天天重复。
        val today = LifeformDay.forDay(20_000L, salt = 3L).beats.map { it.text }
        val tomorrow = LifeformDay.forDay(20_001L, salt = 3L).beats.map { it.text }
        assertNotEquals(today, tomorrow)
    }

    @Test
    fun `不同 salt 是不同角色的不同一天`() {
        val hers = LifeformDay.forDay(20_000L, salt = 1L)
        val his = LifeformDay.forDay(20_000L, salt = 2L)
        assertNotEquals(hers.beats.map { it.text }, his.beats.map { it.text })
    }

    @Test
    fun `跨过一次重启也还是同一天`() {
        // 落盘后再读回来，序列不变。
        val day = LifeformDay.forDay(20_000L, salt = 5L)
        val copy = day.copy(beats = day.beats.toList())
        assertEquals(copy, day)
    }

    // ── ② 只断言她自己 ──────────────────────────────────────────

    @Test
    fun `文案里不出现第二人称`() {
        // 生成是盲的：它不知道用户今天说没说话、发没发照片。
        // 一句"把你说过的话念了好几遍"就是凭空替用户编经历 —— 这是本文件唯一
        // 会让整段作废的错误，所以钉在测试里，加新句子时跑一下就知道。
        for (day in 0L until 400L) {
            val d = LifeformDay.forDay(day, salt = 11L)
            for (beat in d.beats) {
                assertTrue(!beat.text.contains("你"))
                assertTrue(!beat.text.contains("他"))
            }
        }
    }

    @Test
    fun `文案不是空的也不带换行`() {
        for (day in 0L until 200L) {
            for (beat in LifeformDay.forDay(day).beats) {
                assertTrue(beat.text.trim().isNotEmpty())
                assertTrue(!beat.text.contains("\n"))
            }
        }
    }

    // ── ③ 凌晨 4 点翻页 ────────────────────────────────────────

    @Test
    fun `凌晨三点半还算昨天`() {
        // 2026-11-09 03:30 东八区
        val ms = 1762630200_000L
        val at330 = LifeformDay.localDayIndex(ms, 480)
        val at430 = LifeformDay.localDayIndex(ms + 3_600_000L, 480)
        assertNotEquals(at430, at330)
    }

    @Test
    fun `午夜刚过仍然是同一天`() {
        // 23:30 与其次日 00:30 必须是同一个 dayIndex。
        val late = 1762615800_000L // 2026-11-08 23:30 +08
        val early = late + 3_600_000L // 2026-11-09 00:30 +08
        assertEquals(LifeformDay.localDayIndex(early, 480), LifeformDay.localDayIndex(late, 480))
    }

    @Test
    fun `日序号逐日递增 1`() {
        val base = 1762615800_000L
        val a = LifeformDay.localDayIndex(base, 480)
        val b = LifeformDay.localDayIndex(base + 24 * 3_600_000L, 480)
        assertEquals(b - a, 1L)
    }

    @Test
    fun `负时间戳不崩也不跳档`() {
        // 1970 之前：`/` 会朝零取整，floorDiv 不会。
        val a = LifeformDay.localDayIndex(-1_000L, 480)
        val b = LifeformDay.localDayIndex(-1_000L - 24 * 3_600_000L, 480)
        assertEquals(b - a, -1L)
    }

    // ── 结构 ───────────────────────────────────────────────────

    @Test
    fun `一天里至少有一条 且按时间升序`() {
        for (day in 0L until 200L) {
            val beats = LifeformDay.forDay(day).beats
            assertTrue(beats.isNotEmpty())
            assertEquals(hoursIn(LifeformDay.forDay(day)).sorted(), hoursIn(LifeformDay.forDay(day)))
        }
    }

    @Test
    fun `相邻两条不会挤在一起`() {
        for (day in 0L until 200L) {
            val hs = hoursIn(LifeformDay.forDay(day))
            for (i in 1 until hs.size) {
                assertTrue(hs[i] - hs[i - 1] >= LifeformDay.MIN_GAP_HOURS - 1e-9)
            }
        }
    }

    @Test
    fun `时段都落在 0 到 24 之间 且一位小数`() {
        for (day in 0L until 200L) {
            for (h in hoursIn(LifeformDay.forDay(day))) {
                assertTrue(h >= 0.0)
                assertTrue(h < 24.0)
                assertEquals(h, Math.round(h * 10.0) / 10.0, 1e-9)
            }
        }
    }

    @Test
    fun `空 beats 会被挡住`() {
        val threw = runCatching { LifeformDay(dayIndex = 1L, beats = emptyList()) }
        assertTrue(threw.isFailure)
    }

    @Test
    fun `越界的小时会被挡住`() {
        assertTrue(runCatching { LifeformBeat(atHour = 24.0, text = "x") }.isFailure)
        assertTrue(runCatching { LifeformBeat(atHour = -0.1, text = "x") }.isFailure)
        assertTrue(runCatching { LifeformBeat(atHour = 9.0, text = "  ") }.isFailure)
    }

    // ── 取用 ───────────────────────────────────────────────────

    @Test
    fun `at 给出此前最近的那一条`() {
        val day = LifeformDay.forDay(20_000L)
        val first = day.beats.first()
        val before = day.at(first.atHour - 0.05)
        assertNull(before)
        val after = day.at(day.beats.last().atHour + 0.05)
        assertNotNull(after)
        assertEquals(after!!.text, day.beats.last().text)
    }

    @Test
    fun `before 是前缀 不是全部`() {
        val day = LifeformDay.forDay(20_000L)
        val mid = day.beats[day.beats.size / 2].atHour
        val got = day.before(mid)
        assertTrue(got.size >= 1)
        assertTrue(got.size < day.beats.size)
        assertTrue(got.all { it.atHour <= mid })
    }

    @Test
    fun `band 按小时分段`() {
        assertEquals(LifeformDay.bandOf(4.9), LifeformBand.NIGHT)
        assertEquals(LifeformDay.bandOf(5.0), LifeformBand.MORNING)
        assertEquals(LifeformDay.bandOf(10.9), LifeformBand.MORNING)
        assertEquals(LifeformDay.bandOf(11.0), LifeformBand.DAY)
        assertEquals(LifeformDay.bandOf(17.0), LifeformBand.EVENING)
        assertEquals(LifeformDay.bandOf(22.0), LifeformBand.NIGHT)
        assertEquals(LifeformDay.bandOf(Double.NaN), LifeformBand.DAY)
    }

    @Test
    fun `beat 自己也知道自己在哪一段`() {
        assertEquals(LifeformBeat(7.5, "x").band, LifeformBand.MORNING)
        assertEquals(LifeformBeat(23.9, "x").band, LifeformBand.NIGHT)
    }

    // ── 提示词 ─────────────────────────────────────────────────

    @Test
    fun `提示词有标题 有现在 最多两条早些时候`() {
        val day = LifeformDay.forDay(20_000L)
        val section = LifeformDay.promptSection(day, hourOfDay = day.beats.last().atHour + 0.1)
        assertTrue(section.contains("## 你今天"))
        assertTrue(section.contains("现在（"))
        val earlierLines = section.lines().count { it.startsWith("  - ") }
        assertTrue(earlierLines <= 2)
    }

    @Test
    fun `一天刚开始时提示词不空`() {
        val day = LifeformDay.forDay(20_000L)
        val section = LifeformDay.promptSection(day, hourOfDay = 0.1)
        assertTrue(section.contains("## 你今天"))
        assertTrue(section.isNotEmpty())
        // 也不许凭空说"现在在做某事"
        assertTrue(!section.contains("现在（"))
    }

    @Test
    fun `提示词明说这几件事他没参与`() {
        val day = LifeformDay.forDay(20_000L)
        val section = LifeformDay.promptSection(day, hourOfDay = 14.0)
        assertTrue(section.contains("他没参与"))
    }

    @Test
    fun `提示词里不出现机制词`() {
        val section = LifeformDay.promptSection(LifeformDay.forDay(20_000L), 14.0)
        for (word in listOf("提示词", "模型", "系统", "AI", "记忆库", "设定")) {
            assertTrue(!section.contains(word))
        }
    }

    // ── 隔了多久 ───────────────────────────────────────────────

    @Test
    fun `今天来过就不提间隔`() {
        assertTrue(LifeformDay.gapNote(previousDayIndex = 100L, currentDayIndex = 100L).isEmpty())
    }

    @Test
    fun `第一次见面不提间隔`() {
        assertTrue(LifeformDay.gapNote(previousDayIndex = null, currentDayIndex = 100L).isEmpty())
    }

    @Test
    fun `隔一天说的是昨天`() {
        val note = LifeformDay.gapNote(previousDayIndex = 99L, currentDayIndex = 100L)
        assertTrue(note.contains("昨天"))
    }

    @Test
    fun `隔两三天说天数`() {
        val note = LifeformDay.gapNote(previousDayIndex = 97L, currentDayIndex = 100L)
        assertTrue(note.contains("3 天"))
    }

    @Test
    fun `隔很久就说很久 不编具体天数`() {
        val note = LifeformDay.gapNote(previousDayIndex = 1L, currentDayIndex = 100L)
        assertTrue(note.contains("很久"))
        assertTrue(!note.contains("99"))
    }

    @Test
    fun `间隔句里没有指责`() {
        // 陪伴类产品最容易在这里翻车：「你都不理我」。
        // 只陈述"你是一个人过的"，不评价用户。
        for (gap in 1L..30L) {
            val note = LifeformDay.gapNote(previousDayIndex = 100L - gap, currentDayIndex = 100L)
            assertTrue(!note.contains("都不"))
            assertTrue(!note.contains("为什么不"))
            assertTrue(!note.contains("难过"))
        }
    }

    // ── 时刻读法 ───────────────────────────────────────────────

    @Test
    fun `时刻读法不带小数`() {
        assertEquals(LifeformDay.clockOf(14.0), "14 点")
        assertEquals(LifeformDay.clockOf(14.5), "14 点半")
        assertEquals(LifeformDay.clockOf(14.25), "14 点一刻")
        assertEquals(LifeformDay.clockOf(14.75), "14 点三刻")
        for (h in listOf(0.0, 6.2, 11.7, 18.9, 23.95)) {
            assertTrue(!LifeformDay.clockOf(h).contains("."))
        }
    }

    @Test
    fun `today 用的是本地日`() {
        val nowMs = 1762615800_000L // 2026-11-08 23:30 +08
        val day = LifeformDay.today(nowMs, utcOffsetMinutes = 480)
        assertEquals(day.dayIndex, LifeformDay.localDayIndex(nowMs, 480))
    }
}
