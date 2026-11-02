package com.pocketagent.personalogic

/**
 * **生理周期** —— 她身体自己的节律。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：我一开始把它排除了，那是错的
 * ═══════════════════════════════════════════════════════════════
 *
 * 我先前的理由是"用户会问『你怎么了』，而它答不出真的由头"。
 *
 * ⚠️ 那个理由**站不住**：生理期不是编造的事件，它是**真实的周期性事实**，
 *    而且它本身就是由头 —— "我这两天不太舒服"是一句完全成立的话。
 *    被排除的应该是"无中生有的生病"，不是这个。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条写作纪律（写坏了比不写更糟）
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 不做刻板印象机器。**
 *    最常见的烂写法是"她这几天脾气差"。真实情况是**不舒服**，
 *    而不是"变成了另一个人"。所以这里的影响是
 *    **精力下降 + 想被照顾**，而不是"易怒"。
 *
 * **② 不能用来说服用户。**
 *    "我生理期所以你要让着我"是操纵。禁止。
 *    她可以说自己不舒服，但**不能拿它当筹码**。
 *
 * **③ 可以不提。**
 *    真实的表达是「今天有点不舒服」「肚子疼」，而不是每次都宣布
 *    "我在生理期第 2 天"。所以提示词只写"如果你愿意可以说"，不强制。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用日历锚定而不是存一个"上次开始时间"
 * ═══════════════════════════════════════════════════════════════
 *
 * 存一个开始时间需要：首次安装时先"造"一个（她不可能在装 app 那天开始），
 * 之后还要维护它滚动。
 *
 * ⇒ 直接用**日历天数取模**：
 * ```
 *   phase = (从纪元起的天数) mod 周期长度
 * ```
 * 好处：**每天自动前进一天**，跨重启不变，不需要任何持久化。
 * 代价：所有用户共享同一个相位（可以后续加偏移，但今天是过度设计）。
 */
@kotlinx.serialization.Serializable
data class Cycle(
    /**
     * 是否启用。
     *
     * ⚠️ 默认 **true**，但只对**女性人格**有意义 —— 是否真的有，取决于
     *    角色设定。这里不做性别判断（那是 `PersonaPreset` 的事），
     *    只提供机制。
     */
    val enabled: Boolean = true,
    /** 周期长度（天）。28 是常见值，但不是所有人的。 */
    val cycleLengthDays: Int = 28,
    /** 经期长度（天）。 */
    val periodLengthDays: Int = 5,
) {

    init {
        require(cycleLengthDays in 21..40) { "周期长度不现实：$cycleLengthDays" }
        require(periodLengthDays in 2..10) { "经期长度不现实：$periodLengthDays" }
        require(periodLengthDays < cycleLengthDays) { "经期不能长于整个周期" }
    }

    /**
     * 现在是周期的第几天（0 起）。
     *
     * ★ 0 = 经期第一天。
     */
    fun dayOf(epochMs: Long): Int {
        if (epochMs <= 0L) return 0
        val days = epochMs / DAY_MS
        return (days % cycleLengthDays).toInt()
    }

    /**
     * 现在处于哪个阶段、第几天。
     *
     * @return null = 未启用
     */
    fun momentAt(epochMs: Long): CycleMoment? {
        if (!enabled) return null
        val d = dayOf(epochMs)
        val phase = when {
            // 经期
            d < periodLengthDays -> CyclePhase.MENSTRUAL
            // 排卵期大约在中间偏前
            d in (cycleLengthDays / 2 - 2)..(cycleLengthDays / 2 + 1) -> CyclePhase.OVULATION
            // 经期结束到排卵前
            d < cycleLengthDays / 2 -> CyclePhase.FOLLICULAR
            // 排卵后到下次经期前
            else -> CyclePhase.LUTEAL
        }
        return CycleMoment(phase = phase, dayIndex = d)
    }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}

/** 周期里的四个阶段。 */
enum class CyclePhase {
    /** 经期。 */
    MENSTRUAL,

    /** 经期之后，状态回升。 */
    FOLLICULAR,

    /** 排卵期，通常状态最好。 */
    OVULATION,

    /** 排卵后到经期前。可能有轻微不适，但**不是"脾气差"**。 */
    LUTEAL,
}

/**
 * **此刻处于周期的什么位置**。
 *
 * ★ 为什么把"第几天"放在这里而不是枚举条目里：
 *   Kotlin 的枚举条目不能携带实例数据（`MENSTRUAL(firstDay = true)` 是
 *   把 `firstDay` 变成**条目级常量**，而不是"这一次是不是头两天"）。
 *   把"阶段"（枚举）与"第几天"（数据）分开，语义才对。
 */
data class CycleMoment(
    val phase: CyclePhase,
    /** 周期里的第几天，0 = 经期第一天。 */
    val dayIndex: Int,
) {
    /** 经期里最难受的头两天。 */
    val isEarlyPeriod: Boolean
        get() = phase == CyclePhase.MENSTRUAL && dayIndex <= 1
}

/**
 * 生理周期的影响 —— 纯函数。
 *
 * ⚠️ 强度刻意**温和**。一条真实的经期不会让人"变一个人"，
 *    只会让人**累一点、想被照顾一点**。
 */
object CycleEngine {

    /**
     * 对精力的修正。
     *
     * 经期头两天 -12，之后 -6；排卵期 +5；其余 0。
     */
    fun energyModifier(moment: CycleMoment?): Int = when (moment?.phase) {
        CyclePhase.MENSTRUAL -> if (moment.isEarlyPeriod) -12 else -6
        CyclePhase.OVULATION -> 5
        else -> 0
    }

    /**
     * 对激素的**倾向**（不直接改值，交给调用方叠加）。
     *
     * ★ 经期：皮质醇轻微上升（不适）+ 催产素需求上升（想被照顾）。
     * ⚠️ **不动血清素** —— 那会变成"经期就情绪低落"的刻板印象。
     */
    fun hormoneNudge(moment: CycleMoment?): Impulse = when (moment?.phase) {
        CyclePhase.MENSTRUAL ->
            Impulse(cortisol = if (moment.isEarlyPeriod) 8 else 4, oxytocin = 6)
        CyclePhase.OVULATION -> Impulse(dopamine = 4, serotonin = 3)
        else -> Impulse()
    }

    /**
     * 该怎么写进提示词。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 三处刻意的克制
     * ═══════════════════════════════════════════════════════════════
     *
     * 1. **只在经期写**（其余阶段不写）—— 天天提就成了设定说明
     * 2. **不强制说出来** —— 写的是"可以说"，不是"必须说"
     * 3. **不写"脾气"** —— 写"不舒服"
     */
    fun promptLine(moment: CycleMoment?): String? = when (moment?.phase) {
        CyclePhase.MENSTRUAL -> buildString {
            append("## 此刻的身体\n")
            if (moment.isEarlyPeriod) {
                append("你这两天在生理期，肚子不舒服，人有点蔫。")
                append("如果对方问起，可以如实说；不问他也不用主动宣布。")
                append("**不要**拿这个当理由去要求对方让步。")
            } else {
                append("生理期快过去了，人还有一点乏，但已经好多了。")
            }
        }.trimEnd().takeIf { it.isNotBlank() }
        else -> null
    }
}
