package com.pocketagent.ui.models

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaType
import kotlin.math.cos
import kotlin.math.sin

/**
 * 推理等级拉条 —— **照 dsh-claude-style 的真实结构移植**。
 *
 * ===== 源码 =====
 * ~/.dsh/profiles/desktop/node_modules/dsh-claude-style/lib/client.js
 * 类名族 dsh-claude-effort-*
 *
 * ===== 真实现的结构（我前几版全都理解错了）=====
 *
 * 它的轨道**不是**一排格子，而是：
 *
 *   track (h30)
 *     :before  -> 一个 h26 / 圆角 8 的矩形，色 = interactive-bg-hover
 *     .fill    -> 从 0 到把手，h26，圆角 8 只圆左边两角，色 = label 26%
 *     .ticks   -> 每个档位一个 4×4 的**圆点**，色 = label 26%
 *     .knob    -> 16×30，圆角 5，白底 + 1px 描边 + 微阴影
 *
 * 而我参考的那张图是 **Max 档的 [data-apex] 模式**：
 *   - .matrix 显示（display:grid），里面是 accent 色小方格，带闪烁动画
 *   - .fill 和 .ticks 的 opacity 变 0（隐藏）
 *   - .knob 变成 accent 22% + 白，加 0 0 10px accent 80% 光晕
 *
 * => 所以我之前画"30 个渐变格子"是错的：那是 apex 的方格，不是常态轨道。
 *
 * ===== 移植过来的数值 =====
 *  track 30 / loop 26 / 圆角 8 / knob 16×30 圆角 5 / tick 4 圆点
 *  fill 与 tick 用 label-primary 26%
 *  knob 常态 #fff + 0 1px 3px #14141347 + 0 0 0 1px #1414130f
 *  knob apex 用 accent 22% + 白，光晕 10px accent 80% + 1px accent 35%
 *  拖动时 knob scale 1.1
 */
