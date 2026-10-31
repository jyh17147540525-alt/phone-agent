package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **WCAG 对比度**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这些数字是本项目**实际配色的审计结果**
 * ═══════════════════════════════════════════════════════════════
 *
 * 调色前实测（底 F5F2EC）：
 *   TextPrimary   #1F1D1A  → 15.05  AA ✓
 *   TextSecondary #6B655C  →  5.16  AA ✓
 *   TextTertiary  #9A938A  → **2.72  AA ✗**
 *
 * 而 `TextTertiary` 被用了 **77 处** —— 本项目最大面积的可访问性缺陷。
 *
 * ⇒ 三档一起重排（见 `PaPalette.LightPalette` 的注释）。
 *   下面这些断言把新值钉住，防止有人"调淡一点"时悄悄破坏。
 */
class ContrastTest {

    // ═══════════════════════════════════════════════════════════
    //  算法正确性（先验工具，再验配色）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `纯黑对纯白的对比度是二十一`() {
        // ★ 这是 WCAG 的定义值。算不对说明线性化写错了。
        assertEquals(21.0, Contrast.ratio(0x000000, 0xFFFFFF), 0.01)
    }

    @Test
    fun `同色对比度是一`() {
        assertEquals(1.0, Contrast.ratio(0x123456, 0x123456), 0.001)
    }

    @Test
    fun `对比度与参数顺序无关`() {
        // ⚠️ 对比度是对称量。有人可能以为"前景/背景"顺序有意义 ——
        //    这条测试把"顺序无关"钉住。
        val a = Contrast.ratio(ColorTokens.Light.TEXT_PRIMARY, ColorTokens.Light.CANVAS)
        val b = Contrast.ratio(ColorTokens.Light.CANVAS, ColorTokens.Light.TEXT_PRIMARY)
        assertEquals(a, b, 0.0001)
    }

    @Test
    fun `亮度单调递增`() {
        // 从黑到白，亮度必须单调增 —— 否则线性化有 bug。
        val lums = listOf(0x000000, 0x333333, 0x666666, 0x999999, 0xCCCCCC, 0xFFFFFF)
            .map { Contrast.luminance(it) }
        assertEquals("亮度必须单调递增：$lums", lums.sorted(), lums)
    }

    @Test
    fun `纯白亮度是一纯黑是零`() {
        assertEquals(1.0, Contrast.luminance(0xFFFFFF), 0.0001)
        assertEquals(0.0, Contrast.luminance(0x000000), 0.0001)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 浅色主题：三档全部必须过 AA
    // ═══════════════════════════════════════════════════════════

    private val lightCanvas = ColorTokens.Light.CANVAS
    private val lightSurface = ColorTokens.Light.SURFACE

    @Test
    fun `浅色三档文字全部通过AA`() {
        val pairs = listOf(
            Contrast.Pair("TextPrimary on Canvas", ColorTokens.Light.TEXT_PRIMARY, lightCanvas),
            Contrast.Pair("TextSecondary on Canvas", ColorTokens.Light.TEXT_SECONDARY, lightCanvas),
            Contrast.Pair("TextTertiary on Canvas", ColorTokens.Light.TEXT_TERTIARY, lightCanvas),
            Contrast.Pair("TextPrimary on Surface", ColorTokens.Light.TEXT_PRIMARY, lightSurface),
            Contrast.Pair("TextSecondary on Surface", ColorTokens.Light.TEXT_SECONDARY, lightSurface),
            Contrast.Pair("TextTertiary on Surface", ColorTokens.Light.TEXT_TERTIARY, lightSurface),
        )
        val bad = Contrast.failures(pairs)
        assertTrue(
            "这些组合不合格：\n${bad.joinToString("\n") { "  " + it.describe() }}",
            bad.isEmpty(),
        )
    }

    @Test
    fun `浅色三档的层级必须可辨`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条防的是"合规但难用"
        // ══════════════════════════════════════════════════════
        // 把 Tertiary 单独提到合规的 #706B62 之后，它与
        // Secondary(#6B655C) 的亮度差只剩 0.0164 —— **层级消失**。
        // 所以必须三档一起重排。
        assertTrue(
            "Primary 与 Secondary 必须可辨",
            Contrast.tiersDistinguishable(ColorTokens.Light.TEXT_PRIMARY, ColorTokens.Light.TEXT_SECONDARY),
        )
        assertTrue(
            "Secondary 与 Tertiary 必须可辨（调色前只有 0.0164）",
            Contrast.tiersDistinguishable(ColorTokens.Light.TEXT_SECONDARY, ColorTokens.Light.TEXT_TERTIARY),
        )
    }

    @Test
    fun `调色前的旧 Tertiary 确实不合格`() {
        // ★ 这条是"审计证据"：证明我们**确实**修了一个真实缺陷，
        //   而不是为了凑测试数字。
        val old = Contrast.Pair("旧 TextTertiary", 0x9A938A, lightCanvas)
        assertFalse("旧的 #9A938A 确实不合格（${old.ratio}）", old.passes)
    }

    @Test
    fun `只提亮 Tertiary 会让它和Secondary挤在一起`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条记录的是一个**真实的取舍陷阱**
        // ══════════════════════════════════════════════════════
        // 直觉做法：只把 Tertiary 从 2.72 提到合规的 #706B62。
        // 但那样它与**未改动**的 Secondary(#6B655C) 只差 0.0164 ——
        // 两档几乎一模一样，层级消失。
        //
        // ⚠️ 注意：调色**前**的差是 0.164（很大，因为 Tertiary 很浅）——
        //    也就是说"原来的层级是分明的，只是 Tertiary 读不清"。
        //    所以正确解法不是"把它提亮到刚好合规"，而是
        //    **三档一起重排**，同时满足"读得清"和"分得清"。
        val wouldBe = Contrast.tierGap(0x6B655C, ColorTokens.Light.TEXT_TERTIARY)
        assertFalse(
            "只改 Tertiary（Secondary 不动）会让层级消失：差 $wouldBe",
            Contrast.tiersDistinguishable(0x6B655C, ColorTokens.Light.TEXT_TERTIARY),
        )

        // 而三档一起重排之后，两个约束同时满足
        assertTrue(
            "重排后 Secondary↔Tertiary 可辨：差 ${Contrast.tierGap(ColorTokens.Light.TEXT_SECONDARY, ColorTokens.Light.TEXT_TERTIARY)}",
            Contrast.tiersDistinguishable(ColorTokens.Light.TEXT_SECONDARY, ColorTokens.Light.TEXT_TERTIARY),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 深色主题
    // ═══════════════════════════════════════════════════════════

    private val darkCanvas = ColorTokens.Dark.CANVAS
    private val darkSurface = ColorTokens.Dark.SURFACE

    @Test
    fun `深色三档文字全部通过AA`() {
        val pairs = listOf(
            Contrast.Pair("TextPrimary on Canvas", ColorTokens.Dark.TEXT_PRIMARY, darkCanvas),
            Contrast.Pair("TextSecondary on Canvas", ColorTokens.Dark.TEXT_SECONDARY, darkCanvas),
            Contrast.Pair("TextTertiary on Canvas", ColorTokens.Dark.TEXT_TERTIARY, darkCanvas),
            Contrast.Pair("TextPrimary on Surface", ColorTokens.Dark.TEXT_PRIMARY, darkSurface),
            Contrast.Pair("TextSecondary on Surface", ColorTokens.Dark.TEXT_SECONDARY, darkSurface),
            Contrast.Pair("TextTertiary on Surface", ColorTokens.Dark.TEXT_TERTIARY, darkSurface),
        )
        val bad = Contrast.failures(pairs)
        assertTrue(
            "这些组合不合格：\n${bad.joinToString("\n") { "  " + it.describe() }}",
            bad.isEmpty(),
        )
    }

    @Test
    fun `深色三档的层级必须可辨`() {
        assertTrue(Contrast.tiersDistinguishable(ColorTokens.Dark.TEXT_PRIMARY, ColorTokens.Dark.TEXT_SECONDARY))
        assertTrue(Contrast.tiersDistinguishable(ColorTokens.Dark.TEXT_SECONDARY, ColorTokens.Dark.TEXT_TERTIARY))
    }

    @Test
    fun `调色前的深色Tertiary确实不合格`() {
        val old = Contrast.Pair("旧深色 TextTertiary", 0x736C64, darkCanvas)
        assertFalse("旧的 #736C64 确实不合格（${old.ratio}）", old.passes)
    }

    // ═══════════════════════════════════════════════════════════
    //  语义色（状态提示也要能读）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `浅色语义色通过AA`() {
        val pairs = listOf(
            Contrast.Pair("Success", ColorTokens.Light.SUCCESS, lightCanvas),
            Contrast.Pair("Warning", ColorTokens.Light.WARNING, lightCanvas),
            Contrast.Pair("Danger", ColorTokens.Light.DANGER, lightCanvas),
            Contrast.Pair("Info", ColorTokens.Light.INFO, lightCanvas),
        )
        val bad = Contrast.failures(pairs)
        assertTrue(
            "这些语义色不合格：\n${bad.joinToString("\n") { "  " + it.describe() }}",
            bad.isEmpty(),
        )
    }

    @Test
    fun `强调色作为文字必须用 AccentText 而不是 Accent`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条记录的是一个**不能靠调一个值解决**的冲突
        // ══════════════════════════════════════════════════════
        // `Accent` 既作按钮背景（上面浮深色文字）又作链接文字。
        //  · 作背景：要够浅，否则上面的深色文字看不清
        //  · 作文字：要够深，否则在暖米底上读不清
        //
        // 两个要求物理上冲突 ⇒ 拆成两个令牌。
        val asText = Contrast.Pair("Accent 作文字", ColorTokens.Light.ACCENT, lightCanvas)
        assertFalse(
            "`Accent` 作文字不合格（${asText.ratio}）—— 所以才有 AccentText",
            asText.passes,
        )

        val properText = Contrast.Pair("AccentText 作文字", ColorTokens.Light.ACCENT_TEXT, lightCanvas)
        assertTrue(
            "`AccentText` 必须合格：${properText.describe()}",
            properText.passes,
        )
    }

    @Test
    fun `调色前的语义色确实不合格`() {
        // ★ 审计证据：证明我们修的是真实缺陷。
        val oldSuccess = Contrast.Pair("旧 Success", 0x3D8A5F, lightCanvas)
        val oldWarning = Contrast.Pair("旧 Warning", 0xB07425, lightCanvas)
        assertFalse("旧 Success 不合格（${oldSuccess.ratio}）", oldSuccess.passes)
        assertFalse("旧 Warning 不合格（${oldWarning.ratio}）", oldWarning.passes)
    }

    @Test
    fun `深色语义色全部合格`() {
        // ★ 深色底天然对比度高 —— 同一颜色在深底上的对比度远高于浅底。
        //   所以深色主题不需要像浅色那样调值。
        val pairs = listOf(
            Contrast.Pair("Success", ColorTokens.Dark.SUCCESS, darkCanvas),
            Contrast.Pair("Warning", ColorTokens.Dark.WARNING, darkCanvas),
            Contrast.Pair("Danger", ColorTokens.Dark.DANGER, darkCanvas),
            Contrast.Pair("Info", ColorTokens.Dark.INFO, darkCanvas),
            Contrast.Pair("Accent", ColorTokens.Dark.ACCENT, darkCanvas),
        )
        val bad = Contrast.failures(pairs)
        assertTrue(
            "深色语义色不合格：\n${bad.joinToString("\n") { "  " + it.describe() }}",
            bad.isEmpty(),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  禁用态**不要求** AA
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `禁用态可以低于AA`() {
        // ★ 这不是放过它，是**规范如此**：禁用元素不可交互，
        //   低对比度是"它不响应你"的视觉暗示。
        //   ⚠️ 但如果有人把 TextDisabled 用在**正常文字**上，
        //      那才是 bug —— 那需要靠 review 而不是靠测试发现。
        val disabled = Contrast.ratio(0xC4BDB2, lightCanvas)
        assertTrue("禁用态本来就该低（$disabled）", disabled < Contrast.AA_NORMAL)
    }

    // ═══════════════════════════════════════════════════════════
    //  失败信息必须可行动
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `失败描述包含颜色比值和要求`() {
        // ★ 测试失败时应当能直接看出"哪一对、多少、差多少"，
        //   而不是再去手算一遍。
        val bad = Contrast.Pair("X", 0x9A938A, ColorTokens.Light.CANVAS)
        val d = bad.describe()
        assertTrue("要含颜色：$d", "9A938A" in d.uppercase())
        assertTrue("要含比值：$d", "2.72" in d || "2.7" in d)
        assertTrue("要含要求：$d", "4.5" in d)
        assertTrue("要标出不合格：$d", "不合格" in d)
    }

    @Test
    fun `不合格列表只含失败项`() {
        val pairs = listOf(
            Contrast.Pair("good", ColorTokens.Light.TEXT_PRIMARY, lightCanvas),
            Contrast.Pair("bad", 0x9A938A, lightCanvas),
        )
        val f = Contrast.failures(pairs)
        assertEquals(1, f.size)
        assertEquals("bad", f.first().name)
    }
}
