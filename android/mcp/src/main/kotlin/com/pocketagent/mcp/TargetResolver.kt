package com.pocketagent.mcp

/**
 * `android_tap` 的目标规格 —— 与 `screen_read` 结构化输出的字段**逐一对齐**。
 *
 * - [ByNodeId]：模型从 screen_read 输出里**原样抄** `nodeId` 与 `bounds`。
 *   bounds 是必需项 —— 它是跨快照的位置校验（见 [ResolvedNodeRef] 的注释）。
 * - [ByText]：语义定位。`exact=false` 时按"包含"匹配（对模型抄写更宽容）。
 * - [ByCoord]：坐标兜底。坐标处**没有**可识别节点时是"盲点"（见 [ResolvedTarget]）。
 */
sealed interface TapTargetSpec {

    data class ByNodeId(val nodeId: String, val bounds: ScreenRect) : TapTargetSpec

    data class ByText(val text: String, val exact: Boolean) : TapTargetSpec

    data class ByCoord(val x: Int, val y: Int) : TapTargetSpec
}

sealed interface ResolveOutcome {

    data class Ok(val target: ResolvedTarget) : ResolveOutcome

    /** 解析失败。`reason` 必须给出"下一步"（重新 screen_read / 换定位方式）。 */
    data class Failed(val reason: String) : ResolveOutcome
}

/**
 * 解析结果。
 *
 * ⚠️ [clickNode] 与 [blindPoint] **恰好有一个非空**：
 * - [clickNode] 非空 = 找到了要点的元素（可能已从语义节点上浮到可点击祖先，
 *   `note` 里写着过程）；
 * - [blindPoint] 非空 = 坐标处没有任何可识别节点，"盲点"点击 ——
 *   只有**坐标目标**允许走到这里，且它只过了页面级安全关卡（详见 `TapTool`）。
 *
 * [candidates] 是参与"目标描述"的全部节点（语义节点 + 点击节点，按 nodeId 去重）——
 * 安全判定用它拼 targetDescription（危险动作关键词匹配），**不额外携带任何内容**。
 */
data class ResolvedTarget(
    val clickNode: UiNodeDto?,
    val blindPoint: ScreenPoint?,
    val candidates: List<UiNodeDto>,
)

/**
 * 目标解析器 —— 纯函数，全部离线可测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 解析发生在**本工具自己的采集快照**上，而且它同时是安全判定的输入
 * ═══════════════════════════════════════════════════════════════
 *
 * 顺序是：**先解析 → 再判定 → 才派发**。解析出的节点（语义节点 + 可点击祖先）
 * 就是要送去安全判定的"目标"，也是最终交给执行侧的引用 ——
 * 三处引用的是**同一批数据**，不存在"判定时看的是一个节点、点击时点的是另一个"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 可点击祖先上浮：为什么必须做
 * ═══════════════════════════════════════════════════════════════
 *
 * 无障碍树里"有文本的节点"经常**不是**"可点击的节点"（按钮的可点击性
 * 在外层容器上，文本只是它的孩子）。物理点击是发到坐标上的 ——
 * 直接点文本节点的中心多数时候也能命中，但当文本与可点击容器的中心
 * 明显不同（角标、多行布局）时会点空。
 * 所以：能上浮就上浮到**最近的可见可点击祖先**，不能则照原节点点（如实标注）。
 */
object TargetResolver {

    fun resolve(spec: TapTargetSpec, capture: ScreenCaptureDto): ResolveOutcome = when (spec) {
        is TapTargetSpec.ByCoord -> resolveCoord(spec, capture)
        is TapTargetSpec.ByNodeId -> resolveNodeId(spec, capture)
        is TapTargetSpec.ByText -> resolveText(spec, capture)
    }

    // ── 坐标 ────────────────────────────────────────────────────

    private fun resolveCoord(spec: TapTargetSpec.ByCoord, capture: ScreenCaptureDto): ResolveOutcome {
        if (spec.x < 0 || spec.x >= capture.width || spec.y < 0 || spec.y >= capture.height) {
            return ResolveOutcome.Failed(
                "坐标 (${spec.x}, ${spec.y}) 超出屏幕范围（当前屏幕 ${capture.width}×${capture.height}）。" +
                    "请从 screen_read 输出里的坐标重新选一个。",
            )
        }

        // 包含该点的节点里选**面积最小**的 —— 最具体的那个。
        val smallest = capture.nodes
            .filter { it.visible && isSane(it.bounds) && contains(it.bounds, spec.x, spec.y) }
            .minByOrNull { area(it.bounds) }

        if (smallest == null) {
            // 盲点：坐标处没有任何可识别节点。允许，但如实标注（详见 ResolvedTarget）。
            return ResolveOutcome.Ok(
                ResolvedTarget(
                    clickNode = null,
                    blindPoint = ScreenPoint(spec.x, spec.y),
                    candidates = emptyList(),
                ),
            )
        }
        return finish(smallest, capture)
    }

    // ── 节点 id（带跨快照位置校验）──────────────────────────────

