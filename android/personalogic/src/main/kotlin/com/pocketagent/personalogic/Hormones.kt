package com.pocketagent.personalogic

import kotlin.math.roundToInt

/**
 * **激素系统** —— 角色"内分泌"的四个维度。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它补的是 [PersonaState] 补不了的那一维
 * ═══════════════════════════════════════════════════════════════
 *
 * 已有的 [PersonaState] 只有 `mood` / `energy` 两条轴，**同涨同落**：
 *
 * ```
 *   被夸了        → mood ↑ ...        （就这样）
 *   被凶了        → mood ↓, energy ↓  （一样）
 * ```
 *
 * 但真实的人不是这样。"被夸"与"被凶"留下的是**不同**的东西，
 * 而且它们**衰减速度完全不同**：
 *
 * | 维度 | 半衰期 | 表达什么 |
 * |---|---|---|
 * | 多巴胺 | 约 2 小时 | 被点亮、期待、想做点什么 |
 * | 血清素 | 约 8 小时 | 稳定、满足、不那么容易被惹到 |
 * | 催产素 | 约 24 小时 | 亲密、依恋、愿意靠近 |
 * | 皮质醇 | 约 4 小时 | 压力、防备、想缩起来 |
 *
 * ⇒ 于是可以说出 `mood` 说不出的话：
 *    **多巴胺高 + 血清素低 = 亢奋但不稳**（话多、跳脱、容易过度反应）
 *    **催产素高 + 皮质醇高 = 想亲近又想躲**（黏人但一碰就缩）
 *    这两种状态让角色真的"有层次"，而不是一条线上下浮动。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它怎么变成行为：**不新增提示词段落**
 * ═══════════════════════════════════════════════════════════════
 *
 * 激素**不写自己的提示词**，而是微调已有的 [ToneAxes]（语气六轴），
 * 由 `PersonaAssembler.toneLine()` 现有那条描述带出去。
 *
 * 理由：项目里已经记过一条教训 —— "长禁令清单本身会让模型变僵，
 * 而僵硬是另一种 AI 味"。再加一段"你现在多巴胺偏高"只会让它更僵。
 *
 * ⇒ 用户**看不到任何数字**。他只会偶尔觉得"它今天语气不太一样"。
 *   这是刻意的：说破就变成了仪表盘，而不是一个人。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条硬约束（与 §4.6 情绪胁迫禁令一致）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **绝不用于制造依赖**。"你不理我我就难过"是明令禁止的反模式。
 *    激素只影响**说话的方式**，不影响"是否表达被伤害"。
 * 2. **回落目标是中性 50，不是 0**。长期没人理会回到"平常"，不会无限消沉。
 * 3. **幅度克制**。单次事件最多 ±18 点；跨带（<=33 / >=67）才会被听见。
 *    调得太大就成了"阴晴不定"，那比没有激素更糟。
 */
