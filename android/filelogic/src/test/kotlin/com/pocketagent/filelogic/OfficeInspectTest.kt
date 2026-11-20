package com.pocketagent.filelogic

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeInspectTest {

    @Test
    fun `docx 能读出段落文字`() {
        val bytes = DocxWriter.write(
            listOf(
                DocxWriter.Block.Heading(level = 1, text = "季度回顾"),
                DocxWriter.Block.Paragraph("这一季做完了三件事。"),
            ),
        )
        val result = OfficeInspect.inspect(bytes, "docx")
        assertNotNull(result)
        assertTrue(result!!.text.contains("季度回顾"))
        assertTrue(result.text.contains("这一季做完了三件事"))
    }

    @Test
    fun `pptx 按页读出文字`() {
        val bytes = PptxWriter.write(
            listOf(
                PptxWriter.Slide(title = "第一页", bullets = listOf("要点甲")),
                PptxWriter.Slide(title = "第二页", bullets = listOf("要点乙")),
            ),
        )
        val result = OfficeInspect.inspect(bytes, "pptx")
        assertNotNull(result)
        assertTrue(result!!.text.contains("第 1 页"))
        assertTrue(result.text.contains("第一页"))
        assertTrue(result.text.contains("要点乙"))
    }

    @Test
    fun `xlsx 能读出表里的值`() {
        val bytes = XlsxWriter.write(
            listOf(
                XlsxWriter.Sheet(
                    name = "表1",
                    header = listOf("项目", "数字"),
                    rows = listOf(listOf(XlsxWriter.Cell.Text("苹果"), XlsxWriter.Cell.Text("12"))),
                ),
            ),
        )
        val result = OfficeInspect.inspect(bytes, "xlsx")
        assertNotNull(result)
        assertTrue(result!!.text.contains("苹果"))
        assertTrue(result.text.contains("12"))
    }

    @Test
    fun `不是 zip 的字节读不出来`() {
        assertNull(OfficeInspect.inspect("这不是压缩包".toByteArray(), "docx"))
    }

    @Test
    fun `不认识的扩展名返回空`() {
        assertNull(OfficeInspect.inspect(ByteArray(10), "pages"))
    }
}
