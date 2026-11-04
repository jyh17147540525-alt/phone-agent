package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **自主度阶梯** —— 每一档必须能说清"放过了什么、还拦着什么"。
 *
 * ⚠️ 这组测试里最重要的是 `放开档仍然拦不可逆` 那几条：
 *    它们是这套设计里唯一一处**不由用户档位决定**的硬边界。
 *    如果哪天有人为了让任务跑得更顺把它删了，钱和身份证号就会
 *    在没有第二次机会的地方被自动提交。
 */
class AutonomyPolicyTest {

    private fun decide(
        level: Autonomy,
        risk: RiskLevel,
        remembered: Consent = Consent.UNASKED,
        allowIrreversible: Boolean = false,
    ) = AutonomyPolicy.decide(level, risk, remembered, allowIrreversible)

    // ═══════════════════════════════════════════════════════════
    //  ★ 只读：任何档位都不问
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `只读动作在任何档位都直接放行`() {
        for (level in Autonomy.entries) {
            assertEquals("$level 不该把读屏也拦下来", ConsentDecision.Allowed, decide(level, RiskLevel.READ))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ ASK：就是原来的逐次确认
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每步都问时沿用既有授权策略`() {
        assertEquals(ConsentDecision.Allowed, decide(Autonomy.ASK, RiskLevel.WRITE, Consent.ALWAYS))
        assertEquals(ConsentDecision.Blocked, decide(Autonomy.ASK, RiskLevel.WRITE, Consent.DENIED))
        assertTrue(decide(Autonomy.ASK, RiskLevel.WRITE) is ConsentDecision.Ask)
    }

    @Test
    fun `花钱只有这一次和不要两个选项`() {
        val d = decide(Autonomy.ASK, RiskLevel.MONEY, Consent.UNASKED) as ConsentDecision.Ask
        assertEquals(listOf(Consent.ONCE, Consent.DENIED), d.options)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ NOTIFY：本机随便动，往外发东西要问
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `本机档不问本机改动`() {
        assertEquals(ConsentDecision.Allowed, decide(Autonomy.NOTIFY, RiskLevel.WRITE))
    }

    @Test
    fun `本机档仍然拦往外发`() {
        for (risk in listOf(RiskLevel.EXTERNAL, RiskLevel.MONEY, RiskLevel.IDENTITY)) {
            assertTrue("$risk 在 NOTIFY 档必须问", decide(Autonomy.NOTIFY, risk) is ConsentDecision.Ask)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ AUTO：连发出去也不问，只留不可逆两件
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `自己看着办档连对外发送也不问`() {
        assertEquals(ConsentDecision.Allowed, decide(Autonomy.AUTO, RiskLevel.EXTERNAL))
        assertEquals(ConsentDecision.Allowed, decide(Autonomy.AUTO, RiskLevel.WRITE))
    }

    @Test
    fun `自己看着办档仍拦花钱与身份信息`() {
        assertTrue(decide(Autonomy.AUTO, RiskLevel.MONEY) is ConsentDecision.Ask)
        assertTrue(decide(Autonomy.AUTO, RiskLevel.IDENTITY) is ConsentDecision.Ask)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★ YOLO：唯一的硬边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `放开档仍然拦不可逆`() {
        val money = decide(Autonomy.YOLO, RiskLevel.MONEY)
        val id = decide(Autonomy.YOLO, RiskLevel.IDENTITY)
        assertTrue("放开档也必须拦住付款", money is ConsentDecision.Ask)
        assertTrue("放开档也必须拦住身份信息", id is ConsentDecision.Ask)
    }

    @Test
    fun `设置里显式关掉之后才真的不问`() {
        assertEquals(
            ConsentDecision.Allowed,
            decide(Autonomy.YOLO, RiskLevel.MONEY, allowIrreversible = true),
        )
    }

    @Test
    fun `放开档不给以后都行的选项`() {
        val d = decide(Autonomy.YOLO, RiskLevel.IDENTITY) as ConsentDecision.Ask
        assertEquals("不可逆动作永远只给这一次", listOf(Consent.ONCE, Consent.DENIED), d.options)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 档位单调性：越往上，问得越少
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `档位越高问得越少`() {
        val risks = listOf(RiskLevel.READ, RiskLevel.WRITE, RiskLevel.EXTERNAL)
        val counts = Autonomy.entries.map { level -> risks.count { decide(level, it) is ConsentDecision.Ask } }
        // READ 本来就不问，所以"每步都问"档在 WRITE/EXTERNAL 上各有一次询问
        assertEquals(counts.sortedDescending(), counts)
        assertEquals(2, counts.first())
        assertEquals(0, counts.last())
    }
}
