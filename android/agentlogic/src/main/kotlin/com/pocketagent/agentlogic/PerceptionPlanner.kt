package com.pocketagent.agentlogic

/**
 * **感知档位的接线层** —— 把 `PerceptionLadder` 从"有测试没人调用"变成
 * "每一步真的按它决策"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么需要这一层（一个反复出现的问题）
 * ═══════════════════════════════════════════════════════════════
 *
 * `PerceptionLadder` 有完整实现 + 24 条测试，但**零生产引用**。
 * 而界面上那个「感知档位」徽章一直显示写死的 `1`。
 *
 * 这类"装好了但没接线"在本项目出现过至少四次：
 *   · `RelationshipStage`（关系永远停在初识）
 *   · `PaMotion.bouncy` / `staggerDelay`（定义完整，零引用）
 *   · `OutputGuard`（反 OOC 守卫，只有测试用）
 *   · `PerceptionLadder`（本次）
 *
 * ⇒ 共同点：**能力已经写好，测试也绿，但没人调它**。
 *   后果不是崩溃，是"功能看起来存在，实际不起作用"——
 *   本项目的头号 bug 形态。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一层解决什么
 * ═══════════════════════════════════════════════════════════════
 *
 * `PerceptionLadder` 需要 `PerceptionDemand`（本步要不要元素、是不是纯导航…），
 * 而那些信息只有**知道这一步在干什么的人**才有 —— 也就是工具调用层。
 *
 * 本类做两件事：
 *  1. 从「工具名 + 参数」推出 `PerceptionDemand`（[demandFor]）
 *  2. 把决策结果 + 理由打包成界面能直接显示的东西（[StepPerception]）
 *
 * ⚠️ 它**不采集任何东西** —— 采集由调用方按 `tier` 去做。
 *   这一层的唯一职责是"决定要不要采、采到第几档"，那是纯判断。
 */
object PerceptionPlanner {

    /**
     * 工具名 → 感知需求。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 这张表是整个"省成本"策略的实质
     * ═══════════════════════════════════════════════════════════
     *
     * 规划文档的核心结论：**轮次能耗差三个数量级**
     * （30 轮 0.27% vs 8 轮 0.07%）。而每一轮里最贵的不是模型调用，
     * 是**截图**：一次 PNG 编码 + base64 + 上传，几百毫秒 + 几百 KB。
     *
     * ⇒ 对**不需要看屏幕**的动作坚决不截：
     *   · `android_tap` —— 坐标已经给了，点就完了
     *   · `android_swipe` —— 同上
     *   · `android_key` —— 按返回/回桌面不需要知道屏幕
     *   · `type_text` —— 往当前焦点打字
     *
     * ⚠️ 而 `screen_read` 本身就是"要看屏幕"，它当然要采集 ——
     *   但那是**模型主动要求**的，不是我们替它决定的。
     *
     * ⚠️ 这一条最容易写反：把"执行完要确认结果"当成"执行前要截图"。
     *   确认由**下一步**的 `screen_read` 做，不该在这一步预支成本。
     */
    fun demandFor(toolName: String): PerceptionDemand = when (toolName) {
        // ── 零采集：动作本身不需要知道屏幕长什么样 ──────────────
        //
        // ⚠️⚠️ 为什么这里必须写 `isPureNavigation = true` 而不是只写
        //      `needsElement = false`：
        //
        //   `PerceptionLadder` 的第 4 步兜底是「**未声明具体需求**，
        //   按兜底档采集」。只写 `needsElement = false` 时，
        //   这个 demand 既不满足"纯导航"、也不满足"需要元素"、
        //   也不满足"需要视觉" ⇒ 掉进兜底 ⇒ **得到第 3 档（全屏截图）**。
        //
        //   实测：`android_tap` 与 `root_exec` 都因此拿到 tier=3，
        //   而不是本该的 0。也就是说 —— **省成本的目的是反的**。
        //
        //   ⇒ 对"确实不需要感知"的工具，必须**显式声明**
        //      `isPureNavigation = true`。它的字面意思是"这一步
        //      只看导航意图，不看屏幕内容"，正是我们要表达的东西。
        //
        //   ⚠️ 这不是滥用：`isPureNavigation` 的定义是"本步不需要
        //      了解屏幕内容，执行完由下一步确认" —— 点击、滑动、
        //      按键、打字、跑 shell、办公操作全都符合。
        "android_tap", "android_swipe", "android_key", "type_text" ->
            PerceptionDemand(needsElement = false, isPureNavigation = true)

        // ── 纯导航：连"下一步会怎样"都不用看 ────────────────────
        "app_host", "open_app", "go_home", "go_back" ->
            PerceptionDemand(needsElement = false, isPureNavigation = true)

        // ── 读屏：模型明确要求看，按它的意图给档 ─────────────────
        //
        // ⚠️ 注意 `screen_read` **不走** `requiresVisualUnderstanding`：
        //    无障碍树通常就够回答"屏幕上有什么字"，而像素是更贵的档。
        //    见 `PerceptionLadder` 步骤 2 的注释（"不为了保险而多采集"）。
        "screen_read" ->
            PerceptionDemand(needsElement = true)

        // ── 需要视觉理解（图标识别、截图问答）──────────────────
        "screen_describe", "screen_ocr" ->
            PerceptionDemand(needsElement = true, requiresVisualUnderstanding = true)

        // ── 文件/办公/上下文类：完全不碰屏幕 ────────────────────
        "root_exec", "office_run", "office_tools",
        "context_offload", "context_recall", "persona_brief" ->
            PerceptionDemand(needsElement = false, isPureNavigation = true)

        // ── 未知工具：保守给"需要元素"
        //
        // ⚠️ 保守方向是**多采集**（第 1 档），不是少采集。
        //    理由：未知工具如果其实需要屏幕而我们没采，它会因为
        //    "看不到东西"而失败 —— 那是**功能性失败**，比多花一次
        //    截图严重得多。多采只是费一点电。
        else -> PerceptionDemand(needsElement = true)
    }

