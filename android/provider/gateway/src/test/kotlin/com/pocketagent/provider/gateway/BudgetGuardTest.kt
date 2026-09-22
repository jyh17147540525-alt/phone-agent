package com.pocketagent.provider.gateway

import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BudgetGuard] 的离线单测。
 *
 * ⚠️ 本文件必须用 `org.junit.Assert` 而**不是 Truth** ——
 *    Truth 不在离线验证器的 classpath 里（见 run_logic_tests.py 的 LIBRARY_JARS）。
 *    用 Truth 会让离线通道编不过，而报错是"unresolved reference assertThat"，
 *    看起来像源码写错了。
 */
class BudgetGuardTest {

    // ─────────────────────────────────────────────────────────────
    //  测试替身
    // ─────────────────────────────────────────────────────────────

    /**
     * @param inputPrice 输入价（美元/百万 token）；null = 未知
     * @param outputPrice 输出价；null = 未知
     */
    private fun modelWith(
        inputPrice: Double? = 1.0,
        outputPrice: Double? = 2.0,
        label: String = "测试模型",
    ) = ModelConfig(
        id = "m1",
        label = label,
        credentialId = "c1",
        modelId = "test-model",
        modelDisplayName = "Test Model",
        tier = ModelTier.STANDARD,
        inputPricePerMillion = inputPrice,
        outputPricePerMillion = outputPrice,
        roles = setOf(ModelRole.WORKER),
        enabled = true,
    )

    private fun exceeded(verdict: BudgetVerdict): GatewayFailure.BudgetExceeded {
        assertTrue(
            "期望熔断，实际是 $verdict",
            verdict is BudgetVerdict.Exceeded,
        )
        return (verdict as BudgetVerdict.Exceeded).failure
    }

    // ─────────────────────────────────────────────────────────────
    //  无预算 = 放行
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `budget 为 null 时一律放行`() {
        val verdict = BudgetGuard.check(
            request = null,
            budget = null,
            model = modelWith(),
            estimatedInputTokens = 999_999_999,
        )

        // ⚠️ 必须是 Allowed 而不是 AllowedWithWarning ——
        //    "没传预算"和"有预算但算不出价格"是两件事，
        //    后者才需要在界面上提醒。合并会让探测请求也弹提示。
        assertEquals(BudgetVerdict.Allowed, verdict)
    }

    // ─────────────────────────────────────────────────────────────
    //  输入 token：超限 → 拒绝
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `输入 token 超限时拒绝`() {
        val failure = exceeded(
            BudgetGuard.check(
                request = null,
                budget = RequestBudget(maxTokens = 1000),
                model = modelWith(),
                estimatedInputTokens = 1500,
            )
        )

        assertEquals("单次请求 token 上限", failure.limitName)
        assertEquals("1000", failure.limitValue)
        assertEquals("1500", failure.actualValue)
    }

    @Test
    fun `输入 token 恰好等于上限时放行`() {
        // ⚠️ 边界值。用 `>=` 判断会让"设 1000 就只能到 999"，
        //    而用户从任何界面都看不出这一点。
        val verdict = BudgetGuard.check(
            request = null,
            budget = RequestBudget(maxTokens = 1000),
            model = modelWith(),
            estimatedInputTokens = 1000,
        )

        assertEquals(BudgetVerdict.Allowed, verdict)
    }

    // ─────────────────────────────────────────────────────────────
    //  费用：算得出时才检查
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `费用超限时拒绝并给出格式化金额`() {
        // 输入 1,000,000 token × $1/M = $1；输出 1,000,000 × $2/M = $2
        // 合计 $3，上限 $1 → 超
        val failure = exceeded(
            BudgetGuard.check(
                request = RequestBudget(maxTokens = 1_000_000),
                budget = RequestBudget(maxTokens = 2_000_000, maxCostUsd = 1.0),
                model = modelWith(inputPrice = 1.0, outputPrice = 2.0),
                estimatedInputTokens = 1_000_000,
            )
        )

        assertEquals("单次调用费用上限", failure.limitName)
        assertEquals("$1.0000", failure.limitValue)
        assertEquals("$3.0000", failure.actualValue)
    }

