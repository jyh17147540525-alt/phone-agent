package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片构造期的硬约束。
 *
 * ★ 这些断言守的是"**界面上的谎话**"这一类 bug ——
 *   它们不抛异常、不崩溃，只是让用户看到一句与事实不符的话。
 */
class CardTest {

    private fun intent(
        action: String = "给妈妈发一条消息",
        risk: RiskLevel = RiskLevel.EXTERNAL,
        reversible: Boolean = false,
        note: String? = null,
    ) = Card.Intent(
        id = CardId("i-1"),
        at = 1L,
        state = CardState.DRAFT,
        evidence = emptyList(),
        action = action,
        target = "微信",
        risk = risk,
        reversible = reversible,
        note = note,
    )

    private fun act(
        summary: String = "点「发送」",
        detail: String = "已派发，通道=ACCESSIBILITY",
        uncertainty: String? = null,
    ) = Card.Act(
        id = CardId("a-1"),
        at = 1L,
        state = CardState.RUNNING,
        evidence = emptyList(),
        tool = "android_tap",
        summary = summary,
        detail = detail,
        channel = "ACCESSIBILITY",
        latencyMs = 72L,
        uncertainty = uncertainty,
    )

    private fun trouble(
        gate: Gate = Gate.EXECUTION,
        exits: List<Exit> = FailurePlan.exitsFor(gate),
        reason: String = "点了，但界面没反应",
    ) = Card.Trouble(
        id = CardId("t-1"),
        at = 1L,
        state = CardState.DONE,
        evidence = emptyList(),
        about = CardId("a-1"),
        gate = gate,
        reason = reason,
        exits = exits,
    )

    // ── CardId ─────────────────────────────────────────────────────

    @Test
    fun `CardId 不能为空`() {
        assertThrows(IllegalArgumentException::class.java) { CardId("") }
        assertThrows(IllegalArgumentException::class.java) { CardId("   ") }
    }

    @Test
    fun `CardId 的 toString 就是它的值`() {
        assertEquals("say-3", CardId("say-3").toString())
    }

    @Test
    fun `CardId 值相同即相等`() {
        assertEquals(CardId("a-1"), CardId("a-1"))
    }

    // ── CardState ──────────────────────────────────────────────────

    @Test
    fun `DONE BLOCKED FAILED CANCELLED 都是终态`() {
        assertTrue(CardState.DONE.isTerminal)
        assertTrue(CardState.BLOCKED.isTerminal)
        assertTrue(CardState.FAILED.isTerminal)
        assertTrue(CardState.CANCELLED.isTerminal)
    }

    @Test
    fun `DRAFT 与 RUNNING 不是终态`() {
        assertFalse(CardState.DRAFT.isTerminal)
        assertFalse(CardState.RUNNING.isTerminal)
    }

    // ── Evidence ───────────────────────────────────────────────────

    @Test
    fun `证据内容不能为空`() {
        assertThrows(IllegalArgumentException::class.java) {
            Evidence(EvidenceKind.NODE_REF, "")
        }
    }

    @Test
    fun `模型声称与其它三种证据是可区分的`() {
        val kinds = EvidenceKind.entries.toSet()
        assertEquals(4, kinds.size)
        assertTrue(EvidenceKind.MODEL_CLAIM in kinds)
    }

    // ── Card.Intent ────────────────────────────────────────────────

    @Test
    fun `预演卡必须有动作描述`() {
        assertThrows(IllegalArgumentException::class.java) { intent(action = "") }
    }

    @Test
    fun `预演动作描述不能超过 80 字`() {
        val ok = intent(action = "做".repeat(Card.Intent.ACTION_MAX_CHARS))
        assertEquals(Card.Intent.ACTION_MAX_CHARS, ok.action.length)
        assertThrows(IllegalArgumentException::class.java) {
            intent(action = "做".repeat(Card.Intent.ACTION_MAX_CHARS + 1))
        }
    }

    @Test
    fun `预演卡默认没有用户答复`() {
        assertNull(intent().note)
    }

    @Test
    fun `预演卡能记住用户否决时的原话`() {
        assertEquals("那个群不对", intent(note = "那个群不对").note)
    }

    @Test
    fun `预演卡的类型是 INTENT`() {
        assertEquals(CardKind.INTENT, intent().kind)
    }

    // ── Card.Act ───────────────────────────────────────────────────

