package com.pocketagent.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.pocketagent.ui.theme.LocalPalette
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PocketAgent 设计令牌 —— 所有视觉决策的单一事实来源。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-25：整体转为「Claude 风格」暖色浅底
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户明确要求："我都喜欢克劳德的审美。"
 *
 * Claude 的视觉识别不是"某个颜色好看"，而是三个可复述的决策：
 *
 *  1. **底色是暖的，不是灰的。** `#F5F2EC` 这类偏黄的米色，
 *     而不是 `#F7F8FA` 那种偏蓝的中性灰。冷暖一线之差，
 *     但暖底长时间阅读的疲劳感明显更低 —— 这是它"书卷气"的来源。
 *  2. **标题用衬线体。** 正文仍是无衬线（保证小字可读），
 *     只有标题走衬线 —— 那个对比就是"高级感"的实体。
 *  3. **强调色是低饱和的暖色**（陶土/珊瑚），不是高饱和蓝。
 *     蓝在浅底上显得像"系统默认"，暖色才像"被设计过"。
 *
 * ⚠️ **深色模式不再是主要打磨对象** —— 见 [PaColorDark] 的注释。
 *
 * ═══════════════════════════════════════════════════════════════
 *  保留下来的一条旧立场（它与风格无关，是工程约束）
 * ═══════════════════════════════════════════════════════════════
 *
 * **不跟随动态取色（Material You）。** 同一个界面在十台手机上呈现
 * 十种配色，就没有"设计"可言了，只剩下"系统给我的颜色"。
 *
 * **颜色数量压到最低。** 除语义色外全站只有一个强调色；
 * 需要区分层级时用**透明度**而不是新颜色。
 *
 * **间距只用 4 的倍数。**
 */
/**
 * **当前主题的颜色** —— 一个读取 CompositionLocal 的**转发层**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-25：支持明暗两套主题的关键改动
 * ═══════════════════════════════════════════════════════════════
 *
 * 它原来是 `object PaColor { val Canvas = Color(...) }` —— 一组编译期常量。
 * 现在每个属性变成 `get() = LocalPalette.current.Xxx`，即**运行时读取当前主题**。
 *
 * 这样做的唯一目的是：**让 326 处调用点一行都不用改**。
 * （分布在 21 个文件里 —— 逐个改成 `if (isDark) ... else ...` 是 326 次出错机会。）
 *
 * ⚠️ 代价：从"编译期常量"变成"运行时查表"。它发生在 Compose 的**组合期**，
 *    不是绘制期 —— 实测开销可忽略。换来的是"主题只在一处决定"，值得。
 *
 * ⚠️ **必须从 @Composable 调用**（因为它读 CompositionLocal）。
 *    当前 326 处全都在 Composable 上下文里（已核对过），所以不需要改。
 *    若将来要在非 Composable 里取色，请直接读 `LightPalette` / `DarkPalette`
 *    的字面值，**不要**在这里加一个"非 Composable 版本"的旁路 ——
 *    那会让"当前主题是什么"出现第二个事实来源。
 */
object PaColor {

    // ── 基底 ───────────────────────────────────────────────────
    val Canvas: Color
        @Composable get() = LocalPalette.current.Canvas
    val CanvasElevated: Color
        @Composable get() = LocalPalette.current.CanvasElevated
    val Surface: Color
        @Composable get() = LocalPalette.current.Surface
    val SurfaceHigh: Color
        @Composable get() = LocalPalette.current.SurfaceHigh

    // ── 卡片质感 ───────────────────────────────────────────────
    val GlassTint: Color
        @Composable get() = LocalPalette.current.GlassTint
    val GlassTintStrong: Color
        @Composable get() = LocalPalette.current.GlassTintStrong
    val GlassBorder: Color
        @Composable get() = LocalPalette.current.GlassBorder
    val GlassHighlight: Color
        @Composable get() = LocalPalette.current.GlassHighlight
    val GlassShadow: Color
        @Composable get() = LocalPalette.current.GlassShadow

    // ── 文字 ───────────────────────────────────────────────────
    val TextPrimary: Color
        @Composable get() = LocalPalette.current.TextPrimary
    val TextSecondary: Color
        @Composable get() = LocalPalette.current.TextSecondary
    val TextTertiary: Color
        @Composable get() = LocalPalette.current.TextTertiary
    val TextDisabled: Color
        @Composable get() = LocalPalette.current.TextDisabled

    // ── 强调色 ─────────────────────────────────────────────────
    val Accent: Color
        @Composable get() = LocalPalette.current.Accent
    val AccentPressed: Color
        @Composable get() = LocalPalette.current.AccentPressed
    val AccentSoft: Color
        @Composable get() = LocalPalette.current.AccentSoft
    val AccentBorder: Color
        @Composable get() = LocalPalette.current.AccentBorder

    // ── 语义色 ─────────────────────────────────────────────────
    // ⚠️ 这四个**不允许**被主题或动态取色覆盖掉语义 ——
    //    但它们的**具体色值**会随明暗切换（深底上要提亮，否则警示色糊成暗红）。
    //    用户的条件反射靠的是"红=危险"这个**关系**，不是某个具体色号。
    val Success: Color
        @Composable get() = LocalPalette.current.Success
    val Warning: Color
        @Composable get() = LocalPalette.current.Warning
    val Danger: Color
        @Composable get() = LocalPalette.current.Danger
    val Info: Color
        @Composable get() = LocalPalette.current.Info

