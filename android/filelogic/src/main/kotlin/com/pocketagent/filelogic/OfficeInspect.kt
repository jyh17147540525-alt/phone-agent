package com.pocketagent.filelogic

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * **在应用里读一眼 Office 文件** —— 不装 WPS、不跳别的软件。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不做完整渲染
 * ═══════════════════════════════════════════════════════════════
 *
 * docx / xlsx / pptx 都是 ZIP + XML。要**一模一样地排版**，得实现
 * 一个排版引擎（字体、行高、表格边框、公式……），那不是这个应用该干的事。
 *
 * 但用户点开卡片想看的，八成是"我让它写的那段话到底写对没有"。把它
 * **读成文字**就够：Word 的段落、PPT 每页的文字、表格里的值。
 *
 * ⚠️ 所以这里的原则是：**宁可少显示，也不假装排版对了**。
 *   界面会明说"这是文字预览"，而不是把它画成一张假的文档。
 */
object OfficeInspect {

    /** 预览最多看这么多字 —— 再长就不是"看一眼"，是"读完"。 */
    const val MAX_CHARS: Int = 20_000

    data class Result(val text: String, val kind: String)

    /** 读不出来就返回 null（界面据此说"打不开"，而不是显示一段空白）。 */
    fun inspect(bytes: ByteArray, extension: String): Result? {
        val parts = runCatching { unzip(bytes) }.getOrNull() ?: return null
        return when (extension.lowercase()) {
            "docx" -> textOfDocx(parts)
            "pptx" -> textOfPptx(parts)
            "xlsx" -> textOfXlsx(parts)
            else -> null
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            var guard = 0
            while (entry != null && guard < MAX_ENTRIES) {
                guard++
                val name = entry.name
                if (!entry.isDirectory && name.endsWith(".xml")) {
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    out[name] = text
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun textOfDocx(parts: Map<String, String>): Result? {
        val doc = parts["word/document.xml"] ?: return null
        val body = doc
            .replace("</w:p>", "<w:p/>\n")
            .replace(Regex("</w:tr>"), "</w:tr>\n")
        val text = plain(body).trim()
        return Result(text.take(MAX_CHARS), "docx")
    }

    private fun textOfPptx(parts: Map<String, String>): Result? {
        val slides = parts.keys
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.substringAfter("slide").substringBefore(".xml").toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return null
        val sb = StringBuilder()
        slides.forEachIndexed { index, key ->
            sb.append("—— 第 ").append(index + 1).append(" 页 ——\n")
            val runs = RUN.findAll(parts.getValue(key)).map { unescape(it.groupValues[1]) }
            val line = runs.joinToString("").trim()
            sb.append(line.ifEmpty { "(这一页没有文字)" }).append('\n').append('\n')
        }
        return Result(sb.toString().trim().take(MAX_CHARS), "pptx")
    }

    private fun textOfXlsx(parts: Map<String, String>): Result? {
        val shared = parts["xl/sharedStrings.xml"]
            ?.let { xml -> RUN.findAll(xml).map { unescape(it.groupValues[1]) }.toList() }
            .orEmpty()
        val sheets = parts.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
        if (sheets.isEmpty()) return null
        val sb = StringBuilder()
        sheets.forEachIndexed { index, key ->
            sb.append("—— 表 ").append(index + 1).append(" ——\n")
            val xml = parts.getValue(key)
            ROW.findAll(xml).take(MAX_ROWS).forEach { row ->
                // ⚠️ 两种写法都要认：`<c t="inlineStr"><is><t>文本</t></is></c>`（我们自己写的）
                //    和 `<c t="s"><v>0</v></c>` / `<c><v>42</v></c>`（Excel 常写的）。
                //    只认后者的话，本应用自己产出的表格会被读成一片空白。
                val cells = CELL.findAll(row.value).map { cell ->
                    val type = cell.groupValues[1]
                    val inline = cell.groupValues[2]
                    val v = cell.groupValues[3]
                    when {
                        inline.isNotEmpty() -> unescape(inline)
                        type == "s" -> shared.getOrNull(v.toIntOrNull() ?: -1) ?: v
                        else -> unescape(v)
                    }
                }
                val line = cells.joinToString("\t").trimEnd()
                if (line.isNotBlank()) sb.append(line).append('\n')
            }
            sb.append('\n')
        }
        return Result(sb.toString().trim().take(MAX_CHARS), "xlsx")
    }

    /** 去标签 + 反转义，保留换行。 */
    private fun plain(xml: String): String = unescape(
        TAG.replace(xml.replace("<w:tab/>", "\t").replace("<w:br/>", "\n"), ""),
    )

    private fun unescape(raw: String): String = raw
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private const val MAX_ENTRIES = 400
    private const val MAX_ROWS = 200

    private val TAG = Regex("<[^>]+>")
    private val RUN = Regex("<a:t[^>]*>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
    private val ROW = Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex(
        "<c[^>]*?(?:t=\"([^\"]*)\")?[^>]*>(?:\\s*<is>\\s*<t[^>]*>(.*?)</t>|\\s*<v>(.*?)</v>)",
        RegexOption.DOT_MATCHES_ALL,
    )
}
