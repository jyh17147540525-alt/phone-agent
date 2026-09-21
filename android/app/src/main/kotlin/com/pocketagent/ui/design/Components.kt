package com.pocketagent.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 通用 UI 组件。
 *
 * 设计约束（与 [PaColor] 的立场一致）：
 *  - 组件**不暴露颜色参数**，只暴露语义变体（如 [PaButtonStyle]）。
 *    让调用方传 Color 就等于允许每个页面自己发明配色，那是"简约"的反面。
 *  - 所有可点击元素都有**统一的按压反馈**（缩放到 0.985 + 提亮），
 *    且关掉 Material 涟漪 —— 涟漪与玻璃质感不兼容。
 */

// ═══════════════════════════════════════════════════════════════
//  页面骨架
// ═══════════════════════════════════════════════════════════════

/**
 * 页面骨架：玻璃顶栏 + 内容槽。
 *
 * ⚠️ **不含背景** —— 极光背景由外层外壳画一次，全应用共用一层。
 *
 * 原因：页面转场时背景必须**不动**，只有内容在动。
 * 如果每页各画一层背景，转场时两层背景交叉淡入淡出，
 * 会看到"背景闪一下" —— 那是廉价感的典型来源，
 * 而且会让转场看起来像是"整个屏幕被替换"而不是"内容在切换"。
 *
 * 内容槽不预设滚动方式 —— 因为有的页面要 `LazyColumn`（市场列表），
 * 有的要 `verticalScroll`（设置页）。**嵌套滚动容器会直接崩**，
 * 所以这个决定必须留给调用方。
 */
@Composable
fun PaScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        PaTopBar(title = title, subtitle = subtitle, onBack = onBack, actions = actions)
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

/**
 * 玻璃顶栏。
 *
 * 底边有一条微亮线（`highlightOnTop = false`）——
 * 这条线是"面板贴着屏幕顶部"的暗示。去掉它，顶栏和内容会糊在一起。
 */
@Composable
fun PaTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(PaColor.GlassTint)
            .glassEdgeHorizontal(RoundedCornerShape(0.dp), highlightOnTop = false)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(
                start = if (onBack != null) PaSpace.xs else PaSpace.screenH,
                end = PaSpace.screenH,
                top = PaSpace.xs,
                bottom = PaSpace.s,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PaIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
            Spacer(Modifier.width(PaSpace.xxs))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = PaType.title,
                color = PaColor.TextPrimary,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = PaType.caption,
                    color = PaColor.TextSecondary,
                )
            }
        }

        actions()
    }
}

// ═══════════════════════════════════════════════════════════════
//  按钮
// ═══════════════════════════════════════════════════════════════

/** 按钮语义变体。**不提供自定义颜色** —— 见本文件顶部约束。 */
enum class PaButtonStyle {
    /** 强调色实心。一个界面里最多一个 */
    Primary,

    /** 玻璃 + 描边。次要动作 */
    Glass,

    /** 危险色实心。仅用于不可逆操作 */
    Danger,

    /** 纯文字。用于最轻量的动作 */
    Text,
}

/**
 * 按钮。
 *
 * 高度固定 44dp —— 低于 44 在手机上不好点，高于 48 会显得笨重。
 */
@Composable
fun PaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PaButtonStyle = PaButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    fillWidth: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = PaMotion.fast(),
        label = "paButtonScale",
    )

    val shape = RoundedCornerShape(PaRadius.s)

    // ⚠️ 必须有 `else`：这是**无主语**的 `when`，编译器不做穷尽性分析 ——
    //    哪怕枚举的四个值都列全了，少一个 `else` 依然是编译错误。
    //    （有主语的 `when (style)` 才享受穷尽性检查。）
    //    else 落到 Transparent，与 Text 变体语义一致：它本来就是"无底"的。
    val background: Color = when {
        !enabled -> PaColor.SurfaceHigh
        style == PaButtonStyle.Primary -> PaColor.Accent
        style == PaButtonStyle.Danger -> PaColor.Danger
        style == PaButtonStyle.Glass -> if (pressed) PaColor.GlassTintStrong else PaColor.GlassTint
        else -> Color.Transparent
    }

    val contentColor: Color = when {
        !enabled -> PaColor.TextDisabled
        style == PaButtonStyle.Primary -> Color(0xFF06121F)
        style == PaButtonStyle.Danger -> Color(0xFF1A0505)
        style == PaButtonStyle.Glass -> PaColor.TextPrimary
        else -> PaColor.Accent
    }

    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .height(44.dp)
            .scale(scale)
            .clip(shape)
            .background(background)
            .then(
                if (style == PaButtonStyle.Glass) Modifier.glassEdge(shape) else Modifier
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = if (style == PaButtonStyle.Text) PaSpace.xs else PaSpace.m),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(PaSpace.xs))
        }
        Text(
            text = text,
            style = PaType.label,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}

/** 图标按钮。44dp 触达区 */
@Composable
fun PaIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = PaColor.TextSecondary,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(PaRadius.xs))
            .background(if (pressed) PaColor.GlassTint else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  文本与标签
// ═══════════════════════════════════════════════════════════════

/**
 * 区块标题。
 *
 * 用 [PaType.overline]（全大写 + 放开字距）而不是普通小标题 ——
 * 这是让界面"有结构但不吵"的经典手法：字小、色淡、字距松，
 * 读者会把它当作分隔符而不是内容。
 */
@Composable
fun PaSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.uppercase(),
            style = PaType.overline,
            color = PaColor.TextTertiary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** 标签语义色 */
enum class PaBadgeTone { Neutral, Accent, Success, Warning, Danger }

/** 小标签。用于风险等级、状态、计数 */
@Composable
fun PaBadge(
    text: String,
    tone: PaBadgeTone = PaBadgeTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val color = when (tone) {
        PaBadgeTone.Neutral -> PaColor.TextSecondary
        PaBadgeTone.Accent -> PaColor.Accent
        PaBadgeTone.Success -> PaColor.Success
        PaBadgeTone.Warning -> PaColor.Warning
        PaBadgeTone.Danger -> PaColor.Danger
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(PaRadius.pill))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = PaSpace.xs, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = text, style = PaType.label, color = color)
    }
}

// ═══════════════════════════════════════════════════════════════
//  空状态
// ═══════════════════════════════════════════════════════════════

/**
 * 空状态。
 *
 * ⚠️ 刻意**不放插画**。大多数空状态插画只是把"这里没东西"这个信息
 *    用 200KB 的图片重说一遍。一句话 + 一个动作就够了，也更符合"简约"。
 */
@Composable
fun PaEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PaSpace.l, vertical = PaSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = PaType.headline,
            color = PaColor.TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(PaSpace.xs))
            Text(
                text = description,
                style = PaType.body,
                color = PaColor.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(PaSpace.l))
            PaButton(text = actionText, onClick = onAction, style = PaButtonStyle.Glass)
        }
    }
}

/** 标准列表项内边距。集中定义，避免每个页面各写一套 */
val PaListItemPadding = PaddingValues(
    horizontal = PaSpace.m,
    vertical = PaSpace.s,
)
