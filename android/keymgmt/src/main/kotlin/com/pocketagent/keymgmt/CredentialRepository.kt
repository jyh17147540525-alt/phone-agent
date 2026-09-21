package com.pocketagent.keymgmt

import androidx.room.withTransaction
import com.pocketagent.core.crypto.CryptoManager
import com.pocketagent.core.crypto.EncryptedBlob
import com.pocketagent.core.database.PocketAgentDatabase
import com.pocketagent.core.database.entity.CredentialCheckStatus
import com.pocketagent.core.database.entity.CredentialEntity
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.ProviderException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * API 凭据的加密存储与校验。
 *
 * ═══════════════════════════════════════════════════════════════
 *  明文的暴露窗口被压缩到一条路径
 * ═══════════════════════════════════════════════════════════════
 *
 * 全应用能拿到 Key 明文的地方只有两处，且都在本文件内：
 * [validate] 与 [useDefaultKey]。两者都用
 * [CryptoManager.withDecryptedKey]，作用域结束立即清零。
 *
 * ⚠️ **一处无法消除的明文暴露**：用户在输入框里粘贴 Key 时，
 *    Compose 的 `TextField` 只能用 `String` 承载。String 进 JVM 字符串池后
 *    **无法擦除** —— 这是本设计唯一无法消除的暴露点，只能在输入完成后
 *    尽快转成 ByteArray 并让那个 String 尽快失去引用。
 *
 *    把这条写在这里而不是假装不存在，是因为下一个人读到"Key 永不落明文"
 *    时会以为全链路都是 ByteArray。**它只有落盘之后才是。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么"至多一个默认凭据"由本层守
 * ═══════════════════════════════════════════════════════════════
 *
 * SQLite 表达不了"某列至多有一行为 1"这种约束（部分唯一索引在 Room 里
 * 不生成）。所以这条不变量只能靠"改默认值"这个动作**在事务里成对执行**
 * （先全清、再置一）来保证。见 [setDefault] 与 [remove]。
 */
