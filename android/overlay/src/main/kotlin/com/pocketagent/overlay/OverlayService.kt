package com.pocketagent.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.pocketagent.overlaylogic.OverlayEffect
import com.pocketagent.overlaylogic.OverlayEvent
import com.pocketagent.overlaylogic.OverlayState
import com.pocketagent.overlaylogic.OverlayStateMachine
import com.pocketagent.overlaylogic.OverlayTransition
import com.pocketagent.overlaylogic.StopHandler
import com.pocketagent.overlaylogic.StopOutcome
import com.pocketagent.overlaylogic.StopRequest
import com.pocketagent.overlaylogic.StopSource
// ⚠️ 扩展属性/函数必须显式 import —— 它们不是类成员，
//    不会因为"导入了 OverlayTransition"就自动可见。
import com.pocketagent.overlaylogic.resultingState
import kotlin.math.abs
import timber.log.Timber
import java.util.UUID

/**
 * 悬浮球服务。
 *
 * ## 职责边界（重要）
 *
 * ⚠️ **本服务只负责"显示"与"交互"，绝不执行任务。**
 *
 * ```
 * OverlayService  ── 显示圆点、接收用户点击、广播停止信号
 *       ↓
 * :agent 进程     ── 执行任务（独立进程，崩溃不影响悬浮球）
 * ```
 *
 * 这样即使 agent 跑飞（死循环、被模型带偏），圆点仍然响应"停止"。
 * **这是"急停开关必须在另一个进程"的具体落地。**
 *
 * ## 状态迁移
 *
 * 所有状态判断都委托给 [OverlayStateMachine]（纯逻辑、已单测覆盖）。
 * 本类只做两件事：把 Android 事件翻译成 `OverlayEvent`，
 * 以及把返回的 `OverlayEffect` 变成实际的 UI 操作。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var state: OverlayState = OverlayState.DOT
    private val stopHandler = StopHandler()

    /** 当前执行中的任务 ID（由外部通过 Intent 告知） */
    private var runningTaskId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> showBall()
            ACTION_STOP -> {
                hideBall()
                stopSelf()
            }
            ACTION_TASK_STARTED -> {
                runningTaskId = intent.getStringExtra(EXTRA_TASK_ID)
                dispatch(OverlayEvent.TaskStarted(runningTaskId.orEmpty()))
            }
            ACTION_TASK_FINISHED -> {
                val finished = intent.getStringExtra(EXTRA_TASK_ID)
                runningTaskId = null
                dispatch(OverlayEvent.TaskFinished(finished.orEmpty()))
            }
            ACTION_EMERGENCY_STOP -> handleEmergencyStop()
            else -> showBall()
        }

        // ⚠️ 不用 START_STICKY：服务被杀后静默重启会让用户看到"球又回来了"。
        // 悬浮球是用户显式开启的东西，重启应由用户决定。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        hideBall()
        super.onDestroy()
    }

    // ── 前台服务 ──────────────────────────────────────────────

    /**
     * 提升为前台服务。
     *
     * ⚠️ 类型是 `specialUse`（见 manifest）：悬浮球需要长期驻留，
     * 而 `dataSync`/`mediaProcessing` 在 Android 15 上 24 小时只能跑 6 小时。
     */
    private fun promoteToForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "悬浮球",
            // IMPORTANCE_LOW：常驻通知不该响铃或弹横幅 —— 那本身就是干扰
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持悬浮球可用，用于随时停止任务"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮球运行中")
            .setContentText("点击可停止当前任务")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    // ── 悬浮球视图 ────────────────────────────────────────────

    private fun showBall() {
        if (ballView != null) return

        val view = View(this).apply {
            setBackgroundColor(BALL_COLOR)
            setOnTouchListener(BallTouchListener())
        }

        val params = WindowManager.LayoutParams(
            BALL_SIZE_PX,
            BALL_SIZE_PX,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // ⚠️ 三个 flag 缺一不可：
            //   NOT_FOCUSABLE  —— 不抢焦点，用户能照常用下面的 App（"减少干扰"的关键）
            //   NOT_TOUCH_MODAL —— 球外的触摸事件透传给下层应用
            //   LAYOUT_NO_LIMITS —— 允许贴边，不受系统默认边距限制
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = INITIAL_X
            y = INITIAL_Y
        }

        windowManager.addView(view, params)
        ballView = view
        ballParams = params
    }

    private fun hideBall() {
        ballView?.let { view ->
            runCatching { windowManager.removeView(view) }
                .onFailure { Timber.w(it, "移除悬浮球失败") }
        }
        ballView = null
        ballParams = null
    }

    /** 触摸处理：区分单击 / 长按 / 拖动 */
    private inner class BallTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var isDragging = false

        /** 本次触摸已由长按消费，抬起时不再触发单击。必须在 gestureDetector 之前声明 */
        private var longPressHandled = false

        /**
         * 用标准 GestureDetector 识别长按。
         *
         * ⚠️ 不要自己用计时器/标记位实现长按 —— 那要处理
         *    ACTION_CANCEL、多指、滑动取消等一堆边界，很容易漏。
         */
        private val gestureDetector = android.view.GestureDetector(
            this@OverlayService,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (!isDragging) {
                        dispatch(OverlayEvent.LongPress)
                        longPressHandled = true
                    }
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (!isDragging && !longPressHandled) {
                        dispatch(OverlayEvent.Tap)
                    }
                    return true
                }
            },
        )

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = ballParams ?: return false

            // 先让手势识别器看事件，它负责判定长按与单击
            gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    isDragging = false
                    longPressHandled = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartX
                    val dy = event.rawY - touchStartY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD_PX || abs(dy) > DRAG_THRESHOLD_PX)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    // 拖动结束时做吸附判定；单击/长按已由 gestureDetector 处理
                    if (isDragging) {
                        handleDragEnd()
                    }
                    isDragging = false
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    longPressHandled = false
                    return true
                }
            }
            return false
        }
    }

    private fun handleDragEnd() {
        val params = ballParams ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val shouldSnap = params.x < SNAP_THRESHOLD_PX ||
            params.x > screenWidth - BALL_SIZE_PX - SNAP_THRESHOLD_PX

        if (shouldSnap) {
            dispatch(OverlayEvent.DragToEdge)
        } else if (state == OverlayState.EDGE) {
            dispatch(OverlayEvent.DragFromEdge)
        }
    }

    // ── 状态机桥接 ────────────────────────────────────────────

    /** 把一个 Android 侧事件交给纯逻辑状态机，并执行返回的副作用 */
    private fun dispatch(event: OverlayEvent) {
        val transition = OverlayStateMachine.reduce(
            current = state,
            event = event,
            runningTaskId = runningTaskId,
        )
        state = transition.resultingState

        when (transition) {
            is OverlayTransition.Moved -> applyEffect(transition.effect)
            is OverlayTransition.Ignored ->
                Timber.d("悬浮球事件 %s 被忽略：%s", event, transition.reason)
        }
    }

    private fun applyEffect(effect: OverlayEffect) {
        when (effect) {
            OverlayEffect.None -> Unit
            OverlayEffect.ShowPanel -> showPanel()
            OverlayEffect.HidePanel -> hidePanel()
            OverlayEffect.ShowProgress -> setProgressVisible(true)
            OverlayEffect.HideProgress -> setProgressVisible(false)
            OverlayEffect.SnapToEdge -> snapToEdge()
            OverlayEffect.UnsnapFromEdge -> unsnapFromEdge()
            OverlayEffect.ShowModelPicker -> showModelPicker()
            OverlayEffect.BroadcastStop -> broadcastStop()
        }
    }

    // ── 副作用实现 ────────────────────────────────────────────

    private fun showPanel() {
        // TODO(M1)：上滑出一块卡片，含任务描述 / 文字输入框 / 停止按钮。
        // 布局与视觉规范见 docs/多Key与模型调度设计-v1.0.md §5.2。
        Timber.d("展开面板（M1 实现）")
    }

    private fun hidePanel() {
        Timber.d("收起面板（M1 实现）")
    }

    private fun setProgressVisible(visible: Boolean) {
        Timber.d("进度环 visible=%s（M1 实现）", visible)
    }

    private fun snapToEdge() {
        // TODO(M1)：按 OverlayGeometry.computeEdgePosition 计算位置并更新。
        // ⚠️ 关键：贴边后仍要保留一条细线在屏幕内 ——
        //    完全移出会丢掉"可见 overlay 窗口"的资格，进而影响后台启动前台服务。
        Timber.d("吸附到边缘（M1 实现）")
    }

    private fun unsnapFromEdge() {
        Timber.d("从边缘展开（M1 实现）")
    }

    private fun showModelPicker() {
        // TODO(M1)：临时指定本次任务用哪个模型（见设计文档 §4.3）
        Timber.d("打开临时模型选择（M1 实现）")
    }

    /**
     * 广播急停信号。
     *
     * ⚠️ 这是跨进程投递 —— 执行进程独立于本服务。
     * 即使用户点击时 agent 已崩溃，广播也应正常发出（接收方自行判空）。
     */
    private fun broadcastStop() {
        val request = StopRequest(
            requestId = UUID.randomUUID().toString(),
            taskId = runningTaskId,
            timestampMs = System.currentTimeMillis(),
            source = StopSource.OVERLAY_BALL,
        )

        when (val outcome = stopHandler.handle(request, runningTaskId, System.currentTimeMillis())) {
            is StopOutcome.Accepted -> {
                Timber.i("急停已受理：%s", outcome.requestId)
                sendStopBroadcast(request)
            }
            is StopOutcome.NothingToStop -> {
                // ⚠️ 即使无事可停也要给用户反馈（例如球闪一下），
                //    否则用户以为急停坏了，反复点击。详见 StopSignal.kt 的注释。
                Timber.i("急停时无任务在跑：%s", outcome.requestId)
                sendStopBroadcast(request)
            }
            is StopOutcome.Duplicate -> {
                Timber.d("重复的急停请求，忽略：%s", outcome.requestId)
            }
        }
    }

    private fun sendStopBroadcast(request: StopRequest) {
        // TODO(M1)：换成显式 Intent 指向 :agent 的接收器（当前 agent 进程未实现）。
        // 这里先留一个应用内广播，接口形状已定型。
        Timber.d("广播停止信号：taskId=%s", request.taskId)
    }

    private fun handleEmergencyStop() {
        dispatch(OverlayEvent.EmergencyStop)
    }

    companion object {
        const val ACTION_START = "com.pocketagent.overlay.START"
        const val ACTION_STOP = "com.pocketagent.overlay.STOP"
        const val ACTION_TASK_STARTED = "com.pocketagent.overlay.TASK_STARTED"
        const val ACTION_TASK_FINISHED = "com.pocketagent.overlay.TASK_FINISHED"
        const val ACTION_EMERGENCY_STOP = "com.pocketagent.overlay.EMERGENCY_STOP"
        const val EXTRA_TASK_ID = "task_id"

        private const val CHANNEL_ID = "overlay_ball"
        private const val NOTIFICATION_ID = 4201

        /** 球直径与初始位置（px）。真实值应由 app 层按 density 换算后传入。 */
        private const val BALL_SIZE_PX = 120
        private const val INITIAL_X = 0
        private const val INITIAL_Y = 600

        private const val DRAG_THRESHOLD_PX = 12
        private const val SNAP_THRESHOLD_PX = 80

        private const val BALL_COLOR = 0x8834A853.toInt()

        /** 便捷启动入口 */
        fun startIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_STOP)

        /** 紧急停止 —— 供通知栏 / 应用内按钮调用 */
        fun emergencyStopIntent(context: Context): Intent =
            Intent(context, OverlayService::class.java).setAction(ACTION_EMERGENCY_STOP)
    }
}
