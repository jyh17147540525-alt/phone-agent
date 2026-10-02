package com.pocketagent.mcp

import com.pocketagent.agentlogic.RedactRegion

/**
 * 屏幕内容的**纯数据表示**，与两根端口（[ScreenReaderPort] / [ScreenSafetyPort]）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不直接复用 :perception 的 ScreenSnapshot / UiNode
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为 `:perception` 依赖 `android.graphics`（`Bitmap` / `Rect`）——
 * 一旦本模块引它，就再也进不了 `run_logic_tests.py`（本项目最贵的一课：
 * `:memorylogic` 的注释里写着「隐私过滤跑在卸载之后 = 隐私已经落盘」，
 * 那条测试之所以存在，就是因为纯模块能被完整离线覆盖）。
 *
 * 所以这里定义**镜像 DTO**，由 `:app` 薄壳做一次逐字段映射。
 * 映射是机械的、一页纸能读完的代码；而本模块的全部判定逻辑因此可测。
 *
 * ⚠️ 屏幕坐标来自无障碍树（**不可信数据**）：[ScreenRect] 刻意**不**在构造时
 *    校验大小关系 —— 畸形矩形要在**使用点**被过滤（[ScreenDerive]），
 *    而不是让一次构造异常炸掉整次工具调用。
 */

/** 屏幕矩形（左、上、右、下，像素）。字段语义与 `android.graphics.Rect` 一致。 */
data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/** 采集来源。与 `:perception` 的 `SnapshotSource` 一一对应（映射在 :app 做）。 */
enum class CaptureSource {
    ACCESSIBILITY,
    VISION,
    HYBRID,
    OCR_ONLY,
}

/**
 * 一次采集的完整结果。
 *
 * @property confidence 采集置信度（0~1）。照抄 `ScreenSnapshot.confidence` 的立场：
 *   **只有树时不给满分** —— 树不含视觉信息，给满分会让模型以为采集完美从而不补采。
 * @property nodes 扁平节点列表（与 `:perception` 的扁平输出一致；层级靠 depth 保留）。
 */
data class ScreenCaptureDto(
    val packageName: String,
    val activityName: String?,
    val width: Int,
    val height: Int,
    val source: CaptureSource,
    val confidence: Float,
    val capturedAtMs: Long,
    val nodes: List<UiNodeDto>,
)

/**
 * 一个节点的归一化表示。
 *
 * 字段与 `:perception` 的 `UiNode` 对齐；**新增了 [password]** ——
 * 密码框检测的第一优先信号是 `inputType=password`（`:safety` 的既有判据），
 * 而 `UiNode` 此前把它丢掉了，桥只能靠文本猜（"漏判比误判危险"，不可以）。
 */
data class UiNodeDto(
    val nodeId: String,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val bounds: ScreenRect,
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
    val viewIdResourceName: String?,
    val depth: Int,
    /** 密码输入框标记（`AccessibilityNodeInfo.isPassword`）。 */
    val password: Boolean,
)

// ═══════════════════════════════════════════════════════════════
//  端口
// ═══════════════════════════════════════════════════════════════

/**
 * 读屏端口 —— 由 `:app` 用 `:perception` 的实现填上。
 *
 * ## 契约（装错实现 = 整个产品说假话，所以写死在注释里）
 *
 * - [ScreenCaptureOutcome.Ok] 表示"**这次真的读到了**"（树可能很稀疏，比如
 *   微信朋友圈只有 2 个节点 —— 那是"读到了但内容少"，照抄既有立场：
 *   **绝不把"读不到"包装成"界面是空的"**）。
 * - "读不到"走 [ScreenCaptureOutcome.Unavailable]（权限/服务问题，
 *   用户去开权限）或 [ScreenCaptureOutcome.Failed]（本次失败，可重试）。
 * - [ScreenCaptureOutcome] 里的 reason 必须是**用户能直接读**的一句话。
 */
interface ScreenReaderPort {
    suspend fun capture(): ScreenCaptureOutcome
}

sealed interface ScreenCaptureOutcome {

    data class Ok(val dto: ScreenCaptureDto) : ScreenCaptureOutcome

    /** 能力不可用（无障碍没开 / 服务没连上）。用户动作：去开权限。 */
    data class Unavailable(val reason: String) : ScreenCaptureOutcome

