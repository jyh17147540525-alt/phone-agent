package com.pocketagent.filelogic

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PPTX 生成器的离线验证。
 *
 * 这一类代码**最适合离线验证**：输入确定 → 产出字节确定。
 * 所以这里不只看"能不能生成"，还逐条钉住 OOXML 骨架里那些
 * 「少一个 PowerPoint 就弹修复对话框」的部件。
 */
class PptxWriterTest {

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
            }
        }
        return out
    }

    private fun deck(vararg slides: PptxWriter.Slide) = PptxWriter.write(slides.toList())

    @Test
    fun `骨架九件套一个都不能少`() {
        val parts = unzip(deck(PptxWriter.Slide("第一页"))).keys
        for (required in listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "ppt/presentation.xml",
            "ppt/_rels/presentation.xml.rels",
            "ppt/slideMasters/slideMaster1.xml",
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            "ppt/slideLayouts/slideLayout1.xml",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            "ppt/theme/theme1.xml",
            "ppt/slides/slide1.xml",
            "ppt/slides/_rels/slide1.xml.rels",
        )) {
            assertTrue("缺部件：$required（PowerPoint 会判文件损坏）", parts.contains(required))
        }
    }

    @Test
    fun `同样的内容产出同样的字节`() {
        val a = deck(PptxWriter.Slide("标题", listOf("要点")))
        val b = deck(PptxWriter.Slide("标题", listOf("要点")))
        assertTrue("时间戳或顺序不稳定，sha256 会每次都变", a.contentEquals(b))
    }

    @Test
    fun `三页就是三个 slide 部件且清单里都引到`() {
        val parts = unzip(
            deck(
                PptxWriter.Slide("一"),
                PptxWriter.Slide("二", listOf("a")),
                PptxWriter.Slide("三", listOf("b", "c")),
            ),
        )
        assertTrue(parts.containsKey("ppt/slides/slide3.xml"))
        assertFalse(parts.containsKey("ppt/slides/slide4.xml"))
        val presentation = parts.getValue("ppt/presentation.xml")
        assertTrue(presentation.contains("""r:id="rId3""""))
        assertTrue(presentation.contains("""r:id="rId5""""))
        // rels 里的 id 必须与 sldIdLst 逐字对上，否则打开少几页且不报错
        val rels = parts.getValue("ppt/_rels/presentation.xml.rels")
        assertTrue(rels.contains("""Id="rId5" Type=""""))
        assertTrue(rels.contains("Target=\"slides/slide3.xml\""))
    }

    @Test
    fun `标题与要点进正文，副标题单独一段`() {
        val parts = unzip(
            deck(PptxWriter.Slide(title = "季度回顾", subtitle = "2026 Q4", bullets = listOf("收入涨 12%", "留存 68%"))),
        )
        val slide = parts.getValue("ppt/slides/slide1.xml")
        assertTrue(slide.contains("季度回顾"))
        assertTrue(slide.contains("2026 Q4"))
        assertTrue(slide.contains("收入涨 12%"))
        assertTrue(slide.contains("留存 68%"))
    }

    @Test
    fun `要点才有项目符号，标题没有`() {
        val slide = unzip(deck(PptxWriter.Slide("标题", listOf("要点")))).getValue("ppt/slides/slide1.xml")
        // 标题段：buNone。要点段：buChar。
        assertTrue(slide.contains("<a:buNone/>"))
        assertTrue(slide.contains("""<a:buChar char="•"/>"""))
    }

    @Test
    fun `XML 特殊字符被转义，尖括号不会撕坏文件`() {
        val slide = unzip(deck(PptxWriter.Slide("A & B <tag>", listOf("\"引号\" 与 '撇号'"))))
            .getValue("ppt/slides/slide1.xml")
        assertTrue(slide.contains("A &amp; B &lt;tag&gt;"))
        assertTrue(slide.contains("&quot;引号&quot;"))
        assertTrue(slide.contains("&apos;撇号&apos;"))
    }

    @Test
    fun `换行拆成 a-br 而不是塞进 a-t`() {
        val slide = unzip(deck(PptxWriter.Slide("两行\n标题"))).getValue("ppt/slides/slide1.xml")
        assertTrue(slide.contains("<a:br/>"))
        assertFalse("a:t 里的 \\n 会被 PowerPoint 当普通字符丢掉", slide.contains("两行\n标题"))
    }

    @Test
    fun `emoji 不会被代理对切碎`() {
        val slide = unzip(deck(PptxWriter.Slide("完成 🎉 了"))).getValue("ppt/slides/slide1.xml")
        assertTrue(slide.contains("🎉"))
    }

    @Test
    fun `超长文本截断并加省略号`() {
        val long = "字".repeat(PptxWriter.MAX_TEXT_CHARS + 50)
        val slide = unzip(deck(PptxWriter.Slide(long))).getValue("ppt/slides/slide1.xml")
        assertTrue(slide.contains("…"))
        assertFalse(slide.contains("字".repeat(PptxWriter.MAX_TEXT_CHARS + 1)))
    }

    @Test
    fun `零页也产出合法包，只是没有 slide 部件`() {
        val parts = unzip(PptxWriter.write(emptyList()))
        assertFalse(parts.keys.any { it.startsWith("ppt/slides/") })
        assertTrue(parts.containsKey("ppt/presentation.xml"))
    }

    @Test
    fun `只有标题没有要点是合法的过渡页`() {
        val slide = unzip(deck(PptxWriter.Slide("第二章"))).getValue("ppt/slides/slide1.xml")
        assertTrue(slide.contains("第二章"))
        // 没有正文框：shape id 4 不该出现
        assertFalse(slide.contains("""name="正文 1""""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `标题不能为空`() {
        PptxWriter.Slide("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `页数超上限要拒绝而不是悄悄截断`() {
        PptxWriter.write(List(PptxWriter.MAX_SLIDES + 1) { PptxWriter.Slide("第 $it 页") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `单页要点超上限要拒绝`() {
        PptxWriter.write(
            listOf(PptxWriter.Slide("标题", List(PptxWriter.MAX_BULLETS + 1) { "要点 $it" })),
        )
    }
}
