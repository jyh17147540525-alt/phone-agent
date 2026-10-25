package com.pocketagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaColorDark
import com.pocketagent.ui.design.PaType

/**
 * 应用主题 —— 把 [PaColor] 设计令牌接到 Material3 上。
 *
 * ═══════════════════════════════════════════════════════════════
 *  本文件曾经用 dynamicDarkColorScheme()，现已移除
 * ═══════════════════════════════════════════════════════════════
 *
 * 动态取色（Material You）会跟着用户壁纸给出配色。它在"实用工具"类应用上
 * 说得过去，但它是**高级感的天敌**：同一个界面在十台手机上呈现十种配色，
 * 就不存在"设计"了，只剩下"系统给我的颜色"。而且动态取色倾向给出高饱和的
 * 糖果色，与本应用"风险提示必须克制"的基调直接冲突。
 *
 * 详见 [PaColor] 顶部注释的设计立场。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么还要接 Material3 的 ColorScheme
 * ═══════════════════════════════════════════════════════════════
 *
 * 新的 UI 层（`ui/design` 下的组件）**直接读 [PaColor]，不走 MaterialTheme**。
 * 但旧的页面（市场 / 导入 / 订阅源）仍在用 Material3 的 `Card` / `Button` /
 * `Scaffold`，它们读的是 `MaterialTheme.colorScheme`。
 *
 * 把 ColorScheme 映射成同一套令牌，这些旧页面就能**自动**跟上新配色 ——
 * 底色、主色、错误色都对齐了，只有组件形态还是旧的。
 * 这样迁移可以逐个页面做，而不是"全改完才能编译"。
 *
 * 等旧页面全部迁移完，这个映射可以退化成最小实现（只留 error 给对话框）。
 */
private val DarkColors = darkColorScheme(
    primary = PaColor.Accent,
    onPrimary = Color(0xFF06121F),
    primaryContainer = PaColor.AccentSoft,
    onPrimaryContainer = PaColor.Accent,
    secondary = PaColor.TextSecondary,
    onSecondary = PaColor.TextPrimary,
    secondaryContainer = PaColor.SurfaceHigh,
    onSecondaryContainer = PaColor.TextPrimary,
    tertiary = PaColor.Accent,
    onTertiary = Color(0xFF06121F),
    background = PaColor.Canvas,
    onBackground = PaColor.TextPrimary,
    surface = PaColor.Surface,
    onSurface = PaColor.TextPrimary,
    surfaceVariant = PaColor.SurfaceHigh,
    onSurfaceVariant = PaColor.TextSecondary,
    surfaceContainer = PaColor.CanvasElevated,
    surfaceContainerHigh = PaColor.Surface,
    surfaceContainerHighest = PaColor.SurfaceHigh,
    outline = PaColor.GlassBorder,
    outlineVariant = PaColor.GlassBorder,
    error = PaColor.Danger,
    onError = Color(0xFF1A0505),
    errorContainer = Color(0x26FF5C5C),
    onErrorContainer = PaColor.Danger,
)

private val LightColors = lightColorScheme(
    primary = PaColor.Accent,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = PaColor.AccentSoft,
    onPrimaryContainer = PaColor.Accent,
    secondary = PaColor.TextSecondary,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = PaColor.SurfaceHigh,
    onSecondaryContainer = PaColor.TextPrimary,
    tertiary = PaColor.Accent,
    onTertiary = androidx.compose.ui.graphics.Color.White,
    background = PaColor.Canvas,
    onBackground = PaColor.TextPrimary,
    surface = PaColor.Surface,
    onSurface = PaColor.TextPrimary,
    surfaceVariant = PaColor.SurfaceHigh,
    onSurfaceVariant = PaColor.TextSecondary,
    surfaceContainer = PaColor.CanvasElevated,
    surfaceContainerHigh = PaColor.Surface,
    surfaceContainerHighest = PaColor.SurfaceHigh,
    outline = PaColor.GlassBorder,
    outlineVariant = PaColor.GlassBorder,
    error = PaColor.Danger,
    onError = androidx.compose.ui.graphics.Color.White,
    errorContainer = Color(0x1FB5443A),
    onErrorContainer = PaColor.Danger,
)

/**
 * 字体层级映射。
 *
 * ⚠️ 只映射 **8 个** 字段，其余保持 Material 默认。
 *
 * 理由：Material3 的 Typography 有 15 档，而本设计只有 7 档（见 [PaType]）。
 * 硬凑 15 档会造出一堆"看起来一样但值不同"的样式，那是维护灾难。
 * 只映射旧页面**实际用到**的那几档，剩下的一旦被用到就会明显不搭 ——
 * 这是个**故意的信号**：它说明那个页面还没迁移。
 */
private fun buildTypography(): Typography {
    val base = Typography()
    return base.copy(
        displaySmall = PaType.display,
        headlineSmall = PaType.title,
        titleLarge = PaType.title,
        titleMedium = PaType.headline,
        titleSmall = PaType.headline,
        bodyLarge = PaType.body,
        bodyMedium = PaType.body,
        bodySmall = PaType.caption,
        labelLarge = PaType.label,
        labelMedium = PaType.label,
        labelSmall = PaType.overline,
    )
}

private val AppTypography = buildTypography()

/**
 * 应用主题。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-25：`darkTheme` 默认改为 **false**（浅色）
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求 Claude 风格的审美。而 Claude 的识别度**全部建立在暖色浅底上**：
 * 米色纸感、衬线标题、陶土强调色。这三样换到深色会**同时失效** ——
 * 深色里衬线标题显得过于庄重、暖色强调糊在黑底上、纸感无从谈起。
 *
 * ⚠️ 所以这不是"把默认值从 true 改成 false"这么简单，
 *    而是**主设计对象换了**：浅色是打磨对象，深色是可用的备选。
 *    这与本文件历史上的立场相反，那一段保留在下面供追溯。
 *
 * ═══════════════════════════════════════════════════════════════
 *  （历史立场，已被上面的决定取代）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. 深色曾是唯一打磨对象 —— 现在浅色是。
 * 2. 极光背景曾是深色设计的一部分（高饱和光斑叠在近黑底上）；
 *    浅底版必须改用**极低饱和的暖色晕染**，否则会变成脏色块
 *    （见 `PaColor.Aurora*` 的注释）。
 * 3. **不跟随系统明暗**这条立场**保留**：理由与放弃动态取色相同 ——
 *    既然要固定设计，就连明暗一起固定。跟随系统等于把设计权交出去。
 */
@Composable
fun PocketAgentTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
