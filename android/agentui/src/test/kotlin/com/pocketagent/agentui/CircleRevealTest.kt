package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * **圆形扩散转场**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 最重要的是"半径够不够"
 * ═══════════════════════════════════════════════════════════════
 *
 * 半径算小了，动画结束时几个角仍然是旧页面，要等转场结束后
 * "啪"地出现 —— 那一下比不做圆形扩散更糟。
 */
class CircleRevealTest {

    private val screen = CircleReveal.Rect(left = 0f, top = 0f, width = 1080f, height = 2400f)

    // ═══════════════════════════════════════════════════════════
    //  半径
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `从中心扩散到最远角是对角线一半`() {
        val r = CircleReveal.maxRadiusFrom(540f, 1200f, screen)
        val expected = hypot(540.0, 1200.0).toFloat()
        assertEquals("从中心到角的距离", expected, r, 0.5f)
    }

    @Test
    fun `从角落扩散到对角是整条对角线`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是"取宽高最大值"那个直觉错误
        // ══════════════════════════════════════════════════════
        // 从左上角扩散时，最远的点是右下角。
        // 如果按 max(width, height) = 2400 算，会**盖不到**右下角
        // （真实需要 hypot(1080, 2400) ≈ 2631）。
        val r = CircleReveal.maxRadiusFrom(0f, 0f, screen)
        val diag = hypot(1080.0, 2400.0).toFloat()
        assertEquals("从角到对角必须是整条对角线", diag, r, 0.5f)
        assertTrue("必须大于宽高最大值", r > maxOf(screen.width, screen.height))
    }

    @Test
    fun `半径覆盖整个区域的所有角`() {
        // ★ 充要条件：四个角到圆心的距离都 <= 半径。
        for (cx in listOf(0f, 540f, 1080f, 200f, 900f)) {
            for (cy in listOf(0f, 1200f, 2400f, 300f, 2000f)) {
                val r = CircleReveal.maxRadiusFrom(cx, cy, screen)
                val corners = listOf(
                    0f to 0f, 1080f to 0f, 0f to 2400f, 1080f to 2400f,
                )
                for ((px, py) in corners) {
                    val d = hypot((px - cx).toDouble(), (py - cy).toDouble()).toFloat()
                    assertTrue(
                        "圆心($cx,$cy) 半径 $r 盖不到角($px,$py) 需要 $d",
                        d <= r + 0.5f,
                    )
                }
            }
        }
    }

    @Test
    fun `带偏移的区域也对`() {
        val area = CircleReveal.Rect(left = 100f, top = 200f, width = 800f, height = 600f)
        val r = CircleReveal.maxRadiusFrom(100f, 200f, area)
        // 最远角是 (900, 800)
        val expected = hypot(800.0, 600.0).toFloat()
        assertEquals(expected, r, 0.5f)
    }

    // ═══════════════════════════════════════════════════════════
    //  进度 → 半径
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `进度零半径为零`() {
        assertEquals(0f, CircleReveal.radiusAt(0f, 1000f), 0.0001f)
    }

    @Test
    fun `进度一到最大半径`() {
        assertEquals(1000f, CircleReveal.radiusAt(1f, 1000f), 0.0001f)
    }

    @Test
    fun `慢起快停`() {
        // ★ ease-out：前 20% 走完大部分距离。
        //   匀速会让最后一段显得很拖。
        val half = CircleReveal.radiusAt(0.5f, 1000f)
        assertTrue("到一半进度时半径应已远超一半（现在 $half）", half > 800f)
    }

    @Test
    fun `半径单调不减`() {
        var prev = -1f
        for (i in 0..20) {
            val r = CircleReveal.radiusAt(i / 20f, 1000f)
            assertTrue("半径不能回退：在第 $i 步 $prev → $r", r >= prev)
            prev = r
        }
    }

    @Test
    fun `进度超界被夹住`() {
        assertEquals(0f, CircleReveal.radiusAt(-1f, 1000f), 0.0001f)
        assertEquals(1000f, CircleReveal.radiusAt(5f, 1000f), 0.0001f)
    }

    // ═══════════════════════════════════════════════════════════
    //  覆盖判定
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `圆内被覆盖`() {
        assertTrue(CircleReveal.isCovered(540f, 1200f, 540f, 1200f, 10f))
        assertTrue(CircleReveal.isCovered(545f, 1200f, 540f, 1200f, 10f))
    }

    @Test
    fun `圆外不被覆盖`() {
        assertFalse(CircleReveal.isCovered(600f, 1200f, 540f, 1200f, 10f))
    }

    @Test
    fun `边界上算被覆盖`() {
        assertTrue(CircleReveal.isCovered(550f, 1200f, 540f, 1200f, 10f))
    }

    // ═══════════════════════════════════════════════════════════
    //  羽化
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `圆内深处完全覆盖`() {
        assertEquals(1f, CircleReveal.coverage(540f, 1200f, 540f, 1200f, 100f), 0.0001f)
    }

    @Test
    fun `圆外远处完全未覆盖`() {
        assertEquals(0f, CircleReveal.coverage(900f, 1200f, 540f, 1200f, 100f), 0.0001f)
    }

    @Test
    fun `羽化带内是渐变`() {
        // 圆心在 0，半径 100，羽化 24 ⇒ 88..112 之间是过渡带
        val atEdge = CircleReveal.coverage(100f, 0f, 0f, 0f, 100f)
        assertTrue("边缘处应当是中间值（现在 $atEdge）", atEdge > 0f && atEdge < 1f)

        val inner = CircleReveal.coverage(90f, 0f, 0f, 0f, 100f)
        val outer = CircleReveal.coverage(110f, 0f, 0f, 0f, 100f)
        assertTrue("越靠内越不透明：$inner vs $atEdge", inner > atEdge)
        assertTrue("越靠外越透明：$outer vs $atEdge", outer < atEdge)
    }

    @Test
    fun `覆盖度始终在零到一之间`() {
        for (d in listOf(0f, 50f, 90f, 100f, 110f, 200f)) {
            val c = CircleReveal.coverage(d, 0f, 0f, 0f, 100f)
            assertTrue("覆盖度必须在 0..1：$c（距圆心 $d）", c in 0f..1f)
        }
    }

    @Test
    fun `羽化带宽合理`() {
        // 太小（<8）边缘有台阶感；太大（>64）看起来像渐显而不是扩散
        assertTrue("羽化不能太窄：${CircleReveal.FEATHER}", CircleReveal.FEATHER >= 8f)
        assertTrue("羽化不能太宽：${CircleReveal.FEATHER}", CircleReveal.FEATHER <= 64f)
    }

    // ═══════════════════════════════════════════════════════════
    //  时长
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `展开比普通转场慢`() {
        // ★ 扩散这个过程本身需要时间才看得出"从哪儿开始"。
        //   太快（<300）会退化成闪一下。
        assertTrue(
            "展开时长 ${CircleReveal.DURATION_MS}ms 应当足够看出方向",
            CircleReveal.DURATION_MS >= 300,
        )
        assertTrue(
            "但也不该拖（现在 ${CircleReveal.DURATION_MS}ms）",
            CircleReveal.DURATION_MS <= 600,
        )
    }

    @Test
    fun `收拢比展开快`() {
        // ⚠️ 关闭是"要离开了"，用户不想等。与"退出比进入快"同一条纪律。
        assertTrue(
            "收拢(${CircleReveal.EXIT_DURATION_MS}) 必须快于展开(${CircleReveal.DURATION_MS})",
            CircleReveal.EXIT_DURATION_MS < CircleReveal.DURATION_MS,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  圆心
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `有点击时圆心在点击处`() {
        val (x, y) = CircleReveal.center(300f, 800f, screen)
        assertEquals(300f, x, 0.0001f)
        assertEquals(800f, y, 0.0001f)
    }

    @Test
    fun `无点击时用屏幕中心`() {
        // ★ 没有因果线索时，中心是最不突兀的选择。
        //   用一个假的手指位置会让动画看起来在指别的地方。
        val (x, y) = CircleReveal.center(null, null, screen)
        assertEquals(540f, x, 0.0001f)
        assertEquals(1200f, y, 0.0001f)
    }

    @Test
    fun `点击超出区域时被夹住`() {
        val (x, y) = CircleReveal.center(-100f, 9999f, screen)
        assertEquals("x 不能被夹到区域外", 0f, x, 0.0001f)
        assertEquals("y 不能被夹到区域外", 2400f, y, 0.0001f)
    }

    @Test
    fun `只有一半点击时也用中心`() {
        val (x, y) = CircleReveal.center(300f, null, screen)
        assertEquals(540f, x, 0.0001f)
        assertEquals(1200f, y, 0.0001f)
    }

    // ═══════════════════════════════════════════════════════════
    //  半径钳制
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `可以限制半径上限`() {
        val r = CircleReveal.clampRadius(5000f, screen, maxRatio = 0.5f)
        assertEquals("上限是较长的边的一半", 1200f, r, 0.0001f)
    }

    @Test
    fun `不超过上限时不改动`() {
        assertEquals(500f, CircleReveal.clampRadius(500f, screen), 0.0001f)
    }
}
