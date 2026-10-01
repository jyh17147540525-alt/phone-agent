package com.pocketagent.perception

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * `:perception` 的真实实现 —— 无障碍树 → [ScreenSnapshot]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它最重要的设计决定：**如实报告"读不到"，而不是返回空树**
 * ═══════════════════════════════════════════════════════════════
 *
 * 真机实测（2026-10-01）三种界面：
 *
 * | 界面 | 节点数 |
 * |---|---|
 * | 桌面（miui home） | 123 |
 * | 本项目 App（Compose） | 30 |
 * | **微信朋友圈** | **2**（只有一个空 FrameLayout + 状态栏背景） |
 *
 * ⇒ "树可用性"是**因界面而异**的，不是一个全局的布尔值。
 *    微信朋友圈这种界面必须走截图兜底 —— 这不是理论需要，是实测结论。
 *
 * 所以本类**绝不**把"读不到"包装成"界面是空的"：
 * 前者要触发降级（补截图），后者会让决策层得出"这个页面没有可操作元素"
 * 并放弃任务 —— 而用户看到的是"助理说这个页面什么都没有"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  线程
 * ═══════════════════════════════════════════════════════════════
 *
 * [capture] 在 **Main** 上执行：`AccessibilityNodeInfo` 的读取必须发生在
 * 主线程（跨线程访问的行为是未定义的，且不会抛异常）。
 * 遍历本身很轻（实测 123 个节点），不值得为它切线程 ——
 * 而切线程会引入"读到的树正在变化"的新问题。
 */
