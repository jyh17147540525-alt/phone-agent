package com.pocketagent.modelrouter

/**
 * **模型选择器** —— 用户只需要"用哪个"，不需要懂档位和角色。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：用户要求简化
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户原话：
 * > "模型选择页你做得太复杂了，能不能像 workbuddy、deepseekharness
 * >  那样快捷切换模型，还可以通过类似于 codex 那样有一个思考强度滑块，
 * >  默认是让用户选自动的，也就是根据场景需求自动调用模型。"
 *
 * 现状的问题：每个模型要配「档位 + 用途 + 启用 + 价格」四项，
 * 还有大段解释文字。用户配一个模型要先读懂三个概念。
 *
 * ⇒ 把"选择"收敛成两个输入：
 *   ① **用哪个**：`Auto`（默认）或 `Pinned(某个模型)`
 *   ② **多用力想**：`ThinkingEffort`（默认 `OFF`）
 *
 * 其余（档位、角色）变成**规则**而不是**表单** —— 见 [pick]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 思考强度当前**只影响选哪个模型**，不会传给厂商
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的类比是 Codex 的 reasoning effort，那个是**请求参数**
 * （`reasoning_effort: low/medium/high`）。
 *
 * ⚠️ 但本项目的协议层（`:provider:openai-compat`）**还没有**
 *    这个字段 —— 查过，没有任何 `reasoning_effort` / `thinking` 的写入点。
 *
 * ⇒ 所以本版如实做成"**选哪一档模型**"：
 *   想得多 → 挑更强的模型；想得少 → 挑更快的模型。
 *   这是**真的会生效**的语义，而不是发一个厂商可能忽略的参数。
 *
 * ★ 将来协议层支持了 `reasoning_effort`，再加一个透传即可 ——
 *   那时 [ThinkingEffort] 就是那个字段的直接映射，本文件的选模型
 *   逻辑仍然有效（两者可以同时用）。
 */
enum class ThinkingEffort(val label: String, val hint: String) {

    /**
     * **关闭** —— 不做额外推理。最快、最省。
     *
     * ⚠️ 这是「不额外投入推理」的意思，不是"不思考" ——
     *    模型照样回答，只是不分配额外的思考预算。
     */
    OFF(
        label = "off",
        hint = "不做额外推理。最快、最省，适合闲聊和简单问题",
    ),

    /** **低** —— 少量推理。 */
    LOW(
        label = "low",
        hint = "少量推理。简单任务够用",
    ),

    /** **高** —— 深入推理。 */
    HIGH(
        label = "high",
        hint = "深入推理。复杂任务更稳，但更慢也更贵",
    ),

    /**
     * **最高** —— 最长推理链。
     *
     * ★ 选到这一档会触发轨道上的 **apex 模式**（方格网格 + 发光把手）——
     *   那是参考图里的样子，也是桌面端 `[data-apex]` 的语义：
     *   "你已经推到顶了"。
     */
    MAX(
        label = "max",
        hint = "最长推理链。最慢也最贵，留给真正难的任务",
    ),
    ;

    /**
     * **这一档要发给模型的 `reasoning_effort`**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-11-01：回答"滑块真的能调节思考强度吗"
     * ═══════════════════════════════════════════════════════════════
     *
     * ⚠️ 在此之前，`ThinkingEffort` 只有 [preferredTier] 一条去向 ——
     *    也就是说**滑块只是换了个模型**，请求里从来没有推理参数。
     *
     *    后果很具体：
     *    · 只有一个模型的用户 —— 拉条**完全无效**
     *    · `off` 与 `low` 都映射 LIGHT —— 前两档没有任何区别
     *    · 界面上"深入推理""最长推理链"，请求侧没有对应字段
     *
     * ⇒ 现在多这一条去向。`null` = 不指定（由模型自己决定）。
     *
     * ⚠️ 为什么 `HIGH` 是 `medium` 而不是 `high`：
     *    OpenAI 的 `reasoning_effort` 只有 low / medium / high 三档，
     *    而本 UI 有四档。留 `high` 给 [MAX]，`HIGH` 落在 `medium` ——
     *    否则四档压成三档时，最高的两档又会变成一样。
     *
     * ⚠️ 发不发仍由 Provider 决定：只有 `supportsReasoningEffort`
     *    的厂商才会真的写进请求体。
     */
    val wireValue: String?
        get() = when (this) {
            OFF -> null
            LOW -> "low"
            HIGH -> "medium"
            MAX -> "high"
        }

