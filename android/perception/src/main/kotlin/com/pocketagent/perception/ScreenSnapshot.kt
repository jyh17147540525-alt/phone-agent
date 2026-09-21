package com.pocketagent.perception

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * 屏幕的统一表示。
 *
 * ⚠️ 这是感知层的**唯一出口**。决策层只认识 ScreenSnapshot，不认识 AccessibilityNodeInfo
 *    也不认识 Bitmap。这样做的目的：
 *    1. Android 17+ 收紧无障碍后，只需替换 [ScreenSnapshot] 的生产者，决策层零改动
 *    2. 便于缓存与回放（测试时可以把真实快照序列化成 fixture）
 *    3. 明确标注数据来源，便于统计各来源的成功率与成本
 */
data class ScreenSnapshot(
    val timestamp: Long,
    /** 当前前台应用包名 */
    val packageName: String,
    /** 当前 Activity 名（可能为 null，如游戏/Flutter 应用） */
    val activityName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    /** 屏幕方向 */
    val orientation: Orientation,

    /** 无障碍节点树。为 null 表示该来源不可用（权限未授予 / 节点数为 0） */
    val nodes: List<UiNode>?,

    /** 截图。仅在需要时才采集，用完必须 recycle（见 [dispose]） */
    val screenshot: Bitmap?,

    /** OCR 结果。用于截图兜底路径下补充文本信息 */
    val ocrBlocks: List<OcrBlock>?,

    /** 数据来源，决定决策层的置信度与降级策略 */
    val source: SnapshotSource,

    /** 整体置信度 0~1。低置信度时应触发补充采集（如补一张截图） */
    val confidence: Float,

    /** 该快照的采集耗时，用于性能监控 */
    val captureDurationMs: Long,
) {
    enum class Orientation { PORTRAIT, LANDSCAPE, UNDEFINED }

    /**
     * 是否具备结构化信息（树或 OCR 至少有一个可用）。
     * 决策层可据此选择「文本化元素列表」还是「纯视觉」的提示词策略。
     */
    val hasStructuredInfo: Boolean
        get() = !nodes.isNullOrEmpty() || !ocrBlocks.isNullOrEmpty()

    /** 扁平化的可交互元素列表 —— 送给 Grounder 的精简输入 */
    fun interactiveElements(): List<UiNode> = nodes.orEmpty().flatMap { it.flatten() }
        .filter { it.clickable || it.editable || it.scrollable || it.longClickable }

    /**
     * 释放资源。**任何持有 Snapshot 的代码都必须在 finally 中调用**。
     * 截图是最高敏感度的数据，不允许在内存中长期驻留。
     */
    fun dispose() {
        screenshot?.takeIf { !it.isRecycled }?.recycle()
    }
}

/** 采集来源 */
enum class SnapshotSource {
    /** 纯无障碍树 */
    ACCESSIBILITY,
    /** 纯截图（+OCR） */
    VISION,
    /** 树 + 截图混合 */
    HYBRID,
    /** 仅 OCR（无障碍与截图都不可用时的兜底） */
    OCR_ONLY,
}

/**
 * 无障碍节点树的归一化表示。
 *
 * 刻意与 Android 的 AccessibilityNodeInfo 解耦：
 * - AccessibilityNodeInfo 是重对象，持有它会阻止 GC，且跨线程访问不安全
 * - 我们只抽取决策需要的字段，采集完立即 recycle 原生节点
 */
