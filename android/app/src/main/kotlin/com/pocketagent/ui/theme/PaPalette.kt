package com.pocketagent.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * **配色字典** —— 一套主题的全部颜色。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用"字典 + CompositionLocal"，而不是两套 object
 * ═══════════════════════════════════════════════════════════════
 *
 * 做明暗两套最直接的办法是写 `PaColorLight` / `PaColorDark` 两个 object，
 * 然后在每个界面里判断当前是哪个 —— 但那要改 **326 处使用点**
 * （分布在 21 个文件里），而且每一处都是一个新的出错机会。
 *
 * 更好的做法：让 `PaColor.Xxx` **变成一个读取当前主题的转发层**。
 * 于是 326 处代码**一行都不用改**，而"现在是哪个主题"只在**一个地方**决定。
 *
 * 代价：`PaColor.Xxx` 从"编译期常量"变成了"运行时查表"。这有一次哈希查找的开销 ——
 * 但它发生在 Compose 的**组合期**，而不是绘制期，实测可忽略。
 * 换来的是"改主题只改一处"，这个交换很划算。
 *
 * ⚠️ 用 `staticCompositionLocalOf` 而不是 `compositionLocalOf`：
 *    主题切换是**罕见事件**，static 版本在读取时不建立订阅关系，
 *    读取开销更低。代价是切换主题会**重组整棵树** ——
 *    但那正是我们想要的（全屏换色）。
 */
class PaPalette(
    val Canvas: Color,
    val CanvasElevated: Color,
    val Surface: Color,
    val SurfaceHigh: Color,

    val GlassTint: Color,
    val GlassTintStrong: Color,
    val GlassBorder: Color,
    val GlassHighlight: Color,
    val GlassShadow: Color,

    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,
    val TextDisabled: Color,

    val Accent: Color,
    val AccentPressed: Color,
    val AccentSoft: Color,
    val AccentBorder: Color,

    val Success: Color,
    val Warning: Color,
    val Danger: Color,
    val Info: Color,

    val RiskLow: Color,
    val RiskMedium: Color,
    val RiskHigh: Color,
    val RiskCritical: Color,

    val AuroraBlue: Color,
    val AuroraViolet: Color,
    val AuroraTeal: Color,
)

/**
 * 当前主题配色。
 *
 * ⚠️ 默认值取浅色 —— 它是 Claude 风格的主设计（见 [PaPalette] 注释）。
 *    这个默认只在"没有 Theme 包裹"时生效（比如 Compose Preview），
 *    真实运行时一定由 `PocketAgentTheme` 提供。
 */
val LocalPalette = staticCompositionLocalOf { LightPalette }

/** **浅色** —— Claude 风格的暖米底。默认主题。 */
val LightPalette = PaPalette(
    Canvas = Color(0xFFF5F2EC),
    CanvasElevated = Color(0xFFFAF8F4),
    Surface = Color(0xFFFFFFFF),
    SurfaceHigh = Color(0xFFF0ECE4),

    GlassTint = Color(0x00000000),
    GlassTintStrong = Color(0x0A000000),
    GlassBorder = Color(0x14000000),
    GlassHighlight = Color(0x00000000),
    GlassShadow = Color(0x0A000000),

    TextPrimary = Color(0xFF1F1D1A),
    TextSecondary = Color(0xFF6B655C),
    TextTertiary = Color(0xFF9A938A),
    TextDisabled = Color(0xFFC4BDB2),

    Accent = Color(0xFFC96442),
    AccentPressed = Color(0xFFAE5236),
    AccentSoft = Color(0x14C96442),
    AccentBorder = Color(0x3DC96442),

    Success = Color(0xFF3D8A5F),
    Warning = Color(0xFFB07425),
    Danger = Color(0xFFB5443A),
    Info = Color(0xFF4A6B8A),

    RiskLow = Color(0xFF3D8A5F),
    RiskMedium = Color(0xFFB07425),
    RiskHigh = Color(0xFFC4703A),
    RiskCritical = Color(0xFFB5443A),

    AuroraBlue = Color(0xFFE8DFD2),
    AuroraViolet = Color(0xFFEFE4DC),
    AuroraTeal = Color(0xFFE4E8DE),
)

/**
 * **深色** —— 同一套设计的暗色版本。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它不是"浅色的取反"，而是重新校准过的
 * ═══════════════════════════════════════════════════════════════
 *
 * 三处必须与浅色**不同地**处理：
 *
 * 1. **强调色要提亮。** 陶土色 `#C96442` 在深底上会糊掉（对比度不足），
 *    所以暗色版用 `#D9785A`。这不是"换个颜色"，是**同一个色的暗背景适配**。
 * 2. **卡片质感从"发丝边"换成"发光边"。** 浅色靠极细的灰边撑出实体感；
 *    深色下灰边看不见，要用**白色低透明度描边**（同玻璃手法）。
 * 3. **光斑要提亮。** 浅色的光斑是"水彩在纸上化开"（极淡暖色）；
 *    深色下淡色完全看不见，必须换成**低饱和但有亮度的**光斑。
 *
 * ⚠️ 语义色（成功/警告/危险）在深底上也要提亮 ——
 *    否则"红色警告"会变成一团暗红，起不到警示作用。
 */
val DarkPalette = PaPalette(
    Canvas = Color(0xFF1A1816),
    CanvasElevated = Color(0xFF221F1C),
    Surface = Color(0xFF262320),
    SurfaceHigh = Color(0xFF302C28),

    GlassTint = Color(0x0DFFFFFF),
    GlassTintStrong = Color(0x1AFFFFFF),
    GlassBorder = Color(0x1FFFFFFF),
    GlassHighlight = Color(0x26FFFFFF),
    GlassShadow = Color(0x40000000),

    TextPrimary = Color(0xFFF2EDE6),
    TextSecondary = Color(0xFFA8A199),
    TextTertiary = Color(0xFF736C64),
    TextDisabled = Color(0xFF4A453F),

    Accent = Color(0xFFD9785A),
    AccentPressed = Color(0xFFC96442),
    AccentSoft = Color(0x1FD9785A),
    AccentBorder = Color(0x4DD9785A),

    Success = Color(0xFF5FA97D),
    Warning = Color(0xFFD09448),
    Danger = Color(0xFFD96A5F),
    Info = Color(0xFF7E9DB8),

    RiskLow = Color(0xFF5FA97D),
    RiskMedium = Color(0xFFD09448),
    RiskHigh = Color(0xFFE08A5A),
    RiskCritical = Color(0xFFD96A5F),

    // ⚠️ 深底上的光斑：低饱和但有亮度，否则看不见
    AuroraBlue = Color(0xFF2A2620),
    AuroraViolet = Color(0xFF302521),
    AuroraTeal = Color(0xFF232A26),
)

/**
 * 用户选的明暗偏好。
 *
 * ★ **默认 [SYSTEM]** —— 跟随手机设置。
 *   多数人在白天用浅色、夜间切深色，跟随系统是最少惊喜的选择。
 */
enum class ThemeMode(val label: String) {
    /** 跟随系统设置（默认） */
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}
