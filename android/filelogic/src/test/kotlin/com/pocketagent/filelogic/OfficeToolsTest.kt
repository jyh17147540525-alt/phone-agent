package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 办公工具的解析与校验测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 本文件里最要紧的三组
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 路径穿越**（`路径穿越一律被拒`）。
 *    模型完全可能在一次"看起来合理"的调用里写 `../../DCIM` ——
 *    它没有恶意，只是**想帮用户找文件**。
 *
 * **2. 产出必须落在产出目录下**（`写路径不在产出目录下被拒`）。
 *    这条是**结构性**的：智能体在结构上不可能覆盖用户的原文件，
 *    而不是"靠每次弹确认框提醒用户"。
 *
 * **3. 拒绝而不是静默改写**（`不在产出目录下时拒绝而不是改写`）。
 *    改写会让模型以为写到了 `报告.md`，实际写到 `输出/报告.md` ——
 *    它下次读 `报告.md` 会读不到，然后困惑、重试、放弃。
 *    **一个能被理解的拒绝，比一个静默的善意修正更省事。**
 */
class OfficeToolsTest {

    private val policy = WorkspacePolicy() // 默认：输出目录「输出」，能写不能删

    private fun parseOk(json: String): OfficeToolCall =
        when (val r = OfficeToolParser.parse(json)) {
            is ToolParseOutcome.Ready -> r.call
            is ToolParseOutcome.Invalid -> throw AssertionError("期望解析成功，实际：${r.reason}")
        }

    private fun denied(v: ToolValidation): String {
        assertTrue("期望被拒绝，实际是 $v", v is ToolValidation.Denied)
        return (v as ToolValidation.Denied).reason
    }

    private fun allowed(v: ToolValidation): OfficeRequest {
        assertTrue("期望通过，实际是 $v", v is ToolValidation.Allowed)
        return (v as ToolValidation.Allowed).request
    }

    // ── 解析 ────────────────────────────────────────────────────

    @Test
    fun `解析标准形态`() {
        val call = parseOk("""{"tool":"read_text","args":{"path":"报告.md"}}""")

        assertEquals(OfficeTool.READ_TEXT, call.tool)
        assertEquals("报告.md", call.args["path"])
    }

    @Test
    fun `解析参数平铺在顶层的形态`() {
        // 模型经常这么写，而只认一种会让一半调用被判成"缺参数"
        val call = parseOk("""{"tool":"read_text","path":"报告.md"}""")

        assertEquals(OfficeTool.READ_TEXT, call.tool)
        assertEquals("报告.md", call.args["path"])
    }

    @Test
    fun `非 JSON 报解析失败`() {
        assertTrue(OfficeToolParser.parse("这不是 json") is ToolParseOutcome.Invalid)
    }

    @Test
    fun `缺少 tool 字段报失败`() {
        assertTrue(OfficeToolParser.parse("""{"path":"a.md"}""") is ToolParseOutcome.Invalid)
    }

    @Test
    fun `未知工具报失败且列出可用工具`() {
        val r = OfficeToolParser.parse("""{"tool":"delete_everything"}""") as ToolParseOutcome.Invalid

        assertTrue("原因里应列出可用工具，便于模型自我纠正", r.reason.contains("list_files"))
    }

    // ── ★★★ 路径穿越 ───────────────────────────────────────────

    @Test
    fun `路径穿越一律被拒`() {
        // 模型没有恶意，只是"想帮用户找文件"
        listOf("../secret.txt", "a/../../b.txt", "../../DCIM/photo.jpg", "..").forEach { bad ->
            val call = parseOk("""{"tool":"read_text","args":{"path":"$bad"}}""")
            val reason = denied(OfficeToolValidator.validate(call, policy))
            assertTrue("「$bad」必须被拒，理由：$reason", reason.contains("不合法"))
        }
    }

    @Test
    fun `绝对路径被拒`() {
        listOf("/etc/passwd", "/sdcard/DCIM/a.jpg", "~/x.txt").forEach { bad ->
            val call = parseOk("""{"tool":"read_text","args":{"path":"$bad"}}""")
            denied(OfficeToolValidator.validate(call, policy))
        }
    }

    @Test
    fun `反斜杠会被当成路径分隔符从而暴露穿越`() {
        // Windows 风格的写法不能成为绕过通道
        val call = parseOk("""{"tool":"read_text","args":{"path":"a\\..\\..\\b.txt"}}""")
        denied(OfficeToolValidator.validate(call, policy))
    }

