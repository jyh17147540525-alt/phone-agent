package com.pocketagent.agentui

import kotlin.math.abs

/**
 * **吸附卡 + 画线** —— 拖拽卡片时吸附到参考线，并用线指出"吸到了什么"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"吸附"必须配"画线"
 * ═══════════════════════════════════════════════════════════════
 *
 * 只做吸附、不画线的效果是：卡片自己跳了一下，用户不知道**为什么**跳。
 * 那感觉像卡顿，不像对齐。
 *
 * ⇒ 吸附发生时画一条参考线（从被吸附的边延伸到同排的其他卡），
 *   用户立刻明白"我和它对上了"。这是设计工具里通行的手法，
 *   移到手机列表上同样成立。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三个参数决定它是"顺手"还是"较劲"
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **吸附阈值**（[SNAP_THRESHOLD]）。太小 ⇒ 几乎吸不上，用户觉得没这功能；
 *    太大 ⇒ 想放到 1dp 外都放不下，用户觉得被拽着走。
 *    **8dp** 是手指精度与意图之间的平衡点。
 *
 * 2. **吸附的是"边"不是"中心"**。卡片对齐看的是左/右/上/下边，
 *    中心对齐只在居中排版时有意义。手机列表里几乎总是边对齐。
 *
 * 3. **要有"脱离"的迟滞**。吸住之后手指往回拉 8dp 才脱开 ——
 *    否则在阈值边缘会反复吸/脱，抖成一片。
 *    见 [HYSTERESIS]。
 */
object SnapGuide {

    /** 吸附阈值（dp）。 */
    const val SNAP_THRESHOLD: Float = 8f

    /**
     * 脱离的迟滞（dp）。
     *
     * ⚠️ 必须 **大于** 0：等于 0 时在阈值边界会反复吸脱。
     * 取 [SNAP_THRESHOLD] 的 1.5 倍 —— 吸上之后要明显拉远才脱开，
     * 这样"吸住"的手感是稳的。
     */
    const val HYSTERESIS: Float = SNAP_THRESHOLD * 1.5f

    /** 吸附的方向。 */
    enum class Edge {
        /** 左边缘对齐 */
        LEFT,

        /** 右边缘对齐 */
        RIGHT,

        /** 上边缘对齐 */
        TOP,

        /** 下边缘对齐 */
        BOTTOM,
    }

    /**
     * 一条可吸附的参考线。
     *
     * @param position 线的位置（dp，相对内容区原点）
     * @param edge 它约束的是卡片的哪条边
     * @param fromPeer 提供这条线的邻居卡（用于画线的另一端）
     */
    data class GuideLine(
        val position: Float,
        val edge: Edge,
        val fromPeer: String? = null,
    )

    /**
     * 一次吸附的结果。
     *
     * @param snapped 吸附后的位置（没吸上就返回原值）
     * @param guide 吸到了哪条线；null = 没吸上
     * @param offset 修正量（snapped - raw）。UI 用它做动画，而不是直接跳
     */
    data class Result(
        val snapped: Float,
        val guide: GuideLine?,
        val offset: Float,
    ) {
        val isSnapped: Boolean get() = guide != null
    }

    /**
     * 求吸附。
     *
     * @param raw 手指算出来的原始位置（**卡片的边**，不是中心）
     * @param lines 候选参考线（通常来自同排其他卡）
     * @param currentlySnappedTo 当前已吸住的线（用于迟滞判断）；null = 没吸住
     * @param threshold 吸附阈值。默认 [SNAP_THRESHOLD]
     *
     * ★ 迟滞的具体做法：
     *   · 已经吸在线上 ⇒ 要求 |raw - 线| 超过 [HYSTERESIS] 才脱开
     *   · 没吸住 ⇒ 要求 |raw - 线| 在 [threshold] 内才吸上
     *   两个阈值不同，就形成了"进去难、出来也难"的稳定带。
     */
    fun resolve(
        raw: Float,
        lines: List<GuideLine>,
        currentlySnappedTo: GuideLine? = null,
        threshold: Float = SNAP_THRESHOLD,
    ): Result {
        // ① 已经吸住的：先看要不要脱开（迟滞）
        if (currentlySnappedTo != null) {
            val dist = abs(raw - currentlySnappedTo.position)
            if (dist <= HYSTERESIS) {
                // 仍在保持带内 ⇒ 维持吸附（位置压回线上）
                return Result(
                    snapped = currentlySnappedTo.position,
                    guide = currentlySnappedTo,
                    offset = currentlySnappedTo.position - raw,
                )
            }
            // 超出迟滞带 ⇒ 脱开，往下走正常流程
        }

        // ② 没吸住：找最近的、在阈值内的线
        val best = lines
            .map { it to abs(raw - it.position) }
            .filter { (_, d) -> d <= threshold }
            .minByOrNull { (_, d) -> d }

        return if (best == null) {
            Result(snapped = raw, guide = null, offset = 0f)
        } else {
            val (line, _) = best
            Result(snapped = line.position, guide = line, offset = line.position - raw)
        }
    }

    /**
     * 从一组邻居卡生成参考线。
     *
     * @param peers 邻居：id → (left, right, top, bottom)
     *
     * ★ 只对**同一条边**生成线：左对左、右对右。
     *   左对右也能"对齐"（两张卡并排贴住），但那是**间距**不是对齐，
     *   把它当吸附线会让拖拽变得莫名其妙地粘。
     */
    fun linesFrom(
        peers: Map<String, Edges>,
    ): List<GuideLine> = buildList {
        for ((id, e) in peers) {
            add(GuideLine(e.left, Edge.LEFT, id))
            add(GuideLine(e.right, Edge.RIGHT, id))
            add(GuideLine(e.top, Edge.TOP, id))
            add(GuideLine(e.bottom, Edge.BOTTOM, id))
        }
    }

    /** 一张卡的四个边（dp）。 */
    data class Edges(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * 画线时要连的两端。
     *
     * @param from 被拖动卡的边上的点
     * @param to 邻卡对应边的点
     *
     * ⚠️ 返回 null 表示**不画线**：没吸上、或线来自自己。
     *   给自己画一条线是纯粹视觉噪音。
     */
    data class Seg(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    fun segment(
        result: Result,
        dragged: Edges,
        peers: Map<String, Edges>,
    ): Seg? {
        val guide = result.guide ?: return null
        val peerId = guide.fromPeer ?: return null
        val peer = peers[peerId] ?: return null

        return when (guide.edge) {
            // 竖直的线：在两张卡的上沿之间画
            Edge.LEFT, Edge.RIGHT ->
                Seg(guide.position, dragged.top, guide.position, peer.top)
            // 水平的线：在两张卡的左沿之间画
            Edge.TOP, Edge.BOTTOM ->
                Seg(dragged.left, guide.position, peer.left, guide.position)
        }
    }
}
