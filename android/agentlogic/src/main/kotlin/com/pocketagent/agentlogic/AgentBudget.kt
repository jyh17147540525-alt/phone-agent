package com.pocketagent.agentlogic

/**
 * 任务的四维预算 —— **一等公民，不是事后优化项**。
 *
 * ## 为什么它必须在循环的第一版里
 *
 * 移动端 agent 的瓶颈不是算力，是**轮次**。
 *
 * | 场景 | 轮次 | 电量 |
 * |---|---|---|
 * | 优化单步（省 0.06 J/轮） | 30 | 0.27% |
 * | 把 30 轮压到 8 轮 | 8 | 0.07% |
 *
 * 两者差**三个数量级**。也就是说：再精细地优化"每一轮做得多快"，
 * 也追不上"少做 22 轮"。
 *
 * 结论：预算不能等循环写完再补 —— 补的时候要把循环重写一遍。
 *
 * ## 四维为什么是这四个
 *
 * | 维度 | 防的是什么 | 为什么不能省 |
 * |---|---|---|
 * | 轮次 | 模型反复规划、原地打转 | 能耗几乎完全由轮次决定 |
 * | 时长 | 用户等了十分钟才发现任务跑飞 | 轮次少但每轮卡住的情况它抓得住 |
 * | 能耗 | 后台把电偷偷耗光 | ★ 用户感知最强，也最难道歉 |
 * | 流量 | 移动数据下烧掉用户套餐 | 上传截图是最大头，且非 WiFi 下代价不同 |
 *
 * 四者**任一**超限即中止。它们不是"加权综合分"—— 综合分会把
 * "轮次严重超标但能耗还没超"这种情形平均成一个看起来还行的数字。
 *
 * ## 能耗口径：`Charge counter` 而不是 `level`
 *
 * 真机实测（2026-09-22，K60 / Android 15）：
 *
 * ```
 * level: 93                ← 1% 粒度
 * Charge counter: 3045     ← ★ 1 单位粒度
 * ```
 *
 * 5 秒内即观测到变化（`3049 → 3049 → 3045`），
 * 换算后 **1 单位 ≈ 0.033%，比 `level` 精确约 30 倍**。
 *
 * 所以「能耗预算」可以保留**硬中止**语义（超了就立刻停），
 * 而不必退化成"跑完之后再核对一下"—— 后者对用户毫无意义。
 *
 * ⚠️ **真实测量必须拔线**：充电会干扰读数。
 * 应用侧取值路径 `BatteryManager.getIntProperty(BATTERY_PROPERTY_CHARGE_COUNTER)`
 * （API 21+，返回 µAh），与 `dumpsys` 同源。
 *
 * ## 本文件零 Android 依赖
 *
 * 电量由调用方作为 [EnergyReading] 传进来 —— 采集依赖 Android，
 * **判定不依赖**。这样"预算算错了"这类不报错、只是静默烧钱的问题
 * 才能被离线测试钉住。
 */
data class AgentBudget(
    /** 最大轮次（一次「感知 → 决策 → 执行 → 校验」算一轮） */
    val maxTurns: Int = DEFAULT_MAX_TURNS,

    /** 最大时长（毫秒） */
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,

    /**
     * 最大能耗，单位 µAh（微安时）。
     *
     * ⚠️ **用绝对电荷量而不是百分比** —— 百分比要先去问电池总量，
     * 而那个值在不同机型上口径不一致（设计容量 / 当前满充容量 / 标称容量）。
     * 绝对量与 `Charge counter` 直接同源，没有换算歧义。
     */
    val maxEnergyMicroAh: Long = DEFAULT_MAX_ENERGY_MICRO_AH,

    /** 最大上传字节数（含截图与请求体） */
    val maxUploadBytes: Long = DEFAULT_MAX_UPLOAD_BYTES,

    /** 是否要求 WiFi 才允许重负载（大截图 / 多模态）。用户可选 */
    val requireWifiForHeavy: Boolean = false,
) {

    init {
        require(maxTurns > 0) { "maxTurns 必须为正数，当前为 $maxTurns" }
        require(maxDurationMs > 0) { "maxDurationMs 必须为正数，当前为 $maxDurationMs" }
        require(maxEnergyMicroAh > 0) { "maxEnergyMicroAh 必须为正数，当前为 $maxEnergyMicroAh" }
        require(maxUploadBytes > 0) { "maxUploadBytes 必须为正数，当前为 $maxUploadBytes" }
    }

    companion object {
        /** 移动端文档推荐的默认轮次。8 轮覆盖绝大多数"打开 App 做一件事" */
        const val DEFAULT_MAX_TURNS = 8

        /** 5 分钟 —— 用户开始怀疑"是不是卡住了"的心理阈值 */
        const val DEFAULT_MAX_DURATION_MS = 300_000L

        /**
         * 默认能耗上限 0.1%。
         *
         * 换算：实测总量约 3049 单位 / 1% ≈ 30 单位 → 0.1% ≈ 3 单位。
         * 8 轮任务耗几十 mAh（≈ 几万 µAh 量级），所以 0.1% 是个宽松但有意义的上限。
         */
        const val DEFAULT_MAX_ENERGY_MICRO_AH = 30_000L

        /** 5 MB —— 非 WiFi 下的可感知阈值 */
        const val DEFAULT_MAX_UPLOAD_BYTES = 5_000_000L

        /** 宽松预算：用于「用户明确要求跑长任务」的场景 */
        val RELAXED = AgentBudget(
            maxTurns = 20,
            maxDurationMs = 900_000L,
            maxEnergyMicroAh = 100_000L,
            maxUploadBytes = 20_000_000L,
        )
    }
}

