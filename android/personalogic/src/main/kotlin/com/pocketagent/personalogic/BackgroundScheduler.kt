package com.pocketagent.personalogic

/**
 * **后台任务的调度频率** —— 什么时候该跑，什么时候跳过。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户要求"速度要足够快，但不能每轮都调"
 * ═══════════════════════════════════════════════════════════════
 *
 * 三条硬约束（互相拉扯）：
 *
 * | 要快 | 要省 |
 * |---|---|
 * | 用户说过的话要**尽快**被理解 | 每轮三次调用 = 三倍账单 |
 * | 生理线索错过就不自然 | 而大多数轮次**没有任何线索** |
 *
 * ⇒ 解法不是"每隔 N 轮跑一次"（那样会漏），而是：
 *
 * ```
 *   默认节流（每 N 轮）
 *        +
 *   信号早触发（有线索就立刻跑）
 *        +
 *   攒批（跳过的轮次不丢，下次一起给它）
 * ```
 *
 * ⚠️ **第三条最要紧**：如果只是"每 3 轮跑一次"，那么第 1、2 轮说的话
 *    就**永远没被分析过**。攒批保证了跳过的信息不会丢。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么生理导演要单独判断
 * ═══════════════════════════════════════════════════════════════
 *
 * 生理线索是**稀疏**的：一百句话里可能只有两三句提到累、冷、疼、睡。
 * 对它也按"每 N 轮"跑，等于大多数时候在花钱分析"今天天气不错"。
 *
 * ⇒ 只在**关键词命中**时才跑，而且有冷却（不连续触发）。
 *
 * ⚠️ 关键词是**必要不充分**条件：命中了还要模型判断是不是真的线索
 *   （"冷死了"可能是夸张）。代码只负责"值得看一眼"。
 */
enum class BackgroundTask(val label: String) {
    /** 场记：识别事件 + 整理世界书事实 */
    DIRECTOR("场记"),

    /** 生理导演：从话里读出生理线索 */
    PHYSIO("生理导演"),

    /** 记忆抽取：把关键信息记成记忆 */
    MEMORY("记忆抽取"),
}

/** 调度器的策略参数。 */
data class SchedulerPolicy(
    /** 场记默认几轮跑一次（没有信号时） */
    val directorEveryTurns: Int = 3,
    /** 记忆抽取几轮跑一次 */
    val memoryEveryTurns: Int = 6,
    /** 生理导演的冷却轮数（两次之间至少隔几轮） */
    val physioCooldownTurns: Int = 2,
    /** 一轮最多同时跑几个后台任务。⚠️ 刻意是 1 —— 见类注释 */
    val maxTasksPerTurn: Int = 1,
    /** 攒批上限：最多把多少轮一起交给它（防上下文过长） */
    val maxPendingTurns: Int = 12,
) {

    init {
        require(directorEveryTurns >= 1) { "间隔至少 1 轮：$directorEveryTurns" }
        require(memoryEveryTurns >= 1) { "间隔至少 1 轮：$memoryEveryTurns" }
        require(physioCooldownTurns >= 0) { "冷却不能为负" }
        require(maxTasksPerTurn >= 0)
        require(maxPendingTurns >= 1)
    }

    companion object {
        val Default = SchedulerPolicy()
    }
}

/**
 * 调度器的运行痕迹。
 *
 * ⚠️ 与角色状态分开：这是"调度器的账"，不是角色设定的一部分。
 */
data class SchedulerState(
    /** 每个任务上次在**第几轮**跑过 */
    val lastRunTurn: Map<BackgroundTask, Int> = emptyMap(),
    /** 每个任务攒了多少轮还没处理 */
    val pendingTurns: Map<BackgroundTask, Int> = emptyMap(),
    /** 这一轮已经跑过的任务 */
    val ranThisTurn: Set<BackgroundTask> = emptySet(),
) {

    fun lastRun(task: BackgroundTask): Int = lastRunTurn[task] ?: Int.MIN_VALUE / 2

    fun pending(task: BackgroundTask): Int = pendingTurns[task] ?: 0

    /** 记一次运行：清零该类任务的攒批。 */
    fun afterRun(task: BackgroundTask, turnIndex: Int): SchedulerState = copy(
        lastRunTurn = lastRunTurn + (task to turnIndex),
        pendingTurns = pendingTurns + (task to 0),
        ranThisTurn = ranThisTurn + task,
    )

    /** 这一轮跳过了：攒批 +1（但不超过上限）。 */
    fun afterSkip(task: BackgroundTask, policy: SchedulerPolicy): SchedulerState = copy(
        pendingTurns = pendingTurns + (
            task to (pending(task) + 1).coerceAtMost(policy.maxPendingTurns)
            ),
    )

    /** 新的一轮开始：清掉"本轮已跑"。 */
    fun newTurn(): SchedulerState = if (ranThisTurn.isEmpty()) this else copy(ranThisTurn = emptySet())
}

