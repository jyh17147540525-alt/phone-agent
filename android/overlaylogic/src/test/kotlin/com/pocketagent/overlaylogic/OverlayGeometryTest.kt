package com.pocketagent.overlaylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {

    // K60 实际屏幕（px）：1440 宽 × 3200 高，density 560
    private val k60Screen = Bounds(left = 0, top = 0, right = 1440, bottom = 3200)

    // 常规 1080p 手机
    private val fhdScreen = Bounds(left = 0, top = 0, right = 1080, bottom = 2340)

    private val ballDiameter = 40 * 3 + 20 // 约 140px（40dp @ density 3.5）

    // ── clampToScreen ──────────────────────────────────────────

    @Test
    fun `球在屏幕中间时位置不变`() {
        val b = OverlayGeometry.clampToScreen(
            centerX = 720, centerY = 1600, ballDiameter = ballDiameter, screen = k60Screen,
        )
        assertEquals(720, b.centerX)
        assertEquals(1600, b.centerY)
        assertEquals(ballDiameter, b.width)
    }

    @Test
    fun `球被推到屏幕外时会被拉回至少露出一部分`() {
        // 关键：不能完全移出屏幕 —— 会丢掉 Android 15 的 FGS 启动豁免
        val b = OverlayGeometry.clampToScreen(
            centerX = -9999, centerY = 1600, ballDiameter = ballDiameter, screen = k60Screen,
        )
        assertTrue("球必须仍与屏幕有重叠", b.overlaps(k60Screen))
        assertTrue("球左边缘不应跑到屏幕右侧之外", b.left < k60Screen.right)
    }

    @Test
    fun `球向右推过头也会被拉回`() {
        val b = OverlayGeometry.clampToScreen(
            centerX = 9999, centerY = 1600, ballDiameter = ballDiameter, screen = k60Screen,
        )
        assertTrue("球必须仍与屏幕有重叠", b.overlaps(k60Screen))
        assertTrue(b.right > k60Screen.left)
    }

    @Test
    fun `球向上推到状态栏区域会被约束`() {
        val b = OverlayGeometry.clampToScreen(
            centerX = 720, centerY = -500, ballDiameter = ballDiameter, screen = k60Screen,
        )
        assertTrue("球必须仍与屏幕有重叠", b.overlaps(k60Screen))
    }

    @Test
    fun `极小屏幕也不会算出越界矩形`() {
        val tiny = Bounds(left = 0, top = 0, right = 100, bottom = 100)
        val b = OverlayGeometry.clampToScreen(
            centerX = 50, centerY = 50, ballDiameter = 400, screen = tiny,
        )
        // 球比屏幕还大时不应崩溃，且仍应返回合法矩形
        assertTrue(b.right >= b.left)
        assertTrue(b.bottom >= b.top)
    }

    @Test
    fun `不同分辨率下均能正确约束`() {
        listOf(k60Screen, fhdScreen).forEach { screen ->
            val b = OverlayGeometry.clampToScreen(
                centerX = 0, centerY = 0, ballDiameter = ballDiameter, screen = screen,
            )
            assertTrue("屏幕 $screen 下越界", b.overlaps(screen))
        }
    }

    // ── shouldSnapToEdge ───────────────────────────────────────

    @Test
    fun `靠近左边缘时应吸附`() {
        val ball = Bounds(left = 10, top = 1000, right = 10 + ballDiameter, bottom = 1000 + ballDiameter)
        assertTrue(OverlayGeometry.shouldSnapToEdge(ball, k60Screen, thresholdPx = 200))
    }

    @Test
    fun `靠近右边缘时应吸附`() {
        val right = k60Screen.right - ballDiameter - 10
        val ball = Bounds(left = right, top = 1000, right = right + ballDiameter, bottom = 1000 + ballDiameter)
        assertTrue(OverlayGeometry.shouldSnapToEdge(ball, k60Screen, thresholdPx = 200))
    }

    @Test
    fun `屏幕中间不应吸附`() {
        val ball = OverlayGeometry.clampToScreen(720, 1600, ballDiameter, k60Screen)
        assertFalse(OverlayGeometry.shouldSnapToEdge(ball, k60Screen, thresholdPx = 200))
    }

    @Test
    fun `正好在阈值边界上应吸附`() {
        // 中心距离左边缘恰好 = 阈值 → 应吸附（用 <= 而非 <）
        val cx = k60Screen.left + 200
        val ball = Bounds(cx - ballDiameter / 2, 1000, cx + ballDiameter / 2, 1000 + ballDiameter)
        assertTrue(OverlayGeometry.shouldSnapToEdge(ball, k60Screen, thresholdPx = 200))
    }

    // ── nearestEdge ────────────────────────────────────────────

    @Test
    fun `偏左时吸附到左边缘`() {
        val ball = OverlayGeometry.clampToScreen(200, 1600, ballDiameter, k60Screen)
        assertEquals(ScreenEdge.LEFT, OverlayGeometry.nearestEdge(ball, k60Screen))
    }

    @Test
    fun `偏右时吸附到右边缘`() {
        val ball = OverlayGeometry.clampToScreen(1200, 1600, ballDiameter, k60Screen)
        assertEquals(ScreenEdge.RIGHT, OverlayGeometry.nearestEdge(ball, k60Screen))
    }

    @Test
    fun `正好居中时偏向右侧`() {
        // 符合右手持机习惯
        val ball = OverlayGeometry.clampToScreen(k60Screen.centerX, 1600, ballDiameter, k60Screen)
        assertEquals(ScreenEdge.RIGHT, OverlayGeometry.nearestEdge(ball, k60Screen))
    }

    // ── computeEdgePosition ────────────────────────────────────

    @Test
    fun `吸附左边缘后仍保留细线在屏幕内`() {
        val b = OverlayGeometry.computeEdgePosition(
            edge = ScreenEdge.LEFT,
            centerY = 1600,
            ballDiameter = ballDiameter,
            screen = k60Screen,
            edgeLineWidth = 12,
        )
        // 关键：球不能被推到屏幕外，否则丢掉"可见 overlay"资格
        assertTrue("吸附后必须仍与屏幕重叠", b.overlaps(k60Screen))
        assertTrue("吸附后应贴近左边缘", b.centerX - k60Screen.left <= 12)
    }

    @Test
    fun `吸附右边缘后仍保留细线在屏幕内`() {
        val b = OverlayGeometry.computeEdgePosition(
            edge = ScreenEdge.RIGHT,
            centerY = 1600,
            ballDiameter = ballDiameter,
            screen = k60Screen,
            edgeLineWidth = 12,
        )
        assertTrue("吸附后必须仍与屏幕重叠", b.overlaps(k60Screen))
        assertTrue("吸附后应贴近右边缘", k60Screen.right - b.centerX <= 12)
    }

    @Test
    fun `贴边时纵向位置仍受屏幕约束`() {
        val b = OverlayGeometry.computeEdgePosition(
            edge = ScreenEdge.LEFT,
            centerY = -9999, // 用户把球拖到屏幕上方之外
            ballDiameter = ballDiameter,
            screen = k60Screen,
            edgeLineWidth = 12,
        )
        assertTrue("贴边后纵向不应越界", b.top >= k60Screen.top - 1)
    }

    @Test
    fun `贴边时纵向超出下边界也会被拉回`() {
        val b = OverlayGeometry.computeEdgePosition(
            edge = ScreenEdge.RIGHT,
            centerY = 99999,
            ballDiameter = ballDiameter,
            screen = k60Screen,
            edgeLineWidth = 12,
        )
        assertTrue("贴边后纵向不应越界", b.bottom <= k60Screen.bottom + 1)
    }

    // ── computePanelBounds ─────────────────────────────────────

    @Test
    fun `球在左半屏时面板向右展开`() {
        val ball = OverlayGeometry.clampToScreen(200, 1600, ballDiameter, k60Screen)
        val panel = OverlayGeometry.computePanelBounds(
            ballBounds = ball, panelWidth = 800, panelHeight = 500, screen = k60Screen, gap = 20,
        )
        assertTrue("面板应在球的右侧", panel.left >= ball.right)
    }

    @Test
    fun `球在右半屏时面板向左展开`() {
        val ball = OverlayGeometry.clampToScreen(1300, 1600, ballDiameter, k60Screen)
        val panel = OverlayGeometry.computePanelBounds(
            ballBounds = ball, panelWidth = 800, panelHeight = 500, screen = k60Screen, gap = 20,
        )
        assertTrue("面板应在球的左侧", panel.right <= ball.left)
    }

    @Test
    fun `面板不会超出屏幕边界`() {
        // 面板被屏幕裁掉会显得很糟，且用户点不到里面的控件
        val ball = OverlayGeometry.clampToScreen(1300, 3100, ballDiameter, k60Screen)
        val panel = OverlayGeometry.computePanelBounds(
            ballBounds = ball, panelWidth = 800, panelHeight = 500, screen = k60Screen, gap = 20,
        )
        assertTrue("面板左边越界", panel.left >= k60Screen.left)
        assertTrue("面板右边越界", panel.right <= k60Screen.right)
        assertTrue("面板上边越界", panel.top >= k60Screen.top)
        assertTrue("面板下边越界", panel.bottom <= k60Screen.bottom)
    }

    @Test
    fun `面板比屏幕还宽时也不越界`() {
        val ball = OverlayGeometry.clampToScreen(720, 1600, ballDiameter, k60Screen)
        val panel = OverlayGeometry.computePanelBounds(
            ballBounds = ball, panelWidth = 5000, panelHeight = 500, screen = k60Screen,
        )
        assertEquals("超宽面板应对齐屏幕左边", k60Screen.left, panel.left)
    }

    @Test
    fun `面板比屏幕还高时也不越界`() {
        val ball = OverlayGeometry.clampToScreen(720, 1600, ballDiameter, k60Screen)
        val panel = OverlayGeometry.computePanelBounds(
            ballBounds = ball, panelWidth = 800, panelHeight = 9999, screen = k60Screen,
        )
        assertEquals("超高面板应对齐屏幕顶部", k60Screen.top, panel.top)
    }

    // ── Bounds 基础行为 ────────────────────────────────────────

    @Test
    fun `矩形的宽高与中心计算正确`() {
        val b = Bounds(left = 10, top = 20, right = 110, bottom = 220)
        assertEquals(100, b.width)
        assertEquals(200, b.height)
        assertEquals(60, b.centerX)
        assertEquals(120, b.centerY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法矩形会被拒绝`() {
        Bounds(left = 100, top = 0, right = 0, bottom = 100)
    }

    @Test
    fun `相交判断正确`() {
        val a = Bounds(0, 0, 100, 100)
        assertTrue(a.overlaps(Bounds(50, 50, 150, 150)))
        assertFalse(a.overlaps(Bounds(200, 200, 300, 300)))
        // 仅边缘相接不算重叠
        assertFalse(a.overlaps(Bounds(100, 0, 200, 100)))
    }

    @Test
    fun `包含判断正确`() {
        val a = Bounds(0, 0, 100, 100)
        assertTrue(a.contains(50, 50))
        assertFalse(a.contains(100, 100)) // 右/下边界不含
        assertFalse(a.contains(-1, 50))
    }
}
