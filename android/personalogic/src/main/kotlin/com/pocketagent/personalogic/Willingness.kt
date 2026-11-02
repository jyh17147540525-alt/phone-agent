package com.pocketagent.personalogic

import kotlin.math.roundToInt

/**
 * **意愿** —— 它今天想不想聊。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：为什么这是"像人"的最后一块硬骨头
 * ═══════════════════════════════════════════════════════════════
 *
 * 现在的角色**永远有空、永远愿意**。这不是温柔，这是破绽 ——
 * 比语言风格致命得多。人会说"今天不想聊"。
 *
 * ⚠️ 但它也是**最容易做坏**的一条：做重了，用户觉得"我花钱买的
 *    AI 凭什么不理我"，第一次被冷落就卸载。
 *
 * ⇒ 三条设计纪律：
 *
 * **① 拒绝必须罕见。**
 *    低于 [DECLINE_BELOW] 才拒；而正常使用下意愿**很少**掉到那里。
 *    门槛定得低，是为了让"被拒绝"成为一个有分量的事件，
 *    而不是随机噪音。
 *
 * **② 拒绝后要缓过来。**
 *    拒绝一次会让意愿回升一点（人也是：说出口之后反而轻松些）。
 *    不然它会连续三轮拒绝，那已经不是人，是坏掉的机器。
 *
 * **③ 时间是最好的恢复。**
 *    睡一觉一切都好了 —— 每小时恢复 [RECOVER_PER_HOUR]，
 *    比任何"安慰"都有效。这也是唯一不需要用户操作就能恢复的路径。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它与激素的分工
 * ═══════════════════════════════════════════════════════════════
 *
 * | | 激素 | 意愿 |
 * |---|---|---|
 * | 管什么 | **怎么说**（语气、长度、防备）| **要不要接** |
 * | 恢复 | 各自半衰期 | 时间 + 拒绝后的释然 |
 *
 * 高皮质醇会**压低**意愿 —— 但不是直接映射：吵架之后它可能
 * 依然愿意聊，只是话说得短。所以两者独立演化，只在 `pressure` 里耦合。
 */
data class Willingness(
    /** 0..100。默认 [DEFAULT]。 */
    val value: Int = DEFAULT,
    /** 上次推进的时间。 */
    val lastTouchedAt: Long = 0L,
    /**
     * 最近一次拒绝的轮次序号。**null = 从没拒过**。
     *
     * ⚠️ 用途是**限频**：见 [WillingnessEngine.verdictOf]。
     *    没有它，意愿低的时候会**连续**拒绝，看起来像坏了。
     *
     * ⚠️ 用 null 而不是 `Int.MIN_VALUE` 这种哨兵值 ——
     *    测试抓到过：`turnIndex - Int.MIN_VALUE` 会**整型溢出**，
     *    溢出的结果是负数，于是"从没拒过"被读成"刚拒过"，
     *    冷却永远不成立 ⇒ **永远不会拒绝**。这是静默的。
     */
    val lastDeclineAtTurn: Int? = null,
) {

    init {
        require(value in 0..100) { "willingness 必须落在 0..100，当前为 $value" }
    }

    companion object {
        const val DEFAULT = 70
        /** 低于这个值才会**明确拒绝**。刻意定得低 —— 见类注释纪律 ① */
        const val DECLINE_BELOW = 22
        /** 低于这个值回复会变短、不主动。 */
        const val LOW_BELOW = 42
        /** 每小时恢复多少点。睡一觉（8h）能回满大半。 */
        const val RECOVER_PER_HOUR = 5.0
        /** 两次拒绝之间至少隔多少轮。 */
        const val DECLINE_COOLDOWN_TURNS = 12
    }
}

/** 这一轮该怎么对待用户。 */
enum class WillingnessVerdict {
    /** 正常。 */
    EAGER,

    /** 话变短、不主动挑起话题，但仍然回应。 */
    LOW,

    /** 明确说"现在不太想聊"。**不调用模型**，省一次请求。 */
    DECLINE,
}

/**
 * 意愿的演化 —— 纯函数。
 *
 * ⚠️ 与 `HormoneEngine` / `MemoryDecay` 同一纪律：时间由调用方给，
 *    否则"睡一觉之后好没好"没法离线测。
 */
