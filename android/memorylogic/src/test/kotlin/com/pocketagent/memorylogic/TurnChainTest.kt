package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **上下文链**的测试 —— 钉住 2026-10-26 真机上那个"模型没有记忆"的 bug。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 那个 bug 的形态值得完整记下来
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户反馈："模型似乎只有单次记忆，没有上下文。"
 *
 * 排查发现**不是"没传上下文"，而是"上下文被自己弄丢了"**：
 *
 *  - `ConversationStore.append()` 没传 `parentTurnId`，于是每一轮
 *    在库里都是**孤立节点**（parentTurnId = null）
 *  - `currentPath()` 靠 `activeLeafTurnId` **回溯父链**取路径 ——
 *    孤立节点回溯一步就停
 *  - 结果：第 1 轮 append 后路径 1 条（看着正常），
 *    第 2 轮 append 后路径**还是 1 条**（第 1 轮凭空消失）
 *
 * ⚠️ 它为什么能潜伏这么久：**第一轮永远是对的**。
 *    只有连发第二句才暴露，而那时用户会以为是"模型记性差"，
 *    不会怀疑到数据层。
 *
 * ⇒ 这组测试直接钉 `TurnChain.currentPath` 的行为：
 *    **父链完整 ⇒ 全部回来；父链断了 ⇒ 只剩最后一个**。
 */
class TurnChainTest {

    private fun turn(id: String, parent: String?, at: Long = 0L) = Turn(
        id = id,
        sessionId = "s1",
        role = TurnRole.USER,
        text = "t-$id",
        at = at,
        parentTurnId = parent,
    )

    @Test
    fun `父链完整时全部回到路径上`() {
        // t1 ← t2 ← t3，叶子是 t3
        val all = listOf(
            turn("t1", null),
            turn("t2", "t1"),
            turn("t3", "t2"),
        )
        val path = TurnChain.currentPath(all, activeLeafId = "t3")

        assertEquals("完整的父链必须全部回来", 3, path.size)
        assertEquals(listOf("t1", "t2", "t3"), path.map { it.id })
    }

    @Test
    fun `父链断裂时只剩最后一个 —— 这就是真机上发生的`() {
        // ★★ 三个孤立节点（真实 bug 的形态：append 没传 parentTurnId）
        val all = listOf(
            turn("t1", null),
            turn("t2", null),
            turn("t3", null),
        )
        val path = TurnChain.currentPath(all, activeLeafId = "t3")

        assertEquals(
            "孤立节点回溯一步就停 —— 这正是'第二轮后第一轮消失'的原因",
            1, path.size,
        )
        assertEquals("t3", path.first().id)
    }

    @Test
    fun `第二轮之后必须还看得见第一轮`() {
        // ★ 这是用户实际报告的场景的最小复现
        // 正确的做法：第 2 轮 append 时把 currentPath 的最后一条当父节点
        val t1 = turn("t1", null)
        // 追加第 2 轮时：parentId = currentPath.lastOrNull()?.id = t1
        val t2 = turn("t2", "t1")

        val path = TurnChain.currentPath(listOf(t1, t2), activeLeafId = "t2")

        assertEquals("两轮都应该在路径上", 2, path.size)
        assertTrue("第一轮不该消失", path.any { it.id == "t1" })
    }

    @Test
    fun `空会话返回空路径`() {
        assertTrue(TurnChain.currentPath(emptyList(), activeLeafId = null).isEmpty())
        assertTrue(TurnChain.currentPath(emptyList(), activeLeafId = "nope").isEmpty())
    }

    @Test
    fun `叶子为 null 时不该崩`() {
        val all = listOf(turn("t1", null), turn("t2", "t1"))
        // ⚠️ 叶子没设（新会话第一轮之前）—— 返回什么都行，但不能抛异常
        val path = TurnChain.currentPath(all, activeLeafId = null)
        assertTrue("不该崩", path.size <= all.size)
    }

    @Test
    fun `分支只回当前路径上的那一条`() {
        // t1 ← t2a ← t3     （用户走了这条）
        //   ↖ t2b           （用户否掉的分支）
        val all = listOf(
            turn("t1", null),
            turn("t2a", "t1"),
            turn("t2b", "t1"),
            turn("t3", "t2a"),
        )
        val path = TurnChain.currentPath(all, activeLeafId = "t3")

        assertEquals("只回当前路径", 3, path.size)
        assertTrue("不该包含被否掉的分支", path.none { it.id == "t2b" })
    }
}
