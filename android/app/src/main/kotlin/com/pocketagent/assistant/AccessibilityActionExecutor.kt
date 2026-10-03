package com.pocketagent.assistant

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.pocketagent.action.ActionExecutor
import com.pocketagent.action.ActionResult
import com.pocketagent.action.ActionType
import com.pocketagent.action.ElementRef
import com.pocketagent.action.ExecutorChannel
import com.pocketagent.action.HumanizePolicy
import com.pocketagent.action.UiAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * `ACCESSIBILITY` 通道的动作执行器 —— 用 `dispatchGesture` 实现点击 / 长按 / 滑动。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它只报告「动作是否被派发」，不报告「有没有生效」
 * ═══════════════════════════════════════════════════════════════
 *
 * `dispatchGesture` 的完成回调在**手势动画结束**时触发 ——
 * 与"被点的那个控件有没有响应"**毫无关系**。
 *
 * ⇒ 所以本类返回 [ActionResult.Dispatched]，**不返回"成功"**。
 *    "有没有生效"要靠 Verifier 重新采集一次快照来确认。
 *
 * ⚠️ 把这两件事混起来是本类最容易犯的错：一旦这里返回"成功"，
 *    上层就再也不会去验证了 —— 而**手势被受理**和**事情办成了**
 *    之间隔着整整一个应用。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么服务实例是「每次取」而不是「构造时注入」
 * ═══════════════════════════════════════════════════════════════
 *
 * 无障碍服务随时可能被系统断开重连（用户在设置里关一下再开、
 * 系统回收后重启）。构造时注入一个实例，会在重连后**持有一个死引用**，
 * 表现为"明明权限开着，就是点不动"。
 *
 * ⇒ 用 `() -> Service?` 每次现取。**这是"服务型依赖"的通用纪律**：
 *    生命周期比调用方短的东西，不能存引用。
 */
class AccessibilityActionExecutor(
    private val serviceProvider: () -> AgentAccessibilityService? = {
        AgentAccessibilityService.instance
    },
) : ActionExecutor {

    override val channel: ExecutorChannel = ExecutorChannel.ACCESSIBILITY

    override fun isAvailable(): Boolean = serviceProvider() != null

    /** 无障碍是首选通道 —— 它最直接、延迟最低。 */
    override fun priority(): Int = 100

    override fun supportedActions(): Set<ActionType> = setOf(
        ActionType.CLICK,
        ActionType.TAP_COORD,
        ActionType.LONG_PRESS,
        ActionType.SWIPE,
    )

    override suspend fun perform(action: UiAction): ActionResult {
        val service = serviceProvider()
            ?: return ActionResult.ChannelUnavailable(
                channel,
                "无障碍服务未连接。请在系统设置中开启本应用的「辅助功能」后重试。",
            )

        if (action.type !in supportedActions()) {
            return ActionResult.Unsupported(channel)
        }

        // ── 解析目标坐标 ─────────────────────────────────────────
        // ★ 统一成「起点→终点」形状（单击时起点==终点）。
        //   ⚠️ 不能让 when 的两个分支各自返回 Pair<Int,Int> 与
        //      Pair<Pair<..>,Pair<..>> —— Kotlin 会把公共类型统一成
        //      Serializable（Pair 实现了它），后面整个用不了，
        //      报错还落在毫不相干的下一行（本次踩坑实录）。
        val coords: Pair<Pair<Int, Int>, Pair<Int, Int>> = when (action.type) {
            ActionType.SWIPE -> resolveSwipe(action)
                ?: return ActionResult.TargetNotFound(action.targetDescription)
            else -> {
                val p = resolveTapPoint(action)
                    ?: return ActionResult.TargetNotFound(action.targetDescription)
                p to p
            }
        }

        val (from, to) = coords
        val duration = when (action.type) {
            ActionType.LONG_PRESS -> action.durationMs ?: DEFAULT_LONG_PRESS_MS
            ActionType.SWIPE -> action.durationMs ?: DEFAULT_SWIPE_MS
            else -> DEFAULT_TAP_MS
        }

        val gesture = buildGesture(from, to, duration)
            ?: return ActionResult.Failed(channel, "手势参数非法（起止点相同或越界）。")

        val startedAt = System.currentTimeMillis()
        val accepted = withContext(Dispatchers.Main) { dispatch(service, gesture) }
        val latency = System.currentTimeMillis() - startedAt

        return if (accepted) {
            // ⚠️ 注意措辞：Dispatched，不是 Success —— 见类注释。
            ActionResult.Dispatched(channel, latency)
        } else {
            ActionResult.Failed(channel, "系统取消了这次手势派发（可能有更高优先级的触摸或服务被断开）。")
        }
    }

    // ── 内部 ────────────────────────────────────────────────────

    private fun resolveTapPoint(action: UiAction): Pair<Int, Int>? {
        action.point?.let { return it.x to it.y }

        val bounds = action.elementRef?.bounds ?: return null
        if (bounds.width() <= 0 || bounds.height() <= 0) return null

        // ★ 拟人化：不在正中心点，在元素内随机偏移。
        //   正中心是自动化最明显的特征，且部分控件中心可能是空白区。
        val maxDx = (bounds.width() * HumanizePolicy.MAX_OFFSET_RATIO).roundToInt()
        val maxDy = (bounds.height() * HumanizePolicy.MAX_OFFSET_RATIO).roundToInt()
        val cx = bounds.centerX() + if (maxDx > 0) (-maxDx..maxDx).random() else 0
        val cy = bounds.centerY() + if (maxDy > 0) (-maxDy..maxDy).random() else 0
        return cx to cy
    }

    private fun resolveSwipe(action: UiAction): Pair<Pair<Int, Int>, Pair<Int, Int>>? {
        val from = action.point ?: return null
        val to = action.endPoint ?: return null
        // ⚠️ 不用 `(a to b) to (c to d)` 的嵌套中缀 —— Kotlin 会把整条链推断成
        //    Serializable 而不是 Pair<Pair<..>, Pair<..>>，编译直接报错。
        return Pair(Pair(from.x, from.y), Pair(to.x, to.y))
    }

    private fun buildGesture(
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
        durationMs: Long,
    ): GestureDescription? {
        if (durationMs <= 0) return null

        val path = Path().apply {
            moveTo(from.first.toFloat(), from.second.toFloat())
            if (to != from) lineTo(to.first.toFloat(), to.second.toFloat())
        }

        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
    }

    private suspend fun dispatch(
        service: AgentAccessibilityService,
        gesture: GestureDescription,
    ): Boolean = suspendCancellableCoroutine { cont ->
        try {
            service.performGesture(gesture) { ok ->
                if (cont.isActive) cont.resume(ok)
            }
        } catch (e: Exception) {
            // ⚠️ 不吞异常：记日志 + 如实返回 false。
            //    `dispatchGesture` 在服务刚被断开时可能抛 —— 那是真实故障，
            //    但**不是本方法的异常**，上层要的是"这次没成"，不是堆栈。
            Timber.w(e, "手势派发抛异常")
            if (cont.isActive) cont.resume(false)
        }
    }

    private companion object {
        const val DEFAULT_TAP_MS = 60L
        const val DEFAULT_LONG_PRESS_MS = 800L
        const val DEFAULT_SWIPE_MS = 300L
    }
}
