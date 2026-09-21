package com.pocketagent.core.database

import com.pocketagent.core.crypto.EncryptedBlob

/**
 * SQLCipher 口令文件的二进制格式。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么自己定一个格式，而不是塞进 DataStore / JSON
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **这个文件是数据库的钥匙。** 数据库里存的是加密后的 API Key，
 *    而打开数据库的口令又是加密后存在这里的。用 JSON 装一个二进制密文，
 *    得先 base64 一遍 —— 凭空多一层编码，出错面还更大。
 *
 * 2. **格式要能自证。** 前四个字节是魔数，第五个是版本号。将来若要换算法
 *    （比如主密钥加生物识别），靠版本号就能识别旧文件并走迁移路径；
 *    没有它，只能靠"解不开"来判断，而解不开的原因有很多种。
 *
 * 3. **不依赖 serialization 插件。** `:core:database` 没有装那个插件，
 *    而这个格式只有三个字段，手写反而更短、更好测。
 *
 * 布局：
 * ```
 *   偏移  长度        内容
 *   0     4          魔数 'P' 'A' 'K' 'S'
 *   4     1          版本号
 *   5     1          IV 长度（1..255）
 *   6     ivLen      IV
 *   6+n   rest       密文
 * ```
 *
 * ⚠️ 整个文件里没有任何明文。密文要用 Keystore 里的主密钥才能解开，
 *    而主密钥永不离开 TEE/StrongBox —— 所以这个文件**可以**被备份、
 *    可以被 root 后读走，都解不出东西。
 */
internal object PassphraseFileFormat {

    private val MAGIC = byteArrayOf(
        'P'.code.toByte(),
        'A'.code.toByte(),
        'K'.code.toByte(),
        'S'.code.toByte(),
    )

    private const val VERSION: Byte = 1

    /** 魔数(4) + 版本(1) + IV 长度(1) */
    private const val HEADER_BYTES = 6

    /**
     * 密文的最小长度 = GCM 认证标签长度。
     *
     * 与 `CryptoManager` 里的 `GCM_TAG_LENGTH_BITS = 128` 对应（128 位 = 16 字节）。
     * 两个常量分处不同模块，无法直接互相引用 —— 改动其一时必须同时改另一个，
     * 所以这条注释存在的意义就是让改动者知道还有另一处。
     */
    private const val MIN_CIPHERTEXT_BYTES = 16

    fun encode(blob: EncryptedBlob): ByteArray {
        // IV 长度用单字节承载，所以上限 255。GCM 的 IV 是 12 字节，远够用。
        // 这里 require 而不是静默截断 —— 截断会写出一个自己都解不开的文件。
        require(blob.iv.size in 1..255) { "IV 长度 ${blob.iv.size} 超出格式上限 255" }

        val out = ByteArray(HEADER_BYTES + blob.iv.size + blob.ciphertext.size)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION
        out[5] = blob.iv.size.toByte()
        blob.iv.copyInto(out, HEADER_BYTES)
        blob.ciphertext.copyInto(out, HEADER_BYTES + blob.iv.size)
        return out
    }

    /**
     * @throws PassphraseUnavailableException 文件不符合本格式（损坏 / 被截断 / 版本不认识）
     */
    fun decode(bytes: ByteArray): EncryptedBlob {
        if (bytes.size <= HEADER_BYTES) {
            throw PassphraseUnavailableException(
                "口令文件只有 ${bytes.size} 字节，连文件头都不完整。"
            )
        }

        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) {
                throw PassphraseUnavailableException(
                    "口令文件的标识不匹配 —— 这个文件不是本应用写的，或者内容已经被改坏。"
                )
            }
        }

        val version = bytes[4]
        if (version != VERSION) {
            // 刻意区分"版本不认识"和"内容损坏"：前者是新旧版本冲突（可恢复），
            // 后者是数据没了（不可恢复）。混在一起会让用户看到错误的处置建议。
            throw PassphraseUnavailableException(
                "口令文件的版本是 $version，本版本只认识 $VERSION。"
            )
        }

        val ivLength = bytes[5].toInt() and 0xFF
        if (ivLength == 0) {
            throw PassphraseUnavailableException("口令文件记录的 IV 长度为零，文件已损坏。")
        }
        if (bytes.size <= HEADER_BYTES + ivLength) {
            throw PassphraseUnavailableException("口令文件在 IV 处被截断，文件已损坏。")
        }

        val iv = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength)
        val ciphertext = bytes.copyOfRange(HEADER_BYTES + ivLength, bytes.size)

        // ⚠️ 这一条不是洁癖，是为了**让报错指向正确的方向**。
        //
        //    GCM 的认证标签是 16 字节，所以密文短于 16 字节时解密必然失败。
        //    但失败发生在 CryptoManager 里，异常类型是 AEADBadTagException ——
        //    而 DatabaseKeyProvider 把它解读成"主密钥失效，数据没了"。
        //
        //    于是文件被截断这件事，会以"系统密钥失效"的面目呈现给用户，
        //    并建议他去查指纹/恢复出厂设置。**排查方向完全错了。**
        //
        //    在这里拦住，就能在格式层给出准确的原因：文件坏了。
        if (ciphertext.size < MIN_CIPHERTEXT_BYTES) {
            throw PassphraseUnavailableException(
                "口令文件的密文只有 ${ciphertext.size} 字节，短于 GCM 认证标签所需的 " +
                    "$MIN_CIPHERTEXT_BYTES 字节 —— 文件已被截断。"
            )
        }

        return EncryptedBlob(ciphertext = ciphertext, iv = iv)
    }
}