data class UiNode(
    /** 稳定标识。优先用 viewIdResourceName，退化为 路径哈希。用于动作执行与图谱缓存 */
    val nodeId: String,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    /** 在屏幕上的绝对坐标 */
    val bounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val visible: Boolean,
    val selected: Boolean,
    /** viewIdResourceName，如 "com.tencent.mm:id/搜索框"。可能为 null（自绘 UI） */
    val viewIdResourceName: String?,
    /** 在树中的层级，用于判断父子关系 */
    val depth: Int,
    val children: List<UiNode> = emptyList(),
) {
    /** 深度优先展平 */
    fun flatten(): List<UiNode> = buildList {
        add(this@UiNode)
        children.forEach { addAll(it.flatten()) }
    }

    /** 供 LLM 消费的简洁描述，避免把整个节点结构塞进提示词 */
    fun describe(): String = buildString {
        append(className.substringAfterLast('.')).append(" [").append(nodeId.takeLast(12)).append("]")
        text?.takeIf { it.isNotBlank() }?.let { append(" text=\"").append(it.take(60)).append('"') }
        contentDescription?.takeIf { it.isNotBlank() }?.let { append(" desc=\"").append(it.take(60)).append('"') }
        if (editable) append(" [可输入]")
        if (scrollable) append(" [可滚动]")
        append(" @(").append(bounds.centerX()).append(',').append(bounds.centerY()).append(')')
    }
}

/** OCR 文本块 */
data class OcrBlock(
    val text: String,
    val bounds: Rect,
    val confidence: Float,
)

/**
 * 感知层统一入口。
 *
 * 采集策略（降级链）：
 * ```
 * 无障碍树可用？
 *   ├─ 是 → 覆盖度足够？
 *   │        ├─ 是 → ACCESSIBILITY（成本最低）
 *   │        └─ 否 → 补截图 → HYBRID
 *   └─ 否 → 截图 + OCR → VISION / OCR_ONLY
 * ```
 *
 * 实现要求：
 * - **必须支持超时**：无障碍树的遍历在复杂界面上可能耗时数百毫秒，
 *   必须设上限（建议 300ms）并在超时后返回已有部分
 * - **必须可取消**：用户中止任务时立即停止采集
 * - 截图必须走前台服务，且每次会话需用户确认（Android 14+）
 */
interface PerceptionManager {

    /** 采集一次完整快照 */
    suspend fun capture(options: CaptureOptions = CaptureOptions()): SnapshotResult

    /** 当前可用的采集能力，用于 UI 上的权限引导与降级提示 */
    fun availableCapabilities(): PerceptionCapabilities

    /** 观察屏幕变化（用于等待页面加载完成） */
    suspend fun awaitScreenChange(timeoutMs: Long = 3_000): Boolean
}

/** 采集参数 */
data class CaptureOptions(
    /** 是否强制截图（即使树可用）。用于「截图问答」这类明确需要视觉的场景 */
    val forceScreenshot: Boolean = false,
    /** 是否启用 OCR */
    val enableOcr: Boolean = false,
    /** 截图最大边长，用于控制 token 成本。默认 1280，超过会被等比缩放 */
    val maxScreenshotEdge: Int = 1280,
    /** 截图压缩质量（WebP），默认 75 */
    val screenshotQuality: Int = 75,
    /** 采集超时 */
    val timeoutMs: Long = 500,
    /** 是否包含不可见节点 */
    val includeInvisible: Boolean = false,
)

/** 采集结果 */
sealed interface SnapshotResult {
    data class Success(val snapshot: ScreenSnapshot) : SnapshotResult
    /** 权限缺失，附带需要引导用户开启的权限类型 */
    data class PermissionMissing(val required: List<RequiredPermission>) : SnapshotResult
    /** 采集失败 */
    data class Failed(val reason: String) : SnapshotResult
    /** 命中敏感页面，主动拒绝采集 */
    data object BlockedBySafety : SnapshotResult

    enum class RequiredPermission { ACCESSIBILITY, MEDIA_PROJECTION, OVERLAY }
}

/** 当前可用的感知能力 */
data class PerceptionCapabilities(
    val accessibilityAvailable: Boolean,
    val mediaProjectionAvailable: Boolean,
    val ocrAvailable: Boolean,
    /** 推荐使用的采集模式，用于 UI 提示用户当前处于哪种体验档位 */
    val recommendedSource: SnapshotSource,
)
