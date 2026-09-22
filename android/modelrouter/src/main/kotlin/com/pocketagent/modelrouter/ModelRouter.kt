package com.pocketagent.modelrouter

/**
 * 路由模式。
 *
 * ⚠️ **默认永远是 [Single]**。
 *
 *    "多模型调度"是给愿意配置的用户准备的**优化项**，不是使用前提。
 *    只配一个模型的用户，从生到死都不该看到"调度"这两个字 ——
 *    那是给他增加认知负担，而收益是零（他只有一个模型可选）。
 */
sealed interface RoutingMode {

    /**
     * 单模型模式：所有任务走同一个模型。
     *
     * 这是默认值，也是零配置路径。没有调度开销、行为完全可预测。
     */
    data class Single(val modelConfigId: String) : RoutingMode

    /**
     * 调度模式：按任务难度派发。
     *
     * [tierModels] 是档位 → 模型配置 id 的映射。**允许缺档位** ——
     * 缺档位时的行为由 [ModelRouter] 的降级规则决定，不是报错。
     */
    data class Scheduled(
        val schedulerModelConfigId: String,
        val tierModels: Map<ModelTier, String>,
    ) : RoutingMode {
        /** 调度者不能同时是唯一的工作模型 —— 那不是调度，是原地打转 */
        val hasAnyWorker: Boolean get() = tierModels.isNotEmpty()
    }
}

/**
 * 一次路由决策的结果。
 *
 * 包含"选了谁"和"为什么" —— 后者不是装饰。用户看到不符合预期的选择时，
 * 唯一能自救的方式就是理解依据，然后手动覆盖。
 */
data class RoutingDecision(
    /** 选中的模型配置 id */
    val modelConfigId: String,

    /** 决策依据，给用户看的人话 */
    val reasons: List<String>,

    /** 本次任务被判定的难度 */
    val effectiveTier: ModelTier,

    /**
     * 是否为降级结果（用户没配该档位，退而求其次）。
     *
     * 界面需要在降级时给出提示 —— 用户以为自己配了省钱方案，
     * 实际每次都走了贵的那个，**不告诉他就等于让他白配**。
     */
    val degraded: Boolean = false,
) {
    val summary: String get() = reasons.joinToString("；")
}

/**
 * 路由失败的原因。
 *
 * ⚠️ 刻意做成 sealed 而不是抛异常：路由失败是**正常业务状态**
 *    （用户还没配 Key、模型都被禁用了），不是程序错误。
 *    调用方应该据此引导配置，而不是崩给用户看。
 *
 * ⚠️ 也**刻意不用 Kotlin 的 `Result<T>`**：它要求失败侧是 `Throwable`，
 *    而把"用户还没配置"包装成一个异常，会诱导调用方写
 *    `try { route() } catch (e: Exception)` —— 那正是我们想避免的。
 *    用自定义的 [Outcome] 让"失败"在类型上就与"异常"分开。
 */
sealed interface RoutingFailure {
    /** 一个可用的模型配置都没有 */
    data object NoModelsConfigured : RoutingFailure

    /** 指定的模型配置不存在（可能刚被删除） */
    data class ModelNotFound(val modelConfigId: String) : RoutingFailure

    /** 模型存在但被禁用 */
    data class ModelDisabled(val modelConfigId: String) : RoutingFailure

    /** 调度模式没有任何工作模型 */
    data object NoWorkerModels : RoutingFailure
}

/**
 * 路由结果的两态封装。
 *
 * 不用 `Result<T>` 的理由见 [RoutingFailure] —— 关键点是**失败不是异常**。
 */
sealed interface RoutingOutcome {
    data class Success(val decision: RoutingDecision) : RoutingOutcome
    data class Failure(val reason: RoutingFailure) : RoutingOutcome
}

/** 便捷取值，语义与 `Result.getOrNull()` 一致 */
fun RoutingOutcome.decisionOrNull(): RoutingDecision? =
    (this as? RoutingOutcome.Success)?.decision

/** 便捷取值，语义与 `Result.exceptionOrNull()` 一致 */
fun RoutingOutcome.failureOrNull(): RoutingFailure? =
    (this as? RoutingOutcome.Failure)?.reason

/**
 * 模型路由器（纯逻辑，零 I/O）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个类不做的事
 * ═══════════════════════════════════════════════════════════════
 *
 * - **不发网络请求** —— 调度模型怎么调是调用方的事，本类只消费它的结论
 * - **不碰 Key** —— 只处理 modelConfigId，凭据由 LlmGateway 解析
 * - **不读写数据库** —— 模型列表由调用方传入
 *
 * 保持这三条，本模块就能在没有手机的机器上被完整验证。
 */
