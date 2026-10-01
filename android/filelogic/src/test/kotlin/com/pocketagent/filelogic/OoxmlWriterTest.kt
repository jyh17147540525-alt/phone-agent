package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * OOXML 生成的离线测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这一层为什么值得这么多断言
 * ═══════════════════════════════════════════════════════════════
 *
 * 它产出的是**会被别的程序解析**的字节。失败的表现不是报错，
 * 是"文件能打开、内容是错的" —— 与 [OfficeText] 面对的是同一类敌人。
 *
 * 但这里比 CSV 多一层：**zip 结构本身**。少一个部件、关系 id 对不上、
 * XML 元素顺序不合 schema，都会让 Excel/Word 报"文件已损坏"
 * （而错误信息完全指不到是哪一处）。
 *
 * ⚠️ 所以本文件刻意**自己解包**（`ZipInputStream`）来验证，
 *    而不是只断言"生成的字节非空"。后者是最典型的假覆盖。
 */
class OoxmlWriterTest {

    // ── 工具 ────────────────────────────────────────────────────

    /** 解包成「路径 → 文本」。用于检查部件齐全与内容正确。 */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun xlsx(header: List<String> = listOf("姓名", "年龄"), rows: List<List<XlsxWriter.Cell>> = emptyList()) =
        XlsxWriter.write(listOf(XlsxWriter.Sheet("Sheet1", header, rows)))

    // ── ★★ 确定性：本层唯一的硬承诺 ──────────────────────────────

    @Test
    fun `同样输入两次生成的字节完全相同`() {
        // ★★ 这条是整个 OoxmlPackage 存在的理由。
        //    如果 ZipEntry 用了默认时间戳，两次生成的字节会不同 ——
        //    于是 sha256 每次都变，无法做完整性校验或去重，
        //    而且会与 :memorylogic 的「id 由内容派生、不含时间」直接冲突。
        val a = xlsx(rows = listOf(listOf(XlsxWriter.Cell.Text("张三"))))
        val b = xlsx(rows = listOf(listOf(XlsxWriter.Cell.Text("张三"))))

        assertTrue("同一份内容必须产出同样的字节", a.contentEquals(b))
    }

    @Test
    fun `docx 同样输入两次生成的字节完全相同`() {
        val a = DocxWriter.write(listOf(DocxWriter.Block.Paragraph("你好")))
        val b = DocxWriter.write(listOf(DocxWriter.Block.Paragraph("你好")))

        assertTrue(a.contentEquals(b))
    }

    @Test
    fun `内容不同则字节不同`() {
        val a = xlsx(rows = listOf(listOf(XlsxWriter.Cell.Text("甲"))))
        val b = xlsx(rows = listOf(listOf(XlsxWriter.Cell.Text("乙"))))

        assertFalse("不同内容不该产出相同字节", a.contentEquals(b))
    }

    @Test
    fun `zip 条目时间戳固定在 1980 年而不是生成时刻`() {
        // ⚠️ 这条测试的判据**修正过一次**，过程值得记下来：
        //
        //    第一版写的是「时间戳必须落在生成时刻之外」
        //    （`stamp < before || stamp > after`）。它**抓不住变异** ——
        //    因为 ZIP 的 DOS 时间字段只有 **2 秒精度**，当前时刻被截断后
        //    （如 10:36:24.573 → 10:36:24.000）反而**小于** `before`，
        //    于是"用了当前时间"这个 bug 被判定为通过。
        //
        //    ⇒ 改用「年份必须是 1980」：与时间窗口无关，无法被精度截断糊弄。
        //    实测把 `lastModifiedTime` 换成 `System.currentTimeMillis()` 后，
        //    这条立刻变红 —— 而前一版是绿的。
        //
        //    同一条 zip 里**每个**条目都要查：只查第一个的话，
        //    将来若有人只给第一个条目设了时间，后面全用默认值，仍然漏网。
        ZipInputStream(ByteArrayInputStream(xlsx())).use { zip ->
            var entry = zip.nextEntry
            var checked = 0
            while (entry != null) {
                val year = java.time.Instant.ofEpochMilli(entry.time)
                    .atZone(java.time.ZoneId.systemDefault())
                    .year
                assertEquals(
                    "条目 ${entry.name} 的时间戳应固定在 ZIP 纪元（1980），" +
                        "实际年份 $year —— 说明用了当前时间",
                    1980,
                    year,
                )
                checked++
                zip.closeEntry()
                entry = zip.nextEntry
            }
            assertTrue("至少要检查到一个条目", checked > 0)
        }
    }

