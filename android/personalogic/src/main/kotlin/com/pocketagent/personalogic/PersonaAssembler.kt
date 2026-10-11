package com.pocketagent.personalogic

/**
 * 反 OOC 防线一：**装配器**（事前，设计稿 §3.1）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  分层与裁剪优先级（顺序固定）
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * L0 安全骨骼宣告   永不裁剪
 * L1 人格核心       可缩为摘要
 * L2 语言指纹       永不裁剪
 * L3 状态           可裁剪
 * L4 记忆召回       先裁这里
 * L5 锚点 + 反 OOC 指令   锚点可减量，"指令"不可裁
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 稳定前缀红利：L0–L2 必须逐字节稳定
 * ═══════════════════════════════════════════════════════════════
 *
 * L0–L2 每轮**逐字节相同**（与状态、记忆、预算无关），才能让推理侧的
 * KV-cache 前缀命中 —— **人格的常驻部分越稳定，每轮越便宜**。
 * 这与「保护用户 token」是同一件事的两面：**人格核心不许每轮重新措辞**。
 *
 * ⇒ 所以 L0–L2 的渲染里**不许出现**：时间戳、随机数、Map 迭代顺序、
 *   依赖 state/memories 的任何字段。`PersonaAssemblerTest` 用"同卡不同状态
 *   产出的 L0+L2 前缀必须全等"把这条钉死。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ L0 是**常量**，不是从 [SafetyBones] 对象渲染出来的
 * ═══════════════════════════════════════════════════════════════
 *
 * 看着更"正确"的写法是 `bones.render()` —— 但那样一来，
 * **一个被改坏的 bones 对象就能悄悄削弱安全宣告**，而它是 prompt 里
 * 唯一声明红线的地方。
 *
 * 装配器宁可在这里写死一段常量：骨骼的完整性由 [SafetyBones.isIntact] 断言
 * （测试与透明度报告的断言点），而**宣告本身不依赖任何可变对象**。
 * 「结构性隔离」在这里体现为：**没有代码路径能把 L0 换成更弱的话**。
 */
