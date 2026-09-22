package com.pocketagent.agentlogic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检查点与恢复的测试。
 *
 * 关注的是**恢复决策的正确性** ——
 * 一个错误的恢复决策会让用户在毫无察觉的情况下，
 * 把一个已中断的任务从半路继续，而前置步骤的效果可能早已消失
 * （App 被切走了、页面变了、按钮挪了位置）。
 */
class TaskCheckpointTest {

    /** 内存实现，用于测试。刻意放在测试里 —— 生产实现由 Android 层提供 */
    private class FakeStore : CheckpointStore {
        val saved = mutableMapOf<String, TaskCheckpoint>()
        var saveCount = 0

        override suspend fun save(checkpoint: TaskCheckpoint) {
            saveCount++
            saved[checkpoint.taskId] = checkpoint
        }

        override suspend fun load(taskId: String): TaskCheckpoint? = saved[taskId]

        override suspend fun loadLatestIncomplete(): TaskCheckpoint? =
            saved.values.filter { !it.finished }.maxByOrNull { it.savedAtMs }

        override suspend fun delete(taskId: String) {
            saved.remove(taskId)
        }
    }

    private fun checkpoint(
        taskId: String = "t1",
        userInput: String = "给张三发个消息",
        completedSteps: Int = 1,
        replanCount: Int = 0,
        plannedActions: List<String> = listOf("OPEN_APP", "CLICK", "INPUT_TEXT"),
        usage: BudgetUsage = BudgetUsage(turns = 1),
        savedAtMs: Long = 1_000L,
        finished: Boolean = false,
    ) = TaskCheckpoint(
        taskId = taskId,
        userInput = userInput,
        completedSteps = completedSteps,
        replanCount = replanCount,
        plannedActions = plannedActions,
        usage = usage,
        savedAtMs = savedAtMs,
        finished = finished,
    )

    // ── 构造校验 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `taskId 为空时构造失败`() {
        checkpoint(taskId = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `已完成步数为负时构造失败`() {
        checkpoint(completedSteps = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `已完成步数超过计划步数时构造失败`() {
        // 这个校验防的是"恢复到一个不存在的步骤"
        checkpoint(completedSteps = 99, plannedActions = listOf("CLICK"))
    }

    @Test
    fun `已完成步数等于计划步数是合法的`() {
        val c = checkpoint(completedSteps = 1, plannedActions = listOf("CLICK"))
        assertFalse(c.hasRemaining)
        assertEquals(0, c.remainingSteps)
    }

    // ── 剩余步数 ───────────────────────────────────────────────

    @Test
    fun `剩余步数按计划减已完成计算`() {
        val c = checkpoint(completedSteps = 1, plannedActions = listOf("A", "B", "C"))
        assertEquals(2, c.remainingSteps)
        assertTrue(c.hasRemaining)
    }

    // ── 保存 ───────────────────────────────────────────────────

    @Test
    fun `保存后可载入`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        val c = checkpoint()

        mgr.save(c)
        assertEquals(c, store.load("t1"))
    }

    @Test
    fun `同一任务重复保存是覆盖而不是追加`() = runTest {
        // 幂等性 —— 否则用户中断恢复一次就会看到两份同名记录
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)

        mgr.save(checkpoint(completedSteps = 1))
        mgr.save(checkpoint(completedSteps = 2))

        assertEquals(1, store.saved.size)
        assertEquals(2, store.load("t1")!!.completedSteps)
    }

    @Test
    fun `每步落盘靠调用方每次 save 实现`() = runTest {
        // D-AW：每步都落盘。Manager 不自己做节流 ——
        // 省这一次写入会让恢复点变得不可预测。
        //
        // ⚠️ 计划步数必须 ≥ 循环次数：第一版我用了默认的 3 步计划却循环 5 次，
        //    被 TaskCheckpoint 的 init 校验拦下（completedSteps > plannedActions.size）。
        //    那次红是**测试 fixture 自相矛盾**，不是源码问题 ——
        //    而 init 校验能拦住它，说明那条校验是有价值的。
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        val plan = List(5) { "CLICK" }

        repeat(5) { step -> mgr.save(checkpoint(completedSteps = step, plannedActions = plan)) }

        assertEquals(5, store.saveCount)
    }

    @Test
    fun `步数超过计划步数会被构造校验拦下`() {
        // 这条校验防的是"恢复到一个不存在的步骤"
        val error = runCatching { checkpoint(completedSteps = 4, plannedActions = List(3) { "CLICK" }) }
        assertTrue(error.isFailure)
    }

    // ── 恢复建议 ───────────────────────────────────────────────

    @Test
    fun `无可恢复内容时返回 Nothing`() = runTest {
        val mgr = TaskCheckpointManager(FakeStore())
        assertEquals(ResumeOffer.Nothing, mgr.offerResume(2_000L))
    }

    @Test
    fun `有未完成检查点时给出恢复建议`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        mgr.save(checkpoint(completedSteps = 1, savedAtMs = 1_000L))

        val offer = mgr.offerResume(2_000L) as ResumeOffer.Available
        assertEquals("t1", offer.taskId)
        assertEquals("给张三发个消息", offer.userInput)
        assertEquals(1, offer.completedSteps)
        assertEquals(2, offer.remainingSteps)
        assertEquals(1_000L, offer.ageMs)
    }

