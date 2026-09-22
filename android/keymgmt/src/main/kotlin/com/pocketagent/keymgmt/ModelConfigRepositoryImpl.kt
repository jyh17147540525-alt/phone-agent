package com.pocketagent.keymgmt

import androidx.room.withTransaction
import com.pocketagent.core.database.PocketAgentDatabase
import com.pocketagent.core.database.entity.CredentialPurpose
import com.pocketagent.core.database.entity.ModelConfigEntity
import com.pocketagent.core.database.entity.ModelRoleEntity
import com.pocketagent.core.database.entity.ModelTierEntity
import com.pocketagent.modelrouter.DeclaredModel
import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelConfigRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 模型声明的来源。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不是"从 Provider 直接问"
 * ═══════════════════════════════════════════════════════════════
 *
 * 直觉做法是拿 `List<LlmProvider>` 然后调 `listModels()` —— 但那签名是：
 *
 * ```kotlin
 * suspend fun listModels(credential: ProviderCredential): List<ModelInfo>?
 * ```
 *
 * **它要求一个已解密的凭据，还要发网络请求。** 这两件事都不能出现在
 * 配置页的读取路径上：
 *
 * - 配置页打开时用户还没选 Key，解不了密
 * - 配置页必须在飞行模式下能打开 —— 它读的是"我配过什么"，不是"厂商有什么"
 *
 * 所以声明侧必须是**静态的、离线的**。内置的静态清单在
 * `provider:openai-compat` 的 `ProviderProfile.defaultModels` 里，
 * 但那是实现细节，不在 `LlmProvider` 契约上 —— 本模块看不到它。
 *
 * 于是改成注入一个 [ModelDeclarationSource]：谁有静态清单谁来提供，
 * 没有就返回空 map（界面退化为显示 `modelId`，价格显示"未知"）。
 *
 * ⚠️ 空 map **不是降级状态**，是完全可用的状态。价格未知时
 *    [ModelConfig.estimatedCost] 返回 null，预算熔断按"未知"处理 ——
 *    这比塞一个 0.0 安全得多（见 `ModelConfigMapping` 的长注释）。
 */
fun interface ModelDeclarationSource {

    /**
     * 取出全部已知的模型声明。
     *
     * 返回 `modelId` → 声明。**不要按用户的配置去筛** ——
     * 用户可能配了清单里没有的模型名（自建端点），那时"筛不出来"
     * 和"查不到"会变成同一件事，排查时分不清是配置问题还是清单问题。
     */
    fun declarations(): Map<String, DeclaredModel>
}

/** 什么都不知道的实现。默认值 —— 界面照样能用，只是展示名是裸 id */
val NoModelDeclarations: ModelDeclarationSource = ModelDeclarationSource { emptyMap() }

/**
 * 模型配置的持久化仓储。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它补的是哪一段缺口
 * ═══════════════════════════════════════════════════════════════
 *
 * 在它之前，`ModelConfigDao`（数据库读写）和 `ModelRouter`（路由算法）
 * 各自完备，但**中间没有人**：
 *
 * ```
 *   ModelConfigDao ──✂── (缺) ──✂── ModelRouter
 * ```
 *
 * 后果是"调度器写得很完整，但线上永远只有一个模型" ——
 * 因为真正要发请求的时候，没人去数据库读那个列表。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 展示名/价格从哪来
 * ═══════════════════════════════════════════════════════════════
 *
 * 数据库只存 `modelId`（如 `"deepseek-chat"`），**不存展示名和价格** ——
 * 那是静态清单里的声明，随版本更新。
 *
 * 所以本类持有一个 [ModelDeclarationSource]，在映射时按 `modelId` 查声明。
 * 查不到**不报错**：用户可能配了自建端点的模型名，或厂商下架了它。
 * 此时展示名回落到 `modelId`（裸 id 至少能认出来），价格回落到 null（未知）。
 * 为什么不能直接问 Provider 见 [ModelDeclarationSource] 的注释。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ Key 与模型的关系
 * ═══════════════════════════════════════════════════════════════
 *
 * `model_config.credentialId` 指向 `credential.id`，而**外键约束是靠本层
 * 在事务里守的**（Room 外键未启用，理由见 `ModelConfigEntity` 的注释）。
 * 具体就是 [deleteCredentialCascade] —— 删 Key 时连带删掉它下面的模型配置，
 * 否则会留下引用不存在凭据的孤儿记录：调度时选中它 →
 * 取 Key 时找不到 → 任务失败，而界面上那个模型看起来完全正常。
 */
