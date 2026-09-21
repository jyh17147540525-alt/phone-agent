package com.pocketagent.plugin.api

import java.security.MessageDigest

/**
 * 插件内容完整性校验。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它防的是什么，不防什么
 * ═══════════════════════════════════════════════════════════════
 *
 * **防**：下载途中被替换、被污染、被缓存服务器投毒。市场目录里每个条目都带
 * 一个 `sha256`，下载完对一遍，不匹配就丢弃 —— 这是"来源可信但传输不可信"
 * 场景下的最低保障。
 *
 * **不防**：源本身就在分发恶意插件。哈希是**源自己声明的**，源想给你什么哈希
 * 就给什么哈希。要防这个需要作者签名（`signature` 字段，M2 再上），
 * 哈希替代不了。
 *
 * 所以界面上不能写"已验证安全"，只能写"文件与源声明的一致"。
 * 这两句话的区别，就是 [PluginTrustLevel.MARKET_UNSIGNED] 存在的理由。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么单独放一个对象
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为它是**纯 JVM 逻辑**（`java.security.MessageDigest` 是 JDK 标准库，
 * 不是 Android API）。放在 plugin/api 而不是 app 里，就能在没有 Android SDK
 * 的机器上跑单测 —— 而哈希比较恰恰是那种"写错了也照样能跑"的代码，
 * 不测就等于没写。
 */
object PluginIntegrity {

    /** SHA-256 的十六进制长度 */
    private const val HEX_LENGTH = 64

    /**
     * 计算内容的 SHA-256，返回小写十六进制。
     *
     * 为什么不复用 `core:network` 里那个十六进制转换：那个是给日志脱敏用的，
     * 语义不同。脱敏器哪天为了可读性加个分隔符，这里的校验就会静默失效 ——
     * 耦合两件不相干的事，代价永远比省下的十行代码大。
     */
    fun sha256Hex(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content)
        val sb = StringBuilder(HEX_LENGTH)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4])
            sb.append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * 校验内容是否与声明的哈希一致。
     *
     * @param expected 源声明的哈希。容忍大小写、前后空白与 `sha256:` 前缀 ——
     *   这些差异在真实数据里天天出现，因为各家工具的输出格式不一样。
     *   但**格式合法性与内容匹配是两件事**：格式不对直接判 false，
     *   不能"看不懂就放过"。
     */
    fun verify(content: ByteArray, expected: String): Boolean {
        val normalized = normalize(expected) ?: return false
        val actual = sha256Hex(content)
        // MessageDigest.isEqual 是常数时间比较。
        // 这里其实不怕时序攻击（攻击者控制的是文件内容，不是期望值），
        // 但用对的原语不花额外成本，也省得后来的人纠结。
        return MessageDigest.isEqual(
            actual.toByteArray(Charsets.US_ASCII),
            normalized.toByteArray(Charsets.US_ASCII),
        )
    }

    /**
     * 归一化声明的哈希；格式不合法返回 null。
     *
     * 只接受 64 位十六进制（可带 `sha256:` / `sha256=` 前缀，可大写）。
     * 其余一律 null —— 包括空串和 `"unknown"`。
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        for (prefix in PREFIXES) {
            if (s.startsWith(prefix, ignoreCase = true)) {
                s = s.substring(prefix.length).trim()
                break
            }
        }
        if (s.length != HEX_LENGTH) return null
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return s.lowercase()
    }

    private val PREFIXES = listOf("sha256:", "sha256=", "sha-256:")
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
