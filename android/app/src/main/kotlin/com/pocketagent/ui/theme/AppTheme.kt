package com.pocketagent.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pocketagent.ui.design.PaColor

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
@Composable
private fun darkColors() = darkColorScheme(
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

@Composable
private fun lightColors() = lightColorScheme(
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
 *  ★★ 2026-10-25：支持明暗两套主题，默认**跟随系统**
 * ═══════════════════════════════════════════════════════════════
 *
 * 此前这里把 `darkTheme` 写死成 false（"浅色是唯一打磨对象"）——
 * 那是为了让 Claude 风格先立起来。现在补上暗色版，并跟随系统。
 *
 * ⚠️ 暗色**不是浅色的取反**：三处重新校准过（见 [DarkPalette] 的注释）。
 *   简单取反会让陶土强调色在深底上糊掉、卡片描边消失、光斑看不见。
 *
 * ⚠️ `PaColor` 现在是**转发层**（读 [LocalPalette]），所以两套主题
 *    对 326 处调用点**完全透明** —— 一行都不用改。
 *
 * @param mode 用户的明暗偏好。默认 [ThemeMode.SYSTEM] = 跟随系统。
 */
@Composable
fun PocketAgentTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    // ★ 跟随系统时读系统的明暗标志。
    //   ⚠️ `isSystemInDarkTheme()` 是 Composable，必须在组合期调用 ——
    //      所以它只能在这里读，不能放进 `mode.toDark()` 那种纯函数里。
    val systemDark = isSystemInDarkTheme()
    val isDark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val palette = if (isDark) DarkPalette else LightPalette
    val colors = if (isDark) darkColors() else lightColors()

    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalIsDark provides isDark,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = AppTypography,
            content = content,
        )
    }
}
