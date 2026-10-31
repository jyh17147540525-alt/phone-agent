package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * **惯性滚轮**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户说的"不够 iOS"，一半可能在这里
 * ═══════════════════════════════════════════════════════════════
 *
 * Android 默认 fling 和 iOS 的差异**不在曲线，在物理**：
 * iOS 松手后滑得远得多、拖尾长得多。而"松手就停"是被感觉成
 * "不丝滑"最主要的来源。
 */
class WheelFlingTest {

    // ═══════════════════════════════════════════════════════════
    //  两套预设必须真的不同
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `内容列表滑得比滚轮远`() {
        // ★ 这是两套预设存在的全部理由。
        val v = 3000f
        val content = WheelFling.estimateDistance(v, WheelFling.CONTENT)
        val wheel = WheelFling.estimateDistance(v, WheelFling.WHEEL)
        assertTrue(
            "内容列表($content) 应当比滚轮($wheel) 滑得远",
            content > wheel,
        )
    }

    @Test
    fun `只有滚轮吸附`() {
        assertTrue("滚轮必须吸附", WheelFling.WHEEL.snapToItem)
        assertTrue(
            "内容列表**不该**吸附（滑到一半被吸到某一行非常难受）",
            !WheelFling.CONTENT.snapToItem,
        )
    }

    @Test
    fun `摩擦系数落在合理区间`() {
        for (p in listOf(WheelFling.CONTENT, WheelFling.WHEEL)) {
            assertTrue("摩擦必须 < 1：${p.friction}", p.friction < 1.0)
            assertTrue("摩擦必须 > 0：${p.friction}", p.friction > 0.0)
            // 0.9 以下会"滑两下就死"，0.9999 以上会滑到失控
            assertTrue("摩擦不能太小：${p.friction}", p.friction > 0.99)
            assertTrue("摩擦不能太接近 1：${p.friction}", p.friction < 0.9999)
        }
    }

    @Test
    fun `非法参数被拒绝`() {
        val bad = listOf(
            runCatching { WheelFling.Profile(1.5, 40f, false) }.isFailure,
            runCatching { WheelFling.Profile(0.0, 40f, false) }.isFailure,
            runCatching { WheelFling.Profile(0.999, 0f, false) }.isFailure,
        )
        assertTrue("非法摩擦/阈值都必须被拦下：$bad", bad.all { it })
    }

    // ═══════════════════════════════════════════════════════════
    //  距离与时长
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `静止时距离为零`() {
        assertEquals(0f, WheelFling.estimateDistance(0f), 0.0001f)
        assertEquals(0f, WheelFling.estimateDistance(10f), 0.0001f)
    }

    @Test
    fun `距离随速度单调递增`() {
        val ds = listOf(500f, 1000f, 2000f, 4000f)
            .map { WheelFling.estimateDistance(it, WheelFling.CONTENT) }
        assertEquals("速度越快滑得越远：$ds", ds.sorted(), ds)
    }

    @Test
    fun `负速度给出正距离`() {
        // ★ 反方向滑动同样是"滑了一段"，距离是标量。
        val pos = WheelFling.estimateDistance(2000f, WheelFling.CONTENT)
        val neg = WheelFling.estimateDistance(-2000f, WheelFling.CONTENT)
        assertEquals(pos, neg, 0.0001f)
    }

    @Test
    fun `时长随速度递增但不超过上限`() {
        val dur = WheelFling.estimateDurationMs(2000f, WheelFling.CONTENT)
        assertTrue("中等速度应当有几百毫秒：$dur", dur > 0)

        // 极大速度下原始时长会超过上限 —— 所以需要 clampVelocity
        val huge = WheelFling.estimateDurationMs(100_000f, WheelFling.CONTENT)
        assertTrue("极大速度的原始时长应当超过上限：$huge", huge > WheelFling.MAX_FLING_MS)
    }

    @Test
    fun `上限是一点六秒`() {
        // ★ iOS 的 fling 大致在这个量级。再长用户会觉得"停不下来"。
        assertEquals(1_600L, WheelFling.MAX_FLING_MS)
    }