    @Test
    fun `费用恰好等于上限时放行`() {
        val verdict = BudgetGuard.check(
            request = RequestBudget(maxTokens = 1_000_000),
            budget = RequestBudget(maxTokens = 2_000_000, maxCostUsd = 3.0),
            model = modelWith(inputPrice = 1.0, outputPrice = 2.0),
            estimatedInputTokens = 1_000_000,
        )

        assertEquals(BudgetVerdict.Allowed, verdict)
    }

    @Test
    fun `maxCostUsd 为 null 时不做费用检查`() {
        val verdict = BudgetGuard.check(
            request = RequestBudget(maxTokens = 10_000_000),
            budget = RequestBudget(maxTokens = 100_000_000, maxCostUsd = null),
            model = modelWith(inputPrice = 500.0, outputPrice = 500.0),
            estimatedInputTokens = 10_000_000,
        )

        // null 表示"不限制"，不是"限制为 0"
        assertEquals(BudgetVerdict.Allowed, verdict)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 价格未知：必须显式分叉，绝不静默当作 0
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `价格未知且策略为 Allow 时放行但带警告`() {
        val verdict = BudgetGuard.check(
            request = RequestBudget(maxTokens = 1_000_000),
            budget = RequestBudget(
                maxTokens = 2_000_000,
                maxCostUsd = 1.0,
                costFallback = RequestBudget.CostFallback.Allow,
            ),
            model = modelWith(inputPrice = null, outputPrice = null),
            estimatedInputTokens = 1_000_000,
        )

        // ⚠️ 必须是 AllowedWithWarning。返回 Allowed 的话"预算对没标价的
        //    模型完全失效"这件事就**没有任何界面线索**，而用户会以为
        //    自己在被保护 —— 这正是他最不可能自己发现的那类问题。
        assertTrue("期望 AllowedWithWarning，实际是 $verdict", verdict is BudgetVerdict.AllowedWithWarning)
        val warning = (verdict as BudgetVerdict.AllowedWithWarning).warning
        assertTrue("警告里应含模型名，实际：$warning", warning.contains("测试模型"))
        assertTrue("警告里应说清预算不生效，实际：$warning", warning.contains("不生效"))
    }

    @Test
    fun `价格未知且策略为 Deny 时拒绝且理由是没标价`() {
        val failure = exceeded(
            BudgetGuard.check(
                request = RequestBudget(maxTokens = 1_000_000),
                budget = RequestBudget(
                    maxTokens = 2_000_000,
                    maxCostUsd = 1.0,
                    costFallback = RequestBudget.CostFallback.Deny,
                ),
                model = modelWith(inputPrice = null, outputPrice = null),
                estimatedInputTokens = 1_000_000,
            )
        )

        // ⚠️ actualValue 必须说"无法估算"，不能是 "$0.0000" ——
        //    后者会被读成"一分钱没花，凭什么拦我"，
        //    而用户会去查预算设置（那里没问题），排查方向全错。
        assertTrue(
            "actualValue 应说明是算不出来，实际：${failure.actualValue}",
            failure.actualValue.contains("无法估算"),
        )
    }

    @Test
    fun `只有输入价未知时也算价格未知`() {
        // 输入价有、输出价无 → estimatedCost 里输出按 0 算 → 不返回 null，
        // 所以这条实际会算出费用。这个测试钉住"部分缺失"的行为，
        // 防止将来有人把 estimatedCost 改成"任一缺失就返回 null"而没人发现。
        val verdict = BudgetGuard.check(
            request = RequestBudget(maxTokens = 1_000_000),
            budget = RequestBudget(maxTokens = 2_000_000, maxCostUsd = 0.5),
            model = modelWith(inputPrice = 1.0, outputPrice = null),
            estimatedInputTokens = 1_000_000,
        )

        // 输入 $1 + 输出按 0 = $1 > $0.5 → 拒绝
        assertTrue("期望熔断，实际是 $verdict", verdict is BudgetVerdict.Exceeded)
    }

    // ─────────────────────────────────────────────────────────────
    //  输出 token：截断而非拒绝
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `输出预算不足时收敛到剩余额度`() {
        val effective = BudgetGuard.effectiveMaxOutputTokens(
            request = RequestBudget(maxTokens = 5000),
            budget = RequestBudget(maxTokens = 3000),
            estimatedInputTokens = 1000,
        )

        // 剩 3000 - 1000 = 2000，要 5000 → 收敛到 2000（截断）
        assertEquals(2000, effective)
    }

    @Test
    fun `输出额度充足时保持调用方的请求值`() {
        val effective = BudgetGuard.effectiveMaxOutputTokens(
            request = RequestBudget(maxTokens = 500),
            budget = RequestBudget(maxTokens = 100_000),
            estimatedInputTokens = 1000,
        )

        assertEquals(500, effective)
    }

    @Test
    fun `调用方未指定 maxTokens 时用剩余额度`() {
        val effective = BudgetGuard.effectiveMaxOutputTokens(
            request = null,
            budget = RequestBudget(maxTokens = 8000),
            estimatedInputTokens = 3000,
        )

        assertEquals(5000, effective)
    }

    @Test
    fun `预算耗尽时输出额度为 null`() {
        // ⚠️ 返回 null 而不是 0 —— 0 会被下游当成"允许生成 0 个 token"，
        //    请求仍然发出去，然后上游报一个语焉不详的 400。
        //    返回 null 表示"不设上限"，配合 check() 已经在更早的地方拒绝了。
        val effective = BudgetGuard.effectiveMaxOutputTokens(
            request = RequestBudget(maxTokens = 5000),
            budget = RequestBudget(maxTokens = 1000),
            estimatedInputTokens = 1000,
        )

        assertNull(effective)
    }

    @Test
    fun `无预算时输出额度取调用方请求值`() {
        assertEquals(
            777,
            BudgetGuard.effectiveMaxOutputTokens(
                request = RequestBudget(maxTokens = 777),
                budget = null,
                estimatedInputTokens = 12345,
            ),
        )

        assertNull(
            BudgetGuard.effectiveMaxOutputTokens(
                request = null,
                budget = null,
                estimatedInputTokens = 12345,
            )
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  格式化：locale 陷阱
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `金额格式固定用小数点，不受默认 locale 影响`() {
        // ⚠️ 这条测的是 `String.format(Locale.US, ...)`。
        //
        //    关键点：**必须临时把默认 locale 换成用逗号作小数点的地区**
        //    （德语 / 土耳其语 / 法语…），否则这个测试是假的 ——
        //    在中文或英文 locale 下，即使代码写漏了 `Locale.US`，
        //    输出也是 `0.0123`，测试照样绿。
        //
        //    第一版就是这么写的（只断言"含点号"），实测它无论代码对错都通过，
        //    属于"看起来在防，实际什么都没防"。
        //
        //    真实的坑：默认 locale 下 `%.4f` 输出 `0,0123`，而这个字符串会被
        //    嵌进用户可见的提示语（"超出单次调用费用上限（上限 1,0000…）"），
        //    用户会看成两个数字，且这个字符串再也无法被解析回来。
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val formatted = BudgetGuard.formatUsd(0.0123)

            assertEquals(
                "德语 locale 下金额仍须用小数点，实际：$formatted",
                "$0.0123",
                formatted,
            )
        } finally {
            // ⚠️ 必须还原 —— locale 是 JVM 级的全局状态，
            //    漏还原会污染同一次运行里的其它测试，
            //    表现为"另一组测试莫名其妙地红"，且顺序相关难以复现。
            java.util.Locale.setDefault(original)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  构造期校验
    // ─────────────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `maxTokens 为 0 时构造失败`() {
        RequestBudget(maxTokens = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxTokens 为负时构造失败`() {
        RequestBudget(maxTokens = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxCostUsd 为负时构造失败`() {
        RequestBudget(maxCostUsd = -0.01)
    }

    @Test
    fun `maxCostUsd 为 0 是合法的`() {
        // 语义："我只想用免费的模型"。0 与 null（不限）是两件事。
        val budget = RequestBudget(maxCostUsd = 0.0)
        assertEquals(0.0, budget.maxCostUsd!!, 0.0)
    }
}
