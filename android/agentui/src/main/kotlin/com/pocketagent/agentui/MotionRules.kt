package com.pocketagent.agentui

/**
 * **动效决策的纯逻辑** —— 从 Android 侧剥出来的那一半。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它要住在这里，而不是住在 `ui/design`
 * ═══════════════════════════════════════════════════════════════
 *
 * `compose` 的 `animateFloatAsState` 只在 `:app` 里 —— 而 `:app` 依赖
 * Android Context，**进不了离线验证器**。于是"动画时长对不对""要不要
 * 降级""错峰是否封顶"这些判断，全都没法被测试盯住。
 *
 * ⚠️ 这正是本项目反复出问题的地方：`PaMotion.staggerDelay` 有实现、
 *    零引用；`RelationshipStage` 有实现、零引用。**写的时候都觉得会用**。
 *
 * ⇒ 把"该不该动、动多久、延迟多少"抽成纯函数放在这里，
 *   由 `:app` 的那一层薄薄地调用。这样：
 *     · 规则可以离线测（本模块在验证器里）
 *     · Android 那一层只剩"把数字喂给 animate*AsState"
 *
 * ★ 它不 import 任何 compose/android 类型 —— 那条纪律是硬性的：
 *   只要多一个 `androidx` 依赖，这一整个文件就会掉出验证器。
 */
object MotionRules {

    /**
     * 一次进场动画的完整参数。
     *
     * @param durationMs 0 = **不动画，直接显示**
     * @param riseDp 上浮距离。0 = 只淡入（省电模式）
     * @param fromScale 起始缩放。1.0 = 不缩放（省电模式）
     */
    data class Plan(
        val durationMs: Int,
        val riseDp: Float,
        val fromScale: Float,
        val startDelayMs: Int,
    ) {
        /** 是否完全不动（直接显示）。 */
        val isStatic: Boolean get() = durationMs <= 0
    }

    /** 标准浮现参数 —— 与 `Reveal` 里的常量保持同一组数字。 */
    const val BASE_DURATION_MS: Int = 260
    const val BASE_RISE_DP: Float = 8f
    const val BASE_FROM_SCALE: Float = 0.98f

    /** 错峰：每项间隔与封顶。 */
    const val STAGGER_STEP_MS: Int = 40
    const val STAGGER_MAX_MS: Int = 240

    /**
     * 算出这次进场该怎么动。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 这是整套"丝滑"的**唯一决策点**
     * ═══════════════════════════════════════════════════════════
     *
     * 三个输入分别对应三种"用户其实不想看动画"的情形：
     *
     * @param animationsOn 系统动画开关。false ⇒ **完全不动**。
     *   用户在开发者选项里把动画时长设成 0，是想让一切立刻到位。
     *   我们如果还播 260ms，那他每次操作都慢半拍 —— 那不是丝滑，是卡。
     * @param richMotion 是否允许"贵"的效果（位移 + 缩放）。
     *   省电模式下关掉：淡入几乎不耗合成，缩放要重建图层。
     *   ⚠️ 但**保留淡入** —— 直接硬切是另一种粗糙。
     * @param index 列表序号，决定错峰延迟。
     *
     * ⚠️ 错峰必须封顶：不封顶的话第 30 项要等 1.2 秒才出现，
     *    用户会以为界面坏了。封顶后"前几项依次、后面一起"——
     *    这正是 iOS 的做法，而且人眼看不出来分界在哪。
     */
    fun plan(
        animationsOn: Boolean,
        richMotion: Boolean,
        index: Int = 0,
    ): Plan {
        // ① 系统关掉了动画 ⇒ 立刻显示，零延迟。
        //    ⚠️ 连 startDelay 也必须是 0：否则"关掉动画"之后
        //    列表项还要依次等 40ms 才出现 —— 那是另一种形式的卡。
        if (!animationsOn) {
            return Plan(durationMs = 0, riseDp = 0f, fromScale = 1f, startDelayMs = 0)
        }

        val delay = staggerDelay(index)

        // ② 省电 ⇒ 只淡入，不做位移/缩放。
        if (!richMotion) {
            return Plan(
                durationMs = BASE_DURATION_MS,
                riseDp = 0f,
                fromScale = 1f,
                startDelayMs = delay,
            )
        }

        // ③ 完整效果。
        return Plan(
            durationMs = BASE_DURATION_MS,
            riseDp = BASE_RISE_DP,
            fromScale = BASE_FROM_SCALE,
            startDelayMs = delay,
        )
    }

    /**
     * 交错延迟（带封顶）。
     *
     * ⚠️ `index` 为负数时按 0 处理 —— 界面上不应该出现负序号，
     *    但真出现了也不能算出负延迟（`delay(-40)` 会立刻返回，
     *    看起来像"跳过了动画"，很难查）。
     */
    fun staggerDelay(index: Int): Int {
        if (index <= 0) return 0
        return (index * STAGGER_STEP_MS).coerceAtMost(STAGGER_MAX_MS)
    }
}