    /** 本次读取失败（超时 / 窗口不提供节点）。用户动作：可重试。 */
    data class Failed(val reason: String) : ScreenCaptureOutcome
}

/**
 * 敏感判定端口 —— 由 `:app` 映射到 `:safety` 的 `SensitiveDetector`。
 *
 * ⚠️ 为什么是端口而不是直接依赖：`:safety` 是 Android 库模块
 *    （`com.android.library` + hilt/robolectric），**纯 Kotlin 模块在 Gradle
 *    上依赖不了它**。而判定引擎本身（`SensitiveRules`）是零 Android 依赖的 ——
 *    将来若把它拆成纯模块，这条端口可以退化成直接调用。
 */
interface ScreenSafetyPort {
    fun review(query: ScreenSafetyQuery): ScreenSafetyOutcome
}

/**
 * 一次敏感判定的输入。**只有"形状"，没有"内容"** ——
 * 控件字段刻意不含已输入的文本（与 `:safety` 的 `InputFieldSignature` 同一条纪律：
 * 判断"这是不是密码框"不需要看里面写了什么，多带一个字段就多一条泄漏路径）。
 */
data class ScreenSafetyQuery(
    val packageName: String,
    val visibleTexts: List<String>,
    val fields: List<InputFieldLite>,
)

/** 控件形状（与 `:safety` 的 `InputFieldSignature` 逐字段对应，映射由 :app 做）。 */
data class InputFieldLite(
    val hint: String?,
    val className: String?,
    val contentDescription: String?,
    /** `inputType` 明确标记密码 —— 唯一不依赖文本描述的信号。 */
    val isPassword: Boolean,
    val isEditable: Boolean = true,
)

sealed interface ScreenSafetyOutcome {

    /** 未命中任何敏感规则。 */
    data object Pass : ScreenSafetyOutcome

    /** 命中。`userMessage` 必须能直接展示给用户（沿用 `SensitivityHit` 的文案立场）。 */
    data class Block(val userMessage: String) : ScreenSafetyOutcome
}

// ═══════════════════════════════════════════════════════════════
//  派生（纯函数）
// ═══════════════════════════════════════════════════════════════

/**
 * 从采集结果派生出下游需要的输入。
 *
 * ⚠️ 全部是**纯函数** —— "哪些文本参与敏感判定""哪些节点算输入框"这类问题
 *    错一处的后果是静默的安全缺口，必须有确定性测试钉住。
 */
object ScreenDerive {

    /**
     * 供敏感判定的可见文本：所有可见节点的 text 与 contentDescription。
     *
     * 判定引擎内部会把它们拼起来做归一化匹配（"确认 支付"拆成两个节点也能命中），
     * 所以这里**不需要**预先拼接或去重 —— 保持原样，让引擎的既有规则全权处理。
     */
    fun visibleTexts(dto: ScreenCaptureDto): List<String> {
        val out = ArrayList<String>()
        for (node in dto.nodes) {
            if (!node.visible) continue
            node.text?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            node.contentDescription?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        }
        return out
    }

    /** 可见的输入控件形状（不含已输入内容）。 */
    fun inputFields(dto: ScreenCaptureDto): List<InputFieldLite> =
        dto.nodes
            .filter { it.visible && it.editable }
            .map {
                InputFieldLite(
                    hint = it.hintText,
                    className = it.className,
                    contentDescription = it.contentDescription,
                    isPassword = it.password,
                )
            }

    /**
     * 可见输入框的区域（供 `PrivacyFilter` 产出遮蔽计划）。
     *
     * ⚠️ 只收**几何合法**的矩形（right ≥ left 且 bottom ≥ top）——
     *    无障碍树的坐标是外部数据，畸形值在 `RedactRegion` 的构造校验处会抛异常，
     *    而一次构造异常会炸掉整次工具调用。在这里过滤，比在工具里 try/catch 诚实。
     */
    fun inputFieldRegions(dto: ScreenCaptureDto): List<RedactRegion> =
        dto.nodes
            .filter {
                it.visible && it.editable &&
                    it.bounds.right >= it.bounds.left && it.bounds.bottom >= it.bounds.top
            }
            .map { RedactRegion(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom) }
}
