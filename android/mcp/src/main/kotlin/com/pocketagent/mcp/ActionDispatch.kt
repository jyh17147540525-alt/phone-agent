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

    /**
     * 往一个输入框里写文字（2026-10-12 加）。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 为什么它必须存在
     * ═══════════════════════════════════════════════════════════
     *
     * 在此之前能力桥**只能点击与滑动** —— 而"帮我搜一下 X"、"把地址填进去"
     * 这类任务的**第一步就是打字**。没有它，跨应用任务执行大部分走不通。
     *
     * ⚠️ 语义是「**把输入框的内容设成 [text]**」，不是"追加"：
     *    底层用 `ACTION_SET_TEXT`，它是**替换**。要清空就传空串。
     *    刻意不做"追加"模式 —— 追加需要先读回原内容，
     *    而读回的内容会经过隐私关卡（输入框文本在树里是明文），
     *    多绕这一圈只是多一条泄漏路径，换来的能力用户很少需要。
     *
     * ⚠️ [ref] / [point] 与 [AgentAction.Tap] 同构：目标三选一（node_id / text / 坐标）。
     *    执行侧会**先点一下这个目标**再写入 —— 那是让人手做同一件事时的顺序，
     *    而且它顺带解决了"焦点在不在这个框上"的问题（见执行侧的自校验）。
     */
    data class TypeText(
        val ref: ResolvedNodeRef?,
        val point: ScreenPoint?,
        /** 要写入的文本。空串 = 清空该输入框。 */
        val text: String,
        val reason: String?,
    ) : AgentAction

    /**
     * 一个**全局导航键**（2026-10-12 加）。
     *
     * ⚠️ 只开放三个键，且**不开放 HOME 之外的"回桌面再打开别的 App"组合**：
     *    `BACK` / `HOME` / `RECENTS` 是**用户自己也会按**的导航动作，
     *    而"跳转到某个 App"是另一回事（它需要 `OPEN_APP` + 应用白名单，本版不做）。
     */
    data class GlobalKey(
        val key: GlobalKeyName,
        val reason: String?,
    ) : AgentAction
}

/**
 * 全局导航键的**白名单**。
 *
 * ⚠️ 枚举而不是字符串：执行侧要 `when` 穷尽匹配，
 *    传字符串会让"写错一个键名"变成运行期静默无效。
 */
enum class GlobalKeyName(val id: String, val label: String) {
    BACK("back", "返回"),
    HOME("home", "回桌面"),
    RECENTS("recents", "最近任务"),
    ;

    companion object {
        fun byId(raw: String): GlobalKeyName? = entries.firstOrNull { it.id == raw.trim().lowercase() }
    }
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
