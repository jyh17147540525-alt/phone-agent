package com.pocketagent.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 输入类组件：输入框、筛选标签、信息横幅。
 *
 * 与 [Components.kt] 的分工：那边是"骨架与动作"（页面、按钮、徽章），
 * 这边是"接收输入与呈现提示"。
 */

// ═══════════════════════════════════════════════════════════════
//  输入框
// ═══════════════════════════════════════════════════════════════

/**
 * 玻璃输入框。
 *
 * ⚠️ 用 [BasicTextField] 而不是 Material 的 `OutlinedTextField`。
 *    后者自带一整套 Material 装饰（浮动标签、底部指示线、容器色、焦点态动画），
 *    要改成玻璃风格等于把它们全部覆盖一遍 —— 那比直接用基础组件更麻烦，
 *    而且每次 Material 升级都可能把覆盖点挪走。
 *
 * [isError] 时把渐变描边换成实心红边：错误必须**看起来就是错误**，
 * 而不是"描边颜色稍微有点不一样"。
 */
@Composable
fun PaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else 4,
    enabled: Boolean = true,
    isError: Boolean = false,
    /**
     * 文本显示变换。API Key 输入框传 `PasswordVisualTransformation()` 把内容遮起来。
     *
     * ⚠️ 遮罩**不是**安全措施 —— 明文该在内存里还是在内存里。它挡的是
     *    最朴素的一种泄露：旁边有人看了一眼屏幕。
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** 尾部插槽。用于"显示/隐藏"这类就地切换 */
    trailing: @Composable (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(PaRadius.s)

    Row(
        modifier = modifier
            .clip(shape)
            .background(PaColor.GlassTint)
            .then(
                if (isError) {
                    Modifier.border(1.dp, PaColor.Danger.copy(alpha = 0.6f), shape)
                } else {
                    Modifier.glassEdge(shape)
                }
            )
            .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = PaColor.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(PaSpace.xs))
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = singleLine,
            maxLines = maxLines,
            visualTransformation = visualTransformation,
            textStyle = TextStyle(
                fontSize = PaType.body.fontSize,
                lineHeight = PaType.body.lineHeight,
                color = if (enabled) PaColor.TextPrimary else PaColor.TextDisabled,
            ),
            cursorBrush = SolidColor(PaColor.Accent),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        Text(
                            text = placeholder,
                            style = PaType.body,
                            color = PaColor.TextTertiary,
                        )
                    }
                    inner()
                }
            },
        )

        if (trailing != null) {
            Spacer(Modifier.width(PaSpace.xs))
            trailing()
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  筛选标签
// ═══════════════════════════════════════════════════════════════

/**
 * 可切换的筛选标签。
 *
 * 三处状态（底、字、边）都用 [animateColorAsState] 过渡而不是硬切。
 * 硬切在快速连点时会产生"闪"的观感 —— 而这个控件天然会被连点
 * （用户会连续勾选几个筛选项），所以过渡在这里不是装饰，是必需。
 */
@Composable
fun PaFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(PaRadius.pill)

    val background by animateColorAsState(
        targetValue = if (selected) PaColor.AccentSoft else PaColor.GlassTint,
        animationSpec = PaMotion.standard(),
        label = "chipBackground",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) PaColor.Accent else PaColor.TextSecondary,
        animationSpec = PaMotion.standard(),
        label = "chipContent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) PaColor.AccentBorder else PaColor.GlassBorder,
        animationSpec = PaMotion.standard(),
        label = "chipBorder",
    )

    Row(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = PaSpace.s, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = PaType.label, color = contentColor)
    }
}

// ═══════════════════════════════════════════════════════════════
//  信息横幅
// ═══════════════════════════════════════════════════════════════

/** 横幅语义。与 [PaBadgeTone] 分开定义 —— 横幅有底色，徽章没有 */
enum class PaBannerTone { Info, Success, Warning, Danger }

/**
 * 信息横幅。
 *
 * 用于"必须被看见但又不该弹窗打断"的内容：被拒绝的条目、加载失败的源、
 * 插件申请了高危能力。
 *
 * ⚠️ 刻意**不做可关闭**。这几类信息的共同点是"用户需要在做决定前看到它"，
 *    给个 × 按钮的结果是用户条件反射地点掉，然后按自己的直觉安装。
 *    要么让它留在那儿，要么从一开始就别显示。
 *
 * [details] 用于逐条列出（如每个失败的源名）。上限由调用方控制 ——
 * 这个组件不做截断，因为"截断多少条"是业务判断不是视觉判断。
 */
@Composable
fun PaBanner(
    title: String,
    modifier: Modifier = Modifier,
    tone: PaBannerTone = PaBannerTone.Info,
    description: String? = null,
    details: List<String> = emptyList(),
) {
    val color = when (tone) {
        PaBannerTone.Info -> PaColor.Info
        PaBannerTone.Success -> PaColor.Success
        PaBannerTone.Warning -> PaColor.Warning
        PaBannerTone.Danger -> PaColor.Danger
    }
    val shape = RoundedCornerShape(PaRadius.s)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), shape)
            .padding(PaSpace.s),
    ) {
        Text(text = title, style = PaType.headline, color = color)

        if (description != null) {
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = description,
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        details.forEach { line ->
            Spacer(Modifier.height(2.dp))
            Text(
                text = "· $line",
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }
    }
}
