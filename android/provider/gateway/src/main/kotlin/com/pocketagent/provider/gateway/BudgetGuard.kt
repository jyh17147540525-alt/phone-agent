package com.pocketagent.provider.gateway

import com.pocketagent.modelrouter.ModelConfig

/**
 * 预算熔断（纯逻辑）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它解决的问题
 * ═══════════════════════════════════════════════════════════════
 *
 * BYOK 模式下**花的是用户自己的钱**。一次跑飞的 agent 循环可能在一夜之间
 * 烧掉几十美元，而用户唯一的发现途径是收到账单 —— 那时已经晚了。
 *
 * 所以熔断必须在**发请求之前**、在**客户端**完成，不能依赖 Provider 侧的
 * 配额（用户可能没设，且设了也不告诉我们）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 两条限制的方向完全不同（这是本类最容易写错的地方）
 * ═══════════════════════════════════════════════════════════════
 *
 * | 限制 | 超限后的正确行为 | 为什么 |
 * |---|---|---|
 * | **输入 token** | **拒绝** | 输入已经在手上了。截断输入 = 静默丢上下文，模型会给出错误答案，而且**不报错** |
 * | **输出 token** | **截断**（转成 `maxTokens` 传下去） | 输出是模型生成的，停在这里只是"答短一点"，语义仍然正确 |
 *
 * 把这两个方向搞反的后果：
 * - 输入超限却放行 → 请求被上游拒（400），用户看到的是"模型报错了"
 * - 输出超限却拒绝 → 长答案永远发不出去，而 `maxTokens` 明明有上限可用
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 价格未知怎么办（[RequestBudget.CostFallback]）
 * ═══════════════════════════════════════════════════════════════
 *
 * `ModelConfig.estimatedCost` 在价格未知时返回 **null**（不是 0）。
 * 而"未知"是常见情况：本地 Ollama、自建端点、厂商刚下架又上架的新模型。
 *
 * 此时**不能静默当作 0** —— 那会让熔断对这类模型完全失效，而用户
 * 以为它在保护自己。正确做法是**显式分叉**：
 * - [RequestBudget.CostFallback.Allow] → 放行，但返回
 *   [BudgetVerdict.AllowedWithWarning]，让 UI 能显示"这个模型没标价，
 *   预算熔断对它无效"
 * - [RequestBudget.CostFallback.Deny] → 拒绝，理由说清是"没标价"而不是"太贵"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是无状态的
 * ═══════════════════════════════════════════════════════════════
 *
 * 本类**不累计**任何东西 —— 每次 `check` 只看"这一次调用"。
 * "整个任务已经花了多少"属于 agent 循环的 `AgentBudget`（P5 阶段落地），
 * 由循环自己维护累计值再传进来。
 *
 * 这样切分的理由：累计状态需要生命周期管理（任务开始/结束/中断恢复），
 * 而网关是**无状态**的（同一个实例服务多个任务）。把累计塞进来的后果是
 * "两个并发任务互相吃掉对方的预算"，且**没有任何线索能看出这件事**。
 */
object BudgetGuard {

