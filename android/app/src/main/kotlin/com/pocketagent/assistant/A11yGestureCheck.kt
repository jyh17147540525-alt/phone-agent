package com.pocketagent.assistant

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * 手势通道自检 —— **会真的点屏幕**，所以**只能由用户主动触发**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它验证的是**整条链路**，不是一个 API
 * ═══════════════════════════════════════════════════════════════
 *
 * 单看"`dispatchGesture` 返回 true"是没有意义的 —— 那只能证明手势被系统受理，
 * 与"目标被点中了"毫无关系（见 [AgentAccessibilityService.performGesture] 的注释）。
 *
 * 所以本自检做的是**四步闭环**，缺任何一步都不算通过：
 *
 * ```
 * ① 感知：读无障碍树，找到一个"可点击且文字含『设置』"的节点
 * ② 决策：取其 bounds 中心点（这一步是 Grounder 的最小形态）
 * ③ 行动：dispatchGesture 在该点按下并抬起
 * ④ 验证：等界面响应后**重新读树**，确认内容真的变了
 * ```
 *
 * ★ 第 ④ 步是全部价值所在。没有它，一次"点了但没点中"会报告成功 ——
 * 而那种失败在真实任务里表现为"助理说它做了，但什么都没发生"，
 * 是本项目反复要防的那类故障。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么必须有 intent extra 才跑
 * ═══════════════════════════════════════════════════════════════
 *
 * 它会点屏幕。如果做成"App 启动就自动跑"，用户每次打开应用都可能被
 * 莫名其妙点一下别的应用 —— **用户没有任何机会拒绝**。
 * 自动化能力不该在用户没预期的时候自己动手。
 *
 * 触发方式（显式）：
 * ```
 * adb shell am start -n com.pocketagent.debug/com.pocketagent.MainActivity \
 *   --ez a11y_gesture_check true
 * ```
 */
object A11yGestureCheck {

    /** intent extra 的键。与 MainActivity 约定。 */
    const val EXTRA = "a11y_gesture_check"

    /** 目标文字。选"设置"是因为它是底部导航的一项，点了会换页 —— 便于验证。 */
    private const val TARGET_TEXT = "设置"

    /** 点击后等待界面响应的时间。太短会读到还没切换完的界面，造成假阴性。 */
    private const val SETTLE_MS = 1_500L

    fun run() {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            Timber.w("手势自检跳过：无障碍服务未连接")
            return
        }

        // ── ① 感知 ──────────────────────────────────────────────
        val root = service.rootNode()
        if (root == null) {
            Timber.w("手势自检失败：读不到根节点")
            return
        }

        val beforeSignature = signatureOf(root)
        val target = findClickable(root, TARGET_TEXT)
        if (target == null) {
            Timber.w("手势自检失败：在树上找不到可点击的「$TARGET_TEXT」节点（当前界面可能不在预期页）")
            root.recycle()
            return
        }

        val rect = Rect()
        target.getBoundsInScreen(rect)
        root.recycle()

        if (rect.isEmpty) {
            Timber.w("手势自检失败：目标节点 bounds 为空")
            return
        }

        // ── ② 决策：取中心点 ─────────────────────────────────────
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        Timber.i("手势自检：目标「$TARGET_TEXT」bounds=$rect 中心=($cx, $cy)")

        // ── ③ 行动 ──────────────────────────────────────────────
        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            // duration=60ms：太短可能被当成"滑动"（有些 ROM 有最小触摸时长），
            // 太长会让人觉得卡。60ms 接近真人的快速轻点。
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()

        service.performGesture(gesture) { dispatched ->
            Timber.i("手势自检：dispatchGesture 回调 dispatched=$dispatched（**只代表被受理**）")

            // ── ④ 验证：等界面响应后重新读树 ─────────────────────
            Handler(Looper.getMainLooper()).postDelayed({
                verify(service, beforeSignature)
            }, SETTLE_MS)
        }
    }

    /**
     * ④ 验证。**这一步才是自检的意义。**
     *
     * 判据不是"某个元素出现了"，而是"**整棵树的签名变了**" ——
     * 因为点击可能导致的界面变化有太多种（换页、弹层、列表滚动），
     * 逐种去猜会漏。签名变化是最宽也最诚实的判据。
     */
    private fun verify(service: AgentAccessibilityService, before: String) {
        val root = service.rootNode()
        if (root == null) {
            Timber.w("手势自检·验证：读不到根节点，无法确认点击是否生效")
            return
        }
        val after = signatureOf(root)
        root.recycle()

        if (after == before) {
            // ⚠️ 这一条**不能**直接判定"手势失败"：目标也可能本来就是个
            //    不引起界面变化的东西（比如一个已经选中的 tab）。
            //    所以如实报告"界面未变化"，把判断留给调用方。
            Timber.w("手势自检·验证：界面**没有变化**（签名相同）—— 手势可能没点中，或目标本身不引起变化")
        } else {
            Timber.i("手势自检·验证：**界面已变化** ⇒ 整条链路（感知→决策→行动→验证）打通")
        }
        Timber.i("手势自检完成。before=${before.take(80)}…")
        Timber.i("手势自检完成。after =${after.take(80)}…")
    }

    // ── 工具 ────────────────────────────────────────────────────

    /**
     * 树的**签名**：把所有可见节点的 `(viewId, text, bounds)` 拼起来取哈希。
     *
     * ⚠️ 用**内容**而不是"节点数"：点击导致的界面变化经常**节点数不变**
     *    （比如同一批容器里换了文字），只比数量会得到"没变化"的假阴性。
     */
    private fun signatureOf(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val rect = Rect()
            n.getBoundsInScreen(rect)
            sb.append(n.viewIdResourceName ?: "").append('|')
                .append(n.text ?: "").append('|')
                .append(n.contentDescription ?: "").append('|')
                .append(rect.toShortString()).append('\n')
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return sb.toString().hashCode().toString() + "#" + sb.length
    }

    /**
     * 深度优先找一个"文字或描述等于 [text] 且可点击"的节点。
     *
     * ⚠️ 这里**同时**接受 text 与 contentDescription：Compose 的
     *    `Text` 可能落在 contentDescription 上，而传统 View 落在 text 上。
     *    只认一个的话，会在某一类界面上稳定找不到 —— 而那看起来像
     *    "这个功能不支持这种界面"。
     */
    private fun findClickable(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val hit = n.text?.toString() == text || n.contentDescription?.toString() == text
            if (hit && n.isClickable) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }
}
