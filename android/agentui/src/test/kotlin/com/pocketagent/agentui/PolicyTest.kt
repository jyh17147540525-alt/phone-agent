package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预演判据 / 撤销判据 / 授权判据 / 失败出口。
 *
 * ★ 这四个对象加起来只有几十行，但它们决定了**用户什么时候被打断**。
 *   判据写松 → 危险动作直接做；判据写紧 → 用户把确认框当空气。
 *   两条错路都会让"确认"这个机制整体失效，而且都不会抛异常。
 */
class PolicyTest {

    // ── IntentGate ─────────────────────────────────────────────────

    @Test
    fun `只读动作不预演`() {
        assertFalse(IntentGate.needsPreflight(RiskLevel.READ, reversible = true))
        assertFalse(IntentGate.needsPreflight(RiskLevel.READ, reversible = false))
    }

    @Test
    fun `能撤销的写入不预演`() {
        assertFalse(IntentGate.needsPreflight(RiskLevel.WRITE, reversible = true))
    }

    @Test
    fun `不能撤销的写入要预演`() {
        assertTrue(IntentGate.needsPreflight(RiskLevel.WRITE, reversible = false))
    }

    @Test
    fun `对外发送一律预演`() {
        assertTrue(IntentGate.needsPreflight(RiskLevel.EXTERNAL, reversible = true))
        assertTrue(IntentGate.needsPreflight(RiskLevel.EXTERNAL, reversible = false))
    }

    @Test
    fun `花钱一律预演`() {
        assertTrue(IntentGate.needsPreflight(RiskLevel.MONEY, reversible = true))
    }

    @Test
    fun `身份一律预演`() {
        assertTrue(IntentGate.needsPreflight(RiskLevel.IDENTITY, reversible = true))
    }

    @Test
    fun `确认按钮写的是将要发生的事而不是「确定」`() {
        val labels = RiskLevel.entries.map { IntentGate.confirmLabel(it) }
        assertFalse(labels.any { it == "确定" || it == "取消" })
    }

    @Test
    fun `对外发送的确认按钮是「发送」`() {
        assertEquals("发送", IntentGate.confirmLabel(RiskLevel.EXTERNAL))
    }

    @Test
    fun `只读没有风险提示`() {
        assertNull(IntentGate.caution(RiskLevel.READ, reversible = true))
    }

    @Test
    fun `可撤销的写入没有风险提示`() {
        assertNull(IntentGate.caution(RiskLevel.WRITE, reversible = true))
    }

    @Test
    fun `不可撤销的写入有风险提示`() {
        assertNotNull(IntentGate.caution(RiskLevel.WRITE, reversible = false))
    }

    @Test
    fun `花钱的提示明说最后一步由用户点`() {
        val caution = IntentGate.caution(RiskLevel.MONEY, reversible = false)
        assertNotNull(caution)
        assertTrue(caution!!.contains("你自己点"))
    }

    @Test
    fun `身份的提示明说不代提交`() {
        val caution = IntentGate.caution(RiskLevel.IDENTITY, reversible = false)
        assertNotNull(caution)
        assertTrue(caution!!.contains("你自己确认"))
    }

    // ── UndoPolicy ─────────────────────────────────────────────────

    @Test
    fun `输入文字可撤销`() {
        assertTrue(UndoPolicy.isUndoable(ActionClass.TEXT_ENTRY))
    }

    @Test
    fun `草稿可撤销`() {
        assertTrue(UndoPolicy.isUndoable(ActionClass.DRAFT))
    }

    @Test
    fun `启动应用可撤销`() {
        assertTrue(UndoPolicy.isUndoable(ActionClass.LAUNCH))
    }

    @Test
    fun `点击不可撤销`() {
        assertFalse(UndoPolicy.isUndoable(ActionClass.TAP))
    }

    @Test
    fun `滑动不可撤销`() {
        assertFalse(UndoPolicy.isUndoable(ActionClass.SWIPE))
    }

    @Test
    fun `发送不可撤销`() {
        assertFalse(UndoPolicy.isUndoable(ActionClass.SEND))
    }

    @Test
    fun `删除不可撤销`() {
        assertFalse(UndoPolicy.isUndoable(ActionClass.DELETE))
    }

    @Test
    fun `付款不可撤销`() {
        assertFalse(UndoPolicy.isUndoable(ActionClass.PAY))
    }

