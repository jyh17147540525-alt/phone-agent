package com.pocketagent.personalogic

import kotlin.math.abs
import kotlin.math.exp
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
 * | 维度 | 半衰期 | 依据 | 表达什么 |
 * |---|---|---|---|
 * | 多巴胺 | 约 30 分钟 | 产品量（相位 burst 只有 100–200ms，建不了） | 被点亮、期待、想做点什么 |
 * | 血清素 | 约 6 小时 | ATD 周转 5–7h（弱效应） | 稳定、满足、不那么容易被惹到 |
 * | 催产素 | 约 45 分钟 | 血浆 4.5min，受体后行为窗口 30–60min | 亲密、依恋、愿意靠近 |
 * | 皮质醇 | 约 66 分钟 | 血浆 t1/2 66min（Holland-Frei） | 压力、防备、想缩起来 |
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
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 2026-11-14：衰减从"线性"改为"指数"（半衰期口径）
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个文件顶上那张表写的本来就是**半衰期**（2h / 8h / 24h / 4h），
 * 但旧实现是 `每小时固定掉 N 点` —— 那是"分数还有多久归零"，
 * 不是半衰期。于是：
 *
 * · 从 90 掉到 70 和从 60 掉到 40 用一样久（真实是越接近中性越慢）；
 * · 表里 24h 的催产素实际 27 小时才回中，表里 2h 的多巴胺要 2.7 小时。
 *
 * 现在直接按半衰期算：`偏差(t) = 偏差(0) × 2^(-t / 半衰期)`。
 * 与 [SleepHomeostasis] 同一套指数语言，也更容易解释"为什么醒来
 * 那股兴奋就没了，但亲近感还在"。
 *
 * 出处：一类通用做法，见 SentiCore 的 `E(t) = Base + (E_prev - Base)e^(-λΔt)`
 * （https://github.com/chuchuyei/SentiCore ，AGPL —— **只借公式，没有抄代码**）。
 * 指数衰减本身是教科书内容，不构成衍生。
 *
 * ⚠️ 由此多了一个 [HormonePolicy.settleEpsilonPoints]：指数**永远**到不了中性，
 *    必须有"偏差小于多少就算回中"的门槛，否则"越过中性就停住"永远不会发生，
 *    激素会带着 0.3 点的尾数过一辈子。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 2026-11-14（同日）：半衰期从"手感"换成"人的数值"
 * ═══════════════════════════════════════════════════════════════
 *
 * 上一版 2h / 8h / 24h / 4h 是拍出来的（好看、好解释，但没有出处）。
 * 查过文献之后四个都换了，**其中三个短了很多**：
 *
 * | 维度 | 旧 | 新 | 为什么 |
 * |---|---|---|---|
 * | 多巴胺 | 2h | **30min** | 相位信号本身是百毫秒级，"被夸的高兴"半小时就该淡一半 |
 * | 血清素 | 8h | **6h** | 色氨酸耗竭的周转是 5–7h |
 * | 催产素 | 24h | **45min** | 血浆半衰期只有 4.5min，行为余韵 30–60min；24h 太重了 |
 * | 皮质醇 | 4h | **66min** | 血浆 t1/2 = 66min，应激后 60–90min 回基线 |
 *
 * 同时加了两条**真实存在、之前完全没有**的机制：
 *
 * · **HPA 负反馈**：皮质醇越高，回落越快（见 [HormoneEngine.advance]）。
 * · **夜间反应性下调**：同样一句凶话，凌晨两点的生理反应比上午十点小
 *   （见 [HormoneKinetics.stressReactivity]）。
 *
 * 全部出处与"哪些是文献值、哪些是产品标定"的分界写在 [HormoneKinetics]。
 *
 * ⚠️ 换短之后的直接后果：**激素不再负责"记着"**。
 *    半小时前被夸，现在只剩 1/4 的多巴胺；而"她记得你夸过她"这件事
 *    属于记忆与关系线，不属于激素。这两层分开是对的 ——
 *    旧参数让一次互动影响一整天，反而把"记忆"和"当下状态"混成了一摊。
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

    /**
     * 给**后台 AI** 看的档位（人话，不含数字，也不含术语）。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 为什么不给它 0–100
     * ═══════════════════════════════════════════════════════════════
     *
     * 模型看到数字就会把数字抄进表演指导（"你现在皮质醇 72，所以话短一点"），
     * 而前台读到数字立刻出戏 —— 用户也会从语气里感觉到"被测量"。
     * 档位足够它判断强弱，且抄不出禁用词。
     *
     * ⚠️ 这里**刻意不出现「皮质醇」「催产素」这些词**：后台模型很爱照抄输入，
     *    输入里写了，输出里就会带着，然后被 [PerformanceGate] 的禁词表拒掉。
     *    用纯行为词，它抄出来的正好是能用的句子。
     *
     * 这也是 [Performance.hormonesKey] 的来源：档位没变 = 状态没变，
     * 不允许换措辞（见 [PerformanceGate.accept] 的第三条）。
     */
    fun portrait(): String {
        val bits = buildList {
            if (cortisol >= STRONG) add("绷着，容易烦") else if (cortisol >= 65) add("有点紧")
            if (oxytocin >= STRONG) add("很想靠近") else if (oxytocin >= 65) add("想多聊两句")
            if (serotonin <= LOW) add("情绪底子薄") else if (serotonin <= 40) add("情绪偏低")
            if (dopamine >= STRONG) add("来劲") else if (dopamine <= LOW) add("提不起劲")
        }
        return if (bits.isEmpty()) "平稳" else bits.joinToString("、")
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

    /**
     * 事件 → 激素跳变（**不含**互锁门控，见 [step]）。
     *
     * ⚠️ 这里已经有"饱和"：越接近上限，同样的好事推动得越少
     *    （见 [dampen]）。除此之外不做任何状态相关的调节 ——
     *    需要"紧张时乐不起来""脆弱时更疼"这类耦合，请走 [step]。
     */
    fun apply(state: Hormones, event: HormoneEvent): Hormones {
        val d = policy.impulseOf(event)
        return make(
            state.dopamine + dampen(d.dopamine, state.dopamine),
            state.serotonin + dampen(d.serotonin, state.serotonin),
            state.oxytocin + dampen(d.oxytocin, state.oxytocin),
            state.cortisol + dampen(d.cortisol, state.cortisol),
        )
    }

    /**
     * 时间流逝 → 向中性回落。
     *
     * ★ 规则 1–3（有层次的来源，不是装饰；**完整 10 条见 [step] 的表格**）：
     *
     * 1. **高皮质醇抑制催产素上升** —— 正在紧张的时候，亲近是攒不起来的。
     *    这一条在 [step] 里统一处理（它需要看到**事件**）。
     * 2. **低血清素让皮质醇落得慢** —— 情绪不稳时，压力会黏住。
     * 3. **高催产素让血清素回升更快** —— 有人陪着，恢复得快。
     *
     * ⚠️ 规则 2/3 改的是**半衰期**（规则 2 让半衰期翻倍 = 落得慢；
     *    规则 3 让半衰期 ÷1.5 = 回得快）。改之前它们是"速率"，
     *    方向正好相反 —— 换成半衰期时最容易写反的地方就是这里。
     *
     * ★ 2026-11-14 新增**规则 8：HPA 负反馈**（见下方 `advance` 里的实现）。
     *   它与规则 2 方向相反：写字楼里熬到血清素见底时压力会黏住（规则 2），
     *   但皮质醇本身冲到高位时清除反而加快（规则 8）。
     *   同时成立时的净效果仍偏向"黏住"：规则 2 先乘 2，规则 8 最多除 1.6。
     */
    fun advance(state: Hormones, elapsedMs: Long): Hormones {
        if (elapsedMs <= 0) return state
        val hours = elapsedMs.toDouble() / MS_PER_HOUR

        // 规则 2：低血清素 ⇒ 皮质醇半衰期翻倍（压力黏住）
        val cortisolBase = if (state.serotonin <= Hormones.LOW) {
            policy.cortisolHalfLifeHours * 2.0
        } else {
            policy.cortisolHalfLifeHours
        }
        // ★ 规则 8：HPA 负反馈 —— 皮质醇越高，回落越快。
        // 偏离中性的部分归一到 0..1（50→0，100→1），有效半衰期除以 (1 + 0.6×该值)。
        // ⇒ 100 分时半衰期是基线的 0.62 倍；75 分时是 0.77 倍；50 分及以下不变。
        val cortisolLoad =
            ((state.cortisol - Hormones.NEUTRAL).coerceAtLeast(0).toDouble() / Hormones.NEUTRAL)
                .coerceIn(0.0, 1.0)
        val cortisolHalfLife = cortisolBase / (1.0 + policy.hpaNegativeFeedbackGain * cortisolLoad)
        // 规则 3：高催产素 ⇒ 血清素半衰期缩短（往中性走得更快）
        val serotoninHalfLife = if (state.oxytocin >= Hormones.STRONG) {
            policy.serotoninHalfLifeHours / 1.5
        } else {
            policy.serotoninHalfLifeHours
        }

        return make(
            decay(state.dopamine, hours, policy.dopamineHalfLifeHours),
            decay(state.serotonin, hours, serotoninHalfLife),
            decay(state.oxytocin, hours, policy.oxytocinHalfLifeHours),
            decay(state.cortisol, hours, cortisolHalfLife),
        )
    }

    /**
     * 事件 + 时间一步到位（带**全部互锁规则**）。
     *
     * ⚠️ 用这个而不是分别调 `advance` 和 `apply`：门控需要在
     *    **同一次**推进里看到"当前的激素底子"，分开调会漏掉它。
     *
     * ★ 2026-11-14 新增三条互锁（原来的只有规则 1）：
     *
     * | # | 条件 | 效果 | 为什么 |
     * |---|---|---|---|
     * | 1 | 皮质醇高 | 催产素增益减半 | 紧张时亲近攒不起来 |
     * | 4 | 皮质醇高 | 多巴胺增益减半 | 绷着的时候，好事也乐不起来 |
     * | 5 | 血清素低 + 负面事件 | 皮质醇增益 ×1.5 | 脆弱时同一件事扎得更深 |
     * | 6 | 多巴胺高 + 负面事件 | 皮质醇增益 ×0.7 | 正兴奋时对压力钝一点 |
     * | 10 | 催产素高 + 负面事件 | 皮质醇增益 ×0.75 | 有人在，同样的话扎得浅一点（元分析 d=0.83） |
     *
     * ⚠️ 规则 5 和 6 可能同时成立（低血清素 + 高多巴胺 = 亢奋不稳）。
     *    这里让 **5 优先** —— 脆的那一面压过钝的那一面，
     *    因为"亢奋时被戳穿"比"亢奋时被安慰"更常见也更真实。
     *
     * ★ **累积顺序**（结果与顺序有关，所以钉死）：5/6 选一个 → 10 缓冲 → 9 昼夜。
     *    最极端的一例：Conflict(18) ×1.5（血清素低）= 27，×0.75（有人在）= 20，
     *    再 ×0.85（凌晨）= 17 —— 比完全不设防的 27 小了三分之一，
     *    但仍然比"她的默认状态"疼。这个梯度是有意的。
     *
     * ⚠️ 规则 10 只**削负面事件的幅度**，不产生任何正向收益：
     *    聊天不会因为"她开心"就少挨骂。别把它改成"陪着就不疼"。
     *
     * ★ **证据强度不一样，别一视同仁**（2026-11-14 逐条对过文献）：
     *
     * | 规则 | 依据强度 |
     * |---|---|
     * | 8 HPA 负反馈 | **机制明确**：糖皮质激素抑制人下丘脑 CRH/AVP（Erkut 1998）+ TSST 60–90min 必回基线 |
     * | 9 昼夜反应性 | 方向明确（HPA 昼夜节律），幅度是标定；白天不分上下午（那层证据互相矛盾） |
     * | 10 社会支持 | **d = 0.83**（元分析，行为层）+ 催产素抑制 CRH（动物电生理）；系数标定 |
     * | 1 紧张时亲近攒不起来 | 方向有依据（应激抑制催产素系统），减半是标定 |
     * | 4 绷着时好事也乐不起来 | 方向有依据（急性压力降低奖赏相关神经活动），减半是标定 |
     * | 5 脆弱时更疼 | 方向有依据（5-HT 对 HPA 是抑制性的），×1.5 是标定 |
     * | 2 低血清素压力黏住 | 方向有依据（同上），×2 是标定 |
     * | 6 兴奋时对压力钝 | **纯产品假设**，只有间接旁证 |
     * | 3 高催产素血清素回得快 | **纯产品假设**，没有文献支持这个方向 |
     * | 7 饱和 | 借享乐适应最朴素的那一层（同刺激、基线越高、增量越小） |
     *
     * ⇒ 前三条可以替参数辩护；后面的数字只能替"方向"辩护。
     *
     * ★ 2026-11-14 新增**规则 9：昼夜反应性**（只在给了 `hourOfDay` 时生效）。
     *    同样的负面事件，夜里 22:00–06:00 的皮质醇增益要乘 0.85 —— 那段时间
     *    HPA 轴处在昼夜谷值，分泌能力本身就低。白天不区分上午下午。
     */
    fun step(
        state: Hormones,
        event: HormoneEvent?,
        elapsedMs: Long,
        /**
         * 当前时刻（0.0–24.0，小数小时）。`null` = 不做昼夜校正，
         * 便于测试与离线回放；线上由 `PersonaRuntime` 传真实时间。
         */
        hourOfDay: Double? = null,
    ): Hormones {
        val advanced = advance(state, elapsedMs)
        if (event == null) return advanced

        val base = policy.impulseOf(event)
        val pressured = advanced.cortisol >= policy.cortisolBlocksOxytocinAt

        // ★ 规则 1：高皮质醇时，催产素的**增益**减半。
        val oxytocinGain = if (pressured) base.oxytocin / 2 else base.oxytocin
        // ★ 规则 4：同理，多巴胺的正向增益也减半（负向不动，本来就该掉）。
        val dopamineGain = if (pressured && base.dopamine > 0) base.dopamine / 2 else base.dopamine

        // ★ 规则 5 / 6：只作用于"往上顶"的皮质醇（负面事件）。
        val cortisolGain = when {
            base.cortisol > 0 && advanced.serotonin <= Hormones.LOW ->
                (base.cortisol * 1.5).roundToInt()
            base.cortisol > 0 && advanced.dopamine >= Hormones.STRONG ->
                (base.cortisol * 0.7).roundToInt()
            else -> base.cortisol
        }

        // ★ 规则 10：社会支持缓冲。催产素在场时，同样的重话扎得浅一点。
        //   元分析效应量 d = 0.83（社会支持 vs 无支持对皮质醇），折算成 ×0.75。
        val cortisolBuffered =
            if (cortisolGain > 0 && advanced.oxytocin >= policy.socialBufferOxytocinAt) {
                (cortisolGain * policy.socialBufferGain).roundToInt()
            } else {
                cortisolGain
            }

        // ★ 规则 9：昼夜反应性。只削"往上顶"的那部分，且只削一点点（夜间 ×0.85）。
        val cortisolAdjusted = if (hourOfDay != null && cortisolBuffered > 0) {
            (cortisolBuffered * HormoneKinetics.stressReactivity(hourOfDay)).roundToInt()
        } else {
            cortisolBuffered
        }

        return make(
            advanced.dopamine + dampen(dopamineGain, advanced.dopamine),
            advanced.serotonin + dampen(base.serotonin, advanced.serotonin),
            advanced.oxytocin + dampen(oxytocinGain, advanced.oxytocin),
            advanced.cortisol + dampen(cortisolAdjusted, advanced.cortisol),
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
     * ★ 指数：`偏差(t) = 偏差(0) × 2^(-t / 半衰期)`。
     *
     * ⚠️ 与 [PersonaStateEngine] 同一条理由：人会回到**平时的样子**，
     *    不会一直往谷底掉。若目标是 0，"长期没人理"会让它无限消沉 ——
     *    那既不像人，也是情绪胁迫。
     *
     * ★ 换成指数之后，三条性质是**算出来的**，不再依赖巧合：
     * · 越接近中性走得越慢（这正是半衰期的含义）；
     * · 永远不会越过中性（指数恒正），所以方向判断可以删掉；
     * · 但也**永远到不了**中性 —— 必须有 [HormonePolicy.settleEpsilonPoints]
     *   兜底，否则 `60` 会带着 `50.3` 过一辈子，"回中"的断言全部不成立。
     */
    private fun decay(value: Int, hours: Double, halfLifeHours: Double): Int {
        if (halfLifeHours <= 0.0) return value
        val delta = value - Hormones.NEUTRAL
        if (delta == 0) return value
        val remaining = delta * exp(-LN2 * hours / halfLifeHours)
        return if (abs(remaining) < policy.settleEpsilonPoints) {
            Hormones.NEUTRAL
        } else {
            (Hormones.NEUTRAL + remaining).roundToInt()
        }
    }

    /**
     * **饱和**：已经站在高处时，同样的好事推动得更少。
     *
     * ★ 借的是"享乐适应"（hedonic adaptation，Frederick & Loewenstein 1999）
     *   最朴素的那一层：**同一份刺激，基线越高，主观增量越小**。
     *
     * 实现上刻意**不存历史**（不记"今天被夸了几次"）：
     * 项目纪律是状态必须能从"当前时刻 + 当前状态"算出来，
     * 存一份"今天被夸次数"就得处理跨天清零、时区、重装丢失 ——
     * 而它换来的只是更细的手感。用当前值当基线已经够用，
     * 而且它自动具备"刚被夸过就再夸，效果递减"的效果。
     *
     * 公式：`增益 × (0.5 + 0.5 × (100 - 当前值) / 50)`，夹在 0.5..1 之间。
     * ⇒ 在中性 50 时系数 1.0（跟以前一样），在 100 时只剩 0.5。
     * ⇒ **只削正向**。负向不减半：坏事在高位时不该打折，
     *    "乐极生悲"是真实存在的。
     */
    private fun dampen(gain: Int, current: Int): Int {
        if (gain <= 0 || !policy.saturateNearCeiling) return gain
        val headroom =
            ((Hormones.MAX - current).toDouble() / (Hormones.MAX - Hormones.NEUTRAL))
                .coerceIn(0.0, 1.0)
        return (gain * (0.5 + 0.5 * headroom)).roundToInt()
    }

    companion object {
        const val MS_PER_HOUR = 3_600_000.0

        /** `ln 2`，半衰期公式用。 */
        private const val LN2 = 0.6931471805599453
    }
}

/**
 * 每条激素的**半衰期**与事件脉冲。
 *
 * ⚠️ 字段是 `HalfLifeHours`，不是 `DecayPerHour`。2026-11-14 之前存的是
 *    "每小时掉多少点"，与文件顶那张表的"半衰期"是两套语言 ——
 *    表里写"多巴胺 2 小时"，代码里配 15/h，实际 2.7 小时才回中。
 *    改名就是为了让"表里写 2h"和"代码里写 2.0"变成同一件事。
 *
 * 半衰期填 `0` = 关掉这条的回落（给测试与"冻结状态"用）。
 */
data class HormonePolicy(
    val dopamineHalfLifeHours: Double,
    val serotoninHalfLifeHours: Double,
    val oxytocinHalfLifeHours: Double,
    val cortisolHalfLifeHours: Double,
    /** 皮质醇高到这里，催产素与多巴胺的**正向**增益减半（规则 1 / 4）。 */
    val cortisolBlocksOxytocinAt: Int,
    /**
     * 正向脉冲是否随基线升高而饱和（规则 7，见 `HormoneEngine.dampen`）。
     *
     * 关掉它的唯一理由是**对照实验**：想确认某个手感变化是不是饱和造成的。
     */
    val saturateNearCeiling: Boolean = true,
    /**
     * 距中性的偏差小于这个点数，就直接落回 50。
     *
     * ⚠️ 必须存在。指数衰减是渐近的，`60` 出发两小时后是 `50.03`，
     *    十小时后还是 `50.0001` —— 不设门槛，"回中"永远不成立，
     *    而 UI 上会看到一个永远不变的微小偏移。
     *    0.5 的意思是"半个点以内，人听不出来"。
     */
    val settleEpsilonPoints: Double = 0.5,
    /**
     * **HPA 负反馈强度**：皮质醇偏离中性越高，有效半衰期越短。
     *
     * 机制：糖皮质激素受体（GR）介导的负反馈抑制 CRH/ACTH，叠加肝脏清除加快。
     * 效果是"高压时恢复得更快" —— 这和直觉相反（越高越难下来），
     * 但真实测量就是如此：TSST 之后 60–90 分钟一定回到基线，
     * 而不是"升得越高拖得越久"。
     *
     * ⚠️ 系数本身是产品标定（见 [HormoneKinetics.HPA_NEGATIVE_FEEDBACK_GAIN]）。
     *    填 0 = 关掉这条，退回"固定半衰期"，给对照测试用。
     */
    val hpaNegativeFeedbackGain: Double = HormoneKinetics.HPA_NEGATIVE_FEEDBACK_GAIN,
    /**
     * **社会支持缓冲**：催产素高到 [socialBufferOxytocinAt] 时，
     * 负面事件的皮质醇增量乘这个系数（规则 10，默认 0.75）。
     *
     * 依据：Thorsteinsson & James 1999 元分析（22 项实验、26 组比较、56 个效应量）——
     * 社会支持对**皮质醇**反应的效应量 **d = 0.83**，比心率（0.61）、
     * 舒张压（0.51）、皮电（0.25）都大。
     * <https://doi.org/10.1080/08870449908407353>
     *
     * 机制：催产素能神经元对下丘脑 CRH 神经元有抑制性输入，
     * 社会接触释放的催产素直接掐住 HPA 轴最上游。
     *
     * ⚠️ 0.75 是**产品标定**：文献支持"这一条该有、且效应不小"，
     *    支持不了"就是 0.75"。填 1.0 = 关掉这条（对照用）。
     */
    val socialBufferGain: Double = HormoneKinetics.SOCIAL_SUPPORT_CORTISOL_GAIN,
    /** 催产素高到多少才算"支持在场"（[socialBufferGain] 的触发线）。 */
    val socialBufferOxytocinAt: Int = 65,
) {

    init {
        require(dopamineHalfLifeHours >= 0) { "半衰期不能为负：$dopamineHalfLifeHours" }
        require(serotoninHalfLifeHours >= 0) { "半衰期不能为负：$serotoninHalfLifeHours" }
        require(oxytocinHalfLifeHours >= 0) { "半衰期不能为负：$oxytocinHalfLifeHours" }
        require(cortisolHalfLifeHours >= 0) { "半衰期不能为负：$cortisolHalfLifeHours" }
        require(settleEpsilonPoints >= 0) { "归中门槛不能为负：$settleEpsilonPoints" }
        require(hpaNegativeFeedbackGain >= 0) { "负反馈系数不能为负：$hpaNegativeFeedbackGain" }
        require(socialBufferGain in 0.0..1.0) { "社会缓冲系数应落在 0..1：$socialBufferGain" }
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
         * 默认半衰期＝[Hormones] 顶上那张表的值，而那四个值来自 [HormoneKinetics]。
         *
         * ★ 表就是配置：以前表里的数字只是"象征性"的，真正生效的是
         *   15/h、6/h、1.5/h、10/h 四个从别处换算来的速率，改表没有任何效果。
         *
         * ⚠️ 0.75 与 1.1 是小数小时（45min / 66min），不是"大概 1 小时"。
         *    写成整数会让"半小时内格外黏"变成"一整天都黏"。
         */
        val Default = HormonePolicy(
            dopamineHalfLifeHours = HormoneKinetics.DOPAMINE_BEHAVIOR_HALF_LIFE_MINUTES / 60.0,
            serotoninHalfLifeHours = HormoneKinetics.SEROTONIN_BEHAVIOR_HALF_LIFE_HOURS,
            oxytocinHalfLifeHours = HormoneKinetics.OXYTOCIN_BEHAVIOR_HALF_LIFE_MINUTES / 60.0,
            cortisolHalfLifeHours = HormoneKinetics.CORTISOL_PLASMA_HALF_LIFE_MINUTES / 60.0,
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
