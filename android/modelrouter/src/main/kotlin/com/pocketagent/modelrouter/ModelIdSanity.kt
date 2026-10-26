package com.pocketagent.modelrouter

/**
 * **模型 id 的合理性检查** —— 在发请求**之前**拦住必然失败的配置。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 2026-10-26 真机：这是一次真实的用户失败
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户配了 `glm5.3flash`（少了连字符），点"对话用它"，
 * 然后每次说话都得到：
 *
 *     请求被拒绝（400）：模型不存在，请检查模型代码。
 *
 * 而**设备上已经配了一个正确的 `glm-5.3-flash`** —— 只是没被选中。
 *
 * ⚠️ 为什么这件事值得写一个专门的检查：
 *    它**必然失败**。id 抄错的模型 100% 会 400，
 *    不存在"这次运气好能跑"的可能。而失败发生在**用户发消息之后** ——
 *    那时他已经等了一个网络往返，看到的是一句厂商原话。
 *
 * ⇒ 能在配置时就判死的错误，不该留到使用时才炸。
 *
 * ═══════════════════════════════════════════════════════════════
 *  判据的分寸：只报**高置信度**的错
 * ═══════════════════════════════════════════════════════════════
 *
 * ⚠️ 模型 id 的命名**没有统一标准**（`gpt-4o`、`deepseek-chat`、
 *    `Qwen/Qwen2.5-72B-Instruct`、`claude-3-5-sonnet-20241022`…），
 *    所以**绝不能"猜一个正确格式"然后判错** —— 那会误伤合法的自定义 id。
 *
 * 这里只做一件很窄的事：**当同一个厂商下已有一个"极像"的合法 id 时，
 * 指出这两者只差标点**。这是用户抄写失误的典型形态，
 * 也是唯一能高置信度判定的情况。
 */
object ModelIdSanity {

    /**
     * 一次检查的结论。
     *
     * @param suspicious 可疑的模型
     * @param looksLike 它"像是要写"的那个 id（已存在且格式规范）
     */
    data class Finding(
        val suspicious: ModelConfig,
        val looksLike: ModelConfig,
    ) {
        /** 给用户看的一句话 —— 要能直接指导他怎么改。 */
        fun message(): String =
            "「${suspicious.effectiveLabel}」的 id「${suspicious.modelId}」看起来是「${looksLike.modelId}」写错了位置。" +
                "这两个只差标点。模型 id 抄错的话，发消息时一定会失败（模型不存在）。"
    }

    /**
     * 找出配置里"疑似抄错"的模型。
     *
     * 判据（三条同时满足才报）：
     *  1. 两个 id **去掉所有非字母数字后完全相同**（`glm5.3flash` vs `glm-5.3-flash`）
     *  2. 但**原文不同**（否则就是同一个）
     *  3. 另一个 id **含有连字符/点/斜杠等分隔符**（说明它更像厂商的规范写法）
     *
     * ⚠️ 三条缺一不可：
     *    - 只满足 1、2 而无 3 → 可能是两个都"不规整"的合法 id，报出来是误伤
     *    - 只满足 1、3 而无 2 → 那是同一个，没必要报
     */
    fun check(models: List<ModelConfig>): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (a in models) {
            if (a.modelId.isBlank()) continue
            val aKey = normalize(a.modelId)
            if (aKey.isEmpty()) continue

            // ⚠️ **只看 a 自己有没有分隔符** —— 这是让结论单向的关键。
            //
            //   若两个 id 只差标点，我们只该说「没分隔符的那个可能写错了」，
            //   不该同时说「有分隔符的那个可能写错了」——
            //   后者会给出**两条互相矛盾的建议**
            //   （测试当场抓到：expected 1 but was 2）。
            //
            //   判据依据：厂商的模型 id **普遍带分隔符**
            //   （glm-5.3-flash / deepseek-chat / gpt-4o / claude-3-5-sonnet…）。
            //   所以"没有分隔符的那个"更可能是抄写时漏掉了。
            //
            //   ⚠️ 这仍然是**猜测**，所以文案措辞用「看起来是…写错了位置」，
            //      不用「必须改成」。
            if (hasSeparator(a.modelId)) continue

            val lookalike = models.firstOrNull { b ->
                b.id != a.id &&
                    b.modelId.isNotBlank() &&
                    normalize(b.modelId) == aKey &&
                    b.modelId != a.modelId &&
                    hasSeparator(b.modelId)
            } ?: continue

            findings += Finding(suspicious = a, looksLike = lookalike)
        }
        return findings
    }

    /**
     * 去掉所有非字母数字字符，并转小写。
     *
     * `glm-5.3-flash` → `glm53flash`
     * `glm5.3flash`   → `glm53flash`   ← 两者相同，这就是判据 1
     */
    private fun normalize(id: String): String =
        id.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 是否含**真正的**分隔符 —— 用来判断"哪个更像厂商规范写法"。
     *
     * ⚠️ 只看 `-` `_` `/`，**刻意不含 `.`**。
     *
     * 理由（真机抓到的）：`glm5.3flash` 里有一个点，但那不是厂商的分隔符 ——
     * 它是版本号里的小数点。把 `.` 算进判据会让 `glm5.3flash`
     * 被当成"已经规整"，于是**恰好漏掉真机上那个案例**。
     *
     * 厂商的模型 id 用连字符/下划线/斜杠分段：
     *   glm-5.3-flash / deepseek-chat / gpt-4o / claude-3-5-sonnet
     *   Qwen/Qwen2.5-72B-Instruct（斜杠分组织名）
     * 而点号几乎总是版本号的一部分，不承担分段作用。
     */
    private fun hasSeparator(id: String): Boolean =
        id.any { it == '-' || it == '_' || it == '/' }

    /**
     * 这次配置里，**真正会被用来聊天**的那个模型是否可疑。
     *
     * ★ 只有这个才会导致用户"发消息必失败"。其它档位的可疑配置
     *   只是"将来可能踩坑"，不该在聊天前拦。
     */
    fun suspiciousChatModel(models: List<ModelConfig>, chatModel: ModelConfig?): Finding? {
        if (chatModel == null) return null
        return check(models).firstOrNull { it.suspicious.id == chatModel.id }
    }
}
