package com.pocketagent.agent

import com.pocketagent.action.ElementRef

/**
 * **自动定位** —— 把「匹配 → 消歧」串成一条可用流程。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：三段此前互不相连
 * ═══════════════════════════════════════════════════════════════
 *
 * · `TextGrounder`（本模块）—— 算出候选与置信度
 * · `AmbiguityResolver`（本模块）—— 决定选哪个 / 问用户 / 放弃
 * · `GroundResult` / `AmbiguityResolution`（`AgentOrchestrator`）
 *
 * 三者各自有测试，但**没有任何地方把它们串起来**：
 * `AmbiguityResolver` 在生产代码里零引用。
 *
 * 本类补上那条线。之所以放在纯逻辑层而不是 `AgentOrchestrator`：
 * 编排决策本身不需要 Android，那部分应该能被离线钉住。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 一条纪律：**三个出口都要能被上层区分**
 * ═══════════════════════════════════════════════════════════════
 *
 * | 出口 | 上层该做什么 |
 * |---|---|
 * | [Outcome.Found] | 直接执行动作 |
 * | [Outcome.AskUser] | 把 `options` 展示给用户选 |
 * | [Outcome.NeedMoreInfo] | 补采集（截图 / 展开树）再试 |
 * | [Outcome.GiveUp] | 换策略或告知做不到 |
 *
 * ⚠️ 绝不能把 [Outcome.AskUser] 或 [Outcome.NeedMoreInfo] 悄悄降级成
 *    [Outcome.Found] —— 那是"假装成功"，比失败更糟。
 */
object AutoLocator {

    /** 定位结果。 */
    sealed interface Outcome {

        /** 找到了，可以动手。 */
        data class Found(
            val ref: ElementRef,
            val confidence: Float,
            val reason: String,
        ) : Outcome

        /** 多个候选分不清，**问用户**。`options` 是可读标签。 */
        data class AskUser(
            val options: List<String>,
            val reason: String,
        ) : Outcome

        /** 信息不足，需要补采集（通常是补一张截图）。 */
        data class NeedMoreInfo(val reason: String) : Outcome

        /** 确实做不到。 */
        data class GiveUp(val reason: String) : Outcome
    }

    /**
     * 定位。
     *
     * @param target 目标描述（模型给的自然语言）
     * @param nodes 当前屏幕的候选节点
     * @param resolver 消歧器。**由调用方持有** —— 它内部记着"问过几次"，
     *   每次新建会让那个计数失效，于是同一目标会被反复追问。
     */
    fun locate(
        target: String,
        nodes: List<TextGrounder.CandidateNode>,
        resolver: AmbiguityResolver = AmbiguityResolver(),
    ): Outcome {
        // ── ① 匹配 ────────────────────────────────────────────
        val scored = TextGrounder.scoredCandidates(target, nodes)
        if (scored.isEmpty()) {
            return Outcome.GiveUp(
                "屏幕上找不到「${target.take(40)}」（共扫了 ${nodes.size} 个节点）",
            )
        }

        // ── ② 唯一候选 ────────────────────────────────────────
        if (scored.size == 1) {
            val (node, conf) = scored.first()

            // ⚠️ 唯一但不可点 ⇒ 补采集，不能假装成功
            if (!node.clickable) {
                return Outcome.NeedMoreInfo(
                    "找到了「${target.take(40)}」但它是不可点的容器",
                )
            }

            // ⚠️ 置信度太低 ⇒ 交给消歧器判（它会 GiveUp 或按策略处理）
            //    不在这里自己定阈值 —— 那是 resolver 的职责，两边各定一个
            //    阈值迟早会不一致。
            val resolution = resolver.resolve(
                targetDescription = target,
                candidates = listOf(node.toCandidate(conf)),
            )
            return resolution.toOutcome(scoredNodes = mapOf(node.nodeId to node))
        }

        // ── ③ 多个候选 ⇒ 交给消歧器决定问不问 ─────────────────
        return resolver.resolve(
            targetDescription = target,
            candidates = scored.map { (node, conf) -> node.toCandidate(conf) },
        ).toOutcome(scoredNodes = scored.associate { (n, _) -> n.nodeId to n })
    }

    // ─────────────────────────────────────────────────────────────
    //  转换
    // ─────────────────────────────────────────────────────────────

    /**
     * 把候选节点转成消歧器认识的形式。
     *
     * ★ `refKey` 用 `nodeId` —— 消歧器只把它当**不透明字符串**回传，
     *   由本类再翻回 `ElementRef`。这样 `:agentlogic` 的
     *   `AmbiguousCandidate` 不需要认识 `:action` 的类型。
     */
    private fun TextGrounder.CandidateNode.toCandidate(confidence: Float) =
        AmbiguousCandidate(
            label = labelOf(),
            confidence = confidence,
            refKey = nodeId,
        )

    /**
     * 可读标签 —— 直接展示给用户，所以必须**人能看懂**。
     *
     * ⚠️ 优先级是刻意的：文本 > 描述 > viewId 尾段 > nodeId。
     *    `nodeId` 是最后手段（它对人毫无意义，但至少能区分选项）。
     */
    private fun TextGrounder.CandidateNode.labelOf(): String {
        val t = text.trim()
        if (t.isNotEmpty()) return t.take(24)
        val d = contentDescription.trim()
        if (d.isNotEmpty()) return d.take(24)
        val v = viewId?.substringAfterLast('/')?.trim()
        if (!v.isNullOrEmpty()) return v.take(24)
        return nodeId.take(12)
    }

    /**
     * 把消歧决策翻成定位结果。
     *
     * ⚠️ `PickFirst` 只回传 `refKey`（一个不透明字符串），**不带 bounds**。
     *    所以必须回查原始节点 —— 否则点击坐标会是 (0,0,0,0)，
     *    也就是**点在屏幕左上角**。那个错误很难在真机上发现，
     *    因为"点了但没反应"看起来像是元素变了。
     */
    private fun AmbiguityResolution.toOutcome(
        scoredNodes: Map<String, TextGrounder.CandidateNode>,
    ): Outcome = when (this) {
        is AmbiguityResolution.PickFirst -> {
            val node = scoredNodes[chosen.refKey]
            if (node == null) {
                // 理论上不可能：refKey 就是从这些节点来的。
                // 真发生了说明契约被破坏 —— 明确报出来，不要编一个坐标。
                Outcome.GiveUp("消歧器返回了未知的候选（refKey=${chosen.refKey}）")
            } else {
                Outcome.Found(
                    ref = ElementRef.ByNodeId(node.nodeId, node.bounds),
                    confidence = chosen.confidence,
                    reason = reason,
                )
            }
        }
        is AmbiguityResolution.AskUser -> Outcome.AskUser(
            options = options,
            reason = reason,
        )
        is AmbiguityResolution.GiveUp -> Outcome.GiveUp(reason)
    }
}
