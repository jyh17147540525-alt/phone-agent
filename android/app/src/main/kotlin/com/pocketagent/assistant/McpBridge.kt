package com.pocketagent.assistant

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import com.pocketagent.mcp.CaptureSource
import com.pocketagent.mcp.InputFieldLite
import com.pocketagent.mcp.McpBridgeSession
import com.pocketagent.mcp.McpBridgeStartResult
import com.pocketagent.perception.A11ySource
import com.pocketagent.perception.AccessibilityPerceptionManager
import com.pocketagent.perception.ScreenSnapshot
import com.pocketagent.perception.SnapshotResult
import com.pocketagent.perception.SnapshotSource
import com.pocketagent.perception.UiNode
import com.pocketagent.mcp.ScreenCaptureDto
import com.pocketagent.mcp.ScreenCaptureOutcome
import com.pocketagent.mcp.ScreenReaderPort
import com.pocketagent.mcp.ScreenRect
import com.pocketagent.mcp.ScreenSafetyOutcome
import com.pocketagent.mcp.ScreenSafetyPort
import com.pocketagent.mcp.ScreenSafetyQuery
import com.pocketagent.mcp.UiNodeDto
import com.pocketagent.safety.InputFieldSignature
import com.pocketagent.safety.SensitiveDetector
import com.pocketagent.safety.SensitiveRules

/**
 * MCP 能力桥在 `:app` 侧的**全部接线**：两根端口的实现 + 主机（生命周期与草稿落盘）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么这些代码住在 `:app` 而不是 `:mcp`
 * ═══════════════════════════════════════════════════════════════
 *
 * `:mcp` 是纯 Kotlin（进离线验证器）——它**依赖不了** `:perception`（Android 模块）
 * 与 `:safety`（Android 库模块），所以桥与真实能力之间用**窄端口**相接：
 *
 * ```
 *   :mcp  ── ScreenReaderPort ──►  本文件（:app）  ── :perception ──► 无障碍服务
 *         ── ScreenSafetyPort ──►  本文件（:app）  ── :safety     ──► SensitiveDetector
 * ```
 *
 * 本文件刻意**没有判定逻辑**：采集映射是机械的字段搬运，"命中后怎么办"
 * 在 `:mcp` 的 `ScreenReadTool` 里（那里有离线测试）。
 * 判定逻辑住在这里的任何一份，都是在制造"迟早会漂移"的副本。
 */

// ═══════════════════════════════════════════════════════════════
//  端口一：读屏（:perception）
// ═══════════════════════════════════════════════════════════════

/**
 * 把 [AgentAccessibilityService]（通道壳）适配成 `:perception` 的 [A11ySource]。
 *
 * ⚠️ 服务未连接时逐调用返回 null / 失败 —— **不要**在这里换成
 *    `UnavailableA11ySource` 的实例级替换：服务可能随时重连，
 *    实例级替换需要监听生命周期，多一层会静默失效的接线。
 *    （逐调用判空就是最诚实的"现在读不到"。）
 */
object AgentA11ySource : A11ySource {

    override fun rootNode(): AccessibilityNodeInfo? =
        AgentAccessibilityService.instance?.rootNode()

    override fun foregroundPackage(): String? =
        AgentAccessibilityService.instance?.foregroundPackage()

    override fun screenshot(onResult: (Result<Bitmap>) -> Unit) {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            onResult(Result.failure(IllegalStateException("无障碍服务未连接，无法截图")))
            return
        }
        service.takeScreenshotCompat(onResult)
    }
}

/**
 * 读屏端口的实现：`:perception` 采集 → `:mcp` 的 DTO。
 *
 * ⚠️ **只走树路径**。截图兜底（VISION/OCR）不在 P2 范围 —— 树不可用时
 *    如实返回失败，**绝不**把它包装成"界面是空的"（那条纪律见
 *    `AccessibilityPerceptionManager` 的类注释）。
 */
class PerceptionScreenReader(
    private val manager: AccessibilityPerceptionManager,
) : ScreenReaderPort {

    override suspend fun capture(): ScreenCaptureOutcome {
        return when (val result = manager.capture()) {
            is SnapshotResult.Success -> {
                val snapshot = result.snapshot
                try {
                    val nodes = snapshot.nodes
                    if (nodes == null) {
                        // 树读不到（游戏 / DRM / 自绘界面）且截图兜底未实现。
                        ScreenCaptureOutcome.Failed(
                            "该界面不提供无障碍信息（可能是游戏或自绘界面），当前版本无法读取。",
                        )
                    } else {
                        ScreenCaptureOutcome.Ok(snapshot.toDto())
                    }
                } finally {
                    // ★ 快照可能持有截图 Bitmap（树不可用时主采过一张）——
                    //   它是最高敏感度的数据，必须在**本函数内**释放，
                    //   一个字节都不许离开（能力桥只发文字结构）。
                    snapshot.dispose()
                }
            }

            is SnapshotResult.PermissionMissing ->
                ScreenCaptureOutcome.Unavailable("无障碍权限未开启")

            is SnapshotResult.Failed -> ScreenCaptureOutcome.Failed(result.reason)

            SnapshotResult.BlockedBySafety ->
                ScreenCaptureOutcome.Failed("安全策略拒绝了本次采集。请把这一情况告知用户。")
        }
    }
}

private fun ScreenSnapshot.toDto(): ScreenCaptureDto = ScreenCaptureDto(
    packageName = packageName,
    activityName = activityName,
    width = screenWidth,
    height = screenHeight,
    source = when (source) {
        SnapshotSource.ACCESSIBILITY -> CaptureSource.ACCESSIBILITY
        SnapshotSource.VISION -> CaptureSource.VISION
        SnapshotSource.HYBRID -> CaptureSource.HYBRID
        SnapshotSource.OCR_ONLY -> CaptureSource.OCR_ONLY
    },
    confidence = confidence,
    capturedAtMs = timestamp,
    nodes = nodes.orEmpty().map { it.toDto() },
)

