package com.pocketagent.modelrouter

/**
 * 一条模型配置的**声明侧来源**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么要有这个接口，而不是直接吃数据库实体
 * ═══════════════════════════════════════════════════════════════
 *
 * 数据库里的 `ModelConfigEntity` 长着 `roleMask: Int`、`tier: ModelTierEntity`、
 * `modelId: String` 这些字段，而调度层要的是 `roles: Set<ModelRole>`、
 * `tier: ModelTier`、还有**从 Provider 声明里查出来的** `modelDisplayName` 与价格。
 *
 * 最直觉的做法是让 `core:database` 提供实体、由本模块转换 —— 但那要求
 * `modelrouter` 依赖 `core:database`，而 `core:database` 又依赖 Room/SQLCipher，
 * **本模块就再也进不了离线验证器了**（见 `tools/verify/run_logic_tests.py`
 * 的模块准入规则：零 `android.*` 依赖）。
 *
 * 于是改成：本模块只声明它**需要什么**，由调用方（仓储层）按需提供。
 * 这是端口/适配器模式 —— 但与"为了潮而分层"不同的是，
 * 这次分层有**可验证的收益**：下面这个转换函数被 30+ 个单测离线钉住，
 * 而它恰好是"配错了不报错、只是任务跑不动"的高发区。
 */
interface ModelConfigSource {

    /** 唯一标识，对应数据库 `model_config.id` */
    val id: String

    /** 用哪个凭据访问（`credential.id`）；本模块不解析它，只当不透明标识传下去 */
    val credentialId: String

    /** 模型 id，如 `"deepseek-chat"`。**这是发给 Provider 的那个字符串** */
    val modelId: String

    /** 用户起的名；空则界面回退到展示名 */
    val label: String?

    /** 档位 */
    val tier: ModelTier

    /** 角色集合。空集 = 这个模型既不调度也不干活，等价于禁用 */
    val roles: Set<ModelRole>

    /**
     * 用户覆盖的输入价格（美元/百万 token）；null 表示"我没填，用声明的"。
     *
     * ⚠️ **null 与 0.0 含义完全不同**：null = 未知，0.0 = 免费（本地模型）。
     *    合并这两者的后果见 [ModelConfig.estimatedCost] 的注释。
     */
    val inputPriceOverride: Double?

    /** 用户覆盖的输出价格；null 同上 */
    val outputPriceOverride: Double?

    /** 是否启用 */
    val enabled: Boolean
}

/**
 * Provider 侧对一个模型的声明（`provider:api` 的 `ModelInfo` 里本模块要用的部分）。
 *
 * ⚠️ 刻意**不直接引用 `ModelInfo`** —— 那会让本模块依赖 `provider:api`。
 *    虽然 `provider:api` 也是零 Android 依赖（技术上能依赖），但
 *    调度层需要的东西只有三个字段，为它引一个模块不划算，
 *    而且会把"能力声明"（`capabilities` / `contextWindow`）这些
 *    **与调度无关**的概念漏进本模块的类型里。
 *
 *    [ModelConfigSource] 的适配器由调用方写，那里顺带做字段选取。
 */
data class DeclaredModel(
    /** 展示名。Provider 常给的是人话（"DeepSeek Chat"），比 modelId 好看 */
    val displayName: String,

    /** 声明的输入价格；null = Provider 没给 */
    val inputPricePerMillion: Double? = null,

    /** 声明的输出价格；null = Provider 没给 */
    val outputPricePerMillion: Double? = null,
)

/**
 * 把"用户配置 + Provider 声明"合成一条调度层能用的 [ModelConfig]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 价格的回落规则（这是本函数存在的首要理由）
 * ═══════════════════════════════════════════════════════════════
 *
 * 三级回落，顺序不能乱：
 *
 * ```
 *   用户覆盖值（非 null） → Provider 声明值（非 null） → null
 * ```
 *
 * ⚠️ **最后一级必须是 null 而不是 0.0。**
 *
 *    把"不知道多少钱"当成"免费"的后果是：预算熔断（`AgentBudget`）
 *    算出 0 成本 → 永远不触发 → 用户以为自己在省钱，实际在烧钱。
 *    而这恰恰是他**自己没法发现**的那种故障 —— 界面全程正常。
 *
 * ⚠️ **用户覆盖优先于声明**，理由见 `ModelConfigEntity` 的类注释：
 *    同一个模型在不同渠道价差可达十倍，而"哪个便宜"正是调度的核心依据，
 *    用户比我们清楚他买的是哪一档。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 展示名的回落规则
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   Provider 声明的人话  →  modelId（裸 id 至少能看）
 * ```
 *
 * ⚠️ **不回落成 `source.label`** —— `ModelConfig.label` 是"用户起的名"，
 *    它是独立的一层（`ModelConfig.effectiveLabel` 里生效）。
 *    这里若拿 label 兜底，会出现"用户把 label 清空后展示名变成旧 label"的怪事。
 *
 *    两者的分工：
 *    - 本函数的 [ModelConfig.modelDisplayName] = **模型自己叫什么**
 *    - [ModelConfig.label] = **用户叫它什么**
 *    - [ModelConfig.effectiveLabel] = 用户叫的三者取一，最后回落到"模型自己叫什么"
 *
 * @param source 用户配置（数据库侧）
 * @param declared Provider 的声明；**为 null 表示"Provider 列表里没有这个模型"**。
 *        这完全可能发生：用户配完之后厂商下架了模型，或者他手填了一个自定义端点
 *        的模型名。此时**不报错**，价格回落为 null，展示名回落为 modelId。
 */
fun ModelConfigSource.toModelConfig(declared: DeclaredModel? = null): ModelConfig {
    val declaredInput = declared?.inputPricePerMillion
    val declaredOutput = declared?.outputPricePerMillion

    return ModelConfig(
        id = id,
        // ⚠️ 空串归一成空串（而非 null）：ModelConfig.label 是 String 非空类型，
        //    而它的 effectiveLabel 用 isBlank 判断回退。
        //    所以这里**不做** trim 以外的加工 —— 用户存了什么就是什么。
        label = label.orEmpty(),
        credentialId = credentialId,
        modelId = modelId,
        modelDisplayName = declared?.displayName?.takeIf { it.isNotBlank() } ?: modelId,
        tier = tier,
        // 用户填了就用用户的；没填用声明的；都没有就是 null（未知）
        inputPricePerMillion = inputPriceOverride ?: declaredInput,
        outputPricePerMillion = outputPriceOverride ?: declaredOutput,
        roles = roles,
        enabled = enabled,
    )
}

/**
 * 批量转换，并为每条配置配上它的 Provider 声明。
 *
 * @param declaredById `modelId` → 声明。查不到就按 null 走（见 [toModelConfig] 的注释）。
 *
 * ⚠️ **保留原列表顺序**。调用方（尤其界面）常常依赖"用户添加的顺序"来排列，
 *    而这里若用 `associateBy` 再 `values` 就会丢掉顺序 —— 那属于
 *    "不报错、只是安静地把用户的东西排乱了"。
 */
fun Iterable<ModelConfigSource>.toModelConfigs(
    declaredById: Map<String, DeclaredModel> = emptyMap(),
): List<ModelConfig> = map { it.toModelConfig(declaredById[it.modelId]) }
