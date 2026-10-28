package com.pocketagent.legacy

import com.pocketagent.legacy.SituationalContext.Advice
import com.pocketagent.legacy.SituationalContext.DayPhase
import com.pocketagent.legacy.SituationalContext.Situation
import com.pocketagent.legacy.SituationalContext.Topic
import com.pocketagent.legacy.SituationalContext.WeatherKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SituationalContext] 的离线钉子。
 *
 * ★ 重点全在"**什么情况下闭嘴**"：
 *   主动消息的故障形态只有一种 —— 说得太多、说得不对。
 *   而它一旦错一次，用户会去关通知，之后所有功能都叫不回来。
 */
class SituationalContextTest {

    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun sit(
        hourOfDay: Int = 15,
        weather: WeatherKind = WeatherKind.UNKNOWN,
        tempC: Int? = null,
        idleMs: Long = 0L,
        turns: Int = 100,
        sinceComplaintMs: Long = -1L,
    ) = Situation(
        hour = hourOfDay,
        weather = weather,
        tempC = tempC,
        idleMs = idleMs,
        turnCount = turns,
        sinceLastComplaintMs = sinceComplaintMs,
    )

    @Test
    fun `一天的阶段按小时切`() {
        assertEquals(DayPhase.EARLY, DayPhase.of(3))
        assertEquals(DayPhase.MORNING, DayPhase.of(7))
        assertEquals(DayPhase.NOON, DayPhase.of(12))
        assertEquals(DayPhase.AFTERNOON, DayPhase.of(15))
        assertEquals(DayPhase.EVENING, DayPhase.of(20))
        assertEquals(DayPhase.NIGHT, DayPhase.of(23))
    }

    @Test
    fun `WMO 天气码映射到有意义的类别`() {
        assertEquals(WeatherKind.CLEAR, WeatherKind.ofWmo(0))
        assertEquals(WeatherKind.CLOUDY, WeatherKind.ofWmo(3))
        assertEquals(WeatherKind.RAIN, WeatherKind.ofWmo(61))
        assertEquals(WeatherKind.SNOW, WeatherKind.ofWmo(71))
        assertEquals(WeatherKind.STORM, WeatherKind.ofWmo(95))
        assertEquals("不认识的码就是 UNKNOWN，不能瞎猜", WeatherKind.UNKNOWN, WeatherKind.ofWmo(1234))
    }

    @Test
    fun `下雨时优先提醒带伞，而不是先埋怨`() {
        // 关系很深 + 很久没说话 + 下雨 —— 应该先提醒天气
        val a = SituationalContext.advise(
            sit(hourOfDay = 8, weather = WeatherKind.RAIN, idleMs = 3 * day, sinceComplaintMs = 0L),
        )
        assertTrue(a is Advice.Say)
        assertEquals("能落到实处的提醒优先于情绪", Topic.WEATHER_ALERT, (a as Advice.Say).topic)
    }

    @Test
    fun `拿不到天气就绝不提天气`() {
        // ⚠️ 编一句"好像要下雨"，用户淋湿那天会想起来是谁说的。
        val a = SituationalContext.advise(sit(hourOfDay = 15, weather = WeatherKind.UNKNOWN))
        assertTrue(a is Advice.Say)
        assertFalse("UNKNOWN 不能产出天气提醒", (a as Advice.Say).topic == Topic.WEATHER_ALERT)
    }

    @Test
    fun `极端温度也算提醒，但要有真实温度值`() {
        assertEquals(
            Topic.WEATHER_ALERT,
            (SituationalContext.advise(sit(hourOfDay = 14, tempC = 38)) as Advice.Say).topic,
        )
        assertEquals(
            Topic.WEATHER_ALERT,
            (SituationalContext.advise(sit(hourOfDay = 14, tempC = -12)) as Advice.Say).topic,
        )
        // 温度未知 → 不提
        assertNull(SituationalContext.weatherWarning(sit(hourOfDay = 14, tempC = null)))
    }

    @Test
    fun `凌晨三四点会劝睡`() {
        val a = SituationalContext.advise(sit(hourOfDay = 3))
        assertEquals(Topic.LATE_NIGHT, (a as Advice.Say).topic)
    }

    @Test
    fun `饭点提醒只出现在午晚两个窗口`() {
        assertEquals(Topic.MEAL_REMINDER, SituationalContext.mealTopic(12))
        assertEquals(Topic.MEAL_REMINDER, SituationalContext.mealTopic(18))
        assertNull("上午十点不是饭点", SituationalContext.mealTopic(10))
        assertNull("下午三点也不是", SituationalContext.mealTopic(15))
    }

    @Test
    fun `埋怨需要同时满足关系深与隔得久`() {
        // 关系不够深
        assertNull(
            "刚认识就埋怨是讨债",
            SituationalContext.complain(sit(turns = 10, idleMs = 5 * day)),
        )
        // 隔得不够久
        assertNull(
            "六小时没说话不算冷落",
            SituationalContext.complain(sit(turns = 100, idleMs = 6 * hour)),
        )
        // 都满足 → 允许
        val ok = SituationalContext.complain(sit(turns = 100, idleMs = 3 * day))
        assertEquals(Topic.COMPLAIN_NEGLECT, ok?.topic)
    }

    @Test
    fun `一周内已经埋怨过一次就不再埋怨`() {
        val justDid = SituationalContext.complain(
            sit(turns = 100, idleMs = 3 * day, sinceComplaintMs = 2 * day),
        )
        assertNull("两天前刚埋怨过，再来一次就是唠叨", justDid)

        val longAgo = SituationalContext.complain(
            sit(turns = 100, idleMs = 10 * day, sinceComplaintMs = 8 * day),
        )
        assertEquals("超过一周可以再说一次", Topic.COMPLAIN_NEGLECT, longAgo?.topic)
    }

    @Test
    fun `早上会打招呼`() {
        val a = SituationalContext.advise(sit(hourOfDay = 8, turns = 10))
        assertEquals(Topic.MORNING_GREETING, (a as Advice.Say).topic)
    }

    @Test
    fun `关系浅的时候下午什么都不说`() {
        // 下午三点、天气未知、没饭点、轮次很少 → 没有值得开口的事
        val a = SituationalContext.advise(sit(hourOfDay = 15, turns = 8))
        assertTrue("不该硬找话题：$a", a is Advice.Silent)
    }

    @Test
    fun `关系深的时候下午可以随便聊两句`() {
        val a = SituationalContext.advise(sit(hourOfDay = 15, turns = 100))
        assertEquals(Topic.TOPIC_STARTER, (a as Advice.Say).topic)
    }

    @Test
    fun `同一个输入永远得到同一个结论`() {
        val s = sit(hourOfDay = 8, weather = WeatherKind.RAIN)
        assertEquals(SituationalContext.advise(s), SituationalContext.advise(s))
    }
}
