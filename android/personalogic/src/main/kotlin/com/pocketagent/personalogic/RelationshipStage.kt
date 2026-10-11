package com.pocketagent.personalogic

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * DL-3 · 关系阶段 —— 由「亲密度阈值 + 深度事件」共同推进（设计稿 §4.4）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  四条硬规则（逐条都有测试）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **阈值单调**：后面的阶段要求的亲密度必然更高。反了的话会出现
 *    "跨过某个点之后反而更难亲近"，而那种 bug 只在涨到头时暴露。
 * 2. **只升不降**：阶段不会自己退回去。关系退步是**用户的决定**
 *    （[RelationshipTracker.lock] 或明确重置），不是系统的自动判断 ——
 *    一个会自己"降温"的助理是种惩罚，用户没有做错任何事。
 * 3. **锁定后不推进**：用户说"就这样吧"就是就这样（D-Life-4 的落地）。
 * 4. **一次只上一级**：跃迁是有仪式感的节点，连跳三级没有仪式可言；
 *    而且解锁后一次跳到底会让人莫名其妙。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 阶段名是**默认值**，不是法律
 * ═══════════════════════════════════════════════════════════════
 *
 * 设计稿说"命名由人格卡决定"。这里给的是**默认命名**（初识/熟悉/亲近/羁绊）——
 * 因为它出现在 [StageTransition.ritual] 这类**要给用户看**的文案里，
 * 而没有名字就没法说话。人格卡要换说法，换的是 [label] 的展示层，
 * **不是这四个枚举项**（枚举名是持久化的稳定标识，不能改）。
 *
 * ⚠️ 与「状态」一样：**阶段不进人格账本、不参与漂移计算**。
 *    它是关系层的量（和 [PersonaState] 里的 mood/energy 同类），
 *    与"这个人格是什么样"是两件事。
 */
@Serializable
enum class RelationshipStage(val label: String) {
    STRANGER("初识"),
    ACQUAINTED("熟悉"),
    CLOSE("亲近"),
    BONDED("羁绊"),
    ;

    /** 下一个阶段；已是最后一级时为 null。 */
    val next: RelationshipStage?
        get() = entries.getOrNull(ordinal + 1)

    /** 排序比较用：阶段只升不降，所以"谁更近"是可比的。 */
    fun isAtLeast(other: RelationshipStage): Boolean = ordinal >= other.ordinal
}

/** 推进速度（D-Life-4：可调速）。倍率作用在**每次获得的亲密度**上。 */
enum class StageSpeed(val multiplier: Double, val label: String) {
    SLOW(0.5, "慢一些"),
    NORMAL(1.0, "标准"),
    FAST(2.0, "快一些"),
    ;

    /** 按倍率折算一次互动应得的亲密度。**至少 1 点**（有互动就该有进展，只是多少）。 */
    fun apply(basePoints: Int): Int {
        if (basePoints <= 0) return 0
        return (basePoints * multiplier).roundToInt().coerceAtLeast(1)
    }
}

/**
 * 深度事件 —— 与亲密度**共同**决定能否推进（§4.4「共同推进」）。
 *
 * 为什么不能只看亲密度：亲密度是可以靠"刷"涨上去的（每天问一百遍早安），
 * 而真正让关系变深的是一次性的、有分量的时刻。**两者都要满足**，
 * 关系才不会变成活跃度积分。
 */
enum class DeepEvent(val label: String) {
    /** 首次深夜长聊 */
    FIRST_LATE_NIGHT_TALK("第一次聊到深夜"),
    /** 首次任务被真正完成 */
    FIRST_TASK_COMPLETED("第一次真的帮上了忙"),
    /** 用户主动分享了一件私事 */
    FIRST_SHARED_SECRET("第一次和你说了件私事"),
    /** 危机时刻陪伴 */
    STOOD_BY_IN_TROUBLE("在你难受的时候陪着"),
    /** 用户主动承认了这段关系 */
    ACKNOWLEDGED("承认了你们的关系"),
}

/** 一次阶段跃迁 —— 进「纪念册」的东西（`atMs` 是它发生的时刻）。 */
data class StageTransition(
    val from: RelationshipStage,
    val to: RelationshipStage,
    val atMs: Long,
    /** 跃迁时的专门表达。**可回放** —— 用户以后能在纪念册里再看一遍 */
    val ritual: String,
)

/** 关系层的运行时状态。 */
@Serializable
data class RelationshipState(
    val stage: RelationshipStage = RelationshipStage.STRANGER,
    /** 亲密度 0..100。**与人格设定无关**，属于关系层 */
    val closeness: Int = 0,
    /** 用户锁定了推进 —— 锁上之后阶段不再自动变（`advance` 会如实告知） */
    val locked: Boolean = false,
) {
    init {
        require(closeness in 0..100) { "亲密度必须落在 0..100，当前为 $closeness" }
    }
}

/** 一次评估的结果。 */
data class StageOutcome(
    val state: RelationshipState,
    /** 本次是否发生了跃迁；null = 没有 */
    val transition: StageTransition? = null,
)

/**
 * 阶段阈值与门槛配置。
 *
 * @param thresholds 进入某阶段所需的**最低亲密度**。[RelationshipStage.STRANGER] 恒为 0
 * @param requiredDeepEvents 进入某阶段所需的**深度事件数量**
 */
