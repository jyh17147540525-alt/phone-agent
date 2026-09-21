package com.pocketagent.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PocketAgent 设计令牌 —— 所有视觉决策的单一事实来源。
 *
 * ═══════════════════════════════════════════════════════════════
 *  设计立场（改之前先读完）
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 不跟随动态取色（Material You）。**
 * 之前用的是 `dynamicDarkColorScheme(context)`，它会跟着用户壁纸变色。
 * 这在"实用工具"上没问题，但它是**高级感的天敌** ——
 * 同一个界面在十台手机上呈现十种配色，就没有"设计"可言了，
 * 只剩下"系统给我的颜色"。而且动态取色会给出高饱和的糖果色，
 * 与本应用"风险提示要克制"的基调冲突。
 * → **改为固定配色。** 这是本文件存在的主要理由。
 *
 * **2. 深色是主要打磨对象。**
 * 「极致简约 + 高级」在深色下最容易成立：靠**层级差异**而不是颜色数量来建立结构。
 * 浅色配色给出一套可用的值，但不追求同等打磨深度。
 *
 * **3. 颜色数量刻意压到最低。**
 * 除语义色（成功/警告/危险）外，全站只有**一个强调色**。
 * 需要区分层级时，用**透明度**而不是新颜色 —— 这是"简约"的技术含义。
 *
 * **4. 间距只用 4 的倍数。**
 * 例外：1.dp 用于描边。8pt grid 是廉价的秩序感来源。
 */
object PaColor {

    // ── 基底：三级高度，靠亮度差建立层级，不靠颜色 ──────────────
    /** 最底层。近黑但不纯黑 —— 纯黑在 OLED 上会让卡片"浮不起来" */
    val Canvas = Color(0xFF0A0B0D)
    /** 抬升一档，用于分区背景 */
    val CanvasElevated = Color(0xFF0F1115)
    /** 卡片/列表项 */
    val Surface = Color(0xFF14171C)
    /** 卡片内的次级块（如代码块、嵌套项） */
    val SurfaceHigh = Color(0xFF1B1F25)

    // ── 玻璃：全部用白色透明度叠加，随底层亮度自适应 ────────────
    /** 玻璃主体：5% 白 */
    val GlassTint = Color(0x0DFFFFFF)
    /** 玻璃加强：10% 白，用于需要更明显"实体感"的悬浮层 */
    val GlassTintStrong = Color(0x1AFFFFFF)
    /** 玻璃描边：12% 白。**这条边是玻璃质感的关键**，去掉就变成普通半透明块 */
    val GlassBorder = Color(0x1FFFFFFF)
    /** 顶边高光：模拟光线打在玻璃上边缘 */
    val GlassHighlight = Color(0x26FFFFFF)
    /** 底部暗边：与顶边高光配合形成厚度感 */
    val GlassShadow = Color(0x40000000)

    // ── 文字：四级，靠亮度而非字号建立层级 ─────────────────────
    val TextPrimary = Color(0xFFF2F4F7)
    val TextSecondary = Color(0xFF9BA3AE)
    val TextTertiary = Color(0xFF5F6771)
    val TextDisabled = Color(0xFF3C424A)

    // ── 强调色：全站唯一 ───────────────────────────────────────
    val Accent = Color(0xFF5B9DF9)
    val AccentPressed = Color(0xFF4A86D8)
    /** 强调色的低透明底，用于选中态背景 */
    val AccentSoft = Color(0x1F5B9DF9)
    val AccentBorder = Color(0x4D5B9DF9)

    // ── 语义色 ─────────────────────────────────────────────────
    // ⚠️ 这四个**不允许**被主题或动态取色覆盖。
    //    用户是靠颜色形成条件反射的：看到红=危险。
    //    如果红色在某些手机上变成橙色，条件反射就断了。
    val Success = Color(0xFF3DD68C)
    val Warning = Color(0xFFF5A623)
    val Danger = Color(0xFFFF5C5C)
    val Info = Color(0xFF5B9DF9)

