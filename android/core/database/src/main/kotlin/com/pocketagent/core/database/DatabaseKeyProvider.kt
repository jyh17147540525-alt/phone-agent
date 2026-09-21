package com.pocketagent.core.database

import com.pocketagent.core.crypto.CryptoManager
import java.io.File
import java.security.SecureRandom

/**
 * SQLCipher 口令的生成与保管。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么需要这一层（绕不开的一环）
 * ═══════════════════════════════════════════════════════════════
 *
 * SQLCipher 加密整个数据库文件，但它自己需要一个**口令**。
 * 于是问题原样回来了：口令存哪儿？
 *
 *  - 硬编码在代码里 → 反编译即可得，等于没加密
 *  - 存在 SharedPreferences → 明文落盘，直接违反原则第 7 条
 *  - 每次让用户输入 → 打开应用先输密码，这不是本产品的形态
 *
 * 解法是**两级密钥**：
 *
 * ```
 *   Android Keystore（TEE/StrongBox，永不出芯片）
 *          │  保护
 *          ▼
 *   主密钥 AES-256-GCM
 *          │  加密
 *          ▼
 *   随机 32 字节的 SQLCipher 口令 ── 密文落盘（本文件管的那个文件）
 *          │  打开
 *          ▼
 *   数据库文件（里面装着 AES-GCM 加密过的 API Key）
 * ```
 *
 * 这样落盘的全是密文，而解开它的钥匙在硬件里。
 * **备份这个文件没有意义** —— 换台设备解不开。
 *
 * ⚠️ 代价要说清楚：**主密钥一旦丢失（清除应用数据、恢复出厂、
 *    指纹变更导致密钥作废），数据库就永久打不开了。** 这不是 bug，
 *    是"Key 永不落明文"的必然推论。所以 [PassphraseUnavailableException]
 *    的文案必须让用户明白"数据没了"而不是"重试一下"。
 */
class DatabaseKeyProvider(
    private val crypto: CryptoManager,
    private val keyFile: File,
) {

    /**
     * 取出数据库口令；首次调用时生成并落盘。
     *
     * @throws PassphraseUnavailableException 口令文件损坏，或主密钥已失效。
     *         两者都意味着**已存的数据不可恢复**，调用方应引导用户重置。
     */
    fun loadOrCreate(): ByteArray {
        if (!keyFile.exists()) return createAndPersist()
        return readExisting()
    }

    /** 口令文件是否存在（不等于可用 —— 见 [loadOrCreate]） */
    fun exists(): Boolean = keyFile.exists()

    /**
     * 删除口令文件。
     *
     * 用于「一键清除所有数据」：删掉口令等于把数据库变成一堆无法解开的字节。
     * **不做安全擦除** —— 在闪存上覆写原位置既不可靠也无意义（磨损均衡会把
     * 数据搬到别处），真正的保障是主密钥被销毁后密文没有解密途径。
     */
    fun delete() {
        if (keyFile.exists()) keyFile.delete()
    }

    // ─────────────────────────────────────────────────────────────
    //  内部
    // ─────────────────────────────────────────────────────────────

    private fun readExisting(): ByteArray {
        val raw = try {
            keyFile.readBytes()
        } catch (e: Exception) {
            throw PassphraseUnavailableException(
                "读不出数据库口令文件：${e.message ?: e::class.simpleName}",
                cause = e,
            )
        }

        val blob = PassphraseFileFormat.decode(raw)

        return try {
            crypto.decrypt(blob)
        } catch (e: Exception) {
            // 走到这里说明文件格式没问题，但主密钥解不开它。
            // 典型原因：清除应用数据、恢复出厂、或指纹变更触发了密钥作废。
            // 这与"文件损坏"是两回事，文案必须区分开。
            throw PassphraseUnavailableException(
                "数据库口令解不开 —— 保护它的系统密钥已经失效（可能是清除过应用数据、" +
                    "恢复过出厂设置，或改过锁屏/指纹）。已保存的 API Key 无法再读取，需要重新添加。",
                cause = e,
            )
        }
    }

    private fun createAndPersist(): ByteArray {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }

        val blob = try {
            crypto.encrypt(passphrase)
        } catch (e: Exception) {
            passphrase.fill(0)
            throw PassphraseUnavailableException(
                "系统密钥库不可用，无法建立加密存储：${e.message ?: e::class.simpleName}",
                cause = e,
            )
        }

        try {
            writeAtomically(PassphraseFileFormat.encode(blob))
        } catch (e: Exception) {
            passphrase.fill(0)
            throw PassphraseUnavailableException(
                "写入口令文件失败：${e.message ?: e::class.simpleName}",
                cause = e,
            )
        }

        return passphrase
    }

    /**
     * 原子写入：先写临时文件，再 rename 覆盖。
     *
     * ⚠️ 不能直接往目标文件写。写到一半掉电 / 被系统杀掉，会留下一个
     *    **长度不对的文件** —— 而它是数据库唯一的钥匙。rename 在同一文件系统内
     *    是原子的：要么看到旧文件，要么看到完整的新文件，不存在中间态。
     */
    private fun writeAtomically(bytes: ByteArray) {
        keyFile.parentFile?.mkdirs()
        val temp = File(keyFile.parentFile, keyFile.name + TEMP_SUFFIX)
        temp.writeBytes(bytes)

        if (!temp.renameTo(keyFile)) {
            // rename 失败时目标文件**没有**被破坏（旧内容还在），
            // 所以这里清掉临时文件即可，不必惊慌
            temp.delete()
            throw PassphraseUnavailableException(
                "无法把口令文件落盘（rename 失败）。请检查存储空间是否充足。"
            )
        }
    }

    private companion object {
        /** 256 位。SQLCipher 的口令长度不限，但没必要比主密钥更强 */
        const val PASSPHRASE_BYTES = 32

        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * 数据库口令不可用。
 *
 * 单独定义一个异常类型，是因为这个错误**不能和一般 IO 错误混在一起处理**：
 * 一般 IO 错误可以重试，而这个错误意味着数据已经没了，
 * 重试只会让用户等更久然后看到同样的结果。
 */
class PassphraseUnavailableException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
