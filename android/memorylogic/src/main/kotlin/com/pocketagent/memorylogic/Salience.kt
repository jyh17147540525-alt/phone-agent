package com.pocketagent.memorylogic

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * **记忆的显著度** —— 会衰减，也会被加固；而且**衰减速度取决于它有多疼**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：为什么"遗忘"是像人最要紧的一步
 * ═══════════════════════════════════════════════════════════════
 *
 * 完美记忆是最明显的破绽之一。但真正像人**不是"会忘"** ——
 * 而是**"记得但记不清"**。
 *
 * 现有 [MemoryRecall] 只有两种结果：出现 / 不出现。人类有第三种：
 *
 * ```
 *   清晰    → "你上次说你妈在杭州住院"
 *   模糊    → "我记得你好像提过家里有人住院？"
 *   忘了    → （不提）
 * ```
 *
 * ⚠️ 中间那一档才是人的地方。它同时做了两件事：
 *   · 承认自己可能记错（人是会记错的）
 *   · 又没真的忘（关系还在）
 *
 * ⇒ 本文件提供三样东西：
 *   1. [Salience]  —— 一条记忆"有多清楚"
 *   2. [decay]     —— 随时间衰减，**情绪越强衰减越慢**
 *   3. [fidelity]  —— 衰减到什么程度该怎么说出来
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么情绪会拖慢遗忘（这不是装饰，是真实机制）
 * ═══════════════════════════════════════════════════════════════
 *
 * 人记得住"被骂的那天"却不记得"上周三中午吃了什么"。
 * 情绪唤醒会让记忆**巩固**（consolidation）—— 这是神经科学里很稳的结论。
 *
 * ⇒ [emotionalCharge] 越高，衰减系数越小。极端情况下几乎不忘。
 *   这让角色**记得住吵过的那一架**，而不是像现在这样全凭新鲜度排序。
 */
data class Salience(
    /** 记忆强度 0..1。到 [FORGOTTEN] 以下就等于忘了。 */
    val strength: Double,
    /** 情绪强度 0..1。越高越难忘。 */
    val emotionalCharge: Double,
    /** 上次被想起（或被加固）的时刻。 */
    val lastTouchedAt: Long,
    /** 被想起过几次。反复想起的记忆会被加固。 */
    val recallCount: Int = 0,
) {

    init {
        require(strength in 0.0..1.0) { "strength 必须落在 0..1，当前为 $strength" }
        require(emotionalCharge in 0.0..1.0) { "emotionalCharge 必须落在 0..1，当前为 $emotionalCharge" }
        require(recallCount >= 0) { "recallCount 不能为负：$recallCount" }
    }

    /** 彻底忘了。 */
    val isForgotten: Boolean get() = strength < FORGOTTEN

    /**
     * 说出来时的**确定程度**。
     *
     * ⚠️ 三档之间是**阶梯**，不是连续值 —— 因为提示词里需要的是
     *    "该怎么措辞"，而措辞只有几种，中间态没法表达。
     */
    fun fidelity(): Fidelity = when {
        strength >= EXACT -> Fidelity.EXACT
        strength >= FORGOTTEN -> Fidelity.HEDGED
        else -> Fidelity.GONE
    }

    companion object {
        /** 到这条线以下就算忘了。 */
        const val FORGOTTEN = 0.30
        /** 到这条线以上才算"记得清楚"。 */
        const val EXACT = 0.72
    }
}

/** 一条记忆被说出来时的确定程度。 */
enum class Fidelity {
    /** 记得清楚 —— 可以照实说。 */
    EXACT,

    /** 记得但模糊 —— 必须带不确定性措辞（"好像""隐约记得"）。 */
    HEDGED,

    /** 忘了 —— 不该出现。 */
    GONE,
}

/**
 * 记忆的演化 —— 纯函数，时间由调用方给。
 *
 * ⚠️ 与 `:personalogic` 的 `HormoneEngine` / `PersonaStateEngine` 同一纪律：
 *    显式传 `nowMs`，否则没法离线测试"过了一周会怎样"。
 */
object MemoryDecay {

    /** 一条记忆的基础遗忘速度（每小时强度衰减比例的对数尺度）。 */
    const val BASE_RATE = 0.010

    /** 情绪拉满时，衰减速度最多降到这个比例。 */
    const val MIN_EMOTION_FACTOR = 0.06

