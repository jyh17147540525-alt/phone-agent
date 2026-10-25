package com.pocketagent.legacy

/**
 * **毛病解析** —— 把用户在界面上写的文本，变成 [Flaw]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么解析要单独一个对象
 * ═══════════════════════════════════════════════════════════════
 *
 * 输入是**人随手写的**，必然不规整：
 *  - 「爱较真：一说到工作就数落我」← 标准
 *  - 「爱较真 一说到工作就数落我」← 少了冒号
 *  - 「一说到工作就要数落我」← 没写标签
 *  - 「爱较真」← 只有标签
 *
 * ⚠️ 判据：**宁可收下也不丢弃**。
 *    用户认真写下的一条毛病，如果因为格式不对被静默丢掉，
 *    那这个机制就少了一块燃料 —— 而他会发现"我写了但它没反应"。
 *
 *    所以最后两种都能用：只有标签时，把标签当表现（信息少但可用）；
 *    只有表现时，用前 8 个字当标签（够在对话里被指认）。
 */
object FlawParser {

    /** 只有表现的条目的标签长度上限。太长会被当成一整句话塞进提示词。 */
    private const val FALLBACK_LABEL_LEN = 8

    /**
     * 解析一行。
     *
     * @return null = 这行是空的（或无意义），跳过
     */
    fun parseLine(raw: String): Flaw? {
        val line = raw.trim()
        if (line.isEmpty()) return null
        // 排除用户可能写下的分隔线 / 项目符号
        val cleaned = line.trimStart('-', '·', '*', '•').trim()
        if (cleaned.isEmpty()) return null

        // ── 带分隔符：标签：表现 ────────────────────────────
        // 支持全角冒号、半角冒号、破折号
        for (sep in listOf("：", ":", "——", "—")) {
            val at = cleaned.indexOf(sep)
            if (at > 0 && at < cleaned.length - 1) {
                val label = cleaned.substring(0, at).trim()
                val detail = cleaned.substring(at + sep.length).trim()
                if (label.isNotEmpty() && detail.isNotEmpty()) {
                    return Flaw(label = label, detail = detail)
                }
            }
        }

        // ── 没分隔符：整行当表现，取前缀当标签 ───────────────
        // ⚠️ 这一步是"不丢"的关键：用户写了就得用上。
        val label = cleaned.take(FALLBACK_LABEL_LEN)
        return Flaw(label = label, detail = cleaned)
    }

    /**
     * 解析多行。
     *
     * ⚠️ 单行解析失败**不影响其他行** —— 用户写十条有一条格式怪，
     *    不该让九条都失效。
     */
    fun parse(lines: List<String>): List<Flaw> =
        lines.mapNotNull { parseLine(it) }

    /**
     * 把解析结果转回界面显示的文本。
     *
     * ★ 用于「编辑已有角色」时回填 —— 用户再进去看到的是他原来写的格式。
     */
    fun format(flaws: List<Flaw>): String =
        flaws.joinToString("\n") { "${it.label}：${it.detail}" }
}

/**
 * 把毛病与冲突组装成**完整的一段给模型的指令**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是 `:legacy` 对外的唯一入口
 * ═══════════════════════════════════════════════════════════════
 *
 * 上层（`:app`）只需要：
 *  1. 把用户写的毛病文本交给它
 *  2. 把当前交互统计 / 当前话题交给它
 *  3. 拿到一段可以直接拼进系统提示词的文本
 *
 * 不需要知道调度、衰减、相关性判定这些细节。
 */
object LegacyConflictPort {

    /**
     * 组装这一轮该注入的冲突相关内容。
     *
     * @param flawsRaw 用户写的毛病原文（每行一条）
     * @param makeupStyle 用户选的和好方式
     * @param stats 交互统计
     * @param existing 已发生的冲突
     * @param currentTopic 当前用户这句话（用来判相关性）
     * @param friction 摩擦信号
     * @param nowMs 当前时间
     * @param userName 用户称呼
     * @return 该拼进提示词的文本。**空字符串 = 这一轮什么都不该发生**
     */
    fun buildInjection(
        flawsRaw: List<String>,
        makeupStyle: MakeupStyle,
        stats: InteractionStats,
        existing: List<Conflict>,
        currentTopic: String,
        friction: FrictionSignal,
        nowMs: Long,
        userName: String?,
    ): String {
        val flaws = FlawParser.parse(flawsRaw)
        if (flaws.isEmpty()) return ""

        val temperament = ConflictScheduler.temperamentOf(flaws, makeupStyle)
        val sb = StringBuilder()

        // ── 常驻：让模型知道这个人有这些毛病 ──────────────────
        sb.append(ConflictScript.flawsBrief(flaws))

        // ── 用户表达了"不像" → 先给这一句 ────────────────────
        if (friction.level != FrictionLevel.NONE) {
            sb.append("\n").append(ConflictScript.onFriction(temperament))
        }

        // ── 这一轮该不该提起旧账 ──────────────────────────────
        // ★ 遍历已发生的冲突，**取概率最高的那一个**。
        //   不叠加 —— 一次对话同时提两件旧账是灾难。
        val best = existing
            .filter { !it.resolved || it.mentionCount < MAX_MENTIONS }
            .map { c ->
                c to ConflictScheduler.mentionProbability(
                    conflict = c,
                    currentTopic = currentTopic,
                    friction = friction,
                    nowMs = nowMs,
                    grudgeDays = temperament.grudgeDays,
                )
            }
            .filter { (_, p) -> p > 0.0 }
            .maxByOrNull { (_, p) -> p }

        if (best != null) {
            // ⚠️ 用概率做门槛，但判据是**确定性的**（同一个 p 同样处理）：
            //    这里不掷骰子，而是要求 p 超过阈值才注入。
            //    理由：掷骰子会让同一个状态下重复调用得到不同结果 ——
            //    测试钉不住，且用户会发现"同一句话问两次反应不同"。
            if (best.second >= MENTION_THRESHOLD) {
                sb.append("\n").append(
                    ConflictScript.recalling(best.first, temperament),
                )
            }
        }

        // ── 这一轮该不该发生**新**冲突 ────────────────────────
        val newFlaw = ConflictScheduler.shouldTrigger(stats, flaws, existing, nowMs)
        if (newFlaw != null) {
            sb.append("\n").append(
                ConflictScript.opening(newFlaw, temperament, userName),
            )
        }

        return sb.toString().trim()
    }

    /**
     * 提及概率的**注入阈值**。
     *
     * ★ 为什么是"阈值"而不是"按概率随机"：
     *   随机会让同一个状态下重复调用得到不同结果。那有两个后果：
     *   ① 用户发现同一句话问两次反应不同 → 觉得是随机机械
     *   ② 无法用测试钉住任何行为
     *
     *   用阈值则完全确定：概率够高就提，不够就不提。
     *   衰减仍在起作用（概率会降低），只是判定变成确定的。
     */
    private const val MENTION_THRESHOLD = 0.3

    /** 同一次冲突最多主动提几次。太多就成了唠叨。 */
    private const val MAX_MENTIONS = 3
}
