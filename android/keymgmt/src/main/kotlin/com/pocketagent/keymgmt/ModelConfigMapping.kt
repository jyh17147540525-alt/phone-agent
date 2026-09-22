package com.pocketagent.keymgmt

import com.pocketagent.core.database.entity.ModelConfigEntity
import com.pocketagent.core.database.entity.ModelRoleEntity
import com.pocketagent.core.database.entity.ModelTierEntity
import com.pocketagent.modelrouter.DeclaredModel
import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelConfigSource
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.modelrouter.toModelConfig

/**
 * 数据库实体 → 调度层模型的映射。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么这个文件在 `keymgmt` 而不是 `modelrouter`
 * ═══════════════════════════════════════════════════════════════
 *
 * 它 `import` 了 `core:database` 的实体。若放在 `modelrouter`，
 * 那个纯 Kotlin 模块就得依赖 Room —— 于是它**再也进不了离线验证器**
 * （`run_logic_tests.py` 要求零 `android.*` 依赖）。
 *
 * 而离线验证器恰好是这个模块最重要的资产：路由算法 19+18+27 个用例
 * 能在毫秒级跑完，全部因为它是纯的。
 *
 * 所以：**映射放在能碰 Room 的一侧，算法留在纯的一侧。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个文件里三个"错了不报错"的地方
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **档位枚举转换**：`ModelTierEntity` 与 `ModelTier` 是两个枚举，
 *    取值必须一一对应。对不上的表现形式是 `when` 抛
 *    `NoSuchElementException`（如果用了 `valueOf`），或者更糟 ——
 *    静默落到某个 `else` 分支，于是所有任务都走同一个档位。
 * 2. **角色 bit mask → Set**：漏掉一位会让"调度者"模型变成"执行者"，
 *    而那是个**权限方向的变化**（该只判断难度的模型开始接任务了）。
 * 3. **价格 null 的传递**：`null` 必须原样传下去，绝不能变成 `0.0`。
 *    理由见 [ModelConfigMapping.toModelConfig] 的长注释 ——
 *    那会让预算熔断静默失效。
 *
 * 前两条在这里就地转换（不跨模块），第三条复用 `modelrouter` 里
 * 已经被 16 个用例钉住的 `toModelConfig`。
 */

/**
 * 档位转换。
 *
 * ⚠️ 用**穷举 `when`** 而不是 `ModelTier.valueOf(entity.name)`。
 *
 *    `valueOf` 的问题是：将来某个枚举加了取值（比如 `ModelTier` 加了
 *    `ULTRA`），另一个没加 —— `valueOf` 会在**运行时**抛异常，
 *    而穷举 `when` 会在**编译时**就报错，逼着改的人同时想清楚两边。
 *
 *    Kotlin 对 `enum` 的 `when` 做了穷尽性检查，这正是要利用的东西。
 */
internal fun ModelTierEntity.toTier(): ModelTier = when (this) {
    ModelTierEntity.LIGHT -> ModelTier.LIGHT
    ModelTierEntity.STANDARD -> ModelTier.STANDARD
    ModelTierEntity.HEAVY -> ModelTier.HEAVY
}

/**
 * 角色转换。
 *
 * ⚠️ 空集是合法输入，且**语义重要**：它表示"这个模型启用了但没指定用途"。
 *
 *    这种配置在调度时会被静默跳过（既不是 worker 也不是 scheduler），
 *    而用户在界面上看到的是"已启用" —— 典型的"看起来配好了、实际不工作"。
 *
 *    `modelrouter` 的 `ModelSetupSummary.noRole` 专门为它留了一个字段，
 *    所以这里**必须**如实返回空集，不能"兜底成 WORKER"：
 *    兜底会让那个提示永远不出现，用户永远不知道自己的配置是半截的。
 */
internal fun ModelRoleEntity.Companion.fromEntityMask(mask: Int): Set<ModelRole> =
    fromMask(mask).mapTo(mutableSetOf()) { it.toRole() }

internal fun ModelRoleEntity.toRole(): ModelRole = when (this) {
    ModelRoleEntity.SCHEDULER -> ModelRole.SCHEDULER
    ModelRoleEntity.WORKER -> ModelRole.WORKER
}