/**
 * 一次电量采样。
 *
 * @param chargeCounterMicroAh `Charge counter` 读数（µAh）。null 表示该机型拿不到 —— 这时
 *   **能耗这一维自动豁免**，其余三维照常生效。
 * @param sampledAtMs 采样时刻，用于时长计算
 */
data class EnergyReading(
    val chargeCounterMicroAh: Long?,
    val sampledAtMs: Long,
)

/**
 * 预算消耗的累计量。
 *
 * 刻意用 `data class` + `copy` 而不是可变累加器：
 * 循环里每一步都会读它做判定，若它是可变的，就会出现
 * "判定时读到的值和落盘的值不一致"这种极难复现的问题。
 */
data class BudgetUsage(
    val turns: Int = 0,
    val elapsedMs: Long = 0,
    val energyMicroAh: Long = 0,
    val uploadBytes: Long = 0,
) {
    fun nextTurn(): BudgetUsage = copy(turns = turns + 1)

    fun plusUpload(bytes: Long): BudgetUsage = copy(uploadBytes = uploadBytes + bytes)

    fun at(elapsedMs: Long, energyMicroAh: Long): BudgetUsage =
        copy(elapsedMs = elapsedMs, energyMicroAh = energyMicroAh)
}

/**
 * 预算判定结果。
 *
 * ⚠️ **不要用 Boolean 表示**。「超了」和「还差多少」是两件事 ——
 * UI 要在预算将尽时提前提示用户（"还能再做 2 步"），
 * Boolean 做不到这件事，而事后再补一个"剩余量"接口会让调用方分裂成两套逻辑。
 */
sealed interface BudgetVerdict {

    /** 可以继续。附带剩余量，供 UI 展示进度 */
    data class Ok(val remaining: BudgetUsage) : BudgetVerdict

    /** 用掉的比例已超过警戒线（默认 80%），但还没超 */
    data class Warn(val dimension: BudgetDimension, val ratio: Float) : BudgetVerdict

    /** 某一维已超限，必须**立即中止** */
    data class Exceeded(val dimension: BudgetDimension, val detail: String) : BudgetVerdict

    /** 这一维在本机型上不可测，已豁免（例如拿不到 `Charge counter`） */
    data class NotMeasurable(val dimension: BudgetDimension, val reason: String) : BudgetVerdict
}

/** 预算的四个维度 */
enum class BudgetDimension {
    TURNS,
    DURATION,
    ENERGY,
    UPLOAD,
}

/**
 * 预算守卫。
 *
 * ## 为什么是「守卫」而不是「记账本」
 *
 * 记账本是被动记录，守卫是**主动拒绝**。差别在调用方必须处理返回值：
 *
 * ```kotlin
 * when (val v = guard.check(usage)) {
 *     is Exceeded -> return@channelFlow   // ★ 编译器逼你处理中止
 *     is Warn     -> send(BudgetWarning(...))
 *     else        -> {}
 * }
 * ```
 *
 * 若做成记账本，超限只是一个"可以读一下"的字段 —— 忘了读不会编译错，
 * 表现是任务悄悄跑了 40 轮。
 *
 * ## 检查顺序是刻意的
 *
 * 先查**代价最确定**的（轮次、时长），再查需要换算的（能耗、流量）。
 * 轮次是硬计数、时长是时钟差，两者都不可能误判；
 * 能耗依赖采样、流量依赖统计，两者的读数都可能滞后。
 * 顺序反过来会让"其实轮次早就超了"这种情形被误报成"能耗异常"。
 *
 * @param budget 预算
 * @param warnRatio 警戒线比例。≥ 此值且未超限时返回 [BudgetVerdict.Warn]
 */
