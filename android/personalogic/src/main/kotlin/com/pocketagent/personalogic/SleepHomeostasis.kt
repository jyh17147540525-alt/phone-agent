package com.pocketagent.personalogic

import kotlin.math.exp

/**
 * **睡眠稳态（Process S）** —— 熬夜的账，怎么还。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 出处：Borbély 双过程模型（two-process model）
 * ═══════════════════════════════════════════════════════════════
 *
 * Borbély 1982 / Borbély, Daan, Wirz-Justice & Deboer 2016（综述免费版：
 * https://pmc.ncbi.nlm.nih.gov/articles/PMC9540767/ ）把"困不困"拆成两件事：
 *
 * ```
 *   睡不困 = S（睡眠压力，醒得越久越高） + C（昼夜节律，24h 正弦）
 * ```
 *
 * 其中 S 的动力学是**两条指数**，这也是本文件唯一借来的东西：
 *
 * ```
 *   睡眠中： S(t) = S0 * exp(-t / τ_s)          τ_s = 4.2 小时
 *   清醒中： S(t) = 1 - (1 - S0) * exp(-t / τ_w)  τ_w = 18.2 小时
 * ```
 *
 * 参考实现（可直接对照，MIT）：https://github.com/PabRod/sleepR 的 `R/borbely.R`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 只借"指数"，不借"阈值"
 * ═══════════════════════════════════════════════════════════════
 *
 * 双过程模型真正的内容是"当 S 撞上上/下阈值时就睡着/醒来"（门槛 `Hu0 + a·C`、
 * `Hl0 + a·C`）。**这个我们不能照搬**：
 *
 * · 阈值模型需要"睡着的那一刻"，而我们的角色没有入睡事件 ——
 *   它只是"用户没来找它的那几个小时"。硬造一个入睡时刻就是编。
 * · 一旦有了阈值，就得决定"谁让它睡" —— 这是产品决策，不是生理学。
 *
 * ⇒ 我们只取两件**能确定性算出来**的事：
 *   1. **恢复是指数，不是线性**（[debtAfterRest]）
 *   2. **睡眠压力对清醒度是乘法削弱，不是减法**（[alertness]）
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"乘法"比原来的"减法"对
 * ═══════════════════════════════════════════════════════════════
 *
 * 改动前 `energyModifier` 是 `昼夜偏移 - 债务×常数`。那意味着：
 * 上午 10 点欠睡 5 小时 = 下午 4 点欠睡 5 小时，**扣掉一样多的分**。
 *
 * 但真实体验不是这样：欠睡的人**在低谷时更惨**（下午直接废掉），
 * 而在峰顶还能撑一会儿。乘法交互（`C × (1 - k·S)`）才有这个性质：
 * 同一个 S，作用在 0.95 上只掉一点，作用在 0.42 上按比例掉更多。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 常数哪些是文献的，哪些是我们自己的
 * ═══════════════════════════════════════════════════════════════
 *
 * | 常数 | 值 | 来源 |
 * |---|---|---|
 * | [TAU_SLEEP_HOURS] | 4.2 h | Borbély（文献值，别改） |
 * | [TAU_WAKE_HOURS] | 18.2 h | Borbély（文献值，当前未使用，留作对照） |
 * | [ALERTNESS_LOSS_AT_FULL_PRESSURE] | 0.75 | **产品标定**：欠满 12h 债时清醒度只剩四分之一 |
 *
 * 最后一行不是物理常数。选 0.75 的理由：12 小时债 ≈ 连熬两整夜，
 * 那时候"只剩四分之一的精神"比"完全归零"更可信（人不会真的宕机）。
 */
object SleepHomeostasis {

    /**
     * 睡眠中 Process S 的时间常数（小时）。
     *
     * ★ 文献值。它的意思是：**睡 4.2 小时，压力降到 1/e（约 37%）**；
     *   睡满 8 小时约剩 15%。这比线性恢复（睡 8 小时才恢复 8×常数）快得多，
     *   也更接近"补一觉就明显好受"的体感。
     */
    const val TAU_SLEEP_HOURS = 4.2

