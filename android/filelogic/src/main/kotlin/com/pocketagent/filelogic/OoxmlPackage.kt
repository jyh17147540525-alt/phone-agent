package com.pocketagent.filelogic

import java.io.ByteArrayOutputStream
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OOXML 包（本质是 zip）的组装 —— XLSX 与 DOCX 共用这一层。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么时间戳必须固定（这是本文件存在的首要理由）
 * ═══════════════════════════════════════════════════════════════
 *
 * `ZipEntry` 默认取**当前时间**。那会让同一份内容在两次生成里产出
 * **不同的字节**，于是：
 *
 * - `sha256(文件)` 每次都变 —— 无法用它做完整性校验、去重或缓存键；
 * - 用户把文件放进同步盘，会看到"同一份内容反复被更新"；
 * - ★ 更关键的是它与 `:memorylogic` 的 `OffloadRef` 取舍**直接冲突**：
 *   那边刚确立「id 由内容派生、**不含时间**，以保证重试幂等」，
 *   而同一份内容若在这里产生两个指纹，就是同一个坑换了个地方。
 *
 * ⇒ 固定成 ZIP 纪元起点（1980-01-01T00:00:00Z，DOS 时间能表示的最小值）。
 *
 * ⚠️ 用 [Instant.parse] 而不是手写毫秒数：**时间戳不该由人脑换算**。
 * ⚠️ 用 [FileTime] 而不是 `ZipEntry.setTime(Long)`：后者走 DOS **本地**时间，
 *    同一个 UTC 时刻在不同时区会写出不同字节 —— 跨机器就不确定了。
 *    （`FileTime` 会同时写 DOS 字段与扩展的 UTC 字段，两边都确定。）
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么只用 `java.util.zip`
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是 **JDK 标准库**，不是 Android API —— 所以本模块能继续进离线验证器
 * （同 `:memorylogic` 的 `Hashing` 用 `MessageDigest` 的理由）。
 * 换成任何第三方 OOXML 库，这一层就会立刻退化成"只能靠真机验证"，
 * 而它恰恰是**最适合离线验证**的一类代码：输入确定 → 输出字节确定。
 */
object OoxmlPackage {

    /** 每个 XML 部件都要的声明头。 */
    const val XML_DECLARATION: String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""

    /** 固定时间戳。见类注释。 */
    private val FIXED_TIME: FileTime = FileTime.from(Instant.parse("1980-01-01T00:00:00Z"))

    /**
     * 压缩级别。
     *
     * ⚠️ 显式写死，而不是依赖 `Deflater.DEFAULT_COMPRESSION`：默认值将来
     *    若变化，产出字节就会变 —— 而"同样的输入产出同样的字节"是本层
     *    唯一的承诺。6 是 zlib 的常用默认档，压缩率与速度平衡。
     */
    const val COMPRESSION_LEVEL: Int = 6

    /** 一个部件：包内路径 → 字节。 */
    class Part(val path: String, val bytes: ByteArray) {
        init {
            require(path.isNotBlank()) { "部件路径不能为空" }
            require(!path.startsWith("/")) { "OOXML 部件路径不带前导斜杠：$path" }
        }
    }

    /** 文本部件的便捷构造（UTF-8）。 */
    fun textPart(path: String, text: String): Part = Part(path, text.toByteArray(Charsets.UTF_8))

    /**
     * 打成 zip。
     *
     * @param parts **顺序即写入顺序**。收 `List` 而不是 `Map`：`HashMap`
     *   的迭代顺序不定，会让同一份内容产出不同字节 —— 那就把本类
     *   唯一的承诺毁掉了。
     */
    fun zip(parts: List<Part>): ByteArray {
        require(parts.isNotEmpty()) { "OOXML 包至少要有一个部件" }
        val names = parts.map { it.path }
        require(names.size == names.toSet().size) { "部件路径不能重复：$names" }

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(COMPRESSION_LEVEL)
            for (part in parts) {
                zip.putNextEntry(
                    ZipEntry(part.path).apply {
                        lastModifiedTime = FIXED_TIME
                        // 额外字段与注释也可能带不确定内容，一律不写
                        setExtra(null)
                        setComment(null)
                    },
                )
                zip.write(part.bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * XML 文本转义。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 控制字符是**剥离**，不是转义 —— 因为 XML 1.0 做不到
     * ═══════════════════════════════════════════════════════════════
     *
     * JSON 可以把控制字符写成 `\u0000`（见 [OfficeText.jsonString]），
     * 但 **XML 1.0 连字符引用都不允许引用它们** —— `&#x0;` 本身就是非法的。
     * 所以只有两个选择：剥离，或者拒绝生成整份文档。
     *
     * 选了剥离，理由是：这些字符在办公文档里**本来就不可见**
     * （用户从网页 / PDF 复制文本时最容易带上它们），而"因为粘了一段
     * 带 `\u0001` 的文字就整份文档生成失败"，对用户是一个无法理解的错误。
     *
     * ⚠️ 但**代理对（emoji）必须保住** —— 所以这里按 **code point** 遍历
     *    而不是按 `Char`。按 `Char` 遍历会把 `U+D83D` / `U+DE00` 这类
     *    代理项当成"非法字符"逐个剥离，**把 emoji 撕成碎片**，
     *    而产出的 XML 依然合法 —— 又是一次静默的破坏。
     */
    fun xmlText(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val width = Character.charCount(cp)
            when {
                cp == '&'.code -> sb.append("&amp;")
                cp == '<'.code -> sb.append("&lt;")
                cp == '>'.code -> sb.append("&gt;")
                cp == '"'.code -> sb.append("&quot;")
                cp == '\''.code -> sb.append("&apos;")
                isLegalXmlCodePoint(cp) -> sb.appendCodePoint(cp)
                // 非法控制字符：剥离（见上面的注释）
                else -> Unit
            }
            i += width
        }
        return sb.toString()
    }

    /**
     * XML 1.0 的 `Char` 产生式：
     *
     * ```
     * Char ::= #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]
     * ```
     *
     * ⚠️ 最后一段是**按 code point** 的 —— 这正是 [xmlText] 必须按
     *    code point 遍历、而不能按 `Char` 遍历的规范依据。
     */
    private fun isLegalXmlCodePoint(cp: Int): Boolean =
        cp == 0x9 || cp == 0xA || cp == 0xD ||
            cp in 0x20..0xD7FF ||
            cp in 0xE000..0xFFFD ||
            cp in 0x10000..0x10FFFF
}
