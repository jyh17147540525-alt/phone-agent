package com.pocketagent.ui.models

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin

/**
 * 推理等级拉条 —— 移植 dsh-claude-style 的 UI 与交互。
 *
 * 源码：`~/.dsh/.../dsh-claude-style/lib/client.js`，类名族 `dsh-claude-effort-*`
 * 常量：`U = .8`（磁吸回拉）/ `G = 3px`（拖动前置位移）
 *
 * ===== 这一版修掉的三件事（用户指出）=====
 *
 * ① **矩阵是 5 行，不是 2 行**
 *    源码 `gridAutoRows = sq`，行数由布局函数给；实测参考图是 5 行。
 *
 * ② **吸附会把 off 弹回 max**
 *    原来是 `LaunchedEffect(index, trackW, settleTo)` —— `settleTo` 归零时
 *    这个 effect 会**带着旧 index 重跑一次**，于是把手先弹回上一档再回来。
 *    ⇒ 改成"显式请求 + 令牌"：`requestMove(x)` 才会动画，令牌变化才重跑。
 *
 * ③ **只能拖，不能点**
 *    用户："点击拉条上对应思考等级的大概位置也能切换"。
 *    ⇒ 加了 tap 手势：点哪儿就跳到最近的档。
 *
 * ===== 动画（源码三条 keyframes）=====
 * ```
 * Flash: 0-14% accent → 20-34% 亮色 → 100% accent   （1.45s，infinite）
 * Front: 0% 0 → 30% .9 → 100% 0                      （入场）
 * Flow : 0 → 3% .95 → 6% 0                           （4s，横扫）
 * ```
 * 每格按**列位置**决定基础亮度，再加上错开的相位 —— 于是看起来是一道波
 * 从左往右推进，而不是整片一起闪。
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

    // ══════════════════════════════════════════════════════════════
    //  ★★ 2026-11-01：手势闭包里**不能直接捕获 index / onPick**
    // ══════════════════════════════════════════════════════════════
    //
    // ⚠️ `pointerInput(span, levels)` 的 key 不含 `index`，所以那个块
    //    只在 span/levels 变化时重建 —— 其中的 `index` 是**创建时的旧值**。
    //
    //    症状（真机日志坐实）：拖动结束时 `target(0) != index(旧值0)` 为 false
    //    ⇒ 跳过 onPick ⇒ 把手动了、状态没变 ⇒ "拖回 off 却停在 max"。
    //
    // ⇒ 用 rememberUpdatedState 让闭包每次读到最新值。
    val indexState = androidx.compose.runtime.rememberUpdatedState(index)
    val pickState = androidx.compose.runtime.rememberUpdatedState(onPick)

    var trackW by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(-1) }

    var visualX by remember { mutableStateOf(0f) }
    // ⚠️ 原始位置与显示位置必须分开：
    //    源码里 `na` 是原始 clientX，`p = W(Y(na))` 才是显示值。
    //    拿显示值当累加起点，误差会越滚越大（"拖到底却停在中间"）。
    var rawX by remember { mutableStateOf(0f) }

    var targetX by remember { mutableStateOf(0f) }
    var animToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    val density = LocalDensity.current
    val knobW = with(density) { 16.dp.toPx() }
    val span = (trackW - knobW).coerceAtLeast(1f)

    fun posOf(i: Int): Float =
        if (levels.size < 2) span else i.toFloat() / (levels.size - 1) * span

    fun idxOf(x: Float): Int =
        if (levels.size < 2) 0
        else round(x / span * (levels.size - 1)).toInt().coerceIn(0, levels.size - 1)

    /** 磁吸曲线 —— 逐字翻译源码 `W`。拉向段边界，最多 12.7% 段宽。 */
    fun snapCurve(x: Float): Float {
        if (levels.size < 2 || span <= 0f) return x
        val seg = span / (levels.size - 1)
        val k = floor(x / seg)
        if (k >= levels.size - 1) return x
        val t = (x - k * seg) / seg
        return (k + t - SNAP_PULL * sin(2f * PI.toFloat() * t) / (2f * PI.toFloat())) * seg
    }

    /** 请求把把手动画到 x。**只有这里会启动动画**。 */
    fun requestMove(x: Float) {
        targetX = x
        animToken++
    }

    // 唯一的动画驱动
    LaunchedEffect(animToken, span) {
        if (span <= 0f) return@LaunchedEffect
        animate(
            initialValue = visualX,
            targetValue = targetX,
            animationSpec = tween(MOVE_MS, easing = GLIDE),
        ) { v, _ -> visualX = v }
    }

    // 外部档位变化（例如另一处改了设置）时归位；拖动中不打断
    LaunchedEffect(index, span) {
        if (!dragging && span > 0f) requestMove(posOf(index))
    }

    // 轨道几何就绪时先把把手放到位
    LaunchedEffect(span) {
        if (span > 0f && !dragging) {
            visualX = posOf(index)
            targetX = visualX
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ★★ 2026-11-01：震动反馈
    // ══════════════════════════════════════════════════════════════
    //
    // 用户："加入震动反馈，max 挡位应该是绵长细腻的震动反馈"
    //
    // ⚠️ 两档语义不同：
    //   · 普通档位变化 → 一下轻触（像机械刻度）
    //   · 到 max       → **连续细密**的一长串（像"嗡"），约 1.8 秒
    //
    // ★ 用 `HapticsHandle.tick()`（TextHandleMove）：系统里最短最轻的一类，
    //   连续发才像纹理，而不是"哒哒哒"。它**不需要 VIBRATE 权限**。
    val haptics = com.pocketagent.ui.design.rememberHaptics()
    LaunchedEffect(effort) {
        if (effort == ThinkingEffort.MAX) {
            // ══════════════════════════════════════════════════════
            //  ★★ 2026-11-01：**充能感**
            // ══════════════════════════════════════════════════════
            //
            // 用户："要像充能那样的震动感"。
            //
            // ⚠️ 均匀间隔（每次 50ms）听起来是"哒·哒·哒"，像节拍器 ——
            //    均匀 = 没有方向感 = 不像充能。
            //
            // ★ 充能的核心是**由缓到急**：
            //    间隔从 100ms 递减到 20ms（用 t² 曲线，前段松、后段紧），
            //    后半程再叠一层 `light()` 加强 —— 人感觉到的是一条
            //    **越来越密的纹路**，最后收一记 `confirm()` 像"满了"。
            //
            // ⚠️ 总时长 ≈ 2.4 秒：比原来 1.8 秒更长，但要的是"绵长"。
            val steps = CHARGE_STEPS
            for (i in 0 until steps) {
                val t = i.toFloat() / (steps - 1)
                // 后 1/4 加重：从"轻触"过渡到"短振"
                if (t > 0.75f) haptics.light() else haptics.tick()
                val gap = (CHARGE_START_MS + (CHARGE_END_MS - CHARGE_START_MS) * t * t)
                    .toLong()
                kotlinx.coroutines.delay(gap)
            }
            // 收尾：一记明确的"充满"
            haptics.confirm()
        } else {
            haptics.light()
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {

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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("更快", style = PaType.overline, color = PaColor.TextTertiary)
            Text("更强", style = PaType.overline, color = PaColor.TextTertiary)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { trackW = it.width.toFloat() }
                // ══════════════════════════════════════════════════
                //  ★ 统一手势：按下即定位、位移超 3px 才算拖、松开提交
                // ══════════════════════════════════════════════════
                //
                // ⚠️ 原来挂了两个 `pointerInput`（tap + drag），
                //    tap 那个先消费了事件 ⇒ **拖不动了**。
                //
                // ★ 源码本身就是一个手势（`Ct` / `gi` / `Fe`）：
                //     pointerdown → 立刻定位到指针处（无过渡）
                //     pointermove → 位移 >= G(3px) 才标记 dragging（把手 scale 1.1）
                //     pointerup   → 提交最近的档
                //   ⇒ "点一下" 和 "拖动" 是同一个逻辑的两个极端，
                //     不需要两个手势识别器。
                .pointerInput(span, levels) {
                    val slop = 3.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startX = down.position.x
                        var moved = false

                        // 按下：立刻定位（源码 Ct）
                        var x = (startX - knobW / 2f).coerceIn(0f, span)
                        rawX = x
                        visualX = snapCurve(x)
                        pending = idxOf(x)

                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull() ?: break
                            if (!ch.pressed) break
                            val dx = ch.position.x - startX
                            if (!moved && abs(dx) >= slop) {
                                moved = true
                                dragging = true
                                pending = idxOf(rawX)
                            }
                            if (moved) {
                                ch.consume()
                                x = (ch.position.x - knobW / 2f).coerceIn(0f, span)
                                rawX = x
                                visualX = snapCurve(x)
                                pending = idxOf(x)
                            }
                        }

                        // 松开：提交（源码 Fe）—— 点一下也是这条路径
                        dragging = false
                        val target = if (pending >= 0) pending else indexState.value
                        pending = -1
                        if (target != indexState.value) pickState.value(levels[target])
                        requestMove(posOf(target))
                    }
                },
        ) {
            val accent = PaColor.Accent
            val label = PaColor.TextPrimary

            // ══════════════════════════════════════════════════════
            //  ★★ 动画相位：**不在组合阶段读**
            // ══════════════════════════════════════════════════════
            //
            // ⚠️ 原来写 `val phase by flash.animateFloat(...)` ——
            //    `by` 会在**组合阶段**读值，于是每一帧都触发重组。
            //    28×5 个格子的 Canvas 每帧重组一次，就是用户说的"会断"。
            //
            // ⇒ 保留 `State`，在 `Canvas` 的绘制 lambda 里读 `.value`：
            //   只有绘制阶段失效，不触发重组。
            //
            // ⚠️ 周期从 1450ms 放慢到 2600ms：用户说"太快，有种廉价感"。
            //    源码的 period-flash 是 1.45s，但那是网页端的 28 列小格；
            //    手机上是 5 行更密的网格，同样的周期看起来会躁。
            //    参考项目自己的 `--dsh-claude-period-breathe: 2.4s` 更合适。
            val flash = rememberInfiniteTransition(label = "apexFlash")
            val phaseState = flash.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(FLASH_PERIOD_MS, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "phase",
            )

            Canvas(Modifier.fillMaxWidth().height(30.dp)) {
                val phase = phaseState.value
                val h = size.height
                val loopH = 26.dp.toPx()
                val top = (h - loopH) / 2f
                val r8 = CornerRadius(8.dp.toPx())
                val kx = visualX
                val kwNow = if (dragging) knobW * 1.1f else knobW
                val khNow = 30.dp.toPx() * (if (dragging) 1.1f else 1f)
                val kTop = (h - khNow) / 2f
                val kLeft = kx - (kwNow - knobW) / 2f

                // 轨道底
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.06f),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, loopH),
                    cornerRadius = r8,
                )

                if (isApex) {
                    // ── apex：5 行方格 + 按列渐变 + 错相闪烁 ──────
                    val rows = 5
                    val inset = 2.dp.toPx()
                    val sq = 3.4.dp.toPx()
                    val gap = 1.6.dp.toPx()
                    val cols = ((size.width - inset * 2 + gap) / (sq + gap))
                        .toInt().coerceAtLeast(1)
                    val flashLight = lerp(accent, Color.White, 0.55f)

                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            val p = if (cols > 1) c.toFloat() / (cols - 1) else 1f
                            // 基础亮度：按**列位置**渐变（源码 h.style.opacity = d(p)）
                            val base = 0.18f + 0.82f * p

                            // 闪烁：每格错开相位，波从右往左推进（源码用 (1-p)）。
                            //
                            // ⚠️ 源码的关键帧是"0-14 base / 20-34 亮"，占空比
                            //    只有 20% —— 在网页小格上像流光，在手机密集网格上
                            //    会变成"这里闪一下那里闪一下"，也就是廉价感。
                            // ⇒ 用**更宽更缓**的升余弦：10%~55% 之间平滑起落，
                            //   没有硬边，看着像一条缓慢流动的光。
                            val delay = (1f - p) * 0.31f + ((r * 7 + c * 3) % 11) * 0.04f
                            val local = (phase + delay) % 1f
                            val t = ((local - 0.10f) / 0.45f).coerceIn(0f, 1f)
                            val flashAmt = (0.5f - 0.5f * cos(t * PI.toFloat()))

                            // ⚠️ 闪烁**只调亮度**，不换底层色 ——
                            //    否则峰值时整格变成亮色，列位置的渐变会被冲淡，
                            //    整条看起来就是"一片橙"，失去左暗右亮的方向感。
                            val alpha = (base * (0.55f + 0.45f * flashAmt)).coerceIn(0f, 1f)
                            val col = lerp(
                                accent.copy(alpha = alpha),
                                flashLight.copy(alpha = alpha),
                                // 峰值时轻微偏亮，保留一点"闪"的味道
                                flashAmt * 0.30f,
                            )

                            drawRoundRect(
                                color = col,
                                topLeft = Offset(
                                    inset + c * (sq + gap),
                                    top + inset + r * (sq + gap),
                                ),
                                size = Size(sq, sq),
                                cornerRadius = CornerRadius(1.dp.toPx()),
                            )
                        }
                    }
                } else {
                    // ── 常态：fill（只圆左边两角）+ 每档一个圆点 ──
                    // ⚠️ fill 宽 = 把手位置 + 8dp（源码 `ne + 8`），形成拖尾
                    drawRoundRect(
                        color = label.copy(alpha = 0.26f),
                        topLeft = Offset(0f, top),
                        size = Size((kx + 8.dp.toPx()).coerceIn(0f, size.width), loopH),
                        cornerRadius = r8,
                    )
                    for (i in levels.indices) {
                        drawCircle(
                            color = label.copy(alpha = 0.26f),
                            radius = 2.dp.toPx(),
                            center = Offset(posOf(i) + knobW / 2f, h / 2f),
                        )
                    }
                }

                // 把手
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
                    cornerRadius = CornerRadius(6.dp.toPx()),
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

/** apex 闪烁周期。源码 1.45s，手机上放慢到 2.6s（见 Canvas 里的注释）。 */
private const val FLASH_PERIOD_MS = 2600

/**
 * max 档的**充能**震动参数。
 *
 * 间隔从 [CHARGE_START_MS] 递减到 [CHARGE_END_MS]，共 [CHARGE_STEPS] 次。
 * ⚠️ 用 t² 曲线而不是线性：前段松、后段紧，才有"充能"的加速度感。
 */
private const val CHARGE_STEPS = 34
private const val CHARGE_START_MS = 100f
private const val CHARGE_END_MS = 20f

/** 吸附回拉系数（源码 `U = .8`）。 */
private const val SNAP_PULL = 0.8f

/** 松手吸附时长（源码 `--dsh-claude-duration-move: .2s`）。 */
private const val MOVE_MS = 200

/** 源码 `--dsh-claude-ease-glide: cubic-bezier(.22,.61,.36,1)`。 */
private val GLIDE = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

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
