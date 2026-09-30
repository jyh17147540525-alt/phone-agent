package com.pocketagent.memorylogic

import java.security.MessageDigest

/**
 * SHA-256 工具。
 *
 * ⚠️ 为什么可以住在一个「零 Android 依赖」的模块里：`java.security.MessageDigest`
 * 是 **JDK 标准库**，不是 Android API（`:plugin:api` 的 `PluginIntegrity` 同理）。
 * 换成 `android.util.Base64` 之类的就再也进不了离线验证器了。
 *
 * 本模块有两处要用它，用途完全不同，所以分成两个方法：
 *
 * - [sha256Hex] —— **完整性**。卸载出去的内容在恢复时要对指纹，对不上说明
 *   文件被截断/改写了，此时必须报 [RecallOutcome.Corrupted] 而不是把半截内容
 *   当成功返回。
 * - [shortHex] —— **给人看的短标识**。只用于生成 id（原子的、卸载引用的）。
 *   刻意不复用 [sha256Hex]：短标识不承担校验职责，混用会让后来的人以为
 *   "id 撞了 = 内容撞了"。
 */
internal object Hashing {

    private const val HEX_DIGITS = "0123456789abcdef"

    /** UTF-8 字节的 SHA-256，小写十六进制（64 位）。 */
    fun sha256Hex(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4])
            sb.append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** 字符串 UTF-8 字节的 SHA-256。 */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** 取 SHA-256 的前 [length] 位十六进制，用作可读的短标识。 */
    fun shortHex(text: String, length: Int): String {
        require(length in 1..64) { "短标识长度必须在 1..64，当前 $length" }
        return sha256Hex(text).take(length)
    }
}