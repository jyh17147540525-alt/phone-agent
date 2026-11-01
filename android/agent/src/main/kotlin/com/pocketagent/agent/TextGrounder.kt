package com.pocketagent.agent

import com.pocketagent.action.Bounds
import com.pocketagent.action.ElementRef

/**
 * **文本定位器** —— `Grounder` 的核心匹配算法。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：`Grounder` 此前只有契约，没有实现
 * ═══════════════════════════════════════════════════════════════
 *
 * `AgentOrchestrator` 声明了 `Grounder.locate(...)` 与 `Verifier.verify(...)`，
 * 但全代码库**没有任何类实现它们**。于是：
 *
 *  · `GroundResult.Ambiguous(candidates)` 在运行时永远不会产生
 *  · `AmbiguityResolver`（唯一该消费它的东西）因此一直零引用
 *  · 整条「感知 → 定位 → 校验 → 消歧」链路只是纸面设计
 *
 * 本类补上这条链路里**可以离线验证的那一半**：给定一批候选节点，
 * 算出 `GroundResult`。Android 侧只需把节点树映射成 [CandidateNode]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 置信度的含义（要能直接喂给 `AmbiguityResolver`）
 * ═══════════════════════════════════════════════════════════════
 *
 * `AmbiguityResolver` 用两个阈值：
 *  · `LOW_CONFIDENCE_FLOOR = 0.4` —— 唯一候选低于它就不值得照做
 *  · `minConfidenceGap = 0.2` —— 前两名差距够大就自动选，否则问用户
 *
 * ⇒ 本类的置信度必须落在同一把尺子上：
 *
 * | 命中方式 | 置信度 | 理由 |
 * |---|---|---|
 * | 文本**完全相同** 且可点 | 0.95 | 最可靠 |
 * | 文本完全相同 | 0.90 | 可靠，但可能只是标签 |
 * | 描述（contentDescription）完全相同 | 0.85 | 语义明确 |
 * | viewId 完全相同 | 0.80 | 稳定但用户看不见 |
 * | 文本**包含**目标 | 0.60 | 容易误命中 |
 * | 描述包含目标 | 0.55 | 更松 |
 *
 * ⚠️ "包含"类刻意给低分：列表里十条都含"确定"是常态。
 *    给高了会让 `AmbiguityResolver` 的 0.2 差距判据失效，
 *    于是它自动选一个错的。
 */
object TextGrounder {

    /**
     * 一个候选节点 —— **纯数据**，由 Android 侧从节点树映射而来。
     *
     * ⚠️ 刻意不直接吃 `:perception.UiNode`：那个类型带 Android 依赖
     *    （见 `SnapshotView` 的注释）。纯数据让本类能离线测。
     */
    data class CandidateNode(
        /** 稳定节点 ID。跨快照不保证稳定，所以还要 [bounds] 二次校验 */
        val nodeId: String,
        /** 可见文本 */
        val text: String = "",
        /** 无障碍描述 */
        val contentDescription: String = "",
        /** `viewIdResourceName`，比 nodeId 稳定 */
        val viewId: String? = null,
        val bounds: Bounds,
        /** 能不能点。不能点的元素点了没用 */
        val clickable: Boolean = true,
    )

    // ─────────────────────────────────────────────────────────────
    //  置信度表（对外公开，便于测试与审计）
    // ─────────────────────────────────────────────────────────────

    const val EXACT_TEXT_CLICKABLE = 0.95f
    const val EXACT_TEXT = 0.90f
    const val EXACT_DESCRIPTION = 0.85f
    const val EXACT_VIEW_ID = 0.80f
    const val CONTAINS_TEXT = 0.60f
    const val CONTAINS_DESCRIPTION = 0.55f

    /**
     * 低于这个分数的候选**不进候选列表**。
     *
     * ★ 与 `AmbiguityResolver.LOW_CONFIDENCE_FLOOR` 分开：
     *   那个是"唯一候选值不值得照做"，这个是"算不算命中"。
     *   两者的语义不同，数值碰巧接近而已 —— 所以不共用常量。
     */
    const val MIN_MATCH_CONFIDENCE = 0.5f

