package com.pocketagent.legacy

/**
 * **冲突调度器** —— 决定什么时候闹矛盾、以后怎么提、提多少次。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 整个机制的核心难题：怎么"吵得对"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话拆成可执行的约束有六条：
 *
 *  1. **只有重度交互才触发** —— 普通用户不该莫名其妙被怼
 *  2. **通过大模型推理出隔阂** —— 不是硬编码的对白
 *  3. **以后会一定提及这次矛盾** —— 它得"记得"
 *  4. **"你以前不是这样的"要降低提及次数** —— 用户能干预
 *  5. **只在相关的矛盾中才提** —— 不相关的话题硬提 = OOC
 *  6. **不能让人觉得 OOC** —— 要像"那个人生前就会犯的事"
 *
 * ═══════════════════════════════════════════════════════════════
 *  算法设计：三条独立的闸门
 * ═══════════════════════════════════════════════════════════════
 *
 * 第 1 道 · **够不够熟**（[shouldTrigger]）
 *   重度和普通的区别不是"聊了多少句"，而是**有没有深度事件**。
 *   一个人跟你聊了一百句天气，不会跟你吵架；聊了三句心里话，
 *   才可能踩到彼此的雷。
 *   ⇒ 判据 = 关系阶段 + 深度事件数 + 交互天数，三者同时满足。
 *
 * 第 2 道 · **该不该吵**（[shouldTrigger] 的冷却）
 *   ⚠️ 这条最容易做错：没有冷却的话，用户痛快地聊一晚会被怼三次 ——
 *      那不是在模拟人，那是坏掉的机器。
 *   ⇒ 每次冲突后有**冷却期**，且冷却期随冲突次数**递增**
 *      （越到后面越难吵起来，因为"该磨合的都磨合过了"）。
 *
 * 第 3 道 · **提不提得起**（[mentionProbability]）
 *   用户说的"提及次数降低"不是一个开关，是一条**衰减曲线**：
 *   - 刚吵完：很容易被相关话题勾起（权重高）
 *   - 提过一次之后：**显著下降**（用户已经知道了）
 *   - 用户说"你以前不是这样的"：**再乘一个惩罚系数**
 *   - 超过记仇期：降到接近 0（但不清零 —— 人会偶尔想起很久以前的事）
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么"相关话题"是硬门槛而不是加权
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户明确要求"只在一些与之相关的矛盾中才会提及"。
 * 这句话的正确实现是**先筛相关性，再算概率** —— 而不是
 * "相关就概率高、不相关就概率低"。
 *
 * 因为低概率乘以足够多的轮次，早晚会命中。而一次不相关的硬提，
 * 就足以让用户觉得"这不是他，这是个记仇的机器"。
 *
 * ⇒ 不相关 = **概率为 0**，不是"低"。
 */
object ConflictScheduler {

    // ═══════════════════════════════════════════════════════════
    //  第 1 道闸：够不够熟
    // ═══════════════════════════════════════════════════════════

    /** 触发冲突所需的最少交互天数。**7 天** —— 一周都没到，谈不上"隔阂"。 */
    const val MIN_DAYS_FOR_CONFLICT = 7

    /** 触发冲突所需的最少对话轮数。**60 轮** —— 这是"重度"的量化。 */
    const val MIN_TURNS_FOR_CONFLICT = 60

