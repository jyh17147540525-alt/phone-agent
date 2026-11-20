package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyFilesTest {

    @Test
    fun `认出输出目录里的文件`() {
        val files = ReplyFiles.parse("做好了，在 输出/季度回顾.pptx 里。")
        assertEquals(1, files.size)
        assertEquals("输出/季度回顾.pptx", files.first().path)
        assertEquals("季度回顾.pptx", files.first().name)
        assertEquals(ReplyFileKind.OFFICE, files.first().kind)
    }

    @Test
    fun `认出绝对路径与图片`() {
        val files = ReplyFiles.parse("图在 /sdcard/PocketAgentTest/输出/a.png ，看一眼。")
        assertEquals(1, files.size)
        assertEquals(ReplyFileKind.IMAGE, files.first().kind)
    }

    @Test
    fun `同一个文件只出现一次`() {
        val files = ReplyFiles.parse("输出/a.pdf 和 输出/a.pdf 是同一个。")
        assertEquals(1, files.size)
    }

    @Test
    fun `没有扩展名的不算文件`() {
        assertTrue(ReplyFiles.parse("东西都在 输出/ 下面。").isEmpty())
    }

    @Test
    fun `不是文件的扩展名不算`() {
        assertTrue(ReplyFiles.parse("我在想 3.5 这个数。").isEmpty())
    }

    @Test
    fun `最多六张卡片`() {
        val text = (1..10).joinToString(" ") { "输出/f$it.png" }
        assertEquals(ReplyFiles.MAX_FILES, ReplyFiles.parse(text).size)
    }

    @Test
    fun `贴着中文也能认出来`() {
        val files = ReplyFiles.parse("保存成了输出/周报.docx，你打开看看")
        assertEquals("输出/周报.docx", files.first().path)
    }

    @Test
    fun `哪些格式应用内能打开`() {
        assertTrue(ReplyFiles.openableInApp(ReplyFileKind.IMAGE))
        assertTrue(ReplyFiles.openableInApp(ReplyFileKind.PDF))
        assertTrue(ReplyFiles.openableInApp(ReplyFileKind.TEXT))
        assertTrue(ReplyFiles.openableInApp(ReplyFileKind.OFFICE))
        assertFalse(ReplyFiles.openableInApp(ReplyFileKind.ARCHIVE))
        assertFalse(ReplyFiles.openableInApp(ReplyFileKind.AUDIO))
    }

    @Test
    fun `打不开时给的是人话`() {
        assertTrue(ReplyFiles.unsupportedNote(ReplyFileKind.ARCHIVE).contains("压缩包"))
        assertTrue(ReplyFiles.unsupportedNote(ReplyFileKind.OTHER).contains("工作区"))
    }

    @Test
    fun `空回复没有文件`() {
        assertTrue(ReplyFiles.parse("").isEmpty())
    }
}
