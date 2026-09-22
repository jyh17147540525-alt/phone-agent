package com.pocketagent.overlaylogic

/**
 * 悬浮球状态机。
 *
 * **纯函数、无副作用** —— 输入当前状态 + 事件，输出新状态 + 要执行的副作用。
 * 这样整个交互逻辑可以在没有 Android 设备的情况下完整测试。
 *
 * 迁移表（行=当前状态，列=事件）：
 *
 * |          | Tap       | DragToEdge | DragFromEdge  | TaskStarted | TaskFinished | EmergencyStop |
 * |----------|-----------|------------|---------------|-------------|--------------|---------------|
 * | DOT      | → PANEL   | → EDGE     | Ignored       | → RUNNING   | Ignored      | Ignored       |
 * | EDGE     | → PANEL   | Ignored    | → DOT         | → EDGE※     | Ignored      | Ignored       |
 * | RUNNING  | → PANEL   | → EDGE     | Ignored       | Ignored†    | → DOT        | → DOT         |
 * | PANEL    | Ignored   | Ignored    | Ignored       | Ignored     | → DOT        | → DOT         |
 *
 * ※ 任务在贴边状态下开始：保持贴边（用户明确表达了"别占我屏幕"），
 *    只是内部记录任务在跑。这比"擅自弹回圆点"更尊重用户意图。
 *
 * † 已有任务在跑时又来一个 TaskStarted：忽略。本项目**不并发执行任务** ——
 *    并发会同时抢无障碍服务与屏幕，对用户的影响是叠加的，与最高优先级冲突。
 */
object OverlayStateMachine {

    /**
     * 计算状态迁移。
     *
     * @param current 当前状态
     * @param event 收到的事件
     * @param runningTaskId 当前正在执行的任务 ID（null 表示无任务）
     * @return 迁移结果；无意义的事件返回 [OverlayTransition.Ignored] 而非抛异常
     */
    fun reduce(
        current: OverlayState,
        event: OverlayEvent,
        runningTaskId: String? = null,
    ): OverlayTransition {
        // 急停优先级最高：任何状态下都应立即响应，且不受 runningTaskId 约束。
        // 即使用户误判（其实没任务在跑），也照常给反馈 —— 急停开关不能"看起来没反应"。
        if (event is OverlayEvent.EmergencyStop) {
            return OverlayTransition.Moved(
                from = current,
                to = OverlayState.DOT,
                effect = OverlayEffect.BroadcastStop,
            )
        }

        return when (current) {
            OverlayState.DOT -> reduceFromDot(event, runningTaskId)
            OverlayState.EDGE -> reduceFromEdge(event, runningTaskId)
            OverlayState.RUNNING -> reduceFromRunning(event, runningTaskId)
            OverlayState.PANEL -> reduceFromPanel(event, runningTaskId)
        }
    }

    private fun reduceFromDot(event: OverlayEvent, runningTaskId: String?): OverlayTransition = when (event) {
        OverlayEvent.Tap -> moved(
            OverlayState.DOT, OverlayState.PANEL, OverlayEffect.ShowPanel,
        )

        OverlayEvent.DragToEdge -> moved(
            OverlayState.DOT, OverlayState.EDGE, OverlayEffect.SnapToEdge,
        )

        is OverlayEvent.TaskStarted -> moved(
            OverlayState.DOT, OverlayState.RUNNING, OverlayEffect.ShowProgress,
        )

        OverlayEvent.LongPress -> moved(
            OverlayState.DOT, OverlayState.DOT, OverlayEffect.ShowModelPicker,
        )

        else -> ignored(OverlayState.DOT, event, "圆点状态下该事件无意义")
    }

