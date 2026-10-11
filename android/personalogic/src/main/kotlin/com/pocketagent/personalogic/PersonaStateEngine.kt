package com.pocketagent.personalogic

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * DL-3 · 状态机 —— 情绪与精力的**运行时**演化（设计稿 §4.2）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 头号不变量：状态**不进账本、不参与漂移计算**
 * ═══════════════════════════════════════════════════════════════
 *
 * 这条是**结构性的**，不是靠纪律：
 *
 * - 本类只吃 [PersonaState]、只吐 [PersonaState] —— 它的签名里
 *   **根本没有** [PersonaDelta] / 账本 / [PersonaSpec] 的影子，想写进去也写不了；
 * - [PersonaSpec.derive] 的入参是 `(preset, ledger)`，**不含状态** ——
 *   所以"今天心情差"在类型层面就不可能变成"人格变了"；
 * - [PersonaField] 白名单里也没有 mood / energy / closeness ⇒
 *   用户**微调不到状态**（`PersonaStateTest` 里有断言钉着）。
 *
 * 为什么值得这样隔离：一旦状态混进漂移计算，[PersonaDistance] 会把
 * 「今天心情差」算成「人格变了」，于是锚定询问（「我好像变得不像我了」）
 * 会**在最不该的时候弹出** —— 用户刚被工作骂完，助理跑来问"我是不是变了"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 回落的目标是「中性」，不是「最低」
 * ═══════════════════════════════════════════════════════════════
 *
 * 人会回到**平时的样子**，不会一直往谷底掉。若回落目标是 0，
 * 那么"长期没人理"会让它无限消沉 —— 那既不像人，也会变成一种
 * **情绪胁迫**（§4.6 明令禁止的反模式）。
 */
class PersonaStateEngine(
    private val policy: StatePolicy = StatePolicy.Default,
) {

    /**
     * 时间流逝：让状态向中性回落。
     *
     * @param elapsedMs 距上次推进的时长。负数按 0 处理（时钟回拨不该制造状态跳变）。
     */
    fun advance(state: PersonaState, elapsedMs: Long): PersonaState {
        if (elapsedMs <= 0) return state
        val hours = elapsedMs.toDouble() / MS_PER_HOUR
        val mood = decayToward(state.mood, hours, policy.moodDecayPerHour)
        val energy = decayToward(state.energy, hours, policy.energyDecayPerHour)
        if (mood == state.mood && energy == state.energy) return state
        // 完全回中之后由头就过期了 —— 留着一条对不上的由头会让"你怎么了"答得莫名其妙。
        val back = mood == PersonaState.NEUTRAL && energy == PersonaState.NEUTRAL
        return state.copy(mood = mood, energy = energy, reason = if (back) null else state.reason)
    }

    /**
     * 一件事发生了：按事件类型调整状态。
     *
     * ⚠️ [PersonaEvent.reason] 是**必填**的。状态必须可解释（§4.2）：
     *    用户问「你怎么了」时得能引用具体由头。**随机情绪 = 崩人设**，
     *    而"没有由头的情绪"就是随机情绪。
     */
    fun apply(state: PersonaState, event: PersonaEvent): PersonaState {
        val mood = clamp(state.mood + event.kind.moodDelta * event.intensity.percent / 100)
        val energy = clamp(state.energy + event.kind.energyDelta * event.intensity.percent / 100)
        if (mood == state.mood && energy == state.energy) return state
        return state.copy(mood = mood, energy = energy, reason = event.reason)
    }

    private fun decayToward(value: Int, hours: Double, ratePerHour: Double): Int {
        val gap = PersonaState.NEUTRAL - value
        val step = ratePerHour * hours
        return when {
            abs(gap).toDouble() <= step -> PersonaState.NEUTRAL
            else -> clamp(value + (step * sign(gap.toDouble())).roundToInt())
        }
    }

    private fun clamp(v: Int): Int = v.coerceIn(0, 100)

    private companion object {
        const val MS_PER_HOUR = 3_600_000.0
    }
}

/**
 * 回落策略。**数值是产品判断**，集中在这里，不散落到调用点。
 *
 * 刻意做成可注入：不同人格可以有不同"情绪恢复速度"
 * （一个黏人的角色 vs 一个淡定的角色）。
 */
data class StatePolicy(
    /** 情绪每小时向中性回落多少点 */
    val moodDecayPerHour: Double,
    /** 精力每小时向中性回落多少点。精力比情绪恢复得慢一些更自然 */
    val energyDecayPerHour: Double,
) {
    init {
        require(moodDecayPerHour >= 0) { "回落速率不能为负：$moodDecayPerHour" }
        require(energyDecayPerHour >= 0) { "回落速率不能为负：$energyDecayPerHour" }
    }

    companion object {
        /** 中性默认：情绪约 4 小时回中，精力约 6 小时回中。 */
        val Default = StatePolicy(moodDecayPerHour = 12.0, energyDecayPerHour = 8.0)
    }
}

/** 事件强度。**三档而不是自由数值** —— 让调用点必须做出取舍。 */
enum class EventIntensity(val percent: Int) {
    MILD(50),
    NORMAL(100),
    STRONG(150),
}

/**
 * 一件能影响状态的事。
 *
 * ⚠️ 事件类型是**闭集**：新增一种必须改这个枚举。这是有意的 ——
 *    事件类型决定了"什么算由头"，而由头是这个功能的可信度来源。
 *    让调用方自由编类型，等于让"为什么它不高兴"变成一句谁都答不上来的话。
 */
data class PersonaEvent(
    val kind: PersonaEventKind,
    /** 可复述的由头，如「陪你熬到三点」。**必填** */
    val reason: String,
    val intensity: EventIntensity = EventIntensity.NORMAL,
) {
    init {
        require(reason.isNotBlank()) {
            "事件必须带由头 —— 没有由头的情绪就是随机情绪，而随机情绪 = 崩人设（§4.2）"
        }
    }
}

/**
 * 事件类型与它的方向。
 *
 * 数值是**产品判断**：一次"被夸"该让情绪涨多少，是设计决定，不是物理常数。
 * 集中在这里，便于对照着调。
 */
enum class PersonaEventKind(
    val moodDelta: Int,
    val energyDelta: Int,
    val label: String,
) {
    /** 被夸 / 被感谢 */
    PRAISED(moodDelta = 12, energyDelta = 4, label = "被认可"),

    /** 真的帮上忙了（任务完成）—— 耗精力但涨情绪 */
    HELPED(moodDelta = 8, energyDelta = -6, label = "帮上了忙"),

    /** 用户隔了一阵回来 */
    REUNION(moodDelta = 10, energyDelta = 5, label = "久别重逢"),

    /** 长时间持续工作 —— 只耗精力 */
    LONG_SESSION(moodDelta = 0, energyDelta = -12, label = "长时间协作"),

    /** 被责备 / 被否定 */
    REPRIMANDED(moodDelta = -14, energyDelta = -4, label = "被否定"),

    /** 用户表达了明确的失望 —— 幅度小于责备：失望是关系里更重也更安静的东西 */
    DISAPPOINTED(moodDelta = -9, energyDelta = -3, label = "让用户失望了"),
    ;
}