class ModelConfigRepositoryImpl(
    private val db: PocketAgentDatabase,
    /** 静态模型清单。默认什么都不知道，界面照样可用 */
    private val declarations: ModelDeclarationSource = NoModelDeclarations,
    /** 时间源。抽出来为了单测能固定时间 */
    private val clock: () -> Long = System::currentTimeMillis,
) : ModelConfigRepository {

    private val dao = db.modelConfigDao()

    /**
     * 声明查找表，惰性构建。
     *
     * ⚠️ `by lazy` 而不是构造时求值：内置清单有 11 家 × 几十个模型，
     *    构造它要遍历一遍。而绝大多数会话根本不打开配置页 ——
     *    放在构造函数里等于**每次注入都白付这份开销**。
     *
     *    名单是静态的，缓存下来没有失效问题。
     */
    private val declaredById: Map<String, DeclaredModel> by lazy { declarations.declarations() }

    override val models: Flow<List<ModelConfig>> =
        dao.observeAll().map { rows -> rows.toModelConfigs(declaredById) }

    override suspend fun all(): List<ModelConfig> =
        dao.all().toModelConfigs(declaredById)

    override suspend fun byId(id: String): ModelConfig? =
        dao.byId(id)?.let { it.toModelConfig(declaredById[it.modelId]) }

    // ─────────────────────────────────────────────────────────────
    //  写入
    // ─────────────────────────────────────────────────────────────

    /**
     * 添加（或覆盖）一条模型配置。
     *
     * @param credentialId 必须指向一条**存在的**凭据，否则拒绝 ——
     *        见 [AddModelResult.Rejected]，理由同类注释里说的孤儿记录。
     *        这里不靠外键（没开），靠一次显式查询。
     * @param roles 空的角色集合会让这个模型"启用但不工作"，属于用户没填完。
     *        **刻意允许**（不拒绝）：用户可能先建条目、再去配用途。
     *        界面靠 `ModelSetupSummary.noRole` 提示他，而不是在这里拦。
     */
    suspend fun add(
        credentialId: String,
        modelId: String,
        label: String = "",
        tier: com.pocketagent.core.database.entity.ModelTierEntity,
        roles: Set<ModelRoleEntity>,
        inputPriceOverride: Double? = null,
        outputPriceOverride: Double? = null,
        enabled: Boolean = true,
    ): AddModelResult {
        val trimmedModelId = modelId.trim()
        if (trimmedModelId.isEmpty()) {
            return AddModelResult.Rejected("模型 id 不能为空。")
        }

        val credential = db.credentialDao().byId(credentialId)
            ?: return AddModelResult.Rejected("这条 Key 已经不存在了，请重新选择。")

        // ⚠️ TTS 的 Key 不能拿来访问模型接口。放在**添加时**拦住，
        //    而不是等发请求收到 401 之后再让用户排查 ——
        //    那个 Key 在语音场景明明好着，用户无法理解发生了什么。
        if (credential.purpose != CredentialPurpose.LLM) {
            return AddModelResult.Rejected(
                "「${credential.purpose.displayName}」类的 Key 不能用来调用模型接口。" +
                    "请选择一条模型接口的 Key。"
            )
        }

        // 同一凭据下不允许重复配置同一个模型（数据库有唯一索引，这里先给出人话）
        if (dao.countByCredentialAndModel(credentialId, trimmedModelId) > 0) {
            return AddModelResult.Rejected(
                "这条 Key 下已经配置过「${trimmedModelId}」了。"
            )
        }

        val entity = ModelConfigEntity(
            id = UUID.randomUUID().toString(),
            credentialId = credentialId,
            modelId = trimmedModelId,
            label = label.trim().takeIf { it.isNotEmpty() },
            tier = tier,
            roleMask = ModelRoleEntity.toMask(roles),
            inputPriceOverride = inputPriceOverride,
            outputPriceOverride = outputPriceOverride,
            enabled = enabled,
            createdAtMillis = clock(),
        )

        return try {
            dao.upsert(entity)
            AddModelResult.Added(entity.toModelConfig(declaredById[trimmedModelId]))
        } catch (e: Exception) {
            AddModelResult.Failed(
                "保存失败：${e.message ?: e::class.simpleName}。请检查存储空间是否充足。"
            )
        }
    }

    suspend fun remove(id: String) {
        dao.deleteById(id)
    }

    /** 只改启用状态。用 DAO 的定向 UPDATE 而非读改写 —— 避免覆盖并发修改 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
    }

    suspend fun setTier(id: String, tier: com.pocketagent.core.database.entity.ModelTierEntity) {
        dao.setTier(id, tier.name)
    }

    suspend fun setRoles(id: String, roles: Set<ModelRoleEntity>) {
        dao.setRoleMask(id, ModelRoleEntity.toMask(roles))
    }

    suspend fun rename(id: String, label: String) {
        val existing = dao.byId(id) ?: return
        dao.upsert(existing.copy(label = label.trim().takeIf { it.isNotEmpty() }))
    }

    // ─────────────────────────────────────────────────────────────
    //  引用完整性
    // ─────────────────────────────────────────────────────────────

    /**
     * 删除凭据时级联清理它下面的模型配置。
     *
     * ⚠️ **必须在同一事务里**。分两次做的话，中间态是"Key 没了但模型还在"，
     *    而这个窗口里如果恰好有任务在路由，它会选中一个取不到 Key 的模型 ——
     *    失败原因是"凭据不存在"，而用户刚刚明明只是删了一个 Key。
     *
     * ⚠️ 为什么不是 `CredentialRepository.remove` 自己做：那样两个仓储
     *    会互相依赖（Key 仓储要认识模型表）。实际的接法是**上层编排**：
     *    删 Key 的调用点先调本方法、再删 Key，两步都在这一个事务里。
     *    这是刻意的 —— 本类不认识 `CryptoManager`，不需要为级联而多一个依赖。
     */
    suspend fun deleteCredentialCascade(credentialId: String) {
        db.withTransaction {
            dao.deleteByCredential(credentialId)
        }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    /**
     * 某个凭据下配了哪些模型。用于 Key 管理页显示"这个 Key 有 N 个模型"，
     * 以及删除 Key 前的确认弹窗。
     */
    suspend fun modelsForCredential(credentialId: String): List<ModelConfig> =
        dao.byCredential(credentialId).toModelConfigs(declaredById)
}

/**
 * 添加模型配置的结果。
 *
 * 刻意与 `AddCredentialResult` 分开（不复用）：那两个东西的失败原因是
 * 不同类的 —— 凭据会因"网络校验失败"而失败，模型配置**没有网络环节**。
 * 共用一个类型会让人以为这里也可能因为网络而失败。
 */
sealed interface AddModelResult {
    data class Added(val model: ModelConfig) : AddModelResult

    /** 输入不合法或前置条件不满足（Key 不存在、Key 用途不对、重复配置） */
    data class Rejected(val reason: String) : AddModelResult

    /** 落库失败 */
    data class Failed(val reason: String) : AddModelResult
}
