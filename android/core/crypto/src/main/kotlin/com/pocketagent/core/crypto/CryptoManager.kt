package com.pocketagent.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的加密存储核心。
 *
 * 三层防护：
 *  1. **Android Keystore** 生成 AES-256-GCM 主密钥，密钥永不离开 TEE/StrongBox
 *  2. **每条记录独立随机 IV**，密文 + IV 存入数据库（数据库整体再由 SQLCipher 加密）
 *  3. **应用层访问控制**：解密仅在发起请求瞬间进行，用完立即清零
 *
 * 安全红线（任何 PR 违反即拒绝合并）：
 *  - 禁止把明文 Key 写入 SharedPreferences / 文件 / 日志 / 崩溃上报
 *  - 禁止把 Key 存入 String（JVM 字符串池无法擦除）—— 一律用 ByteArray
 *  - 禁止在 toString() / 异常信息中暴露 Key
 *  - Key 管理界面必须设置 FLAG_SECURE 防截屏
 *
 * 参考：Android 密钥库系统
 * https://developer.android.com/privacy-and-security/keystore
 */
class CryptoManager(
    private val keyAliasProvider: KeyAliasProvider = KeyAliasProvider.Default,
) {

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    // ─────────────────────────────────────────────────────────────
    //  主密钥管理
    // ─────────────────────────────────────────────────────────────

    /**
     * 获取（或首次创建）主密钥。
     *
     * @param requireUserAuth 是否要求用户认证（生物识别/锁屏）才能使用该密钥。
     *        开启后每次解密都会触发系统认证，安全性最高但体验有损耗。
     *        建议：默认关闭，在设置中提供「用生物识别保护 API Key」开关。
     */
    fun getOrCreateMasterKey(requireUserAuth: Boolean = false): SecretKey {
        val alias = keyAliasProvider.masterKeyAlias()
        keyStore.getKey(alias, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            // 优先使用 StrongBox（独立安全芯片），不可用时回退到 TEE
            .setIsStrongBoxBacked(true)
            .setUserAuthenticationRequired(requireUserAuth)
            .apply {
                if (requireUserAuth) {
                    setUserAuthenticationParameters(
                        AUTH_VALIDITY_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                    // 新增指纹/面容时作废密钥，防止攻击者录入自己的生物特征后解密
                    setInvalidatedByBiometricEnrollment(true)
                }
            }
            .build()

        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            // StrongBox 在部分机型上不可用，回退到 TEE
            val fallback = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(requireUserAuth)
                .build()
            generator.init(fallback)
            generator.generateKey()
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  加解密
    // ─────────────────────────────────────────────────────────────

    /**
     * 加密。每次调用使用独立随机 IV（由 Cipher 自动生成），
     * IV 与密文一起返回，二者都不敏感。
     */
    fun encrypt(plaintext: ByteArray, requireUserAuth: Boolean = false): EncryptedBlob {
        val key = getOrCreateMasterKey(requireUserAuth)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBlob(ciphertext = ciphertext, iv = cipher.iv)
    }

    /**
     * 解密。
     *
     * @throws UserNotAuthenticatedException 当主密钥要求用户认证但尚未认证时抛出，
     *         调用方应引导用户完成生物识别后重试。
     */
    fun decrypt(blob: EncryptedBlob, requireUserAuth: Boolean = false): ByteArray {
        val key = getOrCreateMasterKey(requireUserAuth)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertext)
    }

    /**
     * 便捷方法：在受控作用域内使用明文 Key，作用域结束后**强制清零**。
     *
     * 这是唯一推荐的 Key 使用方式 —— 不要在任何地方长期持有解密后的 ByteArray。
     *
     * ```kotlin
     * crypto.withDecryptedKey(blob) { plain ->
     *     val credential = ProviderCredential(plain)
     *     try { provider.chat(req, credential).collect { ... } }
     *     finally { credential.clear() }
     * }
     * // 此处 plain 已被清零
     * ```
     */
    inline fun <T> withDecryptedKey(
        blob: EncryptedBlob,
        requireUserAuth: Boolean = false,
        block: (ByteArray) -> T,
    ): T {
        val plain = decrypt(blob, requireUserAuth)
        return try {
            block(plain)
        } finally {
            // 无论成功失败都清零，防止异常路径泄漏
            plain.fill(0)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  销毁
    // ─────────────────────────────────────────────────────────────

    /**
     * 彻底销毁主密钥。调用后所有已加密的 Key **永久不可恢复**。
     * 用于「一键清除所有数据」。
     */
    fun destroyMasterKey() {
        val alias = keyAliasProvider.masterKeyAlias()
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    fun hasMasterKey(): Boolean = keyStore.containsAlias(keyAliasProvider.masterKeyAlias())

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val AUTH_VALIDITY_SECONDS = 30
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/** 加密结果：密文 + IV。两者都不敏感，可安全落库。 */
data class EncryptedBlob(
    val ciphertext: ByteArray,
    val iv: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is EncryptedBlob &&
            ciphertext.contentEquals(other.ciphertext) && iv.contentEquals(other.iv))

    override fun hashCode(): Int = 31 * ciphertext.contentHashCode() + iv.contentHashCode()

    /** 禁止打印内容 */
    override fun toString(): String = "EncryptedBlob(${ciphertext.size}B)"
}

/** 密钥别名提供者 —— 抽象出来是为了支持「多个密钥库实例」与单元测试注入 */
interface KeyAliasProvider {
    fun masterKeyAlias(): String

    object Default : KeyAliasProvider {
        override fun masterKeyAlias(): String = "pocketagent_master_key_v1"
    }
}