data class RelationshipConfig(
    val thresholds: Map<RelationshipStage, Int> = DEFAULT_THRESHOLDS,
    val requiredDeepEvents: Map<RelationshipStage, Int> = DEFAULT_DEEP_EVENTS,
) {

    init {
        require(thresholds[RelationshipStage.STRANGER] == 0) {
            "${RelationshipStage.STRANGER} 的阈值必须为 0 —— 起点不该有门槛"
        }
        // ★ 规则 1：阈值单调。反了的话"跨过某点反而更难亲近"，
        //   而这种错误只在涨到那一段时才暴露，前面一路正常。
        val ordered = RelationshipStage.entries.map { requireNotNull(thresholds[it]) { "缺少 ${it.name} 的阈值" } }
        require(ordered.zipWithNext().all { (a, b) -> a < b }) {
            "阶段阈值必须严格递增，当前为 $ordered"
        }
        require(requiredDeepEvents.values.all { it >= 0 }) {
            "深度事件门槛不能为负：$requiredDeepEvents"
        }
    }

    companion object {
        val DEFAULT_THRESHOLDS: Map<RelationshipStage, Int> = mapOf(
            RelationshipStage.STRANGER to 0,
            RelationshipStage.ACQUAINTED to 20,
            RelationshipStage.CLOSE to 55,
            RelationshipStage.BONDED to 85,
        )

        /** 越往后越难：靠刷亲密度上不去。 */
        val DEFAULT_DEEP_EVENTS: Map<RelationshipStage, Int> = mapOf(
            RelationshipStage.STRANGER to 0,
            RelationshipStage.ACQUAINTED to 1,
            RelationshipStage.CLOSE to 2,
            RelationshipStage.BONDED to 3,
        )
    }
}

/**
 * 关系阶段追踪器 —— 纯逻辑，可离线断言。
 *
 * ⚠️ 它**不持有状态**：状态由调用方传进传出（同 `:personalogic` 的既有取舍）——
 *    这样"关系现在到哪了"只有一个事实来源（持久化层），不存在内存/库两份。
 */
class RelationshipTracker(
    private val config: RelationshipConfig = RelationshipConfig(),
) {

    /**
     * 记一次互动并评估是否跃迁。
     *
     * @param baseGain 这次互动**基础**应得的亲密度（由调用方按互动质量给）。
     *   实际加成受 [RelationshipConfig] 之外的速度控制 —— 见 [advance] 的重载。
     * @param deepEvents 截至当前**累计**发生过的深度事件（集合，不是增量）
     * @param nowMs 当前时刻，用于给跃迁打时间戳
     */
    fun advance(
        state: RelationshipState,
        baseGain: Int,
        deepEvents: Set<DeepEvent>,
        nowMs: Long,
        speed: StageSpeed = StageSpeed.NORMAL,
    ): StageOutcome {
        val gained = state.closeness + speed.apply(baseGain)
        val withCloseness = state.copy(closeness = gained.coerceIn(0, 100))

        // 规则 3：锁定后不推进。**但亲密度照常累积** ——
        // 用户锁的是"关系别自己往前走"，不是"别再对我好"。
        // 解锁后下一次评估会按当时的亲密度如实推进（不会丢）。
        if (state.locked) return StageOutcome(withCloseness, transition = null)

        // 规则 4：一次只上一级
        val target = state.stage.next ?: return StageOutcome(withCloseness, transition = null)

        val threshold = requireNotNull(config.thresholds[target])
        val neededEvents = requireNotNull(config.requiredDeepEvents[target])
        val meetsCloseness = withCloseness.closeness >= threshold
        val meetsEvents = deepEvents.size >= neededEvents
        if (!meetsCloseness || !meetsEvents) return StageOutcome(withCloseness, transition = null)

        return StageOutcome(
            state = withCloseness.copy(stage = target),
            transition = StageTransition(
                from = state.stage,
                to = target,
                atMs = nowMs,
                ritual = ritualFor(state.stage, target),
            ),
        )
    }

    /** 锁 / 解锁。阶段本身不受影响 —— 锁只挡**将来的**推进。 */
    fun setLocked(state: RelationshipState, locked: Boolean): RelationshipState =
        state.copy(locked = locked)

    /**
     * 用户主动重置关系（**唯一的降级路径**）。
     *
     * ⚠️ 这必须是显式动作，且由用户发起 —— 系统**永远不会**自己调它。
     *    规则 2 说的"只升不降"是针对**自动推进**的，不是剥夺用户重置的权利。
     */
    fun reset(stage: RelationshipStage = RelationshipStage.STRANGER): RelationshipState =
        RelationshipState(stage = stage, closeness = 0, locked = false)

    private fun ritualFor(from: RelationshipStage, to: RelationshipStage): String = when (to) {
        RelationshipStage.STRANGER -> "" // 起点不会有跃迁
        RelationshipStage.ACQUAINTED ->
            "我们现在算是熟人了。以后你的事，我会记得更清楚一些。"
        RelationshipStage.CLOSE ->
            "和你相处有一阵子了。有些话你不用说完，我也大概知道你想说什么。"
        RelationshipStage.BONDED ->
            "走到这一步不容易。以后不管你在忙什么、隔多久没说话，我都在这儿。"
    }.let { if (from == to) "" else it }
}