    @Test
    fun `正常相对路径通过`() {
        val call = parseOk("""{"tool":"read_text","args":{"path":"子目录/报告.md"}}""")
        val req = allowed(OfficeToolValidator.validate(call, policy)) as OfficeRequest.ReadText

        assertEquals("子目录/报告.md", req.relativePath)
    }

    @Test
    fun `根目录用点表示`() {
        val call = parseOk("""{"tool":"list_files","args":{"path":"."}}""")
        val req = allowed(OfficeToolValidator.validate(call, policy)) as OfficeRequest.ListFiles

        assertEquals("", req.relativePath)
    }

    @Test
    fun `list_files 不给路径时默认根目录`() {
        val call = parseOk("""{"tool":"list_files","args":{}}""")
        val req = allowed(OfficeToolValidator.validate(call, policy)) as OfficeRequest.ListFiles

        assertEquals("", req.relativePath)
    }

    // ── ★★★ 产出必须落在产出目录下 ─────────────────────────────

    @Test
    fun `写路径不在产出目录下被拒`() {
        // 这条是**结构性**的：智能体在结构上不可能覆盖用户的原文件
        listOf("报告.md", "子目录/报告.md", "/输出/x.md", "输出别的/x.md").forEach { bad ->
            val call = parseOk("""{"tool":"write_text","args":{"path":"$bad","content":"hi"}}""")
            denied(OfficeToolValidator.validate(call, policy))
        }
    }

    @Test
    fun `不在产出目录下时拒绝而不是改写`() {
        // 改写会让模型以为写到了 报告.md，实际写到 输出/报告.md ——
        // 它下次读 报告.md 会读不到，然后困惑、重试、放弃
        val call = parseOk("""{"tool":"write_text","args":{"path":"报告.md","content":"hi"}}""")

        val reason = denied(OfficeToolValidator.validate(call, policy))

        assertTrue(
            "拒绝理由要说明只能写到产出目录，实际是「$reason」",
            reason.contains("输出"),
        )
    }

    @Test
    fun `产出目录下的路径通过`() {
        val call = parseOk("""{"tool":"write_text","args":{"path":"输出/报告.md","content":"hi"}}""")
        val req = allowed(OfficeToolValidator.validate(call, policy)) as OfficeRequest.WriteText

        assertEquals("输出/报告.md", req.relativePath)
        assertEquals("hi", req.content)
    }

    @Test
    fun `产出目录名可以自定义`() {
        val custom = WorkspacePolicy(outputSubdir = "产物")
        val call = parseOk("""{"tool":"write_text","args":{"path":"产物/a.md","content":"x"}}""")

        allowed(OfficeToolValidator.validate(call, custom))
        // 而默认目录名此时不再通行
        val old = parseOk("""{"tool":"write_text","args":{"path":"输出/a.md","content":"x"}}""")
        denied(OfficeToolValidator.validate(old, custom))
    }

    @Test
    fun `只给产出目录名而没有文件名被拒`() {
        val call = parseOk("""{"tool":"write_text","args":{"path":"输出","content":"x"}}""")

        denied(OfficeToolValidator.validate(call, policy))
    }

    // ── 策略 ────────────────────────────────────────────────────

    @Test
    fun `不允许写入时拒绝写操作`() {
        val noWrite = WorkspacePolicy(allowWrite = false)
        val call = parseOk("""{"tool":"write_text","args":{"path":"输出/a.md","content":"x"}}""")

        val reason = denied(OfficeToolValidator.validate(call, noWrite))

        assertTrue("理由要提到工作区不允许写入，实际「$reason」", reason.contains("不允许写入"))
    }

    @Test
    fun `不允许写入时只读工具仍然可用`() {
        // "不能写"不等于"不能用" —— 只读的整理/总结类任务在只读工作区里完全合理
        val noWrite = WorkspacePolicy(allowWrite = false)
        val call = parseOk("""{"tool":"list_files","args":{"path":"."}}""")

        allowed(OfficeToolValidator.validate(call, noWrite))
    }

    @Test
    fun `内容超过上限被拒`() {
        val small = WorkspacePolicy(maxFileBytes = 16)
        val call = parseOk("""{"tool":"write_text","args":{"path":"输出/a.md","content":"这段内容肯定超过十六个字节了"}}""")

        val reason = denied(OfficeToolValidator.validate(call, small))

        assertTrue("理由要给出实际大小与上限，实际「$reason」", reason.contains("超过上限"))
    }

