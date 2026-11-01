package com.pocketagent.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Bounds] 与 `ElementRef` 的边界换算。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：这个类型是为了摆脱 `android.graphics.Rect`
 * ═══════════════════════════════════════════════════════════════
 *
 * 那一处 `import android.graphics.Rect` 把**整个 `:action` 模块**
 * 拖出了纯 Kotlin，进而让 `:agent` 也进不了离线验证器 ——
 * 而 `AmbiguityResolver` 一直没能接线正是因为验不了。
 *
 * ⇒ 换成只含四个 Int 的纯 Kotlin 类型，Android 侧的 `Rect`
 *   在边界转换（见 `ActionPorts`）。
 *
 * ⚠️ 这些测试能存在本身就说明改造成功了 ——
 *    改之前 `:action` 根本不在验证器的模块清单里。
 */
class BoundsTest {

    @Test
    fun `半径与中心算得对`() {
        val b = Bounds(left = 10, top = 20, right = 30, bottom = 60)
        assertEquals(20, b.width)
        assertEquals(40, b.height)
        assertEquals(20, b.centerX)
        assertEquals(40, b.centerY)
    }

    @Test
    fun `单点矩形的宽高为零`() {
        // ⚠️ `ByCoord` 会构造 `Bounds(x, y, x, y)` —— 宽高必须为 0，
        //    而不是 1。调用方靠 `width <= 0` 判断"没有可用面积"。
        val b = Bounds(5, 7, 5, 7)
        assertEquals(0, b.width)
        assertEquals(0, b.height)
        assertEquals(5, b.centerX)
        assertEquals(7, b.centerY)
    }

    @Test
    fun `中心点落在元素内部`() {
        val b = Bounds(100, 200, 300, 400)
        assertTrue(b.centerX in b.left..b.right)
        assertTrue(b.centerY in b.top..b.bottom)
    }

    @Test
    fun `ByCoord 的 bounds 是单点且非空`() {
        val ref = ElementRef.ByCoord(12, 34)
        val b = ref.bounds
        assertEquals(12, b!!.left)
        assertEquals(34, b.top)
        assertEquals(0, b.width)
    }

    @Test
    fun `ByText 没有已知位置`() {
        // ★ 文本定位拿不到坐标 —— 必须返回 null，
        //   不能当成"坐标为 0"，否则会点在屏幕左上角。
        assertNull(ElementRef.ByText("确定").bounds)
    }

    @Test
    fun `ByNodeId 与 ByViewId 带出各自的 bounds`() {
        val b = Bounds(1, 2, 3, 4)
        assertEquals(b, ElementRef.ByNodeId("n1", b).bounds)
        assertEquals(b, ElementRef.ByViewId("id/x", b).bounds)
    }
}
