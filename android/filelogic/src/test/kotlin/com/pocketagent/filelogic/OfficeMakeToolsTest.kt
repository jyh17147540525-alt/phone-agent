package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `make_slides` / `make_doc` 的**校验层**验证。
 *
 * 这一层的价值不是"能不能读懂 JSON"，而是：**不合规的请求必须在碰文件系统之前被拒**。
 * 所以每条用例都盯着"拒绝理由说不说得清" —— 模型会读它，然后改一次再试。
 */
class OfficeMakeToolsTest {

    private val policy = WorkspacePolicy()

    private fun call(tool: String, vararg args: Pair<String, String>) =
        OfficeToolCall(tool = OfficeTool.byId(tool)!!, args = args.toMap())

    // ── make_slides ─────────────────────────────────────────────

    @Test
    fun `make_slides 通过并纠正扩展名`() {
        val r = OfficeToolValidator.validate(
            call(
                "make_slides",
                "path" to "输出/汇报.ppt",
                "slides" to """[{"title":"季度回顾","bullets":["收入涨 12%","留存 68%"]}]""",
            ),
            policy,
        )
        val allowed = r as ToolValidation.Allowed
        val request = allowed.request as OfficeRequest.MakeSlides
        assertEquals("输出/汇报.pptx", request.relativePath)
        assertEquals(1, request.slides.size)
        assertEquals("季度回顾", request.slides[0].title)
        assertEquals(listOf("收入涨 12%", "留存 68%"), request.slides[0].bullets)
    }

    @Test
    fun `make_slides 带副标题`() {
        val r = OfficeToolValidator.validate(
            call(
                "make_slides",
                "path" to "输出/a.pptx",
                "slides" to """[{"title":"封面","subtitle":"2026 Q4","bullets":[]}]""",
            ),
            policy,
        )
        val request = (r as ToolValidation.Allowed).request as OfficeRequest.MakeSlides
        assertEquals("2026 Q4", request.slides[0].subtitle)
        assertTrue(request.slides[0].bullets.isEmpty())
    }

    @Test
    fun `make_slides 缺 path 被拒且说清缺什么`() {
        val r = OfficeToolValidator.validate(call("make_slides", "slides" to "[]"), policy)
        assertTrue((r as ToolValidation.Denied).reason.contains("path"))
    }

    @Test
    fun `make_slides 的 slides 不是数组时给出示例`() {
        val r = OfficeToolValidator.validate(
            call("make_slides", "path" to "输出/a.pptx", "slides" to """{"title":"x"}"""),
            policy,
        )
        val reason = (r as ToolValidation.Denied).reason
        assertTrue(reason, reason.contains("JSON"))
        assertTrue("要给出可照抄的形状", reason.contains("title"))
    }

    @Test
    fun `make_slides 空数组被拒`() {
        val r = OfficeToolValidator.validate(
            call("make_slides", "path" to "输出/a.pptx", "slides" to "[]"),
            policy,
        )
        assertTrue((r as ToolValidation.Denied).reason.contains("空"))
    }

    @Test
    fun `make_slides 有一页没标题被拒并指出第几页`() {
        val r = OfficeToolValidator.validate(
            call(
                "make_slides",
                "path" to "输出/a.pptx",
                "slides" to """[{"title":"好"},{"bullets":["x"]}]""",
            ),
            policy,
        )
        val reason = (r as ToolValidation.Denied).reason
        assertTrue(reason, reason.contains("第 2 页"))
    }

    @Test
    fun `make_slides 要点超上限被拒`() {
        val bullets = (1..PptxWriter.MAX_BULLETS + 1).joinToString(",") { "\"p$it\"" }
        val r = OfficeToolValidator.validate(
            call("make_slides", "path" to "输出/a.pptx", "slides" to """[{"title":"t","bullets":[$bullets]}]"""),
            policy,
        )
        assertTrue((r as ToolValidation.Denied).reason.contains("上限"))
    }

    @Test
    fun `make_slides 同样只能写到产出目录`() {
        val r = OfficeToolValidator.validate(
            call("make_slides", "path" to "a.pptx", "slides" to """[{"title":"t"}]"""),
            policy,
        )
        assertTrue((r as ToolValidation.Denied).reason.contains(policy.outputSubdir))
    }

    // ── make_doc ────────────────────────────────────────────────

    @Test
    fun `make_doc 解析标题与段落`() {
        val r = OfficeToolValidator.validate(
            call(
                "make_doc",
                "path" to "输出/报告.txt",
                "blocks" to """[{"heading":1,"text":"季度报告"},{"text":"第一段"},{"text":""}]""",
            ),
            policy,
        )
        val request = (r as ToolValidation.Allowed).request as OfficeRequest.MakeDoc
        assertEquals("输出/报告.docx", request.relativePath)
        assertEquals(3, request.blocks.size)
        assertTrue(request.blocks[0] is DocxWriter.Block.Heading)
        assertEquals(1, (request.blocks[0] as DocxWriter.Block.Heading).level)
        assertTrue(request.blocks[1] is DocxWriter.Block.Paragraph)
        // 空段落是合法的：用户就是想要一个空行
        assertEquals("", (request.blocks[2] as DocxWriter.Block.Paragraph).text)
    }

    @Test
    fun `make_doc 标题层级越界被拒`() {
        val r = OfficeToolValidator.validate(
            call("make_doc", "path" to "输出/a.docx", "blocks" to """[{"heading":9,"text":"深"}]"""),
            policy,
        )
        val reason = (r as ToolValidation.Denied).reason
        assertTrue(reason, reason.contains("heading"))
        assertTrue(reason, reason.contains(DocxWriter.MAX_HEADING_LEVEL.toString()))
    }

    @Test
    fun `make_doc 空数组被拒`() {
        val r = OfficeToolValidator.validate(
            call("make_doc", "path" to "输出/a.docx", "blocks" to "[]"),
            policy,
        )
        assertTrue((r as ToolValidation.Denied).reason.contains("空"))
    }

    @Test
    fun `make_doc 不许写到产出目录之外`() {
        val r = OfficeToolValidator.validate(
            call("make_doc", "path" to "../乱写.docx", "blocks" to """[{"text":"x"}]"""),
            policy,
        )
        assertTrue(r is ToolValidation.Denied)
    }

    // ── 工具目录本身 ────────────────────────────────────────────

    @Test
    fun `新工具登记在案且可写`() {
        for (id in listOf("make_slides", "make_doc")) {
            val tool = OfficeTool.byId(id)
            assertTrue("工具目录里没有 $id", tool != null)
            assertEquals("$id 必须是写操作", false, tool!!.readOnly)
        }
    }
}