    /**
     * 随时间衰减。
     *
     * @param nowMs 当前时间
     * @return 新的显著度。**时间倒流（nowMs < lastTouchedAt）按不衰减处理** ——
     *   时钟被改不该让记忆凭空消失。
     */
    fun decay(s: Salience, nowMs: Long): Salience {
        val elapsed = nowMs - s.lastTouchedAt
        if (elapsed <= 0L) return s

        val hours = elapsed.toDouble() / 3_600_000.0
        // ★ 情绪越强，衰减越慢 —— 线性插值到 MIN_EMOTION_FACTOR
        val factor = 1.0 - s.emotionalCharge * (1.0 - MIN_EMOTION_FACTOR)
        val rate = BASE_RATE * factor
        val next = s.strength * exp(-rate * hours)

        // ⚠️ 数值上永远到不了 0，所以低于下限就直接归零 ——
        //    否则"忘了"这件事会变成"强度 0.0001 但仍算存在"。
        val clamped = if (next < 1e-4) 0.0 else next
        return s.copy(strength = clamped, lastTouchedAt = nowMs)
    }

    /**
     * 被想起一次 ⇒ 加固。
     *
     * ⚠️ 加固量**递减**（`(1-strength) * 0.35`）：已经记得很牢的事，
     *    再提一次也不会有多少增益。这既符合直觉，也防止强度无限冲顶。
     */
    fun reinforce(s: Salience, nowMs: Long, extraEmotion: Double = 0.0): Salience {
        val charge = (s.emotionalCharge + extraEmotion).coerceIn(0.0, 1.0)
        val gain = (1.0 - s.strength) * 0.35
        return s.copy(
            strength = (s.strength + gain).coerceIn(0.0, 1.0),
            emotionalCharge = charge,
            lastTouchedAt = nowMs,
            recallCount = s.recallCount + 1,
        )
    }

    /**
     * 从"这条记忆当时有多要紧"推出初始显著度。
     *
     * @param confidence 抽取时的置信度（见 [Atom.confidence]）
     * @param emotionalCharge 那一刻的情绪强度（由调用方从激素/冲突信号算）
     */
    fun born(confidence: Double, emotionalCharge: Double, atMs: Long): Salience =
        Salience(
            strength = confidence.coerceIn(0.0, 1.0),
            emotionalCharge = emotionalCharge.coerceIn(0.0, 1.0),
            lastTouchedAt = atMs,
        )

    /**
     * 把模糊的记忆**说成模糊的样子**。
     *
     * ⚠️ 这是本文件存在的意义所在 —— 见 [Fidelity] 的注释。
     *    返回 null 表示"想不起来"，调用方不该把它塞进提示词。
     */
    fun render(fidelity: Fidelity, text: String): String? = when (fidelity) {
        Fidelity.GONE -> null
        Fidelity.EXACT -> text
        // ⚠️ 措辞要**多样**：每次都"好像"会让角色像在背台词。
        //    用文本长度决定挑哪一句（同样的输入永远给同样的结果，可测试）。
        Fidelity.HEDGED -> {
            val openers = listOf("好像", "隐约记得", "我记得你提过", "之前是不是说过")
            val opener = openers[text.length % openers.size]
            "$opener，$text"
        }
    }
    /**
     * **这条记忆现在有多清楚** —— 从 [Atom] 直接算。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 情绪强度目前是**按类别估的**，不是真的
     * ═══════════════════════════════════════════════════════════════
     *
     * [Atom] 里没有"当时的情绪有多强"这个字段（写入端还没采它）。
     * 所以这里用**类别**做代理：
     *
     * | 类别 | 情绪权重 | 理由 |
     * |---|---|---|
     * | 约束 | 0.7 | "他不能吃辣"这类通常与在意的事有关 |
     * | 偏好 | 0.5 | "你是谁"的证据，比事实重 |
     * | 阶段结论 | 0.3 | 是归纳出来的，不是当场感受 |
     * | 事实 | 0.1 | "今天下雨"这种，本来就该忘 |
     *
     * ⚠️ 这是**暂时的**。等写入端开始记录真实情绪强度，
     *    这个函数应该改成读那个值 —— 代理永远不如真实信号。
     */
    fun fidelityOf(atom: Atom, nowMs: Long): Fidelity {
        val charge = when (atom.kind) {
            AtomKind.CONSTRAINT -> 0.7
            AtomKind.PREFERENCE -> 0.5
            AtomKind.STAGE_CONCLUSION -> 0.3
            AtomKind.FACT -> 0.1
        }
        val born = born(confidence = atom.confidence, emotionalCharge = charge, atMs = atom.at)
        return decay(born, nowMs).fidelity()
    }
}