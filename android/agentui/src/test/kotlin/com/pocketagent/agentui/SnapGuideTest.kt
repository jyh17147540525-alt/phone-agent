package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **吸附 + 画线**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里最重要的是"迟滞"
 * ═══════════════════════════════════════════════════════════════
 *
 * 没有迟滞时，手指在阈值边缘会**反复吸/脱** —— 界面抖成一片。
 * 那比不吸附更糟（不吸附只是没对齐，抖是坏掉了）。
 */
class SnapGuideTest {

    private fun line(pos: Float, edge: SnapGuide.Edge = SnapGuide.Edge.LEFT, peer: String? = "p1") =
        SnapGuide.GuideLine(pos, edge, peer)

    // ═══════════════════════════════════════════════════════════
    //  基本吸附
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `阈值内吸上`() {
        val r = SnapGuide.resolve(raw = 101f, lines = listOf(line(100f)))
        assertTrue("101 靠近 100，应当吸上", r.isSnapped)
        assertEquals(100f, r.snapped, 0.0001f)
        assertEquals("偏移应当是 -1", -1f, r.offset, 0.0001f)
    }

    @Test
    fun `阈值外不吸`() {
        val r = SnapGuide.resolve(raw = 120f, lines = listOf(line(100f)))
        assertFalse("120 离 100 太远", r.isSnapped)
        assertEquals("不吸时原样返回", 120f, r.snapped, 0.0001f)
        assertEquals(0f, r.offset, 0.0001f)
    }

    @Test
    fun `刚好在阈值上算吸上`() {
        val r = SnapGuide.resolve(raw = 108f, lines = listOf(line(100f)))
        assertTrue("等于阈值应当吸上", r.isSnapped)
    }

    @Test
    fun `没有参考线时原样返回`() {
        val r = SnapGuide.resolve(raw = 50f, lines = emptyList())
        assertFalse(r.isSnapped)
        assertEquals(50f, r.snapped, 0.0001f)
    }

