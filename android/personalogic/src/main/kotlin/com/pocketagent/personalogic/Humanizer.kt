package com.pocketagent.personalogic

/**
 * **聊久了怎么就变 AI 了** —— 一份可测的"去味"规则集。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户的原话
 * ═══════════════════════════════════════════════════════════════
 *
 * "现在的角色对话短期来看会很拟真，但是聊久后还是会有一股 AI 味。"
 *
 * 这句话描述的是一个**可诊断**的现象，不是玄学：
 * 前几轮模型还在演人，轮次一多，提示词里的人设被对话历史稀释，
 * 它会**回落到自己的默认习惯** —— 而默认习惯就是 AI 腔。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 具体是哪几种回落（真机上逐条观察到）
 * ═══════════════════════════════════════════════════════════════
 *
 * | # | 症状 | 为什么算 AI 味 |
 * |---|---|---|
 * | 1 | 每轮都分点、加粗小标题 | 人发微信不分点 |
 * | 2 | 结尾追问"还有什么需要吗" | 客服话术，不是朋友 |
 * | 3 | 反复用同一句口头禅 | 人会换说法；重复是模板的痕迹 |
 * | 4 | 每轮长度都差不多 | 真人的长短由情绪决定，不由格式决定 |
 * | 5 | 无论聊多久都还是"刚认识"的客气 | 关系不推进 = 没有记忆在起作用 |
 * | 6 | 主动解释自己为什么这么说 | 人不会给自己的话加旁白 |
 *
 * ⚠️ 本文件只做**能离线验证**的那部分：诊断 + 生成规则。
 *    规则写进提示词之后效果真不真只有真机能证；但"有没有写进去"、
 *    "判据对不对"是可以钉住的。
 */
object Humanizer {

    /** 一条诊断结论。 */
    data class Finding(val code: Code, val detail: String)

    enum class Code(val label: String) {
        /** 连续几轮都在分点 / 加小标题 */
        STRUCTURED("在分点"),

        /** 结尾在追问服务性的话 */
        SERVICE_TAIL("客服尾巴"),

        /** 同一句口头禅反复出现 */
        CATCHPHRASE_LOOP("口头禅循环"),

        /** 长度几乎一样 */
        FLAT_LENGTH("长度一样"),

        /** 关系阶段一直不推进 */
        STUCK_RELATION("关系停滞"),

        /** 给自己的话加旁白 */
        SELF_NARRATION("自述旁白"),
    }

    /**
     * 诊断最近几轮的回复。
     *
     * @param replies 最近的助理回复（**时间升序**，末尾是最新的）
     * @param stage 当前关系阶段
     * @param turnsSinceStageChange 距上次阶段推进过了多少轮
     * @return 发现的 AI 味来源；空表 = 这一阵子挺像人
     */
    fun diagnose(
        replies: List<String>,
        stage: RelationshipStage,
        turnsSinceStageChange: Int,
    ): List<Finding> {
        if (replies.isEmpty()) return emptyList()
        val out = mutableListOf<Finding>()

        // ① 分点：出现两轮以上才算 — 偶尔一次可以说是"分条说清一件事"
        val structured = replies.count { looksStructured(it) }
        if (structured >= 2) {
            out += Finding(Code.STRUCTURED, "最近 ${replies.size} 轮里有 $structured 轮在分点")
        }

        // ② 客服尾巴：只看最后一条（这是"当下该改"的）
        replies.lastOrNull()?.let { last ->
            SERVICE_TAILS.firstOrNull { last.contains(it) }?.let {
                out += Finding(Code.SERVICE_TAIL, "结尾出现了「$it」")
            }
        }

        // ③ 口头禅循环：同一句出现 3 次以上
        val counts = HashMap<String, Int>()
        for (r in replies) {
            for (c in CATCHPHRASE_CANDIDATES) {
                if (r.contains(c)) counts[c] = (counts[c] ?: 0) + 1
            }
        }
        counts.entries.firstOrNull { it.value >= 3 }?.let {
            out += Finding(Code.CATCHPHRASE_LOOP, "「${it.key}」在 ${it.value} 轮里都出现了")
        }

        // ④ 长度扁平：全都落在同一个窄区间
        if (replies.size >= 4) {
            val lens = replies.map { it.length }
            val avg = lens.average()
            val spread = (lens.max() - lens.min()).toDouble()
            if (avg > 0 && spread / avg < 0.15) {
                out += Finding(
                    Code.FLAT_LENGTH,
                    "长度几乎一样（${lens.min()}~${lens.max()} 字），像模板",
                )
            }
        }

        // ⑤ 关系停滞：聊了很多轮却一直在初识
        if (stage == RelationshipStage.STRANGER && turnsSinceStageChange >= STUCK_TURNS) {
            out += Finding(
                Code.STUCK_RELATION,
                "已经 $turnsSinceStageChange 轮还停在「${stage.label}」",
            )
        }

        // ⑥ 自述旁白
        if (replies.any { r -> SELF_NARRATION.any { r.contains(it) } }) {
            out += Finding(Code.SELF_NARRATION, "在解释自己为什么这么说")
        }
        return out
    }