    /**
     * 判断**现在能不能**发生一次新冲突。
     *
     * @param stats 当前的交互统计
     * @param flaws 用户写的毛病
     * @param existing 已经发生过的冲突
     * @param nowMs 当前时间
     * @return 可以触发时返回**具体该吵哪条**；否则 null
     */
    fun shouldTrigger(
        stats: InteractionStats,
        flaws: List<Flaw>,
        existing: List<Conflict>,
        nowMs: Long,
    ): Flaw? {
        // ── 闸 1：够不够熟 ──────────────────────────────────
        if (stats.daysKnown < MIN_DAYS_FOR_CONFLICT) return null
        if (stats.totalTurns < MIN_TURNS_FOR_CONFLICT) return null
        if (stats.deepEventCount < MIN_DEEP_EVENTS) return null

        // ── 闸 2：冷却 ──────────────────────────────────────
        val last = existing.maxByOrNull { it.atMs }
        if (last != null) {
            val cooldownMs = cooldownFor(existing.size) * MS_PER_DAY
            if (nowMs - last.atMs < cooldownMs) return null
        }

        // ── 闸 3：不重复吵同一条（除非隔了很久）─────────────
        val eligible = flaws.filter { flaw ->
            // ⚠️ 伤人的毛病**永不自动触发** —— 见下方注释
            if (flaw.hurtful) return@filter false

            val times = existing.count { it.flawLabel == flaw.label }
            if (times == 0) return@filter true

            // ══════════════════════════════════════════════════
            //  同一条毛病要再吵，需要**更长的**冷却
            // ══════════════════════════════════════════════════
            //
            // ★ 为什么要区分"换一条毛病吵"和"同一条再吵"：
            //   前者是"这个人有不同的面"，后者是"他一直在同一件事上挑刺"。
            //   后者让人烦，前者才像人。
            //
            // ⚠️ 倍数取 1.5 而不是 2：
            //   用户可能只写了一条毛病，取 2 会让"一年只能吵两次" ——
            //   那样这个机制在单毛病场景下几乎等于不存在。
            //   1.5 既拉开了间距，又不至于让机制失效。
            val lastSame = existing.filter { it.flawLabel == flaw.label }.maxOf { it.atMs }
            nowMs - lastSame >= (cooldownFor(existing.size) * SAME_FLAW_MULTIPLIER).toLong() * MS_PER_DAY
        }
        if (eligible.isEmpty()) return null

        // ── 挑一条：权重高的更可能，但不是纯随机 ──────────────
        // 用时间做种子，保证"同样状态下重复调用得到同样结果"（可测试）
        val totalWeight = eligible.sumOf { it.weight }
        if (totalWeight <= 0) return eligible.first()
        var pick = positiveMod((nowMs / MS_PER_DAY).toInt(), totalWeight)
        for (f in eligible) {
            pick -= f.weight
            if (pick < 0) return f
        }
        return eligible.last()
    }

    /**
     * 第 n 次冲突要等多少天。
     *
     * ★ 递增的理由：**该磨合的都磨合过了**。
     *   一对相处十年的人不会每两周吵一次架 —— 如果这个数字不涨，
     *   长期用户的体验会变成"定期被怼"。
     */
    fun cooldownFor(conflictCount: Int): Int = when {
        conflictCount <= 0 -> 14      // 第一次：半个月
        conflictCount == 1 -> 30      // 第二次：一个月
        conflictCount == 2 -> 60
        else -> 120                   // 三次以后：四个月
    }

    /** 至少要有几个深度事件 —— 没有深度事件 = 还没交心 = 不该有隔阂。 */
    const val MIN_DEEP_EVENTS = 2

    // ═══════════════════════════════════════════════════════════
    //  第 3 道闸：提不提得起
    // ═══════════════════════════════════════════════════════════

