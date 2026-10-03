package com.pocketagent.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pocketagent.MainActivity
import com.pocketagent.PocketAgentApp
import com.pocketagent.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 保活前台服务 —— dsh 集成（模型网关 + 能力桥）的**唯一保活锚点**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么必须有它（2026-10-03 真机实证）
 * ═══════════════════════════════════════════════════════════════
 *
 * 缺少前台服务时，应用退到后台 → 进程进入 cached 状态被**冷冻**
 * （cached app freezer）：进程还活着、端口还在听，但 HTTP 请求
 * **不再被处理**（真机实测：12 秒超时、0 响应）。
 *
 * 而 dsh 的两条链路（模型网关 / `screen_read` 能力桥）恰恰都是
 * "**应用在后台时被调用**"的形态 —— `screen_read` 要读的就是别的 App，
 * 那时 PocketAgent 必然在后台。没有保活，它们只剩"应用在前台"这
 * 一个可用场景，等于不可用（10-03 之前的"联调成功"读的是
 * PocketAgent 自己，所以没暴露这个问题 —— 又一个"演示能跑、真用不行"）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么用 specialUse，而不是 dataSync / mediaProjection
 * ═══════════════════════════════════════════════════════════════
 *
 * Android 15 起，`dataSync` / `mediaProcessing` 类前台服务在 24 小时内
 * **累计**只能运行 6 小时，超时抛 `RemoteServiceException` ——
 * 而本服务要在用户开着 dsh 集成的整个期间常驻。`specialUse` 不受该限制
 * （需在 manifest 里用 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 说明用途）。
 * 完整推演见 `docs/后台常驻与语音交互方案-v1.0.md`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 通知是**信息面**，「停止」按钮的语义是"全停"
 * ═══════════════════════════════════════════════════════════════
 *
 * 通知只做三件事：告诉用户"进程正在为 dsh 常驻"、点按回到应用、
 * 提供「全部停止」。**「全部停止」= 把网关与能力桥两个开关都关**
 * （走容器里与界面完全相同的停止路径）——
 * 刻意**不做**"只停保活"这种半状态：那个状态下两条链路在界面上仍是
 * "运行中"、实际却一冻就死，是又一个"界面说假话"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 状态文本由**容器**通过 EXTRA 传入
 * ═══════════════════════════════════════════════════════════════
 *
 * 服务不认识"网关"与"能力桥"这两个概念 —— 它只有一个状态文本。
 * 谁在跑、文本怎么写，全部由 `AppContainer.syncKeepAlive()` 决定
 * （单一事实来源；服务重复 onStartCommand 即就地更新通知）。
 */
class AgentForegroundService : Service() {

    /**
     * 「全部停止」要在 IO 线程执行 —— 容器的两个 stop 都是**同步阻塞**
     * （要与 `AppContainer` 里"调用方必须切到 IO 线程"那条纪律一致；
     * 通知动作的回调跑在主线程）。
     */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopEverythingAsync()

            // 默认（也含重复 start）：更新常驻通知。同 id 的 startForeground
            // 是幂等的 —— 文本随"现在开着什么"变化就从这里进来。
            else -> startAsForeground(intent?.getStringExtra(EXTRA_STATUS_TEXT).orEmpty())
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        io.cancel()
        super.onDestroy()
    }

    // ── 前台化 ──────────────────────────────────────────────────

    private fun startAsForeground(statusText: String) {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // 34+ 必须显式传类型，且必须已声明 + 已持有对应权限。
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            // 29 到 33：类型可选，传 0 交给 manifest 声明。
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(statusText), type)
        } catch (e: Exception) {
            // 走到这里基本只有一种成因：manifest 声明或权限被改坏（开发期错误）。
            // 记日志并自停 —— 不让一条"已经不代表保活"的通知继续挂着。
            Timber.e(e, "保活前台服务启动失败 —— dsh 集成在后台将不可用")
            stopSelf()
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keepalive_channel_name),
                // 低优先级：静默、不弹窗，但常驻可见（保活通知的合理档位）。
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.keepalive_channel_desc)
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(statusText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(statusText.ifEmpty { null })
            .setContentIntent(tapIntent)
            .addAction(0, getString(R.string.keepalive_notification_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // 默认的"延迟显示"可能让通知 10 秒内不可见 —— 保活通知要立刻可见。
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── 「全部停止」────────────────────────────────────────────

    private fun stopEverythingAsync() {
        io.launch {
            runCatching {
                val container = (application as? PocketAgentApp)?.container
                // 两个 stop 都是幂等的；顺序无关。
                container?.stopDshIntegration()
                container?.stopMcpBridge()
            }.onFailure {
                Timber.w(it, "从通知执行「全部停止」失败 —— 仍会停掉保活服务")
            }
            // 容器里的 stop 已经会连带停掉本服务（syncKeepAlive）；
            // stopSelf 只是兜底：容器未初始化时也不留一条孤儿通知。
            stopSelf()
        }
    }

    companion object {

        private const val CHANNEL_ID = "dsh_keepalive"

        /**
         * 固定的通知 id —— 重复 start 时原地更新同一条通知，
         * 不会出现"第二条保活通知"。
         */
        private const val NOTIFICATION_ID = 1001

        private const val EXTRA_STATUS_TEXT = "status_text"

        private const val ACTION_STOP = "com.pocketagent.action.KEEPALIVE_STOP"

        /**
         * 拉起（或更新）保活服务。返回是否**成功把请求交给了系统**。
         *
         * ⚠️ 只在两条链路至少一条在跑时调用；都停了要调 [stop]
         *    （那一层判断在 `AppContainer.syncKeepAlive()`，本服务不做业务判断）。
         *
         * ⚠️ 这里的 catch 是**如实的降级**，不是吞异常：典型成因是
         *    Android 12+ 的后台启动限制，而本调用来自用户在前台点击开关，
         *    正常不会命中。真命中时链路本身在前台场景仍然可用，
         *    记日志即可 —— 不要为一个"不该发生"的分支把调用方全改造成错误处理。
         */
        fun start(context: Context, statusText: String): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, AgentForegroundService::class.java)
                        .putExtra(EXTRA_STATUS_TEXT, statusText),
                )
                true
            } catch (e: Exception) {
                Timber.w(e, "保活前台服务未能启动 —— 应用退到后台后 dsh 将连不上")
                false
            }
        }

        /** 停止保活服务（幂等）。两条链路都停了才该调到这里。 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, AgentForegroundService::class.java))
            } catch (e: Exception) {
                // 停止失败没有补救动作（进程退出时系统会收走），记日志即可。
                Timber.w(e, "保活前台服务停止失败")
            }
        }
    }
}
