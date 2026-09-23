package com.pocketagent.filelogic

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * 字节 → 文本。**只做一件事：严格解码，不尽力而为。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这几行值得单独一个文件、还要离线测
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为「用哪种方式把字节变成字符串」这件事的两种写法**都返回 `String`**，
 * 而它们的后果完全不同：
 *
 * ```kotlin
 * String(bytes, Charsets.UTF_8)          // ← 非法字节变成 U+FFFD（�），不报错
 * decoder.onMalformedInput(REPORT)       // ← 抛 CharacterCodingException
 * ```
 *
 * 第一种写法在**所有正常文件上都完全正确** —— 直到有人读到一个
 * 图片、一个 zip、一个 GBK 编码的旧文档。那时它会交出一份
 * "能打开、看着像文本、实际已经损坏"的字符串，而 agent 会**基于它做判断**
 * 并据此去改用户的其他文件。
 *
 * 本项目对这类取舍的立场一贯是 **宁可如实失败，也不给一份错的**
 * （见 [FileReadResult.NotUtf8]）。所以解码必须走第二种写法，
 * 而"第二种写法确实在报错"这件事必须能被测试钉住 ——
 * 它是**沉默的那一半**：正常路径上两种写法一模一样。
 *
 * ⚠️ 放在 `:filelogic` 而不是通道实现里，正是因为通道实现（SAF / Shizuku）
 *    都是 Android 模块，进不了离线跑器。而这条规则是**通道无关**的 ——
 *    换一条通道不该换一种解码方式。
 */
object TextDecoding {

    /** UTF-8 BOM 的三个字节。 */
    private const val BOM_0: Int = 0xEF
    private const val BOM_1: Int = 0xBB
    private const val BOM_2: Int = 0xBF

    /**
     * 严格按 UTF-8 解码。
     *
     * 顺带剥掉开头的 BOM：它是**编码标记**，不是内容。
     * 留在正文开头会让 agent 把 `\uFEFF` 当成文件的一部分 ——
     * 表现是"这个文件的第一个字符很奇怪"，而那是它自己造成的。
     *
     * ⚠️ 只在**开头**剥。中间出现的 `EF BB BF` 是真实的字符数据，
     *    动了它就是篡改内容。
     */
    fun decodeUtf8(bytes: ByteArray): FileReadResult {
        val body = stripBom(bytes)

        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        return try {
            FileReadResult.Text(decoder.decode(ByteBuffer.wrap(body)).toString())
        } catch (e: CharacterCodingException) {
            // ⚠️ 这句里**不能**出现文件内容 —— 它可能一路走到界面上，
            //    而"读不出来"这件事不该顺带把读不出来的东西展示一遍。
            FileReadResult.NotUtf8(
                "这个文件不是 UTF-8 文本（可能是图片、压缩包，或者用了别的编码）",
            )
        }
    }

    private fun stripBom(bytes: ByteArray): ByteArray =
        if (bytes.size >= 3 &&
            bytes[0] == BOM_0.toByte() &&
            bytes[1] == BOM_1.toByte() &&
            bytes[2] == BOM_2.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
}