    /**
     * 这次该不该提起某次旧冲突。
     *
     * @param conflict 那次冲突
     * @param currentTopic 当前聊的话题（用来判相关性）
     * @param friction 用户表达过的"你不像他了"的程度（见 [FrictionLedger]）
     * @param nowMs 当前时间
     * @param grudgeDays 那个人的记仇期
     * @return 0.0 = 绝不提；1.0 = 一定会提
     */
    fun mentionProbability(
        conflict: Conflict,
        currentTopic: String,
        friction: FrictionSignal,
        nowMs: Long,
        grudgeDays: Int,
    ): Double {
        // ── 硬门槛：不相关 = 0 ──────────────────────────────
        // ⚠️ 用户明确要求"只在相关的矛盾中才提"。
        //    这里必须 return 0 而不是"给个低概率" ——
        //    低概率乘以足够多轮次，早晚命中，而那一次就足以毁掉可信度。
        if (!isRelated(conflict.topic, currentTopic)) return 0.0

        // ── 基数：刚吵完最高 ────────────────────────────────
        val daysSince = ((nowMs - conflict.atMs) / MS_PER_DAY).toInt().coerceAtLeast(0)
        val base = when {
            daysSince <= 7 -> 0.7      // 一周内：很容易勾起
            daysSince <= 30 -> 0.4
            daysSince <= grudgeDays -> 0.2
            // ⚠️ 超过记仇期**不清零**：人会偶尔想起很久以前的事。
            //    但降到很低，且不再随轮次累积。
            else -> 0.05
        }

        // ── 提过一次就显著下降 ──────────────────────────────
        // ★ 这一条直接对应用户那句"提及次数降低"。
        //   逻辑：第一次提起是"提醒他想起来"，之后再说就是唠叨。
        val mentionPenalty = when (conflict.mentionCount) {
            0 -> 1.0
            1 -> 0.35
            2 -> 0.15
            else -> 0.05
        }

        // ── 用户说"你以前不是这样的" → 再乘惩罚 ─────────────
        // ⚠️ 这是用户唯一的干预手段，必须**真的有效**：
        //    乘一个明显小于 1 的系数，而不是象征性减少。
        val frictionPenalty = when {
            friction.level == FrictionLevel.STRONG -> 0.1
            friction.level == FrictionLevel.MILD -> 0.4
            else -> 1.0
        }

        return (base * mentionPenalty * frictionPenalty).coerceIn(0.0, 1.0)
    }

    /**
     * 当前话题与那次冲突**是否相关**。
     *
     * ★ 判据用**关键词重叠**而不是语义相似度：
     *   语义相似度要调模型（多一次请求、多一份成本），
     *   而"相关"在这里的要求很低 —— 宁可不提，也不要错误地提。
     *   关键词重叠已经足够筛掉大部分不相关，剩下的由概率控制。
     */
    fun isRelated(conflictTopic: String, currentTopic: String): Boolean {
        if (conflictTopic.isBlank() || currentTopic.isBlank()) return false

        // 去掉常见虚词后取双字词做粗重叠
        val a = keywords(conflictTopic)
        val b = keywords(currentTopic)
        if (a.isEmpty() || b.isEmpty()) return false
        return a.any { ka -> b.any { kb -> ka.contains(kb) || kb.contains(ka) } }
    }

    /** 抽 2 字以上的连续中文/字母片段作为关键词。 */
    private fun keywords(text: String): Set<String> {
        val out = mutableSetOf<String>()
        val buf = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit()) {
                buf.append(c)
            } else {
                if (buf.length >= 2) out += buf.toString()
                buf.setLength(0)
            }
        }
        if (buf.length >= 2) out += buf.toString()
        // 长片段再切成双字，提高重叠命中率
        val doubled = out.flatMap { w ->
            if (w.length <= 2) listOf(w) else (0..w.length - 2).map { w.substring(it, it + 2) }
        }.toSet()
        return doubled - STOP_WORDS
    }

    /**
     * 虚词表 —— 它们会让"相关"变得过于宽松。
     *
     * ⚠️ 这一条很关键：如果不去虚词，「我」「你」「的」这类
     *    会在任何两句话里都重叠，于是"相关"恒为真 —— 硬门槛就失效了。
     */
    private val STOP_WORDS = setOf(
        "我们", "你们", "他们", "这个", "那个", "什么", "怎么", "因为", "所以",
        "但是", "如果", "就是", "不是", "可以", "已经", "还是", "一个",
    )

    // ═══════════════════════════════════════════════════════════
    //  脾气推断
    // ═══════════════════════════════════════════════════════════

    /**
     * 从用户写的毛病，推出这个人的"脾气参数"。
     *
     * ★ 这是"不 OOC"的关键一步：脾气**不是系统给的**，
     *   而是从用户描述里读出来的。用户写得越具体，它越像。
     *
     * @param flaws 用户写的毛病
     * @param makeupStyle 用户选的收场方式
     */
    fun temperamentOf(flaws: List<Flaw>, makeupStyle: MakeupStyle): Temperament {
        if (flaws.isEmpty()) {
            // 没写毛病 ⇒ 一个很不容易起冲突的人。
            // ⚠️ 这是**保守**的选择：没写就是不希望他吵架。
            return Temperament(irritability = 10, grudgeDays = 3, makeupStyle = makeupStyle)
        }
        val avg = flaws.map { it.weight }.average().toInt().coerceIn(0, 100)
        // 记仇期与易怒程度正相关，但设上限 —— 记一辈子仇的角色会变得不可用
        val grudge = (avg / 10 * 3).coerceIn(3, 30)
        return Temperament(irritability = avg, grudgeDays = grudge, makeupStyle = makeupStyle)
    }

    /**
     * 同一条毛病再次触发的冷却倍数。
     *
     * ⚠️ 取 1.5 而不是 2：用户可能只写了一条毛病，
     *    取 2 会让「一年只能吵两次」—— 那个机制在单毛病场景下几乎不存在。
     */
    private const val SAME_FLAW_MULTIPLIER = 1.5

    private const val MS_PER_DAY = 86_400_000L

    private fun positiveMod(v: Int, m: Int): Int {
        if (m <= 0) return 0
        val r = v % m
        return if (r < 0) r + m else r
    }
}