    /**
     * 生成写进提示词的**去味规则**。
     *
     * ★ 只在真的诊断出问题时才写那几条。
     *   一律全写会让提示词变成一张长长的禁令清单 ——
     *   模型在长禁令下会变得僵硬，那本身也是一种 AI 味。
     */
    fun promptSection(
        findings: List<Finding>,
        stage: RelationshipStage,
        verbosity: PersonaVerbosity,
    ): String {
        val targeted = findings.distinctBy { it.code }.mapNotNull { f ->
            when (f.code) {
                Code.STRUCTURED ->
                    "- **不要分点、不要小标题、不要加粗**。你在发微信，不是在写报告。"
                Code.SERVICE_TAIL ->
                    "- 结尾**不要**问「还有什么需要」「还有别的吗」。说完就停。"
                Code.CATCHPHRASE_LOOP ->
                    "- 你最近老用同一句话。换一种说法，哪怕说得笨一点。"
                Code.FLAT_LENGTH ->
                    "- 你的回复长度太均匀了。有时候一个字就够（「嗯」「哦」），有时候多说两句。"
                Code.SELF_NARRATION ->
                    "- **不要**解释你为什么这么说。人不会给自己的话加旁白。"
                // 关系推进不靠单句禁令，见下面那段
                Code.STUCK_RELATION -> null
            }
        }
        val stuck = findings.any { it.code == Code.STUCK_RELATION }
        if (targeted.isEmpty() && !stuck) return ""

        return buildString {
            appendLine("## 说话方式（这一轮请特别注意）")
            targeted.forEach { appendLine(it) }
            if (stuck) {
                appendLine(
                    "- 你们聊了有一阵子了，关系不该还停在「${stage.label}」。" +
                        "该有的熟络、该开的玩笑、该有的不用解释的默契，都可以有了。",
                )
            }
            append("- 长度：${verbosity.promptHint}")
        }.trimEnd()
    }

    // ── 判据 ──────────────────────────────────────────────────

    /** 像报告：有列表记号或小标题。 */
    fun looksStructured(text: String): Boolean =
        STRUCTURE_MARKERS.any { text.contains(it) } ||
            Regex("^\\s*\\d+[.、)]", RegexOption.MULTILINE).containsMatchIn(text)

    /** 多少轮不推进关系就算"停滞"。 */
    private const val STUCK_TURNS = 40

    private val STRUCTURE_MARKERS = listOf(
        "**", "##", "•", "- ", "1. ", "第一，", "首先，", "其次，",
    )

    private val SERVICE_TAILS = listOf(
        "还有什么需要", "还有什么可以帮", "还有其他问题", "还有别的吗",
        "需要我帮你", "有什么我能帮", "随时告诉我", "希望对你有帮助",
    )

    private val CATCHPHRASE_CANDIDATES = listOf(
        "说实话", "讲真", "话说回来", "不得不说", "其实吧", "总的来说",
    )

    private val SELF_NARRATION = listOf(
        "之所以这么说", "我这么说是", "我这样回答", "我的意思是说",
    )
}
