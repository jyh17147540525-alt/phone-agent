package com.pocketagent.modelrouter

/**
 * 模型能力档位。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么是三档而不是连续分数
 * ═══════════════════════════════════════════════════════════════
 *
 * 连续分数（如"能力值 0.73"）看起来更精细，但有两个问题：
 *
 * 1. **用户看不懂。** 用户能判断"这个模型便宜好用"，判断不了 0.73 和 0.68 的区别。
 * 2. **决策不可解释。** 当 agent 选错模型时，"因为难度分 0.61 落在 [0.5,0.8) 区间"
 *    是一句没人能验证的话；"这个任务涉及 5 个 App，判为 HEAVY" 是可以逐条讨论的。
 *
 * 所以刻意只有三档。档位越少，调度逻辑越容易被审查和修正。
 *
 * ⚠️ 顺序有意义：`LIGHT < STANDARD < HEAVY`。降级链依赖这个顺序
 *    （例如"用户没配 HEAVY，就用已配置的最高档"）。
 */
enum class ModelTier(val rank: Int, val displayName: String, val description: String) {
    LIGHT(
        rank = 0,
        displayName = "轻量",
        description = "便宜、快。适合简单问答、字段提取、格式转换",
    ),
    STANDARD(
        rank = 1,
        displayName = "均衡",
        description = "能力与价格平衡。适合需要多步操作或判断的任务",
    ),
    HEAVY(
        rank = 2,
        displayName = "重型",
        description = "贵、慢、能力强。适合复杂规划、长上下文、需要推理",
    );

    companion object {
        /**
         * 取不低于 [tier] 的最低可用档位。
         *
         * 用途：用户配了 HEAVY 而任务判为 STANDARD 时，不必强上 HEAVY ——
         * 但如果用户**只**配了 HEAVY（比如只买得起最好的那一个），
         * 那简单任务也只能用它。这个函数是"没得选时的最近选择"。
         */
        fun atLeast(tier: ModelTier, available: Collection<ModelTier>): ModelTier? =
            available.filter { it.rank >= tier.rank }.minByOrNull { it.rank }

        /**
         * 取不高于 [tier] 的最高可用档位。
         *
         * 用途：任务判为 HEAVY 但用户没配 HEAVY 时的降级 ——
         * 用他能给出的最好的那个。
         */
        fun atMost(tier: ModelTier, available: Collection<ModelTier>): ModelTier? =
            available.filter { it.rank <= tier.rank }.maxByOrNull { it.rank }
    }
}

/**
 * 模型在调度体系里承担的角色。
 *
 * 一个模型可以同时有多个角色（比如既当调度者又当 WORKER —— 虽然少见但合法）。
 */
enum class ModelRole {
    /** 可被选为调度者：只负责判断任务难度，不做实际工作 */
    SCHEDULER,

    /** 可被调度去执行任务 */
    WORKER,
}

/**
 * 一条模型配置（调度层的视角）。
 *
 * ⚠️ 这里**没有 Key 明文，也没有 credentialId 之外的凭据信息**。
 *    调度层只决定"用哪个模型"，不接触凭据 —— 拿 Key 是 LlmGateway 的事，
 *    两者职责必须分开。这样调度逻辑可以完全离线测试。
 */
data class ModelConfig(
    /** 唯一标识，对应数据库 model_config.id */
    val id: String,

    /** 用户起的名字。空则界面退化为 [modelDisplayName] */
    val label: String,

    /** 指向 credential.id —— 仅作为不透明标识传递，本模块不解析它 */
    val credentialId: String,

    /** 模型 id，如 "deepseek-chat" */
    val modelId: String,

    /** 展示名 */
    val modelDisplayName: String,

    val tier: ModelTier,

    /** 每百万输入 token 价格（美元）。null = 未知（本地模型/自建端点） */
    val inputPricePerMillion: Double? = null,

    /** 每百万输出 token 价格（美元）。null = 未知 */
    val outputPricePerMillion: Double? = null,

    val roles: Set<ModelRole> = setOf(ModelRole.WORKER),

    val enabled: Boolean = true,
) {
    /** 界面上实际显示的名字 */
    val effectiveLabel: String get() = label.ifBlank { modelDisplayName }

    /** 是否可用于调度者的候选 */
    val canSchedule: Boolean get() = enabled && ModelRole.SCHEDULER in roles

    /** 是否可用于执行任务 */
    val canWork: Boolean get() = enabled && ModelRole.WORKER in roles

    /**
     * 单次调用的粗略成本（美元）。
     *
     * ⚠️ 用于**相对比较**，不是精确计费。缺价格信息时返回 null ——
     *    绝不返回 0。把"未知"当成"免费"会让预算熔断完全失效，
     *    用户会以为自己在省钱，实际在烧钱。
     */
    fun estimatedCost(inputTokens: Int, outputTokens: Int): Double? {
        if (inputPricePerMillion == null && outputPricePerMillion == null) return null
        val input = (inputPricePerMillion ?: 0.0) * inputTokens / 1_000_000.0
        val output = (outputPricePerMillion ?: 0.0) * outputTokens / 1_000_000.0
        return input + output
    }
}