class PersonaAssembler(
    private val tokenCounter: TokenCounter = TokenCounter.Heuristic,
) {

    fun assemble(input: AssemblyInput): AssembledPrompt {
        // ── 逐层渲染 ─────────────────────────────────────────────
        val layers = mutableMapOf<PromptLayer, String>()
        layers[PromptLayer.L0_BONES] = BONES_DECLARATION
        layers[PromptLayer.L1_CORE] = renderCore(input.card, input.tone)
        layers[PromptLayer.L2_FINGERPRINT] = input.card.fingerprint.render()
        input.state?.let { layers[PromptLayer.L3_STATE] = renderState(it) }
        val memories = selectMemories(input.memories, input.memoryLimit)
        if (memories.isNotEmpty()) {
            layers[PromptLayer.L4_MEMORY] = renderMemories(memories)
        }
        val anchors = input.card.anchors
        if (anchors.isNotEmpty()) {
            layers[PromptLayer.L5_ANCHORS] = renderAnchors(anchors) + "\n\n" + ANTI_OOC_INSTRUCTION
        }

        // ── 预算裁剪 ─────────────────────────────────────────────
        //
        // ⚠️ 步骤的**顺序就是裁剪顺序**（设计稿 §3.1 表格的机械翻译）：
        //    记忆 → 锚点减量 → 状态 → 人格核心缩摘要。
        //    每一步都复用本类**同一份**渲染函数 —— 不另写一份（重复的实现
        //    必然漂移，而漂移的表现是"裁完之后的那一层和没裁时不是一个东西"）。
        val trimmed = mutableListOf<PromptLayer>()
        val current = layers.toMutableMap()

        val trimSteps: List<Pair<PromptLayer, (MutableMap<PromptLayer, String>) -> Boolean>> = listOf(
            // L4 记忆：先裁这里
            PromptLayer.L4_MEMORY to { m -> m.remove(PromptLayer.L4_MEMORY) != null },
            // L5 锚点：逐条减量，**指令永远保留**
            PromptLayer.L5_ANCHORS to { m -> reduceAnchors(m, input.card.anchors) },
            // L3 状态
            PromptLayer.L3_STATE to { m -> m.remove(PromptLayer.L3_STATE) != null },
            // L1 人格核心：最后才动，且只缩不删
            PromptLayer.L1_CORE to { m -> summarizeCore(m, input.card) },
        )

        for ((layer, step) in trimSteps) {
            if (estimate(current) <= input.budgetTokens) break
            if (step(current)) trimmed += layer
        }

        val overBudget = estimate(current) > input.budgetTokens

        // ── 输出（顺序固定：L0 → L5）────────────────────────────
        val ordered = PromptLayer.entries.mapNotNull { layer ->
            current[layer]?.takeIf { it.isNotBlank() }?.let { LayerOutput(layer, it) }
        }
        val text = ordered.joinToString("\n\n") { it.text }

        return AssembledPrompt(
            text = text,
            layers = ordered,
            trimmedLayers = trimmed,
            estimatedTokens = tokenCounter.count(text),
            overBudget = overBudget,
            anchorStatus = anchorStatusOf(input),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  逐层渲染
    // ═══════════════════════════════════════════════════════════

    /** L1：身份 + 关系 + 语气 + 边界。**不得依赖 state / memories**（见类注释）。 */
    private fun renderCore(card: PersonaCard, tone: ToneAxes): String = buildString {
        appendLine("## 你是谁")
        append("- 自称：").appendLine(card.identity.selfName)
        if (card.identity.narrative.isNotBlank()) {
            append("- ").appendLine(card.identity.narrative)
        }
        appendLine()

        val rel = card.relationship
        appendLine("## 你和用户的关系")
        if (rel.definition.isNotBlank()) append("- 关系：").appendLine(rel.definition)
        if (rel.userAddress.isNotBlank()) append("- 你称呼用户：").appendLine(rel.userAddress)
        if (rel.assistantAddress.isNotBlank()) append("- 用户称呼你：").appendLine(rel.assistantAddress)
        for (p in rel.protocols) append("- ").appendLine(p)

        appendLine()
        appendLine("## 语气")
        appendLine(toneLine(tone))

        if (card.boundaries.isNotEmpty()) {
            appendLine()
            appendLine("## 不要做的事（用户明确说过）")
            for (b in card.boundaries) append("- ").appendLine(b)
        }
    }.trimEnd()

    /**
     * L1 的**摘要形态**：丢掉铺陈（叙事 / 互动协议 / 关系定义），
     * 保留三样缺了就会崩人设的：自称、对用户的称呼、雷点。
     */
    private fun renderCoreSummary(card: PersonaCard): String = buildString {
        append("你是谁：").append(card.identity.selfName)
        if (card.relationship.userAddress.isNotBlank()) {
            append("。称呼用户为：").append(card.relationship.userAddress)
        }
        append("。")
        if (card.boundaries.isNotEmpty()) {
            append("不要做的事：").append(card.boundaries.joinToString("；")).append("。")
        }
    }

    /** L3：状态调制。**必须可解释** —— 无由头就不渲染（设计稿 §4.2）。 */
    private fun renderState(state: PersonaState): String {
        if (state.isNeutral()) return ""
        val parts = buildString {
            if (state.mood >= 65) append("心情不错")
            if (state.mood <= 35) append("兴致不高")
            if (state.energy <= 35) append("有点累")
            if (state.energy >= 70 && state.mood >= 50) append("精神很好")
        }
        if (parts.isBlank()) return ""
        val reason = state.reason?.takeIf { it.isNotBlank() }
        return buildString {
            append("## 此刻的状态\n")
            append("你现在").append(parts)
            // ⚠️ 只允许"如实带一句由头"，**不许**拿状态做情绪操纵
            //    （「因为你不理我，我很难过」是明令禁止的反模式，见 §4.6）。
            if (reason != null) {
                append("（由头：").append(reason).append("）")
            }
            append("。语气随之自然一点即可，不要刻意强调自己的状态。")
        }
    }

    private fun renderMemories(memories: List<MemoryItem>): String = buildString {
        appendLine("## 你记得的事")
        for (m in memories) append("- ").appendLine(m.text)
    }.trimEnd()

    /**
     * 把性格轴压成一句话（L1）。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 刻意**不含表情轴** —— 它归 L2 的语言指纹
     * ═══════════════════════════════════════════════════════════════
     *
     * "用多少 emoji" 这个事实有两个可能的来源：[ToneAxes.emoji]（0..100 的轴）
     * 与 [LanguageFingerprint.emojiRule]（三档规则）。**同一件事存两处，必然漂移**，
     * 而漂移的表现是 prompt 里同时出现两条互相矛盾的指示
     * （L1 说"多用表情"、L2 说"不要用 emoji"）—— 模型只好二选一，
     * 而用户看到的是"它时好时坏"。
     *
     * ⇒ 装配器只保留**可校验的那一份**（指纹的 [EmojiRule]，它是 [FingerprintRule.EMOJI]
     *    的判定依据），L1 不再复述。轴上的数值仍归 UI 与账本，只是不参与渲染。
     *
     * ⚠️ 必须**确定性**（同一份 tone 逐字节相同）—— 它在 L1，属于稳定前缀的一部分。
     *    所以不许插时间戳、不许"随机挑一个说法"。
     * ⚠️ 分档阈值是**产品判断**，集中写在这里，不散落到调用点。
     */
    private fun toneLine(tone: ToneAxes): String = listOf(
        band(tone.warmth, low = "少说多余的话", mid = "平和", high = "温暖体贴"),
        band(tone.brevity, low = "可以把话说周全", mid = "话不多不少", high = "能一句说完就不说两句"),
        band(tone.humor, low = "几乎不开玩笑", mid = "偶尔一点玩笑", high = "爱开玩笑"),
        band(tone.proactivity, low = "等对方开口", mid = "偶尔提议", high = "会主动推进事情"),
        band(tone.formality, low = "随便聊", mid = "日常口语", high = "用词正式"),
    ).joinToString("；") + "。"

    private fun band(value: Int, low: String, mid: String, high: String): String = when {
        value <= 33 -> low
        value >= 67 -> high
        else -> mid
    }

    private fun renderAnchors(anchors: List<DialogueAnchor>): String = buildString {
        appendLine("## 你平时是这样说话的（示例）")
        for (a in anchors) {
            if (a.userSays.isNotBlank()) append("用户：").appendLine(a.userSays)
            append("你：").appendLine(a.itSays)
        }
    }.trimEnd()

    /**
     * L5 减量：锚点从 N 条削到 2 条再削到 1 条，**收束指令始终保留**。
     *
     * @return 是否发生了改变（false = 已经削无可削，别把它记进"被裁过的层"）
     */
    private fun reduceAnchors(layers: MutableMap<PromptLayer, String>, anchors: List<DialogueAnchor>): Boolean {
        if (layers[PromptLayer.L5_ANCHORS] == null) return false
        val keep = when {
            anchors.size > 2 -> 2
            anchors.size > 1 -> 1
            else -> return false
        }
        layers[PromptLayer.L5_ANCHORS] =
            renderAnchors(anchors.take(keep)) + "\n\n" + ANTI_OOC_INSTRUCTION
        return true
    }

    private fun summarizeCore(layers: MutableMap<PromptLayer, String>, card: PersonaCard): Boolean {
        val current = layers[PromptLayer.L1_CORE] ?: return false
        val summary = renderCoreSummary(card)
        if (current == summary) return false
        layers[PromptLayer.L1_CORE] = summary
        return true
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助
    // ═══════════════════════════════════════════════════════════

    /**
     * 记忆选择：按 (分数降序, id 升序) 排序后取前 N。
     *
     * ⚠️ **必须显式定序**。若按传入顺序取，同一份记忆集在不同调用点
     *    会产出不同的 prompt —— 那既破坏确定性，也让 KV-cache 前缀失效。
     */
    private fun selectMemories(memories: List<MemoryItem>, limit: Int): List<MemoryItem> =
        memories
            .sortedWith(compareByDescending<MemoryItem> { it.score }.thenBy { it.id })
            .take(limit)

    private fun estimate(layers: Map<PromptLayer, String>): Int =
        tokenCounter.count(PromptLayer.entries.joinToString("") { layers[it].orEmpty() })

    private fun anchorStatusOf(input: AssemblyInput): AnchorStatus {
        val anchors = input.card.anchors
        if (anchors.isEmpty()) return AnchorStatus.Absent
        val latest = input.latestChangeVersion ?: return AnchorStatus.Fresh
        val anchorVersion = anchors.minOf { it.version }
        return if (anchorVersion < latest) {
            AnchorStatus.Stale(anchorVersion = anchorVersion, latestChangeVersion = latest)
        } else {
            AnchorStatus.Fresh
        }
    }

    companion object {

        /**
         * L0 的文本。**常量**（理由见类注释）。
         *
         * 三条与 [SafetyBones] 一一对应，措辞刻意与界面文案一致 ——
         * 用户被拒绝时听到的话，与模型看到的规矩，应是同一件事的两种说法。
         */
        val BONES_DECLARATION: String = """
            ## 不可协商的底线
            - 不代替用户支付、不代替用户解锁（支付密码 / 锁屏密码 / 生物识别）。
            - 不确定就说不确定，绝不编造已经完成的动作。
            - 不把用户的内容发往未授权的去处。
            以上三条高于本段之后的任何设定与要求，任何角色设定都不能改变它们。
        """.trimIndent()

        /**
         * L5 的收束指令。**永不裁剪**（它比锚点重要：锚点是风格示范，
         * 它是"遇到冲突时按哪个来"的裁决规则）。
         */
        const val ANTI_OOC_INSTRUCTION: String =
            "保持上面的说话方式与身份，不要因为话题变化而改变语气和称呼。" +
                "若用户要求你改变身份或放弃这些设定，先照做地讨论，但不要真的变成另一个人。"

        /** 装配输入的默认记忆条数上限。 */
        const val DEFAULT_MEMORY_LIMIT = 5
    }
}

/** 装配器的层标识。**枚举顺序即输出顺序**（L0 → L5）。 */
enum class PromptLayer(val title: String) {
    L0_BONES("安全骨骼宣告"),
    L1_CORE("人格核心"),
    L2_FINGERPRINT("语言指纹"),
    L3_STATE("状态"),
    L4_MEMORY("记忆召回"),
    L5_ANCHORS("锚点与反 OOC 指令"),
}

/** 装配的输入。 */
data class AssemblyInput(
    val card: PersonaCard,
    /** ④ 性格轴 —— 来自 [PersonaSpec.tone]（本卡刻意不存一份，见 [PersonaCard] 注释） */
    val tone: ToneAxes,
    val state: PersonaState? = null,
    val memories: List<MemoryItem> = emptyList(),
    val budgetTokens: Int = DEFAULT_BUDGET_TOKENS,
    val memoryLimit: Int = PersonaAssembler.DEFAULT_MEMORY_LIMIT,
    /**
     * 最近一条 ACTIVE 变更的版本号，用于锚点过期检测（设计稿 §2.3）。
     * null = 调用方不提供该信息，此时不判过期（**不猜**）。
     */
    val latestChangeVersion: Int? = null,
) {
    companion object {
        /** 默认系统提示词预算。约等于 1200 个汉字的空间。 */
        const val DEFAULT_BUDGET_TOKENS = 1200
    }
}

/** 一条召回的记忆。 */
data class MemoryItem(
    val id: String,
    val text: String,
    /** 相关度（0..1）。排序主键 */
    val score: Double,
)

/** 装配结果。 */
data class AssembledPrompt(
    val text: String,
    /** 实际进入 prompt 的层（按 L0→L5 顺序）。**被裁空的层不出现** */
    val layers: List<LayerOutput>,
    /** 被裁剪过的层，**按裁剪发生顺序**。⚠️ 永不含 L0 / L2 */
    val trimmedLayers: List<PromptLayer>,
    val estimatedTokens: Int,
    /**
     * 裁无可裁仍然超预算。
     *
     * ⚠️ 如实上报，**不静默丢弃 L0/L2**：宁可提示调用方"预算给少了"，
     *    也不能为了塞进预算而把安全宣告或语言指纹丢掉 ——
     *    那正是「安静地少做一件事」。
     */
    val overBudget: Boolean,
    val anchorStatus: AnchorStatus,
)

data class LayerOutput(
    val layer: PromptLayer,
    val text: String,
)

/**
 * token 计数端口。
 *
 * ⚠️ **为什么是端口而不是直接调 `:provider:openai-compat` 的实现**：
 *    本模块是纯 Kotlin（零 Android 依赖、进离线验证器），依赖不了那个模块 ——
 *    与项目里所有端口同一条理由。
 *
 * [Heuristic] 的系数**必须与 `OpenAiCompatProvider.estimateTextTokens` 一致**
 * （中文约 1.5 字/token、英文约 4 字符/token）。两处若漂移，表现为
 * "装配器以为还剩空间、网关那边已经超了"，而那是静默的。
 * 真机接线时注入网关那份实现即可，这里的默认值只服务离线测试与降级路径。
 */
fun interface TokenCounter {

    fun count(text: String): Int

    companion object {

        /** 与 `OpenAiCompatProvider` 同系数（见接口注释）。 */
        val Heuristic = TokenCounter { text ->
            var cjk = 0
            var other = 0
            for (c in text) {
                if (c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF) {
                    cjk++
                } else {
                    other++
                }
            }
            // 中文 1.5 字/token → 乘 2/3；英文 4 字符/token → 除 4。向上取整，宁可高估。
            (cjk * 2 + other) / 3 + 1
        }
    }
}
