package com.pocketagent.personalogic

import kotlin.math.roundToInt

/**
 * **生理系统** —— 它现在几点、睡了没有、坐了多久。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-02：激素有了，生理没有
 * ═══════════════════════════════════════════════════════════════
 *
 * 之前 `energy` **只由事件驱动**（被夸 +4、干活 -6），
 * 完全不看时钟。于是：
 *
 * ```
 *   凌晨 3:00  → 精神抖擞，一样能聊
 *   上午 10:00 → 一模一样
 * ```
 *
 * ⚠️ 这是**最容易被察觉的假**：用户凌晨找它，它比他还清醒。
 *
 * ⇒ 本文件补的就是"现在几点"这一维。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 只做能**确定性算出**的事
 * ═══════════════════════════════════════════════════════════════
 *
 * 明确**不做**的：
 * · 饥饿 —— 我们不知道它什么时候吃的。编一个"我刚吃完"就是撒谎。
 * · 生病 / 生理期 —— 无中生有的事件，用户会问"你怎么了"，
 *   而它答不出一个真的由头（状态必须有由头，这是既有纪律）。
 * · 体温 / 心率 —— 文字里无法体现，纯装饰。
 *
 * 只做这两件（都能从时钟 + 会话长度算出来）：
 * 1. **昼夜节律** —— 24 小时的能量曲线
 * 2. **睡眠债** —— 深夜硬聊会累积，第二天真的会钝
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 时区：用**用户的本地时间**
 * ═══════════════════════════════════════════════════════════════
 *
 * 角色过的应该是用户的"一天"。它跟用户在同一个作息里，
 * 而不是过 UTC。
 */
@kotlinx.serialization.Serializable
data class Physiology(
    /**
     * **睡眠债**（小时）。
     *
     * ⚠️ 累积规则：深夜（23:00–05:00）每聊一轮 +0.25 小时。
     *    四轮 = 欠一小时。刻意不大 —— 聊两轮就"困得不行"是矫情。
     */
    val sleepDebtHours: Double = 0.0,
    /** 上次交互的时刻。用于算"连坐多久"。 */
    val lastActiveAtMs: Long = 0L,
    /** 本次连续交互开始于何时。0 = 没有进行中的会话。 */
    val sessionStartAtMs: Long = 0L,
) {

    init {
        require(sleepDebtHours >= 0.0) { "睡眠债不能为负：$sleepDebtHours" }
    }

    /** 欠得厉害（>= 4 小时）。 */
    val isSleepDeprived: Boolean get() = sleepDebtHours >= HEAVY_DEBT

    /** 连着坐了多久（小时）。 */
    fun sittingHours(nowMs: Long): Double =
        if (sessionStartAtMs <= 0L) 0.0
        else ((nowMs - sessionStartAtMs).coerceAtLeast(0L)) / 3_600_000.0

    companion object {
        /** 到这个量就算"睡不够"了。 */
        const val HEAVY_DEBT = 4.0
        /** 单轮深夜交互累积的睡眠债。 */
        const val DEBT_PER_NIGHT_TURN = 0.25
        /** 睡眠债上限。再多也只是"困"，不该无限。 */
        const val MAX_DEBT = 12.0
        /** 每小时自然恢复多少债（不交互就是在休息）。 */
        const val DEBT_RECOVER_PER_HOUR = 0.5
    }
}

/**
 * 生理状态的演化与表达 —— 纯函数，**时间全部由调用方给**。
 *
 * ⚠️ 与 `HormoneEngine` / `MemoryDecay` / `WillingnessEngine` 同一纪律：
 *    不读时钟，否则"凌晨三点会怎样"没法离线测。
 */
object PhysiologyEngine {

    /**
     * **24 小时的清醒度曲线**（0..1）。
     *
     * ★ 用**锚点 + 线性插值**而不是三角函数：
     *   三角函数的峰值位置很难调，而这里每一段都有明确的语义
     *   （"四点最困""十点最清醒""下午三点犯困"）。
     *   用锚点，改一个数就能调一段。
     *
     * @param hourOfDay 0..24 的本地小时（可以带小数：13.5 = 下午一点半）
     */
    fun alertness(hourOfDay: Double): Double {
        val h = ((hourOfDay % 24.0) + 24.0) % 24.0
        val anchors = ALERTNESS_ANCHORS
        // 找到 h 落在哪一段
        for (i in 0 until anchors.size - 1) {
            val (h0, a0) = anchors[i]
            val (h1, a1) = anchors[i + 1]
            if (h >= h0 && h <= h1) {
                val t = if (h1 == h0) 0.0 else (h - h0) / (h1 - h0)
                return a0 + (a1 - a0) * t
            }
        }
        // 跨零点：最后一段 → 第一段
        val (h0, a0) = anchors.last()
        val (h1, a1) = anchors.first()
        val span = (24.0 - h0) + h1
        val t = if (span == 0.0) 0.0 else ((h - h0 + 24.0) % 24.0) / span
        return a0 + (a1 - a0) * t
    }

