package com.pocketagent.agentlogic.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 等待条件的三态收敛。
 *
 * 钉住两条容易写反的地方：①**先判成立、再判超时**（刚好在超时那一刻变成立时算等到）；
 * ②读不到摘要时不能当成"条件已满足"（读屏故障不该被读成"加载完成"）。
 */
class WaitConditionsTest {

    private fun digest(
        packageName: String? = "com.tencent.mm",
        texts: List<String> = listOf("发送"),
        nodeCount: Int = 3,
    ) = ScreenDigest(packageName = packageName, texts = texts, nodeCount = nodeCount)

    // ── TextAppears ─────────────────────────────────────────────────────────

    @Test
    fun `等到文字出现`() {
        val wait = WaitCondition.TextAppears("已发送")
        assertEquals(WaitOutcome.MET, wait.check(digest(texts = listOf("已发送")), null, 300))
    }

    @Test
    fun `还没出现时是还在等`() {
        val wait = WaitCondition.TextAppears("已发送")
        assertEquals(WaitOutcome.PENDING, wait.check(digest(), null, 300))
    }

    @Test
    fun `超时仍未出现就是等超时`() {
        val wait = WaitCondition.TextAppears("已发送", timeoutMs = 1_000)
        assertEquals(WaitOutcome.TIMED_OUT, wait.check(digest(), null, 1_000))
    }

    @Test
    fun `刚好在超时那一刻出现算等到`() {
        val wait = WaitCondition.TextAppears("已发送", timeoutMs = 1_000)
        assertEquals(WaitOutcome.MET, wait.check(digest(texts = listOf("已发送")), null, 1_000))
    }

    @Test
    fun `没读到摘要时不算出现`() {
        val wait = WaitCondition.TextAppears("已发送")
        assertEquals(WaitOutcome.PENDING, wait.check(null, null, 100))
    }

    // ── TextDisappears ──────────────────────────────────────────────────────

    @Test
    fun `等到文字消失`() {
        val wait = WaitCondition.TextDisappears("加载中")
        assertEquals(WaitOutcome.MET, wait.check(digest(texts = listOf("首页")), null, 500))
    }

    @Test
    fun `读不到摘要时不算消失`() {
        // ⚠️ 读屏故障被读成"加载完成"是最坏的一种静默错误
        val wait = WaitCondition.TextDisappears("加载中")
        assertEquals(WaitOutcome.PENDING, wait.check(null, null, 500))
    }

    @Test
    fun `文字还在就一直等`() {
        val wait = WaitCondition.TextDisappears("加载中", timeoutMs = 2_000)
        assertEquals(WaitOutcome.PENDING, wait.check(digest(texts = listOf("加载中")), null, 1_000))
        assertEquals(WaitOutcome.TIMED_OUT, wait.check(digest(texts = listOf("加载中")), null, 2_000))
    }

    // ── PackageIs ───────────────────────────────────────────────────────────

    @Test
    fun `等到换到目标应用`() {
        val wait = WaitCondition.PackageIs("com.android.settings")
        assertEquals(WaitOutcome.MET, wait.check(digest(packageName = "com.android.settings"), null, 400))
        assertEquals(WaitOutcome.PENDING, wait.check(digest(packageName = "com.tencent.mm"), null, 400))
    }

    // ── ValueIs ─────────────────────────────────────────────────────────────

    @Test
    fun `等到值变成目标值`() {
        val wait = WaitCondition.ValueIs("animator_duration_scale", "0.0")
        val ok = ScreenDigest(values = mapOf("animator_duration_scale" to "0.0"))
        val notYet = ScreenDigest(values = mapOf("animator_duration_scale" to "1.0"))

        assertEquals(WaitOutcome.MET, wait.check(ok, null, 100))
        assertEquals(WaitOutcome.PENDING, wait.check(notYet, null, 100))
        assertEquals(WaitOutcome.PENDING, wait.check(ScreenDigest(), null, 100))
    }

    // ── Stable ──────────────────────────────────────────────────────────────

    @Test
    fun `连续两次一样才算稳定`() {
        val wait = WaitCondition.Stable()
        val a = digest(texts = listOf("首页"), nodeCount = 5)
        val b = digest(texts = listOf("首页"), nodeCount = 5)
        val c = digest(texts = listOf("首页", "新消息"), nodeCount = 6)

        assertEquals(WaitOutcome.MET, wait.check(b, a, 600))
        assertEquals(WaitOutcome.PENDING, wait.check(c, a, 600))
        assertEquals(WaitOutcome.PENDING, wait.check(a, null, 600))
    }

    @Test
    fun `稳定条件读不到摘要时一直等`() {
        val wait = WaitCondition.Stable(timeoutMs = 1_000)
        assertEquals(WaitOutcome.PENDING, wait.check(null, null, 500))
        assertEquals(WaitOutcome.TIMED_OUT, wait.check(null, null, 1_000))
    }

    // ── 参数校验 ────────────────────────────────────────────────────────────

    @Test
    fun `超时太短会被拒`() {
        assertThrows(IllegalArgumentException::class.java) {
            WaitCondition.TextAppears("x", timeoutMs = 10)
        }
    }

    @Test
    fun `超时超过上限会被拒`() {
        // 上限存在的理由：条件写错时不能让任务挂死
        assertThrows(IllegalArgumentException::class.java) {
            WaitCondition.TextAppears("x", timeoutMs = WaitCondition.MAX_TIMEOUT_MS + 1)
        }
    }

    @Test
    fun `默认超时在允许范围内`() {
        val wait = WaitCondition.TextAppears("x")
        assertEquals(WaitCondition.DEFAULT_TIMEOUT_MS, wait.timeoutMs)
        assertEquals(true, wait.timeoutMs in WaitCondition.MIN_TIMEOUT_MS..WaitCondition.MAX_TIMEOUT_MS)
    }
}