    /**
     * 清醒中 Process S 的时间常数（小时）。
     *
     * ★ 文献值，**当前未使用** —— 因为"醒着"在我们的模型里等于"正常交互"，
     *   而睡眠债本来就是在交互时按轮累积的（见 `PhysiologyEngine.recordTurn`）。
     *   保留它是为了让后来改这块的人知道正确的数字是多少，不要自己拍。
     */
    const val TAU_WAKE_HOURS = 18.2

    /**
     * 睡眠压力拉满时，清醒度被削掉的比例（0.75 = 只剩 25%）。
     *
     * ⚠️ **产品标定，不是文献值**。调它会直接改变"熬夜之后有多废"。
     */
    const val ALERTNESS_LOSS_AT_FULL_PRESSURE = 0.75

    /**
     * 睡眠债 ⇒ 睡眠压力（0..1）。
     *
     * 线性映射到 [Physiology.MAX_DEBT]：欠满上限就是压力拉满。
     * 真实的 S 是"距上次睡够的时长"，我们没有那个起点，用债当代理量。
     */
    fun pressure(
        debtHours: Double,
        maxDebtHours: Double = Physiology.MAX_DEBT,
    ): Double {
        if (debtHours <= 0.0 || maxDebtHours <= 0.0) return 0.0
        return (debtHours / maxDebtHours).coerceIn(0.0, 1.0)
    }

    /**
     * 睡眠债小于这个量（小时）就直接归零。
     *
     * ⚠️ 与 [HormonePolicy.settleEpsilonPoints][HormonePolicy] 同一类问题：
     *    指数永远到不了 0。`1 小时债` 睡 24 小时后剩 0.0033 小时（12 秒），
     *    数值上没错，但"还欠 12 秒的觉"没有任何意义，而且会让
     *    `sleepDebtHours == 0.0` 这类判断永远不成立。
     *    0.01 小时 = 36 秒，比一次交互的误差还小。
     */
    const val SETTLE_EPSILON_HOURS = 0.01

    /**
     * 休息 [restHours] 小时之后的睡眠债。
     *
     * ★ 指数衰减：`debt * exp(-hours / 4.2)`。
     *
     * 相比原来的 `debt - 0.5×hours`：
     * · 线性会在欠得多时恢复得快、欠得少时恢复得慢（正好反了）；
     * · 线性还会在 `debt=0` 之后继续往下穿（靠 coerce 兜住）；
     * · 指数不会穿零，且"补觉的边际收益递减"—— 睡 4 小时挽回大半，
     *   再睡 4 小时只挽回剩下的一小半，这符合体感。
     *
     * @param restHours 不交互的小时数（我们把它当"在睡"，见 `advance` 的注释）
     * @param settleEpsilonHours 小于它就归零，见 [SETTLE_EPSILON_HOURS]
     */
    fun debtAfterRest(
        debtHours: Double,
        restHours: Double,
        settleEpsilonHours: Double = SETTLE_EPSILON_HOURS,
    ): Double {
        if (debtHours <= 0.0) return 0.0
        if (restHours <= 0.0) return debtHours
        val remaining = debtHours * exp(-restHours / TAU_SLEEP_HOURS)
        return if (remaining < settleEpsilonHours) 0.0 else remaining
    }

    /**
     * 睡眠压力作用之后的**有效清醒度**（0..1）。
     *
     * ★ 乘法：`C × (1 - k·S)`。低谷时被削掉的绝对量小，但相对量一样，
     *   所以"本来就困的时候欠睡"会直接掉到底部。
     *
     * @param circadian 昼夜节律给出的清醒度（`PhysiologyEngine.alertness`）
     * @param pressure 睡眠压力 0..1（[pressure]）；超过 1 会被夹住
     */
    fun alertness(circadian: Double, pressure: Double): Double {
        val p = pressure.coerceIn(0.0, 1.0)
        return (circadian * (1.0 - ALERTNESS_LOSS_AT_FULL_PRESSURE * p)).coerceIn(0.0, 1.0)
    }
}
