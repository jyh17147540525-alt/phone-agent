package com.pocketagent.personalogic

/**
 * **再锚定** —— 把"她本来怎么说话"重新放到模型眼前。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它治的是哪一类病
 * ═══════════════════════════════════════════════════════════════
 *
 * 聊天请求的结构是：`[system prompt][历史 user/assistant 若干条][这一轮 user]`。
 * 历史里的 assistant 消息是**模型自己说过的话** —— 它是 few-shot 里权重最高的一类
 * 示范，比 system 里任何"你要像人"都强，因为它同时提供了"内容"和"格式"。
 *
 * 于是有个正反馈：
 *
 * ```
 *   第 5 轮  她回得还行（88 分）
 *   第 20 轮 模型照着"还行的自己"写 → 略平一点（84）
 *   第 40 轮 照着"略平的自己"写 → 再平一点（79）
 *   ...      每轮都在向"历史均值"回归
 * ```
 *
 * 这就是用户说的"轮次越多 AI 味越浓"。**没有任何一条规则被违反**，
 * 每一轮单看都合格 —— 下滑只发生在**跨轮**。
 *
 * ⇒ 治法不是再加一条禁令（禁令会被读成肯定，且规则越堆越像模板），
 *   而是**换参照物**：从"整个历史"里挑出分数最高的几条，明确告诉她
 *   「你是这么说话的」。参照物高于均值，回归方向就反过来了。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么放在 system prompt 的**最末尾**，而不是紧跟历史之后
 * ═══════════════════════════════════════════════════════════════
 *
 * 理想位置是 history 之后（SillyTavern 叫 post-history instructions），
 * 因为"离生成点越近，约束越硬"。本类**不**那么做，理由是两条硬的：
 *
 * 1. **provider 兼容性**：往 messages 末尾塞一条 assistant 消息等于 prefill，
 *    OpenAI 官方 chat completions 不支持；部分兼容服务会 400 或静默忽略。
 *    这个 app 是 BYOK，用户接的是十几个不同厂商 —— 不能赌。
 * 2. **没有真机可验**：位置效应必须实测才知道值不值这个风险，
 *    而当前交付流程里跑不了端到端（真机验证归 WorkBuddy）。
 *
 * ⇒ 折中：放在 system prompt **末尾**。KV-cache 上这也是最优解 ——
 *   prefix caching 按前缀命中，动态段越靠后，被作废的缓存越少（人格段仍命中）。
 *   等真机验证过位置效应，再把 [prefillCandidate] 接上去。
 */
object Reanchor {

    /**
     * 一段再锚定的字数上限。
     *
     * 定 380 的理由：三条锚点（各 ≤120 字）+ 两行说明 ≈ 400。
     * ⚠️ 再长就挤占记忆段了 —— 而记忆是"她记得你"的载体，
     *    为去 AI 味牺牲连续性，是拿六性质里的一条换另一条，不划算。
     */
    const val MAX_SECTION_CHARS = 380

