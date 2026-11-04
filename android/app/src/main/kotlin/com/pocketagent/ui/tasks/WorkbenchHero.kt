package com.pocketagent.ui.tasks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import kotlin.math.cos
import kotlin.math.sin

/**
 * 工作台页首的**开场白**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-04：这一块是"工作台"与"空白输入框"的分界线
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话："在这个页面，开场，背景上都会有这个角色的随机开场白，
 * 像 claude 那样" —— 并附了一张 Claude 首页的截图：
 * 一个**八向星芒** + 「What's up next, 米克尔？」。
 *
 * 抄的是那个**结构**，不是那句英文：
 *
 *   ① 一个不说话的符号（星芒）—— 它表达"这是新的开始"，
 *      而且不占用任何语义；
 *   ② 一句**有人味**的主文案，称呼用的是角色对用户的称呼
 *      （`CustomPersona.addressUser`），不是"你"；
 *   ③ 一行小字说明这个页面能干什么。
 *
 * ⚠️ 为什么不放角色头像：头像会让这一屏变成"聊天首页"，
 *    而工作台要的是"今天要办什么"的那种干爽。星芒是中性的，
 *    也只在这里出现一次。
 *
 * ⚠️ 文案**不在这里写** —— 全在
 *    `:personalogic` 的 `WorkbenchGreeting` 里，有离线测试钉着
 *    （不许撒娇、不许动作描写、同一颗种子永远同一句）。
 *    这里只负责画。
 */
@Composable
internal fun WorkbenchHero(
    headline: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = PaSpace.screenH),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Starmark()
        Spacer(PaSpace.m)
        Text(
            text = headline,
            style = PaType.headline,
            color = PaColor.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (hint.isNotBlank()) {
            Spacer(PaSpace.xs)
            Text(
                text = hint,
                style = PaType.body,
                color = PaColor.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

/**
 * 八向星芒。
 *
 * ⚠️ 用 `Canvas` 画而不是找一张矢量图：星芒只有 8 根线，
 *    位图/矢量资源在深色/浅色主题下要各带一份，而且**没法跟着强调色走**。
 *    这里直接用 `PaColor.Accent`，主题一改它自己就变。
 *
 * ⚠️ 不做旋转动画：动效偏好里"减少动态效果"打开时它应该静止，
 *    而一个 24dp 的图标转起来更像加载中。工作台不需要"正在加载"的暗示。
 */
@Composable
private fun Starmark(modifier: Modifier = Modifier) {
    // ⚠️ 必须在 Canvas **外面**读颜色：`PaColor.Accent` 是 @Composable 的
    //    取值器（跟随主题 / 动态取色），而 `Canvas` 的 lambda 是 DrawScope，
    //    不是 @Composable 上下文 —— 在里面读会报
    //    "@Composable invocations can only happen from the context of a @Composable function"。
    val accent = PaColor.Accent
    Canvas(modifier = modifier.size(28.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = size.minDimension / 2f
        // 内径 0.30：太短会像"米"字，太长像"雪花"。0.30 出来是星芒。
        val inner = outer * 0.30f
        val stroke = outer * 0.20f
        for (i in 0 until 8) {
            val angle = (Math.PI * 2.0 * i / 8.0).toFloat()
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                color = accent,
                start = Offset(cx + dx * inner, cy + dy * inner),
                end = Offset(cx + dx * outer, cy + dy * outer),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 与 `PaSpace` 同名的小工具，免得每处都写 `Spacer(Modifier.height(...))`。 */
@Composable
private fun Spacer(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(size))
}
