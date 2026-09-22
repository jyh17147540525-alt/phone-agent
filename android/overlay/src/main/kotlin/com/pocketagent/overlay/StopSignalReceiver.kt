package com.pocketagent.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * 急停信号接收器。
 *
 * ⚠️ **`exported="false"`（见 manifest）** —— 急停信号只允许应用内部投递。
 * 开放给外部等于给了任意应用"停止我们任务"的能力。
 *
 * 本接收器的职责很窄：把广播转成对 [OverlayService] 的一次调用。
 * **它不执行停止逻辑本身** —— 那在独立进程的 agent 侧，
 * 由 `StopHandler` 处理（见 `overlaylogic` 模块，已单测覆盖）。
 */
class StopSignalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_REQUESTED -> {
                val taskId = intent.getStringExtra(OverlayService.EXTRA_TASK_ID)
                Timber.i("收到急停广播，taskId=%s", taskId)
                // 转发给悬浮球服务，由它驱动状态机并广播给执行进程。
                // 即使服务没在跑，也不应崩溃 —— 只是这次停止没有 UI 反馈。
                runCatching {
                    context.startService(
                        OverlayService.emergencyStopIntent(context),
                    )
                }.onFailure {
                    Timber.w(it, "转发急停到悬浮球服务失败（服务可能未运行）")
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP_REQUESTED = "com.pocketagent.overlay.STOP_REQUESTED"
    }
}
