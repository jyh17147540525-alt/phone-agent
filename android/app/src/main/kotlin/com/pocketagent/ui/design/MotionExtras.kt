package com.pocketagent.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.CircleReveal
import com.pocketagent.agentui.SnapGuide
import com.pocketagent.agentui.WheelFling
import kotlinx.coroutines.launch

/**
 * 动效类组件：吸附画线、惯性滚轮、圆形扩散转场。
 *
 * ★ 这一批的共同点：**几何与物理全部由 `:agentui` 算好**，本文件只画。
 *   所以这里出现任何 `if` 判断业务规则都是错的 —— 那会掉出离线覆盖。
 */

// ═══════════════════════════════════════════════════════════════
//  吸附画线
// ═══════════════════════════════════════════════════════════════

/**
 * 吸附时画出的那条参考线。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"吸附"必须配"画线"
 * ═══════════════════════════════════════════════════════════════
 *
 * 只吸附不画线的效果是：卡片自己跳了一下，用户不知道**为什么**跳。
 * 那感觉像卡顿，不像对齐。
 *
 * ⇒ 画一条从被拖卡延伸出去的线，并高亮它吸到的那条边 ——
 *   用户立刻明白"我和它对上了"。这是设计工具的通行手法。
 *
 * ⚠️ 线的**几何**由 `:agentui/SnapGuide.segment` 算。
 *    本组件只负责画，不在 Compose 里算坐标。
 */
