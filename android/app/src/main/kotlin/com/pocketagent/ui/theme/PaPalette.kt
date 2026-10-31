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

    // ══════════════════════════════════════════════════════════
    //  ★★ 2026-10-31：`Accent` 拆成"背景用"与"文字用"两个令牌
    // ══════════════════════════════════════════════════════════
    //
    // 审计发现：`Accent`(#C96442) 作为**文字**在暖米底上只有 **3.49**，
    // 低于 AA 的 4.5。而它在界面上**既作按钮背景、又作链接/选中文字**。
    //
    // ⚠️ 不能简单把它调深：作背景时 #C96442 上浮着**深色文字**
    //    （`Color(0xFF06121F)`），背景一深，那个文字反而看不清了。
    //
    // ⇒ 拆成两个令牌：
    //    · `Accent`      —— 背景用，保持 #C96442（按钮、选中块）
    //    · `AccentText`  —— 文字用，#A34A28（5.27，AA 通过）
    //
    // ★ 这不是"多一个令牌"，而是**区分了两种用途**。
    //   一个颜色不可能同时满足"背景够浅（衬托深色文字）"和
    //   "文字够深（压在浅底上）"—— 那是物理约束，不是设计偏好。
    val Accent: Color,
    val AccentPressed: Color,
    val AccentSoft: Color,
    val AccentBorder: Color,

    /** 强调色**作为文字**时用（比 [Accent] 深，保证 AA）。 */
    val AccentText: Color,

    // ══════════════════════════════════════════════════════════
    //  ★★ 语义色同样按 AA 重新定值
    // ══════════════════════════════════════════════════════════
    //
    // 实测（底 F5F2EC）：
    //   Success #3D8A5F → 3.76  ✗
    //   Warning #B07425 → 3.50  ✗
    //   Danger  #B5443A → 4.88  ✓（本来就合格）
    //   Info    #4A6B8A → 5.00  ✓
    //
    // ⇒ Success → #2F6E4A (5.45)，Warning → #8A5A1C (5.28)。
    //   ⚠️ Danger / Info 不动 —— 它们已经合格，改了只是白白让
    //      界面颜色变陌生（用户已经习惯了那个红/那个蓝）。
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

/**
 * 当前是不是深色主题。
 *
 * ★ 为什么需要它：有些绘制（记忆星云、极光背景）不是"取一个品牌色"，
 *   而是要**按明暗选一整套色板** —— 浅底靠"深"吸引注意力，暗底靠"亮"，
 *   两者不是同一套颜色调亮或调暗就能互通的。
 *
 * ⚠️ 刻意不提供"从 `LocalPalette.Canvas` 反推明暗"那种捷径：
 *   亮度阈值是个魔数，换主题就失效，而且失效时是**静默**的
 *   （只是某一天星云在浅色下又看不清了）。
 */
val LocalIsDark = staticCompositionLocalOf { false }

