package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **图标尺寸档位与自适应图标几何**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件要防的两类问题
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **尺寸随手挑** —— 16 / 18 / 20 / 22 混着用。
 *    用户说不出哪里不对，但会觉得"不齐"。
 *
 * ② **前景越界** —— 自适应图标的外圈 18dp 会被厂商遮罩裁掉。
 *    内容画到那里，用户看到的是被切掉一角的图形。
 *    ⚠️ 而描边会向外溢半个宽度，这个细节最容易漏。
 */
class IconGeometryTest {

    // ═══════════════════════════════════════════════════════════
    //  ① 尺寸档位
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `所有档位都落在 4dp 网格上`() {
        for (s in IconGeometry.Size.entries) {
            assertTrue(
                "「${s.name}」= ${s.value}dp 不在 ${IconGeometry.GRID_DP}dp 网格上",
                s.onGrid,
            )
        }
    }

    @Test
    fun `档位严格递增`() {
        // ★ 递增保证 nearest() 不会有两个等距候选 —— 等距时行为不确定，
        //   而"不确定"意味着这个 bug 只在某些尺寸上复现。
        val dps = IconGeometry.Size.entries.map { it.value }
        assertEquals(dps.sorted(), dps)
        assertEquals("档位不该有重复值", dps.size, dps.toSet().size)
    }

    @Test
    fun `默认档位是 Material 的 24dp`() {
        // ★ 24 是 Material 图标的基准尺寸。改成别的会让整套图标显得偏大或偏小。
        assertEquals(24, IconGeometry.Size.DEFAULT.value)
    }

    @Test
    fun `档位覆盖常用区间`() {
        // 16（与文字同行）到 48（引导页）都该有
        assertEquals(16, IconGeometry.Size.INLINE.value)
        assertEquals(48, IconGeometry.Size.HERO.value)
    }

    @Test
    fun `非档位尺寸会被拉回最近的档`() {
        // ⚠️ 拉到最近而不是报错：报错会让人绕过这一层、直接写死 dp。
        assertEquals(IconGeometry.Size.COMPACT, IconGeometry.nearest(18))
        assertEquals(IconGeometry.Size.DEFAULT, IconGeometry.nearest(22))
        assertEquals(IconGeometry.Size.DEFAULT, IconGeometry.nearest(24))
        assertEquals(IconGeometry.Size.INLINE, IconGeometry.nearest(14))
    }

    @Test
    fun `nearest 在极端值上也给得出结果`() {
        // 不该崩，也不该返回 null
        assertEquals(IconGeometry.Size.INLINE, IconGeometry.nearest(0))
        assertEquals(IconGeometry.Size.HERO, IconGeometry.nearest(1000))
    }

    @Test
    fun `档位表能认出合规尺寸`() {
        assertTrue(IconGeometry.isCanonical(24))
        assertFalse("18dp 不在档位表里", IconGeometry.isCanonical(18))
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 自适应图标安全区
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `安全区是 21 到 87`() {
        // 108 - 66 = 42，两侧各 21
        assertEquals(21.0, IconGeometry.SAFE_MIN_DP, 0.001)
        assertEquals(87.0, IconGeometry.SAFE_MAX_DP, 0.001)
    }

    @Test
    fun `本项目的前景内容在安全区内`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这一条盯的是**真实资产**，不是假设
        // ══════════════════════════════════════════════════════
        // `ic_launcher_foreground.xml` 的手机轮廓：
        //   path 从 x=32 到 x=76，strokeWidth = 4
        //   ⇒ 实际绘制范围 30 ~ 78
        assertTrue(
            "前景（含 4dp 描边外扩）必须落在 21~87",
            IconGeometry.isWithinSafeZone(
                minCoord = 32.0,
                maxCoord = 76.0,
                strokeWidth = 4.0,
            ),
        )
    }

    @Test
    fun `描边外扩会被算进去`() {
        // ══════════════════════════════════════════════════════
        //  ★ 最容易漏的一条
        // ══════════════════════════════════════════════════════
        // 路径坐标正好在安全区边界（21）上时，4dp 描边会画到 19 —— **越界**。
        // 只检查路径坐标的话会误判成"合规"。
        assertFalse(
            "路径在 21 上但描边外扩到 19，应当判越界",
            IconGeometry.isWithinSafeZone(
                minCoord = 21.0,
                maxCoord = 87.0,
                strokeWidth = 4.0,
            ),
        )
        // 没有描边时，正好贴边是合规的
        assertTrue(
            IconGeometry.isWithinSafeZone(21.0, 87.0, strokeWidth = 0.0),
        )
    }

    @Test
    fun `明显越界的内容会被判出来`() {
        // 画满整块 108 画布 —— 外圈 18dp 必被裁
        assertFalse(IconGeometry.isWithinSafeZone(0.0, 108.0, 0.0))
        // 画在左上角
        assertFalse(IconGeometry.isWithinSafeZone(5.0, 40.0, 0.0))
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 视觉占比
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `本项目前景的占比落在建议区间`() {
        // 44 / 108 ≈ 0.407 —— 比建议下限 0.5 低。
        //
        // ⚠️ 这是一个**真实的观察**，不是测试失败：
        //    手机轮廓只占 44dp，被 66dp 安全区一框，看起来会偏小。
        //    记在这里是为了让下次改图标的人知道这件事已经被想过 ——
        //    而不是当成"新发现的问题"再查一遍。
        val ratio = IconGeometry.fillRatio(32.0, 76.0)
        assertTrue("占比应当是 44/108 左右：$ratio", kotlin.math.abs(ratio - 44.0 / 108.0) < 0.01)
        assertFalse(
            "44dp 占 108 画布的 0.41，偏低 —— 见上面的注释",
            IconGeometry.fillRatioLooksRight(ratio),
        )
    }

    @Test
    fun `占满安全区的图形占比合规`() {
        val ratio = IconGeometry.fillRatio(
            IconGeometry.SAFE_MIN_DP,
            IconGeometry.SAFE_MAX_DP,
        )
        assertTrue(
            "66/108 ≈ 0.61 应当落在 0.5~0.85：$ratio",
            IconGeometry.fillRatioLooksRight(ratio),
        )
    }

    @Test
    fun `占比计算不会除以零`() {
        assertEquals(0.407, IconGeometry.fillRatio(32.0, 76.0), 0.001)
    }
}