/**
 * 交互统计 —— 判断"够不够熟"的输入。
 *
 * ⚠️ 刻意**不含**"聊了多少字"这类指标：字数和关系的深浅无关，
 *    一个人可以打一万字天气，也不会跟你交心。
 */
data class InteractionStats(
    /** 认识多少天（首次交互到现在） */
    val daysKnown: Int,
    /** 累计对话轮数 */
    val totalTurns: Int,
    /** 深度事件数（来自 `:personalogic` 的 `DeepEvent`） */
    val deepEventCount: Int,
) {
    init {
        require(daysKnown >= 0) { "天数不能为负：$daysKnown" }
        require(totalTurns >= 0) { "轮数不能为负：$totalTurns" }
    }
}

/**
 * 用户的"摩擦信号" —— 他说过"你以前不是这样的"这类话的程度。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这是用户对"它像不像"的唯一干预手段
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户原话："当用户说「你以前不是这样的」时，会把提及次数降低。"
 *
 * 它的语义不是"关掉冲突"，而是"**他提得太多，收敛一点**"。
 * 所以它只影响**提及概率**，不影响冲突是否已经发生 ——
 * 已经发生过的事不能被撤销，那才是诚实的。
 *
 * @param level 当前摩擦程度
 * @param atMs 最近一次出现的时间（用于衰减 —— 用户说了很久之后，不该永久压着）
 */
data class FrictionSignal(
    val level: FrictionLevel = FrictionLevel.NONE,
    val atMs: Long = 0,
) {
    companion object {
        /** 空信号 —— 用户从没抱怨过"不像"。 */
        val NONE = FrictionSignal()
    }
}

/**
 * 摩擦程度。**三档，不是连续值** —— 让判定可解释。
 */
enum class FrictionLevel {
    /** 用户没抱怨过 */
    NONE,

    /** 用户表达过一次不满（"有点不像他"） */
    MILD,

    /** 用户明确说过"你以前不是这样的" */
    STRONG,
    ;

    companion object {
        /**
         * 从用户这句话里判断摩擦程度。
         *
         * ⚠️ 判据刻意保守：**只有明确指向"它不像那个人"才算**。
         *    普通的"我不高兴"不算 —— 那可能是在说别的事。
         */
        fun of(userSaid: String): FrictionLevel {
            val t = userSaid
            val strong = listOf(
                "你以前不是这样", "你以前不这样", "不像他", "不像她",
                "你不是他", "你不是她", "他不会这样", "她不会这样",
                "ooc", "崩人设", "不像了",
            )
            if (strong.any { t.contains(it) }) return STRONG

            val mild = listOf("有点不像", "不太像", "感觉怪", "不像以前")
            if (mild.any { t.contains(it) }) return MILD

            return NONE
        }
    }

    /** 合并两次信号，取更重的那一个。 */
    fun heavierThan(other: FrictionLevel): FrictionLevel =
        if (ordinal >= other.ordinal) this else other
}