    private fun resolveNodeId(spec: TapTargetSpec.ByNodeId, capture: ScreenCaptureDto): ResolveOutcome {
        val byId = capture.nodes.filter { it.nodeId == spec.nodeId }
        if (byId.isEmpty()) {
            return ResolveOutcome.Failed(
                "节点「${spec.nodeId}」在当前屏幕上不存在 —— 界面可能已经变了。" +
                    "请重新 screen_read，再用新的 nodeId 重试。",
            )
        }

        // ⚠️ 位置校验用**精确相等**：这是点击，宁可错报"界面已变化"，不可点错目标。
        val matched = byId.firstOrNull { sameRect(it.bounds, spec.bounds) }
            ?: return ResolveOutcome.Failed(
                "节点「${spec.nodeId}」的位置与上次读取时不一致（界面可能已经变了）。" +
                    "请重新 screen_read，再用新的 nodeId 与 bounds 重试。",
            )

        if (!matched.visible) {
            return ResolveOutcome.Failed(
                "目标节点「${spec.nodeId}」当前不可见。请重新 screen_read 确认界面状态。",
            )
        }
        return finish(matched, capture)
    }

    // ── 文本 ────────────────────────────────────────────────────

    private fun resolveText(spec: TapTargetSpec.ByText, capture: ScreenCaptureDto): ResolveOutcome {
        val query = spec.text.trim()
        val matches = capture.nodes.filter { it.visible && matchesText(it, query, spec.exact) }
        if (matches.isEmpty()) {
            return ResolveOutcome.Failed(
                "当前屏幕上找不到「$query」。界面可能已经变了 —— 请重新 screen_read 后再试，" +
                    "或用 node_id / 坐标定位。",
            )
        }

        val clickable = matches.filter { it.clickable }
        val semantic = when {
            clickable.size == 1 -> clickable[0]

            clickable.size > 1 -> return ResolveOutcome.Failed(
                "找到 ${clickable.size} 个可点击的「$query」，无法确定点哪个。" +
                    "请改用 node_id（从 screen_read 输出里抄）或坐标定位。",
            )

            // 一个匹配但不是可点击节点：照它点（物理点击仍可能命中），如实标注。
            matches.size == 1 -> matches[0]

            else -> return ResolveOutcome.Failed(
                "找到 ${matches.size} 个「$query」，无法确定点哪个。" +
                    "请改用 node_id（从 screen_read 输出里抄）或坐标定位。",
            )
        }
        return finish(semantic, capture)
    }

    // ── 公共收尾：上浮 + 可用性检查 ─────────────────────────────

    private fun finish(semantic: UiNodeDto, capture: ScreenCaptureDto): ResolveOutcome {
        val click = ascendToClickable(semantic, capture.nodes)
        if (!click.enabled) {
            return ResolveOutcome.Failed(
                "目标当前处于不可用状态（灰置 / 禁用），点了也不会生效。" +
                    "请重新 screen_read 确认界面状态，或换一个目标。",
            )
        }
        val candidates = listOf(semantic, click).distinctBy { it.nodeId }
        return ResolveOutcome.Ok(
            ResolvedTarget(
                clickNode = click,
                blindPoint = null,
                candidates = candidates,
            ),
        )
    }

    /**
     * 从 [node] 上浮到最近的"可见、可点击、可用"的祖先。
     *
     * 判定"是祖先"用**中心点包含**（比整条 bounds 包含稳健：布局里
     * 子元素超出父边界一两个像素是常态）。多个容器时取 **depth 最大**的（最近的）。
     */
    private fun ascendToClickable(node: UiNodeDto, nodes: List<UiNodeDto>): UiNodeDto {
        if (node.clickable) return node

        val cx = node.bounds.centerX
        val cy = node.bounds.centerY
        return nodes
            .filter {
                it.nodeId != node.nodeId &&
                    it.clickable && it.visible && it.enabled &&
                    it.depth < node.depth &&
                    isSane(it.bounds) && contains(it.bounds, cx, cy)
            }
            .maxWithOrNull(compareBy({ it.depth }, { -area(it.bounds) }))
            ?: node
    }

    // ── 小工具（纯）─────────────────────────────────────────────

    private fun matchesText(node: UiNodeDto, query: String, exact: Boolean): Boolean {
        val text = node.text?.trim()
        val desc = node.contentDescription?.trim()
        return if (exact) {
            text == query || desc == query
        } else {
            (text?.contains(query) == true) || (desc?.contains(query) == true)
        }
    }

    /** 几何合法的矩形才参与解析（无障碍坐标是外部数据，畸形值不许炸调用）。 */
    private fun isSane(rect: ScreenRect): Boolean =
        rect.right >= rect.left && rect.bottom >= rect.top

    private fun contains(rect: ScreenRect, x: Int, y: Int): Boolean =
        x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom

    private fun sameRect(a: ScreenRect, b: ScreenRect): Boolean =
        a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom

    private fun area(rect: ScreenRect): Long =
        (rect.right - rect.left).toLong() * (rect.bottom - rect.top).toLong()
}