    /**
     * 检查一次调用的预算。
     *
     * @param request 本次请求的 token 上限（已含调用方意图）
     * @param budget 预算约束；null = 无约束，直接放行
     * @param model 路由选中的模型配置（用于取价格）
     * @param estimatedInputTokens 调用方估算的输入 token 数
     * @return 判定结果。**从不抛异常** —— 超预算是正常业务状态
     */
    fun check(
        request: RequestBudget?,
        budget: RequestBudget? = request,
        model: ModelConfig,
        estimatedInputTokens: Int,
    ): BudgetVerdict {
        if (budget == null) return BudgetVerdict.Allowed

        // ── 1. 输入 token：超了只能拒绝 ────────────────────────────
        if (estimatedInputTokens > budget.maxTokens) {
            return BudgetVerdict.Exceeded(
                GatewayFailure.BudgetExceeded(
                    limitName = "单次请求 token 上限",
                    limitValue = budget.maxTokens.toString(),
                    actualValue = estimatedInputTokens.toString(),
                )
            )
        }

        // ── 2. 费用：只有在"算得出"的时候才检查 ──────────────────
        val maxCost = budget.maxCostUsd
        if (maxCost == null) return BudgetVerdict.Allowed

        val inputCost = model.estimatedCost(
            inputTokens = estimatedInputTokens,
            outputTokens = request?.maxTokens ?: 0,
        )

        if (inputCost == null) {
            // 价格未知 —— 显式分叉，绝不静默当作 0
            return when (budget.costFallback) {
                RequestBudget.CostFallback.Allow -> BudgetVerdict.AllowedWithWarning(
                    // ⚠️ 这条文案要能独立成立：用户可能在任何界面看到它，
                    //    而它必须自己说清"哪个模型"和"为什么熔断没生效"
                    "「${model.effectiveLabel}」没有标注价格，本次调用的费用无法估算，预算上限对它不生效"
                )

                RequestBudget.CostFallback.Deny -> BudgetVerdict.Exceeded(
                    GatewayFailure.BudgetExceeded(
                        limitName = "费用上限",
                        limitValue = "$$maxCost",
                        // ⚠️ 这里刻意**不说**"当前 $0" —— 那会被读成"没花钱，
                        //    为什么拦我"。说清是"算不出来"才是有效的解释。
                        actualValue = "无法估算（该模型未标注价格）",
                    )
                )
            }
        }

        // ── 3. 费用：边界值放行（`>` 而非 `>=`）─────────────────
        //
        // ⚠️ 用户把上限设成 $1.00 时，恰好花 $1.00 的那次必须能过。
        //    用 `>=` 会让"我设了多少就只能花到比它少" —— 而用户没法
        //    从任何界面看出这一点，只会觉得"这个数字怎么总是差一点"。
        if (inputCost > maxCost) {
            return BudgetVerdict.Exceeded(
                GatewayFailure.BudgetExceeded(
                    limitName = "单次调用费用上限",
                    limitValue = formatUsd(maxCost),
                    actualValue = formatUsd(inputCost),
                )
            )
        }

        return BudgetVerdict.Allowed
    }

    /**
     * 把调用方的 `maxTokens` 收敛到预算允许的值。
     *
     * ⚠️ **这是"输出超限要截断"那一条的实现** —— 输入超限走 [check] 拒绝，
     *    输出超限走这里收敛。两个方向必须分开，理由见类注释。
     *
     * @return 实际应该传给 Provider 的 `maxTokens`；null = 不限制
     */
    fun effectiveMaxOutputTokens(
        request: RequestBudget?,
        budget: RequestBudget? = request,
        estimatedInputTokens: Int,
    ): Int? {
        if (budget == null) return request?.maxTokens

        // 预算的 maxTokens 管的是"输入 + 输出"总量，所以输出能用的额度
        // 要把已占的输入扣掉
        val remaining = budget.maxTokens - estimatedInputTokens
        if (remaining <= 0) return null

        val requested = request?.maxTokens
        return when {
            requested == null -> remaining
            // 要的比剩的多 → 收敛到剩余额度（截断，不是拒绝）
            requested > remaining -> remaining
            else -> requested
        }
    }

    /**
     * 美元格式化。
     *
     * ⚠️ **必须显式指定 `Locale.US`** —— 默认 locale 下 `%.4f` 在德语/土耳其语
     *    环境会输出 `0,0123`（逗号作小数点）。那个字符串一旦进了用户可见的
     *    提示语，用户会看成两个数字；而它又会被嵌进我们的提示语里，
     *    所以这里和 `Cost.toString()` 是同一个坑，不能只在那一边防。
     */
    fun formatUsd(usd: Double): String =
        "$" + String.format(java.util.Locale.US, "%.4f", usd)
}
