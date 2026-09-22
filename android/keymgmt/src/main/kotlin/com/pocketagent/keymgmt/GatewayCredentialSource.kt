package com.pocketagent.keymgmt

import com.pocketagent.core.crypto.CryptoManager
import com.pocketagent.core.crypto.EncryptedBlob
import com.pocketagent.core.database.dao.CredentialDao
import com.pocketagent.provider.gateway.CredentialDecryptException
import com.pocketagent.provider.gateway.CredentialSource
import com.pocketagent.provider.gateway.ResolvedCredential
import timber.log.Timber

/**
 * 只需要"把 blob 解密"这一件事的最小接口。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么不让 [GatewayCredentialSource] 直接依赖 `CryptoManager`
 * ═══════════════════════════════════════════════════════════════
 *
 * `CryptoManager` 是个 **final class**，而它的构造器第一行就是
 * `KeyStore.getInstance("AndroidKeyStore").load(null)` —— 在普通 JVM 单测里
 * 根本 new 不出来（`NoClassDefFoundError` / `KeyStoreException`）。
 *
 * 于是"用假件替换它"这条路是堵的：不能继承（final），不能直接构造
 * （要 Android Keystore）。而本类里**最值得测**的恰好是纯接线逻辑 ——
 *
 * - 凭据不存在要返回 null 而不是抛异常
 * - 解出来的明文**不能**被提前清零（这是最容易接错、且症状最误导的一处）
 * - 解密失败要抛 [CredentialDecryptException] 而不是返回 null
 * - `ciphertext` / `iv` 有没有传反
 *
 * 这些一条都不需要真的跑 AES-GCM。所以按本项目一贯的做法
 * **契约与实现分离**：这里只声明需要的能力，真实现照旧是 `CryptoManager`。
 *
 * ⚠️ 刻意**不**去改 `core:crypto` 加接口 —— 那个模块零测试、全是
 *    Android 依赖，为了一处接线去动它，风险大于收益。窄接口放在
 *    需要它的地方就够了，而且它只有"解密"一个方法，将来也不容易膨胀。
 */
fun interface CredentialDecryptor {
    /**
     * 解密。
     *
     * ⚠️ 返回的字节数组**归调用方所有**，本方法不做清零 ——
     *    `CryptoManager.decrypt` 的语义正是如此（要自动清零的是
     *    `withDecryptedKey`，那个用在这里是错的，见类注释）。
     */
    fun decrypt(blob: EncryptedBlob): ByteArray
}

/** 把 [CryptoManager] 适配成窄接口。实现在生产代码里就是这一行。 */
fun CryptoManager.asDecryptor(): CredentialDecryptor =
    CredentialDecryptor { blob -> decrypt(blob) }

/**
 * [CredentialSource] 的真实实现 —— **明文 Key 从密文到网关的唯一通道**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它在整条链路里的位置
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   GatewayCore.complete()
 *        │
 *        ├─ 1. 路由        （不碰 Key）
 *        ├─ 2. 熔断        （不碰 Key）★ 超预算在这里就返回了
 *        ├─ 3. 解密   ←── 本类被调用的唯一位置
 *        ├─ 4. 转发
 *        └─ 5. 计量
 * ```
 *
 * 网关**刻意**把解密放在熔断之后，所以"超预算的请求不解密"这条不变量
 * 是由调用顺序保证的（`GatewayCoreTest.超预算时不解密凭据` 钉住了它）。
 * 本类不需要、也不可能自己判断"该不该解密" —— 它只负责"解对了"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 与 `CredentialRepository.useDefaultKey` 的关系
 * ═══════════════════════════════════════════════════════════════
 *
 * 两者都解密凭据、都保证清零，但**服务的是不同的调用者**：
 *
 * | | `useDefaultKey` | `resolve` |
 * |---|---|---|
 * | 选哪条凭据 | 按 purpose 取"当前使用"的那条 | 按 id 精确取 |
 * | 用途 | 用户手动触发的一次对话 / 校验 | 网关路由选中的那条模型配置 |
 * | 返回 | 在受控 lambda 里直接用掉 | 交出持有者、由网关在 `finally` 里清零 |
 *
 * 后者的返回形态看起来更危险（明文离开了本类的栈帧），但那是**必须的** ——
 * 网关要把它交给 `LlmProvider` 去构造请求头，而那是一个异步流式过程，
 * 没法塞进一个同步 lambda。安全由两点保证：
 *
 * 1. 网关那边用 `withResolvedCredential { ... } finally { clear() }` 包住
 * 2. [ResolvedCredential] 与它转出的 `ProviderCredential` **共享同一个
 *    数组**（刻意不复制），所以清一次就够了，不会漏下一份
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三类失败必须分开
 * ═══════════════════════════════════════════════════════════════
 *
 * | 情况 | 返回 / 抛出 | 用户要做的 |
 * |---|---|---|
 * | 凭据不存在（被删了） | 返回 `null` | 去「模型」页重新关联一把 Key |
 * | 密文在但解不开 | 抛 [CredentialDecryptException] | 重新填 Key（主密钥变了） |
 * | 数据库读失败 | **抛原异常** | 重试；这是我们自己的 bug |
 *
 * ⚠️ 前两者绝不能合并。合并成"都返回 null"的表现是：用户换机之后
 *    打开应用，所有 Key 都显示"未配置"，他会以为是自己没保存上，
 *    然后把 11 家厂商的 Key 全部重填一遍 —— 而真实原因是主密钥
 *    变了，重填是**对的**，但他不理解为什么，也没法确认这次会不会
 *    再丢。给一句"Key 解不开了，请重新填写"才是有用的。
 */
class GatewayCredentialSource(
    private val dao: CredentialDao,
    private val decryptor: CredentialDecryptor,
) : CredentialSource {

    /** 便捷构造：生产代码用这个 */
    constructor(dao: CredentialDao, crypto: CryptoManager) :
        this(dao, crypto.asDecryptor())

    /**
     * 读出密文并解密。
     *
     * ⚠️ 解密走 [CredentialDecryptor] 而不是 `CryptoManager.withDecryptedKey` ——
     *    后者的作用是"用完自动清零"，而这里的明文**必须活着交出去**。
     *    用它反而会让我们返回一个已经被清零的数组（阴险：长度对、
     *    内容全 0，表现为上游 401 而排查方向跑到"Key 是不是过期了"）。
     *
     *    清零责任随之转移到调用方，见类注释。
     */
    override suspend fun resolve(credentialId: String): ResolvedCredential? {
        val entity = dao.byId(credentialId) ?: return null

        val plain = try {
            decryptor.decrypt(EncryptedBlob(ciphertext = entity.ciphertext, iv = entity.iv))
        } catch (e: Exception) {
            // ⚠️ 只记异常**类型**，不打 message —— CryptoManager 的异常
            //    message 里可能带 blob 片段，而这是"我们把它写进日志"的
            //    最后一道闸。日志会被导出、会被用户贴到 issue 里。
            Timber.w(e, "凭据解密失败（type=%s）", e::class.simpleName)
            throw CredentialDecryptException(e)
        }

        return ResolvedCredential(
            apiKey = plain,
            // ★ 从凭据上取 providerId —— 模型配置上没有这个字段。
            //   这就是"网关必须等解密后才知道用哪家 Provider"的原因。
            providerId = entity.providerId,
            baseUrlOverride = entity.baseUrlOverride,
        )
    }
}