class BudgetGuard(
    private val budget: AgentBudget,
    private val warnRatio: Float = 0.8f,
) {

    init {
        require(warnRatio > 0f && warnRatio < 1f) {
            "warnRatio 必须在 (0, 1) 之间，当前为 $warnRatio"
        }
    }

    /**
     * 检查预算。
     *
     * @param usage 当前累计用量
     * @return 判定结果。**调用方必须处理 [BudgetVerdict.Exceeded]**
     */
    fun check(usage: BudgetUsage): BudgetVerdict {
        // ── 第一层：代价最确定的两维 ────────────────────────────
        if (usage.turns >= budget.maxTurns) {
            return BudgetVerdict.Exceeded(
                dimension = BudgetDimension.TURNS,
                detail = "轮次已达上限 ${budget.maxTurns}（当前 ${usage.turns}）",
            )
        }
        if (usage.elapsedMs >= budget.maxDurationMs) {
            return BudgetVerdict.Exceeded(
                dimension = BudgetDimension.DURATION,
                detail = "时长已达上限 ${budget.maxDurationMs}ms（当前 ${usage.elapsedMs}ms）",
            )
        }

        // ── 第二层：需要换算的两维 ──────────────────────────────
        if (usage.energyMicroAh >= budget.maxEnergyMicroAh) {
            return BudgetVerdict.Exceeded(
                dimension = BudgetDimension.ENERGY,
                detail = "能耗已达上限 ${budget.maxEnergyMicroAh}µAh（当前 ${usage.energyMicroAh}µAh）",
            )
        }
        if (usage.uploadBytes >= budget.maxUploadBytes) {
            return BudgetVerdict.Exceeded(
                dimension = BudgetDimension.UPLOAD,
                detail = "流量已达上限 ${budget.maxUploadBytes}B（当前 ${usage.uploadBytes}B）",
            )
        }

        // ── 第三层：警戒线 ─────────────────────────────────────
        // ⚠️ 警戒只报**最紧的那一维**，不是报全部。
        //    报全部会让 UI 变成一片黄色警告，用户会学会忽略它。
        val ratios = mapOf(
            BudgetDimension.TURNS to usage.turns.toFloat() / budget.maxTurns,
            BudgetDimension.DURATION to usage.elapsedMs.toFloat() / budget.maxDurationMs,
            BudgetDimension.ENERGY to usage.energyMicroAh.toFloat() / budget.maxEnergyMicroAh,
            BudgetDimension.UPLOAD to usage.uploadBytes.toFloat() / budget.maxUploadBytes,
        )
        val worst = ratios.maxByOrNull { it.value }!!
        if (worst.value >= warnRatio) {
            return BudgetVerdict.Warn(worst.key, worst.value)
        }

        return BudgetVerdict.Ok(remaining(usage))
    }

    /**
     * 当前剩余量。
     *
     * 用来给 UI 展示"还能再做几步"。**这里的值只用于展示，不用于判定** ——
     * 判定一律走 [check]，因为剩余量算出来之后到真正用掉之间还有窗口。
     */
    fun remaining(usage: BudgetUsage): BudgetUsage = BudgetUsage(
        turns = (budget.maxTurns - usage.turns).coerceAtLeast(0),
        elapsedMs = (budget.maxDurationMs - usage.elapsedMs).coerceAtLeast(0),
        energyMicroAh = (budget.maxEnergyMicroAh - usage.energyMicroAh).coerceAtLeast(0),
        uploadBytes = (budget.maxUploadBytes - usage.uploadBytes).coerceAtLeast(0),
    )

    /**
     * 从一次电量采样算出**已消耗的电荷量**。
     *
     * ## 为什么这个换算要单独成函数
     *
     * 它有两个容易搞反的地方：
     *
     * 1. **符号**。`Charge counter` 是**剩余**电荷量，任务跑着它**变小**。
     *    消耗量 = 起点 - 当前，不是当前 - 起点。搞反会得到一个负数。
     * 2. **采样不可用的情形**。拿不到读数时返回 0 而不是抛异常 ——
     *    能耗这一维应当豁免，而不是让整个任务因为"读不到电量"而失败。
     *
     * 另外，中间如果充过电，`counter` 会**变大**，于是算出来是负数。
     * 这种情况说明读数已被充电污染，返回 0（宁可高估剩余，也不要因为
     * 一个假的高消耗而误中止用户的任务）。
     *
     * @param baseline 任务开始时的采样
     * @param current 任务当前的采样
     * @return 已消耗的 µAh，恒 ≥ 0
     */
    fun energySpentMicroAh(baseline: EnergyReading, current: EnergyReading): Long {
        val b = baseline.chargeCounterMicroAh ?: return ENERGY_NOT_MEASURABLE
        val c = current.chargeCounterMicroAh ?: return ENERGY_NOT_MEASURABLE
        return (b - c).coerceAtLeast(0L)
    }

    /** 能耗这一维在本机型上是否可测 */
    fun isEnergyMeasurable(reading: EnergyReading): Boolean = reading.chargeCounterMicroAh != null

    companion object {
        /** 能耗不可测时的哨兵值。0 = 不消耗，从而该维度永远不会触发超限 */
        const val ENERGY_NOT_MEASURABLE = 0L
    }
}
