package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **调度频率** —— 要快，又不能每轮都跑。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"省钱省成了瞎子"
 * ═══════════════════════════════════════════════════════════════
 *
 * 只按"每 N 轮跑一次"省钱，会漏掉避开的那几轮里说的话。
 * 下面第一条测试就是钉这个。
 */
class BackgroundSchedulerTest {

    private val policy = SchedulerPolicy.Default
    private fun fresh() = SchedulerState()

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 1：跳过的轮次不会丢（攒批）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `第 0 轮会跑一次 因为还没有任何分析`() {
        // ⚠️ 这一条是**发现的**而不是设计的：
        //    第一次跑时 `lastRun` 是"很久以前"，于是所有任务都到期。
        //    这是对的 —— 什么都没分析过，第一轮就该看一眼。
        val ran = BackgroundScheduler.decide(0, "嗯", fresh(), policy)
        assertTrue("第 0 轮该跑场记：$ran", BackgroundTask.DIRECTOR in ran)
    }

    @Test
    fun `跳过的轮次会被攒起来`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是整个设计的关键
        // ══════════════════════════════════════════════════════
        // "每 3 轮跑一次"如果不攒批，第 1、2 轮说的话就**永远没被分析**。
        var state = fresh()
        // 第 0 轮跑了场记
        val turn0 = BackgroundScheduler.decide(0, "嗯", state, policy)
        state = BackgroundScheduler.advance(state, turn0, 0, policy)
        assertEquals("跑过之后攒批清零", 0, state.pending(BackgroundTask.DIRECTOR))

        // 第 1 轮：不到间隔、也没有信号 —— 跳过
        val turn1 = BackgroundScheduler.decide(1, "嗯", state, policy)
        state = BackgroundScheduler.advance(state, turn1, 1, policy)
        assertTrue("第 1 轮不该跑场记", BackgroundTask.DIRECTOR !in turn1)
        assertEquals("但攒批必须 +1", 1, state.pending(BackgroundTask.DIRECTOR))
    }

    @Test
    fun `攒批有上限`() {
        var state = fresh()
        repeat(50) { i ->
            val ran = emptyList<BackgroundTask>()
            state = BackgroundScheduler.advance(state, ran, i, policy)
        }
        assertTrue(
            "攒批不该无限增长：${state.pending(BackgroundTask.DIRECTOR)}",
            state.pending(BackgroundTask.DIRECTOR) <= policy.maxPendingTurns,
        )
    }

    @Test
    fun `跑过之后攒批清零`() {
        var state = fresh()
        val ran = listOf(BackgroundTask.DIRECTOR)
        state = BackgroundScheduler.advance(state, ran, 5, policy)
        assertEquals(0, state.pending(BackgroundTask.DIRECTOR))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 2：有信号立刻跑，不等间隔
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `长消息会立刻触发场记`() {
        val long = "今天发生了好多事情，我一件一件跟你说，" +
            "先是早上开会迟到了，然后中午又被老板叫去谈了一轮"
        val ran = BackgroundScheduler.decide(0, long, fresh(), policy)
        assertTrue("长消息该立刻看一眼", BackgroundTask.DIRECTOR in ran)
    }

    @Test
    fun `冲突词会立刻触发场记`() {
        val ran = BackgroundScheduler.decide(0, "你以前不是这样的", fresh(), policy)
        assertTrue("冲突信号该立刻看一眼", BackgroundTask.DIRECTOR in ran)
    }

    @Test
    fun `闲聊不会因为内容而触发`() {
        // ⚠️ 用第 1 轮（第 0 轮会因为"从未分析过"而跑）
        var state = fresh()
        state = BackgroundScheduler.advance(
            state,
            BackgroundScheduler.decide(0, "嗯", state, policy),
            0,
            policy,
        )
        val ran = BackgroundScheduler.decide(1, "嗯", state, policy)
        assertFalse("一个字的消息不该触发场记", BackgroundTask.DIRECTOR in ran)
        assertFalse("更不该触发生理导演", BackgroundTask.PHYSIO in ran)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 3：生理导演只在有线索时跑
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `身体线索会触发生理导演`() {
        for (t in listOf("我今天有点累", "头疼得厉害", "好冷", "睡不着", "肚子疼")) {
            val ran = BackgroundScheduler.decide(0, t, fresh(), policy)
            assertTrue("「$t」该触发生理导演：$ran", BackgroundTask.PHYSIO in ran)
        }
    }

    @Test
    fun `没有身体线索时不跑生理导演`() {
        val ran = BackgroundScheduler.decide(0, "帮我看看这个文件", fresh(), policy)
        assertFalse("不该跑", BackgroundTask.PHYSIO in ran)
    }

    @Test
    fun `生理导演有冷却`() {
        // 上一轮刚跑过
        val tr = SchedulerState(lastRunTurn = mapOf(BackgroundTask.PHYSIO to 4))
        val ran = BackgroundScheduler.decide(5, "还是好冷", tr, policy)
        assertFalse("冷却期内不该再跑", BackgroundTask.PHYSIO in ran)

        val later = BackgroundScheduler.decide(
            4 + policy.physioCooldownTurns,
            "还是好冷",
            tr,
            policy,
        )
        assertTrue("冷却过了可以再跑", BackgroundTask.PHYSIO in later)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 4：一轮最多一个任务
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `一轮最多跑一个后台任务`() {
        // 构造"三件事全都该跑"的局面
        val state = SchedulerState(
            lastRunTurn = mapOf(
                BackgroundTask.DIRECTOR to -100,
                BackgroundTask.PHYSIO to -100,
                BackgroundTask.MEMORY to -100,
            ),
        )
        val ran = BackgroundScheduler.decide(10, "我好累，你今天怎么这样", state, policy)
        assertEquals("一次只跑一个（成本）", 1, ran.size)
    }

    @Test
    fun `生理线索优先于场记`() {
        // 生理反应是"当下"的，晚了就没意义
        val state = SchedulerState(
            lastRunTurn = mapOf(
                BackgroundTask.DIRECTOR to -100,
                BackgroundTask.PHYSIO to -100,
                BackgroundTask.MEMORY to -100,
            ),
        )
        val ran = BackgroundScheduler.decide(10, "我好累", state, policy)
        assertEquals(listOf(BackgroundTask.PHYSIO), ran)
    }

    @Test
    fun `欠得越久优先级越高`() {
        // 场记攒了 10 轮 vs 生理没线索 —— 场记该赢
        val state = SchedulerState(
            lastRunTurn = mapOf(BackgroundTask.DIRECTOR to -100),
            pendingTurns = mapOf(BackgroundTask.DIRECTOR to 10),
        )
        val ran = BackgroundScheduler.decide(20, "嗯", state, policy)
        assertTrue("攒太久的场记该跑", BackgroundTask.DIRECTOR in ran)
    }

    // ═══════════════════════════════════════════════════════════
    //  确定性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同样的输入永远给同样的结果`() {
        val state = fresh()
        val a = BackgroundScheduler.decide(3, "我好累", state, policy)
        repeat(5) {
            assertEquals(a, BackgroundScheduler.decide(3, "我好累", state, policy))
        }
    }

    @Test
    fun `新一轮会清掉本轮已跑标记`() {
        val s = SchedulerState(ranThisTurn = setOf(BackgroundTask.DIRECTOR))
        assertTrue(s.newTurn().ranThisTurn.isEmpty())
    }
}