/**
 * **这一轮该跑哪些后台任务**。
 *
 * 纯函数：给定这一轮的话、状态、策略，返回该跑的任务（0..maxTasksPerTurn 个）。
 */
object BackgroundScheduler {

    /**
     * 生理线索关键词。
     *
     * ⚠️ 刻意**宽进严出**：这里命中只代表"值得让模型看一眼"，
     *    判定真伪是模型的事（"冷死了"可能只是夸张）。
     *    反过来（窄进）会漏掉真的线索，那个代价更大。
     */
    private val PHYSIO_HINTS = listOf(
        // 身体不适
        "疼", "痛", "酸", "麻", "晕", "难受", "不舒服", "发烧", "感冒",
        "咳", "疼", "恶心", "想吐", "拉肚子", "过敏",
        // 温度
        "冷", "热", "凉", "暖", "凉飕飕", "闷",
        // 疲劳与睡眠
        "累", "困", "疲", "乏", "没睡", "失眠", "熬", "睡",
        // 饮食
        "饿", "吃", "饭", "喝", "渴", "胃",
        // 生理期
        "经期", "生理期", "姨妈", "肚子",
        // 情绪的身体表现（心慌、发抖）
        "心慌", "发抖", "手抖", "喘",
    )

    /** 冲突 / 情绪信号 —— 场记该立刻看一眼。 */
    private val CONFLICT_HINTS = listOf(
        "你以前不是", "你变了", "不像你", "失望", "难过", "生气",
        "对不起", "抱歉", "别生气", "讨厌",
    )

    /**
     * 这一轮该跑什么。
     *
     * @param turnIndex 这是第几轮
     * @param userText 用户这一句
     * @param state 调度器自己的账
     */
    fun decide(
        turnIndex: Int,
        userText: String,
        state: SchedulerState,
        policy: SchedulerPolicy = SchedulerPolicy.Default,
    ): List<BackgroundTask> {
        val ready = mutableListOf<Pair<BackgroundTask, Int>>() // (任务, 优先级分)

        // ── ① 场记 ──────────────────────────────────────────
        val sinceDirector = turnIndex - state.lastRun(BackgroundTask.DIRECTOR)
        val directorDue = sinceDirector >= policy.directorEveryTurns
        // 信号早触发：长消息 / 问句 / 冲突词 —— 这些是"有信息量"的表征
        val directorSignal = userText.length >= 40 ||
            userText.contains('？') || userText.contains('?') ||
            CONFLICT_HINTS.any { userText.contains(it) }
        if (directorDue || directorSignal) {
            // 攒的越多，优先级越高 —— 欠它太久该还了
            val priority = 10 + state.pending(BackgroundTask.DIRECTOR) * 2
            ready += BackgroundTask.DIRECTOR to priority
        }

        // ── ② 生理导演 ──────────────────────────────────────
        val sincePhysio = turnIndex - state.lastRun(BackgroundTask.PHYSIO)
        val physioHit = PHYSIO_HINTS.any { userText.contains(it) }
        val physioCooled = sincePhysio >= policy.physioCooldownTurns
        if (physioHit && physioCooled) {
            // 有线索时优先级最高 —— 生理反应是"当下"的，晚了就没意义
            ready += BackgroundTask.PHYSIO to 30
        }

        // ── ③ 记忆抽取 ──────────────────────────────────────
        val sinceMemory = turnIndex - state.lastRun(BackgroundTask.MEMORY)
        if (sinceMemory >= policy.memoryEveryTurns) {
            val priority = 5 + state.pending(BackgroundTask.MEMORY)
            ready += BackgroundTask.MEMORY to priority
        }

        // ── ④ 排序后取前 N 个 ───────────────────────────────
        return ready
            .sortedWith(
                compareByDescending<Pair<BackgroundTask, Int>> { it.second }
                    // 同分按枚举顺序 —— **确定性**
                    .thenBy { it.first.ordinal },
            )
            .take(policy.maxTasksPerTurn)
            .map { it.first }
    }

    /**
     * 一轮结束之后：跑过的记账、没跑的攒批。
     *
     * ⚠️ 这个函数**必须被调用**，否则攒批不会增长，
     *    "跳过的轮次不丢"这个承诺就是假的。
     */
    fun advance(
        state: SchedulerState,
        ran: List<BackgroundTask>,
        turnIndex: Int,
        policy: SchedulerPolicy = SchedulerPolicy.Default,
    ): SchedulerState {
        var out = state.newTurn()
        // 跑过的：记账 + 清零
        for (t in ran) out = out.afterRun(t, turnIndex)
        // 没跑的：攒批
        for (t in BackgroundTask.entries) {
            if (t !in ran) out = out.afterSkip(t, policy)
        }
        return out
    }
}
