package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **连续使用看护**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这条合规要求最怕的三种失败
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **永远不提醒** —— 那等于没实现（现状就是）
 * ② **把断开的两次算成一段** —— 用户上午用一次、下午用一次，
 *    被判定"连续用了 6 小时"，那是**诬告**
 * ③ **反复提醒** —— 每次活动都弹一遍，用户会关掉这个功能，
 *    于是合规要求形同虚设
 */
class UsageWatchTest {

    private val t0 = 1_700_000_000_000L
    private val min = 60_000L
    private val hour = 3_600_000L

    /**
     * 模拟"应用一直开着"：每分钟打一次活动点。
     *
     * ⚠️ 这是正确用法 —— [UsageWatch.onActive] 的语义是**应用此刻在前台**，
     *    由界面周期性调用（比如每 30~60 秒一次）。
     *    测试里直接跳两小时是错的：那正好超过空档阈值，会被判成"断了"。
     */
    private fun keepOpen(
        state: UsageWatch.State,
        from: Long,
        to: Long,
    ): UsageWatch.State {
        var s = state
        var t = from
        while (t < to) {
            t += min
            s = UsageWatch.onActive(s, t)
        }
        return s
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ ① 两小时必须提醒
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `刚到两小时就提醒`() {
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + 2 * hour)
        val r = UsageWatch.dueReminder(s, t0 + 2 * hour)
        assertNotNull("两小时是规章的硬门槛，必须提醒", r)
        assertTrue("第一次提醒", r!!.isFirst)
    }

    @Test
    fun `一小时五十九分不提醒`() {
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + 119 * min)
        assertNull(UsageWatch.dueReminder(s, t0 + 119 * min))
    }

    @Test
    fun `两个半小时仍然提醒`() {
        // ⚠️ 不能只在"恰好两小时"那一点提醒 —— 用户可能那几秒不在活跃状态，
        //    而 `onActive` 是按活动打点的。
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + 150 * min)
        assertNotNull(UsageWatch.dueReminder(s, t0 + 150 * min))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ ② 断开的两次不能算一段
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空档超过十分钟就重新计段`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是"诬告"
        // ══════════════════════════════════════════════════════
        // 用户上午用一次、下午用一次，如果算成一段，
        // 他会在打开应用的第一秒就被提醒"你已经用了 6 小时"。
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        // 用了一小时
        s = UsageWatch.onActive(s, t0 + hour)
        // 离开 3 小时
        s = UsageWatch.onActive(s, t0 + 4 * hour)
        // 此刻的段时长应当是从"回来那一刻"算起，而不是 4 小时
        assertEquals(0L, s.elapsed(t0 + 4 * hour))
    }

    @Test
    fun `空档刚好十分钟仍然算连续`() {
        // ⚠️ 边界：倒杯水、接个短电话不该被算成"断了"。
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = UsageWatch.onActive(s, t0 + UsageWatch.IDLE_TOLERANCE_MS)
        assertEquals(
            "刚好等于阈值应当仍算连续",
            UsageWatch.IDLE_TOLERANCE_MS,
            s.elapsed(t0 + UsageWatch.IDLE_TOLERANCE_MS),
        )
    }

    @Test
    fun `重新计段后提醒记录也清空`() {
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + 2 * hour)
        val r = UsageWatch.dueReminder(s, t0 + 2 * hour)
        s = UsageWatch.onReminded(s, t0 + 2 * hour)
        assertNull("刚提醒过不该再提醒", UsageWatch.dueReminder(s, t0 + 2 * hour))

        // 离开很久再回来 ⇒ 新的一段，应当重新有机会被提醒
        s = UsageWatch.onActive(s, t0 + 10 * hour)
        assertEquals("新一段的起点是回来那一刻", t0 + 10 * hour, s.startedAtMs)
        assertNull("新一段还没到两小时", UsageWatch.dueReminder(s, t0 + 10 * hour))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ ③ 不能反复提醒
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `提醒过之后一小时内不再提醒`() {
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + 2 * hour)
        s = UsageWatch.onReminded(s, t0 + 2 * hour)

        // 一分钟后
        s = keepOpen(s, t0 + 2 * hour, t0 + 2 * hour + min)
        assertNull(UsageWatch.dueReminder(s, t0 + 2 * hour + min))
    }

    @Test
    fun `一小时后再次提醒`() {
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + 2 * hour)
        s = UsageWatch.onReminded(s, t0 + 2 * hour)

        s = keepOpen(s, t0 + 2 * hour, t0 + 3 * hour)
        val r = UsageWatch.dueReminder(s, t0 + 3 * hour)
        assertNotNull("三小时了，该再提醒一次", r)
        assertFalse("这不是第一次", r!!.isFirst)
    }

    @Test
    fun `连续多次提醒的间隔稳定`() {
        // ⚠️ 频率**不递增**：递增会变成惩罚。
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        var now = t0
        val fired = mutableListOf<Long>()

        // 模拟每 5 分钟活动一次，跑 8 小时
        repeat(96) {
            now += 5 * min
            s = UsageWatch.onActive(s, now)
            val r = UsageWatch.dueReminder(s, now)
            if (r != null) {
                fired += now - t0
                s = UsageWatch.onReminded(s, now)
            }
        }

        // 第一次约 2 小时，之后每隔约 1 小时
        assertEquals("8 小时内应当提醒 7 次左右（2h 起，每小时一次）：$fired", 7, fired.size)
        assertTrue("第一次在 2 小时附近：${fired.first()}", fired.first() in (2 * hour)..(2 * hour + 10 * min))
    }

    // ═══════════════════════════════════════════════════════════
    //  时钟倒流
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `时钟倒流不重置也不崩`() {
        // ★ 用户改系统时间、NTP 校时都会造成这个。
        //   ⚠️ 重置会白丢用户已经积累的时长 —— 而他什么都没做错。
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + hour)
        val before = s.elapsed(t0 + hour)

        s = UsageWatch.onActive(s, t0 - hour)
        assertEquals("起点不该被倒流的时钟推后", t0, s.startedAtMs)
        assertTrue("时长不该变成 0", s.elapsed(t0 + hour) == before)
    }

    @Test
    fun `时钟倒流时 elapsed 不为负`() {
        val s = UsageWatch.onActive(UsageWatch.State(), t0)
        assertEquals(0L, s.elapsed(t0 - hour))
    }

    // ═══════════════════════════════════════════════════════════
    //  文案所需的信息
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `提醒里带得出整小时数`() {
        // ★ 文案要用**具体数字**说话："两个多小时了" 比 "很久了" 更有分量。
        var s = UsageWatch.onActive(UsageWatch.State(), t0)
        s = keepOpen(s, t0, t0 + 3 * hour + 20 * min)
        val r = UsageWatch.dueReminder(s, t0 + 3 * hour + 20 * min)!!
        assertEquals(3, r.wholeHours)
    }

    @Test
    fun `整小时数至少是二`() {
        // ⚠️ 边界：刚过 2 小时但不足 2.5 小时时，整除会得 2 —— 正确。
        //    但如果 elapsed 恰好是 2h 减一毫秒（不该发生），也不该出现 1。
        val r = UsageWatch.Reminder(elapsedMs = UsageWatch.FIRST_REMIND_MS - 1, isFirst = true)
        assertEquals("不该说出「1 个小时」这种低于门槛的数字", 2, r.wholeHours)
    }

    @Test
    fun `没开始时什么都不提醒`() {
        assertNull(UsageWatch.dueReminder(UsageWatch.State(), t0))
    }
}