    /** 这一档想要的模型档位。 */
    val preferredTier: ModelTier
        get() = when (this) {
            OFF -> ModelTier.LIGHT
            LOW -> ModelTier.LIGHT
            HIGH -> ModelTier.STANDARD
            MAX -> ModelTier.HEAVY
        }

    /** 是不是最高档 —— 界面据此进入 apex 视觉。 */
    val isApex: Boolean get() = this == MAX
}

/**
 * 这一次请求的场景。
 *
 * ★ 只有两种，因为本产品只有两条路径：
 * 聊天框（纯对话）与 agent 循环（要动手）。
 */
enum class Scenario {
    /** 聊天框里说话。不需要最强模型，快更重要。 */
    CHAT,

    /** 跑 agent 循环（要动手操作手机）。需要稳。 */
    AGENT,
    ;

    /** 这个场景"默认想要"的档位。 */
    val defaultTier: ModelTier
        get() = when (this) {
            // ⚠️ 聊天不需要 HEAVY：用户问"今天几号"用旗舰模型是浪费，
            //    而且慢 —— 首字延迟对聊天体感影响最大。
            CHAT -> ModelTier.STANDARD
            // ⚠️ agent 不要 LIGHT：它要连续多步操作手机，一步错就要重来，
            //    "省钱"在这里会变成"更贵"（重试的轮次成本更高）。
            AGENT -> ModelTier.STANDARD
        }
}

/**
 * 用户对"用哪个模型"的选择。
 *
 * ★ 只有两种 —— 这是简化的核心：把原来的
 *   「档位 + 用途 + 启用」三组开关换成一个二选一。
 */
sealed interface ModelChoice {

    /**
     * **自动**（默认）。按 [Scenario] + [ThinkingEffort] 挑。
     *
     * ★ 用户不需要知道"档位""角色"这些概念，只要说"自动"。
     */
    data object Auto : ModelChoice

    /**
     * **钉死某个模型**（快捷切换选的那个）。
     *
     * @param configId `ModelConfig.id`
     */
    data class Pinned(val configId: String) : ModelChoice
}

/**
 * 一次选择的结果。
 *
 * ★ [reason] 必须能**直接展示给用户** —— 这是本项目的一条纪律：
 *   用户问"为什么这次用了这个模型"时，界面要能回答，
 *   而不是让他去翻日志。
 */
data class PickResult(
    val config: ModelConfig?,
    val reason: String,
) {
    val found: Boolean get() = config != null
}

/**
 * 按「选择 + 思考强度 + 场景」挑一个模型。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 决策顺序（每一步都短路，顺序不可调换）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **Pinned 且那个模型启用** → 直接用。用户明确选了，不要自作聪明。
 * 2. **Pinned 但那个模型被停用/删了** → 回落到 Auto，并**说清楚**
 *    （"你选的 X 已停用，这次自动挑了 Y"）。
 *    ⚠️ 静默回落是最糟的：用户以为在用 A，实际在用 B。
 * 3. **Auto** → 按场景 + 思考强度算目标档位，再在候选里找最接近的。
 *
 * @param models 全部已配置的模型（含停用的，本函数自己过滤）
 * @param choice 用户选择
 * @param effort 思考强度
 * @param scenario 本次请求的场景
 */