class ModelRouter(
    private val assessor: TaskDifficultyAssessor = TaskDifficultyAssessor(),
) {

    /**
     * 决定用哪个模型。
     *
     * @param mode 当前路由模式
     * @param models 全部模型配置（含禁用的，本类负责过滤）
     * @param tierOverride 调度模型给出的判定；null 表示没调或调用失败，
     *        此时用本地启发式兜底
     */
    fun route(
        mode: RoutingMode,
        models: List<ModelConfig>,
        instruction: String,
        context: TaskContext = TaskContext(),
        tierOverride: ModelTier? = null,
    ): RoutingOutcome {
        val byId = models.associateBy { it.id }

        return when (mode) {
            is RoutingMode.Single -> routeSingle(mode, byId)

            is RoutingMode.Scheduled -> routeScheduled(
                mode = mode,
                byId = byId,
                instruction = instruction,
                context = context,
                tierOverride = tierOverride,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  单模型模式
    // ─────────────────────────────────────────────────────────────

    private fun routeSingle(
        mode: RoutingMode.Single,
        byId: Map<String, ModelConfig>,
    ): RoutingOutcome {
        val model = byId[mode.modelConfigId]
            ?: return RoutingOutcome.Failure(RoutingFailure.ModelNotFound(mode.modelConfigId))

        if (!model.enabled) {
            return RoutingOutcome.Failure(RoutingFailure.ModelDisabled(mode.modelConfigId))
        }

        // 单模型模式**不做难度评估** —— 评估没有意义（无可选项），
        // 白花 CPU 只会增加耗电，而本项目的最高优先级是减少对用户的影响
        return RoutingOutcome.Success(
            RoutingDecision(
                modelConfigId = model.id,
                reasons = listOf("当前为单模型模式"),
                effectiveTier = model.tier,
                degraded = false,
            )
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  调度模式
    // ─────────────────────────────────────────────────────────────

    private fun routeScheduled(
        mode: RoutingMode.Scheduled,
        byId: Map<String, ModelConfig>,
        instruction: String,
        context: TaskContext,
        tierOverride: ModelTier?,
    ): RoutingOutcome {
        // 先确定有效的工作模型集合（存在 + 启用 + 允许当 worker）
        val usable = mode.tierModels.mapNotNull { (tier, id) ->
            val model = byId[id] ?: return@mapNotNull null
            if (!model.canWork) return@mapNotNull null
            tier to model
        }.toMap()

        if (usable.isEmpty()) {
            // ⚠️ 两个失败原因的区分标准是**用户的下一步动作不同**：
            //    · NoWorkerModels  → 用户压根没指定工作模型，去配置页挑一个
            //    · NoModelsConfigured → 指定了，但那些模型被删了/禁用了，去修一下
            //
            //    只看 tierModels 是否为空无法区分这两种情况（指定了不可用的模型时
            //    它也是非空的），所以判据要落在"指定了但一个都没留住"上。
            val specified = mode.tierModels.values
            val anySurvived = specified.any { byId.containsKey(it) }

            return RoutingOutcome.Failure(
                if (specified.isEmpty() || !anySurvived) RoutingFailure.NoWorkerModels
                else RoutingFailure.NoModelsConfigured
            )
        }

        // 难度从哪来：优先用调度模型的判定，没有就用本地启发式
        val reasons = mutableListOf<String>()
        val tier: ModelTier

        if (tierOverride != null) {
            tier = tierOverride
            reasons += "调度模型判为「${tier.displayName}」"
        } else {
            val local = assessor.assess(instruction, context)
            tier = local.tier
            reasons += local.reasons
            reasons += "（本地评估：调度模型未参与）"
        }

        // ── 档位解析 ──────────────────────────────────────────────
        // 优先精确匹配；缺档位时降级 —— 降级方向取决于"缺的是哪边"
        val available = usable.keys

        val exact = usable[tier]
        if (exact != null) {
            return RoutingOutcome.Success(
                RoutingDecision(
                    modelConfigId = exact.id,
                    reasons = reasons,
                    effectiveTier = tier,
                    degraded = false,
                )
            )
        }

        // 任务判为 LIGHT 但没配 LIGHT → 用**能给出的最低档**
        // （简单任务用强模型是浪费，但要完成；用最低的浪费最少）
        // 任务判为 HEAVY 但没配 HEAVY → 用**能给出的最高档**
        val fallbackTier: ModelTier? = when (tier) {
            ModelTier.LIGHT -> ModelTier.atLeast(ModelTier.LIGHT, available)
            ModelTier.HEAVY -> ModelTier.atMost(ModelTier.HEAVY, available)
            ModelTier.STANDARD -> ModelTier.atLeast(ModelTier.STANDARD, available)
                // 均衡档缺失：先看有没有更高的（任务别办砸），
                // 没有就用低的（至少能跑）
                ?: ModelTier.atMost(ModelTier.STANDARD, available)
        }

        val chosen = fallbackTier?.let { usable[it] }
            ?: return RoutingOutcome.Failure(RoutingFailure.NoModelsConfigured)

        reasons += "未配置「${tier.displayName}」档，改用「${chosen.tier.displayName}」档"

        return RoutingOutcome.Success(
            RoutingDecision(
                modelConfigId = chosen.id,
                reasons = reasons,
                effectiveTier = chosen.tier,
                degraded = true,
            )
        )
    }
}