    // ── XLSX 结构 ───────────────────────────────────────────────

    @Test
    fun `xlsx 包含全部必需部件`() {
        val parts = unzip(xlsx()).keys

        // 少任何一个，Excel 都会报"文件已损坏"
        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/worksheets/sheet1.xml",
        ).forEach {
            assertTrue("缺少必需部件 $it，实际有 $parts", it in parts)
        }
    }

    @Test
    fun `每个 XML 部件都以声明头开头`() {
        unzip(xlsx()).forEach { (path, text) ->
            if (path.endsWith(".xml") || path.endsWith(".rels")) {
                assertTrue(
                    "$path 必须以 XML 声明开头，实际开头：${text.take(40)}",
                    text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"),
                )
            }
        }
    }

    @Test
    fun `工作表数量与声明的部件一致`() {
        val bytes = XlsxWriter.write(
            listOf(
                XlsxWriter.Sheet("第一张"),
                XlsxWriter.Sheet("第二张"),
            ),
        )
        val parts = unzip(bytes)

        assertTrue("sheet1 必须存在", "xl/worksheets/sheet1.xml" in parts)
        assertTrue("sheet2 必须存在", "xl/worksheets/sheet2.xml" in parts)
        // workbook.xml 里两张表都要登记，且 r:id 要与 rels 对得上
        assertTrue(parts["xl/workbook.xml"]!!.contains("""name="第一张""""))
        assertTrue(parts["xl/workbook.xml"]!!.contains("""name="第二张""""))
        assertTrue(parts["xl/_rels/workbook.xml.rels"]!!.contains("""Id="rId2""""))
    }

    @Test
    fun `表头成为第一行且数据从第二行开始`() {
        val sheet = unzip(xlsx(rows = listOf(listOf(XlsxWriter.Cell.Text("张三")))))["xl/worksheets/sheet1.xml"]!!

        // 表头「姓名」落在 A1
        assertTrue("表头应在 A1", sheet.contains("""<c r="A1" t="inlineStr"><is><t xml:space="preserve">姓名</t>"""))
        // 数据落在 A2
        assertTrue("数据应在 A2", sheet.contains("""<c r="A2" t="inlineStr"><is><t xml:space="preserve">张三</t>"""))
    }

    @Test
    fun `无表头时数据从第一行开始`() {
        val bytes = XlsxWriter.write(
            listOf(XlsxWriter.Sheet("S", header = emptyList(), rows = listOf(listOf(XlsxWriter.Cell.Text("值"))))),
        )
        val sheet = unzip(bytes)["xl/worksheets/sheet1.xml"]!!

        assertTrue("无表头时数据应在 A1", sheet.contains("""<c r="A1""""))
        assertFalse("不该凭空多出第二行", sheet.contains("""<row r="2">"""))
    }

    // ── 类型：文本 vs 数字 ──────────────────────────────────────

    @Test
    fun `文本单元格带 inlineStr 而数字单元格不带`() {
        val bytes = XlsxWriter.write(
            listOf(
                XlsxWriter.Sheet(
                    "S",
                    header = emptyList(),
                    rows = listOf(
                        listOf(XlsxWriter.Cell.Text("007"), XlsxWriter.Cell.Number(7.0)),
                    ),
                ),
            ),
        )
        val sheet = unzip(bytes)["xl/worksheets/sheet1.xml"]!!

        // ★ "007" 必须保持文本 —— 推断成数字会毁掉邮编与身份证号
        assertTrue("文本 007 应原样保留", sheet.contains(""">007</t>"""))
        // 数字走 <v>，没有 t 属性
        assertTrue("数字 7 应走 <v>", sheet.contains("""<c r="B1"><v>7</v></c>"""))
    }

    @Test
    fun `整数数字不写成带小数点的形式`() {
        val bytes = XlsxWriter.write(
            listOf(XlsxWriter.Sheet("S", rows = listOf(listOf(XlsxWriter.Cell.Number(5.0))))),
        )
        val sheet = unzip(bytes)["xl/worksheets/sheet1.xml"]!!

        assertTrue("5.0 应写成 5", sheet.contains("<v>5</v>"))
        assertFalse("不该出现 5.0", sheet.contains("<v>5.0</v>"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `NaN 数字被拒绝`() {
        // 写进 <v> 之后 Excel 报的是"文件已损坏"，排查方向会被完全带偏
        XlsxWriter.Cell.Number(Double.NaN)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `无穷大数字被拒绝`() {
        XlsxWriter.Cell.Number(Double.POSITIVE_INFINITY)
    }

    // ── XML 转义与 Unicode ──────────────────────────────────────

    @Test
    fun `文本里的尖括号与和号被转义`() {
        val bytes = XlsxWriter.write(
            listOf(
                XlsxWriter.Sheet(
                    "S",
                    rows = listOf(listOf(XlsxWriter.Cell.Text("<a & b>"))),
                ),
            ),
        )
        val sheet = unzip(bytes)["xl/worksheets/sheet1.xml"]!!

        assertTrue("尖括号与和号必须转义", sheet.contains("&lt;a &amp; b&gt;"))
        assertFalse("不该出现未转义的裸尖括号", sheet.contains("><a &"))
    }

    @Test
    fun `emoji 不被撕碎`() {
        // ★ 按 Char 遍历会把代理项逐个剥离，把 emoji 拆成碎片，
        //   而产出的 XML 仍然合法 —— 静默的破坏
        val emoji = "完成 ✅🎉"
        val bytes = XlsxWriter.write(
            listOf(XlsxWriter.Sheet("S", rows = listOf(listOf(XlsxWriter.Cell.Text(emoji))))),
        )
        val sheet = unzip(bytes)["xl/worksheets/sheet1.xml"]!!

        assertTrue("emoji 必须完整保留，实际：$sheet", sheet.contains(emoji))
    }

    @Test
    fun `非法控制字符被剥离而正常内容保留`() {
        val bytes = XlsxWriter.write(
            listOf(XlsxWriter.Sheet("S", rows = listOf(listOf(XlsxWriter.Cell.Text("前\u0001后"))))),
        )
        val sheet = unzip(bytes)["xl/worksheets/sheet1.xml"]!!

        // XML 1.0 连字符引用都不允许控制字符，只能剥离
        assertTrue("前后文字应保留", sheet.contains("前后"))
        assertFalse("控制字符不该出现在产物里", sheet.contains('\u0001'))
    }

    // ── 列名 ────────────────────────────────────────────────────

    @Test
    fun `列名是双射而不是普通二十六进制`() {
        assertEquals("A", XlsxWriter.columnName(0))
        assertEquals("Z", XlsxWriter.columnName(25))
        // ★ 26 进制直接换算会得到 "10"，正确是 "AA"
        assertEquals("AA", XlsxWriter.columnName(26))
        assertEquals("AB", XlsxWriter.columnName(27))
        assertEquals("AZ", XlsxWriter.columnName(51))
        assertEquals("BA", XlsxWriter.columnName(52))
        assertEquals("ZZ", XlsxWriter.columnName(701))
        assertEquals("AAA", XlsxWriter.columnName(702))
    }

    @Test
    fun `列名随列数递增且不重复`() {
        val names = (0..800).map { XlsxWriter.columnName(it) }

        assertEquals("列名不能重复", names.size, names.toSet().size)
        assertEquals("第 800 列应比第 0 列靠后（字典序）", true, names[800] > names[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负列号被拒绝`() {
        XlsxWriter.columnName(-1)
    }

    // ── 工作表名校验 ────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `工作表名不能含冒号`() {
        XlsxWriter.Sheet("a:b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `工作表名不能超过三十一个字符`() {
        XlsxWriter.Sheet("x".repeat(32))
    }

    @Test
    fun `三十一个字符的工作表名合法`() {
        val sheet = XlsxWriter.Sheet("x".repeat(31))
        assertEquals(31, sheet.name.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `工作表名不能以单引号开头`() {
        XlsxWriter.Sheet("'名字")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `工作表名不能为空`() {
        XlsxWriter.Sheet("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `工作表名不能重复`() {
        XlsxWriter.write(listOf(XlsxWriter.Sheet("同名"), XlsxWriter.Sheet("同名")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `一个工作表都没有时拒绝生成`() {
        XlsxWriter.write(emptyList())
    }

    // ── DOCX ────────────────────────────────────────────────────

    @Test
    fun `docx 包含全部必需部件`() {
        val parts = unzip(DocxWriter.write(listOf(DocxWriter.Block.Paragraph("你好")))).keys

        listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml").forEach {
            assertTrue("缺少必需部件 $it，实际有 $parts", it in parts)
        }
    }

    @Test
    fun `docx 标题加粗且字号大于正文`() {
        val parts = unzip(
            DocxWriter.write(
                listOf(
                    DocxWriter.Block.Heading(1, "标题"),
                    DocxWriter.Block.Paragraph("正文"),
                ),
            ),
        )
        val doc = parts["word/document.xml"]!!

        // 标题：加粗 + 36 半磅；且 w:b 必须在 w:sz 之前（schema 顺序）
        assertTrue("标题应加粗且 18pt", doc.contains("""<w:rPr><w:b/><w:sz w:val="36"/></w:rPr>"""))
        // 正文：不加粗 + 22 半磅
        assertTrue("正文应 11pt 不加粗", doc.contains("""<w:rPr><w:sz w:val="22"/></w:rPr>"""))
        assertFalse("正文不该加粗", doc.contains("""<w:rPr><w:b/><w:sz w:val="22"/></w:rPr>"""))
    }

    @Test
    fun `docx 的节属性是 body 的最后一个子元素`() {
        val doc = unzip(DocxWriter.write(listOf(DocxWriter.Block.Paragraph("x"))))["word/document.xml"]!!

        // 顺序错了 Word 会拒绝打开整份文档
        assertTrue("sectPr 必须紧贴 body 结尾", doc.endsWith("</w:sectPr></w:body></w:document>"))
    }

    @Test
    fun `docx 换行拆成 w_br 而不是塞进 w_t`() {
        val doc = unzip(DocxWriter.write(listOf(DocxWriter.Block.Paragraph("第一行\n第二行"))))["word/document.xml"]!!

        assertTrue("应有换行标记", doc.contains("<w:br/>"))
        assertTrue("第一行应在 w:t 里", doc.contains(">第一行</w:t>"))
        assertTrue("第二行应在 w:t 里", doc.contains(">第二行</w:t>"))
        // w:t 里不能直接带 \n，Word 会丢掉
        assertFalse("w:t 里不该有裸换行", doc.contains("第一行\n第二行"))
    }

    @Test
    fun `docx 的 CRLF 不会多出一个空行`() {
        val doc = unzip(DocxWriter.write(listOf(DocxWriter.Block.Paragraph("甲\r\n乙"))))["word/document.xml"]!!

        assertEquals("CRLF 应归一成一个换行", 1, Regex("<w:br/>").findAll(doc).count())
    }

    @Test
    fun `docx 空段落写成自闭合标签`() {
        val doc = unzip(DocxWriter.write(listOf(DocxWriter.Block.Paragraph(""))))["word/document.xml"]!!

        // 空行是用户用来分隔内容的，不能丢
        assertTrue("空段落应保留", doc.contains("<w:p/>"))
    }

    @Test
    fun `docx 空文档也能生成而不是失败`() {
        val parts = unzip(DocxWriter.write(emptyList()))

        assertTrue("空文档也应有正文部件", "word/document.xml" in parts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `docx 标题层级不能小于一`() {
        DocxWriter.Block.Heading(0, "非法")
    }

    @Test
    fun `docx 超出支持层级的标题退化成最大号而不是崩溃`() {
        val doc = unzip(DocxWriter.write(listOf(DocxWriter.Block.Heading(9, "深标题"))))["word/document.xml"]!!

        // 取第 4 档字号（26 半磅），而不是抛异常
        assertTrue("应退化成最小标题字号", doc.contains("""<w:sz w:val="26"/>"""))
    }

    // ── DocumentFormat 接线 ─────────────────────────────────────

    @Test
    fun `新增的两种格式标记为二进制`() {
        assertEquals(DocumentKind.BINARY, DocumentFormat.XLSX.kind)
        assertEquals(DocumentKind.BINARY, DocumentFormat.DOCX.kind)
        // 原有四种必须仍是文本 —— 回归保护
        assertEquals(DocumentKind.TEXT, DocumentFormat.CSV.kind)
        assertEquals(DocumentKind.TEXT, DocumentFormat.JSON.kind)
    }

    @Test
    fun `格式枚举的扩展名不重复`() {
        val extensions = DocumentFormat.entries.map { it.extension }

        assertEquals("扩展名不能重复", extensions.size, extensions.toSet().size)
        assertEquals("xlsx", DocumentFormat.XLSX.extension)
        assertEquals("docx", DocumentFormat.DOCX.extension)
    }

    @Test
    fun `OOXML 的 MIME 类型是官方值`() {
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            DocumentFormat.XLSX.mimeType,
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            DocumentFormat.DOCX.mimeType,
        )
    }

    @Test
    fun `二进制格式与文本格式的集合互不重叠`() {
        val binary = DocumentFormat.entries.filter { it.kind == DocumentKind.BINARY }.map { it.extension }.toSet()
        val text = DocumentFormat.entries.filter { it.kind == DocumentKind.TEXT }.map { it.extension }.toSet()

        assertNotEquals(0, binary.size)
        assertNotEquals(0, text.size)
        assertTrue("两个集合不该有交集", (binary intersect text).isEmpty())
    }

    // ── 导出样本供外部工具独立验证 ──────────────────────────────

    /**
     * ⚠️ 这个用例**几乎不断言内容**，它把产物写到临时目录，
     *    供 Python 侧的独立验证脚本读取。
     *
     * ## 为什么需要"换个实现去解"
     *
     * 本文件所有断言都是**字符串包含** —— 它们能证明"我写了这段文字"，
     * 但**证明不了"产出的 XML 是良构的"**。而"良构"恰恰是
     * Excel / Word 能打开文件的前提；一个未闭合的标签会让文件
     * 报"已损坏"，而字符串断言全绿。
     *
     * ⇒ Kotlin 的 `ZipInputStream` + 字符串匹配是一套逻辑，
     *   Python 的 `zipfile` + `ElementTree` 是**另一套**。
     *   两边都过，才叫"结构真的对了"。
     *
     * 这也是本项目在文件沙箱那边总结过的：**桩测试验证的是
     * 「我们的逻辑自洽」，不是「外面真的能读懂」**。
     */
    @Test
    fun `导出样本供外部工具验证`() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "pocketagent-ooxml-samples")
        dir.mkdirs()

        java.io.File(dir, "sample.xlsx").writeBytes(
            XlsxWriter.write(
                listOf(
                    XlsxWriter.Sheet(
                        name = "销售",
                        header = listOf("商品", "数量", "单价"),
                        rows = listOf(
                            listOf(
                                XlsxWriter.Cell.Text("苹果"),
                                XlsxWriter.Cell.Number(3.0),
                                XlsxWriter.Cell.Number(5.5),
                            ),
                            // ★ 刻意放一个"看起来像公式"的文本：
                            //   XLSX 里它必须**原样**是文本，不带前导单引号
                            listOf(
                                XlsxWriter.Cell.Text("=SUM(A1)"),
                                XlsxWriter.Cell.Number(0.0),
                                XlsxWriter.Cell.Number(0.0),
                            ),
                        ),
                    ),
                ),
            ),
        )

        java.io.File(dir, "sample.docx").writeBytes(
            DocxWriter.write(
                listOf(
                    DocxWriter.Block.Heading(1, "季度报告"),
                    DocxWriter.Block.Paragraph("第一段正文。"),
                    DocxWriter.Block.Heading(2, "明细"),
                    DocxWriter.Block.Paragraph("含换行\n第二行"),
                    DocxWriter.Block.Paragraph(""),
                ),
            ),
        )

        // 只断言"确实写出去了"，内容验证交给 Python 侧
        assertTrue("sample.xlsx 应已写出", java.io.File(dir, "sample.xlsx").length() > 0)
        assertTrue("sample.docx 应已写出", java.io.File(dir, "sample.docx").length() > 0)
    }
}
