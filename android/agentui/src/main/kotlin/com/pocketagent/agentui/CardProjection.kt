package com.pocketagent.agentui

/**
 * 把一次工具调用**投影**成一张卡。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 投影 ≠ 转发
 * ═══════════════════════════════════════════════════════════════
 *
 * MCP 工具的返回里有两份东西：
 *  · `text` —— 给**模型**看的（已经进过安全关卡、已脱敏）；
 *  · `structuredContent` —— 给**程序**看的（`dispatched` / `channel` / `latencyMs` / `blind`）。
 *
 * 卡片必须从**两份一起**取：只用 text，卡片就退化成一段话；
 * 只用 structured，模型看到的那句"请用 screen_read 复核"就丢了。
 *
 * ⚠️ 反过来，卡片的内容**绝不回灌上下文** —— 那是 token 账单的来源。
 *    这条是 `:agentui` 存在的理由，破坏它等于把省下的钱又烧回去。
 */
object CardProjection {

    /**
     * 工具返回的形状。
     *
     * ★ 刻意用简单类型而不是依赖 `:mcp` 的 `ToolOutcome`：
     *   本模块必须零 Android、零业务依赖，才能进离线验证器。
     *   接线方（`:app`）负责把 `ToolOutcome` 摊成这个结构。
     */
    data class ToolResult(
        val tool: String,
        /** 给模型看的那段文本（会进上下文）。 */
        val text: String,
        /** 是否 `isError`。 */
        val isError: Boolean,
        /** 三态之一，见 [ToolGate]。 */
        val gate: ToolGate = ToolGate.OK,
        val channel: String? = null,
        val latencyMs: Long? = null,
        /** 盲点：坐标处没有可识别元素，无法确认点到了什么。 */
        val blind: Boolean = false,
        /** 实际发出的动作摘要（比 text 短，专为一行显示）。 */
        val summary: String,
    )

    /** 结果的门类 —— 与 [Gate] 一一对应，但**不含** `ToolGate.OK`。 */
    enum class ToolGate { OK, SAFETY, PERMISSION, EXECUTION }

    /**
     * 投影成事件。
     *
     * @return 一个 `ToolStarted` + 一个 `ToolSucceeded` / `ToolFailed`。
     *         ★ 返回两个事件而不是一个：卡片必须先以 RUNNING 出现，
     *         否则用户看不到"正在做"的那一瞬间，会以为它是瞬间跳出来的。
     */
    fun project(
        id: CardId,
        startedAt: Long,
        finishedAt: Long,
        result: ToolResult,
        evidence: List<Evidence> = emptyList(),
    ): List<CardEvent> {
        require(finishedAt >= startedAt) { "结束时间不能早于开始时间" }
        val started = CardEvent.ToolStarted(
            at = startedAt,
            id = id,
            tool = result.tool,
            summary = result.summary,
            channel = result.channel,
            evidence = evidence,
        )
        val finished = if (result.gate == ToolGate.OK && !result.isError) {
            CardEvent.ToolSucceeded(
                at = finishedAt,
                id = id,
                detail = result.text,
                latencyMs = result.latencyMs,
                uncertainty = if (result.blind) BLIND_NOTE else null,
            )
        } else {
            val gate = when (result.gate) {
                ToolGate.OK, ToolGate.EXECUTION -> Gate.EXECUTION
                ToolGate.SAFETY -> Gate.SAFETY
                ToolGate.PERMISSION -> Gate.PERMISSION
            }
            CardEvent.ToolFailed(
                at = finishedAt,
                id = id,
                gate = gate,
                // ★ 直接透传工具自己那句面向用户的文案，不再加工 ——
                //   工具层已经写好了"下一步做什么"，二次改写只会丢信息。
                reason = result.text.ifBlank { FailurePlan.headline(gate) },
                exits = FailurePlan.exitsFor(gate),
            )
        }
        return listOf(started, finished)
    }

    /**
     * 盲点点击的固定文案。
     *
     * ★ 这句话是**硬约束**（评审补的）：盲点时我们不知道点到了什么，
     *   就必须说出来，并要求模型如实转达用户。写成常量而不是各处手写，
     *   是为了让它无法被"顺手简化"掉。
     */
    const val BLIND_NOTE: String =
        "本次是「盲点」点击：坐标处没有可识别元素，无法确认点到了什么 —— " +
            "请务必如实告诉用户这是一次不确定的操作，并请他确认界面现状。"

    /**
     * 一次读屏的摘要（Obs 卡）。
     *
     * ★ 摘要只放**用户认得出的东西**：应用名 + 页面标志 + 节点数。
     *   不要把整棵树塞进摘要 —— 那是详情，不是摘要。
     */
    fun observeSummary(app: String?, landmarks: List<String>, nodeCount: Int): String {
        val head = app?.takeIf { it.isNotBlank() } ?: "未知界面"
        val marks = landmarks.filter { it.isNotBlank() }.take(3)
        return if (marks.isEmpty()) {
            "$head（$nodeCount 个节点）"
        } else {
            "$head：${marks.joinToString("、")}（$nodeCount 个节点）"
        }
    }
}
