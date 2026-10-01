package com.pocketagent.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 无障碍服务 —— **通道壳**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它经历过两个阶段，注释保留两段是为了让"为什么现在是这个形态"可追溯
 * ═══════════════════════════════════════════════════════════════
 *
 * **阶段一（M0）：实验器材，刻意什么都不做。**
 * 那时它只为 EX-13（侧载应用的受限设置解除流程）提供"被拦截的资格"。
 * 它不读屏、不操作、不缓存 —— 因为能力必须与用途严格相等，
 * 否则"忘了删掉"这个错误一旦发生就是实打实的隐私事故。
 *
 * **阶段二（现在）：通道壳。**
 * 阶段一的注释里写明了交接条件：
 *
 * > 真正的感知层实现在 `:perception`，它落地时本类要么被替换、
 * > 要么被改造成一个纯粹的通道壳。**在那之前，保持它什么都不做。**
 *
 * 现在到了那个时候。本类只做一件事：**把 Android 的原始能力原样递出去**。
 *
 * ⚠️ **它仍然不做任何业务判断**：
 * - 不决定"要不要截图"（那是 `:perception` 的降级链）
 * - 不决定"要不要点这里"（那是 `:action` 的调度器）
 * - 不缓存、不落盘、不做隐私过滤（那是 `PrivacyFilter` 的职责）
 *
 * 这条边界必须守住：一旦这里开始"顺手判断一下"，同一份判断就会
 * 在 `:perception`、`:action`、本类各有一份，而它们迟早会不一致 ——
 * 不一致的那一份会静默生效，且只在特定路径上生效。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条硬约束（违反任一条，行为会变得不可预测）
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 所有方法必须在主线程调用。**
 *    `rootInActiveWindow` / `takeScreenshot` / `dispatchGesture` 三个
 *    API 都要求主线程。在别的线程调用的表现不是抛异常，是**返回 null
 *    或永远不回调** —— 看起来像"这个能力不可用"。
 *
 * **2. [rootNode] 的返回对象必须由调用方 recycle。**
 *    它不是普通对象，持有它会阻止整棵树的 GC。本类刻意不代管生命周期：
 *    代管意味着要猜调用方什么时候用完，而猜错的代价是内存泄漏或
 *    访问已回收对象 —— 两者都比"调用方多写一行 recycle"贵得多。
 *
 * **3. [takeScreenshot] 拿到的是**硬件缓冲**，必须在回调里立刻转成
 *    Bitmap 并 close 掉 buffer。** 缓冲数量有限（系统级池），
 *    泄漏几个之后所有截图都会失败，而错误码是通用的。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        _connected.value = true
        instance = this
        Timber.i("无障碍服务已连接（通道壳：提供树 / 截图 / 手势，不做判断）")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // ⚠️ **仍然刻意留空。**
        //
        // 事件回调不是"顺便记点东西"的地方。感知层要的是**按需采集**
        // （用户问一句，采一次），而不是持续监听 —— 后者会让
        // "助理在什么时候看过我的屏幕"变成一个无法回答的问题。
        //
        // 留空还有一个实际好处：本服务的耗电与后台活跃度与"有没有事件"
        // 无关，因此不会因为用户刷了半小时视频而被系统判定为异常。
    }

    override fun onInterrupt() {
        // 无障碍反馈被系统中断。本服务不提供反馈，无需处理。
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _connected.value = false
        instance = null
        Timber.i("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── 通道：读树 ──────────────────────────────────────────────

    /**
     * 当前活动窗口的根节点。**调用方负责 recycle。**
     *
     * 返回 null 的三种情况，调用方**无法区分**（这是 Android API 的限制）：
     * 1. 服务没连上
     * 2. 当前窗口不提供无障碍节点（如某些游戏、DRM 保护的界面）
     * 3. 主线程被阻塞导致取不到
     *
     * ⇒ 所以调用方拿到的 null 只能当作"这次读不到"，
     *   而**不能**当作"这个界面是空的"。这个区别很重要：
     *   前者应该走截图兜底，后者应该报告"没有可操作元素"。
     */
    fun rootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /** 当前前台应用的包名。取不到时返回 null。 */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    // ── 通道：截图 ──────────────────────────────────────────────

    /**
     * 截图。**API 30+**。
     *
     * ⚠️ 这里的 [onResult] 会在**主线程**回调，且必须在回调内完成
     *    Bitmap 的构造 —— `hardwareBuffer` 在回调返回后即失效。
     *
     * @param onResult 成功给出 Bitmap；失败给出原因（错误码已翻译成人话）
     */
    fun takeScreenshotCompat(onResult: (Result<Bitmap>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(Result.failure(UnsupportedOperationException("takeScreenshot 需要 Android 11+")))
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            // ⚠️ 第二个参数是 **Executor**，不是 Handler。
            //    `Handler` 只在 API 28 之前是合法的 Executor 替代品，
            //    现在传它会直接编译不过（"actual type is Handler, but Executor was expected"）。
            //    `mainExecutor` 由 Context 提供，保证回调在主线程 —— 这是必需的，
            //    因为 hardwareBuffer 在回调返回后即失效，转换必须当场完成。
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        onResult(
                            if (bitmap == null) {
                                Result.failure(IllegalStateException("wrapHardwareBuffer 返回 null"))
                            } else {
                                Result.success(bitmap)
                            },
                        )
                    } catch (e: Exception) {
                        onResult(Result.failure(e))
                    } finally {
                        // ★ 必须 close：缓冲是系统级池，泄漏几个之后所有截图都会失败
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    onResult(Result.failure(IllegalStateException(describeScreenshotError(errorCode))))
                }
            },
        )
    }

    // ── 通道：手势 ──────────────────────────────────────────────

    /**
     * 派发一个手势。
     *
     * ⚠️ **返回 true 只代表"手势被系统受理"，不代表"目标被点中了"。**
     *    这是本项目反复强调的那条线：`dispatchGesture` 的完成回调
     *    在**手势动画结束**时触发，与"被点的那个控件有没有响应"无关。
     *    ⇒ 所以 `:action` 的 `ActionExecutor` 只报告"已派发"，
     *      由 Verifier 重新采集一次快照来确认效果。
     *
     * @param onCompleted 系统是否完成了手势派发
     */
    fun performGesture(gesture: GestureDescription, onCompleted: (Boolean) -> Unit) {
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onCompleted(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onCompleted(false)
            }
        }, null)
    }

    private fun describeScreenshotError(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误（ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR）"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "服务没有截图权限 —— 检查配置里有没有 canTakeScreenshot=true"

        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "两次截图间隔太短（ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT）"
            // ⚠️ 这条在实践中一定会遇到：系统对截图有最小间隔限制。
            //    调用方应当退避重试，而不是把它当成"能力不可用"。

        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "displayId 无效"
        else -> "未知错误码 $code"
    }

    companion object {
        /**
         * 当前绑定的服务实例。null = 未连接。
         *
         * ⚠️ 这是**进程内**引用，不跨进程、不落盘。
         *    [onUnbind] / [onDestroy] 都会置空 —— 不置空会泄漏整个 Service
         *    （它持有 Context），而泄漏的 Service 仍然能响应截图与手势，
         *    于是"服务已断开"和"服务还活着"会同时成立。
         */
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)

        /**
         * 服务当前是否被系统绑定。
         *
         * ⚠️ 它**不能**用来判断"用户有没有开启权限" ——
         *    服务被系统绑定与权限被授予是两件事，前者会滞后。
         *    判断权限状态请读 `AccessibilityManager.getEnabledAccessibilityServiceList`。
         */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()
    }
}