@Composable
fun SnapGuideOverlay(
    segment: SnapGuide.Seg?,
    modifier: Modifier = Modifier,
    color: Color = PaColor.Accent,
    density: Float = 1f,
) {
    if (segment == null) return
    val lineColor = color
    Canvas(modifier = modifier.fillMaxSize()) {
        val a = Offset(segment.fromX * density, segment.fromY * density)
        val b = Offset(segment.toX * density, segment.toY * density)

        // 主线：1dp，alpha 0.6 —— 要看得见但不抢卡片
        drawLine(
            color = lineColor.copy(alpha = 0.6f),
            start = a,
            end = b,
            strokeWidth = 1.dp.toPx(),
        )
        // 两端各画一个小圆点：让线"有端点"，像尺寸标注而不像杂线
        for (pt in listOf(a, b)) {
            drawCircle(color = lineColor, radius = 2.5.dp.toPx(), center = pt)
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  惯性滚轮
// ═══════════════════════════════════════════════════════════════

/**
 * **可吸附的滚轮容器** —— 松手后继续滑，并且停在整格上。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不用 `LazyColumn` 的 fling
 * ═══════════════════════════════════════════════════════════════
 *
 * `LazyColumn` 的 fling 是**平台默认**的（Android 的 splineBasedDecay），
 * 而用户要的是 iOS 那种"滑得很远、拖尾很长"的手感。
 * Compose 没有办法直接替换 LazyColumn 的 fling 曲线 ——
 * 它的衰减是写死在 `scrollable` 里面的。
 *
 * ⇒ 对**滚轮/选择器**这类"项数少、要吸附"的场景，自己做一套：
 *   用手势拿速度 → `:agentui/WheelFling` 算截断与吸附 → `Animatable` 驱动。
 *
 * ⚠️ 长内容列表**不该**用这个（项数多、不需要吸附）。
 *   那种场景继续用 `LazyColumn`，接受平台的 fling ——
 *   见 `WheelFling.CONTENT` 的注释里那段"两套预设"的说明。
 *
 * @param itemSize 一格的尺寸（dp）
 * @param itemCount 总共几格
 * @param onSettled 停稳后回调落在第几格
 */
@Composable
fun PaSnappingWheel(
    itemSize: androidx.compose.ui.unit.Dp,
    itemCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable (offsetPx: Float) -> Unit,
) {
    val density = LocalDensity.current
    val itemSizePx = with(density) { itemSize.toPx() }
    val scope = rememberCoroutineScope()

    // 当前滚动位置（px）。★ 用 Animatable 而不是 Float：
    //   fling 与吸附都要靠它做连续动画。
    //
    // ⚠️ `Animatable(initialValue)` 的重载只接受 Float，
    //    传第二个 `AnimationVector1D()` 参数会被解析成
    //    `Animatable(type, initialValue)` —— 参数位置全错。
    //    要么只传一个 Float，要么两个都传（旧的 type + 值写法）。
    val offset = remember { Animatable(0f) }

    val maxOffset = -(itemCount - 1) * itemSizePx

    // 拖动状态：drag 与 fling 必须共用同一个 offset，
    // 否则松手瞬间会跳回拖动起点。
    val dragState = rememberDraggableState { delta ->
        scope.launch {
            val next = (offset.value + delta).coerceIn(maxOffset, 0f)
            offset.snapTo(next)
        }
    }

    Box(
        modifier = modifier
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    // ⚠️ 先截断速度（超长 fling 用户会觉得停不下来），
                    //    再算吸附目标。顺序反了会让截断失效。
                    val clamped = WheelFling.clampVelocity(velocity, WheelFling.WHEEL)
                    val projected = offset.value +
                        WheelFling.estimateDistance(clamped, WheelFling.WHEEL)
                    val snapped = WheelFling.snapToNearest(projected, itemSizePx)
                        .coerceIn(maxOffset, 0f)

                    offset.animateTo(
                        targetValue = snapped,
                        // 吸附用弹簧：给它一点"咔"的落位感。
                        // ★ bouncy 在这里是对的 —— 它是低频的落位，
                        //   不是高频的滚动。
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    )
                },
            ),
    ) {
        content(offset.value)
    }
}

// ═══════════════════════════════════════════════════════════════
//  圆形扩散转场
// ═══════════════════════════════════════════════════════════════

/**
 * **圆形扩散** —— 新内容从某一点长出来，把旧内容"擦"掉。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它和淡入是两种叙事
 * ═══════════════════════════════════════════════════════════════
 *
 *  · **淡入**：两页同时在场，一个变清晰一个变模糊。感觉是"换了"。
 *  · **圆形扩散**：新内容从你手指按住的那个点**长出来**。感觉是
 *    "这里打开了一个东西"。
 *
 * 后者更强的因果感 —— 手指在右上角，扩散就从右上角开始。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 实现要点（三处都会露馅）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **半径必须覆盖到最远的角**。按"屏幕宽"算的话，从角落扩散时
 *    对角永远盖不到 —— 动画结束会看到一块没被覆盖的三角。
 *    见 `CircleReveal.maxRadiusFrom`（那边有 5 条测试钉着）。
 *
 * 2. **用 `clipPath` 而不是逐像素遮罩**。前者是一次 GPU 裁剪，
 *    后者在软件层画会掉帧到个位数。
 *
 * 3. **必须在 `drawWithContent` 里做**，不能包一层 Box 画个圆 ——
 *    那样圆外的旧内容会被**整个盖住**（而不是露出来）。
 *
 * @param reveal 0 = 完全旧内容，1 = 完全新内容
 * @param centerX / centerY 圆心（px）。来自点击位置
 */
@Composable
fun PaCircleReveal(
    reveal: Float,
    centerX: Float,
    centerY: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) {
        // ⚠️ 关键：用 graphicsLayer 的 clip 而不是 alpha ——
        //    圆形裁剪之后，**外面那一圈仍然绘制旧内容**（由调用方叠放）。
        //    这个组件只负责"把当前内容裁成一个正在长大的圆"。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val maxR = CircleReveal.maxRadiusFrom(
                        centerX, centerY,
                        CircleReveal.Rect(0f, 0f, size.width, size.height),
                    )
                    val r = CircleReveal.radiusAt(reveal, maxR)
                    if (r <= 0f) {
                        // 还没开始：什么都不画（露出底下的旧内容）
                        return@drawWithContent
                    }
                    val path = Path().apply {
                        addOval(
                            androidx.compose.ui.geometry.Rect(
                                center = Offset(centerX, centerY),
                                radius = r,
                            ),
                        )
                    }
                    clipPath(path) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * 带状态的圆形扩散：自己管动画进度。
 *
 * ★ 与 [PaCircleReveal] 的分工：那个是"给我一个进度我画"，
 *   这个是"开始吧，我自己跑完"。绝大多数调用点用这个。
 */
@Composable
fun PaCircleRevealEffect(
    visible: Boolean,
    centerX: Float,
    centerY: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(if (visible) 0f else 1f) }

    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = if (visible) {
                    CircleReveal.DURATION_MS
                } else {
                    // ⚠️ 收拢比展开快：关闭是"要离开了"，用户不想等。
                    //    与 `PaMotion` 里"退出比进入快"同一条纪律。
                    CircleReveal.EXIT_DURATION_MS
                },
                easing = PaEasing.Emphasized,
            ),
        )
    }

    PaCircleReveal(
        reveal = progress.value,
        centerX = centerX,
        centerY = centerY,
        modifier = modifier,
        content = content,
    )
}
