package com.pocketagent.filelogic

/**
 * XLSX（Excel 工作簿）生成 —— 纯 Kotlin，只用 `java.util.zip`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么 XLSX 反而比 CSV **更安全**
 * ═══════════════════════════════════════════════════════════════
 *
 * [OfficeText.csvField] 里花了一整段论证"公式注入"，还要给可疑字段
 * 加前导单引号 —— 而那个单引号**改变了内容**（用户会看到 `'=cmd`）。
 *
 * XLSX 里**根本不存在这个问题**，原因是**类型是显式的**：
 *
 * | 想写什么 | 怎么写 | Excel 会做什么 |
 * |---|---|---|
 * | 文本 `=SUM(A1)` | `<c t="inlineStr">` | 当**文本**，不执行 |
 * | 数字 `42` | `<c><v>42</v></c>` | 当数字 |
 *
 * CSV 之所以危险，是因为它**没有类型** —— 一行 `=SUM(A1)` 到底是文本
 * 还是公式，只能由 Excel 猜，而它选择猜"公式"。
 * ⇒ **把类型显式化，就消灭了整个攻击面**，代价为零。
 *
 * ⚠️ 所以本文件**不需要** [OfficeText] 那套中和逻辑，也不该照抄过来 ——
 *    照抄会让 `'=SUM(A1)` 这种被污染的内容出现在本该干净的 XLSX 里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  最小合法结构
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * [Content_Types].xml            ← 每个部件的 MIME
 * _rels/.rels                    ← 包级关系：谁是主文档
 * xl/workbook.xml                ← 工作表清单
 * xl/_rels/workbook.xml.rels     ← 工作表 id → 文件路径
 * xl/worksheets/sheet1.xml       ← 实际内容
 * ```
 *
 * 少任何一个，Excel 都会报"文件已损坏"或静默丢掉内容。
 */
object XlsxWriter {

    /** Excel 工作表名长度上限（含），这是 Excel 自己的硬限制。 */
    const val MAX_SHEET_NAME_LENGTH: Int = 31

    /**
     * 工作表名里不允许出现的字符。
     *
     * 它们**不是** XML 层面的问题（XML 转义另有其人），是 Excel 层面的：
     * 这些字符在公式引用（`Sheet1!A1`）里有特殊含义。
     */
    private val ILLEGAL_SHEET_NAME_CHARS: Set<Char> = setOf(':', '\\', '/', '?', '*', '[', ']')

    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val NS_REL_PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /**
     * 一个单元格的值。
     *
     * ⚠️ **类型显式，不做推断** —— 与 [OfficeText.toJsonStringMap] 同一条理由：
     *    把 `"007"` 推断成数字 `7` 会毁掉邮政编码和身份证号，
     *    而用户看到的只是"这一列右对齐了"——**没有任何报错**。
     */
    sealed interface Cell {

        /** 文本。**永远是文本**，哪怕它长得像数字或公式。 */
        data class Text(val value: String) : Cell

        /**
         * 数字。
         *
         * ⚠️ 构造时拒绝 NaN / Infinity：它们写进 `<v>` 之后 Excel 报的是
         *    **"文件已损坏"**（而不是"这个单元格不对"），排查方向会被
         *    完全带偏。宁可在这里当场失败。
         */
        data class Number(val value: Double) : Cell {
            init {
                require(value.isFinite()) { "单元格数字必须是有限值，当前为 $value" }
            }
        }
    }

    /**
     * 一个工作表。
     *
     * @param name 工作表名。见 [MAX_SHEET_NAME_LENGTH] 与
     *   [ILLEGAL_SHEET_NAME_CHARS]。
     * @param header 表头。**非空时会成为第 1 行**，数据从第 2 行开始。
     * @param rows 数据行。各行列数允许不同（Excel 本身允许参差不齐）。
     */
    class Sheet(
        val name: String,
        val header: List<String> = emptyList(),
        val rows: List<List<Cell>> = emptyList(),
    ) {
        init {
            require(name.isNotBlank()) { "工作表名不能为空" }
            require(name.length <= MAX_SHEET_NAME_LENGTH) {
                "工作表名最长 $MAX_SHEET_NAME_LENGTH 个字符，当前 ${name.length}：$name"
            }
            val illegal = name.filter { it in ILLEGAL_SHEET_NAME_CHARS }
            require(illegal.isEmpty()) {
                "工作表名不能含 ${ILLEGAL_SHEET_NAME_CHARS.joinToString(" ")} 中的字符，实际含：$illegal"
            }
            // Excel 会把首尾单引号当成"引用整个名字"的语法，所以不允许
            require(!name.startsWith("'") && !name.endsWith("'")) {
                "工作表名不能以单引号开头或结尾：$name"
            }
        }
    }