    @Test
    fun `已完成的检查点不参与恢复提示`() = runTest {
        // 否则用户每次启动都会被问"要不要恢复这个早已完成的任务"
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        mgr.save(checkpoint(finished = true))

        assertEquals(ResumeOffer.Nothing, mgr.offerResume(2_000L))
    }

    @Test
    fun `全部步骤已完成的检查点不提示恢复`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        mgr.save(checkpoint(completedSteps = 3, plannedActions = listOf("A", "B", "C")))

        assertEquals(ResumeOffer.Nothing, mgr.offerResume(2_000L))
    }

    @Test
    fun `多个未完成检查点时取最近的一个`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        mgr.save(checkpoint(taskId = "old", savedAtMs = 1_000L))
        mgr.save(checkpoint(taskId = "new", savedAtMs = 5_000L))

        val offer = mgr.offerResume(6_000L) as ResumeOffer.Available
        assertEquals("new", offer.taskId)
    }

    @Test
    fun `多个检查点时已完成的那些被忽略`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        mgr.save(checkpoint(taskId = "done", savedAtMs = 9_000L, finished = true))
        mgr.save(checkpoint(taskId = "pending", savedAtMs = 5_000L))

        val offer = mgr.offerResume(10_000L) as ResumeOffer.Available
        assertEquals("pending", offer.taskId)
    }

    // ── 过期 ───────────────────────────────────────────────────

    @Test
    fun `超过有效期时返回 TooOld 而不是 Nothing`() = runTest {
        // ★ 两者对用户的含义完全不同：
        //   Nothing = "上次没有任务没跑完"
        //   TooOld  = "上次有个任务没跑完，但已经过去太久了"
        //   后者如果不说，用户会疑惑"我明明记得有个任务没做完"。
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store, maxAgeMs = 1_000L)
        mgr.save(checkpoint(savedAtMs = 1_000L, userInput = "买张火车票"))

        val offer = mgr.offerResume(10_000L) as ResumeOffer.TooOld
        assertEquals("t1", offer.taskId)
        assertEquals("买张火车票", offer.userInput)
        assertEquals(9_000L, offer.ageMs)
    }

    @Test
    fun `恰好等于有效期时不算过期`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store, maxAgeMs = 1_000L)
        mgr.save(checkpoint(savedAtMs = 1_000L))

        assertTrue(mgr.offerResume(2_000L) is ResumeOffer.Available)
    }

    @Test
    fun `时钟倒流不判为过期`() = runTest {
        // 用户改时间或 NTP 校时会让 ageMs 变负。
        // 这种情况按"刚保存的"处理，而不是判定为"来自未来"而拒绝。
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store, maxAgeMs = 1_000L)
        mgr.save(checkpoint(savedAtMs = 100_000L))

        val offer = mgr.offerResume(1_000L) as ResumeOffer.Available
        assertEquals("时钟倒流时 ageMs 应归零", 0L, offer.ageMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxAgeMs 为 0 时构造失败`() {
        TaskCheckpointManager(FakeStore(), maxAgeMs = 0L)
    }

    // ── 预算与重规划的继承（★ 防"恢复即绕过预算"）─────────────

    @Test
    fun `恢复时继承已消耗的预算用量`() = runTest {
        // ★ 若不继承，"中断恢复"就成为绕过预算的后门：
        //   反复恢复，预算永远用不完。
        val usage = BudgetUsage(turns = 5, elapsedMs = 120_000L, energyMicroAh = 20_000L, uploadBytes = 3_000_000L)
        val mgr = TaskCheckpointManager(FakeStore())
        val c = checkpoint(usage = usage)

        assertEquals(usage, mgr.restoreUsage(c))
    }

    @Test
    fun `恢复时继承重规划次数`() = runTest {
        // 同理：不继承的话中断恢复能无限刷新重规划上限
        val mgr = TaskCheckpointManager(FakeStore())
        assertEquals(2, mgr.restoreReplanCount(checkpoint(replanCount = 2)))
    }

    @Test
    fun `继承的预算确实能触发中止`() = runTest {
        // 端到端验证"继承"是有意义的，而不只是把字段搬了一下
        val budget = AgentBudget(maxTurns = 8)
        val guard = BudgetGuard(budget)
        val mgr = TaskCheckpointManager(FakeStore())

        val checkpoint = checkpoint(usage = BudgetUsage(turns = 8))
        val restored = mgr.restoreUsage(checkpoint)

        assertTrue(
            "恢复到已达上限的预算应当立即中止",
            guard.check(restored) is BudgetVerdict.Exceeded,
        )
    }

    // ── 计划还原 ───────────────────────────────────────────────

    @Test
    fun `计划可以还原成动作枚举`() {
        val mgr = TaskCheckpointManager(FakeStore())
        val c = checkpoint(plannedActions = listOf("OPEN_APP", "CLICK", "INPUT_TEXT"))

        assertEquals(
            listOf(StepAction.OPEN_APP, StepAction.CLICK, StepAction.INPUT_TEXT),
            mgr.restorePlan(c),
        )
    }

    @Test
    fun `有步骤名无法解析时整体还原失败`() {
        // ★ 半个计划比没有计划更危险 ——
        //   它会从一个错误的序号往下执行（比如把"输入文字"当成"点击"）。
        val mgr = TaskCheckpointManager(FakeStore())
        val c = checkpoint(plannedActions = listOf("OPEN_APP", "FLY_TO_MOON", "CLICK"))

        assertNull(mgr.restorePlan(c))
    }

    @Test
    fun `空计划可以还原成空列表`() {
        val mgr = TaskCheckpointManager(FakeStore())
        assertNotNull(mgr.restorePlan(checkpoint(completedSteps = 0, plannedActions = emptyList())))
        assertEquals(emptyList<StepAction>(), mgr.restorePlan(checkpoint(completedSteps = 0, plannedActions = emptyList())))
    }

    // ── 结束与丢弃 ─────────────────────────────────────────────

    @Test
    fun `标记完成会写入 finished 检查点`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        mgr.save(checkpoint(completedSteps = 1))

        val done = mgr.markFinished("t1", usage = BudgetUsage(turns = 3), nowMs = 9_000L)

        assertNotNull(done)
        assertTrue(done!!.finished)
        assertEquals(3, done.usage.turns)
        assertEquals(9_000L, done.savedAtMs)
        // 完成后不应再提示恢复
        assertEquals(ResumeOffer.Nothing, mgr.offerResume(10_000L))
    }

    @Test
    fun `标记完成时若原本没有检查点则返回 null`() = runTest {
        val mgr = TaskCheckpointManager(FakeStore())
        assertNull(mgr.markFinished("t1", usage = BudgetUsage(), nowMs = 1_000L))
    }

    @Test
    fun `放弃恢复会删除检查点`() = runTest {
        val store = FakeStore()
        val mgr = TaskCheckpointManager(store)
        mgr.save(checkpoint())

        mgr.discard("t1")

        assertEquals(ResumeOffer.Nothing, mgr.offerResume(2_000L))
        assertNull(store.load("t1"))
    }

    // ── 检查点里不含截图 ───────────────────────────────────────

    @Test
    fun `检查点不携带任何图像数据`() {
        // ★ 截图是最高敏感度数据（可能含验证码/聊天/支付页）。
        //   检查点要长期驻留磁盘，把截图放进去等于把敏感数据
        //   从"用完即回收"变成"永久留档"。
        //
        //   这个测试用"检查点的字段类型里没有 Android 类型"来兜底 ——
        //   本模块零 Android 依赖，所以任何 Bitmap 都编译不进来。
        val fieldTypes = TaskCheckpoint::class.java.declaredFields.map { it.type.name }
        assertFalse(
            "检查点不得含有任何 Android 类型（截图/节点树）：$fieldTypes",
            fieldTypes.any { it.startsWith("android.") || it.startsWith("androidx.") },
        )
    }
}
