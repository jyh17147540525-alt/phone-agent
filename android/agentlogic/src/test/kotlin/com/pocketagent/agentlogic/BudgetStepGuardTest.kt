package com.pocketagent.agentlogic

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ═══════════════════════════════════════════════════════════════════
//  预算/步数配置的合法性 —— 纯函数，与循环分开测
// ═══════════════════════════════════════════════════════════════════

/**
 * `budgetStepMisconfiguration` 的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这个「配置检查」值得单独测
 * ═══════════════════════════════════════════════════════════════
 *
 * `maxTurns` 与 `maxSteps` 来自两个不同模块（`BudgetGuard` / `PhoneTask`），
 * 编译器不会替我们把它们连起来。真机上就发生过：两个都填 12，
 * 于是 `StepLimitReached` 成了死代码，用户看到的是像故障的
 * 「预算超限（TURNS）：轮次已达上限 12」。
 *
 * 这类「两个数字的关系」用行为测试很难钉（要恰好让两个上限同时逼近），
 * 直接测这个纯函数最省事、也最不容易漏。
 */
class BudgetStepGuardTest {

    @Test
    fun `相等是不合法的`() {
        val why = budgetStepMisconfiguration(maxTurns = 12, maxSteps = 12)

        assertNotNull("12/12 必须被判为不合法 —— 这正是真机上踩过的配置", why)
        assertTrue("理由应当解释清楚为什么，实际：$why", why!!.contains("StepLimitReached"))
    }

    @Test
    fun `预算小于步数也不合法`() {
        assertNotNull(
            "预算(5) < 步数(10) 时，还没到步数就先被预算中止了",
            budgetStepMisconfiguration(maxTurns = 5, maxSteps = 10),
        )
    }

    @Test
    fun `预算大于步数是合法的`() {
        assertNull(
            "20/16 是当前生产配置，必须合法",
            budgetStepMisconfiguration(maxTurns = 20, maxSteps = 16),
        )
    }

    @Test
    fun `非正数不合法`() {
        assertNotNull(budgetStepMisconfiguration(maxTurns = 0, maxSteps = 10))
        assertNotNull(budgetStepMisconfiguration(maxTurns = 10, maxSteps = 0))
        assertNotNull(budgetStepMisconfiguration(maxTurns = -1, maxSteps = 5))
    }
}
