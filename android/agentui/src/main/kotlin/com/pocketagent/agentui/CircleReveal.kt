package com.pocketagent.agentui

import kotlin.math.hypot
import kotlin.math.max

/**
 * **圆形扩散转场** —— 从点击位置长出一圈，把新页面"擦"出来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它和淡入淡入是两种叙事
 * ═══════════════════════════════════════════════════════════════
 *
 * · **淡入**：两页同时在场，一个变清晰一个变模糊。感觉是"换了"。
 * · **圆形扩散**：新页面从你手指按住的那个点**长出来**，把旧页面推开。
 *   感觉是"这里打开了一个东西"。
 *
 * 后者更强的因果感 —— 你的手指在右上角，扩散就从右上角开始。
 * 这就是它值得做的理由。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三个必须算对的几何量
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **半径要覆盖到最远的角**。只按"屏幕宽"算的话，从角落扩散时
 *    对角那个角永远盖不到 —— 动画结束时会看到一块没被覆盖的三角。
 *    见 [maxRadiusFrom]。
 *
 * 2. **圆心必须在点击点上**，不是屏幕中心。否则"从我手指长出来"
 *    这个因果关系就断了。
 *
 * 3. **接近半径上限时必须减速**（`easeOut`），否则最后一下
 *    "啪"地铺满，像是跳帧。
 *
 * ★ 全纯函数，可离线测（见 `CircleRevealTest`）。
 */
object CircleReveal {

    /** 一个矩形区域。 */
    data class Rect(val left: Float, val top: Float, val width: Float, val height: Float) {
        val right: Float get() = left + width
        val bottom: Float get() = top + height
    }

    /**
     * 从 (x, y) 扩散到覆盖整个 [area]，需要的最大半径。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 这是本类唯一一个"算错了会露馅"的量
     * ═══════════════════════════════════════════════════════════
     *
     * 直觉是"取宽高的最大值"，但那只在圆心位于中心时成立。
     * 从左上角扩散时，最远的点是右下角 —— 距离是**对角线**长度。
     *
     * 算小了的具体症状：动画结束时四个角里有几个仍然是旧页面，
     * 要等转场结束后才"啪"地出现。那一下比不做圆形扩散更糟。
     *
     * ⇒ 取到**四个角的最大距离**，这是充要条件。
     */
    fun maxRadiusFrom(x: Float, y: Float, area: Rect): Float {
        val corners = listOf(
            area.left to area.top,
            area.right to area.top,
            area.left to area.bottom,
            area.right to area.bottom,
        )
        return corners.maxOf { (cx, cy) -> hypot(cx - x, cy - y) }
    }

    /**
     * 某一进度下的半径。
     *
     * @param progress 0..1
     * @param maxRadius 见 [maxRadiusFrom]
     *
     * ★ 用 ease-out（快起慢停）：扩散要"一下打开"，不是"匀速长大"。
     *   匀速会让最后一段显得很拖。
     */
    fun radiusAt(progress: Float, maxRadius: Float): Float {
        val p = progress.coerceIn(0f, 1f)
        // ease-out cubic，与 `NebulaBloom.easeOutCubic` 同一曲线 ——
        // 全站"扩散/展开"类动画应当共用一个手感。
        val inv = 1f - p
        val eased = 1f - inv * inv * inv
        return maxRadius * eased
    }

    /**
     * 一个点这一帧**是否已经被新页面覆盖**。
     *
     * ★ 用途：给"边缘带一点羽化"的实现判断像素取舍。
     *   纯圆的转场边缘很硬（像蒙版没抗锯齿），
     *   真实实现会在 [FEATHER] 这段距离内做渐变。
     */
    fun isCovered(px: Float, py: Float, cx: Float, cy: Float, radius: Float): Boolean =
        hypot(px - cx, py - cy) <= radius

    /**
     * 羽化带宽（px）。
     *
     * ★ 24px：足够软到看不出锯齿，又不至于把整个转场糊成一团雾。
     *   太小（<8）边缘会有台阶感；太大（>64）看起来像渐显而不是扩散。
     */
    const val FEATHER: Float = 24f

    /**
     * 某个像素的遮盖强度（0 = 全旧页面，1 = 全新页面）。
     *
     * ⚠️ 这个函数是**给实现参考**的：真实渲染用
     *   `Modifier.drawWithContent` + `clipPath`，不会逐像素调它。
     *   它存在的理由是"羽化到底多宽"这个决定可以被测试钉住，
     *   而不是只有写出来看效果才知道。
     */
    fun coverage(px: Float, py: Float, cx: Float, cy: Float, radius: Float): Float {
        val d = hypot(px - cx, py - cy)
        if (d <= radius - FEATHER / 2f) return 1f
        if (d >= radius + FEATHER / 2f) return 0f
        // 线性的中间带：够用，且不会在数值上出意外。
        val t = (radius + FEATHER / 2f - d) / FEATHER
        return t.coerceIn(0f, 1f)
    }

    /**
     * 转场时长（毫秒）。
     *
     * ★ 420ms。比普通转场（280）长、比页面转场（380）再长一点 ——
     *   因为"扩散"这个过程本身需要时间才看得出是从哪儿开始的。
     *   太快（<300）会退化成"闪一下"。
     */
    const val DURATION_MS: Int = 420

    /**
     * 收拢（关闭）时的时长。
     *
     * ⚠️ 比展开**短**（280）：关闭是"要离开了"，用户不想等。
     *   这和 `PaMotion` 里"退出比进入快"是同一条纪律。
     */
    const val EXIT_DURATION_MS: Int = 280

    /**
     * 扩散的圆心。
     *
     * @param tapX / tapY 用户的点击位置。null = 没有点击（比如代码触发导航）
     *
     * ⚠️ 没有点击时用**屏幕中心** —— 那是"没有因果线索"时最不突兀的选择。
     *   用一个假的手指位置（比如右下角）会让动画看起来在指别的地方。
     */
    fun center(tapX: Float?, tapY: Float?, area: Rect): Pair<Float, Float> = when {
        tapX != null && tapY != null ->
            tapX.coerceIn(area.left, area.right) to tapY.coerceIn(area.top, area.bottom)
        else -> (area.left + area.width / 2f) to (area.top + area.height / 2f)
    }

    /**
     * 半径相对屏幕短边的上限比例。
     *
     * ⚠️ 有些场景不该真的"铺满整个屏幕" —— 比如只在内容区内做扩散。
     *   这时传一个限制值进来，避免半径大到把动画时间全花在
     *   "已经盖满之后的空转"上。
     */
    fun clampRadius(radius: Float, area: Rect, maxRatio: Float = 1.0f): Float =
        radius.coerceAtMost(max(area.width, area.height) * maxRatio)
}