class AccessibilityPerceptionManager(
    private val source: A11ySource,
    /** 注入时钟，便于测试"超时"这条分支而不真的等 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PerceptionManager {

    override suspend fun capture(options: CaptureOptions): SnapshotResult = withContext(Dispatchers.Main) {
        val started = clock()

        val root = source.rootNode()
        if (root == null) {
            // ⚠️ null 无法区分"没权限"和"这个窗口不提供节点"（Android API 的限制）。
            //    用 availableCapabilities 再探一次：能力不可用 ⇒ 权限问题；
            //    能力可用却读不到 ⇒ 是**这个窗口**的问题，属于正常业务状态。
            return@withContext if (availableCapabilities().accessibilityAvailable) {
                SnapshotResult.Failed("读不到当前窗口的节点（该界面可能不提供无障碍信息）")
            } else {
                SnapshotResult.PermissionMissing(listOf(SnapshotResult.RequiredPermission.ACCESSIBILITY))
            }
        }

        val packageName = root.packageName?.toString()
        val nodes: List<UiNode>? = try {
            traverse(root, options)
        } finally {
            // ★ 必须 recycle：它不是普通对象，持有它会阻止整棵树的 GC
            root.recycle()
        }

        val elapsed = clock() - started
        val treeUsable = !nodes.isNullOrEmpty()

        // ── 降级判定：树不可用时补截图 ──────────────────────────
        val needScreenshot = options.forceScreenshot || !treeUsable
        val snapshotSource = when {
            treeUsable && needScreenshot -> SnapshotSource.HYBRID
            treeUsable -> SnapshotSource.ACCESSIBILITY
            else -> SnapshotSource.VISION
        }

        if (!needScreenshot) {
            return@withContext SnapshotResult.Success(
                buildSnapshot(
                    started = started,
                    elapsed = elapsed,
                    packageName = packageName,
                    nodes = nodes,
                    screenshot = null,
                    source = snapshotSource,
                    confidence = confidenceFor(treeUsable, hasScreenshot = false),
                ),
            )
        }

        // 截图是异步的 —— 这里等它一次。失败**不**让整个采集失败：
        // 树虽然不可用，但"截图也失败"与"这个界面什么都没有"是两件事，
        // 前者应当如实报告为 Failed，让上层引导用户去开权限。
        val shot = awaitScreenshot()
        val bitmap = shot.getOrNull()
        if (!treeUsable && bitmap == null) {
            return@withContext SnapshotResult.Failed(
                "无障碍树不可用，且截图失败：${shot.exceptionOrNull()?.message ?: "未知原因"}",
            )
        }

        SnapshotResult.Success(
            buildSnapshot(
                started = started,
                elapsed = clock() - started,
                packageName = packageName,
                nodes = nodes,
                screenshot = bitmap,
                source = snapshotSource,
                confidence = confidenceFor(treeUsable, hasScreenshot = bitmap != null),
            ),
        )
    }

    override fun availableCapabilities(): PerceptionCapabilities {
        // ⚠️ 用 foregroundPackage 而不是 rootNode 来探测：后者会返回一个
        //    需要 recycle 的重对象，用它做"能不能用"的探测会泄漏。
        val a11y = source.foregroundPackage() != null
        return PerceptionCapabilities(
            accessibilityAvailable = a11y,
            // 两者都未实现 —— 如实报 false，不假装可用。
            // 假装可用的代价是上层会选一个走不通的降级路径。
            mediaProjectionAvailable = false,
            ocrAvailable = false,
            recommendedSource = if (a11y) SnapshotSource.ACCESSIBILITY else SnapshotSource.VISION,
        )
    }

    override suspend fun awaitScreenChange(timeoutMs: Long): Boolean {
        val before = treeSignature()
        val deadline = clock() + timeoutMs
        while (clock() < deadline) {
            delay(POLL_INTERVAL_MS)
            if (treeSignature() != before) return true
        }
        return false
    }

    // ── 内部 ────────────────────────────────────────────────────

    private fun buildSnapshot(
        started: Long,
        elapsed: Long,
        packageName: String?,
        nodes: List<UiNode>?,
        screenshot: android.graphics.Bitmap?,
        source: SnapshotSource,
        confidence: Float,
    ): ScreenSnapshot {
        // 屏幕尺寸优先取截图的（它是权威的），没有截图时用节点 bounds 的并集
        val width: Int
        val height: Int
        if (screenshot != null) {
            width = screenshot.width
            height = screenshot.height
        } else {
            val union = Rect()
            nodes.orEmpty().forEach { union.union(it.bounds) }
            width = union.right
            height = union.bottom
        }

        return ScreenSnapshot(
            timestamp = started,
            packageName = packageName ?: "",
            activityName = null, // 需要 ActivityManager 才能拿到，不在本层做
            screenWidth = width,
            screenHeight = height,
            orientation = if (height >= width) {
                ScreenSnapshot.Orientation.PORTRAIT
            } else {
                ScreenSnapshot.Orientation.LANDSCAPE
            },
            nodes = nodes,
            screenshot = screenshot,
            ocrBlocks = null, // OCR 未实现
            source = source,
            confidence = confidence,
            captureDurationMs = elapsed,
        )
    }

    /**
     * 置信度。**刻意不用 1.0** —— 即使树完全可用，它也只是一棵树：
     * 它不含视觉信息（图标、图片按钮、自绘控件的文字），
     * 而"只有文字没有视觉"的界面里，模型很可能认错。
     *
     * 给 1.0 会让上层以为"这次采集是完美的"，从而跳过补采。
     */
    private fun confidenceFor(treeUsable: Boolean, hasScreenshot: Boolean): Float = when {
        treeUsable && hasScreenshot -> 0.9f
        treeUsable -> 0.7f
        hasScreenshot -> 0.4f
        else -> 0.0f
    }

    /**
     * 遍历整棵树，产出**扁平**的 [UiNode] 列表。
     *
     * ⚠️ 输出是扁平的，但每个节点带 [UiNode.depth] 与 `nodeId` 里的路径 ——
     *    层级信息没有丢，只是不建树。理由：决策层真正要的是
     *    "有哪些可点的东西、它们在哪"，而嵌套结构会让序列化与提示词组装
     *    都变复杂，收益却只是"看起来更像原结构"。
     *
     * @return 节点列表；**空列表表示树读到了但没有内容**（如微信朋友圈），
     *   这与"读不到"（root 为 null）是**两件事**。
     */
    private fun traverse(root: AccessibilityNodeInfo, options: CaptureOptions): List<UiNode> {
        val out = ArrayList<UiNode>()
        val deadline = clock() + options.timeoutMs

        // 显式栈而不是递归：无障碍树在复杂界面上能到 30+ 层，
        // 递归有栈溢出风险，而溢出的表现是崩溃 —— 会被误读成"无障碍不可用"。
        // 每一项是 (节点, 深度, 路径)，路径用于生成稳定的 nodeId。
        val stack = ArrayDeque<Triple<AccessibilityNodeInfo, Int, String>>()
        stack.addLast(Triple(root, 0, "0"))

        while (stack.isNotEmpty()) {
            // ★ 超时保护：复杂界面遍历可能耗时数百毫秒。
            //   超时后返回**已有部分**而不是失败 —— 部分信息仍可用于决策，
            //   而"什么都没有"会让整个任务失败。
            if (clock() > deadline) break

            val (node, depth, path) = stack.removeLast()

            if (options.includeInvisible || node.isVisibleToUser) {
                toUiNode(node, depth, path)?.let { out.add(it) }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                stack.addLast(Triple(child, depth + 1, "$path/$i"))
            }
        }
        return out
    }

    private fun toUiNode(node: AccessibilityNodeInfo, depth: Int, path: String): UiNode? {
        val rect = Rect()
        // ⚠️ getBoundsInScreen 返回 **void**（out 参数风格），不是 Boolean。
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return null // 无位置的节点无法被点击，对决策无价值

        val viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() }

        return UiNode(
            // ★ 优先 viewId（跨快照稳定），退化到路径（界面一变就失效）。
            //   调用方**必须**在动作执行前用 bounds 二次校验 —— 这一点
            //   在 ElementRef 的注释里已经写明。
            nodeId = viewId ?: "path:$path",
            className = node.className?.toString() ?: "",
            text = node.text?.toString()?.takeIf { it.isNotBlank() },
            contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            hintText = null, // AccessibilityNodeInfo 不直接暴露 hintText
            bounds = rect,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            focused = node.isFocused,
            visible = node.isVisibleToUser,
            selected = node.isSelected,
            viewIdResourceName = viewId,
            depth = depth,
            // 扁平输出 ⇒ 每个节点没有子节点。层级靠 depth + nodeId 里的路径保留。
            children = emptyList(),
        )
    }

    private fun treeSignature(): String {
        val root = source.rootNode() ?: return "null"
        return try {
            val sb = StringBuilder()
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val n = stack.removeLast()
                val r = Rect()
                n.getBoundsInScreen(r)
                sb.append(n.viewIdResourceName ?: "").append('|')
                    .append(n.text ?: "").append('|')
                    .append(r.toShortString()).append('\n')
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
            }
            // ⚠️ 用**内容**而不是"节点数"：点击导致的界面变化经常节点数不变
            //    （同一批容器里换了文字），只比数量会得到"没变化"的假阴性。
            "${sb.toString().hashCode()}#${sb.length}"
        } finally {
            root.recycle()
        }
    }

    private suspend fun awaitScreenshot(): Result<android.graphics.Bitmap> =
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                // ⚠️ 是 `resume` 不是 `resumeWith`。
                //    本函数的返回类型本身就是 `Result<Bitmap>`，而
                //    `resumeWith` 收的是 `Result<T>` —— T 已经是 Result 了，
                //    再传一个 Result 就变成 `Result<Result<Bitmap>>`，编译不过。
                source.screenshot { result -> cont.resume(result) }
            }
        }

    private companion object {
        /** 等待界面变化的轮询间隔。100ms 是"够快"与"不耗电"的折中。 */
        const val POLL_INTERVAL_MS = 100L
    }
}