    /**
     * 定位。
     *
     * @param targetDescription 目标描述（模型给的自然语言，如"搜索按钮"）
     * @param nodes 当前屏幕的候选节点
     * @return [GroundResult] 的一个分支
     */
    fun ground(
        targetDescription: String,
        nodes: List<CandidateNode>,
    ): GroundResult {
        val target = targetDescription.trim()
        if (target.isEmpty()) {
            return GroundResult.NotFound("目标描述为空")
        }

        val scored = nodes
            .mapNotNull { node ->
                val c = confidenceOf(target, node)
                if (c >= MIN_MATCH_CONFIDENCE) node to c else null
            }
            .sortedByDescending { it.second }

        if (scored.isEmpty()) {
            return GroundResult.NotFound(
                "没有匹配「$target」的元素（共扫了 ${nodes.size} 个节点）",
            )
        }

        val (bestNode, bestConfidence) = scored.first()

        // ══════════════════════════════════════════════════════════
        //  ★ 唯一的最高分，但元素不可点 ⇒ 要求更多信息
        // ══════════════════════════════════════════════════════════
        //
        // ⚠️ 不能直接返回 Found —— 点一个不可点的元素什么都不会发生，
        //    而模型会以为点成功了。
        if (!bestNode.clickable && scored.size == 1) {
            return GroundResult.NeedMoreInfo(
                "找到了「$target」但它是不可点的容器，需要找它内部的可点元素",
            )
        }

        // 唯一候选
        if (scored.size == 1) {
            return found(bestNode, bestConfidence)
        }

        // 多个候选：让调用方用 `AmbiguityResolver` 决定问不问
        //
        // ★ 本类**不替它做决定** —— 那需要"问过几次"的状态，
        //   而那是 resolver 的职责。这里只如实报出候选。
        return GroundResult.Ambiguous(
            candidates = scored.map { (node, _) ->
                // ⚠️ 候选只带引用，不带分数：`GroundResult.Ambiguous`
                //    的签名就是 `List<ElementRef>`，改签名会动到
                //    `AmbiguityResolver` 的对接方式。
                ElementRef.ByNodeId(node.nodeId, node.bounds)
            },
        )
    }

    /** 把候选列表与置信度一起交出去 —— 给需要分数的调用方。 */
    fun scoredCandidates(
        targetDescription: String,
        nodes: List<CandidateNode>,
    ): List<Pair<CandidateNode, Float>> {
        val target = targetDescription.trim()
        if (target.isEmpty()) return emptyList()
        return nodes
            .mapNotNull { node ->
                val c = confidenceOf(target, node)
                if (c >= MIN_MATCH_CONFIDENCE) node to c else null
            }
            .sortedByDescending { it.second }
    }

    // ─────────────────────────────────────────────────────────────
    //  打分
    // ─────────────────────────────────────────────────────────────

    /**
     * 一个节点的置信度。取**所有命中方式里最高的那个**。
     *
     * ⚠️ 不用"累加"：文本和描述都含目标不一定更可信 ——
     *    一个按钮同时有 `text="搜索"` 和 `contentDescription="搜索"`
     *    是常态，累加会让它虚高到压过真正的精确匹配。
     */
    private fun confidenceOf(target: String, node: CandidateNode): Float {
        var best = 0f

        if (node.text.isNotBlank()) {
            if (node.text.trim().equals(target, ignoreCase = true)) {
                best = maxOf(best, if (node.clickable) EXACT_TEXT_CLICKABLE else EXACT_TEXT)
            } else if (node.text.contains(target, ignoreCase = true)) {
                best = maxOf(best, CONTAINS_TEXT)
            }
        }

        if (node.contentDescription.isNotBlank()) {
            if (node.contentDescription.trim().equals(target, ignoreCase = true)) {
                best = maxOf(best, EXACT_DESCRIPTION)
            } else if (node.contentDescription.contains(target, ignoreCase = true)) {
                best = maxOf(best, CONTAINS_DESCRIPTION)
            }
        }

        val vid = node.viewId
        if (!vid.isNullOrBlank()) {
            // viewId 通常长这样：`com.foo:id/search`
            // ⇒ 既要支持全名，也要支持 `:id/` 之后那一段
            val short = vid.substringAfterLast('/')
            if (vid.equals(target, ignoreCase = true) ||
                short.equals(target, ignoreCase = true)
            ) {
                best = maxOf(best, EXACT_VIEW_ID)
            }
        }

        return best
    }

    private fun found(
        node: CandidateNode,
        confidence: Float,
    ): GroundResult.Found = GroundResult.Found(
        ref = ElementRef.ByNodeId(node.nodeId, node.bounds),
        confidence = confidence,
        fromCache = false,
    )
}
