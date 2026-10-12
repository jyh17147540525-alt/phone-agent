package com.pocketagent.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.pocketagent.action.ActionExecutor
import com.pocketagent.action.ActionResult
import com.pocketagent.action.ActionType
import com.pocketagent.action.ElementRef
import com.pocketagent.action.ExecutorChannel
import com.pocketagent.action.HumanizePolicy
import com.pocketagent.action.UiAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        // 2026-10-12 加：在此之前本通道**只能点、不能写、不能导航** ——
        // 而"帮我搜一下 X"这类任务的第一步就是打字。见 performInputText 的注释。
        ActionType.INPUT_TEXT,
        ActionType.BACK,
        ActionType.HOME,
        ActionType.RECENTS,
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

        // ── 非手势类动作走各自的路径 ─────────────────────────────
        // ⚠️ 这两类**不能用坐标手势表达**：
        //    · INPUT_TEXT 要对节点调 `ACTION_SET_TEXT`（手势只能"点"，不能"写"）；
        //    · BACK/HOME/RECENTS 走 `performGlobalAction`（系统级，根本没有坐标）。
        //    把它们塞进下面的手势路径，得到的是"点了但没输入"这种**静默失败** ——
        //    手势派发成功、`ActionResult.Dispatched` 照常返回，而屏幕上什么都没变。
        when (action.type) {
            ActionType.INPUT_TEXT -> return performInputText(service, action)
            ActionType.BACK, ActionType.HOME, ActionType.RECENTS ->
                return performGlobal(service, action)

            else -> Unit
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

    /**
     * 往输入框里写文字。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 顺序：**先点一下，再写** —— 那不是多此一举
     * ═══════════════════════════════════════════════════════════
     *
     * `ACTION_SET_TEXT` 作用于**一个具体的节点**，而本通道此前只处理坐标手势、
     * 从不解析节点（见 `resolveTapPoint`：它只用 `elementRef.bounds` 算一个点）。
     *
     * 所以这里用"先点后写"：
     * 1. **点一下目标**（复用既有手势路径，含拟人化偏移）——
     *    那正是人手做同一件事的顺序；
     * 2. 等焦点落定，取 `findFocus(FOCUS_INPUT)`；
     * 3. ★ **自校验**：焦点节点的 bounds 必须**包含刚才点的那个点**。
     *    不含 ⇒ 说明点歪了/焦点没落上去 ⇒ **拒绝写入**。
     *    没有这一步，文字会写进"当时恰好持有焦点的那个框"——
     *    可能是上一个 App 残留的，也可能是页面顶部的搜索框。
     * 4. 写入后**不做读回比对** —— 真机实测它只会产生假阴性，理由见下面第 ④ 步的注释。
     *
     * ⚠️ 第 3、4 步是这类动作**唯一**的护栏：它没有"目标不存在"这种明显失败 ——
     *    写错地方和写对地方，`performAction` 都返回 true。
     */
    private suspend fun performInputText(
        service: AgentAccessibilityService,
        action: UiAction,
    ): ActionResult {
        val text = action.text
            ?: return ActionResult.Failed(channel, "INPUT_TEXT 动作缺少 text（这是接线 bug，不该发生）。")

        val point = resolveTapPoint(action)
            ?: return ActionResult.TargetNotFound(action.targetDescription)

        // ── ① 先点一下，把焦点落到目标上 ─────────────────────────
        val tapGesture = buildGesture(point, point, DEFAULT_TAP_MS)
            ?: return ActionResult.Failed(channel, "定位输入框时的手势参数非法。")
        val tapped = withContext(Dispatchers.Main) { dispatch(service, tapGesture) }
        if (!tapped) {
            return ActionResult.Failed(
                channel,
                "定位输入框时的手势被系统取消（可能有更高优先级的触摸）。可以重试一次。",
            )
        }

        // ── ② 等焦点落定（轮询，不是死等一个固定时长）────────────
        val node = awaitFocusedInput(service, point)
            ?: return ActionResult.Failed(
                channel,
                "点了之后没能把焦点落到那个输入框上（焦点节点不含该坐标，或它不是可编辑控件）。" +
                    "请重新 screen_read 确认那个位置确实是输入框，再试。",
            )

        // ── ③ 写入 ───────────────────────────────────────────────
        val startedAt = System.currentTimeMillis()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val accepted = withContext(Dispatchers.Main) {
            runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }.getOrDefault(false)
        }
        val latency = System.currentTimeMillis() - startedAt

        if (!accepted) {
            return ActionResult.Failed(
                channel,
                "输入框拒绝了写入（`ACTION_SET_TEXT` 返回 false）。" +
                    "有些控件只认输入法（IME）写入，而本通道走的是无障碍直写 —— " +
                    "这类控件当前**写不进去**，请如实告诉用户。",
            )
        }

        // ── ④ 不做读回校验（理由见下，这是**真机实测**的结论）────────
        //
        // ★ 曾经做过读回比对，**已移除**：K60 真机实测两次都报"读回 0 个字符"，
        //   而 `uiautomator dump` 显示文字**明明已经在框里**（`text='端到端验证：中文输入'`）。
        //
        //   成因：写入后输入法弹出，`rootInActiveWindow` 变成了**输入法窗口**，
        //   于是 `findFocus(FOCUS_INPUT)` 拿到的是 IME 的节点（没有文本）。
        //   试过 `node.refresh()`（陈旧）与"重新查找"（拿到 IME 窗口），**两条都不行**。
        //
        // ⇒ 保留一个**假阴性**的校验比不校验更糟：模型会据此重试，**把文字写两遍**。
        //   所以这里回到唯一可靠的信号：**`performAction` 的返回值**。
        //   它返回 false = 控件明确拒绝（上面已如实报错）；
        //   返回 true = 系统接受了这次写入（真机实测与落盘结果一致）。
        //
        // ⚠️ 代价：若某个控件"返回 true 但没写进去"，这里看不出来。
        //    ⇒ 工具层的成功文案里**必须**提示模型用 screen_read 复核 —— 那是它自己的判据。
        return ActionResult.Dispatched(channel, latency)
    }

    /**
     * 等焦点落到 [point] 所在的输入框上。
     *
     * ⚠️ 用**轮询**而不是"固定等 200ms"：焦点落定时间取决于应用与设备，
     *    固定时长在慢机器上会误判成"点歪了"（而那是**假阴性**，
     *    后果是拒绝一次本来能成的输入）。
     *
     * ⚠️ 判据是**焦点节点的 bounds 包含 [point]**，不是"有没有焦点"：
     *    后者在页面上已经有别的输入框持有焦点时会**误判成成功**，
     *    然后把文字写进那个框里 —— 那是最坏的一种失败（写对了格式，写错了地方）。
     */
    private suspend fun awaitFocusedInput(
        service: AgentAccessibilityService,
        point: Pair<Int, Int>,
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + FOCUS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val node = withContext(Dispatchers.Main) { focusedEditableAt(service, point) }
            if (node != null) return node
            delay(FOCUS_POLL_MS)
        }
        return null
    }

    /** 当前持有输入焦点的可编辑节点，且它的 bounds 必须包含 [point]。 */
    private fun focusedEditableAt(
        service: AgentAccessibilityService,
        point: Pair<Int, Int>,
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!focused.isEditable) return null

        val bounds = Rect()
        focused.getBoundsInScreen(bounds)
        if (!bounds.contains(point.first, point.second)) return null
        return focused
    }

    /**
     * 全局导航键（返回 / 回桌面 / 最近任务）。
     *
     * ⚠️ 用 `performGlobalAction` 而不是手势：这三个键**没有屏幕坐标**，
     *    用"从底部上滑"之类的拟人手势去模拟会在不同厂商 ROM 上表现不一致
     *    （手势导航 vs 三键导航），而 `performGlobalAction` 是系统级语义，稳定。
     *
     * ⚠️ 返回值只有"系统接不接受"—— 它**不代表页面真的变了**。
     *    调用方（工具层）必须提示模型用 `screen_read` 复核。
     */
    private suspend fun performGlobal(
        service: AgentAccessibilityService,
        action: UiAction,
    ): ActionResult {
        val globalAction = when (action.type) {
            ActionType.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            ActionType.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            ActionType.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return ActionResult.Unsupported(channel)
        }

        val startedAt = System.currentTimeMillis()
        val accepted = withContext(Dispatchers.Main) {
            runCatching { service.performGlobalAction(globalAction) }.getOrDefault(false)
        }
        val latency = System.currentTimeMillis() - startedAt

        return if (accepted) {
            ActionResult.Dispatched(channel, latency)
        } else {
            ActionResult.Failed(
                channel,
                "系统拒绝了这次导航键（${action.type}）。可能是服务刚被断开 —— 可以重试一次。",
            )
        }
    }

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

        /** 等焦点落定的上限。超了就报"点歪了"（见 awaitFocusedInput）。 */
        const val FOCUS_WAIT_MS = 600L

        /** 轮询间隔。小到不浪费，大到不把主线程刷爆。 */
        const val FOCUS_POLL_MS = 40L
    }
}
