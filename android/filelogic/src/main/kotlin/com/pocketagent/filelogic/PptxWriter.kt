package com.pocketagent.filelogic

/**
 * PPTX（PowerPoint）生成 —— 纯 Kotlin，只用 `java.util.zip`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么带全套骨架，而 DOCX 只有三个部件
 * ═══════════════════════════════════════════════════════════════
 *
 * Word 的最小包是「正文 + 类型表 + 关系表」三件套，去掉任何一个 Word 都拒开，
 * 但除此之外它**确实不需要别的东西**（[DocxWriter] 就是这么做的）。
 *
 * PowerPoint 不一样：幻灯片不是自足的，它必须**挂在一个版式上**，
 * 版式必须挂在母版上，母版必须挂在一个主题上。少一层，PowerPoint 的
 * 表现是弹「修复」对话框，然后**把整份文件清空**保存 —— 用户丢了工作，
 * 而错误信息完全指不到"少了一张版式"。
 *
 * ⇒ 本文件按最小**可挂载**结构生成九类部件：
 *
 * ```
 * [Content_Types].xml
 * _rels/.rels                                   → ppt/presentation.xml
 * ppt/presentation.xml                          ← 幻灯片清单
 * ppt/_rels/presentation.xml.rels               ← 母版 / 主题 / 每页
 * ppt/slideMasters/slideMaster1.xml             ← 母版
 * ppt/slideMasters/_rels/slideMaster1.xml.rels  → 版式 + 主题
 * ppt/slideLayouts/slideLayout1.xml             ← 空白版式
 * ppt/slideLayouts/_rels/slideLayout1.xml.rels  → 母版
 * ppt/theme/theme1.xml                          ← 配色 / 字体 / 效果
 * ppt/slides/slideN.xml (+ _rels)               ← 正文，每页一个
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用「文本框」而不是「占位符」
 * ═══════════════════════════════════════════════════════════════
 *
 * 正规做法是 `p:ph type="title"` 让形状去认版式里的占位符。但那要求
 * 版式里的占位符索引、类型、位置与幻灯片里的引用**逐字对上** ——
 * 对不上的表现同样是"PowerPoint 修复"。而文本框（`txBox="1"` + 显式
 * `a:xfrm`）不依赖任何版式内容：位置自己带，版式只当"底"。
 *
 * 代价是用户不能一键套用设计主题（换设计的按钮对他没意义）；
 * 收益是**任何一页都不会因为版式对不上而崩**。这是生成文档，
 * 优先"打得开"，其次"好改"。
 *
 * ⚠️ EMU 是 1/914400 英寸。16:9 幻灯片 = 12192000×6858000 EMU（13.33″×7.5″），
 *    与 PowerPoint 自己新建 16:9 时的值一致 —— 不一致会让第一页看起来"歪"。
 *
 * ⚠️ `a:t` 里**不能**直接放换行（同 DOCX 的 `w:t`）。需要换行时拆成
 *    多个 `a:br`，见 [bodyXml]。
 */
object PptxWriter {

    /** 一页幻灯片。 */
    data class Slide(
        val title: String,
        /**
         * 正文要点。
         *
         * 每条独立成段并带项目符号。传空列表就是"只有标题的一页" ——
         * 那是**合法且常见**的请求（章节过渡页），不该被当成错误。
         */
        val bullets: List<String> = emptyList(),
        /** 副标题。只对首页真正有区别（画在标题下方、字号小一半）。 */
        val subtitle: String? = null,
    ) {
        init {
            require(title.isNotBlank()) { "标题不能为空" }
        }
    }

    /**
     * 页数上限。
     *
     * ⚠️ 不是技术限制，是**产物质量的上限**：模型一次给的 60 页以上，
     *    基本是"把一段文字按句号切开"，用户拿到手要全删。
     *    超过就拒绝并让它自己缩 —— 比生成一份垃圾强。
     */
    const val MAX_SLIDES: Int = 60

    /** 每页要点上限。同理：一页超过 8 条就不是幻灯片了。 */
    const val MAX_BULLETS: Int = 8

    /** 单个标题/要点的字符上限。超出**截断**并加省略号（不报错）。 */
    const val MAX_TEXT_CHARS: Int = 200

    // ── 版式常量（EMU）─────────────────────────────────────────

