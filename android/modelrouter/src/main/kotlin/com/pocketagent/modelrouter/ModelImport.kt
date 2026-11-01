package com.pocketagent.modelrouter

import com.pocketagent.provider.api.ModelInfo

/**
 * **模型自动导入** —— 用户填完 Key，把厂商那边**所有能用的模型**一次导入。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：用户指出这是根本问题
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户原话：
 * > "用户填入对应的 apikey 之后就应该能自动识别密钥里有的所有模型"
 *
 * 现状：填了 Key 之后，用户还要**手动一个个加模型**，
 * 每个都要选「档位 + 用途 + 价格 + 启用」四项。
 * 配一个模型要先理解三个概念 —— 那就是"太复杂"的真正来源。
 *
 * ⇒ 本文件把"加模型"变成**一次自动完成**：Key 校验通过后，
 *   拉 `/models`，把能聊天的那些全部建好，用户只需要
 *   在聊天页顶栏挑一个（或者干脆用默认的「自动」）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 两个必须处理的问题（否则自动导入会制造垃圾）
 * ═══════════════════════════════════════════════════════════════
 *
 * **① `/models` 返回的不全是对话模型**
 *    OpenAI 的清单里有 `whisper-1`（语音识别）、`tts-1`（合成）、
 *    `text-embedding-3-large`（向量）、`dall-e-3`（画图）。
 *    把它们当对话模型导入，用户选中后**必然 400** ——
 *    而那正是"自动导入"最容易造成的伤害。
 *    ⇒ [isChatCapable] 按 id 排除非对话模型。
 *
 * **② 档位没有元数据**
 *    `ModelInfo` 有价格、上下文长度、能力，**没有档位**。
 *    ⇒ [tierFor] 按**同厂商内相对价格**分档（便宜=轻量、贵=重型）。
 *      这是可解释的：用户看到"轻量"就明白"便宜的那个"。
 *      价格未知时退回 [ModelTier.STANDARD]。
 */
object ModelImport {

    /**
     * 这个模型 id 看起来能**对话**吗？
     *
     * ⚠️ 这是**黑名单**而不是白名单。
     *    白名单（只认 gpt/claude/...）会漏掉新厂商的新模型 ——
     *    而"我配了 Key 却看不到新模型"比"多一个用不了的模型"更烦人。
     *
     * ★ 只排除那些**确定不能对话**的类别。
     */
    fun isChatCapable(modelId: String): Boolean {
        val id = modelId.lowercase()
        val nonChat = listOf(
            // 向量 / 检索
            "embed", "embedding", "rerank",
            // 语音
            "whisper", "tts", "speech", "audio", "voice",
            // 图像生成 / 编辑
            "dall-e", "dalle", "stable-diffusion", "sd-", "flux",
            "image", "imagen",
            // 审核
            "moderation", "guard",
            // 明确的非对话产物
            "-edit", "-transcribe",
        )
        return nonChat.none { id.contains(it) }
    }

    /**
     * 按**同厂商内相对价格**定档。
     *
     * ★ 分档规则（可解释，不是魔法）：
     *   · 把有价格的模型按输出价排序，分三档
     *   · 最便宜那三分之一 = [ModelTier.LIGHT]
     *   · 最贵那三分之一 = [ModelTier.HEAVY]
     *   · 中间 = [ModelTier.STANDARD]
     *
     * ⚠️ **价格未知 → STANDARD**，不是 LIGHT。
     *    把未知当便宜会让"自动选快的"选到一个其实很贵的模型 ——
     *    与 `ModelConfigMapping` 的纪律一致。
     *
     * ⚠️ 只有一个模型时 → STANDARD（不要让它一上来就是"重型"或"轻量"）。
     */
    fun tierFor(modelId: String, all: List<ModelInfo>): ModelTier {
        val priced = all
            .filter { isChatCapable(it.id) }
            .filter { it.outputPricePerMillion != null }
            .sortedBy { it.outputPricePerMillion }

        if (priced.size < 3) return ModelTier.STANDARD

        val target = priced.firstOrNull { it.id == modelId } ?: return ModelTier.STANDARD
        // ⚠️ 用"排第几"而不是价格阈值：不同厂商的价格区间差几十倍，
        //    固定阈值会在一家厂商上全判成轻量、在另一家全判成重型。
        val rank = priced.indexOf(target)
        val third = priced.size / 3
        return when {
            rank < third -> ModelTier.LIGHT
            rank >= priced.size - third -> ModelTier.HEAVY
            else -> ModelTier.STANDARD
        }
    }