    /**
     * 生成再锚定段。
     *
     * @param anchors 由 [StyleDrift.pickAnchors] 选出，**已按时间顺序排好**
     * @param contract 这一轮的尺寸约定（见 [StyleContract]）。null = 不写尺寸行
     * @return 空串 = 没有可用的锚点，调用方应当**什么都不追加**
     *   （"没有就不写"是本项目的一贯纪律：宁缺勿滥，塞一段空话只会稀释人格）
     */
    fun section(
        anchors: List<StyleDrift.Anchor>,
        contract: StyleContract? = null,
        maxChars: Int = MAX_SECTION_CHARS,
    ): String {
        if (anchors.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("## 她一直是这么说话的")
        sb.appendLine()
        sb.appendLine("下面这些是她自己以前的原话。不是范例、不是台词库 —— 是她这个人说话的样子。")
        sb.appendLine("照这个手感：长短、断句、有没有语气词、话说到哪儿就停了。不要照抄句子。")
        sb.appendLine()
        for (a in anchors) {
            sb.appendLine("「${a.text}」")
        }
        contract?.sizeLine()?.let {
            sb.appendLine()
            sb.appendLine(it)
        }
        contract?.familyLine()?.let {
            sb.appendLine(it)
        }

        val out = sb.toString().trimEnd()
        // 超限就丢尾部的锚点，保头部（头部的锚点是分数最高/形状最独特的）。
        // ⚠️ 不截断单条锚点 —— 半句话的样本会被学成"她说话爱断在半截"。
        if (out.length <= maxChars) return out
        val keep = anchors.take(maxOf(1, anchors.size - 1))
        return if (keep.size == anchors.size) out else section(keep, contract, maxChars)
    }

    /**
     * 给未来用的 prefill 候选：`她：「……」` 形式的半句开头。
     *
     * ⚠️ **当前没有接到任何请求里**，见类注释的两条理由。
     *    留着是因为它是最强的位置手段（模型必须从这个开头续写，
     *    续写的自由度只剩"怎么把这句说完"），等真机能验了再接。
     *
     * 选题材的纪律：只取**没有句末标点**的锚点 —— 它天然是"话没说完"的形状，
     * 拿来当开头不会和锚点原文打架。
     */
    fun prefillCandidate(anchors: List<StyleDrift.Anchor>): String? =
        anchors.lastOrNull { a ->
            a.text.isNotBlank() && a.text.lastOrNull() !in setOf('。', '！', '？', '…', '.', '!', '?')
        }?.let { "她：「${it.text}" }

    /**
     * 这一轮该不该插再锚定段。
     *
     * ═══ 三个触发条件 ═══
     * 1. **连续两条低于塑料线** —— 等确认了才动手，避免每轮都塞纸条（那会变成新模板）；
     * 2. **刚换模型** —— 新模型的默认口吻不是她的。它对"她"的全部认知只有
     *    提示词和这几条历史，所以必须把声音样本摆到它面前；
     * 3. **拿到了足够好的锚点** —— 没有合格样本时插一段"你要像人"是纯噪声。
     *
     * ⚠️ 条件 1 与 2 是**或**关系，理由不同：1 治漂移，2 治换模型的水土不服。
     *    用户原话是"特别是切换模型的时候"，2 就是冲那句去的。
     *
     * @param recentScores 最近若干轮的分数，升序
     * @param modelChanged 本会话中途换过模型/换过 provider
     * @param anchors 已选出的锚点（用 `pickAnchors(...).isNotEmpty()` 传进来）
     */
    fun shouldInject(
        recentScores: List<Int>,
        modelChanged: Boolean,
        anchors: List<StyleDrift.Anchor>,
    ): Boolean {
        if (anchors.isEmpty()) return false
        if (modelChanged) return true
        return StyleDrift.needsReanchor(recentScores)
    }
}

/**
 * **模型族** —— 只用来决定"多给一句什么软提示"，不参与任何判定。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 诚实标注：这是**粗归类**，且**未经真机验证**
 * ═══════════════════════════════════════════════════════════════
 *
 * 依据是各家公开的模型定位（推理模型会输出思考过程、指令微调模型爱分点），
 * 不是实测结论。认错族的代价很小 —— 最多多一句或少一句软提示；
 * 不认族一律落 [GENERIC]（不写任何东西）。
 *
 * 之所以还是留着：用户抱怨"**特别是切换模型的时候**"。换模型时口吻突变，
 * 是因为新模型的 RLHF 默认值不同，而这是唯一一个按族给的补偿手段。
 */
enum class ModelFamily(val label: String, val tendency: String) {

    /** 认不出来。不写任何软提示。 */
    GENERIC("通用", ""),

    /** 推理型：习惯把"我想了一下"也说出来。 */
    REASONING("推理型", "容易把心里盘算的过程也说出来"),

    /** 指令型：习惯把话说完整、分点、给结论。 */
    INSTRUCT_HEAVY("指令型", "容易把话说得太完整"),

    /** 创作型：习惯把话说漂亮、堆形容。 */
    CREATIVE("创作型", "容易把话说得太漂亮");

