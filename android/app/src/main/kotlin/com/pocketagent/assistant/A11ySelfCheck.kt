package com.pocketagent.assistant

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * 无障碍通道**自检** —— 只读，**不操作屏幕**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它回答的三个问题
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **树读得到吗？** 不只是"节点数 > 0"，而是"读到的节点能不能用来定位控件"
 *    —— 所以统计的是 `有文本 / 有 viewId / 可点击 / 有 bounds` 四个维度。
 *    一个 500 个节点但全是无属性容器层的树，对自动化是**没用的**，
 *    而"节点数 500"会让人以为一切正常。
 *
 * 2. **截图拿得到吗？** `capabilities` 里有截图位（128）只说明**声明**了，
 *    不代表 `takeScreenshot()` 真的会回调成功。两者之间隔着运行时校验。
 *
 * 3. **当前前台是谁？** 用于确认自检跑在了预期的界面上 ——
 *    否则可能测的是桌面，而结论会被当成"所有 App 都行"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么自检里**没有**手势
 * ═══════════════════════════════════════════════════════════════
 *
 * `dispatchGesture` 会真的点屏幕。自检是**自动触发**的（App 启动时跑一次），
 * 而自动触发的点击意味着"用户打开 App 时可能被莫名其妙点一下别的应用" ——
 * 那不只是体验问题，它是**不可接受的**：用户没有任何机会拒绝。
 *
 * ⇒ 手势验证必须由**用户主动触发**（界面上点一个明确的按钮），
 *   见 `:action` 落地时的自检入口。这条区分不是洁癖，是"自动化能力
 *   不该在用户没预期的时候自己动手"。
 */
object A11ySelfCheck {

    /** 跑一次完整自检。结果全部写日志（`adb logcat -s PocketAgent`）。 */
    fun run() {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            Timber.w("自检跳过：无障碍服务未连接（instance = null）")
            return
        }
        checkTree(service)
        checkScreenshot(service)
    }

    // ── ① 树 ────────────────────────────────────────────────────

    private fun checkTree(service: AgentAccessibilityService) {
        val root = service.rootNode()
        if (root == null) {
            // ⚠️ null 只能当作"这次读不到"，**不能**当作"界面是空的"。
            //    前者该走截图兜底，后者该报告"没有可操作元素"。
            Timber.w("自检·树：rootInActiveWindow = null（服务没连上 / 窗口不提供节点 / 主线程被阻塞）")
            return
        }

        var total = 0
        var withText = 0
        var withViewId = 0
        var withDesc = 0
        var clickable = 0
        var withBounds = 0
        var maxDepth = 0

        try {
            // 显式栈而不是递归：无障碍树的深度在复杂界面上能到 30+ 层，
            // 递归有栈溢出风险，而溢出的表现是崩溃 —— 在自检里崩溃会
            // 被误读成"无障碍不可用"。
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)

            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeLast()
                total++
                if (depth > maxDepth) maxDepth = depth

                if (!node.text.isNullOrBlank()) withText++
                if (!node.viewIdResourceName.isNullOrBlank()) withViewId++
                if (!node.contentDescription.isNullOrBlank()) withDesc++
                if (node.isClickable) clickable++

                // ⚠️ getBoundsInScreen 返回 **void**（out 参数风格，填充传入的 Rect），
                //    不是 Boolean。写成 `if (node.getBoundsInScreen(rect) && ...)`
                //    会得到 "inferred type is Unit but Boolean was expected" ——
                //    而错误信息指向的是整个条件，不会告诉你"这个 API 没有返回值"。
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) withBounds++

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it to depth + 1) }
                }
            }
        } finally {
            // 根节点的 recycle 会连带释放子树（API 33 起是 no-op，但低版本必需）
            root.recycle()
        }

        val pkg = service.foregroundPackage() ?: "(取不到)"
        Timber.i(
            "自检·树：节点=%d 有文本=%d 有viewId=%d 有desc=%d 可点击=%d 有bounds=%d 最大深度=%d 前台=%s"
                .format(total, withText, withViewId, withDesc, clickable, withBounds, maxDepth, pkg),
        )

        // ★ 判据不是"节点数 > 0"，是"**有定位信息的节点数** > 0"。
        //   一个全是无属性容器层的树，节点数再多也无法用来点击任何东西。
        if (total > 0 && withBounds == 0) {
            Timber.w("自检·树：有节点但**没有任何 bounds** —— 这样的树无法用于定位，等同于不可用")
        }
        if (total > 0 && withText == 0 && withDesc == 0 && withViewId == 0) {
            Timber.w("自检·树：有节点但**无文本、无描述、无 viewId** —— 只能靠坐标，属于最差情况")
        }
    }

    // ── ② 截图 ──────────────────────────────────────────────────

    private fun checkScreenshot(service: AgentAccessibilityService) {
        service.takeScreenshotCompat { result ->
            result.fold(
                onSuccess = { bitmap ->
                    Timber.i("自检·截图：成功 ${bitmap.width}x${bitmap.height}")
                    // 自检只需要尺寸，立刻释放 —— 截图是最高敏感度的数据，
                    // 不允许在内存里多待一秒
                    bitmap.recycle()
                },
                onFailure = { error ->
                    Timber.w("自检·截图：失败 —— ${error.message}")
                },
            )
        }
    }
}
