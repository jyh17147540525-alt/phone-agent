package com.pocketagent.mcp

/**
 * 执行通道的**窄端口**与纯数据 DTO —— `android_tap` / `android_swipe` 经由它把动作交出去。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不是直接依赖 `:action` 的 ActionExecutor / UiAction
 * ═══════════════════════════════════════════════════════════════
 *
 * 与 [ScreenReaderPort] 同一个理由：`:action` 依赖 `android.graphics.Rect`，
 * 本模块（纯 Kotlin，进离线验证器）依赖不起。所以这里定义**镜像 DTO**，
 * 由 `:app` 薄壳逐字段映射到 `:action` 的既有类型 —— **不重造执行抽象**，
 * 只搬运。`ActionExecutor` / `ActionDispatcher` / `UiAction` 仍是
 * 执行侧唯一的事实来源（见 `android/action/ActionExecutor.kt`）。
 *
 * ⚠️ [ResolvedNodeRef] 里的 bounds 不是装饰：`:action` 的 `ElementRef.ByNodeId`
 *    注释写明「跨快照的 nodeId 不保证稳定，执行前必须用 bounds 二次校验」。
 *    本工具在**自己的采集快照**里解析目标，把 nodeId + 当时的 bounds 一起
 *    交给执行侧；执行侧在**派发前的实时树**上再校验一次 ——
 *    两次快照之间界面变了，必须失败，而不是点到别的东西上。
 */

/** 屏幕坐标（像素）。 */
data class ScreenPoint(val x: Int, val y: Int)

/**
 * 已在**本工具的快照树**里解析并校验过的节点引用。
 *
 * @param nodeId 快照里的节点 id（原样交给执行侧）
 * @param bounds 解析时的边界 —— 执行侧用它做二次校验（防跨快照漂移）
 * @param note 人类可读的解析说明（如「语义节点不可点击，已上浮到可点击祖先」），
 *   只进日志与响应文本，不参与判定
 */
data class ResolvedNodeRef(
    val nodeId: String,
    val bounds: ScreenRect,
    val note: String? = null,
)

/**
 * 要派给执行通道的动作。
 *
 * ⚠️ 这是 `UiAction` 的**子集镜像**：只包含 P3 离线部分需要的两种动作
 *    （点击 / 滑动）。将来加 input_text 等动作时，在这个包里扩展，
 *    不要顺手把 `:action` 的重型字段（expectation、deepLink…）搬进来 ——
 *    每一个字段都是一条要测试的路径。
 */
sealed interface AgentAction {

    /**
     * 点击。
     *
     * [ref] 与 [point] **恰好有一个非空**：
     * - [ref] 非空 = 语义/坐标解析到了元素（走 `ActionType.CLICK`，可校验）；
     * - [ref] 为空 = **盲点**（该坐标处没有任何可识别节点，走 `ActionType.TAP_COORD`）——
     *   盲点只过了页面级安全关卡（详见 `TapTool` 的注释），这是有意的取舍。
     */
    data class Tap(
        val ref: ResolvedNodeRef?,
        val point: ScreenPoint?,
        /** 模型给出的动机说明（可选），转发给执行侧/审计 */
        val reason: String?,
    ) : AgentAction

    /** 滑动（含滚动）。 */
    data class Swipe(
        val from: ScreenPoint,
        val to: ScreenPoint,
        val durationMs: Long,
        val reason: String?,
    ) : AgentAction
}

/**
 * 一次派发的结局。
 *
 * ⚠️ [Refused] 与 [Failed] 分开，理由与 [ToolOutcome] 完全一致：
 *    「被安全要求人工接手」与「通道坏了」的排查方向、对用户的说法、是否重试，全部相反。
 */
sealed interface DispatchOutcome {

    /**
     * 动作已交给执行通道。
     *
     * ⚠️ **不代表业务成功**——`:action` 的 `ActionResult.Dispatched` 注释写明
     *    "执行后不要自行判断成功与否，那是 Verifier 的职责"。
     *    工具的成功文案必须如实转达这一点。
     */
    data class Dispatched(val channel: String, val latencyMs: Long) : DispatchOutcome

    /** 执行通道要求人工介入（如兜底通道只剩引导模式）。 */
    data class Refused(val reason: String) : DispatchOutcome

    /** 通道不可用 / 目标失效 / 执行失败。 */
    data class Failed(val reason: String) : DispatchOutcome
}

/**
 * 执行端口 —— 由 `:app` 用 `:action` 的 [ActionDispatcher] 装配填补。
 *
 * ## 契约
 *
 * - 实现方**不得抛异常**：所有失败归入 [DispatchOutcome]（与 `McpTool.call` 同一条纪律）。
 * - [DispatchOutcome.Dispatched.channel] 用执行侧通道名（`ExecutorChannel.name`），
 *   这是诊断"到底哪个通道在干活"的唯一线索。
 * - 引导模式（OVERLAY_PROMPT）要求用户手动操作 —— 那是 [DispatchOutcome.Refused]，
 *   不是失败：文案必须说清"请用户接手"。
 */
interface ActionDispatchPort {
    suspend fun dispatch(action: AgentAction): DispatchOutcome
}
