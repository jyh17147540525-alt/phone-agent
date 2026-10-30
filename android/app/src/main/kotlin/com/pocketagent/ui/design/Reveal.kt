package com.pocketagent.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay

/**
 * **浮现** —— 内容不是"出现"，而是"浮现"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这是"iOS 丝滑"里最容易被误认为"花哨"的那一层
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要的是"和 iOS 系统 UI 一样丝滑"。而 iOS 的丝滑，
 * **主要不来自动画速度，来自三件事**：
 *
 *  1. **没有东西是"突然出现"的**。列表、卡片、弹层都有极短的上浮 + 淡入。
 *  2. **有编排**。同一屏的元素不是一起动，而是**依次**（stagger）。
 *     这个"依次"让人眼感觉"界面在为我展开"，而不是"页面刷新了"。
 *  3. **位移极小**。上浮 8dp 就够，超过 16dp 就变成"飞入"，那是网页味。
 *
 * ⚠️ 本项目的现状：`PaMotion.staggerDelay` **有定义、零引用**。
 *    于是所有页面都是"一次性硬切" —— 功能都对，但手感是网页。
 *    这和 `RelationshipStage` 当初"定义完整但没人读"是同一类问题。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条硬约束（都是为了避免"炫技变成卡顿"）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **只对首屏可见项生效。** [index] 超过 [PaMotion.staggerDelay] 的
 *    封顶值之后延迟不再增加 —— 否则第 30 项要等 1.2 秒。
 *
 * 2. **位移只有 8dp。** 见 [MotionRules][com.pocketagent.agentui.MotionRules] 的注释。
 *
 * 3. **必须能被关掉。** 用户在设置里关掉动画时，这里必须**立即直接显示**，
 *    而不是"动得更快"。
 *
 * ⚠️ 具体的数字（8dp / 0.98 / 260ms）**不在这里定义** ——
 *    它们在 `:agentui/MotionRules` 里，因为那边进得了离线验证器。
 *    本文件只负责把那些数字喂给 `animateFloatAsState`。
 *    在两边各写一份常量，是最容易漂移的那种重复。
 */

/**
 * 一个会"浮现"的容器。**进场时上浮 + 淡入 + 微缩放**。
 *
 * @param index 在列表里的序号（决定延迟）。单个元素传 0。
 * @param enabled 关掉时立即完整显示（不做任何动画）。
 *   ⚠️ 它与 `LocalMotionPreference` 是**与**关系：用户在系统里关掉动画、
 *      或者调用方显式传 false，任一成立就不动。
 *
 * 用法（列表）：
 * ```
 * itemsIndexed(list) { i, item ->
 *     PaReveal(index = i) { MyRow(item) }
 * }
 * ```
 *
 * ⚠️ **不要给长列表的每一项都包它**：`LazyColumn` 复用 item 时，
 *    滚回来会**重新播放一次**浮现，那看起来像"列表在抖"。
 *    ⇒ 只在**数量有限且一次性展示**的地方用（设置页的分组、
 *      详情页的几块内容、首屏卡片）。
 */
@Composable
fun PaReveal(
    modifier: Modifier = Modifier,
    index: Int = 0,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // ★ 系统级"关掉动画"优先于调用方。见 MotionPreference 的注释：
    //   一个"无论用户怎么设置都坚持播完动画"的 App，在那些人手里
    //   就是每次操作都慢半拍。
    val motion = LocalMotionPreference.current

    // ══════════════════════════════════════════════════════════
    //  ★★ 决策全部交给 `:agentui/MotionRules`，这里只负责喂给动画
    // ══════════════════════════════════════════════════════════
    //
    // ⚠️ 为什么不在这一行里写 `if (motion.shouldReveal)`：
    //    那样"该不该动/动多久/延迟多少"就散在 Compose 里，
    //    而 `:app` 进不了离线验证器 ⇒ 这些规则**无法被测试**。
    //    `MotionRules` 在 `:agentui`（纯 Kotlin），有 13 条测试钉着。
    val plan = com.pocketagent.agentui.MotionRules.plan(
        animationsOn = enabled && motion.shouldReveal,
        richMotion = motion.richMotion,
        index = index,
    )

    // ⚠️ 已就位的元素**不再重播**。这是"滚动回来不抖"的关键：
    //    状态记在 remember 里，只要这个 composable 没被销毁就不会重来。
    var started by remember { mutableStateOf(plan.isStatic) }

    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = plan.durationMs,
            // ★ 用 Emphasized（快起慢停）：浮现要"上来就稳"，不能有回弹。
            //   bouncy 在这里会让整屏元素各自弹一下，非常廉价。
            easing = PaEasing.Emphasized,
        ),
        label = "revealProgress",
    )

    LaunchedEffect(plan.isStatic, plan.startDelayMs) {
        if (plan.isStatic) {
            started = true
            return@LaunchedEffect
        }
        if (plan.startDelayMs > 0) delay(plan.startDelayMs.toLong())
        started = true
    }

    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            // ★ 上浮与缩放的距离来自 plan —— 省电模式时它们是 0 和 1（不生效）。
            translationY = (1f - progress) * plan.riseDp * density
            val s = plan.fromScale + (1f - plan.fromScale) * progress
            scaleX = s
            scaleY = s
        },
    ) {
        content()
    }
}