    /**
     * 这一步的感知决定（给界面显示用）。
     *
     * @param tier 档位（0..4）
     * @param reason **能直接展示给用户**的理由
     * @param degraded 是否因为能力不足而降级（界面要提示开权限）
     */
    data class StepPerception(
        val tier: Int,
        val reason: String,
        val degraded: Boolean,
    ) {
        /** 要不要真的去采集。 */
        val shouldCapture: Boolean get() = tier > 0
    }

    /**
     * 给一个工具调用算出它的感知档位。
     *
     * @param toolName 工具名
     * @param capability 当前设备能力（无障碍/截图/OCR/网络）
     * @param hasCachedElementRef 目标元素是否已有缓存的引用
     */
    fun plan(
        toolName: String,
        capability: PerceptionCapability,
        hasCachedElementRef: Boolean = false,
    ): StepPerception {
        val demand = demandFor(toolName).copy(hasCachedElementRef = hasCachedElementRef)
        val decision = PerceptionLadder(capability).decide(demand)
        return StepPerception(
            tier = decision.tier.level,
            reason = decision.reason,
            degraded = decision.degraded,
        )
    }

    /**
     * 从**工具名 + 上一次的档位**判断该不该升档。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 什么时候需要升档
     * ═══════════════════════════════════════════════════════════
     *
     * 低档失败了（比如无障碍树里找不到那个元素），下一步应当升档重试。
     * 而不是"再用同样的档位试一次" —— 那必然再失败。
     *
     * @param previousTier 上一步用的档位
     * @param failed 上一步是否失败
     * @return 建议的档位；不变时返回 [previousTier]
     *
     * ⚠️ 升到顶就不再升（第 4 档）。否则会一直往上加，
     *   而 `PerceptionTier` 只有 0..4，超界会崩。
     */
    fun escalateIfFailed(previousTier: Int, failed: Boolean): Int {
        if (!failed) return previousTier
        return (previousTier + 1).coerceAtMost(PerceptionTier.entries.maxOf { it.level })
    }
}