fun pickModel(
    models: List<ModelConfig>,
    choice: ModelChoice,
    effort: ThinkingEffort,
    scenario: Scenario,
): PickResult {

    // ── 步骤 1/2：钉死的模型 ────────────────────────────────
    if (choice is ModelChoice.Pinned) {
        val pinned = models.firstOrNull { it.id == choice.configId }
        return when {
            pinned == null -> autoPick(
                models = models,
                effort = effort,
                scenario = scenario,
                // ⚠️ 用户选的那个**不存在了**（被删）—— 必须说出来
                prefix = "你之前选的模型已被删除，这次自动挑了",
            )

            !pinned.enabled -> autoPick(
                models = models,
                effort = effort,
                scenario = scenario,
                prefix = "「${pinned.effectiveLabel}」已停用，这次自动挑了",
            )

            else -> PickResult(
                config = pinned,
                reason = "用你选定的「${pinned.effectiveLabel}」",
            )
        }
    }

    // ── 步骤 3：自动 ────────────────────────────────────────
    return autoPick(models, effort, scenario, prefix = null)
}

/**
 * 自动挑。被 [pickModel] 的两条回落路径共用。
 *
 * @param prefix 非空时，理由前会加上这句（说明"为什么不是你以为的那个"）
 */
private fun autoPick(
    models: List<ModelConfig>,
    effort: ThinkingEffort,
    scenario: Scenario,
    prefix: String?,
): PickResult {
    val enabled = models.filter { it.enabled }
    if (enabled.isEmpty()) {
        return PickResult(
            config = null,
            reason = if (models.isEmpty()) {
                "还没有配置任何模型。去「设置 → 模型密钥」加一个。"
            } else {
                "所有模型都停用了。去「设置 → 模型」启用一个。"
            },
        )
    }

    // ① 优先在这个场景"能胜任"的模型里挑。
    //
    // ⚠️ 但**不是硬门槛**：如果用户只配了一个模型却没勾对应角色，
    //    仍然要能用它 —— 否则他会看到"没有可用的模型"而明明配了。
    //    这与 `resolveForPurpose` 的三级回落是同一条纪律。
    val forScenario = enabled.filter { m ->
        when (scenario) {
            Scenario.CHAT -> m.canChat
            Scenario.AGENT -> m.canWork
        }
    }
    val candidates = forScenario.ifEmpty { enabled }

    // ② 目标档位
    // ★ 四档都映射到固定档位（OFF/LOW→轻量，HIGH→均衡，MAX→重型）
    val desired = effort.preferredTier

    // ③ 在候选里找最接近目标档位的那个
    //
    // ★ 优先"不低于目标"里最低的（够用就好），
    //   没有则退到"不高于目标"里最高的（用手上最好的）。
    val availableTiers = candidates.map { it.tier }.toSet()
    val chosenTier = ModelTier.atLeast(desired, availableTiers)
        ?: ModelTier.atMost(desired, availableTiers)
        ?: desired

    val sameTier = candidates.filter { it.tier == chosenTier }

    // ④ 同档位多个时：优先**价格已知且更便宜**的，其次按列表顺序（稳定）
    //
    // ⚠️ 价格 null = **未知**，不能当成免费（见 `ModelConfigMapping` 的注释）。
    //    ⇒ 未知价格的排在"已知更贵"的后面，而不是排在最前。
    val config = sameTier.minWithOrNull(
        compareBy(
            // 已知价格优先（null 排后）
            { it.outputPricePerMillion == null },
            { it.outputPricePerMillion ?: Double.MAX_VALUE },
        ),
    ) ?: sameTier.first()

    val why = buildString {
        if (prefix != null) append(prefix).append(' ')
        append("「${config.effectiveLabel}」（${chosenTier.displayName}档")
        append("，思考强度 ${effort.label}")
        append('）')
    }

    return PickResult(config = config, reason = why)
}
