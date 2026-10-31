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

    /**
     * **位移专用 spec**：280ms + `Standard` 曲线。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-10-31：为什么位移不能用 `Emphasized`
     * ═══════════════════════════════════════════════════════════════
     *
     * 用户："就像我在 app 里打开二级和三级页面时，你也没有做好丝滑的转场。"
     *
     * 根因：转场的**位移**用了 `standard()`（即 `Emphasized` =
     * `cubic-bezier(0.16, 1, 0.3, 1)`）。
     *
     * ⚠️ 那条曲线的特征是「**前 20% 走完 80% 的距离**，然后长尾缓停」
     *    （见 `PaEasing` 的注释）。它用在**淡入**上是对的 ——
     *    那正是 iOS 内容出现的观感。
     *
     * ⚠️ 但用在**位移**上就错了。以 24dp / 280ms 为例：
     *    · 前 56ms 走完 19dp（观感是"唰一下到位"）
     *    · 剩下 224ms 磨那最后 5dp（观感是"停在原地不动"）
     *    ⇒ 用户看到的是**"动了一下然后卡住"**，而不是"顺畅滑过去"。
     *
     * ★ 位移要的是"匀速走过这段距离然后停下"，对应 `Standard`
     *   （`cubic-bezier(0.2, 0, 0, 1)`）—— 温和进出，中段速度快且均匀。
     *   这也是 iOS 页面转场用的那类曲线。
     *
     * ⚠️ 刻意**不**改 `standard()` 本身：全站几十处淡入、颜色过渡
     *    都在用它，改它会连带影响那些**本来正确**的地方。
     *    ⇒ 新增一个，只用在位移上。
     */
    fun <T> slide(duration: Int = PaDuration.Normal): FiniteAnimationSpec<T> =
        tween(durationMillis = duration, easing = PaEasing.Standard)

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
     * ⚠️ 这里**只是转发**。真正的实现在 `:agentui/MotionRules`，
     *    因为那边进得了离线验证器（13 条测试钉着封顶与负序号）。
     *
     * ⚠️ 保留这个签名是为了不动既有调用点。但如果有人想"顺手调一下步长"，
     *    请去改 `MotionRules.STAGGER_STEP_MS` —— 在这里另写一份，
     *    两条规则一定会漂移，而且漂移后没有测试能发现。
     */
    fun staggerDelay(index: Int, stepMs: Int = com.pocketagent.agentui.MotionRules.STAGGER_STEP_MS, maxMs: Int = com.pocketagent.agentui.MotionRules.STAGGER_MAX_MS): Int =
        (index.coerceAtLeast(0) * stepMs).coerceAtMost(maxMs)
}

/**
 * 页面转场的位移量。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31：缩放**去掉**了，位移保持小
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户："就像我在 app 里打开二级和三级页面时，你也没有做好丝滑的转场。"
 *
 * 查出来两个问题叠在一起：
 *
 * ① **曲线用错**（已修，见 `PaMotion.slide`）：
 *    位移用了 `Emphasized`（前 20% 走完 80%），观感是"到位后原地磨"。
 *
 * ② **缩放幅度太小，起了反作用**：
 *    原来是 `0.98 → 1.0`（2%）。2% 的缩放在手机屏幕上**几乎看不出**，
 *    但 Compose 要为它多算一层 `graphicsLayer` —— 而它带来的
 *    只是"画面好像轻微抖了一下"的不确定感。
 *
 * ⚠️ 微弱的缩放比没有缩放**更糟**：
 *    没有缩放时，位移 + 淡入是干净的；
 *    有 2% 缩放时，大脑会试图理解"它在变大吗？"但得不到答案。
 *    那种"说不清哪里不对"正是突兀感的来源之一。
 *
 * ⇒ 缩放**整段去掉**。位移 + 淡入已经足够表达"层级前进"，
 *   而且与 iOS 的页面转场一致（iOS 也不用缩放）。
 *
 * ⚠️ 保留这两个常量而不是删掉：调用点在 `AppShell` 的 `scaleIn/Out`，
 *    改常量比改四处调用点风险小。但**值改成 1.0**，
 *    也就是"不变" —— 让 `scaleIn` 变成无操作。
 *    这样如果将来想加回来，改一个数字即可。
 */
object PaTransition {
    /** 页面进入时的水平起始偏移 */
    const val ENTER_OFFSET_X = 24
    /** 页面退出时的水平终点偏移 */
    const val EXIT_OFFSET_X = -16
    /** 页面进入时的垂直起始偏移（用于从底部进入的场景） */
    const val ENTER_OFFSET_Y = 12

    /**
     * 页面初始缩放。
     *
     * ⚠️ **1.0 = 不缩放**。见对象注释 ②：
     *    2% 的缩放在手机上看不出，只会带来"轻微抖一下"的不确定感。
     */
    const val ENTER_SCALE = 1f

    /** 页面退出时的缩放。同上，1.0 = 不缩放。 */
    const val EXIT_SCALE = 1f
}
