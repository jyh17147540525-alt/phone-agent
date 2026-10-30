package com.pocketagent.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * **触感反馈** —— 丝滑感里最容易被忽略、性价比最高的一层。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它属于"设计系统"而不是"某个页面的细节"
 * ═══════════════════════════════════════════════════════════════
 *
 * iOS 的交互之所以"跟手"，一半来自动画曲线，另一半来自**触感**：
 * 手指按下、开关切换、发送成功，都有一记极轻的震。
 * 它不占视觉带宽，却让"我确实按下去了"这件事变成确定的。
 *
 * ⚠️ 而本项目的现状是：**零触感**。全仓搜不到 `HapticFeedback`。
 *    于是同样是"点一下保存"，网页和原生手感的差距就出在这里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条纪律（都是"少即是多"）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **不是每个点击都要震。** 震太多会让人烦躁，而且耗电。
 *    只给"状态真的变了"的时刻：开关切换、保存成功、发送出去、长按触发。
 *    普通列表项跳转**不震** —— 页面切换本身已经有视觉反馈了。
 *
 * 2. **强度只用最轻的两档。** Compose 的 `HapticFeedbackType` 在新版本里
 *    有 `TextHandleMove` / `LongPress` / `Confirm` 等细分类型，
 *    但**老 API 只有 LongPress / TextHandleMove 两类**。
 *    这里只用这两个，因为它们在任何 API 版本上都存在，不需要版本分支。
 *
 * 3. **必须能整机关掉。** 有人讨厌震动（开会、躺着）。
 *    见 [Haptics.enabled] —— 它由调用方设置，本文件不做判断。
 *
 * ⚠️ `performHapticFeedback` 在没有震动马达的设备上**静默无操作**，
 *    不会抛异常。所以这里不需要 try/catch —— 加了一层也只是噪音。
 */
object Haptics {

    /**
     * 全局开关。默认开。
     *
     * ⚠️ 它是 `@Volatile` 而不是普通 var：设置页改它，而触摸回调在
     *    另一个线程上读。不保证可见性的话，关掉之后还可能再震几下。
     */
    @Volatile
    var enabled: Boolean = true

    /** 一次"确认"级触感 —— 保存成功、发送出去、开关切换。 */
    fun confirm(haptics: androidx.compose.ui.hapticfeedback.HapticFeedback) {
        if (!enabled) return
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * 一次"轻点"级触感 —— 长按开始、拖拽落位。
     *
     * ⚠️ 用 `TextHandleMove` 而不是什么都不做：这个类型在各版本上
     *    都是**最轻**的一档，正好对应"手指划过一个元素"的量级。
     *    名字看起来像"文本选择"，但它是这里唯一可用的轻量档。
     */
    fun light(haptics: androidx.compose.ui.hapticfeedback.HapticFeedback) {
        if (!enabled) return
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}

/**
 * 取一个**不会随重组变化**的触感回调。
 *
 * ★ 为什么包一层：直接在每个 onClick 里写
 *   `LocalHapticFeedback.current.performHapticFeedback(...)` 会让
 *   调用点变长，而且容易漏掉 `enabled` 判断。
 *
 * 用法：
 * ```
 * val haptics = rememberHaptics()
 * PaButton(onClick = { haptics.confirm(); save() }, ...)
 * ```
 */
@Composable
fun rememberHaptics(): HapticsHandle {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { HapticsHandle(haptics) }
}

/** [rememberHaptics] 的返回值 —— 两个方法名就说清了该在什么时候用。 */
class HapticsHandle internal constructor(
    private val haptics: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    /** 状态真的变了：保存成功、发送、开关切换。 */
    fun confirm() = Haptics.confirm(haptics)

    /** 手指划过、长按触发。 */
    fun light() = Haptics.light(haptics)
}