    @Test
    fun `不可撤销必须给出理由`() {
        for (action in ActionClass.entries) {
            val v = UndoPolicy.verdict(action)
            if (v is UndoPolicy.Verdict.NotUndoable) {
                assertTrue("$action 的不可撤销理由为空", v.why.isNotBlank())
            }
        }
    }

    @Test
    fun `可撤销必须说明怎么撤`() {
        for (action in ActionClass.entries) {
            val v = UndoPolicy.verdict(action)
            if (v is UndoPolicy.Verdict.Undoable) {
                assertTrue("$action 的撤销方式为空", v.how.isNotBlank())
            }
        }
    }

    @Test
    fun `恰好三类动作可撤销`() {
        assertEquals(3, ActionClass.entries.count { UndoPolicy.isUndoable(it) })
    }

    // ── PermissionPolicy ───────────────────────────────────────────

    @Test
    fun `只读不问`() {
        assertEquals(ConsentDecision.Allowed, PermissionPolicy.decide(RiskLevel.READ, Consent.UNASKED))
    }

    @Test
    fun `只读即使被拒绝过也放行（拒绝不适用于只读）`() {
        assertEquals(ConsentDecision.Allowed, PermissionPolicy.decide(RiskLevel.READ, Consent.DENIED))
    }

    @Test
    fun `只读的选项列表为空`() {
        assertTrue(PermissionPolicy.optionsFor(RiskLevel.READ).isEmpty())
    }

    @Test
    fun `写入首次要问`() {
        assertTrue(PermissionPolicy.decide(RiskLevel.WRITE, Consent.UNASKED) is ConsentDecision.Ask)
    }

    @Test
    fun `写入记住「以后都行」后不再问`() {
        assertEquals(ConsentDecision.Allowed, PermissionPolicy.decide(RiskLevel.WRITE, Consent.ALWAYS))
    }

    @Test
    fun `记住「只这一次」后下一次仍然问`() {
        assertTrue(PermissionPolicy.decide(RiskLevel.WRITE, Consent.ONCE) is ConsentDecision.Ask)
    }

    @Test
    fun `明确拒绝过直接拦下`() {
        assertEquals(ConsentDecision.Blocked, PermissionPolicy.decide(RiskLevel.WRITE, Consent.DENIED))
    }

    @Test
    fun `写入的选项有三个`() {
        assertEquals(
            listOf(Consent.ONCE, Consent.ALWAYS, Consent.DENIED),
            PermissionPolicy.optionsFor(RiskLevel.WRITE),
        )
    }

    @Test
    fun `对外发送的选项有三个`() {
        assertEquals(3, PermissionPolicy.optionsFor(RiskLevel.EXTERNAL).size)
    }

    @Test
    fun `花钱的选项里没有「以后都行」`() {
        assertFalse(Consent.ALWAYS in PermissionPolicy.optionsFor(RiskLevel.MONEY))
    }

    @Test
    fun `身份的选项里没有「以后都行」`() {
        assertFalse(Consent.ALWAYS in PermissionPolicy.optionsFor(RiskLevel.IDENTITY))
    }

    @Test
    fun `花钱的选项恰好是「这一次」和「不要」`() {
        assertEquals(
            listOf(Consent.ONCE, Consent.DENIED),
            PermissionPolicy.optionsFor(RiskLevel.MONEY),
        )
    }

    @Test
    fun `花钱即使点了「以后都行」也不记住`() {
        assertNull(PermissionPolicy.toRemember(RiskLevel.MONEY, Consent.ALWAYS))
    }

    @Test
    fun `身份即使点了「以后都行」也不记住`() {
        assertNull(PermissionPolicy.toRemember(RiskLevel.IDENTITY, Consent.ALWAYS))
    }

    @Test
    fun `写入点「以后都行」会被记住`() {
        assertEquals(Consent.ALWAYS, PermissionPolicy.toRemember(RiskLevel.WRITE, Consent.ALWAYS))
    }

    @Test
    fun `点「只这一次」不记住`() {
        assertNull(PermissionPolicy.toRemember(RiskLevel.WRITE, Consent.ONCE))
    }

    @Test
    fun `点「不要」要记住（避免反复骚扰）`() {
        assertEquals(Consent.DENIED, PermissionPolicy.toRemember(RiskLevel.WRITE, Consent.DENIED))
    }

    @Test
    fun `询问标题同时写出能力和应用`() {
        val title = PermissionPolicy.promptTitle("点击屏幕", "微信")
        assertTrue(title.contains("点击屏幕"))
        assertTrue(title.contains("微信"))
    }

