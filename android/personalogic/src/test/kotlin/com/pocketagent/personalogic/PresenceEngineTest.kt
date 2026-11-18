package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **她此刻腾不腾得出手** —— 只有一张时刻表，没有角色。
 *
 * 这个文件防的是三类错：
 * 1. **热聊时她跑了** —— 说"我去洗澡"，然后真的在对话里消失了；
 * 2. **一天里漂移** —— 同一天问三次"你在干嘛"得到三个答案；
 * 3. **睡觉时回得跟白天一样利索** —— 那是最容易察觉的假。
 */
class PresenceEngineTest {

    private val offset = 480 // 东八区

    /** (第几天, 当天第几分钟) → 毫秒。与 [PresenceEngine.minuteOfDay] 互为反函数。 */
    private fun instant(dayIndex: Long, minuteOfDay: Int): Long =
        dayIndex * 86_400_000L + minuteOfDay * 60_000L - offset * 60_000L

    // ═══════════════════════════════════════════════════════════
    //  热聊保护
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正在说话时她一定在`() {
        // 21:40 本来是洗澡时段（见下面的窗口测试），但一分钟前刚说过话
        val now = instant(20_000L, 21 * 60 + 40)
        val p = PresenceEngine.at(
            nowMs = now,
            utcOffsetMinutes = offset,
            lastUserSpokeAtMs = now - 60_000L,
        )
        assertEquals(Presence.Where.AT_HAND, p.where)
    }

    @Test
    fun `热聊保护对睡觉也生效`() {
        val now = instant(20_000L, 23 * 60 + 40)
        val p = PresenceEngine.at(
            nowMs = now,
            utcOffsetMinutes = offset,
            lastUserSpokeAtMs = now - 10 * 60_000L,
        )
        assertEquals("半夜还在聊，就不该说她睡了", Presence.Where.AT_HAND, p.where)
    }

    @Test
    fun `超过热聊窗口就不再保护`() {
        val now = instant(20_000L, 23 * 60 + 40)
        val p = PresenceEngine.at(
            nowMs = now,
            utcOffsetMinutes = offset,
            lastUserSpokeAtMs = now - 20 * 60_000L,
        )
        assertEquals(Presence.Where.SLEEPING, p.where)
    }

    @Test
    fun `没说过的时刻也按时刻表算`() {
        val p = PresenceEngine.at(instant(20_000L, 3 * 60), utcOffsetMinutes = offset)
        assertEquals(Presence.Where.SLEEPING, p.where)
    }

    // ═══════════════════════════════════════════════════════════
    //  睡
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `睡觉窗口跨午夜且首尾正确`() {
        assertEquals(Presence.Where.SLEEPING, PresenceEngine.at(instant(1L, 23 * 60 + 30), offset).where)
        assertEquals(Presence.Where.SLEEPING, PresenceEngine.at(instant(1L, 23 * 60 + 59), offset).where)
        assertEquals(Presence.Where.SLEEPING, PresenceEngine.at(instant(1L, 0), offset).where)
        assertEquals(Presence.Where.SLEEPING, PresenceEngine.at(instant(1L, 7 * 60 + 44), offset).where)
        assertEquals("7:45 该醒了", Presence.Where.AT_HAND, PresenceEngine.at(instant(1L, 7 * 60 + 45), offset).where)
        assertEquals("22:00 还没睡", Presence.Where.AT_HAND, PresenceEngine.at(instant(1L, 22 * 60), offset).where)
    }

    @Test
    fun `睡了多少与还剩多少加起来是一整夜`() {
        val whole = 8 * 60 + 15 // 23:30 → 07:45
        for (minute in listOf(23 * 60 + 30, 0, 3 * 60, 7 * 60)) {
            val p = PresenceEngine.at(instant(1L, minute), offset)
            assertEquals(Presence.Where.SLEEPING, p.where)
            assertEquals("在 $minute 分处不闭合", whole, p.minutesIn + p.minutesLeft)
        }
        // 凌晨三点：已经睡了三小时半
        val deepNight = PresenceEngine.at(instant(1L, 3 * 60), offset)
        assertEquals(210, deepNight.minutesIn)
        assertEquals(285, deepNight.minutesLeft)
    }

    // ═══════════════════════════════════════════════════════════
    //  洗
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `洗澡窗口的起止与时长对得上`() {
        val day = 20_001L
        val w = PresenceEngine.showerWindow(day)
        assertEquals(
            "洗 14 分钟 + 吹 7 分钟",
            PresenceEngine.SHOWER_MINUTES + PresenceEngine.HAIR_MINUTES,
            w.lengthMinutes,
        )
        val inside = PresenceEngine.at(instant(day, w.start), offset)
        assertEquals(Presence.Where.SHOWER, inside.where)
        assertEquals(0, inside.minutesIn)
        // 窗口最后一分钟还在洗，下一分钟已经出来了
        assertEquals(Presence.Where.SHOWER, PresenceEngine.at(instant(day, w.end - 1), offset).where)
        assertFalse(PresenceEngine.at(instant(day, w.end), offset).where == Presence.Where.SHOWER)
    }

    @Test
    fun `洗澡时间每天不同但当天固定`() {
        val starts = (1L..30L).map { PresenceEngine.showerWindow(it).start }.toSet()
        assertTrue("三十天里洗澡时间不该只有一个值：$starts", starts.size >= 15)
        for (day in 1L..30L) {
            assertEquals(
                "同一天两次调用必须相同",
                PresenceEngine.showerWindow(day).start,
                PresenceEngine.showerWindow(day).start,
            )
        }
    }

    @Test
    fun `浮动不超出幅度`() {
        for (day in 1L..200L) {
            val shower = PresenceEngine.showerWindow(day).start - PresenceEngine.SHOWER_AT
            assertTrue("洗澡浮动越界：$shower", kotlin.math.abs(shower) <= PresenceEngine.SHOWER_SWING)
            for (index in 0..2) {
                val base = when (index) {
                    0 -> PresenceEngine.BREAKFAST_AT
                    1 -> PresenceEngine.LUNCH_AT
                    else -> PresenceEngine.DINNER_AT
                }
                val meal = PresenceEngine.mealWindow(day, index).start - base
                assertTrue("第 $index 顿浮动越界：$meal", kotlin.math.abs(meal) <= PresenceEngine.MEAL_SWING)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  吃
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `三餐各自落在自己的窗口里`() {
        val day = 20_002L
        for (index in 0..2) {
            val w = PresenceEngine.mealWindow(day, index)
            assertEquals(Presence.Where.MEAL, PresenceEngine.at(instant(day, w.start), offset).where)
            assertEquals(Presence.Where.MEAL, PresenceEngine.at(instant(day, w.end - 1), offset).where)
        }
        // 下午三点：既不在饭点也不在洗澡
        assertEquals(Presence.Where.AT_HAND, PresenceEngine.at(instant(day, 15 * 60), offset).where)
    }

    @Test
    fun `三个饭点窗口互不重叠`() {
        for (day in 1L..60L) {
            val windows = (0..2).map { PresenceEngine.mealWindow(day, it) }
            for (i in windows.indices) {
                for (j in i + 1 until windows.size) {
                    val a = windows[i]
                    val b = windows[j]
                    assertTrue(
                        "第 $i 顿(${a.start}..${a.end}) 与第 $j 顿(${b.start}..${b.end}) 重叠",
                        a.end <= b.start || b.end <= a.start,
                    )
                }
            }
        }
    }

    @Test
    fun `一天里的任意一分钟只有一个答案`() {
        // 抽几个日子把 1440 分钟全走一遍：不抛异常、不出现负的剩余时间
        for (day in listOf(1L, 7L, 365L)) {
            for (minute in 0 until 24 * 60) {
                val p = PresenceEngine.at(instant(day, minute), offset)
                assertTrue("minutesIn 为负：$p", p.minutesIn >= 0)
                assertTrue("minutesLeft 为负：$p", p.minutesLeft >= 0)
                if (p.where == Presence.Where.AT_HAND) {
                    assertEquals("AT_HAND 不该有终点", 0, p.minutesLeft)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  出口：要不要发 / 怎么说
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `洗澡和睡觉时不会主动找他`() {
        val showering = Presence(Presence.Where.SHOWER, minutesIn = 3, minutesLeft = 18)
        val sleeping = Presence(Presence.Where.SLEEPING, minutesIn = 60, minutesLeft = 400)
        assertTrue(PresenceEngine.gate(showering) is PresenceEngine.Gate.Mute)
        assertTrue(PresenceEngine.gate(sleeping) is PresenceEngine.Gate.Mute)
        assertTrue(PresenceEngine.gate(Presence()) is PresenceEngine.Gate.Allow)
        assertTrue(
            "吃着饭也能发一句",
            PresenceEngine.gate(Presence(Presence.Where.MEAL, 5, 20)) is PresenceEngine.Gate.Allow,
        )
    }

    @Test
    fun `提醒理由里带得出为什么`() {
        val gate = PresenceEngine.gate(Presence(Presence.Where.SHOWER, 3, 18))
        val reason = (gate as PresenceEngine.Gate.Mute).reason
        assertTrue("理由要能被日志读懂：$reason", reason.contains("洗澡"))
        assertTrue("也要说得出还剩多久：$reason", reason.contains("18"))
    }

    @Test
    fun `只有吃饭和洗澡会写进提示词`() {
        assertNull("在的时候什么都不用说", PresenceEngine.promptLine(Presence()))
        assertNull("睡着不该出现在对话里（交给 gate）", PresenceEngine.promptLine(Presence(Presence.Where.SLEEPING, 60, 400)))

        val meal = PresenceEngine.promptLine(Presence(Presence.Where.MEAL, 5, 20))
        assertNotNull(meal)
        assertTrue(meal!!.contains("吃饭"))

        val shower = PresenceEngine.promptLine(Presence(Presence.Where.SHOWER, 3, 18))
        assertNotNull(shower)
        assertTrue(shower!!.contains("洗澡"))
    }

    @Test
    fun `提示词里不许出现分钟数`() {
        // 数字会被模型复述出来（"我还有 6 分钟洗完"），那就是播报。
        for (presence in listOf(
            Presence(Presence.Where.MEAL, 1, 34),
            Presence(Presence.Where.MEAL, 30, 5),
            Presence(Presence.Where.SHOWER, 2, 19),
            Presence(Presence.Where.SHOWER, 15, 6),
        )) {
            val line = PresenceEngine.promptLine(presence)
            assertNotNull(line)
            assertTrue("出现了数字：$line", line!!.none { it.isDigit() })
        }
    }

    @Test
    fun `提示词说的是作息不是断言`() {
        // 「这个点你一般…」而不是「我正在…」—— 因为她其实可能正在聊天。
        val shower = PresenceEngine.promptLine(Presence(Presence.Where.SHOWER, 3, 18))!!
        assertTrue(shower.contains("这个点"))
        assertFalse("不能硬断言她此刻一定在洗：$shower", shower.contains("我正在"))
    }

    // ═══════════════════════════════════════════════════════════
    //  时区
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `时区不同答案跟着走`() {
        val now = instant(20_000L, 3 * 60) // 东八区凌晨三点
        assertEquals(Presence.Where.SLEEPING, PresenceEngine.at(now, utcOffsetMinutes = 480).where)
        // 同一毫秒在 UTC 视角是前一天 19:00 —— 那时她刚吃完晚饭或已经闲下来，
        // 总之**不该还在睡**。这里不断言具体是哪个状态：19:00 落在晚饭窗口内
        // 与否取决于当天的浮动，钉死成 AT_HAND 会让测试随作息表一起漂。
        val atUtc = PresenceEngine.at(now, utcOffsetMinutes = 0).where
        assertTrue("同一毫秒换个时区不该还是睡着：$atUtc", atUtc != Presence.Where.SLEEPING)
    }

    @Test
    fun `分钟的换算在边界上不抖`() {
        assertEquals(0, PresenceEngine.minuteOfDay(instant(1L, 0), offset))
        assertEquals(1439, PresenceEngine.minuteOfDay(instant(1L, 1439), offset))
    }
}
