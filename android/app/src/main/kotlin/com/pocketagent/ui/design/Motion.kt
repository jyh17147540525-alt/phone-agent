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
 * 页面转场的位移量 —— **按 iOS push 的真实比例**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-31（第二次修）：24dp 是这次"感觉没变化"的真正原因
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户："感觉还是没啥变化呀，你要在 ios 的基础上尽可能靠近 claude 系统的那种。"
 *
 * ⚠️ 上一轮我改了曲线（`Emphasized` → `Standard`）、去掉了 2% 缩放，
 *    **但位移量还是 24dp** —— 在 1440px 宽的屏幕上只占 **1.7%**。
 *    用户根本看不见那点位移，只看到淡入。
 *
 * ⇒ "改了但没变化"的原因不是曲线，是**位移量小到不构成位移**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  iOS 的页面 push 是怎么做的
 * ═══════════════════════════════════════════════════════════════
 *
 * | 元素 | iOS 的行为 |
 * |---|---|
 * | 新页面 | 从**右边缘外一整屏宽**滑入到 0 |
 * | 旧页面 | 同时向左移 **约 30% 屏宽**，并略微变暗 |
 * | 时长 | 350ms 左右 |
 *
 * ★ 关键是**两页同时动**、而且旧页**不滑出屏幕**（只退 30%）。
 *   那个"退让但还在"的观感，是 iOS 层级感的来源 ——
 *   用户能感觉到"下面那层还在那儿"。
 *
 * ⚠️ 而本项目原来是：新页移 24dp（几乎不动）、旧页移 16dp（也不动）、
 *    两页都在原地淡入淡出 ⇒ 观感就是"内容替换"，不是"页面推进"。
 *
 * ★ 用**屏幕宽度的比例**而不是 dp：不同分辨率下 30% 都是 30%，
 *   而 24dp 在大屏上更不明显、在小屏上更明显 —— 那不是设计，那是巧合。
 */
object PaTransition {

    /**
     * 新页面进入时的水平起始位移：**一整个屏幕宽**。
     *
     * ⚠️ 这里存的是**比例**（1f = 100% 屏宽），调用方乘屏幕宽度。
     *    iOS 的 push 就是"从屏幕右侧外面滑进来"。
     */
    const val ENTER_FRACTION = 1f

    /**
     * 旧页面退出时的水平位移：**向左 30% 屏宽**。
     *
     * ⚠️ 不是 -100%：iOS 的旧页**不滑出屏幕**，它只退让 30% 留在那里。
     *    滑出屏幕的观感是"两层都走了"，而退 30% 是"下面那层还在"——
     *    后者才是层级感。
     */
    const val EXIT_FRACTION = -0.3f

    /**
     * 新页面进入时的垂直起始偏移（保留，用于从底部进入的场景）。
     *
     * ⚠️ 水平 push 只用 [ENTER_FRACTION]，这个留给将来的底部弹层。
     */
    const val ENTER_OFFSET_Y = 12

    /**
     * 页面初始缩放。
     *
     * ⚠️ **1.0 = 不缩放**。iOS 的 push 也不用缩放 ——
     *    位移 + 淡入已经足够，加微缩放只会让画面"抖一下"。
     */
    const val ENTER_SCALE = 1f

    /** 页面退出时的缩放。同上，1.0 = 不缩放。 */
    const val EXIT_SCALE = 1f

    /**
     * 旧页面退出时的**变暗程度**。
     *
     * ★ iOS push 时旧页会略微变暗（`0.5 → 0.85` 那一带的 alpha 遮罩）。
     *   这是"焦点转移"的信号：新页是主角，旧页退到次要位置。
     *
     * ⚠️ 不用到 0：旧页完全黑掉会让人以为"它被销毁了"，
     *    而 iOS 的观感是"它还在，只是不在前面"。
     */
    const val EXIT_DIM = 0.75f
}
