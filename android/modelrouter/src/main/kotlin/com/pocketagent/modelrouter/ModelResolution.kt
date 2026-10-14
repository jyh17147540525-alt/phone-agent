package com.pocketagent.modelrouter

/**
 * 按用途挑一个模型。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它解决的是什么问题
 * ═══════════════════════════════════════════════════════════════
 *
 * `ChatPort` 曾经是 `firstOrNull { it.enabled }` —— 按 `createdAt` 升序，
 * 也就是**永远用最早添加且启用的那条**。
 *
 * 后果：用户配了多个模型时，聊天用哪个是**静默决定**的 ——
 * 界面上一个字都没说，而用户只会觉得"这次回答怎么变笨了"。
 * 这正是本项目头号 bug 形态「安静地少做一件事」。
 *
 * 参考 Operit 的做法（按 chat / memory / summarization / UI control 分别指定模型），
 * 把"用哪个"从**隐式顺序**变成**用户可表达的意图**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 三级回落：每一级都必须**能被说出来**
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   ① EXACT          用户显式勾了「对话」的模型        ← 用户说了算
 *   ② FALLBACK_WORKER 没有勾「对话」的，退到「执行任务」 ← 兼容老配置
 *   ③ FALLBACK_ANY    连「执行任务」都没有，用任意启用项 ← 兜底，但要标出来
 *   ④ None            一个启用的都没有 ⇒ 明确报错给下一步
 * ```
 *
 * ⚠️ **返回值里带 [ResolutionStep]**，而不是只返回一个 `ModelConfig`。
 *    只返回 config 的话，调用方无从知道"这是用户选的还是我猜的"——
 *    于是又回到了静默。**回落本身不是问题，"不告诉你回落了"才是问题。**
 *
 * ⚠️ 本文件是**纯函数、零 IO、零 Android 依赖** ⇒ 进得了离线验证器。
 *    时间与数据库都不参与，所以它可以在没有设备的机器上被完整测到。
 */
fun List<ModelConfig>.resolveForPurpose(purpose: ModelRole): ModelResolution {
    // ① 用户显式指定
    firstOrNull { it.enabled && purpose in it.roles }?.let {
        return ModelResolution.Found(it, ResolutionStep.EXACT)
    }

    // ② 兼容老配置：CHAT 是 2026-10-14 才加的，此前用户只勾过「执行任务」。
    //    没有这一级，所有老用户升级后聊天会立刻报"没有可用模型" ——
    //    而他们的配置**本来是好用的**。
    //
    //    ⚠️ 只在 purpose 不是 WORKER 自己时回落（否则第 ① 步就已经命中了）。
    if (purpose != ModelRole.WORKER) {
        firstOrNull { it.canWork }?.let {
            return ModelResolution.Found(it, ResolutionStep.FALLBACK_WORKER)
        }
    }

    // ③ 兜底：任意启用项。⚠️ 这一级最危险（用户可能压根没打算让它对话），
    //    所以它必须被标出来，让界面能说"暂时用了它"。
    firstOrNull { it.enabled }?.let {
        return ModelResolution.Found(it, ResolutionStep.FALLBACK_ANY)
    }

    // ④ 一个都没有 ⇒ 调用方要给出**可操作的下一步**
    return ModelResolution.None
}

/** [resolveForPurpose] 的结果。 */
sealed interface ModelResolution {

    /**
     * @param config 选中的配置。⚠️ 调用方要用它的 `modelId`（**不是 `id`**）——
     *        `id` 是数据库行 id（UUID），当模型名发出去会被服务端拒绝，
     *        报「模型不存在」而用户会去查钱包。
     * @param via 靠哪一级选中的。**必须能被界面说出来**（见文件头注释）。
     */
    data class Found(val config: ModelConfig, val via: ResolutionStep) : ModelResolution

    /** 没有可用的模型。调用方应引导用户去配置，而不是抛异常。 */
    data object None : ModelResolution
}

/**
 * 选中的**依据**。
 *
 * ⚠️ 枚举而不是布尔：`EXACT` 与两个 `FALLBACK_*` 的**用户可做的事完全不同**
 *    （前者什么都不用做，后者要去勾一个用途），合并成一个 `isFallback: Boolean`
 *    会让界面只能给出笼统的提示。
 */
enum class ResolutionStep {

    /** 用户显式勾了这个用途 —— 最理想，不需要任何提示。 */
    EXACT,

    /** 退到了「执行任务」。提示：想指定的话去勾「对话」。 */
    FALLBACK_WORKER,

    /** 退到了任意启用项。⚠️ 用户可能压根没打算让它对话。 */
    FALLBACK_ANY,
    ;

    /** 是不是"不是用户显式选的"。界面据此决定要不要给提示。 */
    val isFallback: Boolean get() = this != EXACT

    /** 给用户看的说明。**每一条都要给出"下一步"**，不是复述现状。 */
    val explanation: String
        get() = when (this) {
            EXACT -> ""
            FALLBACK_WORKER ->
                "你还没有指定「对话」用哪个模型，暂时用了标着「执行任务」的那一个。" +
                    "想固定下来的话，去「设置 → 模型配置」给它勾上「对话」。"
            FALLBACK_ANY ->
                "你配置的模型都没有勾用途，暂时用了其中一个。" +
                    "去「设置 → 模型配置」勾上「对话」，免得以后加了新模型时聊天换人。"
        }
}
