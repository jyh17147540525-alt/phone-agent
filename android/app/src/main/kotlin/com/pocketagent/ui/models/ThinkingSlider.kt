package com.pocketagent.ui.models

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.modelrouter.ThinkingEffort
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaType
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin

/**
 * 推理等级拉条 —— **按 dsh-claude-style 的交互代码逐条移植**。
 *
 * ===== 源码 =====
 * `~/.dsh/profiles/desktop/node_modules/dsh-claude-style/lib/client.js`
 * 组件构建函数 `$b(e)`，类名族 `dsh-claude-effort-*`
 *
 * ===== 从源码里逐条翻译过来的逻辑 =====
 *
 * 常量（源码同名）：
 *   U = 0.8   —— 吸附曲线的回拉系数
 *   G = 3     —— 拖动前置位移（px），防误触
 *
 * 位置换算：
 *   span = trackWidth - knobWidth              （源码 N().span）
 *   P(i) = i / (n-1) * span                    （档位 → 像素）
 *   Y(x) = clamp(x - trackLeft - knob/2, 0, span)   （指针 → 像素）
 *   idx(x) = round(x / span * (n-1))           （像素 → 档位）
 *
 * **磁吸曲线**（源码 W，这是"质感"的核心）：
 * ```
 * seg  = span / (n-1)
 * k    = floor(x / seg)
 * t    = (x - k*seg) / seg        // 段内 0..1
 * W(x) = (k + t - U*sin(2πt)/(2π)) * seg
 * ```
 * ★ 它把位置往**段的边界**拉，最多拉 0.8/(2π) ≈ 12.7% 个段宽。
 *   手感像是有磁铁，而不是一条滑溜溜的线。这是我在前几版完全没做的。
 *
 * 绘制联动（源码 z）：
 *   knob.translate = pos
 *   fill.width     = pos + 8px      ← fill 比把手多出 8px，形成"拖尾"
 *
 * 手势（源码 Ct / gi / Qa / Fe）：
 *   按下  → 立即定位（无过渡）、记录起点
 *   移动  → 位移超过 G(3px) 才进入 dragging（把手 scale 1.1）
 *   松开  → 吸附到 idx、调 onPick、把手动画到 P(idx)
 *
 * apex（源码 ae）：
 *   index == 最后一档 ⇒ 轨道换成 accent 方格网格 + 发光把手
 *
 * ===== 档位：四档 off / low / high / max =====
 * 与源码的 `reasoning.efforts` 一一对应（源码里也是厂商给的离散档位）。
 */