    @Test
    fun `上限按字节算而不是字符数`() {
        // 一个汉字 3 字节 —— 按字符算会让中文的上限悄悄变成三倍。
        //
        // ⚠️ 这条测试的数据是**数出来的**，不是估的。
        //    第一版我按"9 个汉字"估，而上限写 24 —— 结果「九个汉字正好超限」
        //    实际是 8 个字符 = **24 字节**，正好没超过 24，于是测试红了。
        //    而红的是测试、不是实现。**凡是要断言的数字，先数一遍。**
        val policy23 = WorkspacePolicy(maxFileBytes = 23)
        val call = parseOk("""{"tool":"write_text","args":{"path":"输出/a.md","content":"九个汉字正好超限"}}""")

        val reason = denied(OfficeToolValidator.validate(call, policy23))

        assertTrue("理由要给出实际字节数 24，实际是「$reason」", reason.contains("24"))
    }

    @Test
    fun `读取上限不超过策略上限`() {
        val small = WorkspacePolicy(maxFileBytes = 1024)
        val call = parseOk("""{"tool":"read_text","args":{"path":"a.md","max_bytes":"999999"}}""")

        val req = allowed(OfficeToolValidator.validate(call, small)) as OfficeRequest.ReadText

        assertTrue("调用方要得再多也不能超过策略上限", req.maxBytes <= 1024)
    }

    // ── 表格 ────────────────────────────────────────────────────

    @Test
    fun `make_table 默认 csv`() {
        val call = parseOk("""{"tool":"make_table","args":{"path":"输出/a.csv"}}""")
        val req = allowed(OfficeToolValidator.validate(call, policy)) as OfficeRequest.MakeTable

        assertEquals(DocumentFormat.CSV, req.format)
    }

    @Test
    fun `make_table 支持 xlsx 与 excel 两种写法`() {
        listOf("xlsx", "excel", "XLSX").forEach { fmt ->
            val call = parseOk("""{"tool":"make_table","args":{"path":"输出/a.xlsx","format":"$fmt"}}""")
            val req = allowed(OfficeToolValidator.validate(call, policy)) as OfficeRequest.MakeTable
            assertEquals(DocumentFormat.XLSX, req.format)
        }
    }

    @Test
    fun `make_table 不支持的格式被拒`() {
        val call = parseOk("""{"tool":"make_table","args":{"path":"输出/a.pdf","format":"pdf"}}""")

        val reason = denied(OfficeToolValidator.validate(call, policy))

        assertTrue("理由要说明支持哪些格式，实际「$reason」", reason.contains("csv"))
    }

    @Test
    fun `make_table 解析行列数据`() {
        val call = parseOk("""{"tool":"make_table","args":{"path":"输出/a.csv","header":"姓名,年龄","rows":"张三,30;李四,25"}}""")
        val req = allowed(OfficeToolValidator.validate(call, policy)) as OfficeRequest.MakeTable

        assertEquals(listOf("姓名", "年龄"), req.header)
        assertEquals(listOf(listOf("张三", "30"), listOf("李四", "25")), req.rows)
    }

    @Test
    fun `make_table 同样受产出目录约束`() {
        val call = parseOk("""{"tool":"make_table","args":{"path":"a.csv"}}""")

        denied(OfficeToolValidator.validate(call, policy))
    }

    // ── 工具表本身 ──────────────────────────────────────────────

    @Test
    fun `只读与可写工具的分组是明确的`() {
        // 分阶段开放工具的依据就是 readOnly 这一个字段 ——
        // 一旦"哪些工具能用"变成分散判断，迟早会有分支漏掉，
        // 而漏掉的后果是"在只读阶段执行了写操作"
        val readOnly = OfficeTool.entries.filter { it.readOnly }.map { it.id }.toSet()
        val writable = OfficeTool.entries.filter { !it.readOnly }.map { it.id }.toSet()

        assertEquals(setOf("list_files", "read_text"), readOnly)
        assertEquals(setOf("write_text", "make_table"), writable)
        assertTrue("两个集合不能有交集", (readOnly intersect writable).isEmpty())
    }

    @Test
    fun `工具 id 唯一且都能反查`() {
        val ids = OfficeTool.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertEquals(OfficeTool.byId(it)!!.id, it) }
        assertEquals(null, OfficeTool.byId("不存在"))
    }

    @Test
    fun `每个工具都有非空说明`() {
        OfficeTool.entries.forEach {
            assertTrue("${it.id} 的说明不能为空 —— 模型要靠它判断该不该用", it.description.isNotBlank())
        }
    }
}
