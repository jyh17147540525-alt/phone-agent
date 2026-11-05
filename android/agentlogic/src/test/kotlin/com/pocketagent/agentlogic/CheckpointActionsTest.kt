package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CheckpointActions] —— 钉住"检查点的步数必须与动作名条数对齐"。
 *
 * 这条测试的来历是真机崩溃（见 CheckpointActions 的注释）：
 * 调用方喂 `completedSteps = 2, plannedActions = 1 条`，`TaskCheckpoint.init`
 * 抛 `IllegalArgumentException`，而它在 `launch` 里 ⇒ 进程直接没了。
 */
class CheckpointActionsTest {

    @Test
    fun `走一步就多一条`() {
        var actions = CheckpointActions.record(emptyList(), 1, "CLICK")
        assertEquals(listOf("CLICK"), actions)
        actions = CheckpointActions.record(actions, 2, "TYPE")
        assertEquals(listOf("CLICK", "TYPE"), actions)
    }

    @Test
    fun `重规划跳步时用当前动作补齐，长度等于步数`() {
        val actions = CheckpointActions.record(listOf("CLICK"), 4, "TYPE")
        assertEquals(4, actions.size)
        assertEquals("CLICK", actions[0])
        assertEquals(listOf("TYPE", "TYPE", "TYPE"), actions.drop(1))
    }

    @Test
    fun `补出来的结果一定能通过 TaskCheckpoint 的校验`() {
        val actions = CheckpointActions.record(emptyList(), 3, "CLICK")
        val cp = TaskCheckpoint(
            taskId = "t",
            userInput = "u",
            completedSteps = actions.size,
            replanCount = 0,
            plannedActions = actions,
            usage = BudgetUsage(),
            savedAtMs = 0L,
        )
        assertEquals(3, cp.completedSteps)
        assertEquals(0, cp.remainingSteps)
    }

    @Test
    fun `第零步不给东西 —— 还没开始就没有已完成动作`() {
        assertEquals(emptyList<String>(), CheckpointActions.record(emptyList(), 0, "CLICK"))
    }

    @Test
    fun `负数步数当没发生，不崩也不缩表`() {
        assertEquals(emptyList<String>(), CheckpointActions.record(emptyList(), -3, "CLICK"))
        assertEquals(listOf("A"), CheckpointActions.record(listOf("A"), -1, "B"))
    }

    @Test
    fun `已经够长就不再追加 —— 重复回调不许把名字堆成两倍`() {
        val actions = listOf("A", "B")
        assertEquals(actions, CheckpointActions.record(actions, 2, "C"))
        assertEquals(actions, CheckpointActions.record(actions, 1, "C"))
    }

    @Test
    fun `返回值是新列表，改它不会改到调用方那份`() {
        val old = listOf("A")
        val next = CheckpointActions.record(old, 2, "B")
        assertEquals(1, old.size)
        assertEquals(2, next.size)
        assertTrue(next !== old)
    }

    @Test
    fun `步数一路涨到上限也不会漏条`() {
        var actions: List<String> = emptyList()
        for (step in 1..20) {
            actions = CheckpointActions.record(actions, step, "STEP$step")
            assertEquals(step, actions.size)
        }
        assertEquals("STEP1", actions.first())
        assertEquals("STEP20", actions.last())
    }
}