private fun UiNode.toDto(): UiNodeDto = UiNodeDto(
    nodeId = nodeId,
    className = className,
    text = text,
    contentDescription = contentDescription,
    hintText = hintText,
    bounds = ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom),
    clickable = clickable,
    longClickable = longClickable,
    editable = editable,
    scrollable = scrollable,
    checkable = checkable,
    checked = checked,
    enabled = enabled,
    focused = focused,
    visible = visible,
    selected = selected,
    viewIdResourceName = viewIdResourceName,
    depth = depth,
    password = password,
)

// ═══════════════════════════════════════════════════════════════
//  端口二：敏感判定（:safety）
// ═══════════════════════════════════════════════════════════════

/**
 * 敏感判定端口的实现：映射到 `:safety` 的 [SensitiveDetector]。
 *
 * ⚠️ 判定引擎一个字段都不能少地搬运过去 —— 这里是**纯搬运**，
 *    任何"顺手过滤一下"都会制造一个规则引擎之外的影子规则。
 *    规则本身（以及它"漏判比误判危险"的纪律）全部在 `:safety`。
 */
class SafetyDetectorGate(
    private val rules: SensitiveRules = SensitiveRules.Builtin,
) : ScreenSafetyPort {

    override fun review(query: ScreenSafetyQuery): ScreenSafetyOutcome {
        // 顺序：包名（整个应用禁用）→ 页面文本 → 控件形状。
        // 从最"重"的（整个 App 拉黑）到最细的，与 :safety 自身的分档纪律一致。
        val match = SensitiveDetector.matchPackage(query.packageName, rules)
            ?: SensitiveDetector.matchTexts(query.visibleTexts, rules)
            ?: SensitiveDetector.matchFields(query.fields.map { it.toSignature() }, rules)

        return match
            ?.let { ScreenSafetyOutcome.Block(it.hit.userMessage) }
            ?: ScreenSafetyOutcome.Pass
    }
}

private fun InputFieldLite.toSignature(): InputFieldSignature = InputFieldSignature(
    hint = hint,
    className = className,
    contentDescription = contentDescription,
    isPassword = isPassword,
    isEditable = isEditable,
)

// ═══════════════════════════════════════════════════════════════
//  主机：生命周期与草稿落盘
// ═══════════════════════════════════════════════════════════════

/**
 * 能力桥对外的状态。
 *
 * ⚠️ 与 `DshIntegrationStatus` 同款纪律：`On` 里**没有** token ——
 *    界面状态会被重组、截图、进日志，而 token 是"同机其它 App
 *    读到就完蛋"的东西。用户要抄的是**草稿文件**（里面含 token，
 *    但只落在应用私有目录 + 用户主动取用），不是界面上的值。
 */
sealed interface McpBridgeStatus {

    data object Off : McpBridgeStatus

    data class On(val mcpUrl: String, val draft: McpBridgeDraft) : McpBridgeStatus

    /** 没跑起来，且这一次不会自己好。`reason` 必须点名用户要做的动作。 */
    data class Blocked(val reason: String) : McpBridgeStatus
}

/** 草稿投递的结果（"服务器在跑"与"草稿写没写出去"是两件独立的事）。 */
sealed interface McpBridgeDraft {

    data class Written(val displayPath: String) : McpBridgeDraft

    data class Failed(val reason: String) : McpBridgeDraft
}

/**
 * 能力桥主机：起停 + 草稿落盘。
 *
 * ⚠️ 与 `AppContainer.buildDshSession` 同一条纪律：这里**没有判定逻辑** ——
 *    "起服务 → 渲染草稿"全在 `:mcp` 的 `McpBridgeSession`（离线测试覆盖），
 *    本类只负责把草稿文本写进应用私有目录（IO），以及把结果翻成状态。
 */
class McpBridgeHost(
    private val session: McpBridgeSession,
    /** 写文件端口（实现是 `AppContainer.writeAppPrivateFile` → `AtomicTextFile`）。 */
    private val writeFile: (relativePath: String, content: String) -> Boolean,
    private val draftDisplayPath: String,
    private val log: (String) -> Unit = {},
) {

    fun start(): McpBridgeStatus {
        return when (val result = session.start()) {
            is McpBridgeStartResult.NotStarted -> McpBridgeStatus.Blocked(result.reason)

            is McpBridgeStartResult.Running -> {
                val draft = if (writeFile(DRAFT_REL_PATH, result.patchDraft)) {
                    McpBridgeDraft.Written(draftDisplayPath)
                } else {
                    McpBridgeDraft.Failed(
                        "服务器在跑，但配置草稿没写进应用私有目录（可能是存储空间或权限问题）。" +
                            "关掉再开启一次试试。",
                    )
                }
                // ⚠️ 只记结果类型，不记草稿内容（里面有 token）。
                log("MCP 能力桥已启动，草稿投递：${draft::class.simpleName}")
                McpBridgeStatus.On(mcpUrl = result.mcpUrl, draft = draft)
            }
        }
    }

    fun stop() {
        session.stop()
    }

    companion object {
        /**
         * 草稿文件相对路径（相对 `filesDir`）。
         *
         * ⚠️ 与 `AppPrivateDshConfigSink` 的 `dsh/` 前缀同一个理由：
         *    放在文件管理器可见的位置命名，虽然非 root 设备**打不开**它 ——
         *    所以界面上必须显示全文（见 `DshIntegrationScreen` 的 DraftBlock）。
         */
        const val DRAFT_REL_PATH: String = "mcp/cordis.patch.yml"
    }
}
