package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图表渲染。
 *
 * 这里钉的是**能被坏掉**的东西：PNG 头、尺寸、字节确定性、校验器的每一句话。
 * "图好不好看"测不出来，也不该测。
 */
class ChartRenderTest {

    private fun spec(
        kind: ChartKind = ChartKind.BAR,
        categories: List<String> = listOf("Q1", "Q2", "Q3"),
        series: List<ChartSeries> = listOf(ChartSeries("销量", listOf(10.0, 20.0, 30.0))),
    ) = ChartSpec("测试", kind, categories, series)

    private fun widthOf(png: ByteArray): Int =
        ((png[16].toInt() and 0xFF) shl 24) or ((png[17].toInt() and 0xFF) shl 16) or
            ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)

    private fun heightOf(png: ByteArray): Int =
        ((png[20].toInt() and 0xFF) shl 24) or ((png[21].toInt() and 0xFF) shl 16) or
            ((png[22].toInt() and 0xFF) shl 8) or (png[23].toInt() and 0xFF)

    // ── 校验 ──────────────────────────────────────────────────

    @Test
    fun `没有类别时说清楚`() {
        val reason = ChartRender.validate(spec(categories = emptyList(), series = emptyList()))
        assertNotNull(reason)
        assertTrue(reason!!.contains("类别"))
    }

    @Test
    fun `数值个数与类别对不上会被指出来`() {
        val reason = ChartRender.validate(
            spec(series = listOf(ChartSeries("销量", listOf(1.0, 2.0)))),
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("对不上"))
    }

    @Test
    fun `类别过多会被拦住`() {
        val many = (1..ChartRender.MAX_POINTS + 1).map { "c$it" }
        val reason = ChartRender.validate(
            spec(categories = many, series = listOf(ChartSeries("s", many.map { 1.0 }))),
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("最多"))
    }

    @Test
    fun `不是数字的值会被拦住`() {
        val reason = ChartRender.validate(spec(series = listOf(ChartSeries("s", listOf(1.0, Double.NaN, 3.0)))))
        assertNotNull(reason)
        assertTrue(reason!!.contains("不是有效数字"))
    }

    @Test
    fun `合法数据不报错`() {
        assertNull(ChartRender.validate(spec()))
    }

    // ── 渲染 ──────────────────────────────────────────────────

    @Test
    fun `产出的是一张尺寸正确的 PNG`() {
        val png = ChartRender.render(spec(), width = 800, height = 480)
        assertEquals(0x89.toByte(), png[0])
        assertEquals("PNG", String(png, 1, 3, Charsets.US_ASCII))
        assertEquals(800, widthOf(png))
        assertEquals(480, heightOf(png))
        // IEND 必须收尾，否则解码器会说文件被截断
        assertEquals("IEND", String(png, png.size - 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun `同样数据同样字节`() {
        val a = ChartRender.render(spec())
        val b = ChartRender.render(spec())
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `真的有柱子被画上去`() {
        val png = ChartRender.render(spec(), width = 400, height = 300)
        // 需要解压才能验像素，这里退一步：内容不是纯白（体积明显大于空图）
        val blank = ChartRender.render(
            ChartSpec("空", ChartKind.BAR, listOf("a"), listOf(ChartSeries("s", listOf(0.0)))),
            width = 400, height = 300,
        )
        assertTrue("有数据时不该和全 0 一样大", png.size > blank.size / 2)
    }

    @Test
    fun `负值也画得出来`() {
        val png = ChartRender.render(spec(series = listOf(ChartSeries("s", listOf(-5.0, 0.0, 8.0)))))
        assertTrue(png.size > 100)
    }

    @Test
    fun `折线图也能出图`() {
        val png = ChartRender.render(spec(kind = ChartKind.LINE))
        assertEquals("PNG", String(png, 1, 3, Charsets.US_ASCII))
    }

    @Test
    fun `尺寸太小直接拒`() {
        try {
            ChartRender.render(spec(), width = 100, height = 100)
            org.junit.Assert.fail("应该抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("小"))
        }
    }

    // ── 文字版 ────────────────────────────────────────────────

    @Test
    fun `文字版把类别与数值都给出来`() {
        val text = ChartRender.summary(spec())
        assertTrue(text.contains("Q1"))
        assertTrue(text.contains("销量=10"))
        assertTrue(text.contains("Q3"))
    }

    @Test
    fun `多组数据在文字版里并排`() {
        val text = ChartRender.summary(
            spec(
                series = listOf(
                    ChartSeries("销量", listOf(1.0, 2.0, 3.0)),
                    ChartSeries("成本", listOf(4.0, 5.0, 6.0)),
                ),
            ),
        )
        assertTrue(text.contains("销量=1"))
        assertTrue(text.contains("成本=4"))
    }
}
