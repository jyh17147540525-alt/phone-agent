package com.pocketagent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 图片与 PDF 工具的参数把关与话术（真渲染在 `:app`，这里替换成假端口）。 */
class MediaToolsTest {

    private class FakeFiles : WorkFilePort {
        val files = LinkedHashMap<String, ByteArray>()
        override suspend fun read(relativePath: String): ByteArray =
            files[relativePath] ?: throw java.io.FileNotFoundException(relativePath)
        override suspend fun write(relativePath: String, bytes: ByteArray) {
            files[relativePath] = bytes
        }
    }

    private class FakeImages : ImagePort {
        var lastFormat: ImageFormat? = null
        var lastEdge = 0
        var lastQuality = 0
        override suspend fun convert(src: ByteArray, format: ImageFormat, maxWidth: Int, quality: Int): ByteArray {
            lastFormat = format
            lastEdge = maxWidth
            lastQuality = quality
            return ByteArray(10) { 1 }
        }
    }

    private class FakePdf : PdfPort {
        var lastPages: List<Int> = emptyList()
        var lastScale = 0
        override suspend fun renderPages(src: ByteArray, pages: List<Int>, scale: Int): List<ByteArray> {
            lastPages = pages
            lastScale = scale
            return pages.map { ByteArray(4) { 7 } }
        }

        override suspend fun buildPdf(images: List<ByteArray>): ByteArray = ByteArray(images.size * 3)
    }

    private val pdfBytes = "%PDF-1.4 fake".toByteArray()

    // ── image_convert ─────────────────────────────────────────

    @Test
    fun `转图片按 dst 扩展名选格式`() = runBlocking {
        val files = FakeFiles().apply { files["a.png"] = ByteArray(20) }
        val images = FakeImages()
        val out = ImageConvertTool(files, images).call(
            buildJsonObject {
                put("src", "a.png")
                put("dst", "输出/b.jpg")
            },
        )
        assertTrue(out is ToolOutcome.Success)
        assertEquals(ImageFormat.JPEG, images.lastFormat)
        assertEquals("输出/b.jpg", files.files.keys.last())
        assertTrue((out as ToolOutcome.Success).text.contains("转好了"))
    }

    @Test
    fun `不给 dst 就改扩展名存同目录`() = runBlocking {
        val files = FakeFiles().apply { files["pic/a.png"] = ByteArray(20) }
        ImageConvertTool(files, FakeImages()).call(
            buildJsonObject {
                put("src", "pic/a.png")
                put("format", "webp")
            },
        )
        assertTrue(files.files.containsKey("pic/a.webp"))
    }

    @Test
    fun `尺寸被夹在范围内`() = runBlocking {
        val files = FakeFiles().apply { files["a.png"] = ByteArray(20) }
        val images = FakeImages()
        ImageConvertTool(files, images).call(
            buildJsonObject {
                put("src", "a.png")
                put("maxWidth", 99_999)
                put("quality", 0)
            },
        )
        assertEquals(ImageConvertTool.MAX_EDGE, images.lastEdge)
        assertEquals(1, images.lastQuality)
    }

    @Test
    fun `原图不存在时提示路径`() = runBlocking {
        val out = ImageConvertTool(FakeFiles(), FakeImages()).call(buildJsonObject { put("src", "没有.png") })
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("没有「没有.png」"))
    }

    // ── pdf_pages ─────────────────────────────────────────────

    @Test
    fun `页号从 1 开始换算成 0 基`() = runBlocking {
        val files = FakeFiles().apply { files["a.pdf"] = pdfBytes }
        val pdf = FakePdf()
        val out = PdfPagesTool(files, pdf).call(
            buildJsonObject {
                put("src", "a.pdf")
                put("pages", JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(3))))
            },
        )
        assertTrue(out is ToolOutcome.Success)
        assertEquals(listOf(1, 2), pdf.lastPages)
        assertEquals(
            listOf("a.pages/page-2.png", "a.pages/page-3.png"),
            files.files.keys.filter { it.endsWith(".png") },
        )
    }

    @Test
    fun `不是 PDF 的文件直接说清楚`() = runBlocking {
        val files = FakeFiles().apply { files["a.pdf"] = "hello".toByteArray() }
        val out = PdfPagesTool(files, FakePdf()).call(buildJsonObject { put("src", "a.pdf") })
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("%PDF"))
    }

    @Test
    fun `页号 0 会被拦住`() = runBlocking {
        val files = FakeFiles().apply { files["a.pdf"] = pdfBytes }
        val out = PdfPagesTool(files, FakePdf()).call(
            buildJsonObject { put("src", "a.pdf"); put("pages", JsonArray(listOf(JsonPrimitive(0)))) },
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("从 1 开始"))
    }

    @Test
    fun `一次转太多页会被拦住`() = runBlocking {
        val files = FakeFiles().apply { files["a.pdf"] = pdfBytes }
        val many = (1..PdfPagesTool.MAX_PAGES + 1).map { JsonPrimitive(it) }
        val out = PdfPagesTool(files, FakePdf()).call(
            buildJsonObject { put("src", "a.pdf"); put("pages", JsonArray(many)) },
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("最多"))
    }

    // ── pdf_from_images ───────────────────────────────────────

    @Test
    fun `图片拼成 PDF`() = runBlocking {
        val files = FakeFiles().apply {
            files["1.png"] = ByteArray(8)
            files["2.png"] = ByteArray(8)
        }
        val out = PdfFromImagesTool(files, FakePdf()).call(
            buildJsonObject {
                put("images", JsonArray(listOf(JsonPrimitive("1.png"), JsonPrimitive("2.png"))))
                put("dst", "合辑")
            },
        )
        assertTrue(out is ToolOutcome.Success)
        assertTrue((out as ToolOutcome.Success).text.contains("2 页"))
        assertTrue(files.files.containsKey("合辑.pdf"))
    }

    @Test
    fun `拼 PDF 缺参数会说清`() = runBlocking {
        val tool = PdfFromImagesTool(FakeFiles(), FakePdf())
        val noDst = tool.call(buildJsonObject {
            put("images", JsonArray(listOf(JsonPrimitive("1.png"))))
        })
        assertTrue((noDst as ToolOutcome.Failed).reason.contains("存哪里"))

        val noImages = tool.call(buildJsonObject { put("dst", "a.pdf") })
        assertTrue((noImages as ToolOutcome.Failed).reason.contains("至少一张"))
    }
}
