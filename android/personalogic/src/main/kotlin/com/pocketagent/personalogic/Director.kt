package com.pocketagent.personalogic

/**
 * **场记** —— 那个"读这一轮对话、给角色一点刺激"的 AI 该输出什么。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：为什么它输出的是**事件**，不是状态值
 * ═══════════════════════════════════════════════════════════════
 *
 * 这是整个设计里最容易做错的一处。
 *
 * **错的**：
 * ```json
 * { "cortisol": 72, "energy": 35 }
 * ```
 * ⇒ 两份真相（AI 一份、状态机一份），必然矛盾。而且每轮由模型
 *   重新推断"我现在多累"，同一个用户行为在不同轮得到不同结果 ——
 *   **角色会显得精神分裂**，且无法复现、无法测试。
 *
 * **对的**：
 * ```json
 * { "event": "USER_SICK", "reason": "用户说自己发烧了" }
 * ```
 * ⇒ 由 `HormoneEngine` / `PhysiologyEngine` 决定"这具体意味着什么"。
 *   状态演化仍然是确定性的、可测试的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 第二个约束：事件必须**有由头**
 * ═══════════════════════════════════════════════════════════════
 *
 * 项目既有纪律："没有由头的情绪就是随机情绪，而随机情绪 = 崩人设。"
 *
 * ⇒ [DirectorEvent.reason] 是**必填**的，且必须是**可复述的具体事**
 *   （"用户提到他今晚没睡"），不是"检测到压力"。
 *   角色被问起时要能说出这个由头，否则它会答得莫名其妙。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 第三个约束：幅度受控，且**限频**
 * ═══════════════════════════════════════════════════════════════
 *
 * 一个每轮都注入"她想起伤心事"的导演，会让角色变成连续剧。
 * ⇒ 见 [DirectorPolicy]，有每日上限与冷却。
 */
@kotlinx.serialization.Serializable
data class DirectorOutput(
    /** 这一轮识别出的事件（0..N 个，通常 0 或 1 个） */
    val events: List<DirectorEvent> = emptyList(),
    /**
     * 这一轮冒出的小事（环境/氛围类）。
     *
     * ⚠️ 与 [events] 分开：事件改**状态**，小事只改**这一句的写法**。
     *    混在一起会让"窗外下雨"变成"所以她很抑郁"。
     */
    val smallMoments: List<String> = emptyList(),
    /**
     * 值得记成 FACT 的事（用户说的、角色说过的既成事实）。
     *
     * ⚠️ 这些**不会自动进世界书** —— 要用户确认（见 `WorldBook.withFact`）。
     */
    val proposedFacts: List<ProposedFact> = emptyList(),
) {

    val isEmpty: Boolean
        get() = events.isEmpty() && smallMoments.isEmpty() && proposedFacts.isEmpty()
}

/**
 * 一个事件。
 *
 * @param kind 事件类型（闭集）
 * @param reason **可复述的具体由头**。不能是"检测到压力"这种系统语言
 */
@kotlinx.serialization.Serializable
data class DirectorEvent(
    val kind: DirectorEventKind,
    val reason: String,
) {
    init {
        require(reason.isNotBlank()) {
            "事件必须有由头 —— 没有由头的情绪就是随机情绪，而随机情绪 = 崩人设"
        }
    }
}

/**
 * 事件类型的**闭集**。
 *
 * ⚠️ 闭集是刻意的：模型输出一个没见过的类型必须**被丢弃并记下原因**，
 *    而不是硬塞进最接近的那一类（那样会让"冷漠"和"生气"混成一体）。
 */
enum class DirectorEventKind(val label: String) {
    // ── 关于用户 ────────────────────────────────
    /** 用户提到自己不舒服 / 生病 / 受伤 */
    USER_UNWELL("用户身体不适"),

    /** 用户说了让角色在意的事（升职、失恋、被辞退…） */
    USER_BIG_NEWS("用户有重要的事"),

    /** 用户明显在撒娇 / 求关注 */
    USER_CLINGY("用户在撒娇"),

    /** 用户在敷衍（很短、隔很久才回） */
    USER_DISTANT("用户在敷衍"),

    // ── 关于角色自己 ────────────────────────────
    /** 用户的话让角色想起自己的事 */
    REMINDED("想起了自己的事"),

    /** 这一轮被冷到了（用户说了伤人的话） */
    HURT("被伤到了"),

    /** 这一轮被暖到了 */
    WARMED("被暖到了"),

    // ── 关于世界 ────────────────────────────────
    /** 世界书里的某个设定被触碰了（该被想起来） */
    WORLD_TOUCHED("触及世界设定"),

    /** 外界发生了变化（天气、时间、节日） */
    OUTSIDE_CHANGED("外界有变化"),
}

/**
 * 值得记成世界书 FACT 的事。
 *
 * ⚠️ 它带 `id` 与 `keys`：模型不只输出"内容"，还要给出**触发词**，
 *    否则记下来的事实永远检索不到（这是世界书最常见的死法）。
 */
@kotlinx.serialization.Serializable
data class ProposedFact(
    val id: String,
    val title: String,
    /** 至少一个触发词 —— 否则这条会被 `WorldEntry` 拒绝 */
    val keys: List<String>,
    val content: String,
)

