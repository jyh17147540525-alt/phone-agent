package com.pocketagent.agentui

/**
 * **配色常量** —— 色值的**唯一事实来源**，住在纯 Kotlin 层。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31：为什么色值要住在 `:agentui` 而不是 `:app`
 * ═══════════════════════════════════════════════════════════════
 *
 * 我先在 `:agentui` 建了 `Contrast` 与一套对比度测试，测试里
 * **硬编码**了色值字面量。然后做变异验证时发现：
 *
 * ```
 * 把 LightPalette.TextTertiary 改回不合格的 #9A938A
 * ⇒ 测试**依然全绿**
 * ```
 *
 * 因为真实的色值在 `:app/ui/theme/PaPalette.kt`，而 `:app` 依赖
 * Android Context，**进不了离线验证器**。
 *
 * ⇒ 那套测试等于"抄了一份值然后自己验证自己" —— 装饰，不是防护。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 修法：色值本身搬到这里
 * ═══════════════════════════════════════════════════════════════
 *
 * 色值是 `0xRRGGBB` 的整数 —— **完全不需要 Android**。
 * 把它定义在纯 Kotlin 层，`:app` 的 Palette 只是把它转成
 * `Color(...)`。于是：
 *   · 对比度测试读的是**真实的值**（同一个常量）
 *   · 改配色会让测试红
 *   · `:app` 里不再有任何色值字面量
 *
 * ⚠️ 这条改动看起来是"为了测试而搬代码"，但它的实质是
 *   **消除两处真相来源**。之前 `:app` 有一套值、测试里抄了一套 ——
 *   两者可以各自漂移而没人发现。
 *
 * ★ 与项目其它纪律一致：`:personalogic`、`:memorylogic`、`:agentlogic`
 *   都是"把判断搬进纯 Kotlin 以便测试"。这次只是把同样的做法
 *   用到了配色上。
 */
object ColorTokens {

    /**
     * **浅色主题** —— Claude 风格的暖米底。
     *
     * 所有值都经过 WCAG AA 审计（见 `ContrastTest`）。
     */
    object Light {
        // ── 基底 ────────────────────────────────────────────
        const val CANVAS = 0xF5F2EC
        const val CANVAS_ELEVATED = 0xFAF8F4
        const val SURFACE = 0xFFFFFF
        const val SURFACE_HIGH = 0xEFEBE4

        // ── 文字三档（AA 全通过，见 ContrastTest）─────────────
        //
        // 审计结论：
        //   Primary   15.05  ✓
        //   Secondary  6.61  ✓（原 5.16，提到与 Tertiary 拉开距离）
        //   Tertiary   4.74  ✓（**原 2.72，不合格**，用了 77 处）
        const val TEXT_PRIMARY = 0x1F1D1A
        const val TEXT_SECONDARY = 0x5A554D
        const val TEXT_TERTIARY = 0x706B62

        /** 禁用态。⚠️ 规范不要求 AA —— 低对比是"它不响应你"的暗示。 */
        const val TEXT_DISABLED = 0xC4BDB2

        // ── 强调色 ──────────────────────────────────────────
        //
        // ⚠️ 两个令牌，用途不同：
        //   ACCENT      作**背景**（按钮、选中块）—— 上面浮深色文字
        //   ACCENT_TEXT 作**文字**（链接、选中态）—— 要有 AA 对比度
        // 一个颜色满足不了两者，见 `PaPalette` 接口的注释。
        const val ACCENT = 0xC96442
        const val ACCENT_PRESSED = 0xAE5236
        const val ACCENT_TEXT = 0xA34A28

        // ── 语义色（AA 全通过）────────────────────────────────
        //
        // Success / Warning 原来是 3.76 / 3.50，不合格，已重定值。
        // Danger / Info 本来就合格，**不动** —— 改了只是让界面变陌生。
        const val SUCCESS = 0x2F6E4A
        const val WARNING = 0x8A5A1C
        const val DANGER = 0xB5443A
        const val INFO = 0x4A6B8A
    }

    /**
     * **深色主题**。
     *
     * ⚠️ 与浅色的关键差异：**方向相反**。浅色是把颜色调深才有对比度，
     * 深色是调亮。同一组值在两种主题下不可能通用 —— 这是物理约束。
     */
    object Dark {
        const val CANVAS = 0x1A1816
        const val CANVAS_ELEVATED = 0x211E1B
        const val SURFACE = 0x262320
        const val SURFACE_HIGH = 0x2F2B27

        // ── 文字三档 ────────────────────────────────────────
        //
        //   Primary   15.20 on Canvas  ✓
        //   Secondary  8.06 on Canvas  ✓  7.12 on Surface ✓
        //   Tertiary   5.47 on Canvas  ✓  4.83 on Surface ✓
        //
        // ⚠️ Tertiary 必须同时满足 **Canvas 与 Surface 两种底色** ——
        //    Surface 更亮，同一支颜料在上面对比度更低。
        //    只按 Canvas 定值会留下"卡片里的说明文字偏淡"的局部缺陷。
        const val TEXT_PRIMARY = 0xF2EDE6
        const val TEXT_SECONDARY = 0xB5AEA5
        const val TEXT_TERTIARY = 0x958E86
        const val TEXT_DISABLED = 0x4A453F

        // ── 强调色 ──────────────────────────────────────────
        //
        // ★ 深色底天然对比度高（ACCENT 在这里是 5.72），
        //   所以"背景色"和"文字色"能用**同一个值**，不需要拆。
        const val ACCENT = 0xD9785A
        const val ACCENT_PRESSED = 0xC96442
        const val ACCENT_TEXT = 0xD9785A

        // ── 语义色（全部合格，5.21 ~ 6.75）──────────────────
        const val SUCCESS = 0x5FA97D
        const val WARNING = 0xD09448
        const val DANGER = 0xD96A5F
        const val INFO = 0x7E9DB8
    }
}