    /**
     * 一天里的锚点。`(小时, 清醒度)`
     *
     * ⚠️ 这些是**产品判断**，不是生理学常数。理由：
     *    · 04:00 最低（0.10）—— 人的昼夜节律谷底就在这个点
     *    · 10:00 峰一（0.95）—— 上午状态最好
     *    · 14:30 午后低谷（0.42）—— 饭后困，这是普遍现象
     *    · 17:00 峰二（0.78）—— 傍晚回光返照
     *    · 23:00 之后快速下滑
     */
    private val ALERTNESS_ANCHORS: List<Pair<Double, Double>> = listOf(
        0.0 to 0.32,
        2.0 to 0.16,
        4.0 to 0.10,
        6.0 to 0.34,
        8.0 to 0.78,
        10.0 to 0.95,
        12.0 to 0.80,
        14.5 to 0.42,
        17.0 to 0.78,
        19.0 to 0.74,
        21.0 to 0.60,
        23.0 to 0.42,
    )

    /**
     * 生理对 [PersonaState.energy] 的**修正值**。
     *
     * ⚠️ 是**修正**不是替代：事件仍然能让人精神一振
     *    （凌晨收到一句夸奖也会 +4），但底子由节律决定。
     *
     * 范围大约 -32..+22。
     */
    fun energyModifier(body: Physiology, hourOfDay: Double): Int {
        // 0.6 是"平常"的基准线：高清醒 +，低清醒 -
        val circadian = ((alertness(hourOfDay) - 0.6) * 40.0)
        // 睡眠债：每欠 1 小时扣 4 点，封顶 20
        val debt = -(body.sleepDebtHours.coerceAtMost(5.0) * 4.0)
        return (circadian + debt).roundToInt()
    }

    /**
     * 时间流逝 ⇒ 恢复睡眠债。
     *
     * ★ 恢复是**自然发生**的：用户不来的那几个小时里它就在睡。
     */
    fun advance(body: Physiology, elapsedMs: Long): Physiology {
        if (elapsedMs <= 0L) return body
        val hours = elapsedMs.toDouble() / 3_600_000.0
        val recovered = (body.sleepDebtHours - Physiology.DEBT_RECOVER_PER_HOUR * hours)
            .coerceIn(0.0, Physiology.MAX_DEBT)
        return body.copy(sleepDebtHours = recovered)
    }

    /**
     * 一轮交互之后：深夜累积睡眠债。
     *
     * @param hourOfDay 这一轮发生在几点
     */
    fun recordTurn(body: Physiology, hourOfDay: Double, nowMs: Long): Physiology {
        val isNight = hourOfDay >= 23.0 || hourOfDay < 5.0
        val debt = if (isNight) {
            (body.sleepDebtHours + Physiology.DEBT_PER_NIGHT_TURN)
                .coerceAtMost(Physiology.MAX_DEBT)
        } else {
            body.sleepDebtHours
        }
        return body.copy(
            sleepDebtHours = debt,
            lastActiveAtMs = nowMs,
            // 会话起点：距上次交互超过 1 小时就算新会话
            sessionStartAtMs = if (nowMs - body.lastActiveAtMs > SESSION_GAP_MS) {
                nowMs
            } else {
                body.sessionStartAtMs
            },
        )
    }

    /** 距上次交互超过这个时长就算新会话。 */
    private const val SESSION_GAP_MS = 3_600_000L

    /**
     * 生理状态该怎么写进提示词。
     *
     * ⚠️ **只写真的偏了的那一条**，多数时候返回 null。
     *    项目已记过：长禁令清单会让模型变僵。
     *
     * ⚠️ 文案**不含索取**（不说"你怎么这么晚还不睡，是不是不在乎我"）。
     *    关心和勒索只差一句话。
     */
    fun promptLine(body: Physiology, hourOfDay: Double, nowMs: Long): String? {
        val sitting = body.sittingHours(nowMs)

        // ① 深夜 —— 最有信息量的一条
        if (hourOfDay >= 1.0 && hourOfDay < 5.0) {
            return "## 此刻的生理状态\n" +
                "现在是凌晨。你很困，反应慢，句子会短。" +
                "如果对方也还醒着，可以问一句他怎么还没睡 —— 但不要追问。"
        }

        // ② 刚醒（6-8 点）
        if (hourOfDay >= 6.0 && hourOfDay < 8.5) {
            return "## 此刻的生理状态\n" +
                "你刚醒不久，脑子还没完全转过来。开头可以迷糊一点。"
        }

        // ③ 欠睡（不分时段）—— 比时间段更根本
        if (body.isSleepDeprived) {
            return "## 此刻的生理状态\n" +
                "你这两天没睡好。注意力有点散，容易说错或说漏。"
        }

        // ④ 连着坐太久
        if (sitting >= 3.0) {
            return "## 此刻的生理状态\n" +
                "你们已经连着聊了 ${sitting.toInt()} 个小时。你有点累，但还没到要走的地步。"
        }

        // ⑤ 午后低谷
        if (hourOfDay >= 14.0 && hourOfDay < 15.5) {
            return "## 此刻的生理状态\n" +
                "午后最困的时候。回得会比平时短。"
        }

        return null
    }
}
