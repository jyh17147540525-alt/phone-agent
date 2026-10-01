package com.pocketagent.filelogic

/**
 * DOCX（Word 文档）生成 —— 纯 Kotlin，只用 `java.util.zip`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么**不带** `styles.xml`
 * ═══════════════════════════════════════════════════════════════
 *
 * 正规做法是定义一个 `Heading1` 样式，段落用 `<w:pStyle w:val="Heading1"/>` 引用。
 * 但那要多一个部件、多一份关系声明，而且**样式文件里的每一处引用都要对得上**
 * —— 对不上的表现不是报错，是 Word 静默地按默认样式渲染
 * （"我明明设了标题，怎么没变粗"）。
 *
 * 这里改用**内联格式**（`<w:b/>` + `<w:sz w:val="36"/>`）直接写在 run 上：
 *
 * - 少一个部件 ⇒ 少一类"引用对不上"的失败；
 * - 不依赖样式表 ⇒ 在 WPS / LibreOffice 里表现一致（它们对样式表的实现有差异）；
 * - 代价是文件里冗余一点 —— 而这是**生成**的文件，不是给人手改的。
 *
 * ⚠️ 元素顺序在 OOXML 里是**有 schema 约束的**，不是随便排：
 *    `w:rPr` 内必须是 `w:b` 在 `w:sz` 之前。顺序错了 Word 会拒绝打开整份文档
 *    （报"内容有问题"），而错误信息完全指不到顺序上。
 *
 * ═══════════════════════════════════════════════════════════════
 *  最小合法结构
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * [Content_Types].xml      ← 部件 MIME
 * _rels/.rels              ← 包级关系：谁是主文档
 * word/document.xml        ← 正文
 * ```
 */
object DocxWriter {

    private const val NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val NS_REL_PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** 字号单位是**半磅**（half-point）：`w:sz=22` 即 11pt。 */
    private const val SIZE_BODY = 22

    /** 标题层级 → 字号（半磅）。超出 [MAX_HEADING_LEVEL] 的按最小号处理。 */
    private val HEADING_SIZES = listOf(36, 32, 28, 26)

    /** 支持的标题层级上限。1 是一级标题。 */
    const val MAX_HEADING_LEVEL: Int = 4

    /**
     * 文档块。
     *
     * 只有"标题"和"段落"两种 —— 不是设计得少，是**先把最小可用做对**。
     * 表格（`w:tbl`）需要 `w:tblGrid` / `w:tc` / `w:tcPr` 一整套，
     * 而且列宽必须与网格声明一致，否则 Word 会重排（用户看到的是
     * "我设的列宽没用"）。那是独立一轮的事。
     */
    sealed interface Block {

        /** 标题。[level] 从 1 起，超过 [MAX_HEADING_LEVEL] 会退化成最大号标题。 */
        data class Heading(val level: Int, val text: String) : Block {
            init {
                require(level >= 1) { "标题层级从 1 起，当前为 $level" }
            }
        }

        /** 正文段落。文本里的 `\n` 会变成 Word 里的换行（`w:br`）。 */
        data class Paragraph(val text: String) : Block
    }

    /**
     * 生成一份文档。
     *
     * @param blocks 空列表也会产出一份**合法但无内容**的文档
     *   （而不是失败）—— "生成一份空文档"是合理请求。
     */
    fun write(blocks: List<Block>): ByteArray {
        val parts = listOf(
            OoxmlPackage.textPart("[Content_Types].xml", contentTypesXml()),
            OoxmlPackage.textPart("_rels/.rels", rootRelsXml()),
            OoxmlPackage.textPart("word/document.xml", documentXml(blocks)),
        )
        return OoxmlPackage.zip(parts)
    }

    // ── 部件 ────────────────────────────────────────────────────

    private fun contentTypesXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<Types xmlns="$NS_CONTENT_TYPES">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" """ +
            """ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            "</Types>"

    private fun rootRelsXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<Relationships xmlns="$NS_REL_PACKAGE">""" +
            """<Relationship Id="rId1" Type="$NS_REL_DOC/officeDocument" Target="word/document.xml"/>""" +
            "</Relationships>"

    private fun documentXml(blocks: List<Block>): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<w:document xmlns:w="$NS_W">""")
        append("<w:body>")
        for (block in blocks) append(blockXml(block))
        // w:sectPr 必须是 w:body 的**最后一个**子元素 —— 顺序错了 Word 拒开。
        // 值取 A4（11906×16838 twips）+ 1 英寸页边距（1440 twips）。
        append("""<w:sectPr>""")
        append("""<w:pgSz w:w="11906" w:h="16838"/>""")
        append("""<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>""")
        append("</w:sectPr>")
        append("</w:body>")
        append("</w:document>")
    }

    private fun blockXml(block: Block): String = when (block) {
        is Block.Heading -> paragraphXml(
            text = block.text,
            bold = true,
            sizeHalfPoints = HEADING_SIZES[minOf(block.level, MAX_HEADING_LEVEL) - 1],
        )

        is Block.Paragraph -> paragraphXml(
            text = block.text,
            bold = false,
            sizeHalfPoints = SIZE_BODY,
        )
    }

    /**
     * 一个段落。
     *
     * ⚠️ **空段落要写成 `<w:p/>`**，不能省掉 —— 省掉的话用户在文档里
     *    连打几个空行就没了，而那正是他用来分隔内容的。
     */
    private fun paragraphXml(text: String, bold: Boolean, sizeHalfPoints: Int): String {
        if (text.isEmpty()) return "<w:p/>"
        return "<w:p>" + runXml(text, bold, sizeHalfPoints) + "</w:p>"
    }

    /**
     * 一个 run（一段同样格式的文字）。
     *
     * 换行要拆成「多个 `w:t` + 中间的 `w:br`」—— `w:t` 里**不能**直接放 `\n`，
     * Word 会把它当普通字符丢掉（或显示成方块），于是用户看到的段落
     * 挤成一行，而文件本身没有任何错误。
     */
    private fun runXml(text: String, bold: Boolean, sizeHalfPoints: Int): String {
        // 先把各种换行归一，否则 \r\n 会拆出一个多余的空行
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.split('\n')

        return buildString {
            append("<w:r>")
            append("<w:rPr>")
            // ⚠️ 顺序有 schema 约束：w:b 必须在 w:sz 之前
            if (bold) append("<w:b/>")
            append("""<w:sz w:val="$sizeHalfPoints"/>""")
            append("</w:rPr>")
            lines.forEachIndexed { index, line ->
                if (index > 0) append("<w:br/>")
                // xml:space="preserve"：没有它首尾空格会被吃掉
                append("""<w:t xml:space="preserve">${OoxmlPackage.xmlText(line)}</w:t>""")
            }
            append("</w:r>")
        }
    }
}