@Composable
fun ThinkingSlider(
    effort: ThinkingEffort,
    onPick: (ThinkingEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val levels = ThinkingEffort.entries
    val index = levels.indexOf(effort).coerceAtLeast(0)
    val isApex = effort.isApex

    var trackW by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(-1) }

    val density = LocalDensity.current
    val knobW = with(density) { 16.dp.toPx() }
    val span = (trackW - knobW).coerceAtLeast(1f)

    // ══════════════════════════════════════════════════════════
    //  把手位置
    // ══════════════════════════════════════════════════════════
    //
    // ⚠️ 原来用 `Animatable` + `scope.launch { snapTo(...) }`：
    //    `launch` 是**异步**的，连续拖动事件到来时 `pos.value` 还是旧值
    //    ⇒ 位置累加错、拖不到底。
    //
    // ⇒ 拖动中用**同步**的 `visualX`（直接赋值，立即生效）；
    //   只有"松手吸附"那一小段才用 `animate` 做过渡。
    var visualX by remember { mutableStateOf(0f) }
    var settleTo by remember { mutableStateOf<Float?>(null) }

    // ⚠️ 必须分开保存**原始位置**与**显示位置**：
    //    源码里 `na` 是原始 clientX，`p = W(Y(na))` 才是显示值。
    //    如果把显示值（已吸附）当成下次累加的起点，误差会越滚越大 ——
    //    表现就是"拖到最右却停在中间"。
    var rawX by remember { mutableStateOf(0f) }

    fun posOf(i: Int): Float =
        if (levels.size < 2) span else i.toFloat() / (levels.size - 1) * span

    fun idxOf(x: Float): Int =
        if (levels.size < 2) 0
        else round(x / span * (levels.size - 1)).toInt()
            .coerceIn(0, levels.size - 1)

    /**
     * 磁吸曲线 —— 逐字翻译源码的 `W`。
     *
     * ⚠️ 这是我前几版**没做**的东西：只做线性插值的话，
     *    拖动是"滑"的；加了这一项才是"吸"的。
     */
    fun snapCurve(x: Float): Float {
        if (levels.size < 2 || span <= 0f) return x
        val seg = span / (levels.size - 1)
        val k = floor(x / seg)
        if (k >= levels.size - 1) return x
        val t = (x - k * seg) / seg
        return (k + t - SNAP_PULL * sin(2f * PI.toFloat() * t) / (2f * PI.toFloat())) * seg
    }

    // 外部状态变化（点了别处）或松手吸附 —— 都用同一段动画
    LaunchedEffect(index, trackW, settleTo) {
        if (dragging || trackW <= 0f) return@LaunchedEffect
        val target = settleTo ?: posOf(index)
        androidx.compose.animation.core.animate(
            initialValue = visualX,
            targetValue = target,
            animationSpec = tween(MOVE_MS, easing = GLIDE),
        ) { v, _ -> visualX = v }
        if (settleTo != null) settleTo = null
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {

        // head：13sp baseline 对齐
        Row(verticalAlignment = Alignment.Bottom) {
            Text("推理等级", style = PaType.caption, color = PaColor.TextSecondary)
            Spacer(Modifier.width(6.dp))
            Text(
                text = effort.label,
                style = PaType.caption,
                color = levelColor(effort),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        // ends：11sp
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("更快", style = PaType.overline, color = PaColor.TextTertiary)
            Text("更强", style = PaType.overline, color = PaColor.TextTertiary)
        }

        // track：30dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { trackW = it.width.toFloat() }
                .pointerInput(trackW, levels) {
                    detectDragGestures(
                        onDragStart = { off ->
                            val x = (off.x - knobW / 2f).coerceIn(0f, span)
                            dragging = true
                            settleTo = null
                            // 源码：按下立即定位（无过渡）
                            rawX = x
                            visualX = snapCurve(x)
                            pending = idxOf(x)
                        },
                        onDragEnd = {
                            dragging = false
                            val target = if (pending >= 0) pending else index
                            pending = -1
                            // 源码 Fe：吸附到整档 + 提交
                            if (target != index) onPick(levels[target])
                            settleTo = posOf(target)
                        },
                        onDragCancel = {
                            dragging = false
                            pending = -1
                            settleTo = posOf(index)
                        },
                    ) { change, amount ->
                        change.consume()
                        rawX = (rawX + amount.x).coerceIn(0f, span)
                        visualX = snapCurve(rawX)
                        pending = idxOf(rawX)
                    }
                },
        ) {
            val accent = PaColor.Accent
            val label = PaColor.TextPrimary

            Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val h = size.height
                val loopH = 26.dp.toPx()
                val top = (h - loopH) / 2f
                val r8 = CornerRadius(8.dp.toPx())
                val kx = visualX
                val kwNow = if (dragging) knobW * 1.1f else knobW
                val khNow = 30.dp.toPx() * (if (dragging) 1.1f else 1f)
                val kTop = (h - khNow) / 2f
                val kLeft = kx - (kwNow - knobW) / 2f

                // ① 轨道底（源码 :before）
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.06f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, loopH),
                    cornerRadius = r8,
                )

                if (isApex) {
                    // ② apex：accent 方格网格 + 左暗右亮的 ramp
                    val cols = 26
                    val rows = 2
                    val inset = 2.dp.toPx()
                    val cw = (size.width - inset * 2) / cols
                    val ch = (loopH - inset * 2) / rows
                    for (c in 0 until cols) {
                        val f = c.toFloat() / (cols - 1)
                        val ramp = 0.16f + 0.84f * f
                        for (rw in 0 until rows) {
                            val wave = 0.88f +
                                0.12f * sin((f + rw * 0.5f) * 3f * PI.toFloat())
                            drawRoundRect(
                                color = accent.copy(alpha = (ramp * wave).coerceIn(0f, 1f)),
                                topLeft = Offset(inset + c * cw, top + inset + rw * ch),
                                size = Size(
                                    (cw - 0.8.dp.toPx()).coerceAtLeast(1f),
                                    (ch - 0.8.dp.toPx()).coerceAtLeast(1f),
                                ),
                                cornerRadius = CornerRadius(1.dp.toPx()),
                            )
                        }
                    }
                } else {
                    // ③ fill：宽度 = 把手位置 + 8dp（源码 `ne + 8`），只圆左边两角
                    drawRoundRect(
                        color = label.copy(alpha = 0.26f),
                        topLeft = Offset(0f, top),
                        size = Size((kx + 8.dp.toPx()).coerceIn(0f, size.width), loopH),
                        cornerRadius = r8,
                    )
                    // ④ ticks：每档一个 4dp 圆点，位置 = P(i) + knob/2
                    for (i in levels.indices) {
                        drawCircle(
                            color = label.copy(alpha = 0.26f),
                            radius = 2.dp.toPx(),
                            center = Offset(posOf(i) + knobW / 2f, h / 2f),
                        )
                    }
                }

                // ⑤ 把手
                if (isApex) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.7f), accent.copy(alpha = 0f)),
                            center = Offset(kx + knobW / 2f, h / 2f),
                            radius = knobW * 2f,
                        ),
                        radius = knobW * 2f,
                        center = Offset(kx + knobW / 2f, h / 2f),
                    )
                }
                drawRoundRect(
                    color = if (isApex) accent.copy(alpha = 0.35f)
                    else Color.Black.copy(alpha = 0.06f),
                    topLeft = Offset(kLeft - 1.dp.toPx(), kTop - 1.dp.toPx()),
                    size = Size(kwNow + 2.dp.toPx(), khNow + 2.dp.toPx()),
                    cornerRadius = CornerRadius(5.dp.toPx() + 1.dp.toPx()),
                )
                drawRoundRect(
                    color = if (isApex) lerp(Color.White, accent, 0.22f) else Color.White,
                    topLeft = Offset(kLeft, kTop),
                    size = Size(kwNow, khNow),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                )
            }
        }
    }
}

// ── 源码常量 ────────────────────────────────────────────────

/** 吸附曲线的回拉系数（源码 `U = .8`）。 */
private const val SNAP_PULL = 0.8f

/** 松手吸附时长（源码 `--dsh-claude-duration-move: .2s`）。 */
private const val MOVE_MS = 200

/** 源码 `--dsh-claude-ease-glide: cubic-bezier(.22, .61, .36, 1)`。 */
private val GLIDE = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

/** 档位名按档位上色：越高越接近强调色。 */
@Composable
private fun levelColor(effort: ThinkingEffort): Color = when (effort) {
    ThinkingEffort.OFF -> PaColor.TextSecondary
    ThinkingEffort.LOW -> PaColor.AccentText.copy(alpha = 0.55f)
    ThinkingEffort.HIGH -> PaColor.AccentText.copy(alpha = 0.8f)
    ThinkingEffort.MAX -> PaColor.AccentText
}

private fun lerp(from: Color, to: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = from.alpha + (to.alpha - from.alpha) * f,
    )
}