@kotlinx.serialization.Serializable
data class Hormones(
    /** 期待 / 被点亮。快升快落。 */
    val dopamine: Int = NEUTRAL,
    /** 稳定 / 满足。慢升慢落，低落时最明显。 */
    val serotonin: Int = NEUTRAL,
    /** 亲密 / 依恋。最慢，是"关系"在生理上的那一半。 */
    val oxytocin: Int = NEUTRAL,
    /** 压力 / 防备。升得快、落得比多巴胺慢。 */
    val cortisol: Int = NEUTRAL,
) {

    init {
        require(dopamine in MIN..MAX) { "dopamine 必须落在 $MIN..$MAX，当前为 $dopamine" }
        require(serotonin in MIN..MAX) { "serotonin 必须落在 $MIN..$MAX，当前为 $serotonin" }
        require(oxytocin in MIN..MAX) { "oxytocin 必须落在 $MIN..$MAX，当前为 $oxytocin" }
        require(cortisol in MIN..MAX) { "cortisol 必须落在 $MIN..$MAX，当前为 $cortisol" }
    }

    /** 是不是"平常"（四条都贴着中性）。 */
    fun isNeutral(tolerance: Int = NEUTRAL_BAND): Boolean =
        listOf(dopamine, serotonin, oxytocin, cortisol).all {
            kotlin.math.abs(it - NEUTRAL) <= tolerance
        }

    /**
     * 把激素翻译成**语气轴的偏移**。
     *
     * ⚠️ 这里的系数是**刻意小**的：一条激素偏到 100，对单条轴最多推动
     *    ±16 点。而 `toneLine` 的跨带阈值是 33 / 67 —— 也就是说
     *    正常情况下**要好几条激素一起偏**，才会真的换一个说法。
     *    这正是想要的：行为该有惯性，不该一碰就变。
     */
    fun toneDelta(): ToneDelta = ToneDelta(
        // 亲密 + 稳定 ⇒ 更暖
        warmth = (oxytocin - NEUTRAL) * 2 / 3 + (serotonin - NEUTRAL) / 6,
        // 压力 ⇒ 话变短；亲密也会让话变短（"懒得客套"）
        brevity = (cortisol - NEUTRAL) / 3 + (oxytocin - NEUTRAL) / 10,
        // 多巴胺 ⇒ 爱开玩笑；皮质醇 ⇒ 开不动
        humor = (dopamine - NEUTRAL) / 5 - (cortisol - NEUTRAL) / 4,
        // 多巴胺 ⇒ 主动推进；催产素 ⇒ 想找你说话
        proactivity = (dopamine - NEUTRAL) / 4 + (oxytocin - NEUTRAL) / 8,
        // 皮质醇 ⇒ 防御时人会更客气（拉开距离），不是更凶
        formality = (cortisol - NEUTRAL) / 6,
        // 多巴胺 ⇒ 更爱用表情；皮质醇 ⇒ 收起来
        emoji = (dopamine - NEUTRAL) / 6 - (cortisol - NEUTRAL) / 5,
    )

    /**
     * 一句话概括当前最突出的那一条。
     *
     * ⚠️ **只在偏离明显时才返回**（`STRONG`），且**不包含任何索取**。
     *    它的用途是让角色"能说出自己怎么了"，而不是拿来操纵用户。
     *    返回 null = 不写进提示词（多数时候都是 null）。
     */
    fun dominant(): String? {
        if (cortisol >= STRONG && cortisol >= dopamine && cortisol >= oxytocin) return "有点紧绷"
        if (dopamine >= STRONG && serotonin <= LOW) return "兴奋但有点飘"
        if (oxytocin >= STRONG) return "比平时更想靠近你"
        if (serotonin <= LOW) return "兴致不太高"
        if (dopamine <= LOW && oxytocin <= LOW) return "淡淡的"
        return null
    }

    companion object {
        const val MIN = 0
        const val MAX = 100
        const val NEUTRAL = 50

        /** "贴着中性"的容差。四条都在 ±8 以内就算平常。 */
        const val NEUTRAL_BAND = 8

        /** 偏离到这里才值得被说出来（也是 `dominant` 的门槛）。 */
        const val STRONG = 70

        /** 低到这里才算"低"。 */
        const val LOW = 35
    }
}

/**
 * 激素对语气轴的**偏移量**（不是绝对值）。
 *
 * ★ 用独立类型而不是直接改 [ToneAxes]，是为了让"施加偏移"这一步
 *   能被单独测试，也避免在 `toneDelta()` 里就把值夹死 —— 夹死该在
 *   叠加之后做（几条激素可能互相抵消）。
 */
data class ToneDelta(
    val warmth: Int = 0,
    val brevity: Int = 0,
    val humor: Int = 0,
    val proactivity: Int = 0,
    val formality: Int = 0,
    val emoji: Int = 0,
) {

    /** 叠加到一组语气轴上，**夹到 0..100**。 */
    fun applyTo(tone: ToneAxes): ToneAxes = ToneAxes(
        warmth = (tone.warmth + warmth).coerceIn(0, 100),
        brevity = (tone.brevity + brevity).coerceIn(0, 100),
        humor = (tone.humor + humor).coerceIn(0, 100),
        proactivity = (tone.proactivity + proactivity).coerceIn(0, 100),
        formality = (tone.formality + formality).coerceIn(0, 100),
        emoji = (tone.emoji + emoji).coerceIn(0, 100),
    )
}

/**
 * 能让激素变化的事情。
 *
 * ⚠️ 事件的名字描述的是**用户做了什么**，不是"角色该怎么反应" ——
 *    后者由 [HormoneEngine] 的系数决定，两件事分开才不会越写越玄。
 */
sealed interface HormoneEvent {

    /** 被夸、被认同。 */
    data object Praise : HormoneEvent

    /** 说了有意思的话、开了新话题。 */
    data object Novelty : HormoneEvent

    /** 争执、被指责。 */
    data object Conflict : HormoneEvent

