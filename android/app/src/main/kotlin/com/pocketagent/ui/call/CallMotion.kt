package com.pocketagent.ui.call

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.VoiceCall
import kotlin.math.cos
import kotlin.math.sin

/**
 * **通话动效** —— 三种相位给三种**形状不同**的动画。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "打电话的页面互动动画，你可以参考 chatgpt 和豆包的互动效果和
 *  互动动画，用户可以随时打断模型的说话"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么"形状不同"比"颜色不同"重要
 * ═══════════════════════════════════════════════════════════════
 *
 * 打电话时用户的**注意力不在屏幕上** —— 他在听。偶尔瞥一眼是为了
 * 回答"现在轮到谁了"。
 *
 * 如果三种状态只是颜色不同（蓝/紫/绿），瞥一眼**看不出来** ——
 * 那需要凝视并回忆配色约定。而形状（呼吸环/旋转/波形）
 * 是**一眼可辨**的，这正是 ChatGPT / 豆包的做法。
 *
 * ⚠️ 三种动效都是**无限循环**且**不阻塞协程**：
 *    用 `rememberInfiniteTransition` 而不是 `LaunchedEffect` 里的
 *    `while(true)` —— 后者会在页面离开时留下一个跑着的协程。
 */
@Composable
fun CallMotion(
    phase: VoiceCall.Phase,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFFB9D4FF),
    /** 说话时的音量 0..1（驱动波形起伏）。没有音量数据时传固定值。 */
    volume: Float = 0.5f,
) {
    when (VoiceCall.motionFor(phase)) {
        VoiceCall.Motion.BREATHING_RING -> BreathingRing(modifier, accent)
        VoiceCall.Motion.SPINNING -> SpinningDots(modifier, accent)
        VoiceCall.Motion.WAVEFORM -> Waveform(modifier, accent, volume)
        VoiceCall.Motion.STILL -> Unit
    }
}

/**
 * **听** —— 从中心向外的呼吸环。
 *
 * ★ 为什么用"外扩"而不是"内缩"：
 *   外扩读起来是"在收音/在接收"，内缩像是"在收拢/在结束"。
 *
 * ⚠️ 三圈错开相位（各差 1/3 周期）：一圈单独呼吸看起来像心跳，
 *   三圈错开才是"持续在听"的感觉。
 */
@Composable
private fun BreathingRing(modifier: Modifier, accent: Color) {
    val transition = rememberInfiniteTransition(label = "breathing")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // ⚠️ 2.4 秒：比心跳慢、比呼吸快一点 —— 电话等待时的自然节奏。
            animation = tween(2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "breathingT",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxR = size.minDimension * 0.42f

        for (i in 0 until 3) {
            // 每圈错开 1/3 周期
            val phaseT = (t + i / 3f) % 1f
            // 半径从 0.15 长到 1.0
            val r = maxR * (0.15f + 0.85f * phaseT)
            // 越往外越淡：这是"扩散出去消失"的视觉依据
            val alpha = (1f - phaseT) * 0.45f
            drawCircle(
                color = accent.copy(alpha = alpha),
                radius = r,
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/**
 * **想** —— 缓慢旋转的粒子。
 *
 * ★ 旋转速度刻意很慢（3.6 秒一圈）：它要表达"在思考"，
 *   转太快会显得焦躁（像是"卡住了在重试"）。
 *
 * ⚠️ 用粒子而不是"三个点跳动"：
 *   跳动的点暗示"进度条式"的机械感，而旋转粒子更像"脑子在转"。
 */
@Composable
private fun SpinningDots(modifier: Modifier, accent: Color) {
    val transition = rememberInfiniteTransition(label = "spinning")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinAngle",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val orbit = size.minDimension * 0.28f
        val dotR = 5.dp.toPx()

        // 五个粒子，越大越亮（尾巴在后面）
        for (i in 0 until 5) {
            val a = Math.toRadians((angle + i * 24f).toDouble())
            val p = Offset(
                center.x + cos(a).toFloat() * orbit,
                center.y + sin(a).toFloat() * orbit,
            )
            // 第一个最亮，往后递减 —— 形成"拖尾"
            val alpha = 0.9f - i * 0.16f
            drawCircle(
                color = accent.copy(alpha = alpha.coerceAtLeast(0.12f)),
                radius = dotR * (1f - i * 0.12f),
                center = p,
            )
        }
    }
}

/**
 * **说** —— 随音量起伏的波形。
 *
 * ★ 竖条波形（而不是圆环）：声音的直觉就是"起伏"，而竖条最像
 *   音频电平表 —— 用户一眼就知道"它在出声"。
 *
 * ⚠️ 用 `volume` 驱动幅度：没有真实音量数据时它是个固定值，
 *   波形仍然会动（靠时间），只是不会随说话强弱变化。
 *   有真实数据时接进来即可 —— 这个参数就是为那个留的。
 */
@Composable
private fun Waveform(modifier: Modifier, accent: Color, volume: Float) {
    val transition = rememberInfiniteTransition(label = "wave")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "waveT",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val barCount = 9
        val spacing = size.minDimension * 0.055f
        val maxH = size.minDimension * 0.22f
        val barW = 3.5.dp.toPx()

        for (i in 0 until barCount) {
            // 中间的条高，两边低（钟形包络）
            val norm = (i - (barCount - 1) / 2f) / ((barCount - 1) / 2f)
            val envelope = 1f - norm * norm * 0.85f
            // 每根条自己的相位（错开，形成流动感）
            val wave = 0.55f + 0.45f * sin(t + i * 0.7f)
            val h = maxH * envelope * wave * volume.coerceIn(0.05f, 1f)

            val x = cx + (i - (barCount - 1) / 2f) * spacing
            drawLine(
                color = accent.copy(alpha = 0.85f),
                start = Offset(x, cy - h / 2f),
                end = Offset(x, cy + h / 2f),
                strokeWidth = barW,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}
