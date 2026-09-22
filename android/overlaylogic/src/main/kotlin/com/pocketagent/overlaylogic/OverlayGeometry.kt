package com.pocketagent.overlaylogic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 简单矩形（避开 `android.graphics.Rect`，保持纯逻辑可测） */
data class Bounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    init {
        require(right >= left) { "right($right) 不能小于 left($left)" }
        require(bottom >= top) { "bottom($bottom) 不能小于 top($top)" }
    }

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    /** 与另一矩形是否有重叠（用于"面板是否遮挡了重要区域"的判断） */
    fun overlaps(other: Bounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/** 屏幕边缘 */
enum class ScreenEdge { LEFT, RIGHT }

/**
 * 悬浮球的位置计算。
 *
 * 拆成纯函数的原因：贴边吸附看起来简单，实际是**边界条件的重灾区** ——
 * 不同分辨率、横竖屏切换、状态栏/导航栏内边距、多窗口模式。
 * 这些用几何函数可以完全离线测，不必等真机。
 *
 * 坐标约定：屏幕左上角为原点，单位与 Android 的 px 一致（由调用方换算 dp）。
 */
object OverlayGeometry {

    /**
     * 把悬浮球约束在屏幕可见区域内。
     *
     * ⚠️ **不允许球完全移出屏幕** —— 这关系到 Android 15 的 FGS 启动豁免
     * （要求"当前存在可见 overlay 窗口"）。即使参数把球推到屏幕外，
     * 这里也会把它拉回至少露出一部分。
     *
     * @param centerX 期望的中心 X
     * @param centerY 期望的中心 Y
     * @param ballDiameter 球的直径
     * @param screen 屏幕可用区域
     * @param visibleMargin 最少要露出多少 px（默认取球直径的 1/3，保证可抓取）
     */
    fun clampToScreen(
        centerX: Int,
        centerY: Int,
        ballDiameter: Int,
        screen: Bounds,
        visibleMargin: Int = max(1, ballDiameter / 3),
    ): Bounds {
        val radius = ballDiameter / 2
        val margin = min(visibleMargin, ballDiameter)

        // X 方向：至少露出 margin，所以中心不能偏出超过 (半径 - margin)
        val maxOffsetX = max(0, radius - margin)
        val minCenterX = screen.left - maxOffsetX
        val maxCenterX = screen.right + maxOffsetX

        // Y 方向同理，但要受状态栏/导航栏影响 —— 由调用方传入的 screen 已扣除内边距
        val maxOffsetY = max(0, radius - margin)
        val minCenterY = screen.top - maxOffsetY
        val maxCenterY = screen.bottom + maxOffsetY

        val cx = centerX.coerceIn(minCenterX, maxCenterX)
        val cy = centerY.coerceIn(minCenterY, maxCenterY)

        return Bounds(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    /**
     * 判断是否应吸附到边缘。
     *
     * 判据：球中心距离左右边缘的**较小值**小于阈值。
     * 用中心而非球边缘的原因：用户拖动的直觉是"把球推到边上"，
     * 球的边缘早早碰到屏幕边但中心还远 —— 按边缘判会过早吸附。
     */
    fun shouldSnapToEdge(
        ballBounds: Bounds,
        screen: Bounds,
        thresholdPx: Int,
    ): Boolean {
        val distanceToLeft = ballBounds.centerX - screen.left
        val distanceToRight = screen.right - ballBounds.centerX
        return min(distanceToLeft, distanceToRight) <= thresholdPx
    }

    /** 判断应吸附到哪一侧（距离更近的一侧；等距时偏向右侧，符合右手持机习惯） */
    fun nearestEdge(ballBounds: Bounds, screen: Bounds): ScreenEdge {
        val distanceToLeft = abs(ballBounds.centerX - screen.left)
        val distanceToRight = abs(screen.right - ballBounds.centerX)
        return if (distanceToLeft < distanceToRight) ScreenEdge.LEFT else ScreenEdge.RIGHT
    }

    /**
     * 计算吸附后的位置。
     *
     * ⚠️ **关键设计：吸附后仍保留一条细线在屏幕内，而不是完全移出。**
     * 完全移出会丢掉"可见 overlay"的资格，进而影响后台启动前台服务。
     * 这里把球的中心放在屏幕边缘**内侧**一个几乎贴边的位置，
     * 让视觉上是"一条线"，但系统仍认为窗口可见。
     *
     * @param edgeLineWidth 贴边后保留在屏幕内的宽度（细线的粗细）
     * @param edgeWidth 吸附状态下球的"视觉宽度"（通常远小于 DOT 状态的直径）
     */
    fun computeEdgePosition(
        edge: ScreenEdge,
        centerY: Int,
        ballDiameter: Int,
        screen: Bounds,
        edgeLineWidth: Int,
    ): Bounds {
        val radius = ballDiameter / 2
        val lineHalf = max(1, edgeLineWidth / 2)

        val cx = when (edge) {
            // 球心放在屏幕左边缘 + 细线一半的位置 → 只有细线那么宽的部分露在屏内
            ScreenEdge.LEFT -> screen.left + lineHalf
            ScreenEdge.RIGHT -> screen.right - lineHalf
        }

        // Y 仍要约束在屏幕内，避免贴边时球跑出上下边界
        val cy = centerY.coerceIn(screen.top + radius, screen.bottom - radius)

        return Bounds(cx - radius, cy - radius, cx + radius, cy + radius)
    }

    /**
     * 计算面板应出现的位置。
     *
     * 原则：**面板要在球的内侧展开，且完整落在屏幕内** ——
     * 面板被屏幕边缘裁掉一半会显得很糟，而且用户点不到里面的控件。
     *
     * @param ballBounds 当前球的位置
     * @param panelWidth 面板宽
     * @param panelHeight 面板高
     * @param screen 屏幕可用区域
     * @param gap 球与面板之间的间隙
     */
    fun computePanelBounds(
        ballBounds: Bounds,
        panelWidth: Int,
        panelHeight: Int,
        screen: Bounds,
        gap: Int = 0,
    ): Bounds {
        val ballOnLeftHalf = ballBounds.centerX < screen.centerX

        // 球在左半屏 → 面板向右展开；球在右半屏 → 面板向左展开
        val preferredLeft = if (ballOnLeftHalf) {
            ballBounds.right + gap
        } else {
            ballBounds.left - gap - panelWidth
        }

        // 水平方向兜底：不允许超出屏幕
        val left = preferredLeft.coerceIn(
            screen.left,
            max(screen.left, screen.right - panelWidth),
        )

        // 垂直方向：面板与球顶端对齐，但不越出屏幕
        val top = ballBounds.top.coerceIn(
            screen.top,
            max(screen.top, screen.bottom - panelHeight),
        )

        return Bounds(left, top, left + panelWidth, top + panelHeight)
    }
}
