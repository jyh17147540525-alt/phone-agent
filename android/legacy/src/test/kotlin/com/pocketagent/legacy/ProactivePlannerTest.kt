package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProactivePlanner] 的离线钉子。
 *
 * ★ 全部在钉"**不该发的时候不发**"。
 *   主动消息的故障形态只有一种：发得太多。而它一旦出错，
 *   用户的反应不是"这功能有问题"，是**去系统设置里把通知关掉** ——
 *   那之后所有功能都叫不回来了。
 */
class ProactivePlannerTest {

    /** 2026-10-28 14:00 东八区（UTC 06:00）。 */
    private val now = 1_793_100_000_000L
    private val hour = 3_600_000L
    private val day = 24 * hour

    private fun facts(
        lastUserAgoMs: Long = 6 * hour,
        lastProactiveAgoMs: Long = 0L,
        turns: Int = 40,
    ) = ProactivePlanner.Facts(
        nowMs = now,
        lastUserAtMs = now - lastUserAgoMs,
        lastProactiveAtMs = if (lastProactiveAgoMs == 0L) 0L else now - lastProactiveAgoMs,
        turnCount = turns,
    )

    @Test
    fun `聊得还不够时不主动开口`() {
        val d = ProactivePlanner.decide(facts(turns = 3))
        assertTrue("刚认识就发消息是骚扰：$d", d is ProactivePlanner.Decision.Skip)
        assertTrue((d as ProactivePlanner.Decision.Skip).reason.contains("互相认识"))
    }

    @Test
    fun `刚说完话不去凑热闹`() {
        val d = ProactivePlanner.decide(facts(lastUserAgoMs = 10 * 60_000L))
        assertTrue("10 分钟前刚聊过，不该追一条：$d", d is ProactivePlanner.Decision.Skip)
    }

    @Test
    fun `隔了三小时以上就可以开口`() {
        val d = ProactivePlanner.decide(facts(lastUserAgoMs = 4 * hour))
        assertTrue("该发了：$d", d is ProactivePlanner.Decision.Send)
    }

    @Test
    fun `两次主动之间要有最小间隔`() {
        val d = ProactivePlanner.decide(
            facts(lastUserAgoMs = 6 * hour, lastProactiveAgoMs = 1 * hour),
        )
        assertTrue("一小时前刚主动过，不能再来：$d", d is ProactivePlanner.Decision.Skip)
        assertTrue((d as ProactivePlanner.Decision.Skip).reason.contains("太密"))
    }

    @Test
    fun `深夜一律不发，哪怕关系很深`() {
        // ★ 别硬编码"几点"：`now` 是个固定时间戳，而它落在几点取决于时区换算。
        //   这里**先问 hourOf 现在是几点**，再把时间挪到凌晨 3 点 ——
        //   否则测试会因为一个与逻辑无关的常数而红（这条就是这么被发现的）。
        val currentHour = ProactivePlanner.hourOf(now, 8)
        val shiftBackHours = ((currentHour - 3) + 24) % 24
        val threeAm = now - shiftBackHours * hour
        assertEquals("挪完之后应当正好是 3 点", 3, ProactivePlanner.hourOf(threeAm, 8))

        val d = ProactivePlanner.decide(
            ProactivePlanner.Facts(
                nowMs = threeAm,
                lastUserAtMs = threeAm - 8 * hour,
                lastProactiveAtMs = 0L,
                turnCount = 200,
            ),
        )
        assertTrue("凌晨三点弹消息会让人关掉通知：$d", d is ProactivePlanner.Decision.Skip)
    }

    @Test
    fun `免打扰区间跨零点也要正确`() {
        // ⚠️ 写成 `hour in 23..8` 是空区间，免打扰永远不生效 ——
        //    而界面上一切正常，只有用户在凌晨被吵醒才知道。
        assertTrue("23 点要静", ProactivePlanner.isQuietHour(23))
        assertTrue("0 点要静", ProactivePlanner.isQuietHour(0))
        assertTrue("7 点还静", ProactivePlanner.isQuietHour(7))
        assertFalse("8 点可以说话了", ProactivePlanner.isQuietHour(8))
        assertFalse("下午三点当然可以", ProactivePlanner.isQuietHour(15))
    }

    @Test
    fun `时区换算正确`() {
        // ⚠️ 不硬编码"现在几点"：断言的是**关系**，不是某个常数。
        //    硬编码过一次，结果测试红在一个与逻辑无关的数字上。
        val utcHour = ProactivePlanner.hourOf(now, 0)
        val cnHour = ProactivePlanner.hourOf(now, 8)
        assertEquals("东八区 = UTC + 8（模 24）", (utcHour + 8) % 24, cnHour)
        // 负偏移也要正确回绕到 0..23
        val west = ProactivePlanner.hourOf(now, -5)
        assertTrue("负偏移必须回绕到 0..23，实际 $west", west in 0..23)
    }

    @Test
    fun `口气按空白时长分档，不是随机的`() {
        assertEquals(
            ProactivePlanner.Tone.CASUAL,
            ProactivePlanner.toneFor(6 * hour),
        )
        assertEquals(
            ProactivePlanner.Tone.MISSING,
            ProactivePlanner.toneFor(36 * hour),
        )
        assertEquals(
            ProactivePlanner.Tone.CHECK_IN,
            ProactivePlanner.toneFor(10 * day),
        )
        // 同样的输入必须永远得到同样的结论
        assertEquals(
            ProactivePlanner.toneFor(36 * hour),
            ProactivePlanner.toneFor(36 * hour),
        )
    }

    @Test
    fun `从没说过话就不主动`() {
        val d = ProactivePlanner.decide(
            ProactivePlanner.Facts(nowMs = now, lastUserAtMs = 0L, lastProactiveAtMs = 0L, turnCount = 99),
        )
        assertTrue(d is ProactivePlanner.Decision.Skip)
    }

    @Test
    fun `轮次门槛不会因为长时间没说话而被绕过`() {
        // 关系不够深 + 很久没说话 → 仍然不发
        val d = ProactivePlanner.decide(facts(lastUserAgoMs = 30 * day, turns = 5))
        assertTrue("时间久不等于关系深：$d", d is ProactivePlanner.Decision.Skip)
    }
}