    @Test
    fun `每个风险等级都有「为什么问」的说明`() {
        for (risk in RiskLevel.entries) {
            assertTrue("$risk 缺少说明", PermissionPolicy.why(risk).isNotBlank())
        }
    }

    @Test
    fun `授权键的应用名不能为空（拒绝全局授权）`() {
        try {
            GrantKey("点击屏幕", "")
            throw AssertionError("应当拒绝空应用名")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("全局授权"))
        }
    }

    @Test
    fun `授权键的能力名不能为空`() {
        try {
            GrantKey("", "微信")
            throw AssertionError("应当拒绝空能力名")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `不同应用的授权互不影响`() {
        val wechat = GrantKey("点击屏幕", "微信")
        val bank = GrantKey("点击屏幕", "银行")
        assertTrue(wechat != bank)
    }

    // ── FailurePlan ────────────────────────────────────────────────

    @Test
    fun `安全拒绝没有重试出口`() {
        assertFalse(Exit.Retry in FailurePlan.exitsFor(Gate.SAFETY))
    }

    @Test
    fun `用户拒绝有重新授权的出口`() {
        val labels = FailurePlan.exitsFor(Gate.PERMISSION).filterIsInstance<Exit.Alternative>()
        assertTrue(labels.any { it.label.contains("重新授权") })
    }

    @Test
    fun `执行失败有重试`() {
        assertTrue(Exit.Retry in FailurePlan.exitsFor(Gate.EXECUTION))
    }

    @Test
    fun `执行失败有「我来」`() {
        assertTrue(Exit.TakeOver in FailurePlan.exitsFor(Gate.EXECUTION))
    }

    @Test
    fun `每一道门都至少两个出口`() {
        for (gate in Gate.entries) {
            assertTrue("$gate 出口不足", FailurePlan.exitsFor(gate).size >= 2)
        }
    }

    @Test
    fun `每一道门的出口都不重复`() {
        for (gate in Gate.entries) {
            val exits = FailurePlan.exitsFor(gate)
            assertEquals("$gate 有重复出口", exits.distinct().size, exits.size)
        }
    }

    @Test
    fun `三道门的标题互不相同`() {
        val heads = Gate.entries.map { FailurePlan.headline(it) }
        assertEquals(heads.size, heads.distinct().size)
    }

    @Test
    fun `安全拒绝的标题不说「失败」`() {
        assertFalse(FailurePlan.headline(Gate.SAFETY).contains("失败"))
    }

    @Test
    fun `每一道门都有接管提示且不是甩手`() {
        for (gate in Gate.entries) {
            val hint = FailurePlan.takeoverHint(gate)
            assertTrue("$gate 接管提示为空", hint.isNotBlank())
            assertTrue("$gate 接管提示太短", hint.length >= 8)
        }
    }

    @Test
    fun `可能原因最多三条`() {
        for (gate in Gate.entries) {
            assertTrue("$gate 原因过多", FailurePlan.likelyCauses(gate).size <= 3)
        }
    }

    @Test
    fun `替代出口的文案不是空话`() {
        for (gate in Gate.entries) {
            FailurePlan.exitsFor(gate).filterIsInstance<Exit.Alternative>().forEach {
                assertFalse("替代出口写成了空话：${it.label}", it.label == "换个方法")
            }
        }
    }

    // ── RiskLevel ──────────────────────────────────────────────────

    @Test
    fun `只有只读不需要同意`() {
        assertEquals(1, RiskLevel.entries.count { !it.requiresConsent })
        assertFalse(RiskLevel.READ.requiresConsent)
    }

    @Test
    fun `花钱与身份不可记忆`() {
        assertFalse(RiskLevel.MONEY.rememberable)
        assertFalse(RiskLevel.IDENTITY.rememberable)
    }

    @Test
    fun `只读写与对外可记忆`() {
        assertTrue(RiskLevel.READ.rememberable)
        assertTrue(RiskLevel.WRITE.rememberable)
        assertTrue(RiskLevel.EXTERNAL.rememberable)
    }

    @Test
    fun `风险等级的顺序是递增的`() {
        assertTrue(RiskLevel.READ.ordinal < RiskLevel.WRITE.ordinal)
        assertTrue(RiskLevel.WRITE.ordinal < RiskLevel.EXTERNAL.ordinal)
        assertTrue(RiskLevel.EXTERNAL.ordinal < RiskLevel.MONEY.ordinal)
        assertTrue(RiskLevel.MONEY.ordinal < RiskLevel.IDENTITY.ordinal)
    }
}