    @Test
    fun `动作卡必须有工具名`() {
        assertThrows(IllegalArgumentException::class.java) {
            Card.Act(
                id = CardId("a-1"),
                at = 1L,
                state = CardState.RUNNING,
                evidence = emptyList(),
                tool = "",
                summary = "点发送",
                detail = "",
                channel = null,
                latencyMs = null,
                uncertainty = null,
            )
        }
    }

    @Test
    fun `动作卡必须有摘要`() {
        assertThrows(IllegalArgumentException::class.java) { act(summary = "") }
    }

    @Test
    fun `动作卡摘要不能超过 60 字`() {
        assertEquals(
            Card.Act.SUMMARY_MAX_CHARS,
            act(summary = "点".repeat(Card.Act.SUMMARY_MAX_CHARS)).summary.length,
        )
        assertThrows(IllegalArgumentException::class.java) {
            act(summary = "点".repeat(Card.Act.SUMMARY_MAX_CHARS + 1))
        }
    }

    @Test
    fun `动作卡的类型是 ACT`() {
        assertEquals(CardKind.ACT, act().kind)
    }

    @Test
    fun `盲点点击的不确定性文案是常量且提到无法确认`() {
        assertTrue(CardProjection.BLIND_NOTE.contains("无法确认点到了什么"))
        assertTrue(CardProjection.BLIND_NOTE.contains("如实告诉用户"))
    }

    // ── Card.Obs ───────────────────────────────────────────────────

    @Test
    fun `观察卡必须有摘要`() {
        assertThrows(IllegalArgumentException::class.java) {
            Card.Obs(
                id = CardId("o-1"),
                at = 1L,
                state = CardState.DONE,
                evidence = emptyList(),
                summary = "",
                nodeCount = 3,
                redacted = false,
            )
        }
    }

    @Test
    fun `观察卡节点数不能为负`() {
        assertThrows(IllegalArgumentException::class.java) {
            Card.Obs(
                id = CardId("o-1"),
                at = 1L,
                state = CardState.DONE,
                evidence = emptyList(),
                summary = "微信主界面",
                nodeCount = -1,
                redacted = false,
            )
        }
    }

    @Test
    fun `观察卡节点数可以是 0（空屏是合法状态）`() {
        val obs = Card.Obs(
            id = CardId("o-1"),
            at = 1L,
            state = CardState.DONE,
            evidence = emptyList(),
            summary = "空屏",
            nodeCount = 0,
            redacted = false,
        )
        assertEquals(0, obs.nodeCount)
        assertEquals(CardKind.OBS, obs.kind)
    }

    // ── Card.Say ───────────────────────────────────────────────────

    @Test
    fun `说话卡的类型是 SAY`() {
        val say = Card.Say(
            id = CardId("say-1"),
            at = 1L,
            state = CardState.RUNNING,
            evidence = emptyList(),
            text = "好的",
        )
        assertEquals(CardKind.SAY, say.kind)
    }

    // ── Card.Trouble ───────────────────────────────────────────────

    @Test
    fun `问题卡必须有原因`() {
        assertThrows(IllegalArgumentException::class.java) { trouble(reason = "") }
    }

    @Test
    fun `问题卡至少两个出口`() {
        assertThrows(IllegalArgumentException::class.java) {
            trouble(exits = listOf(Exit.Retry))
        }
    }

    @Test
    fun `问题卡不能有重复出口`() {
        assertThrows(IllegalArgumentException::class.java) {
            trouble(exits = listOf(Exit.Retry, Exit.Retry))
        }
    }

    @Test
    fun `安全拒绝的出口里不能出现重试`() {
        assertThrows(IllegalArgumentException::class.java) {
            trouble(gate = Gate.SAFETY, exits = listOf(Exit.Retry, Exit.TakeOver))
        }
    }

    @Test
    fun `问题卡记得它是在补救哪一张卡`() {
        assertEquals(CardId("a-1"), trouble().about)
    }

    @Test
    fun `问题卡的类型是 TROUBLE`() {
        assertEquals(CardKind.TROUBLE, trouble().kind)
    }

    @Test
    fun `替代出口必须说明换什么`() {
        assertThrows(IllegalArgumentException::class.java) { Exit.Alternative("") }
    }

    @Test
    fun `三个门的类型齐全`() {
        assertEquals(3, Gate.entries.size)
    }
}