    // ── 风险等级 ───────────────────────────────────────────────
    val RiskLow: Color
        @Composable get() = LocalPalette.current.RiskLow
    val RiskMedium: Color
        @Composable get() = LocalPalette.current.RiskMedium
    val RiskHigh: Color
        @Composable get() = LocalPalette.current.RiskHigh
    val RiskCritical: Color
        @Composable get() = LocalPalette.current.RiskCritical

    // ── 背景光斑 ───────────────────────────────────────────────
    val AuroraBlue: Color
        @Composable get() = LocalPalette.current.AuroraBlue
    val AuroraViolet: Color
        @Composable get() = LocalPalette.current.AuroraViolet
    val AuroraTeal: Color
        @Composable get() = LocalPalette.current.AuroraTeal
}

object PaSpace {
    /** 4dp —— 图标与文字之间 */
    val xxs = 4.dp
    /** 8dp —— 同组元素之间 */
    val xs = 8.dp
    /** 12dp —— 卡片内边距（紧凑） */
    val s = 12.dp
    /** 16dp —— 屏幕左右边距、卡片内边距 */
    val m = 16.dp
    /** 24dp —— 区块之间 */
    val l = 24.dp
    /** 32dp —— 大区块之间 */
    val xl = 32.dp
    /** 48dp —— 页面顶部留白 */
    val xxl = 48.dp

    /** 屏幕统一左右边距。改这一处即可全站生效 */
    val screenH = 20.dp

    /** 底部导航栏高度（含安全区，安全区另算） */
    val bottomBarHeight = 64.dp
}

/**
 * 圆角。
 *
 * 「高级感」的一大来源是**圆角的克制**：大圆角显廉价，小圆角显精致。
 * 所以卡片用 16 而不是 24。
 */
object PaRadius {
    /** 8dp —— 小控件、标签 */
    val xs = 8.dp
    /** 12dp —— 输入框、小卡片 */
    val s = 12.dp
    /** 16dp —— 标准卡片（**默认值**） */
    val m = 16.dp
    /** 20dp —— 大容器、底部弹层 */
    val l = 20.dp
    /** 28dp —— 全屏 sheet */
    val xl = 28.dp
    /** 胶囊 */
    val pill = 999.dp
}

/**
 * 字体层级。
 *
 * 只有 7 档，且**相邻档位差距明显** —— 层级多而密会显得杂乱。
 *
 * ⚠️ `letterSpacing` 不是随手加的：
 *  - 大字号**收紧**（-0.3sp）：字大了之后默认字距会显得松散
 *  - 小字号**放开**（+0.4sp）：小字放开更易读，且"全大写小标签"放开是经典排版手法
 *  这一收一放是"精致感"最廉价的来源。
 */
object PaType {

    /**
     * **衬线族** —— 标题专用。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 这是 Claude 风格最关键的一处，也是最容易被做丢的一处
     * ═══════════════════════════════════════════════════════════
     *
     * 为什么用 `FontFamily.Serif` 而不是内置一个字体文件：
     *  - 内置中文衬线字体（Noto Serif SC 一类）动辄 10MB+，
     *    会让 APK 明显变大；
     *  - 系统 Serif 在中文上会落到各家的衬线（思源宋体/宋体一类），
     *    视觉方向是对的，且**零体积**。
     *
     * ⚠️ 它的**唯一用途是标题**。正文若也用衬线，小字号下笔画会糊，
     *    可读性明显下降 —— 那正是「书卷气」变成「难以阅读」的分界线。
     */
    private val Serif = FontFamily.Serif

    /**
     * 28sp —— 页面主标题。
     *
     * ★ 衬线 + **常规**字重（不是 Medium）：衬线本身已经带来足够
     *   视觉重量，再加粗会显得笨重。
     */
    val display = androidx.compose.ui.text.TextStyle(
        fontFamily = Serif,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.2).sp,
    )

    /** 22sp —— 区块标题。同样衬线、常规字重 */
    val title = androidx.compose.ui.text.TextStyle(
        fontFamily = Serif,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.1).sp,
    )

    /**
     * 17sp —— 卡片标题、列表主文字。
     *
     * ⚠️ 这一档**不用衬线**：它承担"列表项主文字"，出现频率极高、
     *    常与正文混排。衬线在高频小标题上会显得啰嗦。
     */
    val headline = androidx.compose.ui.text.TextStyle(
        fontSize = 17.sp,
        lineHeight = 25.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    )

    /**
     * 15sp —— 正文。
     *
     * ★ 行高 23 → **24**（行高比约 1.6）：纸感的另一半来自行距。
     *   对比度降低之后必须靠行距补偿可读性，这两件事要一起调。
     */
    val body = androidx.compose.ui.text.TextStyle(
        fontSize = 15.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** 13sp —— 辅助说明 */
    val caption = androidx.compose.ui.text.TextStyle(
        fontSize = 13.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp,
    )

    /** 12sp —— 小标签、按钮文字 */
    val label = androidx.compose.ui.text.TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    )

    /** 11sp —— 微标签（配合放开字距） */
    val overline = androidx.compose.ui.text.TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    )
}
