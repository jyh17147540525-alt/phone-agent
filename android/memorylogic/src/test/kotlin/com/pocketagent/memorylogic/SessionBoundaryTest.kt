package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话边界 / 当前路径 / 问答配对。
 *
 * ⚠️ 这些测试守的是**静默**那一类 bug：
 *    边界判错 → 用户的历史"看起来消失了"（其实在另一个会话里）；
 *    路径取错 → 用户看到的分支和他选的不一样；
 *    配对丢数据 → 界面少一句话，而库里明明有。
 */
class SessionBoundaryTest {

    private val MINUTE = 60 * 1000L
    private val HOUR = 60 * MINUTE

    // ── ① 会话边界 ──────────────────────────────────────────────

    @Test
    fun `从来没说过话就开新会话`() {
        assertTrue(SessionBoundary.shouldStartNewSession(lastActivityAt = null, now = 1000L))
    }

    @Test
    fun `刚说完不新开`() {
        assertFalse(
            SessionBoundary.shouldStartNewSession(
                lastActivityAt = 1000L,
                now = 1000L + 5 * MINUTE,
            ),
        )
    }

    @Test
    fun `超过空闲阈值就新开`() {
        assertTrue(
            SessionBoundary.shouldStartNewSession(
                lastActivityAt = 1000L,
                now = 1000L + SessionBoundary.DEFAULT_IDLE_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun `阈值前一毫秒不新开_边界是闭区间`() {
        val t = SessionBoundary.DEFAULT_IDLE_TIMEOUT_MS
        assertFalse(
            SessionBoundary.shouldStartNewSession(1000L, 1000L + t - 1),
        )
        assertTrue(
            SessionBoundary.shouldStartNewSession(1000L, 1000L + t),
        )
    }

    @Test
    fun `★ 时钟回拨时不新开_否则用户的历史看起来消失了`() {
        // 用户改了系统时间、或 NTP 往回校正。
        // 此时"新开一个会话"会让他的历史**看起来没了**（其实在另一个会话里）。
        // ⇒ 沿用旧会话更接近用户预期。
        assertFalse(
            SessionBoundary.shouldStartNewSession(lastActivityAt = 10 * HOUR, now = 1 * HOUR),
        )
    }

    // ── ② 当前路径 ──────────────────────────────────────────────

    private fun turn(id: String, at: Long, parent: String? = null, text: String = id) =
        Turn(
            id = id,
            sessionId = "s1",
            role = TurnRole.USER,
            text = text,
            at = at,
            parentTurnId = parent,
        )

    @Test
    fun `扁平结构_没有叶子时全部按时间返回`() {
        val all = listOf(turn("c", 300), turn("a", 100), turn("b", 200))
        assertEquals(listOf("a", "b", "c"), TurnChain.currentPath(all, null).map { it.id })
    }

    @Test
    fun `★ 分叉时只取当前路径_被放弃的分支不出现`() {
        //  a ── b1（被放弃）
        //    └─ b2 ── c2（当前路径）
        val all = listOf(
            turn("a", 100),
            turn("b1", 200, parent = "a"),
            turn("b2", 300, parent = "a"),
            turn("c2", 400, parent = "b2"),
        )
        val path = TurnChain.currentPath(all, activeLeafId = "c2").map { it.id }
        assertEquals(listOf("a", "b2", "c2"), path)
        assertFalse("被放弃的分支不该出现在当前路径里", "b1" in path)
    }

    @Test
    fun `切回旧分支只改叶子即可`() {
        val all = listOf(
            turn("a", 100),
            turn("b1", 200, parent = "a"),
            turn("b2", 300, parent = "a"),
        )
        assertEquals(listOf("a", "b1"), TurnChain.currentPath(all, "b1").map { it.id })
        assertEquals(listOf("a", "b2"), TurnChain.currentPath(all, "b2").map { it.id })
    }

    @Test
    fun `★ 叶子找不到时退化成全部_而不是返回空`() {
        // 空列表会让界面显示"没有对话"，而用户的历史**明明还在库里** ——
        // 那是最坏的一种表现（看起来像数据丢了）。
        val all = listOf(turn("a", 100), turn("b", 200, parent = "a"))
        val path = TurnChain.currentPath(all, activeLeafId = "不存在的id")
        assertEquals(listOf("a", "b"), path.map { it.id })
    }

    @Test
    fun `★ 数据成环时不死循环`() {
        // 库里出现 A 的父是 B、B 的父是 A（手工改库 / 迁移 bug）。
        // 没有环检测这里就是**死循环**，而它跑在主线程上会直接 ANR。
        val all = listOf(
            turn("a", 100, parent = "b"),
            turn("b", 200, parent = "a"),
        )
        val path = TurnChain.currentPath(all, activeLeafId = "a")
        assertTrue("至少要返回点东西，且不能卡住", path.isNotEmpty())
        assertTrue(path.size <= 2)
    }

    // ── ③ 问答配对 ──────────────────────────────────────────────

    private fun u(id: String, at: Long, text: String = "问$id") =
        Turn(id, "s1", TurnRole.USER, text, at)

    private fun a(id: String, at: Long, text: String = "答$id") =
        Turn(id, "s1", TurnRole.ASSISTANT, text, at)

    private fun t(id: String, at: Long, text: String = "工具结果") =
        Turn(id, "s1", TurnRole.TOOL, text, at)

    @Test
    fun `正常一问一答配成一对`() {
        val pairs = TurnPairing.pair(listOf(u("u1", 100), a("a1", 200)))
        assertEquals(1, pairs.size)
        assertEquals("u1", pairs[0].prompt?.id)
        assertEquals("a1", pairs[0].reply?.id)
    }

    @Test
    fun `末尾还没答的提问_reply 为 null`() {
        val pairs = TurnPairing.pair(listOf(u("u1", 100), a("a1", 200), u("u2", 300)))
        assertEquals(2, pairs.size)
        assertNull("最后一句还没答", pairs[1].reply)
        assertEquals("u2", pairs[1].prompt?.id)
    }

    @Test
    fun `★ 连续两句用户_第一句不丢`() {
        // 用户连发两句（第一句还没等到答案就又发了一句）。
        // ⚠️ 丢掉第一句 = 界面少一句话，而库里明明有。
        val pairs = TurnPairing.pair(listOf(u("u1", 100), u("u2", 200), a("a2", 300)))
        assertEquals(2, pairs.size)
        assertEquals("u1", pairs[0].prompt?.id)
        assertNull("第一句确实没被回答", pairs[0].reply)
        assertEquals("u2", pairs[1].prompt?.id)
        assertEquals("a2", pairs[1].reply?.id)
    }

    @Test
    fun `★ 孤儿回答也不丢`() {
        val pairs = TurnPairing.pair(listOf(a("a1", 100)))
        assertEquals(1, pairs.size)
        assertNull(pairs[0].prompt)
        assertEquals("a1", pairs[0].reply?.id)
    }

    @Test
    fun `工具结果挂到前一对上_不单独成对`() {
        val pairs = TurnPairing.pair(listOf(u("u1", 100), t("t1", 150), a("a1", 200)))
        assertEquals("工具结果不该单独成对", 1, pairs.size)
        assertEquals(1, pairs[0].toolNotes.size)
    }

    @Test
    fun `空输入返回空`() {
        assertTrue(TurnPairing.pair(emptyList()).isEmpty())
    }

    // ── ④ Turn 的向后兼容 ───────────────────────────────────────

    @Test
    fun `新增的两个字段都有默认值_老调用方不用改`() {
        val old = Turn("t1", "s1", TurnRole.USER, "你好", 1000L)
        assertNull(old.parentTurnId)
        assertFalse(old.superseded)
    }
}