    // ── 风险等级：四档，**只此一套，不随主题漂移** ──────────────
    //
    // ⚠️ 为什么不直接复用上面的语义色：
    //    语义色只有三档（成功/警告/危险），而风险等级有**四档**
    //    （低/中/高/极高），且必须四档一眼能分开 ——
    //    用户是靠这套颜色形成条件反射来判断插件危不危险的，
    //    这是本应用最重要的一道人工防线（详见 Risk.kt）。
    //    低/中/极高沿用语义色的值以保持全站一致，只有「高」是新增的。
    //
    // ⚠️ 这四档**刻意不做浅色版**。风险色的语义就是「不随环境变化」：
    //    同一个插件在市场页是红色、到管理页变成橙色，反射就废了。
    val RiskLow = Success
    val RiskMedium = Warning
    /** 高：黄橙与正红之间的过渡色，专门为「高」这一档存在 */
    val RiskHigh = Color(0xFFFF8A4C)
    val RiskCritical = Danger

    // ── 极光光斑：毛玻璃背后那层被模糊的彩色 ────────────────────
    // 这是"雾化玻璃"观感的来源 —— 玻璃下面是模糊的彩色光，
    // 而不是模糊的纯色。纯色模糊出来是灰雾，彩色模糊才有高级感。
    val AuroraBlue = Color(0xFF2B5CE6)
    val AuroraViolet = Color(0xFF6B3FA0)
    val AuroraTeal = Color(0xFF1E7A6B)
}

/**
 * 浅色配色。
 *
 * 只给出必要值，不做深度打磨 —— 见 [PaColor] 的设计立场第 2 条。
 * 玻璃系列在浅色下改用黑色透明度，语义色保持不变。
 */
object PaColorLight {
    val Canvas = Color(0xFFF7F8FA)
    val CanvasElevated = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceHigh = Color(0xFFF0F2F5)

    val GlassTint = Color(0xB3FFFFFF)
    val GlassTintStrong = Color(0xE6FFFFFF)
    val GlassBorder = Color(0x14000000)
    val GlassHighlight = Color(0x66FFFFFF)
    val GlassShadow = Color(0x14000000)

    val TextPrimary = Color(0xFF0F1115)
    val TextSecondary = Color(0xFF5A6472)
    val TextTertiary = Color(0xFF8B95A3)
    val TextDisabled = Color(0xFFBFC6CF)

    val Accent = Color(0xFF2F6FD0)
    val AccentPressed = Color(0xFF255AB0)
    val AccentSoft = Color(0x1A2F6FD0)
    val AccentBorder = Color(0x4D2F6FD0)

    val Success = Color(0xFF1E9E63)
    val Warning = Color(0xFFB87400)
    val Danger = Color(0xFFD32F2F)
    val Info = Color(0xFF2F6FD0)
}

/**
 * 间距。
 *
 * 只用 4 的倍数。命名按**用途**而不是数值 ——
 * `gapS` 比 `space8` 更抗改：将来把 S 从 8 调到 10，调用点不用动。
 */
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
    /** 28sp / Medium —— 页面主标题 */
    val display = androidx.compose.ui.text.TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.4).sp,
    )

    /** 22sp / Medium —— 区块标题 */
    val title = androidx.compose.ui.text.TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.3).sp,
    )

    /** 17sp / Medium —— 卡片标题、列表主文字 */
    val headline = androidx.compose.ui.text.TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    )

    /** 15sp / Normal —— 正文 */
    val body = androidx.compose.ui.text.TextStyle(
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )

    /** 13sp / Normal —— 辅助说明 */
    val caption = androidx.compose.ui.text.TextStyle(
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp,
    )

    /** 12sp / Medium —— 小标签、按钮文字 */
    val label = androidx.compose.ui.text.TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp,
    )

    /** 11sp / Medium —— 全大写微标签（配合放开字距） */
    val overline = androidx.compose.ui.text.TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    )
}
