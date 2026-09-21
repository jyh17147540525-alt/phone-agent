package com.pocketagent.ui.design

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * 动效规格。
 *
 * ═══════════════════════════════════════════════════════════════
 *  「流畅」的技术定义
 * ═══════════════════════════════════════════════════════════════
 *
 * 动画好不好，90% 取决于**缓动曲线**，不取决于时长。三条经验：
 *
 * **1. 不要用线性。** 线性动画在视觉上"机械"，是廉价感的首要来源。
 *
 * **2. 进入用「快起慢停」（decelerate），退出用「慢起快走」（accelerate）。**
 *    这符合物理直觉：物体运动受阻尼停下，不会突然消失。
 *    反过来用（进入慢起、退出快停）会让人感觉"卡"。
 *
 * **3. 时长宁短勿长。** 手机上的交互节奏比桌面快得多。
 *    超过 400ms 的界面动画，第二次看就开始烦。
 *    本文件的基准是 **280ms**，只有页面级转场才到 380ms。
 *
 * ⚠️ `Emphasized` 是全局默认曲线（cubic-bezier(0.16, 1, 0.3, 1)）。
 *    它的特征是**前 20% 走完 80% 的距离**，然后长尾缓停 ——
 *    这正是 iOS 系统动画的观感来源。改之前先想清楚。
 */
object PaEasing {

    /**
     * 全局默认。快起慢停，长尾收束。
     * 对应 CSS 的 `cubic-bezier(0.16, 1, 0.3, 1)`。
     */
    val Emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** 标准进出。比 Emphasized 温和，用于小幅度的状态变化 */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 退出专用。慢起快走 */
    val Exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** 用于"强调"类的进入，如底部弹层 */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)
}

/** 时长。单位毫秒。命名按用途，不按数值。 */
object PaDuration {
    /** 100ms —— 颜色/透明度等"瞬间"反馈 */
    const val Instant = 100
    /** 180ms —— 小控件状态变化、按压反馈 */
    const val Fast = 180
    /** 280ms —— **默认**。绝大多数界面变化 */
    const val Normal = 280
    /** 380ms —— 页面级转场 */
    const val Page = 380
    /** 520ms —— 首次进入的引导性动画 */
    const val Entrance = 520
}

/**
 * 常用动画 spec 工厂。
 *
 * 集中在这里而不是散落各处，是为了让"全站节奏一致"成为**默认结果**，
 * 而不是靠自觉。
 */
object PaMotion {

    /** 默认 spec：280ms + Emphasized */
    fun <T> standard(duration: Int = PaDuration.Normal): FiniteAnimationSpec<T> =
        tween(durationMillis = duration, easing = PaEasing.Emphasized)

    /** 快速 spec：180ms，用于按压、悬停等即时反馈 */
    fun <T> fast(): FiniteAnimationSpec<T> =
        tween(durationMillis = PaDuration.Fast, easing = PaEasing.Standard)

    /** 页面级 spec：380ms */
    fun <T> page(): FiniteAnimationSpec<T> =
        tween(durationMillis = PaDuration.Page, easing = PaEasing.Emphasized)

    /**
     * 弹性 spec —— 用于"可拖拽/可抛掷"的元素（如底部弹层）。
     *
     * ⚠️ 阻尼常量的正确名字是 `DampingRatioMediumBouncy`（0.5），
     *    不是 `DampingRatioMediumLow` —— Compose 的 `Spring` 里
     *    阻尼只有 Bouncy 系列（High/Medium/Low Bouncy）和 NoBouncy，
     *    没有 Low 这个后缀。写错会直接 `Unresolved reference`。
     *
     * 0.5 的阻尼让回弹只出现一次轻微过冲，不会来回弹：
     *  · `DampingRatioNoBouncy`（1.0）完全没有弹性，显得呆
     *  · `DampingRatioHighBouncy`（0.2）会弹个不停，显得廉价
     */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * 交错延迟 —— 列表项依次进入。
     *
     * ⚠️ 上限 240ms：如果 20 个列表项各延迟 40ms，最后一项要等 800ms 才出现，
     *    用户会以为界面卡住了。所以必须封顶，且**只对首屏可见项生效**。
     */
    fun staggerDelay(index: Int, stepMs: Int = 40, maxMs: Int = 240): Int =
        (index * stepMs).coerceAtMost(maxMs)
}

/**
 * 页面转场的位移量。
 *
 * 刻意**很小**（水平 24dp / 垂直 12dp）。
 * 大位移转场在手机上会显得晕，而且会暴露 NavHost 的真实切换速度 ——
 * 小位移 + 淡入淡出，观感是"内容浮现"而不是"页面滑过"，更高级。
 */
object PaTransition {
    /** 页面进入时的水平起始偏移 */
    const val ENTER_OFFSET_X = 24
    /** 页面退出时的水平终点偏移 */
    const val EXIT_OFFSET_X = -16
    /** 页面进入时的垂直起始偏移（用于从底部进入的场景） */
    const val ENTER_OFFSET_Y = 12

    /** 页面初始缩放（1.0 之外的值）—— 0.98 是"几乎看不出但在起作用"的量级 */
    const val ENTER_SCALE = 0.98f
    /** 页面退出时的缩放 */
    const val EXIT_SCALE = 0.99f
}