/**
 * 场记的策略 —— **限频与幅度**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这一层是"别把角色做成连续剧"的地方
 * ═══════════════════════════════════════════════════════════════
 *
 * 一个每轮都注入事件、每轮都有"我想起伤心事"的导演，
 * 会让角色变成肥皂剧。真实的生活大部分时间是没事发生的。
 *
 * ⇒ 三条：
 * 1. **大多数轮次没有事件**（`maxEventsPerTurn` 通常给到 0 或 1）
 * 2. **事件之间有冷却**，同一类事件不连续触发
 * 3. **每天有上限**
 */
data class DirectorPolicy(
    /** 一轮最多几个事件。默认 1 —— 多了就是连续剧 */
    val maxEventsPerTurn: Int = 1,
    /** 一天最多几个事件 */
    val maxEventsPerDay: Int = 8,
    /** 同一类事件之间的冷却轮数 */
    val cooldownTurns: Int = 6,
) {

    init {
        require(maxEventsPerTurn >= 0) { "maxEventsPerTurn 不能为负" }
        require(maxEventsPerDay >= 0) { "maxEventsPerDay 不能为负" }
        require(cooldownTurns >= 0) { "cooldownTurns 不能为负" }
    }

    companion object {
        val Default = DirectorPolicy()
    }
}

/**
 * 场记的运行痕迹 —— 用来执行 [DirectorPolicy] 的限频。
 *
 * ⚠️ 与状态机分开：这是"导演自己的账"，不是角色的状态。
 *    混在一起会让"今天已经用过几次事件"变成角色设定的一部分。
 */
data class DirectorTrace(
    /** 今天（本地日历日）已经触发的事件数 */
    val eventsToday: Int = 0,
    /** 今天是哪一天（本地日序号）。换天就清零 */
    val dayIndex: Long = 0L,
    /** 上一次触发某类事件是第几轮 */
    val lastFiredTurn: Map<DirectorEventKind, Int> = emptyMap(),
) {

    /** 换天则清零。 */
    fun rollTo(dayIndex: Long): DirectorTrace =
        if (dayIndex == this.dayIndex) this else copy(eventsToday = 0, dayIndex = dayIndex)

    /** 这一类现在能不能触发。 */
    fun canFire(
        kind: DirectorEventKind,
        turnIndex: Int,
        policy: DirectorPolicy,
    ): Boolean {
        if (eventsToday >= policy.maxEventsPerDay) return false
        val last = lastFiredTurn[kind] ?: return true
        return turnIndex - last >= policy.cooldownTurns
    }

    /** 记一次触发。 */
    fun afterFiring(kind: DirectorEventKind, turnIndex: Int): DirectorTrace = copy(
        eventsToday = eventsToday + 1,
        lastFiredTurn = lastFiredTurn + (kind to turnIndex),
    )
}

/**
 * **把场记的输出过滤成"真正允许生效的"**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这一步必须在代码里，而不是提示词里
 * ═══════════════════════════════════════════════════════════════
 *
 * 限频、去重、上限 —— 这些都是**可判定的规则**。
 * 写在提示词里只是"请不要太频繁"，模型会照做吗？偶尔。
 *
 * ⇒ 用代码拦。模型负责**想**，代码负责**刹车**。
 */
object DirectorGate {

    /**
     * @param output 模型给的原始输出
     * @param trace 场记自己的账
     * @param turnIndex 这是第几轮
     * @param dayIndex 本地日序号
     * @param knownFactIds 世界书里已有的 id（用于去重）
     */
    fun accept(
        output: DirectorOutput,
        trace: DirectorTrace,
        turnIndex: Int,
        dayIndex: Long,
        policy: DirectorPolicy = DirectorPolicy.Default,
        knownFactIds: Set<String> = emptySet(),
    ): Accepted {

        val rolled = trace.rollTo(dayIndex)
        var working = rolled
        val accepted = mutableListOf<DirectorEvent>()

        for (e in output.events) {
            if (accepted.size >= policy.maxEventsPerTurn) break
            if (!working.canFire(e.kind, turnIndex, policy)) continue
            accepted += e
            working = working.afterFiring(e.kind, turnIndex)
        }

        // 事实去重：已存在的 id 不重复提议；同批内去重
        val facts = output.proposedFacts
            .filter { it.id.isNotBlank() && it.content.isNotBlank() && it.keys.isNotEmpty() }
            .filterNot { it.id in knownFactIds }
            .distinctBy { it.id }

        return Accepted(
            events = accepted,
            smallMoments = output.smallMoments.filter { it.isNotBlank() }.take(2),
            proposedFacts = facts,
            trace = working,
            // ⚠️ 被拦掉的数量要能被看见 —— 静默丢弃等于"导演不工作"却没人知道
            droppedEvents = output.events.size - accepted.size,
        )
    }

    /** 过滤之后的结果。 */
    data class Accepted(
        val events: List<DirectorEvent>,
        val smallMoments: List<String>,
        val proposedFacts: List<ProposedFact>,
        val trace: DirectorTrace,
        val droppedEvents: Int,
    )
}
