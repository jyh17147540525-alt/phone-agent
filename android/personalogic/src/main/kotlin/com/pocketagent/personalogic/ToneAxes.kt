package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/**
 * 六根语气轴。
 *
 * ⚠️ 轴的名字是**稳定标识**（会被写进持久化的变更账本），
 *    所以不要把 [label] 当成 id 用 —— 展示文案可以改，枚举名不能改。
 */
@Serializable
enum class ToneAxis(val label: String) {
    WARMTH("温度"),
    BREVITY("简洁"),
    HUMOR("幽默"),
    PROACTIVITY("主动程度"),
    FORMALITY("正式度"),
    EMOJI("表情"),
}

/**
 * 语气轴取值 —— 人格的"血肉"部分，可微调。
 *
 * ## 为什么每根轴都是 `Int 0..100` 而不是枚举档位
 *
 * 档位（低/中/高）会让"稍微再简短一点"这种**渐进式**要求无处落脚，
 * 而需求②要的恰恰是渐进感 —— 用户希望看到"ta 为我改了一点点"，
 * 不是"ta 换了个档"。
 *
 * ## 为什么用 `warmth` 这种具名字段而不是 `Map<ToneAxis, Int>`
 *
 * Map 让"某根轴缺失"成为合法状态，于是每个读取点都要处理 null ——
 * 而默认值兜底正是「安静地少做一件事」的标准写法。具名字段 + `init`
 * 校验让"缺一根轴"**根本构造不出来**。
 *
 * @throws IllegalArgumentException 任一根轴不在 [MIN]..[MAX] 内
 */
@Serializable
data class ToneAxes(
    /** 温度：共情与情绪回应的多少 */
    val warmth: Int = 60,

    /** 简洁：同样一件事说多短 */
    val brevity: Int = 50,

    /** 幽默：开玩笑的频率 */
    val humor: Int = 30,

    /** 主动程度：是否主动提议下一步 */
    val proactivity: Int = 40,

    /** 正式度：用词的书面程度 */
    val formality: Int = 30,

    /** 表情：emoji 的使用频率 */
    val emoji: Int = 20,
) {

    init {
        require(
            warmth in MIN..MAX && brevity in MIN..MAX && humor in MIN..MAX &&
                proactivity in MIN..MAX && formality in MIN..MAX && emoji in MIN..MAX,
        ) {
            "语气轴必须落在 $MIN..$MAX 内，当前为 $this"
        }
    }

    /** 读取某一根轴的值 */
    fun axisOf(axis: ToneAxis): Int = when (axis) {
        ToneAxis.WARMTH -> warmth
        ToneAxis.BREVITY -> brevity
        ToneAxis.HUMOR -> humor
        ToneAxis.PROACTIVITY -> proactivity
        ToneAxis.FORMALITY -> formality
        ToneAxis.EMOJI -> emoji
    }

    /**
     * 改写一根轴。
     *
     * ★ **越界一律抛异常，绝不静默钳制。**
     *
     * "把 200 悄悄改成 100 然后返回成功"看起来更友好，但它让调用方
     * 无法区分「用户要的就是 100」和「用户说的 200 被吃掉了」——
     * 而后者在日志里留下的是一条**成功**记录。等用户抱怨"我说了不算"
     * 的时候，没有任何东西能证明曾经发生过一次越界。
     *
     * 越界的正确归宿是 [PersonaRejectionReason.INVALID_VALUE]，
     * 由 [DefaultPersonaTuner] 抛异常 + 落审计。因此这里不提供
     * "顺手帮你改好"的路径。
     *
     * @throws IllegalArgumentException value 不在 [MIN]..[MAX] 内
     */
    fun withAxis(axis: ToneAxis, value: Int): ToneAxes {
        require(value in MIN..MAX) { "轴 ${axis.name} 的值越界：$value（允许 $MIN..$MAX）" }
        return when (axis) {
            ToneAxis.WARMTH -> copy(warmth = value)
            ToneAxis.BREVITY -> copy(brevity = value)
            ToneAxis.HUMOR -> copy(humor = value)
            ToneAxis.PROACTIVITY -> copy(proactivity = value)
            ToneAxis.FORMALITY -> copy(formality = value)
            ToneAxis.EMOJI -> copy(emoji = value)
        }
    }

    companion object {
        const val MIN = 0
        const val MAX = 100

        /**
         * 把一个**外部数据**里的整数钳制进合法区间。
         *
         * ## 它和 [withAxis] 的分工，是这个文件里最容易被搞混的一点
         *
         * | 来源 | 用什么 | 越界时 |
         * |---|---|---|
         * | `tune()` 的入参（活人刚说的） | [withAxis] | **抛异常**，因为要问用户 |
         * | 持久化账本回放（历史数据） | 本函数 | 钳制，因为已经无法回头追问 |
         *
         * 历史数据越界只可能来自"早期版本的校验更松"。这种情况**没有**
         * 可以追问的对象，抛异常会让整个助理起不来 —— 那才是真正的
         * 「安静地做不了任何事」。所以回放路径钳制并继续。
         *
         * ⇒ 两个入口，两种策略，**不可互换**。
         */
        fun coerce(value: Int): Int = value.coerceIn(MIN, MAX)
    }
}