    @Test
    fun `多条线时取最近`() {
        val r = SnapGuide.resolve(
            raw = 103f,
            lines = listOf(line(100f, peer = "far"), line(104f, peer = "near")),
        )
        assertEquals("应当吸到更近的那条", "near", r.guide?.fromPeer)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 迟滞（防抖）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `已吸住时小幅移动保持吸附`() {
        // ⚠️ 关键：如果这里脱开，在阈值边缘会反复吸/脱，界面抖。
        val held = line(100f)
        val r = SnapGuide.resolve(
            raw = 105f, // 超过 SNAP_THRESHOLD(8) 吗？没有，但重点看迟滞
            lines = listOf(held),
            currentlySnappedTo = held,
        )
        assertTrue("仍在迟滞带内，应当保持吸附", r.isSnapped)
        assertEquals(100f, r.snapped, 0.0001f)
    }

    @Test
    fun `已吸住时超过迟滞带才脱开`() {
        val held = line(100f)
        // 迟滞 = 8 * 1.5 = 12
        val within = SnapGuide.resolve(111f, listOf(held), currentlySnappedTo = held)
        assertTrue("111 距 100 为 11，在迟滞带(12)内，保持吸附", within.isSnapped)

        val outside = SnapGuide.resolve(115f, emptyList(), currentlySnappedTo = held)
        assertFalse("115 距 100 为 15，超出迟滞带，应当脱开", outside.isSnapped)
        assertEquals("脱开后回到原始位置", 115f, outside.snapped, 0.0001f)
    }

    @Test
    fun `迟滞必须大于吸附阈值`() {
        // ⚠️ 若相等，在边界上仍然会抖（一进一出用同一个判据）。
        assertTrue(
            "迟滞(${SnapGuide.HYSTERESIS}) 必须大于阈值(${SnapGuide.SNAP_THRESHOLD})",
            SnapGuide.HYSTERESIS > SnapGuide.SNAP_THRESHOLD,
        )
    }

    @Test
    fun `迟滞不能大到脱离困难`() {
        // 太大 ⇒ 用户觉得卡片被"粘住"了，甩不掉。
        assertTrue(
            "迟滞(${SnapGuide.HYSTERESIS}) 不该超过阈值的两倍",
            SnapGuide.HYSTERESIS <= SnapGuide.SNAP_THRESHOLD * 2f,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  参考线生成
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每张邻居卡产生四条线`() {
        val peers = mapOf(
            "p1" to SnapGuide.Edges(left = 0f, top = 10f, right = 100f, bottom = 60f),
        )
        val lines = SnapGuide.linesFrom(peers)
        assertEquals("四条边对应四条线", 4, lines.size)
        // 各条边的位置
        val byEdge = lines.associateBy { it.edge }
        assertEquals(0f, byEdge[SnapGuide.Edge.LEFT]!!.position, 0.0001f)
        assertEquals(100f, byEdge[SnapGuide.Edge.RIGHT]!!.position, 0.0001f)
        assertEquals(10f, byEdge[SnapGuide.Edge.TOP]!!.position, 0.0001f)
        assertEquals(60f, byEdge[SnapGuide.Edge.BOTTOM]!!.position, 0.0001f)
    }

    @Test
    fun `多条参考线带来源`() {
        val peers = mapOf(
            "a" to SnapGuide.Edges(0f, 0f, 50f, 50f),
            "b" to SnapGuide.Edges(10f, 0f, 60f, 50f),
        )
        val lines = SnapGuide.linesFrom(peers)
        assertTrue("每条线都要能指回来源卡", lines.all { it.fromPeer != null })
        assertEquals("两张卡 × 四条边", 8, lines.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  画线
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `吸上时给出一段线`() {
        val dragged = SnapGuide.Edges(100f, 200f, 300f, 260f)
        val peers = mapOf("p1" to SnapGuide.Edges(100f, 400f, 300f, 460f))
        val r = SnapGuide.resolve(
            raw = 101f,
            lines = SnapGuide.linesFrom(peers),
         )
        val seg = SnapGuide.segment(r, dragged, peers)
        assertNotNull("吸上并且有邻居时必须画线", seg)
    }

    @Test
    fun `没吸上时不画线`() {
        val dragged = SnapGuide.Edges(100f, 200f, 300f, 260f)
        val r = SnapGuide.resolve(raw = 500f, lines = emptyList())
        assertNull("没吸上就不画", SnapGuide.segment(r, dragged, emptyMap()))
    }

    @Test
    fun `线来自自己时不画`() {
        // ★ 给自己画一条线是纯粹视觉噪音。
        val dragged = SnapGuide.Edges(100f, 200f, 300f, 260f)
        val r = SnapGuide.Result(100f, SnapGuide.GuideLine(100f, SnapGuide.Edge.LEFT, null), 0f)
        assertNull("没有来源 peer 时不画", SnapGuide.segment(r, dragged, emptyMap()))
    }

    @Test
    fun `左对齐时画竖线`() {
        val dragged = SnapGuide.Edges(100f, 200f, 300f, 260f)
        val peers = mapOf("p1" to SnapGuide.Edges(100f, 400f, 300f, 460f))
        val r = SnapGuide.Result(
            snapped = 100f,
            guide = SnapGuide.GuideLine(100f, SnapGuide.Edge.LEFT, "p1"),
            offset = 0f,
        )
        val seg = SnapGuide.segment(r, dragged, peers)!!
        // 竖线：x 相同，y 张卡的上沿之间
        assertEquals("竖线 x 相同", seg.fromX, seg.toX, 0.0001f)
        assertEquals(100f, seg.fromX, 0.0001f)
        assertEquals("起点在拖动卡的上沿", 200f, seg.fromY, 0.0001f)
        assertEquals("终点在邻卡的上沿", 400f, seg.toY, 0.0001f)
    }

    @Test
    fun `上对齐时画横线`() {
        val dragged = SnapGuide.Edges(100f, 200f, 300f, 260f)
        val peers = mapOf("p1" to SnapGuide.Edges(500f, 200f, 700f, 260f))
        val r = SnapGuide.Result(
            snapped = 200f,
            guide = SnapGuide.GuideLine(200f, SnapGuide.Edge.TOP, "p1"),
            offset = 0f,
        )
        val seg = SnapGuide.segment(r, dragged, peers)!!
        assertEquals("横线 y 相同", seg.fromY, seg.toY, 0.0001f)
        assertEquals(200f, seg.fromY, 0.0001f)
        assertEquals("横线的 x 从拖动卡左沿到邻卡左沿", 100f, seg.fromX, 0.0001f)
        assertEquals(500f, seg.toX, 0.0001f)
    }

    @Test
    fun `邻卡找不到时不画`() {
        val dragged = SnapGuide.Edges(100f, 200f, 300f, 260f)
        val r = SnapGuide.Result(
            snapped = 100f,
            guide = SnapGuide.GuideLine(100f, SnapGuide.Edge.LEFT, "missing"),
            offset = 0f,
        )
        assertNull("邻卡不存在时不能崩，直接不画", SnapGuide.segment(r, dragged, emptyMap()))
    }
}