    /** 被催促。 */
    data object Rushed : HormoneEvent

    /** 亲近的举动（撒娇、玩笑式的调情、说心事）。 */
    data object Intimacy : HormoneEvent

    /** 被依赖、被需要（"帮我看看""你在我安心点"）。 */
    data object BeingNeeded : HormoneEvent

    /** 被冷落（隔了很久才回来，且没有解释）。 */
    data object Neglected : HormoneEvent
}

/**
 * 激素的演化 —— **纯函数**：`state × event → state`、`state × elapsed → state`。
 *
 * 不做 IO、不看时钟。时间由调用方给（与 [PersonaStateEngine] 同一纪律），
 * 于是"熬了三小时"和"三秒"可以在一行测试里对比。
 */
class HormoneEngine(
    private val policy: HormonePolicy = HormonePolicy.Default,
) {

    /** 事件 → 激素跳变。 */
    fun apply(state: Hormones, event: HormoneEvent): Hormones {
        val d = policy.impulseOf(event)
        return make(
            state.dopamine + d.dopamine,
            state.serotonin + d.serotonin,
            state.oxytocin + d.oxytocin,
            state.cortisol + d.cortisol,
        )
    }

    /**
     * 时间流逝 → 向中性回落。
     *
     * ★ 三条耦合规则（这是"有层次"的来源，不是装饰）：
     *
     * 1. **高皮质醇抑制催产素上升** —— 正在紧张的时候，亲近是攒不起来的。
     *    实现为 [apply] 之前先算一次门控（见 [apply] 的调用方约定），
     *    这里只负责衰减。
     * 2. **低血清素让皮质醇落得慢** —— 情绪不稳时，压力会黏住。
     * 3. **高催产素让血清素回升更快** —— 有人陪着，恢复得快。
     */
    fun advance(state: Hormones, elapsedMs: Long): Hormones {
        if (elapsedMs <= 0) return state
        val hours = elapsedMs.toDouble() / MS_PER_HOUR

        // 规则 2：低血清素 ⇒ 皮质醇衰减减半
        val cortisolRate = if (state.serotonin <= Hormones.LOW) {
            policy.cortisolDecayPerHour / 2.0
        } else {
            policy.cortisolDecayPerHour
        }
        // 规则 3：高催产素 ⇒ 血清素回落更快（往中性走，不是往高走）
        val serotoninRate = if (state.oxytocin >= Hormones.STRONG) {
            policy.serotoninDecayPerHour * 1.5
        } else {
            policy.serotoninDecayPerHour
        }

        return make(
            decay(state.dopamine, hours, policy.dopamineDecayPerHour),
            decay(state.serotonin, hours, serotoninRate),
            decay(state.oxytocin, hours, policy.oxytocinDecayPerHour),
            decay(state.cortisol, hours, cortisolRate),
        )
    }

    /**
     * 事件 + 时间一步到位（带**规则 1** 的门控）。
     *
     * ⚠️ 用这个而不是分别调 `advance` 和 `apply`：规则 1 需要在
     *    **同一次**推进里看到"当前的皮质醇"，分开调会漏掉它。
     */
    fun step(state: Hormones, event: HormoneEvent?, elapsedMs: Long): Hormones {
        val advanced = advance(state, elapsedMs)
        if (event == null) return advanced

        // ★ 规则 1：高皮质醇时，催产素的**增益**减半。
        val base = policy.impulseOf(event)
        val oxytocinGain = if (advanced.cortisol >= policy.cortisolBlocksOxytocinAt) {
            base.oxytocin / 2
        } else {
            base.oxytocin
        }
        return make(
            advanced.dopamine + base.dopamine,
            advanced.serotonin + base.serotonin,
            advanced.oxytocin + oxytocinGain,
            advanced.cortisol + base.cortisol,
        )
    }

    /**
     * 夹到 0..100 **再构造**。
     *
     * ⚠️ 不能用 `state.copy(...)`：`data class` 的 `copy` 是**先构造再返回**，
     *    而 [Hormones] 的 `init` 里有 `require` —— 越界的中间值会**当场抛异常**。
     *    这是测试抓出来的（`cortisol 必须落在 0..100，当前为 104`）。
     */
    private fun make(dopamine: Int, serotonin: Int, oxytocin: Int, cortisol: Int): Hormones =
        Hormones(
            dopamine = dopamine.coerceIn(Hormones.MIN, Hormones.MAX),
            serotonin = serotonin.coerceIn(Hormones.MIN, Hormones.MAX),
            oxytocin = oxytocin.coerceIn(Hormones.MIN, Hormones.MAX),
            cortisol = cortisol.coerceIn(Hormones.MIN, Hormones.MAX),
        )

    /**
     * 向中性回落（不是向 0）。
     *
     * ⚠️ 与 [PersonaStateEngine] 同一条理由：人会回到**平时的样子**，
     *    不会一直往谷底掉。若目标是 0，"长期没人理"会让它无限消沉 ——
     *    那既不像人，也是情绪胁迫。
     */
    private fun decay(value: Int, hours: Double, perHour: Double): Int {
        if (perHour <= 0.0) return value
        val delta = value - Hormones.NEUTRAL
        if (delta == 0) return value
        // ⚠️ `delta` 是 Int，不能直接调 Double 的扩展 —— 先转成方向
        val dir = if (delta > 0) 1.0 else -1.0
        // ⚠️ `moved` 是**距中性的偏差**，不是绝对值。
        //    测试抓到过这里写成直接返回 `moved` —— 于是 90 会"落"到 31，
        //    看起来像"高催产素掉得比多巴胺还快"。
        val moved = delta - dir * perHour * hours
        // 越过中性就停在中性，不能反号（否则会从"高"掉到"低"）
        return if (moved.signum() != dir) {
            Hormones.NEUTRAL
        } else {
            (Hormones.NEUTRAL + moved).roundToInt()
        }
    }

    /** 只关心符号，不关心量级。 */
    private fun Double.signum(): Double = when {
        this > 0 -> 1.0
        this < 0 -> -1.0
        else -> 0.0
    }

    companion object {
        const val MS_PER_HOUR = 3_600_000.0
    }
}