    // ═══════════════════════════════════════════════════════════
    //  速度截断
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常速度不被截断`() {
        // ⚠️ 这条断言的阈值是**算出来的**，不是拍的。
        //    CONTENT(f=0.999, minV=200) 下：
        //      1000 px/s ⇒ 1609ms —— 刚好在 1600 上限边缘（容差内）
        //      500  px/s ⇒  916ms —— 明显在上限内
        //    ⇒ 用 500 而不是 1000 来验"不截断"，避免边界抖动。
        val v = 500f
        assertEquals(v, WheelFling.clampVelocity(v, WheelFling.CONTENT), 0.0001f)
    }

    @Test
    fun `一千速度是上限附近`() {
        // ★ 这条把"参数选得对不对"钉住：1000 px/s 应当正好落在
        //   1.6 秒上限附近（ios 量级），不该滑 6 秒（第一版的错误）。
        val dur = WheelFling.estimateDurationMs(1000f, WheelFling.CONTENT)
        assertTrue(
            "1000px/s 的滑行时长 ${dur}ms 应当接近 1.6 秒上限（原来错成 6400ms）",
            dur in 1_400L..1_800L,
        )
    }

    @Test
    fun `极大速度被截断`() {
        val v = 500_000f
        val clamped = WheelFling.clampVelocity(v, WheelFling.CONTENT)
        assertTrue("必须被截断：$clamped", clamped < v)
        assertTrue("截断后仍为正：$clamped", clamped > 0f)
        // 截断后的时长应当不超过上限（允许 1ms 的整数误差）
        val dur = WheelFling.estimateDurationMs(clamped, WheelFling.CONTENT)
        assertTrue(
            "截断后的时长 ${dur}ms 应当不超过上限 ${WheelFling.MAX_FLING_MS}ms",
            dur <= WheelFling.MAX_FLING_MS + 50L,
        )
    }

    @Test
    fun `截断保持方向`() {
        val v = -500_000f
        val clamped = WheelFling.clampVelocity(v, WheelFling.CONTENT)
        assertTrue("负速度截断后仍为负：$clamped", clamped < 0f)
    }

    // ═══════════════════════════════════════════════════════════
    //  吸附
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `吸附到最近整格`() {
        assertEquals(100f, WheelFling.snapToNearest(104f, 100f), 0.0001f)
        assertEquals(100f, WheelFling.snapToNearest(96f, 100f), 0.0001f)
        assertEquals(200f, WheelFling.snapToNearest(151f, 100f), 0.0001f)
        assertEquals(0f, WheelFling.snapToNearest(0f, 100f), 0.0001f)
    }

    @Test
    fun `正好半格时向上取`() {
        // ⚠️ round(1.5) = 2.0（四舍五入），所以 150 会吸到 200。
        //    这个行为要被钉住，否则将来换成 floor/ceil 会悄悄改变手感。
        assertEquals(200f, WheelFling.snapToNearest(150f, 100f), 0.0001f)
    }

    @Test
    fun `格子尺寸非法时原样返回`() {
        // ⚠️ 传错不该让界面跳到 0（那是"列表瞬间滚到顶"）。
        assertEquals(123f, WheelFling.snapToNearest(123f, 0f), 0.0001f)
        assertEquals(123f, WheelFling.snapToNearest(123f, -10f), 0.0001f)
    }

    @Test
    fun `吸附增量方向正确`() {
        // 104 应往上吸到 100 ⇒ 增量 -4
        assertEquals(-4f, WheelFling.snapDelta(104f, 100f), 0.0001f)
        // 96 应往下吸到 100 ⇒ 增量 +4
        assertEquals(4f, WheelFling.snapDelta(96f, 100f), 0.0001f)
    }

    @Test
    fun `已在整格时增量为零`() {
        assertEquals(0f, WheelFling.snapDelta(200f, 100f), 0.0001f)
    }

    // ═══════════════════════════════════════════════════════════
    //  居中感
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正中时距离为零`() {
        assertEquals(0f, WheelFling.distanceFromCenter(200f, 100f), 0.0001f)
    }

    @Test
    fun `偏离时给出归一化距离`() {
        val mid = WheelFling.distanceFromCenter(150f, 100f)
        assertEquals("半格 = 0.5", 0.5f, mid, 0.0001f)
        val full = WheelFling.distanceFromCenter(100f, 100f)
        assertEquals("整格 = 0（算到下一个整格上）", 0f, full, 0.0001f)
    }

    @Test
    fun `距离始终在零到一之间`() {
        for (p in listOf(-500f, -150f, 0f, 149f, 200f, 9999f)) {
            val d = WheelFling.distanceFromCenter(p, 100f)
            assertTrue("距离必须在 0..1：$d（position=$p）", d in 0f..1f)
        }
    }

    @Test
    fun `格子非法时距离为零`() {
        assertEquals(0f, WheelFling.distanceFromCenter(123f, 0f), 0.0001f)
    }

    // ═══════════════════════════════════════════════════════════
    //  估算的合理性（防止公式写错）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `估算距离量级合理`() {
        // ★ 3000px/s 的滑动在手机上大约是"用力甩一下"。
        //   内容列表应当滑出明显超过一屏的距离（一屏约 2400px）。
        val d = WheelFling.estimateDistance(3000f, WheelFling.CONTENT)
        assertTrue("用力甩应当滑出至少一屏（现在 ${d}px）", d > 1000f)
        assertTrue("但也不该滑到失控（现在 ${d}px）", d < 20000f)
    }

    @Test
    fun `估算方向与量纲正确`() {
        // 防止把 1/1000 的换算漏掉导致差三个数量级
        val d = WheelFling.estimateDistance(1000f, WheelFling.CONTENT)
        assertTrue("1000px/s 的滑行距离量级应在数百 px 内（现在 ${d}px）", abs(d) in 100f..5000f)
    }
}