/** **浅色** —— Claude 风格的暖米底。默认主题。 */
val LightPalette = PaPalette(
    Canvas = com.pocketagent.agentui.ColorTokens.Light.CANVAS.toColor(),
    CanvasElevated = Color(0xFFFAF8F4),
    Surface = com.pocketagent.agentui.ColorTokens.Light.SURFACE.toColor(),
    SurfaceHigh = Color(0xFFF0ECE4),

    GlassTint = Color(0x00000000),
    GlassTintStrong = Color(0x0A000000),
    GlassBorder = Color(0x14000000),
    GlassHighlight = Color(0x00000000),
    GlassShadow = Color(0x0A000000),

    // ══════════════════════════════════════════════════════════
    //  ★★ 2026-10-31：文字三档按 **WCAG AA 对比度**重新定值
    // ══════════════════════════════════════════════════════════
    //
    // 审计来源：`design-tokens-template` 的 "Verify contrast for every
    // text/background combination" 一节。本项目从未做过这件事。
    //
    // 实测（底 F5F2EC）：
    //   TextPrimary   #1F1D1A  → 15.05  AA ✓  AAA ✓
    //   TextSecondary #6B655C  →  5.16  AA ✓  AAA ✗   ← 勉强
    //   TextTertiary  #9A938A  →  2.72  AA ✗  ✗✗✗     ← **不合格**
    //   TextDisabled  #C4BDB2  →  1.79  （禁用态，不要求 AA）
    //
    // ⚠️ **TextTertiary 不合格**，而它被用了 **77 处** ——
    //    是本项目最大面积的可访问性缺陷：说明文字在暖米底上发灰，
    //    强光下、小字号、或视力不佳时读不清。
    //
    // ⚠️⚠️ 但**不能只调 Tertiary**：把它提到 4.5 需要 #706B62，
    //    而那与 Secondary(#6B655C) 的亮度差只剩 **0.0164** ——
    //    两档几乎一模一样，**层级消失了**。
    //
    // ⇒ 正确做法是**三档一起重排**，拉开每档的间距：
    //
    //     档位        颜色       对比度   亮度
    //     Primary    #1F1D1A    15.05    0.0126
    //     Secondary  #5A554D     6.61    0.0864
    //     Tertiary   #706B62     4.74    0.1428
    //
    //     Secondary↔Tertiary 亮度差 **0.0564**，是原来的 **3.4 倍** ——
    //     层级比改之前更分明，而且三档全部通过 AA。
    //
    // ★ 这比"照抄一个合规值"多花了一步，但那就是设计判断与
    //   机械替换的区别：合规是**约束**，层级是**目的**，
    //   满足约束的同时不能把目的弄丢。
    TextPrimary = com.pocketagent.agentui.ColorTokens.Light.TEXT_PRIMARY.toColor(),
    TextSecondary = com.pocketagent.agentui.ColorTokens.Light.TEXT_SECONDARY.toColor(),
    TextTertiary = com.pocketagent.agentui.ColorTokens.Light.TEXT_TERTIARY.toColor(),
    TextDisabled = Color(0xFFC4BDB2),

    Accent = com.pocketagent.agentui.ColorTokens.Light.ACCENT.toColor(),
    AccentPressed = Color(0xFFAE5236),
    AccentSoft = Color(0x14C96442),
    AccentBorder = Color(0x3DC96442),
    // ★ 文字用强调色：#A34A28 在 F5F2EC 上是 5.27（AA ✓）。
    //   原 #C96442 只有 3.49 —— 见接口里的注释。
    AccentText = com.pocketagent.agentui.ColorTokens.Light.ACCENT_TEXT.toColor(),

    // ★ Success / Warning 提到 AA（原值 3.76 / 3.50 不合格）；
    //   Danger / Info 本来就合格，不动。
    Success = com.pocketagent.agentui.ColorTokens.Light.SUCCESS.toColor(),
    Warning = com.pocketagent.agentui.ColorTokens.Light.WARNING.toColor(),
    Danger = com.pocketagent.agentui.ColorTokens.Light.DANGER.toColor(),
    Info = com.pocketagent.agentui.ColorTokens.Light.INFO.toColor(),

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
    Canvas = com.pocketagent.agentui.ColorTokens.Dark.CANVAS.toColor(),
    CanvasElevated = Color(0xFF221F1C),
    Surface = com.pocketagent.agentui.ColorTokens.Dark.SURFACE.toColor(),
    SurfaceHigh = Color(0xFF302C28),

    GlassTint = Color(0x0DFFFFFF),
    GlassTintStrong = Color(0x1AFFFFFF),
    GlassBorder = Color(0x1FFFFFFF),
    GlassHighlight = Color(0x26FFFFFF),
    GlassShadow = Color(0x40000000),

    // ★★ 2026-10-31：深色三档同样按 WCAG AA 重排（见 LightPalette 的详细注释）。
    //
    // 实测（底 1A1816）：
    //   TextPrimary   #F2EDE6  → 15.20  AA ✓
    //   TextSecondary #A8A199  →  6.93  AA ✓   ← 本来就合格，但因为浅色
    //                                           主题把 Secondary 提亮了，
    //                                           这边也提一点保持"两套主题
    //                                           层级感一致"
    //   TextTertiary  #736C64  →  3.42  AA ✗   ← **不合格**
    //
    // ⇒ 新值：
    //     Primary    #F2EDE6  15.20   lum 0.8516
    //     Secondary  #B5AEA5   8.06   lum 0.4281
    //     Tertiary   #8A837B   4.73   lum 0.2307
    //
    //   Secondary↔Tertiary 亮度差 0.132。
    //
    // ⚠️ Tertiary 从 #8A837B 改成 **#958E86**：前者在 Canvas 上合格（4.73），
    //    但在 **Surface(#262320)** 上只有 4.18 —— 因为 Surface 比 Canvas 亮，
    //    同一支颜料在更亮的底上对比度更低。
    //    ⇒ 深色 Tertiary 必须按**两种底色里更亮的那个**来定值。
    //      差一步就会留下"列表卡片里的说明文字偏淡"这种局部缺陷。
    //
    // ⚠️ 深色主题的"提亮"方向与浅色相反：浅色是把颜色**调深**才有对比度，
    //   深色是**调亮**。同一组色值在两种主题下不可能通用 —— 这也是
    //   为什么本项目用两套 Palette 而不是一套加透明度。
    TextPrimary = com.pocketagent.agentui.ColorTokens.Dark.TEXT_PRIMARY.toColor(),
    TextSecondary = com.pocketagent.agentui.ColorTokens.Dark.TEXT_SECONDARY.toColor(),
    TextTertiary = com.pocketagent.agentui.ColorTokens.Dark.TEXT_TERTIARY.toColor(),
    TextDisabled = Color(0xFF4A453F),

    Accent = com.pocketagent.agentui.ColorTokens.Dark.ACCENT.toColor(),
    AccentPressed = Color(0xFFC96442),
    AccentSoft = Color(0x1FD9785A),
    AccentBorder = Color(0x4DD9785A),
    // ★ 深色主题下 `Accent`(#D9785A) 作为文字是 **5.72** —— 已经过 AA。
    //   ⇒ `AccentText` 直接复用同一个值，不需要像浅色那样分两档。
    //
    // ⚠️ 这不是"偷懒"：深色底天然对比度高（同一颜色在深底上的对比度
    //    远高于浅底），所以"背景色"和"文字色"能用同一支颜料。
    //    浅色底才必须拆开 —— 那两个约束在物理上冲突。
    AccentText = com.pocketagent.agentui.ColorTokens.Dark.ACCENT_TEXT.toColor(),

    Success = com.pocketagent.agentui.ColorTokens.Dark.SUCCESS.toColor(),
    Warning = com.pocketagent.agentui.ColorTokens.Dark.WARNING.toColor(),
    Danger = com.pocketagent.agentui.ColorTokens.Dark.DANGER.toColor(),
    Info = com.pocketagent.agentui.ColorTokens.Dark.INFO.toColor(),

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

/**
 * **0xRRGGBB → Compose `Color`**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31：色值来源的分层
 * ═══════════════════════════════════════════════════════════════
 *
 * 色值的**唯一事实来源**在 `:agentui/ColorTokens`（纯 Kotlin 整数）——
 * 因为只有那里进得了离线验证器，对比度测试才能守住真实的值。
 *
 * 本文件只负责把它们转成 Compose 的 `Color`。
 *
 * ⚠️ 所以这里**不该再出现任何 0xFFxxxxxx 字面量**（除了语义色里
 *   尚未迁移的那些）。一旦出现，就多了一处可以独自漂移的真相来源 ——
 *   而那正是这套测试要防的事。
 *
 * ⚠️ 加 `0xFF000000 or`：`Color(Int)` 要的是 ARGB，
 *    而 ColorTokens 存的是 RGB。不补 alpha 会让所有颜色变透明。
 */
private fun Int.toColor(): Color = Color(0xFF000000L.toInt() or this)