class WillingnessEngine(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 时间流逝 ⇒ 恢复。
     *
     * ★ 恢复是**线性**的，不是指数回落 —— 与激素不同：
     *   激素回落的目标是"中性"，而意愿的目标是"满"。
     *   人休息够了就是满血，不是"回到平常"。
     */
    fun advance(state: Willingness, nowMs: Long): Willingness {
        val elapsed = nowMs - state.lastTouchedAt
        if (elapsed <= 0L) return state.copy(lastTouchedAt = maxOf(state.lastTouchedAt, nowMs))
        val hours = elapsed.toDouble() / 3_600_000.0
        val recovered = state.value + (Willingness.RECOVER_PER_HOUR * hours).roundToInt()
        return state.copy(
            value = recovered.coerceIn(0, 100),
            lastTouchedAt = nowMs,
        )
    }

    /**
     * 一轮对话的消耗。
     *
     * @param turnIndex 本次会话里的第几轮（用于限频判断）
     * @param cortisol 当前皮质醇（0..100）。高压力会更耗神
     * @param isLongSession 是不是已经连续聊了很多轮
     */
    fun spend(
        state: Willingness,
        turnIndex: Int,
        cortisol: Int = Hormones.NEUTRAL,
        isLongSession: Boolean = false,
    ): Willingness {
        // 基础消耗：每轮 -1（聊天本身就耗神）
        var cost = 1
        // 长会话翻倍 —— 连着聊三十轮和偶尔回一句不是一回事
        if (isLongSession) cost += 1
        // 高皮质醇再额外耗 —— 绷着说话最累
        if (cortisol >= Hormones.STRONG) cost += 2
        return state.copy(value = (state.value - cost).coerceIn(0, 100))
    }

    /**
     * 这一轮该怎么对待用户。
     *
     * ⚠️ **限频**在这里：即使意愿很低，刚拒过就不再拒 ——
     *    连续拒绝不是"有性格"，是"坏了"。
     */
    fun verdictOf(state: Willingness, turnIndex: Int): WillingnessVerdict = when {
        state.value < Willingness.DECLINE_BELOW &&
            (
                state.lastDeclineAtTurn == null ||
                    turnIndex - state.lastDeclineAtTurn >= Willingness.DECLINE_COOLDOWN_TURNS
                ) ->
            WillingnessVerdict.DECLINE
        state.value < Willingness.LOW_BELOW -> WillingnessVerdict.LOW
        else -> WillingnessVerdict.EAGER
    }

    /**
     * 拒绝之后记一笔，并**让意愿回升一点**。
     *
     * ★ 人把"不想聊"说出口之后会轻松一些 —— 这不是玄学，
     *   是"边界被表达了"带来的缓解。没有这一步，它会连拒三轮。
     */
    fun afterDecline(state: Willingness, turnIndex: Int): Willingness = state.copy(
        value = (state.value + DECLINE_RELIEF).coerceIn(0, 100),
        lastDeclineAtTurn = turnIndex,
    )

    /**
     * 意愿低时该怎么说话 —— 写成提示词。
     *
     * ⚠️ 只写**真的偏低**的那一档，且**极短**（项目已记过：
     *    长禁令清单会让模型变僵）。
     */
    fun promptSection(verdict: WillingnessVerdict): String? = when (verdict) {
        WillingnessVerdict.EAGER -> null
        WillingnessVerdict.LOW ->
            "## 此刻的意愿\n" +
                "你现在有点提不起劲。回得短一点，别主动开新话题，也别硬撑热情。"
        WillingnessVerdict.DECLINE ->
            // ⚠️ 这一句会**直接被说出来**，所以措辞要像人，不要像系统消息
            ""
    }

    companion object {
        /** 拒绝一次之后意愿回升多少。 */
        const val DECLINE_RELIEF = 8
    }
}

/**
 * **拒绝时说的那句话**。
 *
 * ⚠️ 与提示词分开：拒绝是**不经过模型**的（省一次请求），
 *    所以这些话是本地文案，不是生成出来的。
 *
 * ★ 三条要求：
 * • 说**自己**的状态，不说用户的问题（"我今天累了"，不是"你太烦了"）
 * • **留门**：明确表示不是永久的
 * • 短。拖长了像在找借口
 */
object DeclineLines {

    /**
     * 按情绪档挑一句。
     *
     * @param cortisol 皮质醇。高的时候更想要独处
     * @param turnNumber 用于制造变化 —— 每次都是同一句会像自动回复
     */
    fun pick(cortisol: Int, turnNumber: Int): String {
        val pool = if (cortisol >= Hormones.STRONG) {
            listOf(
                "今天有点疲惫，想自己待一会儿。晚点再说好吗。",
                "我现在状态不太好，不太想说话。不是你的问题。",
                "让我缓一缓吧，过一阵再聊。",
            )
        } else {
            listOf(
                "今天不太想聊，先歇会儿。",
                "有点累，晚点再来找我吧。",
                "现在提不起劲，回头再说。",
            )
        }
        return pool[turnNumber.mod(pool.size)]
    }
}
