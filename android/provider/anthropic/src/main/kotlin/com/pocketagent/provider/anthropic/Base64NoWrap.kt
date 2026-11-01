package com.pocketagent.provider.anthropic

/**
 * 不换行的 Base64。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要自己写
 * ═══════════════════════════════════════════════════════════════
 *
 * · `android.util.Base64` —— 是 Android API，本模块的核心逻辑要能离线测，
 *   不能依赖 Android 运行时。
 * · `java.util.Base64` —— API 26+，而本项目 minSdk 更低。
 *
 * ⇒ 手写。只有编码，没有解码（本模块不需要解码）。
 *
 * ⚠️ 与 `:mcp` 里的同名实现是**两处独立代码**。没有抽公共模块是因为
 *    `:mcp` 依赖 Android，而这里必须保持纯 Kotlin —— 为了几十行代码
 *    引入一个跨模块依赖（还得把它也做成 Android-free）不值得。
 *    两处都有逐字节的测试盯着。
 */
internal object Base64NoWrap {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 0x3F])
            out.append(ALPHABET[(n ushr 12) and 0x3F])
            out.append(ALPHABET[(n ushr 6) and 0x3F])
            out.append(ALPHABET[n and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = (bytes[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append("==")
            }
            2 -> {
                val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                    ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3F])
                out.append(ALPHABET[(n ushr 12) and 0x3F])
                out.append(ALPHABET[(n ushr 6) and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }
}