/**
 * 把一条数据库记录适配成 `modelrouter` 的端口类型，再合成调度层模型。
 *
 * @param declared Provider 声明（展示名 + 价格）。**为 null 是正常情况** ——
 *        用户可能配了自建端点的模型名，或厂商下架了该模型。此时展示名回落到
 *        `modelId`，价格回落到 null（未知）—— 两者都不是错误。
 *
 *        见 `ModelConfigMapping.toModelConfig` 的注释：那里已经把这套回落规则
 *        用 16 个用例钉住了，本函数**不重复实现**，只负责把数据喂进去。
 *
 * ⚠️ **适配器写成具名类，而不是匿名 object。**
 *
 *    匿名 `object : ModelConfigSource { ... }.toModelConfig(declared)` 会
 *    **自己解析到自己**：本文件也声明了一个 `ModelConfigEntity.toModelConfig`，
 *    而对 `ModelConfigSource` 求值的那个 `toModelConfig` 扩展恰好同名 ——
 *    Kotlin 在匿名对象上没有可用的 receiver 时会把候选集搞混，
 *    实测报 `Unresolved reference 'toModelConfig'`（且**只在跨模块时出现**，
 *    同模块内看不出来）。具名类 + 显式接收者让解析无歧义。
 */
internal fun ModelConfigEntity.toModelConfig(declared: DeclaredModel? = null): ModelConfig =
    ModelConfigSourceAdapter(this).toModelConfig(declared)

/** 见 [ModelConfigEntity.toModelConfig] 的注释：具名是为了让扩展解析无歧义 */
internal class ModelConfigSourceAdapter(
    private val entity: ModelConfigEntity,
) : ModelConfigSource {
    override val id: String get() = entity.id
    override val credentialId: String get() = entity.credentialId
    override val modelId: String get() = entity.modelId
    override val label: String? get() = entity.label
    override val tier: ModelTier get() = entity.tier.toTier()
    override val roles: Set<ModelRole> get() = ModelRoleEntity.fromEntityMask(entity.roleMask)
    override val inputPriceOverride: Double? get() = entity.inputPriceOverride
    override val outputPriceOverride: Double? get() = entity.outputPriceOverride
    override val enabled: Boolean get() = entity.enabled
}

/**
 * 批量转换。**保留输入顺序**（数据库已按 `createdAtMillis` 排好）。
 *
 * @param declaredById `modelId` → 声明。缺项按 null 走。
 */
internal fun Iterable<ModelConfigEntity>.toModelConfigs(
    declaredById: Map<String, DeclaredModel> = emptyMap(),
): List<ModelConfig> = map { entity ->
    ModelConfigSourceAdapter(entity).toModelConfig(declaredById[entity.modelId])
}

/**
 * 从 Provider 的模型列表里挑出 `DeclaredModel`。
 *
 * 单独抽出来是因为这个转换有个**容易搞错的方向**：它是"取字段"，
 * 不是"过滤"。Provider 声明几十个模型，用户只配了三个 ——
 * 但这里**不该**按用户配置去筛，而应该把全部声明都编进 map，
 * 由查询方按键取。理由：用户可能配了一个 Provider 没声明的模型名
 * （自建端点），那时"筛不出来"和"查不到"是同一件事，
 * 混在一起会让排查时分不清是配置问题还是 Provider 问题。
 */
internal fun Iterable<DeclaredModelSource>.toDeclaredMap(): Map<String, DeclaredModel> =
    associate { it.modelId to DeclaredModel(it.displayName, it.inputPrice, it.outputPrice) }

/**
 * 声明侧的输入端口。
 *
 * ⚠️ 刻意不直接吃 `provider:api` 的 `ModelInfo` ——
 *    `keymgmt` 现在依赖它（为了 `LlmProvider`），但把这个映射绑死在
 *    `ModelInfo` 上会让它无法在测试里构造。测试要能只给三个字段。
 */
internal interface DeclaredModelSource {
    val modelId: String
    val displayName: String
    val inputPrice: Double?
    val outputPrice: Double?
}
