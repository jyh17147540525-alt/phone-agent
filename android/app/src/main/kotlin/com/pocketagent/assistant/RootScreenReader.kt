package com.pocketagent.assistant

import com.pocketagent.mcp.CaptureSource
import com.pocketagent.mcp.ScreenCaptureDto
import com.pocketagent.mcp.ScreenCaptureOutcome
import com.pocketagent.mcp.ScreenReaderPort
import com.pocketagent.mcp.ScreenRect
import com.pocketagent.mcp.UiNodeDto
import com.pocketagent.rootlogic.PrivilegeMode
import com.pocketagent.rootlogic.RootAction
import com.pocketagent.rootlogic.RootActions
import com.pocketagent.rootlogic.UiautomatorDump
import timber.log.Timber

/**
 * **root 档的读屏** —— 不用无障碍，靠 `su -c uiautomator dump` 拿界面。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它解决的是"依赖谁"，不是"准不准"
 * ═══════════════════════════════════════════════════════════════
 *
 * 无障碍那条路：用户必须去设置里开启服务，服务被杀/被系统收回时什么都读不到。
 * root 这条路：只要 `su` 通就能读 —— 用户把无障碍关掉（省电/隐私）也照样工作。
 *
 * ⇒ 这就是"满血"的实际含义之一。它不是更聪明的读屏，
 *   而是**少一个必须由用户去开的开关**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三次调用、每次都可能是"环境问题"
 * ═══════════════════════════════════════════════════════════════
 *
 * ① `uiautomator dump <path>` —— 失败多半是 su 被拒 / uiautomator 被系统限制
 * ② `cat <path>`                —— 失败说明 dump 没落盘（或者路径权限不对）
 * ③ 解析 XML                   —— 失败说明输出不是我们以为的东西
 *
 * 三种失败给用户的下一步**完全不同**，所以分开映射（见 [capture] 里的注释）。
 */
