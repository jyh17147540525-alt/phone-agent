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
     *
     * ★ 这个值对应 **Material 3 的 `emphasizedDecelerate`**
     *   （`cubic-bezier(0.16, 1, 0.3, 1)`）—— 已交叉核对过
     *   Material 的 motion token 规格，不是随手写的美术数字。
     *
     * ⚠️ 它的特征是**前 20% 走完 80% 的距离**，然后长尾缓停。
     *    适合**淡入 / 颜色过渡 / 内容浮现**。
     *
     * ⚠️⚠️ **不适合位移** —— 位移用 [Standard]。
     *    原因见 `PaMotion.slide` 的注释：位移用长尾曲线会让观感
     *    变成"唰一下到位，然后原地磨 200ms"。
     */
    val Emphasized: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /**
     * 标准进出。**位移用这个**。
     *
     * ★ `cubic-bezier(0.2, 0, 0, 1)` 对应 Material 的
     *   `standard` / `standardDecelerate` 一族 —— 温和进出，
     *   中段速度快且均匀，观感是"匀速走过这段距离然后停下"。
     */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 退出专用。慢起快走 */
    val Exit: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /**
     * 用于"强调"类的进入。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-10-31：过冲从 1.3 降到 **1.08**
     * ═══════════════════════════════════════════════════════════════
     *
     * 调研结论（多个 skill 库交叉确认）：
     * > `dampingFraction 1.0 = critically damped — smooth settle,
     * >  NO overshoot (house default)`
     *
     * ⚠️ 也就是说：**高质量产品的默认是零过冲**。而这里原来是
     *    `y1 = 1.3` —— 那是**大幅过冲**，观感是"弹了一下"。
     *
     * ⚠️ 大幅过冲是"廉价感"的主要来源：它让界面看起来像
     *    2013 年的 jQuery 动画。Claude / Linear / Notion / iOS
     *    系统动画**全部**是零过冲或极微弱过冲。
     *
     * ★ 1.08 而不是 1.0：完全无过冲的 "强调" 就不再是强调了。
     *   8% 的过冲看得见"弹了一下"，但说不出"廉价"。
     */
    val Overshoot: Easing = CubicBezierEasing(0.34f, 1.08f, 0.64f, 1f)
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
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-10-31：阻尼从 0.5 改成 **1.0（零过冲）**
     * ═══════════════════════════════════════════════════════════════
     *
     * 用户："质量还是太低了，你自己去网上查找 ui 设计类的 skill 和知识库。"
     *
     * 查到一条行业实践（多个 skill 库交叉确认）：
     * > `dampingFraction 1.0 = critically damped — smooth settle,
     * >  NO overshoot (house default)`
     *
     * ⚠️ 也就是说：**高质量产品的默认是零过冲**，而这里原来是 0.5
     *    （`DampingRatioMediumBouncy`）—— 它会过冲。
     *
     * 过冲在"可抛掷"元素上是合理的（你甩它一下，它当然会弹回来）。
     * 但本项目把它当**通用弹性**用在各种场合，于是每次出现都带一个小回弹。
     *
     * ⚠️ 微小的过冲是"廉价感"的主要来源之一：
     *    用户说不清哪里不对，但会觉得"这个 app 有点玩具"。
     *    Claude / Linear / Notion 的动效全部是**零过冲**的 ——
     *    它们看起来"高级"很大程度上就是这一点。
     *
     * ★ 保留一个真正需要弹性的出口：[throwable]。
     *   只有拖拽/抛掷类交互才用 0.5 —— 那里过冲是对的（物理上就该弹）。
     */
    fun <T> bouncy(): FiniteAnimationSpec<T> = spring(
        // ⚠️ 1.0 = 临界阻尼 = 平滑沉降、零过冲。见上文的调研结论。
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * **真的会弹的** spec —— 只给拖拽/抛掷类交互用。
     *
     * ⚠️ 与 [bouncy] 分开是刻意的：
     *  · 普通元素出现 → [bouncy]（零过冲，高级感）
     *  · 你甩出去的东西回弹 → 这里（0.5，物理上就该弹）
     *
     * 混用会让"什么时候该弹"失去意义 —— 全都弹等于全都不重要。
     */
    fun <T> throwable(): FiniteAnimationSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    /**
     * **内容压实** —— 用于"等了一会儿、终于来了"的主要内容出现。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-10-31：为什么它介于 [bouncy] 和 [throwable] 之间
     * ═══════════════════════════════════════════════════════════════
     *
     * AI 回复出现那个位置（`ConversationList` 的 `showReply`）满足三个条件：
     *   ① **低频**（每轮对话一次）
     *   ② **值得期待**（等了几秒）
     *   ③ **不阻塞操作**（它自己长出来）
     *
     * ⇒ 那里可以有一点点"重量感"。但**不能是全弹**：
     *   调研结论是高质量产品的默认零过冲（见 [bouncy] 的注释），
     *   而"回复出现"是最高频的内容动效之一 —— 每次都弹会变成噪音。
     *
     * ★ 0.8 是这个折中的落点：
     *   · 1.0（临界）—— 完全平滑，内容的"到来感"会弱
     *   · 0.8 —— 极轻微的一次收束，看得见但说不出
     *   · 0.5 —— 明显回弹，已经偏"玩具"
     */
    fun <T> settled(): FiniteAnimationSpec<T> = spring(
        // ⚠️ Compose 没有 0.8 的常量，直接给数值。
        dampingRatio = 0.8f,
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