    private const val SLIDE_CX = 12_192_000
    private const val SLIDE_CY = 6_858_000
    private const val MARGIN_X = 838_200
    private const val TITLE_Y = 838_200
    private const val TITLE_CX = SLIDE_CX - MARGIN_X * 2
    private const val TITLE_CY = 1_371_600
    private const val SUBTITLE_Y = 2_286_000
    private const val SUBTITLE_CY = 685_800
    private const val BODY_Y_PLAIN = 2_438_400
    private const val BODY_Y_WITH_SUBTITLE = 3_200_400
    private const val BODY_CY = SLIDE_CY - 457_200

    /** 字号（百分之一磅）：40pt 标题、18pt 正文。 */
    private const val TITLE_SZ = 4_000
    private const val SUBTITLE_SZ = 2_000
    private const val BODY_SZ = 1_800

    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_CONTENT_TYPES = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val NS_REL_PACKAGE = "http://schemas.openxmlformats.org/package/2006/relationships"

    private const val REL_OFFICE_DOCUMENT =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    private const val REL_SLIDE_MASTER =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
    private const val REL_SLIDE_LAYOUT =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
    private const val REL_THEME =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme"
    private const val REL_SLIDE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"

    private const val CT_PRESENTATION =
        "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private const val CT_SLIDE_MASTER =
        "application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"
    private const val CT_SLIDE_LAYOUT =
        "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"
    private const val CT_SLIDE =
        "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private const val CT_THEME = "application/vnd.openxmlformats-officedocument.theme+xml"

    /**
     * 生成一份演示文稿。
     *
     * @param slides 空列表会产出**合法但零页**的文件。PowerPoint 打开会显示
     *   "没有幻灯片"，这在语义上是诚实的（用户确实什么都没给）；
     *   但要让他有机会改正，所以**上游校验**（`OfficeToolValidator`）
     *   会先拒掉空列表 —— 这一层不替它做产品判断。
     */
    fun write(slides: List<Slide>): ByteArray {
        require(slides.size <= MAX_SLIDES) { "幻灯片最多 $MAX_SLIDES 页，收到 ${slides.size} 页" }
        slides.forEachIndexed { index, slide ->
            require(slide.bullets.size <= MAX_BULLETS) {
                "第 ${index + 1} 页有 ${slide.bullets.size} 条要点，最多 $MAX_BULLETS 条"
            }
        }

        val parts = ArrayList<OoxmlPackage.Part>(8 + slides.size * 2)
        parts += OoxmlPackage.textPart("[Content_Types].xml", contentTypesXml(slides.size))
        parts += OoxmlPackage.textPart("_rels/.rels", rootRelsXml())
        parts += OoxmlPackage.textPart("ppt/presentation.xml", presentationXml(slides.size))
        parts += OoxmlPackage.textPart("ppt/_rels/presentation.xml.rels", presentationRelsXml(slides.size))
        parts += OoxmlPackage.textPart("ppt/slideMasters/slideMaster1.xml", slideMasterXml())
        parts += OoxmlPackage.textPart(
            "ppt/slideMasters/_rels/slideMaster1.xml.rels",
            slideMasterRelsXml(),
        )
        parts += OoxmlPackage.textPart("ppt/slideLayouts/slideLayout1.xml", slideLayoutXml())
        parts += OoxmlPackage.textPart(
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels",
            slideLayoutRelsXml(),
        )
        parts += OoxmlPackage.textPart("ppt/theme/theme1.xml", themeXml())

        slides.forEachIndexed { index, slide ->
            val n = index + 1
            parts += OoxmlPackage.textPart("ppt/slides/slide$n.xml", slideXml(slide, index))
            parts += OoxmlPackage.textPart(
                "ppt/slides/_rels/slide$n.xml.rels",
                slideRelsXml(),
            )
        }
        return OoxmlPackage.zip(parts)
    }

    // ── 部件：包级 ──────────────────────────────────────────────