@Composable
fun ThinkingSlider(
    effort: ThinkingEffort,
    onPick: (ThinkingEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manual = ThinkingEffort.entries.filter { it != ThinkingEffort.AUTO }
    val autoOn = effort == ThinkingEffort.AUTO
    val shown = if (autoOn) ThinkingEffort.MEDIUM else effort
    val index = manual.indexOf(shown).coerceAtLeast(0)
    val isApex = effort == ThinkingEffort.HIGH

    var trackW by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // ⚠️ 用 remember（**不带 key**）+ 一个只在「非拖动时」同步的 effect。
    //    原来写 `remember(shown)`：一旦拖动中档位变了，shown 变 ⇒ dragF 被重置
    //    ⇒ 手指还在拖、位置却跳回去 ⇒ 拖不动 / 拖到一半停住。
    var dragF by remember { mutableStateOf(index.toFloat() / (manual.size - 1)) }

    // 档位从外部变化（比如点了「自动」）时，把把手同步到对应位置。
    // 拖动中不覆盖 —— 否则会和手指打架。
    androidx.compose.runtime.LaunchedEffect(shown, dragging) {
        if (!dragging) dragF = index.toFloat() / (manual.size - 1)
    }

    val density = LocalDensity.current
    val knobW = with(density) { 16.dp.toPx() }
    val travel = (trackW - knobW).coerceAtLeast(1f)

    fun snap(f: Float): ThinkingEffort {
        val n = kotlin.math.round(f * (manual.size - 1))
            .toInt().coerceIn(0, manual.size - 1)
        return manual[n]
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {

        // head：13sp，baseline 对齐，gap 6
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
            Spacer(Modifier.weight(1f))
            AutoChip(autoOn) { onPick(if (autoOn) shown else ThinkingEffort.AUTO) }
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
                .pointerInput(trackW, manual) {
                    detectTapGestures { off ->
                        if (travel > 0f) {
                            val f = ((off.x - knobW / 2f) / travel).coerceIn(0f, 1f)
                            dragF = f
                            onPick(snap(f))
                        }
                    }
                }
                .pointerInput(trackW, manual) {
                    detectDragGestures(
                        onDragStart = { off ->
                            dragging = true
                            if (travel > 0f) {
                                dragF = ((off.x - knobW / 2f) / travel).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        if (travel > 0f) {
                            val next = (dragF + amount.x / travel).coerceIn(0f, 1f)
                            dragF = next
                            val picked = snap(next)
                            // 与真实状态比（不是与 shown 比，见上一版注释）
                            if (picked != effort) onPick(picked)
                        }
                    }
                },
        ) {
            val accent = PaColor.Accent
            val label = PaColor.TextPrimary
            val knobBody = if (isApex) lerp(Color.White, accent, 0.22f) else Color.White

            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val h = size.height
                val loopH = 26.dp.toPx()
                val top = (h - loopH) / 2f
                val r8 = CornerRadius(8.dp.toPx())
                val kx = travel * dragF

                // ① :before —— 轨道底
                drawRoundRect(
                    color = if (isApex) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, loopH),
                    cornerRadius = r8,
                )

                if (isApex) {
                    // ② apex 模式：accent 方格网格（替代 fill + ticks）
                    // ⚠️ 参考图里的网格是**又密又带从左到右的明暗渐变**的：
                    //    最左的格子几乎暗到看不出，越往右越亮。
                    //    我第一版用 12 列 + 均匀 sin 波，结果是"整条一样亮"，
                    //    完全没有参考图那种渐变。
                    val cols = 26
                    val rows = 2
                    val inset = 2.dp.toPx()
                    val cw = (size.width - inset * 2) / cols
                    val ch = (loopH - inset * 2) / rows
                    for (c in 0 until cols) {
                        val pos = c.toFloat() / (cols - 1)
                        for (rw in 0 until rows) {
                            // 底色：从左到右的 ramp（0.16 → 1.0）
                            val ramp = 0.16f + 0.84f * pos
                            // 闪烁：按列错开相位，幅度很小，只做"呼吸"
                            val wave = 0.88f +
                                0.12f * sin((pos + rw * 0.5f) * 3f * Math.PI.toFloat())
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
                    // ③ 常态：fill（圆角只圆左边）+ 每档一个圆点
                    drawRoundRect(
                        color = label.copy(alpha = 0.26f),
                        topLeft = Offset(0f, top),
                        size = Size(kx + 8.dp.toPx(), loopH),
                        cornerRadius = r8,
                    )
                    for (i in manual.indices) {
                        val fy = i.toFloat() / (manual.size - 1)
                        val tx = travel * fy + knobW / 2f
                        drawCircle(
                            color = label.copy(alpha = 0.26f),
                            radius = 2.dp.toPx(),
                            center = Offset(tx, h / 2f),
                        )
                    }
                }

                // ④ 把手
                val kw = if (dragging) knobW * 1.1f else knobW
                val kh = 30.dp.toPx() * (if (dragging) 1.1f else 1f)
                val kTop = (h - kh) / 2f
                val kLeft = kx - (kw - knobW) / 2f
                val kr = CornerRadius(5.dp.toPx())

                if (isApex) {
                    // 光晕
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
                // 描边（box-shadow 0 0 0 1px）
                drawRoundRect(
                    color = if (isApex) accent.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.06f),
                    topLeft = Offset(kLeft - 1.dp.toPx(), kTop - 1.dp.toPx()),
                    size = Size(kw + 2.dp.toPx(), kh + 2.dp.toPx()),
                    cornerRadius = CornerRadius(kr.x + 1.dp.toPx()),
                )
                // 本体
                drawRoundRect(
                    color = knobBody,
                    topLeft = Offset(kLeft, kTop),
                    size = Size(kw, kh),
                    cornerRadius = kr,
                )
            }
        }
    }
}

@Composable
private fun AutoChip(on: Boolean, onToggle: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(PaRadius.pill))
            .background(if (on) PaColor.Accent else PaColor.GlassTint)
            .clickable(onClick = onToggle)
            .heightIn(min = 26.dp)
            .padding(horizontal = 8.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "自动",
            style = PaType.overline,
            color = if (on) Color(0xFF06121F) else PaColor.TextTertiary,
        )
    }
}

@Composable
private fun levelColor(effort: ThinkingEffort): Color = when (effort) {
    ThinkingEffort.AUTO -> PaColor.TextSecondary
    ThinkingEffort.LOW -> PaColor.AccentText.copy(alpha = 0.55f)
    ThinkingEffort.MEDIUM -> PaColor.AccentText.copy(alpha = 0.8f)
    ThinkingEffort.HIGH -> PaColor.AccentText
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