    /**
     * 一个模型该被赋予哪些角色。
     *
     * ★ **默认给「对话 + 执行任务」两个** —— 这是"零配置"的关键：
     *   用户不用再勾选。而两个都给是安全的：
     *   · 只给"执行任务"会让聊天用不了它（回落到别的）
     *   · 只给"对话"会让 agent 用不了它
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-11-01：**重型模型自动兼「调度者」**
     * ═══════════════════════════════════════════════════════════════
     *
     * ⚠️ 背景：原来这里一律不给「调度者」，而**唯一能手动指定的界面
     *    （设置 → 模型配置）已被删除**（用户："那是一个冗余设计，
     *    用 key 现在就可以自动加载出来了"）。
     *
     * 若不给，多模型用户就没有任何办法指定调度者 ——
     * 而调度模式需要它。那会**静默降级**（不是报错，只是路由变差）。
     *
     * ⇒ 按档位自动给：重型模型兼调度者。
     *   · 计划列表**按档位从强到弱排序**，所以 `schedulers.first()`
     *     天然就是最强的那一个（见 [buildPlans] 的排序注释）
     *   · 没有重型模型（全是标准/轻量）时谁也不当调度者，
     *     路由走单模型或多 worker 模式，不会卡住
     *
     * ⚠️ 保留原注释里的那条纪律：**不给所有模型都设成调度者** ——
     *    那等于没有选择。所以只给 HEAVY 档。
     */
    fun rolesFor(tier: ModelTier): Set<ModelRole> {
        val base = setOf(ModelRole.CHAT, ModelRole.WORKER)
        return if (tier == ModelTier.HEAVY) base + ModelRole.SCHEDULER else base
    }

    /**
     * 一个待导入的模型。
     *
     * ★ 与 `ModelConfigRepository.add` 的参数一一对应，
     *   调用方（`:app`）只需要把它逐条传进去。
     */
    data class Plan(
        val modelId: String,
        val label: String,
        val tier: ModelTier,
        val roles: Set<ModelRole>,
        val inputPricePerMillion: Double?,
        val outputPricePerMillion: Double?,
    )

    /**
     * 算出**这次该导入哪些**。
     *
     * @param discovered `/models` 返回的清单（可能包含非对话模型）
     * @param alreadyConfigured 这个 Key 下**已经配过**的 modelId（不重复导入）
     * @return 待导入清单，**按档位从强到弱排**（重型在前）
     *
     * ★ 排序理由：界面上第一个通常被当成"默认"，而重型模型
     *   作为默认更安全（能力够；省钱是用户自己切"快"档的事）。
     */
    fun plan(
        discovered: List<ModelInfo>,
        alreadyConfigured: Set<String> = emptySet(),
    ): List<Plan> = planDetailed(discovered, alreadyConfigured).plans

    /**
     * 一次导入的**完整结果** —— 包括"跳过了什么、为什么"。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-11-01：为什么要返回跳过原因
     * ═══════════════════════════════════════════════════════════════
     *
     * OpenCode 的部分模型走 `/responses` 或 `/anthropic/v1/messages`，
     * 而本 provider 只会 `/chat/completions`。把它们导进来，
     * 用户选中后**必然失败** —— 正是他刚报的那类 bug。
     *
     * ⚠️ 但也不能**静默丢弃**：用户明确要求"填完 Key 自动识别所有模型"，
     *    悄悄少几个他会以为厂商没有那些模型。
     * ⇒ 跳过的记下原因，由界面告知"跳过 N 个，因为它们要另一种接口"。
     */
    data class ImportResult(
        val plans: List<Plan>,
        val skippedByProtocol: List<Skipped>,
    ) {
        /** 厂商给了多少个能聊的模型（含被协议挡掉的）。 */
        val discoveredChatCount: Int get() = plans.size + skippedByProtocol.size
    }

    /** 被协议挡掉的一个模型。 */
    data class Skipped(val modelId: String, val reason: String)

    /**
     * 算出该导入哪些，并记录被协议挡掉的。
     *
     * @param providerId 用于 [ProtocolGate] 分派。
     *   ⚠️ 空字符串 = 未知厂商 ⇒ 一律按 chat/completions，不拦任何东西。
     */
    fun planDetailed(
        discovered: List<ModelInfo>,
        alreadyConfigured: Set<String> = emptySet(),
        providerId: String = "",
    ): ImportResult {
        val skipped = mutableListOf<Skipped>()

        val plans = discovered
            .filter { isChatCapable(it.id) }
            .filter { it.id !in alreadyConfigured }
            // 去重：`/models` 偶尔会返回重复 id
            .distinctBy { it.id }
            .filter { m ->
                val reason = ProtocolGate.unsupportedReason(providerId, m.id)
                if (reason != null) {
                    skipped += Skipped(modelId = m.id, reason = reason)
                    false
                } else {
                    true
                }
            }
            .map { m ->
                // ⚠️ 先算档位再传进去 —— `rolesFor` 要按档位决定谁是调度者
                val tier = tierFor(m.id, discovered)
                Plan(
                    modelId = m.id,
                    label = m.displayName.ifBlank { m.id },
                    tier = tier,
                    roles = rolesFor(tier),
                    inputPricePerMillion = m.inputPricePerMillion,
                    outputPricePerMillion = m.outputPricePerMillion,
                )
            }
            .sortedWith(
                compareByDescending<Plan> { it.tier.rank }
                    .thenBy { it.outputPricePerMillion ?: Double.MAX_VALUE },
            )

        return ImportResult(plans = plans, skippedByProtocol = skipped)
    }
}