class RootScreenReader(
    private val actions: RootActions,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val dumpPath: String = DEFAULT_DUMP_PATH,
) : ScreenReaderPort {

    override suspend fun capture(): ScreenCaptureOutcome {
        // ── ① dump ────────────────────────────────────────────────
        val dump = actions.run(RootAction.DumpTree(dumpPath))
        if (!dump.ok) {
            // ★ 这里**不能**说"界面读不到"：命令本身没跑起来。
            //   给用户的动作是"去 Magisk 里看一眼授权"，不是"重试"。
            return ScreenCaptureOutcome.Unavailable(
                "root 直读不可用：`uiautomator dump` 没有跑起来（${dump.describe()}）。" +
                    "常见原因是 Magisk 里没给本应用 root 授权，或 SELinux 拦了这条命令。",
            )
        }

        // ── ② 取回 XML ───────────────────────────────────────────
        val raw = actions.run(RootAction.ReadFile(dumpPath))
        if (!raw.ok) {
            return ScreenCaptureOutcome.Failed(
                "dump 生成了但读不回来（${raw.describe()}）—— 这通常意味着 dump 没有真的落盘，可以重试一次。",
            )
        }

        // ── ③ 解析 ───────────────────────────────────────────────
        val tree = UiautomatorDump.parse(raw.stdout).getOrElse { error ->
            val reason = (error as? UiautomatorDump.DumpException)?.reason
            return when (reason) {
                // 空树 ≠ 界面空：锁屏 / DRM / 正在切换窗口都可能这样。
                // 说"没有可操作元素"是**断言了一件我们并不知道的事**。
                UiautomatorDump.Failure.Empty -> ScreenCaptureOutcome.Failed(
                    "dump 里没有任何节点 —— 可能是锁屏、DRM 界面，或窗口正在切换。可以稍后重试。",
                )

                is UiautomatorDump.Failure.NotADump -> ScreenCaptureOutcome.Failed(
                    "dump 的输出不是预期格式（开头是「${reason.preview.take(60)}」）—— 可以重试；" +
                        "反复这样请把这条原文报给维护者。",
                )

                is UiautomatorDump.Failure.Malformed -> ScreenCaptureOutcome.Failed(
                    "dump 的 XML 解析失败（${reason.reason}）—— dump 可能被截断，可以重试。",
                )

                null -> ScreenCaptureOutcome.Failed("dump 解析失败：${error.message}")
            }
        }

        Timber.i(
            "root 直读：%d 个节点（%s，可点 %d）",
            tree.nodes.size, tree.packageName.ifEmpty { "未知包名" }, tree.tappable.size,
        )

        return ScreenCaptureOutcome.Ok(tree.toDto(clock()))
    }

    private fun UiautomatorDump.Tree.toDto(now: Long) = ScreenCaptureDto(
        packageName = packageName,
        // ⚠️ dump 里没有 Activity 名，编一个会污染"当前在哪个页面"的判断 —— 如实给 null。
        activityName = null,
        width = width,
        height = height,
        source = CaptureSource.ROOT_DUMP,
        // 与无障碍树同一个口径：树不含视觉信息，不给满分（否则模型会以为采集完美）
        confidence = ROOT_TREE_CONFIDENCE,
        capturedAtMs = now,
        nodes = nodes.map { it.toDto() },
    )

    private fun UiautomatorDump.DumpNode.toDto() = UiNodeDto(
        nodeId = nodeId,
        className = className,
        text = text,
        contentDescription = contentDescription,
        // dump 不提供 hint（`hintText` 在无障碍侧来自 `hintText` API）
        hintText = null,
        bounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
        clickable = clickable,
        longClickable = longClickable,
        // ★ dump 没有 `editable` 字段：按类名推断。
        //   宁可漏判一个（上层还有 clickable/focused 兜底），也不要凭空造。
        editable = className.contains("EditText", ignoreCase = true),
        scrollable = scrollable,
        checkable = checkable,
        checked = checked,
        enabled = enabled,
        focused = focused,
        // dump 没有 visible：零面积的节点视为不可见 —— 它们点不中
        visible = !bounds.isEmpty,
        selected = selected,
        viewIdResourceName = resourceId,
        depth = depth,
        password = password,
    )

    companion object {
        /**
         * dump 落盘位置。
         *
         * ★ 用 `/data/local/tmp`：它是 `adb shell` 与 root 都可写的世界可读目录，
         *   而应用私有目录**root 反而更绕**（要处理 SELinux 标签）。
         */
        const val DEFAULT_DUMP_PATH = "/data/local/tmp/pocketagent-ui.xml"

        /** 树路径的采集置信度 —— 与 `PerceptionScreenReader` 保持同一个数。 */
        const val ROOT_TREE_CONFIDENCE = 0.7f
    }
}

/**
 * **两档读屏的汇合点** —— 与 `TieredDispatchPort` 同构，但退让规则**故意不同**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么读屏可以退让，点按不可以
 * ═══════════════════════════════════════════════════════════════
 *
 * 点按退回无障碍 = **占住用户的屏幕**（见 `TieredDispatchPort` 的注释），
 * 那是用户明确不想要的东西，所以那条路失败就失败。
 *
 * 读屏退回无障碍 = 只多一次 `rootInActiveWindow` 调用，**不碰用户的界面**。
 * 而 root 档用户完全可能把 su 撤了（换机、Magisk 更新、安全软件清了授权），
 * 此时"读屏直接不可用"是纯粹的退化 —— 明明有另一条能用的路。
 *
 * ⇒ 规则：**root 侧就绪才走 root，否则走普通**；两条路都断才如实报错。
 */
class TieredScreenReader(
    private val plain: ScreenReaderPort,
    private val root: ScreenReaderPort,
    private val mode: () -> PrivilegeMode,
    private val rootReady: () -> Boolean,
) : ScreenReaderPort {

    override suspend fun capture(): ScreenCaptureOutcome =
        if (mode() == PrivilegeMode.ROOT && rootReady()) root.capture() else plain.capture()
}
