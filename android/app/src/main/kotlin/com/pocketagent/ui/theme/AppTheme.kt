package com.pocketagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaColorLight
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
    primary = PaColorLight.Accent,
    onPrimary = Color.White,
    primaryContainer = PaColorLight.AccentSoft,
    onPrimaryContainer = PaColorLight.Accent,
    secondary = PaColorLight.TextSecondary,
    onSecondary = Color.White,
    secondaryContainer = PaColorLight.SurfaceHigh,
    onSecondaryContainer = PaColorLight.TextPrimary,
    tertiary = PaColorLight.Accent,
    onTertiary = Color.White,
    background = PaColorLight.Canvas,
    onBackground = PaColorLight.TextPrimary,
    surface = PaColorLight.Surface,
    onSurface = PaColorLight.TextPrimary,
    surfaceVariant = PaColorLight.SurfaceHigh,
    onSurfaceVariant = PaColorLight.TextSecondary,
    surfaceContainer = PaColorLight.CanvasElevated,
    surfaceContainerHigh = PaColorLight.Surface,
    surfaceContainerHighest = PaColorLight.SurfaceHigh,
    outline = PaColorLight.GlassBorder,
    outlineVariant = PaColorLight.GlassBorder,
    error = PaColorLight.Danger,
    onError = Color.White,
    errorContainer = Color(0x1FD32F2F),
    onErrorContainer = PaColorLight.Danger,
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
 *  ⚠️ `darkTheme` 默认 **true**，刻意不跟随系统
 * ═══════════════════════════════════════════════════════════════
 *
 * 这不是偷懒，是三个约束共同推出的结果：
 *
 * 1. **深色是本设计的唯一打磨对象。** [PaColorLight] 只给了一套"能用"的值，
 *    没有做同等深度的对比度校准。跟随系统切到浅色，用户看到的是半成品。
 *
 * 2. **极光背景是深色设计的一部分。** [com.pocketagent.ui.design.AuroraBackground]
 *    画的是三团高饱和光斑叠在近黑底上 —— 那是"雾化玻璃"的光源。
 *    换到浅底，光斑会变成脏兮兮的色块，整个玻璃质感直接塌掉。
 *
 * 3. **跟随系统等于把设计权交出去。** 本应用刻意放弃动态取色，理由是
 *    "同一界面在十台手机上十种配色就不叫设计了"（见 [PaColor] 立场第 1 条）。
 *    明暗切换是同一件事 —— 既然要固定，就连明暗一起固定。
 *
 * 将来若要支持浅色，前提是先把 [PaColorLight] 与浅色版极光背景
 * （低饱和光斑 + 白底）都打磨到位，而不是简单地把这个默认值改回
 * `isSystemInDarkTheme()`。
 */
@Composable
fun PocketAgentTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