    companion object {

        /**
         * 按模型名猜族。**大小写不敏感，只看子串**。
         *
         * ⚠️ 顺序有意义：先判 REASONING，因为 `deepseek-r1`、`qwen3-thinking`
         *    这类名字同时含通用词根，先判通用就会漏掉。
         */
        fun of(modelId: String?): ModelFamily {
            val id = modelId?.lowercase()?.trim().orEmpty()
            if (id.isEmpty()) return GENERIC
            return when {
                REASONING_HINTS.any { id.contains(it) } -> REASONING
                CREATIVE_HINTS.any { id.contains(it) } -> CREATIVE
                INSTRUCT_HINTS.any { id.contains(it) } -> INSTRUCT_HEAVY
                else -> GENERIC
            }
        }

        private val REASONING_HINTS = listOf(
            "o1", "o3", "o4-mini", "reason", "thinking", "-r1", "r1-", "qwq", "deepseek-r",
            "gpt-5-thinking", "magistral",
        )

        private val CREATIVE_HINTS = listOf(
            "moonshot", "kimi", "hunyuan", "step-", "minimax", "abab", "spark", "writer", "novel",
        )

        private val INSTRUCT_HINTS = listOf(
            "gpt-4", "gpt-3", "gpt-4o", "claude", "gemini", "glm", "ernie", "qwen", "yi-",
            "doubao", "deepseek-chat", "deepseek-v3", "grok",
        )
    }
}

/**
 * **这一轮的尺寸约定** —— 从"含糊的风格要求"变成"能照着执行的数"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不复用 [PersonaVerbosity.promptHint]
 * ═══════════════════════════════════════════════════════════════
 *
 * `promptHint` 是**常驻**的人格段内容（每轮都在），本类是**触发时**的补充
 * （只在检测到漂移/换模型时才出现）。两者内容重叠是有意的：
 * 常驻段说"一般两三句"，本类在漂移时说"这一轮 90 字以内、说 1–2 条"。
 *
 * ⚠️ 精确到数字，是因为"简洁""别啰嗦"这类形容词对模型没有约束力 ——
 *    项目已引过 prompt-architects 的结论：**精确命名取代模糊禁令**。
 */
data class StyleContract(
    val verbosity: PersonaVerbosity,
    val family: ModelFamily,
    /** 这一轮的字数上限。**不是目标**，是天花板。 */
    val maxChars: Int,
    /** 说几条。真人回微信一般一条，偶尔两条。 */
    val messages: IntRange,
) {

    /** 尺寸行。写事实，不写"不要"。 */
    fun sizeLine(): String = "这一轮：说 ${messages.first}–${messages.last} 条，总共别超过 $maxChars 字。可以是半句话，可以只有一个「嗯」。"

    /**
     * 族特有的软提示。null = 不写（GENERIC）。
     *
     * ⚠️ 一律用**正面描述**（"只说结论那一句"），不用"不要说思考过程"。
     *    否定框定会被读成肯定 —— 这个项目已经为此改过两轮提示词。
     */
    fun familyLine(): String? = when (family) {
        ModelFamily.GENERIC -> null
        ModelFamily.REASONING -> "（说出口的只是最后那一句。盘算留在心里。）"
        ModelFamily.INSTRUCT_HEAVY -> "（话可以只有一句。不用交代完整。）"
        ModelFamily.CREATIVE -> "（说人话，不用挑词。）"
    }

    companion object {

        /**
         * 按说话长度档位造约定。
         *
         * 数值来源：
         * - BRIEF 40 字 ≈ 微信里一条短消息（"哦。那你去躺着。"= 8 字，留足余量）
         * - NORMAL 90 字 ≈ 两三句
         * - CHATTY 160 字 ≈ 四五句，仍低于 [HumanScore.MAX_CHARS]=120 的单条上限两倍以内
         */
        fun of(
            verbosity: PersonaVerbosity,
            family: ModelFamily = ModelFamily.GENERIC,
        ): StyleContract = when (verbosity) {
            PersonaVerbosity.BRIEF -> StyleContract(verbosity, family, 40, 1..2)
            PersonaVerbosity.NORMAL -> StyleContract(verbosity, family, 90, 1..2)
            PersonaVerbosity.CHATTY -> StyleContract(verbosity, family, 160, 1..3)
        }
    }
}
