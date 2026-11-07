package com.pocketagent.assistant

import com.pocketagent.agentlogic.orchestration.Expectation
import com.pocketagent.agentlogic.orchestration.LimitSignals
import com.pocketagent.agentlogic.orchestration.StepEffect
import com.pocketagent.agentlogic.orchestration.PlannedStep
import com.pocketagent.agentlogic.orchestration.ScreenDigest
import com.pocketagent.agentlogic.orchestration.ScreenDigestJson
import com.pocketagent.agentlogic.orchestration.StepOutcomeReport
import com.pocketagent.agentlogic.orchestration.StepRunnerPort
import com.pocketagent.agentlogic.orchestration.StepStatus
import com.pocketagent.agentlogic.orchestration.ToolCatalog
import com.pocketagent.mcp.ToolOutcome
import com.pocketagent.mcp.ToolRegistry
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * 把 [com.pocketagent.agentlogic.orchestration.RunOrchestrator] 的一步落到真实的 MCP 工具上。
 *
 * ## 为什么注册表是 `() -> ToolRegistry` 而不是 `ToolRegistry`
 *
 * 档位网关（`ToolGate`）是按当前权限状态算的，构造那一刻的快照会过期：
 * 用户在设置里一开无障碍/一给 root，快照里那些工具仍然是"不可用"。
 * 传函数就永远读的是此刻的那一份 —— 与 `agentTools` 是 lazy 是同一个理由。
 *
 * ## 验收为什么在这里做
 *
 * 「动作发出去了」与「事情办成了」是两件事。技能层的工具只回报"派发成功"，
 * 于是**验收只能靠再读一次屏幕**。这一步放在 runner 而不是工具内部，是因为：
 * 只有 runner 知道这一趟的 [Expectation] 要验什么，而工具不该为"可能有人要验"付出成本。
 *
 * ⚠️ 代价是每个带验收的步骤多一次读屏（150–400ms）。这个代价是必须付的：
 *    不验就等于相信模型说的"我做完了"，而那正是这类 agent 最常见的翻车方式。
 */
class McpStepRunner(
    private val registry: () -> ToolRegistry,
    private val catalog: ToolCatalog = ToolCatalog(),
    /** 用来取摘要的工具名。改这里就等于换读屏实现。 */
    private val readTool: String = "screen_read",
) : StepRunnerPort {

    override suspend fun run(step: PlannedStep): StepOutcomeReport {
        val spec = catalog.byId(step.tool)
            ?: return StepOutcomeReport(
                StepStatus.FAILED,
                "计划里的「${step.tool}」不在工具目录里，这一步不执行。",
            )

        val registryNow = registry()
        val tool = registryNow.find(step.tool)
            ?: return StepOutcomeReport(
                StepStatus.FAILED,
                "这一版没有「${step.tool}」这个工具 —— 计划里的能力还没落地，请如实告诉用户。",
            )

        val args = buildJsonObject { step.args.forEach { (key, value) -> put(key, value) } }
        val outcome = withTimeoutOrNull(spec.timeoutMs.toLong()) { tool.call(args) }
            ?: return StepOutcomeReport(
                StepStatus.TIMEOUT,
                "「${step.tool}」超过 ${spec.timeoutMs} 毫秒没有返回。可能是通道卡住，也可能界面没响应。",
            )

        return when (outcome) {
            is ToolOutcome.Refused -> StepOutcomeReport(StepStatus.REFUSED, outcome.reason)
            is ToolOutcome.Failed -> StepOutcomeReport(StepStatus.FAILED, outcome.reason)
            is ToolOutcome.Success -> {
                val digest = digestFor(step, outcome, spec.needsScreen)
                val limit = LimitSignals.match(digest)
                val unmet = step.expect.unmetOn(digest)
                val effect = when {
                    // 限额优先：这时连"验收没过"都不重要了，重试一定没用
                    limit != null -> StepEffect.LIMIT_REACHED
                    digest == null && step.expect != Expectation.None -> StepEffect.TREE_BLIND
                    unmet != null -> StepEffect.NO_EFFECT
                    digest == null && spec.needsScreen -> StepEffect.TREE_BLIND
                    else -> StepEffect.EFFECT
                }
                val detail = when (effect) {
                    StepEffect.LIMIT_REACHED -> limit ?: outcome.text.summarize()
                    StepEffect.NO_EFFECT -> unmet ?: "动作发出去了，但界面没有变化"
                    StepEffect.TREE_BLIND -> "读不到界面，这一步的效果无法核对"
                    StepEffect.EFFECT -> outcome.text.summarize()
                }
                StepOutcomeReport(StepStatus.DONE, detail, digest, effect)
            }
        }
    }

    /**
     * 取这一趟要用的屏幕摘要。
     *
     * ★ 什么时候读：有验收条件时**必须**读；会动屏幕的动作（[needsScreen]）即使没写验收
     *   也要读 —— 否则"点了没反应"和"点成功了"在数据上一模一样，agent 只能猜。
     *   代价是每个屏上动作多 150–400ms；不读的代价是把失败当成功，然后编一份结果出来。
     *
     * ★ 怎么读：这一步本身就是读屏 → 直接用它的结构化输出；其余 → 再读一次。
     */
    private suspend fun digestFor(
        step: PlannedStep,
        success: ToolOutcome.Success,
        needsScreen: Boolean,
    ): ScreenDigest? {
        if (step.expect == Expectation.None && !needsScreen) return null

        success.structured?.takeIf { step.tool == readTool }?.let { return ScreenDigestJson.parse(it) }

        val registryNow = registry()
        val reader = registryNow.find(readTool) ?: run {
            Timber.w("验收要读屏，但运行时没有 %s", readTool)
            return null
        }
        val captured = withTimeoutOrNull(READ_TIMEOUT_MS) { reader.call(buildJsonObject { }) }
        val structured = (captured as? ToolOutcome.Success)?.structured
        if (structured == null) {
            Timber.i("验收没拿到摘要（%s）：这一步会判成未通过", captured?.javaClass?.simpleName)
            return null
        }
        return ScreenDigestJson.parse(structured)
    }

    /** 工具回执可能很长（读屏一次几千字），进报告只留头一段。 */
    private fun String.summarize(): String {
        val flat = trim().replace(Regex("\\s+"), " ")
        return if (flat.length <= MAX_DETAIL_CHARS) flat else flat.take(MAX_DETAIL_CHARS) + "…"
    }

    private companion object {
        const val READ_TIMEOUT_MS = 8_000L
        const val MAX_DETAIL_CHARS = 300
    }
}
