package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕租约的测试。
 *
 * ★ 这套规矩全是**时间边界**，所以时钟必须是注入的 —— 用真实时间测
 *   "1.5 秒后自动回来"的测试写出来就是 flaky 的，而 flaky 的测试会被
 *   删掉，删掉之后这段逻辑就再也没人守了。
 *
 * 钉的四条：
 *   1. 用户的手永远优先（一碰就让）
 *   2. 松手 grace 后自动回来（不用点确认）
 *   3. 用户按停就不回来
 *   4. **让开不延长租约**（总时长上限对让开也生效）
 */
class ScreenLeaseTest {

    private var now = 1_000_000L
    private fun lease(hold: Long = 90_000, grace: Long = 1_500) = ScreenLease({ now }, hold, grace)

    @Test
    fun `没借之前不能动手`() {
        val l = lease()
        assertEquals(LeaseState.Idle, l.state)
        assertFalse(l.canAct())
        assertEquals("还没申请占屏", l.blockedReason())
    }

    @Test
    fun `借到之后可以动手`() {
        val l = lease()
        l.acquire("打开设置")
        assertTrue(l.canAct())
        assertTrue(l.state is LeaseState.Held)
        assertNull(l.blockedReason())
    }

    @Test
    fun `用户一碰就让开`() {
        val l = lease()
        l.acquire("打开设置")
        l.onUserTouch()
        assertFalse("用户在手，agent 不能动", l.canAct())
        assertTrue((l.state as LeaseState.Yielded).because == YieldReason.USER_TOUCH)
        assertTrue(l.blockedReason()!!.contains("正在用手机"))
    }

    @Test
    fun `松手不到 grace 不许回来`() {
        val l = lease()
        l.acquire("打开设置")
        l.onUserTouch()
        now += 1_400
        l.tick()
        assertTrue("1_4 秒就回来会打断正在操作的人", l.state is LeaseState.Yielded)
        assertFalse(l.canAct())
    }

    @Test
    fun `松手 grace 之后自动回来`() {
        val l = lease()
        l.acquire("打开设置")
        l.onUserTouch()
        now += 1_600
        l.tick()
        assertTrue(l.state is LeaseState.Held)
        assertTrue(l.canAct())
    }

    @Test
    fun `用户按停就不再自动回来`() {
        // ★ 这条做错，用户会觉得"我让它停它不听"，那整套自主权都是假的。
        val l = lease()
        l.acquire("打开设置")
        l.onUserStop()
        now += 60_000
        l.tick()
        assertTrue(l.state is LeaseState.Yielded)
        assertEquals(YieldReason.USER_STOP, (l.state as LeaseState.Yielded).because)
        assertFalse(l.canAct())
    }

    @Test
    fun `让开不延长租约`() {
        // ★ 用户碰三次屏 → 各续 90 秒 = 占了一下午。总闸必须对让开也生效。
        val l = lease(hold = 10_000, grace = 1_000)
        l.acquire("长任务")
        l.onUserTouch()
        now += 2_000
        l.tick() // 回来了，但用的还是原来那份租约
        assertTrue(l.state is LeaseState.Held)
        now += 9_000 // 距最初申请 11s，超过 10s 上限
        l.tick()
        assertTrue("超过总上限就该到期，而不是又续 10 秒", l.state is LeaseState.Expired)
        assertFalse(l.canAct())
    }

    @Test
    fun `到了上限就到期`() {
        val l = lease(hold = 5_000)
        l.acquire("打开设置")
        now += 5_000
        l.tick()
        assertTrue(l.state is LeaseState.Expired)
        assertFalse(l.canAct())
        assertTrue(l.blockedReason()!!.contains("到期"))
    }

    @Test
    fun `到期后重新申请是一份新租约`() {
        val l = lease(hold = 5_000)
        l.acquire("第一趟")
        now += 6_000
        l.tick()
        l.acquire("第二趟")
        assertTrue(l.state is LeaseState.Held)
        assertTrue(l.canAct())
    }

    @Test
    fun `交还之后回到没借的状态`() {
        val l = lease()
        l.acquire("打开设置")
        l.release()
        assertEquals(LeaseState.Idle, l.state)
        assertFalse(l.canAct())
    }

    @Test
    fun `重复申请不会重置计时`() {
        val l = lease(hold = 10_000)
        l.acquire("打开设置")
        now += 4_000
        l.acquire("打开设置")
        now += 6_000 // 距最初 10s
        l.tick()
        assertTrue("重复申请不该变成「又借 10 秒」", l.state is LeaseState.Expired)
    }

    @Test
    fun `没借的时候碰屏不会凭空产生租约`() {
        val l = lease()
        l.onUserTouch()
        assertEquals(LeaseState.Idle, l.state)
        assertFalse(l.canAct())
    }

    @Test
    fun `前台被切走要说明原因`() {
        val l = lease()
        l.acquire("打开设置")
        l.onAppLeft()
        assertFalse(l.canAct())
        assertTrue(l.blockedReason()!!.contains("前台"))
        // 切走不是用户按停，松手 grace 后应该能自己回来
        now += 1_600
        l.tick()
        assertTrue(l.state is LeaseState.Held)
    }

    @Test
    fun `连续触摸会不断推迟回来`() {
        val l = lease(hold = 60_000, grace = 1_000)
        l.acquire("打开设置")
        for (i in 0 until 5) {
            l.onUserTouch()
            now += 800
            l.tick()
        }
        assertTrue("用户一直在碰，就不该回来", l.state is LeaseState.Yielded)
    }

    @Test
    fun `空状态 tick 不会改变任何东西`() {
        val l = lease()
        assertEquals(LeaseState.Idle, l.tick())
        assertEquals(LeaseState.Idle, l.state)
    }
}
