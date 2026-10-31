package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **完成反馈按钮**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是三个"用户会明显感觉到"的失败
 * ═══════════════════════════════════════════════════════════════
 *
 *  ① 连点两次 ⇒ 重复提交（或者一成一败却显示失败）
 *  ② 迟到响应 ⇒ 已完成后闪回失败
 *  ③ 完成态还能点 ⇒ 把做完的事改回去
 */
class CompletionStateTest {

    // ═══════════════════════════════════════════════════════════
    //  ① 防连点
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `初始状态可点`() {
        val s = CompletionState.initial()
        assertEquals(CompletionState.Phase.IDLE, s.phase)
        assertTrue(s.clickable)
        assertFalse(s.showsDone)
        assertFalse(s.showsWorking)
    }

    @Test
    fun `第一次点击开始工作`() {
        val (next, attempt) = CompletionState.begin(CompletionState.initial())!!
        assertEquals(CompletionState.Phase.WORKING, next.phase)
        assertEquals("第一次尝试编号应为 1", 1, attempt)
        assertTrue(next.showsWorking)
        assertFalse("工作中不该可点", next.clickable)
    }

    @Test
    fun `工作中再点被拒绝`() {
        // ⚠️ 这是最重要的一条：连点两次会发两个请求。
        //    两次都成功 ⇒ 重复记录；一成一败 ⇒ 显示失败但事情做成了。
        val (working, _) = CompletionState.begin(CompletionState.initial())!!
        assertNull("工作中不该允许再次开始", CompletionState.begin(working))
    }

    @Test
    fun `完成后点击被拒绝`() {
        val (working, attempt) = CompletionState.begin(CompletionState.initial())!!
        val done = CompletionState.succeed(working, attempt)
        assertNull("已完成不该允许再次开始", CompletionState.begin(done))
        assertFalse(done.clickable)
    }

    @Test
    fun `失败后可以重试`() {
        val (working, attempt) = CompletionState.begin(CompletionState.initial())!!
        val failed = CompletionState.fail(working, attempt, "网络")
        assertTrue("失败后必须可重试", failed.clickable)
        val again = CompletionState.begin(failed)
        assertNotNull("重试应当被允许", again)
        assertEquals("尝试编号应递增", 2, again!!.second)
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 迟到响应
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `迟到的旧结果被丢弃`() {
        // ══════════════════════════════════════════════════════
        //  ★ 复刻的真实竞态
        // ══════════════════════════════════════════════════════
        // 第 1 次超时 → 用户看到失败 → 重试（第 2 次）
        // 第 2 次还在飞时，**第 1 次的迟到响应**回来了，说失败。
        // 如果无条件应用，界面会从"已完成"闪回"失败"。
        val (w1, a1) = CompletionState.begin(CompletionState.initial())!!
        val (w2, a2) = CompletionState.begin(
            CompletionState.fail(w1, a1, "超时"),
        )!!

        // 第 2 次成功
        val done = CompletionState.succeed(w2, a2)
        assertEquals(CompletionState.Phase.DONE, done.phase)

        // 第 1 次的迟到失败响应
        val afterLate = CompletionState.fail(done, a1, "超时")
        assertEquals(
            "已完成是终态，不该被迟到的失败拉回去",
            CompletionState.Phase.DONE,
            afterLate.phase,
        )
    }

    @Test
    fun `不同 attempt 的结果被忽略`() {
        val (working, attempt) = CompletionState.begin(CompletionState.initial())!!
        // 用一个不是当前的 attempt
        assertFalse(CompletionState.shouldApplyResult(working, attempt + 1))
        assertTrue(CompletionState.shouldApplyResult(working, attempt))
    }

    @Test
    fun `非工作态的结果被忽略`() {
        assertFalse("IDLE 时不该接受结果", CompletionState.shouldApplyResult(CompletionState.initial(), 1))
        val (w, a) = CompletionState.begin(CompletionState.initial())!!
        val f = CompletionState.fail(w, a, "x")
        assertFalse("FAILED 时不该再接受结果", CompletionState.shouldApplyResult(f, a))
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 状态语义
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `成功进入完成态并清除错误`() {
        val (w, a) = CompletionState.begin(CompletionState.initial())!!
        val failed = CompletionState.fail(w, a, "网络不好")
        val (w2, a2) = CompletionState.begin(failed)!!
        val done = CompletionState.succeed(w2, a2)

        assertEquals(CompletionState.Phase.DONE, done.phase)
        assertNull("成功后不该留错误", done.error)
        assertTrue(done.showsDone)
        assertFalse(done.showsWorking)
    }

    @Test
    fun `失败会留下可读的原因`() {
        val (w, a) = CompletionState.begin(CompletionState.initial())!!
        val failed = CompletionState.fail(w, a, "")
        // ⚠️ 空原因要兜一句人话，不能显示空字符串
        assertNotNull("失败必须有一句人话", failed.error)
        assertTrue(failed.error!!.isNotBlank())
    }

    @Test
    fun `showXXX 三个标记互斥`() {
        // ★ 合并成一个 loading 布尔会导致"完成看起来像还在转"。
        val states = mutableListOf(CompletionState.initial())
        val (w, a) = CompletionState.begin(CompletionState.initial())!!
        states += w
        states += CompletionState.succeed(w, a)
        states += CompletionState.fail(w, a, "x")

        for (s in states) {
            val n = listOf(s.showsDone, s.showsWorking).count { it }
            assertTrue("最多一个为真（phase=${s.phase}）", n <= 1)
        }
    }

    @Test
    fun `可点性与阶段一致`() {
        val (w, a) = CompletionState.begin(CompletionState.initial())!!
        assertEquals(CompletionState.Phase.IDLE, CompletionState.initial().phase)
        assertTrue(CompletionState.initial().clickable)
        assertFalse(w.clickable)
        assertFalse(CompletionState.succeed(w, a).clickable)
        assertTrue(CompletionState.fail(w, a, "x").clickable)
    }
}
