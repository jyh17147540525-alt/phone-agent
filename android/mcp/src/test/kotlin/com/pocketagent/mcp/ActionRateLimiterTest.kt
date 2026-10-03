package com.pocketagent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 频率闸：滚动窗口、防连点、拒绝不占额。
 *
 * 时钟是注入的假钟 —— 这类"时间过了一半"的边界只有确定性时钟钉得住。
 */
class ActionRateLimiterTest {

    private var now = 0L

    private fun limiter(maxPerMinute: Int = 20, minGapMs: Long = 250) =
        ActionRateLimiter(maxActionsPerMinute = maxPerMinute, minGapMs = minGapMs, clock = { now })

    @Test
    fun `间隔足够时可以连续放行到每分钟上限`() {
        val limiter = limiter(maxPerMinute = 5)
        repeat(5) {
            now += 300
            assertTrue(limiter.tryAcquire() is ActionRateLimiter.Decision.Allowed)
        }
        now += 300
        val denied = limiter.tryAcquire()
        assertTrue("第 6 次应被拒", denied is ActionRateLimiter.Decision.Denied)
        val reason = (denied as ActionRateLimiter.Decision.Denied).reason
        assertTrue("理由要说明是上限", reason.contains("上限"))
        assertTrue("要给出重试时间", denied.retryAfterMs > 0)
    }

    @Test
    fun `窗口滑动后自动恢复`() {
        val limiter = limiter(maxPerMinute = 1)
        now += 300
        assertTrue(limiter.tryAcquire() is ActionRateLimiter.Decision.Allowed)
        now += 300
        assertTrue("一分钟内第二次应被拒", limiter.tryAcquire() is ActionRateLimiter.Decision.Denied)
        now += ActionRateLimiter.WINDOW_MS // 第一个时间戳滑出窗口
        assertTrue("窗口滑出后应恢复", limiter.tryAcquire() is ActionRateLimiter.Decision.Allowed)
    }

    @Test
    fun `间隔过短被拒且给出等待时间`() {
        val limiter = limiter(minGapMs = 250)
        now += 1000
        assertTrue(limiter.tryAcquire() is ActionRateLimiter.Decision.Allowed)
        now += 100
        val denied = limiter.tryAcquire()
        assertTrue(denied is ActionRateLimiter.Decision.Denied)
        val d = denied as ActionRateLimiter.Decision.Denied
        assertTrue("理由要说明是连点保护", d.reason.contains("间隔"))
        assertEquals(150L, d.retryAfterMs)
    }

    @Test
    fun `被拒的调用不占额度`() {
        val limiter = limiter(minGapMs = 250)
        now += 1000
        assertTrue(limiter.tryAcquire() is ActionRateLimiter.Decision.Allowed)
        now += 100
        assertTrue(limiter.tryAcquire() is ActionRateLimiter.Decision.Denied)
        now += 150 // 距上一次成功恰好 250ms
        assertTrue("被拒不应消耗额度", limiter.tryAcquire() is ActionRateLimiter.Decision.Allowed)
    }

    @Test
    fun `countInLastMinute 只数窗口内的且不清空`() {
        val limiter = limiter(minGapMs = 0)
        now += 1000
        limiter.tryAcquire()
        now += 30_000
        limiter.tryAcquire()
        now += 40_000 // 现在 t=71s；第一条(1s)滑出窗口，第二条(31s)还在
        assertEquals(1, limiter.countInLastMinute())
        assertEquals("读取计数不占额也不清空", 1, limiter.countInLastMinute())
    }
}
