package com.pocketagent.agentui

/**
 * **WCAG 对比度计算** —— 把"文字读不读得清"变成可以测的数字。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么这个要住在纯 Kotlin 里
 * ═══════════════════════════════════════════════════════════════
 *
 * 配色是**设计决策**，而设计决策最容易在"改一个值"时被悄悄破坏：
 * 有人把 `TextTertiary` 调淡一点想让界面"更轻盈"，
 * 而那一步可能让它从 4.7 掉到 3.2 —— 界面上看起来"还行"，
 * 但强光下、小字号、视力不佳时**读不清**。
 *
 * ⇒ 把对比度做成可断言的纯函数，然后在测试里钉住每一对
 *   文字/背景组合。这样"调淡一点"会在 CI 里立刻红。
 *
 * ★ 算法依据 WCAG 2.1：
 *   · 相对亮度 L = 0.2126·R + 0.7152·G + 0.0722·B（先做 sRGB 线性化）
 *   · 对比度 = (L1 + 0.05) / (L2 + 0.05)，L1 是较亮的那个
 *
 * ⚠️ 不用近似公式（如"亮度差 > 125"）：那个判据在中等亮度区间
 *   偏得很厉害，会把不合规的配色判成合规。
 */
object Contrast {

    /**
     * sRGB 通道值（0..255）→ 线性值（0..1）。
     *
     * ⚠️ 这个 2.4 次幂 + 0.055 的偏移不是可调参数 —— 它是 sRGB 规范。
     *    用简单的 `x/255` 会让暗部的亮度算高，从而**高估对比度**。
     */
    private fun linearize(channel: Int): Double {
        val x = channel.coerceIn(0, 255) / 255.0
        return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    }

    /**
     * 相对亮度（0 = 纯黑，1 = 纯白）。
     *
     * @param rgb 0xRRGGBB 形式的颜色
     */
    fun luminance(rgb: Int): Double {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
    }

    /**
     * 两色对比度（1.0 = 完全相同，21.0 = 纯黑对纯白）。
     *
     * ⚠️ 顺序无关：内部会自动取较亮的作分子。
     *   调用方写 `contrast(text, bg)` 或反过来结果一样 ——
     *   因为对比度是个**对称**量。这一点写进注释是为了避免
     *   有人以为"参数顺序有意义"而传错。
     */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * WCAG AA 门槛。
     *
     * ★ 正文是 **4.5**，大字号（≥18pt 或 ≥14pt 加粗）是 **3.0**。
     *   本项目的说明文字都是小字号（12~13sp），所以一律按 4.5 要求 ——
     *   按 3.0 要求会放走一批"只有大字号才勉强能读"的配色。
     */
    const val AA_NORMAL: Double = 4.5
    const val AA_LARGE: Double = 3.0

    /** AAA 门槛（正文 7.0）。**本项目不强制**，但记录下来备查。 */
    const val AAA_NORMAL: Double = 7.0

    /** 这一对配色过不过 AA。 */
    fun passesAA(text: Int, background: Int): Boolean =
        ratio(text, background) >= AA_NORMAL

    /**
     * 一个配色档位 —— 给测试表用。
     *
     * @param name 档位名（`TextPrimary` 这类）
     * @param color 0xRRGGBB
     * @param background 它实际坐在什么底色上
     * @param required 要求的门槛（默认 AA_NORMAL）
     */
    data class Pair(
        val name: String,
        val color: Int,
        val background: Int,
        val required: Double = AA_NORMAL,
    ) {
        val ratio: Double get() = Contrast.ratio(color, background)
        val passes: Boolean get() = ratio >= required

        /** 一句话结论，失败时能直接贴进 issue。 */
        fun describe(): String =
            "$name #%06X on #%06X = %.2f（要求 %.1f）%s".format(
                color, background, ratio, required, if (passes) " ✓" else " ✗ 不合格",
            )
    }

    /**
     * 检查一批配色，返回**不合格的那些**。
     *
     * ★ 返回不合格项而不是布尔：失败时测试信息要能直接说清
     *   "哪一对、多少、差多少"。只返回 false 会让人再去手算一遍。
     */
    fun failures(pairs: List<Pair>): List<Pair> = pairs.filter { !it.passes }

    /**
     * 两档文字之间的**亮度差** —— 用来判断层级是否看得出来。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 这个指标比对比度更容易被忽略
     * ═══════════════════════════════════════════════════════════
     *
     * 对比度管"读不读得清"，它管"**分不分得出层级**"。
     *
     * ⚠️ 真实教训：把 `TextTertiary` 调到合规的 #706B62 之后，
     *   它与 `TextSecondary`(#6B655C) 的亮度差只剩 **0.0164** ——
     *   两档几乎一模一样，**层级消失了**。而对比度测试全绿。
     *
     * ⇒ 两个指标都要有，缺一个就会出现"合规但难用"或"好看但读不清"。
     *
     * @return 亮度差的绝对值（0..1）。经验上 **< 0.03 就该警惕**
     *   （人眼在这个差距下基本分不出两档文字的区别）。
     */
    fun tierGap(a: Int, b: Int): Double =
        kotlin.math.abs(luminance(a) - luminance(b))

    /**
     * 两档文字之间的层级是否**可辨**。
     *
     * ★ 阈值 0.03 是经验值：低于它时，两档文字在屏幕上几乎一样。
     *   本项目调色前 Secondary↔Tertiary 是 0.0164（不可辨），
     *   调色后是 0.0564（可辨）。
     */
    fun tiersDistinguishable(a: Int, b: Int): Boolean =
        tierGap(a, b) >= 0.03
}