    private fun contentTypesXml(slideCount: Int): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<Types xmlns="$NS_CONTENT_TYPES">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/ppt/presentation.xml" ContentType="$CT_PRESENTATION"/>""")
        append("""<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="$CT_SLIDE_MASTER"/>""")
        append("""<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="$CT_SLIDE_LAYOUT"/>""")
        append("""<Override PartName="/ppt/theme/theme1.xml" ContentType="$CT_THEME"/>""")
        for (n in 1..slideCount) {
            append("""<Override PartName="/ppt/slides/slide$n.xml" ContentType="$CT_SLIDE"/>""")
        }
        append("</Types>")
    }

    private fun rootRelsXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<Relationships xmlns="$NS_REL_PACKAGE">""" +
            """<Relationship Id="rId1" Type="$REL_OFFICE_DOCUMENT" Target="ppt/presentation.xml"/>""" +
            "</Relationships>"

    /**
     * 演示文稿本体。
     *
     * ⚠️ `p:sldIdLst` 的 `r:id` 必须与 rels 里的 Id **逐字一致**。
     *    这里把编号算集中在一个地方（[slideRelId]），避免"两处各写一遍然后漂移" ——
     *    漂移的表现是打开后少几页，且不报错。
     */
    private fun presentationXml(slideCount: Int): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<p:presentation xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P" saveSubsetFonts="1">""")
        // 母版固定 rId1（见 presentationRelsXml）
        append("""<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>""")
        if (slideCount > 0) {
            append("<p:sldIdLst>")
            for (n in 1..slideCount) {
                // id 从 256 起：PowerPoint 自己新建时就是这么编的，跟着它省一类兼容性问题
                append("""<p:sldId id="${255 + n}" r:id="${slideRelId(n)}"/>""")
            }
            append("</p:sldIdLst>")
        }
        append("""<p:sldSz cx="$SLIDE_CX" cy="$SLIDE_CY"/>""")
        append("""<p:notesSz cx="6858000" cy="9144000"/>""")
        append("</p:presentation>")
    }

    /** 幻灯片的 rId：母版占 rId1、主题占 rId2，页面从 rId3 起。 */
    private fun slideRelId(n: Int): String = "rId${n + 2}"

    private fun presentationRelsXml(slideCount: Int): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<Relationships xmlns="$NS_REL_PACKAGE">""")
        append("""<Relationship Id="rId1" Type="$REL_SLIDE_MASTER" Target="slideMasters/slideMaster1.xml"/>""")
        append("""<Relationship Id="rId2" Type="$REL_THEME" Target="theme/theme1.xml"/>""")
        for (n in 1..slideCount) {
            append("""<Relationship Id="${slideRelId(n)}" Type="$REL_SLIDE" Target="slides/slide$n.xml"/>""")
        }
        append("</Relationships>")
    }

    // ── 部件：母版 / 版式 / 主题 ────────────────────────────────

    private fun slideMasterXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<p:sldMaster xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P">""" +
            "<p:cSld>" +
            "<p:bg><p:bgPr>" +
            """<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill>""" +
            "<a:effectLst/></p:bgPr></p:bg>" +
            "<p:spTree>" + emptyTreeXml() + "</p:spTree>" +
            "</p:cSld>" +
            // clrMap 的每一项都必须给全，缺项 PowerPoint 会判文件损坏
            """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" """ +
            """accent1="accent1" accent2="accent2" accent3="accent3" """ +
            """accent4="accent4" accent5="accent5" accent6="accent6" """ +
            """hlink="hlink" folHlink="folHlink"/>""" +
            """<p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>""" +
            "</p:sldMaster>"

    private fun slideMasterRelsXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<Relationships xmlns="$NS_REL_PACKAGE">""" +
            """<Relationship Id="rId1" Type="$REL_SLIDE_LAYOUT" Target="../slideLayouts/slideLayout1.xml"/>""" +
            """<Relationship Id="rId2" Type="$REL_THEME" Target="../theme/theme1.xml"/>""" +
            "</Relationships>"

    /**
     * 一张空白版式。
     *
     * ⚠️ `preserve="1"` 不是可选装饰：没有它，PowerPoint 在保存时会把
     *    "看起来没用上"的版式**优化掉**，而我们的每一页都引着它 ——
     *    下次打开就成了"找不到版式"。
     */
    private fun slideLayoutXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<p:sldLayout xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P" type="blank" preserve="1">""" +
            """<p:cSld name="空白"><p:spTree>""" + emptyTreeXml() + "</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>" +
            "</p:sldLayout>"

    private fun slideLayoutRelsXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<Relationships xmlns="$NS_REL_PACKAGE">""" +
            """<Relationship Id="rId1" Type="$REL_SLIDE_MASTER" Target="../slideMasters/slideMaster1.xml"/>""" +
            "</Relationships>"

    /**
     * 主题。
     *
     * ⚠️ `fmtScheme` 的三条 `fillStyleLst` / 三条 `lnStyleLst` /
     *    三条 `bgFillStyleLst` 是**规范要求的最小数量**，少一条 PowerPoint
     *    会判损坏。这些内容几乎不会被用到（我们全部用内联格式），
     *    但仍然必须给全 —— 这就是"骨架"的含义。
     */
    private fun themeXml(): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<a:theme xmlns:a="$NS_A" name="PocketAgent">""")
        append("<a:themeElements>")
        // 配色：dk/lt 用系统色，其余取一组低饱和度的中性色（打印也不刺眼）
        append("<a:clrScheme name=\"PocketAgent\">")
        append("""<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1>""")
        append("""<a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""")
        append("""<a:dk2><a:srgbClr val="2B2B2B"/></a:dk2>""")
        append("""<a:lt2><a:srgbClr val="F2F2F2"/></a:lt2>""")
        append("""<a:accent1><a:srgbClr val="C96442"/></a:accent1>""")
        append("""<a:accent2><a:srgbClr val="4A6FA5"/></a:accent2>""")
        append("""<a:accent3><a:srgbClr val="6E8B3D"/></a:accent3>""")
        append("""<a:accent4><a:srgbClr val="B08A3E"/></a:accent4>""")
        append("""<a:accent5><a:srgbClr val="7A5C8E"/></a:accent5>""")
        append("""<a:accent6><a:srgbClr val="5C8A8A"/></a:accent6>""")
        append("""<a:hlink><a:srgbClr val="1155CC"/></a:hlink>""")
        append("""<a:folHlink><a:srgbClr val="7A5C8E"/></a:folHlink>""")
        append("</a:clrScheme>")
        // 字体：西文 Calibri，中文落到系统黑体 —— 手机上不装雅黑也不会豆腐块
        append("""<a:fontScheme name="PocketAgent">""")
        append("<a:majorFont>")
        append("""<a:latin typeface="Calibri Light"/>""")
        append("""<a:ea typeface="Microsoft YaHei"/>""")
        append("""<a:cs typeface=""/>""")
        append("</a:majorFont>")
        append("<a:minorFont>")
        append("""<a:latin typeface="Calibri"/>""")
        append("""<a:ea typeface="Microsoft YaHei"/>""")
        append("""<a:cs typeface=""/>""")
        append("</a:minorFont>")
        append("</a:fontScheme>")
        append("""<a:fmtScheme name="PocketAgent">""")
        append("<a:fillStyleLst>")
        repeat(3) { append("""<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""") }
        append("</a:fillStyleLst>")
        append("<a:lnStyleLst>")
        repeat(3) {
            append(
                """<a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>""",
            )
        }
        append("</a:lnStyleLst>")
        append("<a:effectStyleLst>")
        repeat(3) { append("<a:effectStyle><a:effectLst/></a:effectStyle>") }
        append("</a:effectStyleLst>")
        append("<a:bgFillStyleLst>")
        repeat(3) { append("""<a:solidFill><a:schemeClr val="phClr"/></a:solidFill>""") }
        append("</a:bgFillStyleLst>")
        append("</a:fmtScheme>")
        append("</a:themeElements>")
        append("</a:theme>")
    }

    // ── 部件：幻灯片 ────────────────────────────────────────────

    private fun slideRelsXml(): String =
        OoxmlPackage.XML_DECLARATION +
            """<Relationships xmlns="$NS_REL_PACKAGE">""" +
            """<Relationship Id="rId1" Type="$REL_SLIDE_LAYOUT" Target="../slideLayouts/slideLayout1.xml"/>""" +
            "</Relationships>"

    private fun slideXml(slide: Slide, index: Int): String = buildString {
        append(OoxmlPackage.XML_DECLARATION)
        append("""<p:sld xmlns:a="$NS_A" xmlns:r="$NS_R" xmlns:p="$NS_P">""")
        append("<p:cSld><p:spTree>")
        append(emptyTreeXml())

        val title = clamp(slide.title)
        // 形状 id 必须唯一且 > 0；标题恒为 2、副标题 3、正文 4（与文档顺序无关，便于排查）
        append(
            textBoxXml(
                shapeId = 2,
                name = "标题 ${index + 1}",
                x = MARGIN_X,
                y = TITLE_Y,
                cx = TITLE_CX,
                cy = TITLE_CY,
                paragraphs = listOf(
                    Paragraph(text = title, sizeSz = TITLE_SZ, bold = true),
                ),
            ),
        )

        val subtitle = slide.subtitle?.trim()?.takeIf { it.isNotEmpty() }?.let(::clamp)
        if (subtitle != null) {
            append(
                textBoxXml(
                    shapeId = 3,
                    name = "副标题 ${index + 1}",
                    x = MARGIN_X,
                    y = SUBTITLE_Y,
                    cx = TITLE_CX,
                    cy = SUBTITLE_CY,
                    paragraphs = listOf(
                        Paragraph(text = subtitle, sizeSz = SUBTITLE_SZ, bold = false),
                    ),
                ),
            )
        }

        val bullets = slide.bullets.map { clamp(it) }.filter { it.isNotEmpty() }
        if (bullets.isNotEmpty()) {
            append(
                textBoxXml(
                    shapeId = 4,
                    name = "正文 ${index + 1}",
                    x = MARGIN_X,
                    y = if (subtitle != null) BODY_Y_WITH_SUBTITLE else BODY_Y_PLAIN,
                    cx = TITLE_CX,
                    cy = BODY_CY,
                    paragraphs = bullets.map {
                        Paragraph(text = it, sizeSz = BODY_SZ, bold = false, bullet = true)
                    },
                ),
            )
        }

        append("</p:spTree></p:cSld>")
        append("<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        append("</p:sld>")
    }

    /**
     * `spTree` 的必备子元素。
     *
     * ⚠️ 不是"可选样板"：`p:nvGrpSpPr` 与 `p:grpSpPr` 是 schema 里的**必填**，
     *    删掉它们 PowerPoint 会说文件需要修复。所有坐标给 0 是对的 ——
     *    它们描述的是"根的变换"，而根不参与变换。
     */
    private fun emptyTreeXml(): String =
        "<p:nvGrpSpPr>" +
            """<p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/>""" +
            "</p:nvGrpSpPr>" +
            "<p:grpSpPr><a:xfrm>" +
            """<a:off x="0" y="0"/><a:ext cx="0" cy="0"/>""" +
            """<a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/>""" +
            "</a:xfrm></p:grpSpPr>"

    /** 段内文字的一段。`bullet` 为真时加项目符号。 */
    private data class Paragraph(
        val text: String,
        val sizeSz: Int,
        val bold: Boolean,
        val bullet: Boolean = false,
    )

    private fun textBoxXml(
        shapeId: Int,
        name: String,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        paragraphs: List<Paragraph>,
    ): String = buildString {
        append("<p:sp>")
        append("<p:nvSpPr>")
        append("""<p:cNvPr id="$shapeId" name="${OoxmlPackage.xmlText(name)}"/>""")
        // txBox="1" 声明"这是个文本框"，而不是版式里的占位符
        append("""<p:cNvSpPr txBox="1"/><p:nvPr/>""")
        append("</p:nvSpPr>")
        append("<p:spPr>")
        append("""<a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
        append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>""")
        // 无填充无边框：底是母版的白色，看得见字就够了
        append("<a:noFill/></p:spPr>")
        append("<p:txBody>")
        append("""<a:bodyPr wrap="square" lIns="0" rIns="0" tIns="0" bIns="0"><a:normAutofit/></a:bodyPr>""")
        append("<a:lstStyle/>")
        for (p in paragraphs) append(paragraphXml(p))
        append("</p:txBody>")
        append("</p:sp>")
    }

    private fun paragraphXml(p: Paragraph): String = buildString {
        append("<a:p>")
        if (p.bullet) {
            // marL/indent 一正一负 = 悬挂缩进：换行后的文字与首字对齐，而不是顶到符号下面
            append("""<a:pPr marL="285750" indent="-285750">""")
            append("""<a:buFont typeface="Arial"/><a:buChar char="•"/>""")
            append("</a:pPr>")
        } else {
            append("""<a:pPr><a:buNone/></a:pPr>""")
        }
        // 换行必须拆成 a:br —— a:t 里的 \n 会被当成普通字符丢掉（同 DOCX 的 w:t）
        val lines = p.text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (lines.size == 1) {
            append(runXml(lines[0], p))
        } else {
            lines.forEachIndexed { index, line ->
                if (index > 0) append("<a:br/>")
                if (line.isNotEmpty()) append(runXml(line, p))
            }
        }
        append("</a:p>")
    }

    private fun runXml(text: String, p: Paragraph): String =
        "<a:r>" +
            // ⚠️ a:rPr 的子元素顺序有 schema 约束：先 fill，再 latin，最后 sz/b。
            //    顺序错了 PowerPoint 拒开，而报错完全指不到顺序上。
            """<a:rPr lang="zh-CN" altLang="en-US" sz="${p.sizeSz}"""" +
            (if (p.bold) """ b="1"""" else "") +
            """ dirty="0"/>""" +
            """<a:t>${OoxmlPackage.xmlText(text)}</a:t>""" +
            "</a:r>"

    /** 超长截断。按 code point 数，避免把 emoji 的代理对切一半。 */
    private fun clamp(raw: String): String {
        val trimmed = raw.trim()
        val count = trimmed.codePointCount(0, trimmed.length)
        if (count <= MAX_TEXT_CHARS) return trimmed
        val end = trimmed.offsetByCodePoints(0, MAX_TEXT_CHARS)
        return trimmed.substring(0, end) + "…"
    }
}
