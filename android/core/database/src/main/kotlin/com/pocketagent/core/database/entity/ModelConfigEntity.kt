package com.pocketagent.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条用户配置的模型。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与 `provider:api` 的 `ModelInfo` 是什么关系
 * ═══════════════════════════════════════════════════════════════
 *
 * **不是同一个东西，也不该合并**：
 *
 * | | `ModelInfo` | 本表 |
 * |---|---|---|
 * | 谁提供 | Provider 声明 | 用户配置 |
 * | 含义 | "这个接口有哪些模型" | "我要用哪些模型、按什么档位用" |
 * | 数量 | 一个 Provider 几十个 | 用户挑出来的几个 |
 * | 存哪 | 内存（接口返回） | 数据库 |
 *
 * 合并的后果是：用户一换 Key，Provider 返回的模型列表变了，
 * 他辛苦配好的档位全没了。所以本表**只引用** `ModelInfo.id`，
 * 不复制它的内容。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 价格为什么冗余存一份
 * ═══════════════════════════════════════════════════════════════
 *
 * [inputPriceOverride] / [outputPriceOverride] 是**用户可覆盖的**，
 * 不是从 `ModelInfo` 拷来的副本。
 *
 * 理由（三个都真实存在）：
 * 1. **Provider 声明可能缺价格**（自建端点、新模型），`ModelInfo` 里是 null
 * 2. **声明可能过时**：厂商调价后我们不一定同步更新
 * 3. ⚠️ **同一个模型在不同渠道价格差很多**（官方 vs 中转），
 *    而"哪个便宜"恰恰是调度的核心依据 —— 用户比我们清楚他买的是哪一档
 *
 * 所以：为 null 时回落到 `ModelInfo` 的声明值，非 null 时以用户填的为准。
 * **不要把这理解成"冗余"而删掉它。**
 */
@Entity(
    tableName = "model_config",
    indices = [
        // 按凭据筛模型（"这个 Key 能用哪些模型"）
        Index("credentialId"),
        // 调度时按档位+角色筛，这是最高频查询
        Index("tier"),
        Index("roleMask"),
        // 同一凭据下不允许重复配置同一个模型
        Index(value = ["credentialId", "modelId"], unique = true),
    ],
)
data class ModelConfigEntity(

    /** UUID。不用自增 —— 理由同 `CredentialEntity` */
    @PrimaryKey val id: String,

    /**
     * 用哪个凭据访问这个模型。
     *
     * 对应 `credential.id`。⚠️ **刻意不声明 Room 外键**：
     * Room 的外键要求开 `PRAGMA foreign_keys=ON`，而 SQLCipher 下这个
     * pragma 要在每次开连接时设置；漏设的表现是**约束静默失效** ——
     * 比没有外键更糟（你以为有保护）。所以引用完整性由仓储层在事务里守：
     * 删除凭据时同步删除其下的模型配置。
     */
    val credentialId: String,

    /** 对应 `ModelInfo.id`，如 "deepseek-chat" / "gpt-4o-mini" */
    val modelId: String,

    /** 用户给这个配置起的名字；为空则界面显示 `modelId` */
    val label: String?,

    /** 档位（轻量/均衡/重型）。并发派发的依据 */
    val tier: ModelTierEntity,

    /**
     * 角色：调度者还是执行者。
     *
     * ⚠️ 刻意做成**可以是多个**（一个模型可同时是 SCHEDULER 和 WORKER）。
     *    用户常见做法是"就用一个模型干所有事"，那不该被数据结构拦住。
     *    这里存 bit mask 而非枚举，就是为了表达"同时是"。
     */
    val roleMask: Int,

    /** 用户覆盖的输入价格（美元/百万 token）；null 表示用 Provider 声明值 */
    val inputPriceOverride: Double?,

    /** 用户覆盖的输出价格；null 同上 */
    val outputPriceOverride: Double?,

    /** 是否启用。禁用的模型不参与调度但保留配置 */
    val enabled: Boolean,

    val createdAtMillis: Long,
) {

    /** 解析出的角色集合 */
    val roles: Set<ModelRoleEntity>
        get() = ModelRoleEntity.fromMask(roleMask)

    fun hasRole(role: ModelRoleEntity): Boolean = ModelRoleEntity.hasRole(roleMask, role)

    /**
     * 是否可用于实际发起请求。
     *
     * 至少要有 WORKER 角色 —— 只有 SCHEDULER 的模型不接任务。
     */
    val canExecuteTasks: Boolean get() = enabled && hasRole(ModelRoleEntity.WORKER)

    override fun toString(): String =
        "ModelConfigEntity(id=$id, credential=$credentialId, model=$modelId, " +
            "tier=$tier, roles=$roles, enabled=$enabled)"
}

/**
 * 模型档位。
 *
 * ⚠️ 与 `modelrouter` 模块的 `ModelTier` **是同构的，且必须保持同构**。
 *
 *    不直接复用的原因是分层：`core:database` 不该依赖 `modelrouter`
 *    （反过来的依赖方向才对 —— 仓储读出来交给调度器）。
 *    转换在本表的映射函数里做，有测试钉住两边的取值一致。
 *
 *    如果哪天要加档位，**两边一起改**，否则会出现"库里存了调度器不认识的档"。
 */
enum class ModelTierEntity {
    /** 轻量：简单、短、不需要推理的任务 */
    LIGHT,

    /** 均衡：默认档 */
    STANDARD,

    /** 重型：多步、需要推理与工具调用的任务 */
    HEAVY,
    ;

    val displayName: String
        get() = when (this) {
            LIGHT -> "轻量"
            STANDARD -> "均衡"
            HEAVY -> "重型"
        }
}

/**
 * 模型在调度中承担的角色。
 *
 * 用 bit mask 存储而非单值枚举，这样**一个模型可以同时是两种角色**。
 * 用户常见配置是"只有一个模型"——它既是调度者也是执行者，
 * 数据结构必须能表达这件事，否则用户被迫配两条一模一样的记录。
 */
enum class ModelRoleEntity(val bit: Int) {
    /**
     * 调度者：判断任务难度并挑选执行模型。
     *
     * ⚠️ 本项目**不要求**用户配置调度模型（见设计文档 D-AK）——
     *    难度判断走本地启发式，零成本零延迟。
     *    这个角色保留给"想用便宜模型做二次确认"的进阶用户。
     */
    SCHEDULER(1),

    /** 执行者：实际接受任务并跑 agent 循环 */
    WORKER(2),
    ;

    companion object {
        fun fromMask(mask: Int): Set<ModelRoleEntity> =
            values().filter { (mask and it.bit) != 0 }.toSet()

        fun toMask(roles: Set<ModelRoleEntity>): Int =
            roles.fold(0) { acc, role -> acc or role.bit }

        fun hasRole(mask: Int, role: ModelRoleEntity): Boolean = (mask and role.bit) != 0
    }
}
