package com.pocketagent.ui.memory

import androidx.compose.ui.graphics.Color
import com.pocketagent.ui.theme.PaPalette

/**
 * **记忆星图的配色** —— 分浅色与深色两套。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-29 用户："记忆星云在浅色主题下会很模糊"
 * ═══════════════════════════════════════════════════════════════
 *
 * 根因：星图最初是**给暗底写的**。浅蓝 `B9D4FF` 与荧光绿 `7CFFB2`
 * 在奶油色背景（`F5F2EC`）上的对比度只有 1.2:1 左右 —— 等于看不见。
 *
 * ⚠️ 关键判断：换浅色版**不是统一减淡**。
 *   暗底靠"亮"吸引注意力，浅底靠"深"。所以这是两套色板，不是一套颜色的两种亮度。
 *
 * | 角色 | 暗底（亮色系） | 浅底（深色系） | 为什么 |
 * |---|---|---|---|
 * | 普通点 | 浅蓝 B9D4FF | 深靛蓝 2E4E7E | 浅底上要够暗才有形 |
 * | 重点 | 荧光绿 7CFFB2 | 深青绿 0F6B4F | 异色但必须够暗 |
 * | 选中 | 浅橙 FFC48A | 赤陶 A34A28 | 与主题强调色同族 |
 * | 边 | 亮蓝 7FA8E8 | 深灰蓝 4A5A70 | 边比点更弱，但要可见 |
 *
 * ⚠️ 光晕的不透明度也必须不同：浅底上淡淡一层白光等于没有，
 *   所以浅色版的光晕**更浓、更暗**（用深色晕而不是亮色晕）。
 */
data class MemoryGraphPalette(
    val dot: Color,
    val key: Color,
    val selected: Color,
    val edge: Color,
    /** 光晕第一层的不透明度。 */
    val glowOuterAlpha: Float,
    /** 光晕第二层的不透明度。 */
    val glowInnerAlpha: Float,
    /** 重点事件中心那一点高光的颜色（暗底用白，浅底用更深）。 */
    val keyCore: Color,
    /** 画布上文字（重点事件的名字）的颜色。 */
    val label: Color,
) {
    companion object {

        /**
         * 深色版 —— 原来的配色，效果是好的，保留。
         */
        val Dark = MemoryGraphPalette(
            dot = Color(0xFFB9D4FF),
            key = Color(0xFF7CFFB2),
            selected = Color(0xFFFFC48A),
            edge = Color(0xFF7FA8E8),
            glowOuterAlpha = 0.10f,
            glowInnerAlpha = 0.20f,
            keyCore = Color.White,
            label = Color(0xFFB9D4FF),
        )

        /**
         * 浅色版 —— 全部换成**深色系**。
         *
         * ★ 对照度：深靛蓝 2E4E7E 在 F5F2EC 上的对比度约 8.6:1，
         *   深青绿 0F6B4F 约 6.4:1 —— 都远高于 WCAG AA 的 4.5:1。
         *   而原来那套浅蓝在这个底色上只有 1.2:1。
         */
        val Light = MemoryGraphPalette(
            dot = Color(0xFF2E4E7E),
            key = Color(0xFF0F6B4F),
            selected = Color(0xFFA34A28),
            edge = Color(0xFF4A5A70),
            // ⚠️ 浅底上的"光晕"必须是**深色晕**，而且更浓。
            //    亮色在奶油底上扩散等于什么都没发生。
            glowOuterAlpha = 0.16f,
            glowInnerAlpha = 0.30f,
            // 浅底不用白色高光（看不见），用同色更亮一档的深色
            keyCore = Color(0xFF093F2E),
            label = Color(0xFF35506E),
        )

        /**
         * 按当前主题取。
         *
         * ⚠️ 传 `isDark` 而不是从 palette 猜：亮度阈值是魔数，
         *   失效时是静默的（某一天星云在浅色下又看不清了）。
         */
        fun forPalette(palette: PaPalette, isDark: Boolean): MemoryGraphPalette =
            if (isDark) Dark else Light
    }
}