    /**
     * 从贴边状态出发。
     *
     * ⚠️ 贴边状态下**允许**点开面板：用户虽然想省地方，但主动点击就是要交互。
     * 点击后回到 [OverlayState.PANEL]，收起时回到 [OverlayState.DOT] 而非 [OverlayState.EDGE]
     * —— 用户的"贴边"意图已被这次主动交互打断，回到默认态更符合直觉。
     */
    private fun reduceFromEdge(event: OverlayEvent, runningTaskId: String?): OverlayTransition = when (event) {
        OverlayEvent.Tap -> moved(
            OverlayState.EDGE, OverlayState.PANEL, OverlayEffect.ShowPanel,
        )

        OverlayEvent.DragFromEdge -> moved(
            OverlayState.EDGE, OverlayState.DOT, OverlayEffect.UnsnapFromEdge,
        )

        is OverlayEvent.TaskStarted -> moved(
            OverlayState.EDGE, OverlayState.EDGE, OverlayEffect.ShowProgress,
        )

        OverlayEvent.LongPress -> moved(
            OverlayState.EDGE, OverlayState.EDGE, OverlayEffect.ShowModelPicker,
        )

        else -> ignored(OverlayState.EDGE, event, "贴边状态下该事件无意义")
    }

    private fun reduceFromRunning(event: OverlayEvent, runningTaskId: String?): OverlayTransition = when (event) {
        OverlayEvent.Tap -> moved(
            OverlayState.RUNNING, OverlayState.PANEL, OverlayEffect.ShowPanel,
        )

        OverlayEvent.DragToEdge -> moved(
            OverlayState.RUNNING, OverlayState.EDGE, OverlayEffect.SnapToEdge,
        )

        is OverlayEvent.TaskStarted -> ignored(
            OverlayState.RUNNING,
            "已有任务 $runningTaskId 在执行，不接受并发任务",
        )

        is OverlayEvent.TaskFinished -> if (runningTaskId != null && event.taskId != runningTaskId) {
            ignored(OverlayState.RUNNING, "收到的完成事件属于其他任务 ${event.taskId}")
        } else {
            moved(OverlayState.RUNNING, OverlayState.DOT, OverlayEffect.HideProgress)
        }

        OverlayEvent.LongPress -> moved(
            OverlayState.RUNNING, OverlayState.RUNNING, OverlayEffect.ShowModelPicker,
        )

        else -> ignored(OverlayState.RUNNING, event, "任务进行中该事件无意义")
    }

    private fun reduceFromPanel(event: OverlayEvent, runningTaskId: String?): OverlayTransition = when (event) {
        OverlayEvent.DismissPanel, OverlayEvent.Tap -> moved(
            OverlayState.PANEL, OverlayState.DOT, OverlayEffect.HidePanel,
        )

        is OverlayEvent.TaskFinished -> moved(
            OverlayState.PANEL, OverlayState.DOT, OverlayEffect.HidePanel,
        )

        is OverlayEvent.TaskStarted -> ignored(
            OverlayState.PANEL,
            "已有任务 $runningTaskId 在执行，不接受并发任务",
        )

        else -> ignored(OverlayState.PANEL, event, "面板展开时该事件无意义")
    }

    private fun moved(
        from: OverlayState,
        to: OverlayState,
        effect: OverlayEffect,
    ) = OverlayTransition.Moved(from = from, to = to, effect = effect)

    private fun ignored(
        state: OverlayState,
        event: OverlayEvent,
        reason: String,
    ) = OverlayTransition.Ignored(state = state, reason = reason)

    private fun ignored(
        state: OverlayState,
        reason: String,
    ) = OverlayTransition.Ignored(state = state, reason = reason)
}

/** 迁移结果的状态便捷访问器 —— 无论成功或忽略，都能拿到"当前应处于的状态" */
val OverlayTransition.resultingState: OverlayState
    get() = when (this) {
        is OverlayTransition.Moved -> to
        is OverlayTransition.Ignored -> state
    }

/** 迁移成功时返回其状态，否则返回 null */
val OverlayTransition.movedTo: OverlayState?
    get() = (this as? OverlayTransition.Moved)?.to

/** 迁移成功时返回其副作用，否则返回 null */
val OverlayTransition.effectOrNull: OverlayEffect?
    get() = (this as? OverlayTransition.Moved)?.effect