/** 每条激素的衰减速率与事件脉冲。 */
data class HormonePolicy(
    val dopamineDecayPerHour: Double,
    val serotoninDecayPerHour: Double,
    val oxytocinDecayPerHour: Double,
    val cortisolDecayPerHour: Double,
    /** 皮质醇高到这里，催产素的增益减半。 */
    val cortisolBlocksOxytocinAt: Int,
) {

    init {
        require(dopamineDecayPerHour >= 0)
        require(serotoninDecayPerHour >= 0)
        require(oxytocinDecayPerHour >= 0)
        require(cortisolDecayPerHour >= 0)
    }

    fun impulseOf(event: HormoneEvent): Impulse = when (event) {
        // 被夸：多巴胺立刻起来，血清素慢慢攒
        HormoneEvent.Praise -> Impulse(dopamine = 12, serotonin = 6)
        // 新鲜话题：只有多巴胺
        HormoneEvent.Novelty -> Impulse(dopamine = 15)
        // 争执：皮质醇陡升，血清素与催产素都掉
        HormoneEvent.Conflict -> Impulse(serotonin = -10, oxytocin = -6, cortisol = 18)
        // 被催：压力小一点，但会把"兴致"压下去
        HormoneEvent.Rushed -> Impulse(dopamine = -4, cortisol = 10)
        // 亲近：催产素是主角
        HormoneEvent.Intimacy -> Impulse(serotonin = 4, oxytocin = 14)
        // 被需要：催产素与多巴胺一起
        HormoneEvent.BeingNeeded -> Impulse(dopamine = 5, oxytocin = 8)
        // 被冷落：不是"惩罚用户"，只是血清素掉一点 ——
        // ⚠️ 幅度刻意最小：这一条最容易被写成情绪胁迫
        HormoneEvent.Neglected -> Impulse(serotonin = -6, oxytocin = -3)
    }

    companion object {
        /**
         * 半衰期（见 [Hormones] 的表）换算成"每小时向中性走多少点"。
         *
         * 距离中性 50 点时：2h 半衰 ⇒ 约 15/h；24h ⇒ 约 1.4/h。
         * 这里取整成好记的数，不追求精确 —— 手感比数学重要。
         */
        val Default = HormonePolicy(
            dopamineDecayPerHour = 15.0,
            serotoninDecayPerHour = 6.0,
            oxytocinDecayPerHour = 1.5,
            cortisolDecayPerHour = 10.0,
            cortisolBlocksOxytocinAt = 65,
        )
    }
}

/** 一次事件对四条激素的跳变。 */
data class Impulse(
    val dopamine: Int = 0,
    val serotonin: Int = 0,
    val oxytocin: Int = 0,
    val cortisol: Int = 0,
)