    /**
     * 生成一个工作簿。
     *
     * @param sheets 至少一个。顺序即工作表顺序。
     */
    fun write(sheets: List<Sheet>): ByteArray {
        require(sheets.isNotEmpty()) { "工作簿至少要有一个工作表" }
        val names = sheets.map { it.name }
        require(names.size == names.toSet().size) { "工作表名不能重复：$names" }

        val parts = buildList {
            add(OoxmlPackage.textPart("[Content_Types].xml", contentTypesXml(sheets.size)))
            add(OoxmlPackage.textPart("_rels/.rels", rootRelsXml()))
            add(OoxmlPackage.textPart("xl/workbook.xml", workbookXml(sheets)))
            add(OoxmlPackage.textPart("xl/_rels/workbook.xml.rels", workbookRelsXml(sheets.size)))
            sheets.forEachIndexed { index, sheet ->
                add(OoxmlPackage.textPart("xl/worksheets/sheet${index + 1}.xml", worksheetXml(sheet)))
            }
        }
        return OoxmlPackage.zip(parts)
    }

    // ── 部件 ────────────────────────────────────────────────────

    private fun contentTypesXml(sheetCount: Int): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<Types xmlns="$NS_CONTENT_TYPES">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append(
            """<Override PartName="/xl/workbook.xml" """ +
                """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""",
        )
        for (i in 1..sheetCount) {
            append(
                """<Override PartName="/xl/worksheets/sheet$i.xml" """ +
                    """ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""",
            )
        }
        append("</Types>")
    }

    private fun rootRelsXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<Relationships xmlns="$NS_REL_PACKAGE">""" +
            """<Relationship Id="rId1" Type="$NS_REL_DOC/officeDocument" Target="xl/workbook.xml"/>""" +
            "</Relationships>"

    private fun workbookXml(sheets: List<Sheet>): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<workbook xmlns="$NS_MAIN" xmlns:r="$NS_REL_DOC">""")
        append("<sheets>")
        sheets.forEachIndexed { index, sheet ->
            // sheetId 从 1 起；r:id 与 workbook.xml.rels 里的 Id 对应
            append("""<sheet name="${OoxmlPackage.xmlText(sheet.name)}" """)
            append("""sheetId="${index + 1}" r:id="rId${index + 1}"/>""")
        }
        append("</sheets>")
        append("</workbook>")
    }

    private fun workbookRelsXml(sheetCount: Int): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<Relationships xmlns="$NS_REL_PACKAGE">""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId$i" Type="$NS_REL_DOC/worksheet" """)
            append("""Target="worksheets/sheet$i.xml"/>""")
        }
        append("</Relationships>")
    }

    private fun worksheetXml(sheet: Sheet): String {
        // 表头成为第 1 行 —— 这是"有表头"这个概念的落地处
        val allRows: List<List<Cell>> = buildList {
            if (sheet.header.isNotEmpty()) add(sheet.header.map { Cell.Text(it) })
            addAll(sheet.rows)
        }

        return buildString {
            append(OoxmlPackage.XML_DECLARATION)
            append("""<worksheet xmlns="$NS_MAIN">""")
            append("<sheetData>")
            allRows.forEachIndexed { rowIndex, row ->
                val rowNumber = rowIndex + 1
                append("""<row r="$rowNumber">""")
                row.forEachIndexed { colIndex, cell ->
                    append(cellXml(columnName(colIndex), rowNumber, cell))
                }
                append("</row>")
            }
            append("</sheetData>")
            append("</worksheet>")
        }
    }

    private fun cellXml(column: String, row: Int, cell: Cell): String {
        val ref = "$column$row"
        return when (cell) {
            // xml:space="preserve" 是必须的：没有它，首尾空格会被 Excel 吃掉，
            // 而"看起来一样、值不一样"正是本项目反复要防的那类故障
            is Cell.Text ->
                """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">""" +
                    "${OoxmlPackage.xmlText(cell.value)}</t></is></c>"

            is Cell.Number -> """<c r="$ref"><v>${numberText(cell.value)}</v></c>"""
        }
    }

    /**
     * 列号（0 起）→ Excel 列名（`A`、`B`…`Z`、`AA`、`AB`…）。
     *
     * ⚠️ 这是**双射但不是普通进制**：26 进制里没有"零"这个位，
     *    所以**不能**用 `toString(26)` —— 那样 26 会得到 `"10"` 而不是 `"AA"`。
     *    正确做法是每次减一（把 1..26 映射到 A..Z，而不是 0..25）。
     */
    internal fun columnName(index: Int): String {
        require(index >= 0) { "列号不能为负：$index" }
        var n = index
        val sb = StringBuilder()
        while (true) {
            sb.append('A' + n % 26)
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.reverse().toString()
    }

    /**
     * 数字的 XML 文本形式。
     *
     * ⚠️ 整数值要写成 `5` 而不是 `5.0` —— 后者 Excel 也认，但会让
     *    "同一份数据用不同路径生成得到不同字节"，破坏可复现性。
     *    超出 `Long` 安全范围时退回 `toString()`（科学计数法，Excel 认）。
     *
     * `Double.toString()` 不受 locale 影响，小数点永远是 `.` —— 这点是
     * 它比 `String.format` 安全的地方（后者不加 `Locale.ROOT` 会写出 `1,5`）。
     */
    private fun numberText(value: Double): String {
        val asLong = value.toLong()
        return if (asLong.toDouble() == value && kotlin.math.abs(value) < 1e15) {
            asLong.toString()
        } else {
            value.toString()
        }
    }
}
