package com.pocketagent.overlaylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopSignalTest {

    private fun request(id: String, taskId: String? = "t1", at: Long = 1000L) =
        StopRequest(requestId = id, taskId = taskId, timestampMs = at, source = StopSource.OVERLAY_BALL)

    // ── 基本受理 ───────────────────────────────────────────────

    @Test
    fun `有任务在跑时急停被受理`() {
        val handler = StopHandler()
        val outcome = handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1200L)
        assertTrue(outcome is StopOutcome.Accepted)
        assertEquals("r1", (outcome as StopOutcome.Accepted).requestId)
    }

    @Test
    fun `受理延迟按时间差计算`() {
        val handler = StopHandler()
        val outcome = handler.handle(request("r1", at = 1000L), runningTaskId = "t1", nowMs = 1300L)
        assertEquals(300L, (outcome as StopOutcome.Accepted).acknowledgementMs)
    }

    @Test
    fun `时间倒流时延迟被钳到零而非负数`() {
        val handler = StopHandler()
        val outcome = handler.handle(request("r1", at = 5000L), runningTaskId = "t1", nowMs = 1000L)
        assertEquals(0L, (outcome as StopOutcome.Accepted).acknowledgementMs)
    }

    // ── 空任务情形 ─────────────────────────────────────────────

    @Test
    fun `没有任务在跑时返回可反馈的空结果`() {
        // 关键：即使无事可停，也要返回明确结果让 UI 给反馈，
        // 否则用户以为急停坏了，会反复点击
        val handler = StopHandler()
        val outcome = handler.handle(request("r1"), runningTaskId = null, nowMs = 1200L)
        assertTrue(outcome is StopOutcome.NothingToStop)
    }

    @Test
    fun `指定的任务 ID 与当前不符时视为无事可停`() {
        // 通常是任务刚好结束了，属正常竞态，不应报错
        val handler = StopHandler()
        val outcome = handler.handle(request("r1", taskId = "t999"), runningTaskId = "t1", nowMs = 1200L)
        assertTrue(outcome is StopOutcome.NothingToStop)
    }

    @Test
    fun `不指定任务 ID 时可停止当前一切任务`() {
        val handler = StopHandler()
        val outcome = handler.handle(request("r1", taskId = null), runningTaskId = "t1", nowMs = 1200L)
        assertTrue(outcome is StopOutcome.Accepted)
    }

    // ── 去重 ───────────────────────────────────────────────────

    @Test
    fun `同一个请求重复投递被识别为重复`() {
        // 用户连点急停 / 悬浮球与通知栏同时触发
        val handler = StopHandler()
        handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1200L)
        val second = handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1300L)
        assertTrue(second is StopOutcome.Duplicate)
    }

    @Test
    fun `重复请求保留原始结果供对照`() {
        val handler = StopHandler()
        handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1200L)
        val second = handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1300L)
        val original = (second as StopOutcome.Duplicate).originalOutcome
        assertTrue(original is StopOutcome.Accepted)
    }

    @Test
    fun `不同请求 ID 不会被误判为重复`() {
        val handler = StopHandler()
        handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1200L)
        val second = handler.handle(request("r2"), runningTaskId = "t1", nowMs = 1300L)
        assertTrue(second is StopOutcome.Accepted)
    }

    // ── 跟踪器容量 ─────────────────────────────────────────────

    @Test
    fun `跟踪器不会无限增长`() {
        val tracker = StopRequestTracker(maxRetained = 3)
        val handler = StopHandler(tracker)
        repeat(10) { i ->
            handler.handle(request("r$i"), runningTaskId = "t1", nowMs = 1000L + i)
        }
        assertEquals(3, tracker.trackedCount)
    }

    @Test
    fun `超出容量后淘汰最旧的请求`() {
        val tracker = StopRequestTracker(maxRetained = 2)
        val handler = StopHandler(tracker)
        handler.handle(request("old"), runningTaskId = "t1", nowMs = 1000L)
        handler.handle(request("mid"), runningTaskId = "t1", nowMs = 1100L)
        handler.handle(request("new"), runningTaskId = "t1", nowMs = 1200L)

        assertNull("最旧的应被淘汰", tracker.findProcessed("old"))
        assertNotNull(tracker.findProcessed("mid"))
        assertNotNull(tracker.findProcessed("new"))
    }

    @Test
    fun `淘汰后同一 ID 可被重新受理`() {
        val tracker = StopRequestTracker(maxRetained = 1)
        val handler = StopHandler(tracker)
        handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1000L)
        handler.handle(request("r2"), runningTaskId = "t1", nowMs = 1100L)
        // r1 已被淘汰，再次投递应重新受理而不是报重复
        val again = handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1200L)
        assertTrue(again is StopOutcome.Accepted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `容量为零会被拒绝`() {
        StopRequestTracker(maxRetained = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `容量为负会被拒绝`() {
        StopRequestTracker(maxRetained = -5)
    }

    @Test
    fun `clear 可清空跟踪记录`() {
        val tracker = StopRequestTracker()
        val handler = StopHandler(tracker)
        handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1000L)
        assertEquals(1, tracker.trackedCount)

        tracker.clear()
        assertEquals(0, tracker.trackedCount)
        assertNull(tracker.findProcessed("r1"))

        // 清空后同一 ID 可重新受理
        val again = handler.handle(request("r1"), runningTaskId = "t1", nowMs = 1300L)
        assertTrue(again is StopOutcome.Accepted)
    }

    @Test
    fun `无事可停的结果也会被记录以参与去重`() {
        val handler = StopHandler()
        handler.handle(request("r1"), runningTaskId = null, nowMs = 1000L)
        val second = handler.handle(request("r1"), runningTaskId = null, nowMs = 1100L)
        assertTrue(second is StopOutcome.Duplicate)
    }

    // ── 触发来源 ───────────────────────────────────────────────

    @Test
    fun `四种急停来源都能被受理`() {
        StopSource.values().forEach { source ->
            val handler = StopHandler()
            val req = StopRequest(
                requestId = "req-$source",
                taskId = "t1",
                timestampMs = 1000L,
                source = source,
            )
            val outcome = handler.handle(req, runningTaskId = "t1", nowMs = 1100L)
            assertTrue("来源 $source 未被受理", outcome is StopOutcome.Accepted)
        }
    }
}