class CredentialRepository(
    private val db: PocketAgentDatabase,
    private val crypto: CryptoManager,
    private val providers: List<LlmProvider>,
    /** 时间源。抽出来是为了单测能固定时间，不必依赖真实时钟 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val dao = db.credentialDao()

    /** 界面订阅这个列表。按添加顺序，不会因为校验结果变化而重排 */
    val credentials: Flow<List<StoredCredential>> = dao.observeAll().map { rows ->
        rows.map { it.toDomain(displayNameFor(it.providerId)) }
    }

    /** 可选的服务商。界面用来渲染选择列表 */
    val availableProviders: List<LlmProvider> get() = providers

    // ─────────────────────────────────────────────────────────────
    //  增删改
    // ─────────────────────────────────────────────────────────────

    /**
     * 添加一条凭据。
     *
     * ⚠️ **不在这里做网络校验**，见 [AddCredentialResult] 的注释：
     *    网络不通时若拒绝保存，用户会在"网络不好"的日子里完全没法添加 Key。
     *    校验是 [validate] 的独立职责，结果单独反馈。
     */
    suspend fun add(
        providerId: String,
        rawKey: String,
        label: String = "",
        baseUrlOverride: String? = null,
    ): AddCredentialResult {
        val provider = providers.firstOrNull { it.id == providerId }
            ?: return AddCredentialResult.Rejected("不认识的服务商「$providerId」。")

        CredentialInput.validateKey(rawKey)?.let { return AddCredentialResult.Rejected(it) }
        CredentialInput.validateBaseUrl(baseUrlOverride)?.let {
            return AddCredentialResult.Rejected(it)
        }

        val trimmedKey = rawKey.trim()
        val keyBytes = trimmedKey.encodeToByteArray()

        val blob = try {
            crypto.encrypt(keyBytes)
        } catch (e: Exception) {
            return AddCredentialResult.Failed(
                "加密失败：${e.message ?: e::class.simpleName}。这通常意味着系统密钥库不可用。"
            )
        } finally {
            // 无论加密成功与否，这份明文副本都不再需要
            keyBytes.fill(0)
        }

        val base = CredentialEntity(
            id = UUID.randomUUID().toString(),
            providerId = provider.id,
            label = label.trim(),
            ciphertext = blob.ciphertext,
            iv = blob.iv,
            keyLength = trimmedKey.length,
            baseUrlOverride = baseUrlOverride?.trim()?.takeIf { it.isNotEmpty() },
            createdAtMillis = clock(),
            lastCheckedAtMillis = null,
            lastStatus = null,
            lastStatusDetail = null,
            modelCount = null,
            isDefault = false,
        )

        val saved = try {
            db.withTransaction {
                // 第一条自动成为默认 —— 否则用户加完 Key 却发不出请求，
                // 而界面上没有任何提示告诉他"还差一步设置默认"
                val shouldBeDefault = dao.count() == 0
                val entity = base.copy(isDefault = shouldBeDefault)
                dao.upsert(entity)
                entity
            }
        } catch (e: Exception) {
            return AddCredentialResult.Failed(
                "保存失败：${e.message ?: e::class.simpleName}。请检查存储空间是否充足。"
            )
        }

        return AddCredentialResult.Added(saved.toDomain(provider.displayName))
    }

    /**
     * 删除一条凭据。
     *
     * ⚠️ 若删掉的正好是默认项，**必须**把默认标记转移到剩下的第一条。
     *    否则会留下"有 Key、但没有默认 Key"的状态：用户每次发请求都失败，
     *    而列表里明明躺着好几个 Key —— 这种不一致比直接报错更难排查。
     */
    suspend fun remove(id: String) {
        db.withTransaction {
            val wasDefault = dao.byId(id)?.isDefault == true
            dao.deleteById(id)
            if (wasDefault) {
                dao.all().firstOrNull()?.let { dao.markDefault(it.id) }
            }
        }
    }

    /** 设为当前使用。先全清再置一，两步必须在同一事务里 */
    suspend fun setDefault(id: String) {
        db.withTransaction {
            dao.clearDefaultFlag()
            dao.markDefault(id)
        }
    }

    suspend fun rename(id: String, label: String) {
        dao.rename(id, label.trim())
    }

    /**
     * 清空全部凭据。
     *
     * ⚠️ 只删数据库里的记录，**不动 Keystore 主密钥** —— 那件事归
     *    `CryptoManager.destroyMasterKey()` 管，属于「一键清除所有数据」。
     *    把两者绑在一起是危险的：只想删掉一个 Key 的人不该顺带毁掉
     *    整个加密存储。
     */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    // ─────────────────────────────────────────────────────────────
    //  校验
    // ─────────────────────────────────────────────────────────────

    /**
     * 校验一条凭据是否可用，并把结论落库。
     *
     * 结论会写进数据库，所以**下次打开应用还能看到上次的结果** ——
     * 否则用户每次进来都要重新点一遍"校验"，而"上次到底怎么了"
     * 这个信息就永远丢失了。
     */
    suspend fun validate(id: String): ValidationOutcome {
        val entity = dao.byId(id)
            ?: return ValidationOutcome(
                status = CredentialCheckStatus.UNCHECKED,
                detail = "这条凭据已经不在了，可能刚被删除。",
            )

        val provider = providers.firstOrNull { it.id == entity.providerId }
            ?: return ValidationOutcome(
                status = CredentialCheckStatus.UNCHECKED,
                detail = "本版本不认识服务商「${entity.providerId}」，无法校验。",
            )

        val outcome = crypto.withDecryptedKey(
            EncryptedBlob(ciphertext = entity.ciphertext, iv = entity.iv)
        ) { plain ->
            val credential = ProviderCredential(plain, entity.baseUrlOverride)
            try {
                provider.validateKey(credential).toOutcome()
            } catch (e: ProviderException) {
                // Provider 的契约允许抛异常（NetworkError / Timeout 等）。
                // 必须在这里兜住 —— 让一个 Provider 实现的疏漏冒到界面上，
                // 用户会看到一句他看不懂的堆栈，而不是"网络不通"。
                e.toOutcome()
            } catch (e: Exception) {
                ValidationOutcome(
                    status = CredentialCheckStatus.UNREACHABLE,
                    detail = "校验过程中出错了：${e.message ?: e::class.simpleName}。" +
                        "这不代表 Key 有问题，稍后重试。",
                )
            } finally {
                credential.clear()
            }
        }

        // 落库失败不该让"校验成功"这件事看起来失败了 ——
        // 结论本身已经拿到，用户要的是这个
        runCatching {
            dao.updateCheckResult(
                id = id,
                checkedAtMillis = clock(),
                status = outcome.status,
                detail = outcome.detail,
                modelCount = outcome.modelCount,
            )
        }

        return outcome
    }

    // ─────────────────────────────────────────────────────────────
    //  取用
    // ─────────────────────────────────────────────────────────────

    /** 当前使用的凭据（不含明文） */
    suspend fun defaultCredential(): StoredCredential? {
        val entity = dao.defaultCredential() ?: return null
        return entity.toDomain(displayNameFor(entity.providerId))
    }

    /**
     * 在受控作用域内用默认凭据发请求。
     *
     * 这是**唯一**推荐的取用方式 —— 不要在任何地方长期持有解密后的字节。
     * 作用域结束后（含异常路径）明文立即清零。
     *
     * @return null 表示还没有可用的默认凭据。调用方应引导用户去配置，
     *         而不是抛异常 —— "还没配 Key"是正常状态，不是错误。
     */
    suspend fun <T> useDefaultKey(
        block: suspend (provider: LlmProvider, credential: ProviderCredential) -> T,
    ): T? {
        val entity = dao.defaultCredential() ?: return null
        val provider = providers.firstOrNull { it.id == entity.providerId } ?: return null

        return crypto.withDecryptedKey(
            EncryptedBlob(ciphertext = entity.ciphertext, iv = entity.iv)
        ) { plain ->
            val credential = ProviderCredential(plain, entity.baseUrlOverride)
            try {
                block(provider, credential)
            } finally {
                credential.clear()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * Provider 展示名。查不到时**退回 id 本身**而不是空白或"未知" ——
     * 用户看到 `my-custom-endpoint` 能立刻认出是自己配的，
     * 看到"未知服务商"只会困惑。
     */
    private fun displayNameFor(providerId: String): String =
        providers.firstOrNull { it.id == providerId }?.displayName ?: providerId
}
