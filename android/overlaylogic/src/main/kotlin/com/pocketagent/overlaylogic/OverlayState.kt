package com.pocketagent.overlaylogic

/**
 * 悬浮球的状态。
 *
 * 设计目标：**占用递增**、**默认最简**。
 * 用户交办任务后应该能去干自己的事 —— 所以默认状态是几乎不可见的一个点，
 * 任何"更占地方"的状态都必须由用户主动触发。
 *
 * | 状态      | 视觉                    | 触发方式       |
 * |-----------|-------------------------|----------------|
 * | [DOT]     | 半透明圆点（或贴边细线）| 默认           |
 * | [EDGE]    | 贴边细线，视觉近乎消失  | 拖到屏幕边缘   |
 * | [RUNNING] | 圆点 + 进度环           | 任务开始       |
 * | [PANEL]   | 展开卡片（含输入框）    | 用户点击圆点   |
 *
 * ⚠️ 刻意**没有**"展示执行步骤"的状态 —— 不断刷新的步骤日志会让用户
 * 忍不住盯着看，这本身就是干扰。想看细节的用户可以点开 [PANEL]。
 */
enum class OverlayState {
    /** 默认：一个半透明圆点 */
    DOT,

    /**
     * 贴边：收成一条细线，视觉上近乎消失。
     *
     * ⚠️ **绝不能完全移出屏幕**。Android 15 收窄了 `SYSTEM_ALERT_WINDOW`
     * 从后台启动前台服务的豁免，要求「当前存在可见的 overlay 窗口」。
     * 完全移出可能丢掉这个豁免资格 —— 详见待验证实验 EX-20。
     */
    EDGE,

    /** 任务进行中：圆点套一个进度环（一个不动的视觉元素） */
    RUNNING,

    /** 展开面板：用户主动点开，显示当前任务 / 输入框 / 停止按钮 */
    PANEL,
    ;

    /** 该状态下悬浮球是否仍应被视为「可见」以保住 FGS 豁免资格 */
    val countsAsVisibleOverlay: Boolean
        get() = true // 所有状态都保留在屏幕内，故均算可见

    /** 该状态是否会占用较大屏幕区域（用于"减少影响"的量化校验） */
    val occupiesSignificantArea: Boolean
        get() = this == PANEL
}

/**
 * 悬浮球可接受的事件。
 *
 * 与 [OverlayState] 分离的原因：状态迁移规则本身是纯逻辑，
 * 可以完全离线测试 —— 而"点击事件从哪来"依赖 Android，测不了。
 */
sealed interface OverlayEvent {
    /** 用户单击圆点 */
    data object Tap : OverlayEvent

    /** 用户把球拖到了屏幕边缘 */
    data object DragToEdge : OverlayEvent

    /** 用户把球从边缘拖回屏幕内 */
    data object DragFromEdge : OverlayEvent

    /** 用户长按（打开临时模型指定菜单） */
    data object LongPress : OverlayEvent

    /** 用户点击面板外区域 / 按返回键收起面板 */
    data object DismissPanel : OverlayEvent

    /** 任务开始 */
    data class TaskStarted(val taskId: String) : OverlayEvent

    /** 任务结束（正常完成或失败） */
    data class TaskFinished(val taskId: String) : OverlayEvent

    /**
     * 用户按下急停。
     *
     * ⚠️ 这是本项目最重要的交互之一 —— 悬浮球独立于执行进程，
     * 所以即使 agent 跑飞了，这个事件仍然能被响应。
     */
    data object EmergencyStop : OverlayEvent
}

/**
 * 状态迁移的结果。
 *
 * 用自定义类型而非 Kotlin `Result<T>`：迁移被拒绝是**正常业务情形**
 * （例如没有任务在跑时收到 [OverlayEvent.TaskFinished]），
 * 不是异常。用 `Result<T>` 会诱导调用方写 `try/catch`。
 */
sealed interface OverlayTransition {
    /** 迁移成功 */
    data class Moved(
        val from: OverlayState,
        val to: OverlayState,
        val effect: OverlayEffect,
    ) : OverlayTransition

    /** 迁移被忽略（当前状态下该事件无意义），状态保持不变 */
    data class Ignored(
        val state: OverlayState,
        val reason: String,
    ) : OverlayTransition
}

/** 需要 UI 层执行的副作用。纯逻辑层只声明"要做什么"，不实际去做。 */
sealed interface OverlayEffect {
    /** 无副作用（纯状态变更） */
    data object None : OverlayEffect

    /** 展开面板 */
    data object ShowPanel : OverlayEffect

    /** 收起面板 */
    data object HidePanel : OverlayEffect

    /** 开始显示进度环 */
    data object ShowProgress : OverlayEffect

    /** 停止进度环 */
    data object HideProgress : OverlayEffect

    /** 吸附到最近的屏幕边缘 */
    data object SnapToEdge : OverlayEffect

    /** 从边缘展开回圆点 */
    data object UnsnapFromEdge : OverlayEffect

    /** 打开临时模型指定菜单（长按触发） */
    data object ShowModelPicker : OverlayEffect

    /**
     * 广播急停信号给执行进程。
     *
     * ⚠️ 这是**跨进程**信号。悬浮球所在进程与 agent 执行进程不同，
     * 所以即使 agent 崩溃或卡死，急停依然有效。
     */
    data object BroadcastStop : OverlayEffect
}
