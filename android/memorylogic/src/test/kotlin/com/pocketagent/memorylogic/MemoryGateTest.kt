package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆闸门：破环轮次**先于提取**被挡、原子与人设矛盾时被拒、理由可展示。
 *
 * ★ 最重要的那条：**OOC 轮次零落库**。记忆会放大错误 ——
 *   一次破环被写进长期记忆，下次它会被当作先例引用。
 */
class MemoryGateTest {

    private fun turn(id: String, text: String = "随便说点什么"): Turn =
        Turn(id = id, sessionId = "s1", role = TurnRole.ASSISTANT, text = text, at = 0L)

    private fun atom(id: String, text: String): Atom = Atom(
        id = id, kind = AtomKind.PREFERENCE, text = text,
        sourceTurnIds = listOf("t1"), confidence = 0.8, at = 0L,
    )

    // ── 第一道闸：轮次 ──────────────────────────────────────────

    @Test
    fun `★ 破环轮次不进提取，其余原样放行`() {
        val gate = MemoryGate()
        val turns = listOf(turn("t1"), turn("t2"), turn("t3"))

        val result = gate.screenTurns(turns, oocTurnIds = setOf("t2"))

        assertEquals("干净的两轮进提取", listOf("t1", "t3"), result.admitted.map { it.id })
        assertEquals(1, result.excluded.size)
        assertEquals("t2", result.excluded.single().turnId)
        assertEquals(ExclusionReason.GUARD_FLAGGED_OOC, result.excluded.single().reason)
    }

    @Test
    fun `★ 破环轮次零落库（闸门在提取之前）`() {
        // 判据：被标记的轮次**根本到不了提取器**，所以不可能产出原子。
        // 这里是"提取器只会看到 admitted"这个前提的可执行证明。
        val gate = MemoryGate()
        val admitted = gate.screenTurns(
            listOf(turn("ooc"), turn("ok")),
            oocTurnIds = setOf("ooc"),
        ).admitted

        assertFalse("破环轮次不许出现在提取输入里", admitted.any { it.id == "ooc" })
        assertTrue(admitted.any { it.id == "ok" })
    }

    @Test
    fun `没有标记时全部放行且没有排除项`() {
        val gate = MemoryGate()
        val result = gate.screenTurns(listOf(turn("t1"), turn("t2")), oocTurnIds = emptySet())
        assertEquals(2, result.admitted.size)
        assertTrue(result.excluded.isEmpty())
    }

    @Test
    fun `放行顺序与输入一致`() {
        val gate = MemoryGate()
        val ids = listOf("a", "b", "c", "d")
        val result = gate.screenTurns(ids.map { turn(it) }, oocTurnIds = setOf("b"))
        assertEquals(listOf("a", "c", "d"), result.admitted.map { it.id })
    }

    @Test
    fun `排除原因带上可展示的说明`() {
        // 闸门不能是黑箱：用户问「它为什么没记住」时要答得上来。
        assertTrue(ExclusionReason.GUARD_FLAGGED_OOC.label.isNotBlank())
        assertTrue(ExclusionReason.GUARD_FLAGGED_OOC.label.contains("长期记忆"))
    }

    @Test
    fun `空输入返回空结果`() {
        val result = MemoryGate().screenTurns(emptyList(), oocTurnIds = setOf("x"))
        assertTrue(result.admitted.isEmpty())
        assertTrue(result.excluded.isEmpty())
    }

    // ── 第二道闸：原子 ──────────────────────────────────────────

    @Test
    fun `默认端口不拦任何原子（显式命名，不会被误当成检查过了）`() {
        val gate = MemoryGate()
        val atoms = listOf(atom("a1", "用户喜欢简洁的回答"))
        val result = gate.screenAtoms(atoms)
        assertEquals(1, result.admitted.size)
        assertTrue(result.rejected.isEmpty())
    }

    @Test
    fun `与人设矛盾的原子被拒绝且带理由`() {
        val port = PersonaContradictionPort { text ->
            if (text.contains("付款")) "这条和人设冲突：助理不代付款。" else null
        }
        val gate = MemoryGate(port)
        val result = gate.screenAtoms(
            listOf(atom("a1", "用户喜欢简洁"), atom("a2", "用户希望助理直接付款")),
        )

        assertEquals("只留下没冲突的", listOf("a1"), result.admitted.map { it.id })
        assertEquals(1, result.rejected.size)
        assertEquals("a2", result.rejected.single().atom.id)
        assertTrue("理由要能展示给用户", result.rejected.single().reason.contains("不代付款"))
    }

    @Test
    fun `被拒的原子原样返回而不是被改写`() {
        // 「改一句让它能过」得到的不是用户的记忆。
        val port = PersonaContradictionPort { "一律拒" }
        val original = atom("a1", "原样文本")
        val result = MemoryGate(port).screenAtoms(listOf(original))
        assertEquals(original, result.rejected.single().atom)
    }

    @Test
    fun `端口返回 null 表示不拦`() {
        val gate = MemoryGate(PersonaContradictionPort { null })
        assertTrue(gate.screenAtoms(listOf(atom("a1", "x"))).admitted.isNotEmpty())
    }
}
