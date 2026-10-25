package com.pocketagent.ui.design

import androidx.compose.ui.graphics.Color
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
object PaColor {

    // ── 基底：暖米色，靠**亮度**建立层级（不是靠加灰）──────────
    //
    // ⚠️ 关键：三档之间是"纸张叠起来"的差别，不是"灰色深浅"。
    //    如果 Surface 做成偏冷的纯白，暖底整体就会显得脏。
    /** 最底层：暖米。比纯白柔和，长时间看不刺眼 */
    val Canvas = Color(0xFFF5F2EC)
    /** 抬升一档：接近纸白，用于分区背景 */
    val CanvasElevated = Color(0xFFFAF8F4)
    /** 卡片/列表项：纯白（在暖底上"浮起来"靠的是白，不是阴影） */
    val Surface = Color(0xFFFFFFFF)
    /** 卡片内的次级块 */
    val SurfaceHigh = Color(0xFFF0ECE4)

    // ── 卡片质感：浅底不用玻璃，用**发丝边** ────────────────────
    //
    // ⚠️ 深色那套"5% 白 + 12% 白描边"的玻璃在浅底上完全看不见。
    //    浅底的高级感来自**极细的暖灰描边**，而不是半透明叠加。
    /** 卡片主体不需要叠加色 —— 白就是白 */
    val GlassTint = Color(0x00000000)
    val GlassTintStrong = Color(0x0A000000)
    /** 卡片描边：暖灰，透明度低到几乎看不见 —— 但它撑起了卡片的"实体感" */
    val GlassBorder = Color(0x14000000)
    /** 浅底下不需要高光/暗边（那是模拟玻璃厚度的手法） */
    val GlassHighlight = Color(0x00000000)
    val GlassShadow = Color(0x0A000000)

    // ── 文字：四级，暖调深色 ───────────────────────────────────
    //
    // ⚠️ 不用纯黑：暖底 + 纯黑会产生刺眼的高对比，
    //    而那种"纸感"恰恰来自**降低对比、加大行距**。
    /** 近黑但偏暖，不是 #000000 */
    val TextPrimary = Color(0xFF1F1D1A)
    val TextSecondary = Color(0xFF6B655C)
    val TextTertiary = Color(0xFF9A938A)
    val TextDisabled = Color(0xFFC4BDB2)

    // ── 强调色：陶土/珊瑚（招牌色）─────────────────────────────
    //
    // ⚠️ 这不是"随便挑一个暖色"，它必须在三个位置都成立：
    //    ① 白底上的小字（对比度要够）
    //    ② 选中态的低透明底（不能脏）
    //    ③ 与语义色红/橙**分得开**（否则"强调"和"危险"混了）
    val Accent = Color(0xFFC96442)
    val AccentPressed = Color(0xFFAE5236)
    /** 强调色的低透明底，用于选中态背景 */
    val AccentSoft = Color(0x14C96442)
    val AccentBorder = Color(0x3DC96442)

    // ── 语义色 ─────────────────────────────────────────────────
    // ⚠️ 这四个**不允许**被主题或动态取色覆盖。
    //    用户是靠颜色形成条件反射的：看到红=危险。
    //    浅底上要压暗一档，否则刺眼。
    val Success = Color(0xFF3D8A5F)
    val Warning = Color(0xFFB07425)
    val Danger = Color(0xFFB5443A)
    val Info = Color(0xFF4A6B8A)

    // ── 风险等级：四档，**只此一套，不随主题漂移** ──────────────
    //
    // ⚠️ 为什么不直接复用语义色：语义色只有三档，而风险等级有四档
    //    （低/中/高/极高），且必须四档一眼能分开 ——
    //    用户靠这套颜色形成条件反射判断插件危不危险，
    //    这是本应用最重要的一道人工防线（详见 Risk.kt）。
    val RiskLow = Success
    val RiskMedium = Warning
    /** 高：橙与正红之间的过渡色，专门为「高」这一档存在 */
    val RiskHigh = Color(0xFFC4703A)
    val RiskCritical = Danger

    // ── 极光光斑：**浅底版** ────────────────────────────────────
    //
    // ⚠️ 深色那套高饱和光斑放到米色底上会变成脏色块。
    //    浅底要用**极低饱和的暖色晕染**，像水彩在纸上化开；
    //    强度还必须大幅降低 —— 浅底对色偏极其敏感。
    val AuroraBlue = Color(0xFFE8DFD2)
    val AuroraViolet = Color(0xFFEFE4DC)
    val AuroraTeal = Color(0xFFE4E8DE)
}

/**
 * 深色配色 —— **本版不再作为主打**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它存在，但请不要用它来评判本应用的设计
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户选了 Claude 风格，而 Claude 的识别度**全部建立在暖色浅底上** ——
 * 衬线标题、陶土强调色、纸感留白，换到深色全部失效
 * （深色里衬线标题显得过于庄重，暖色强调会糊在黑底上）。
 *
 * ⇒ 这里保留一套**可用**的深色值（供夜间浏览不刺眼），
 *   但主题默认值是浅色。见 `PocketAgentTheme`。
 *
 * 如果将来要做深色为主的设计，那是**另一套设计**，不是这套的取反。
 */
object PaColorDark {
    val Canvas = Color(0xFF1A1816)
    val CanvasElevated = Color(0xFF221F1C)
    val Surface = Color(0xFF262320)
    val SurfaceHigh = Color(0xFF302C28)

    val GlassTint = Color(0x0DFFFFFF)
    val GlassTintStrong = Color(0x1AFFFFFF)
    val GlassBorder = Color(0x1FFFFFFF)
    val GlassHighlight = Color(0x26FFFFFF)
    val GlassShadow = Color(0x40000000)

    val TextPrimary = Color(0xFFF2EDE6)
    val TextSecondary = Color(0xFFA8A199)
    val TextTertiary = Color(0xFF736C64)
    val TextDisabled = Color(0xFF4A453F)

    val Accent = Color(0xFFD9785A)
    val AccentPressed = Color(0xFFC96442)
    val AccentSoft = Color(0x1FD9785A)
    val AccentBorder = Color(0x4DD9785A)

    val Success = Color(0xFF5FA97D)
    val Warning = Color(0xFFD09448)
    val Danger = Color(0xFFD96A5F)
    val Info = Color(0xFF7E9DB8)
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
