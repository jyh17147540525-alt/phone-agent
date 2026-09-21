package com.pocketagent.action

import android.graphics.Rect

/**
 * 动作执行的统一抽象。
 *
 * ⚠️ 这是本项目**最重要的抗风险设计**。
 *
 * 背景：Android 17 Beta 2 起，高级保护模式下非无障碍类应用被禁止获取
 * AccessibilityService 权限，已授权的会被自动撤销，且无法通过 ADB 绕过。
 * 因此产品**绝不能把执行能力绑死在无障碍上**。
 *
 * 四条通道（按优先级）：
 * | 通道            | 机制                          | 抗政策风险 | 体验 |
 * |-----------------|-------------------------------|-----------|------|
 * | ACCESSIBILITY   | AccessibilityService + 手势   | 低        | 好   |
 * | SHIZUKU         | ADB shell（input/am/screencap）| 中        | 一般 |
 * | IME             | 自建输入法注入文本             | 中        | 一般 |
 * | OVERLAY_PROMPT  | 悬浮窗高亮 + 用户手点          | 高        | 差   |
 *
 * 调度器按 priority 降序尝试，失败则自动降级。**永远保留 OVERLAY_PROMPT 作为最终兜底**，
 * 保证产品在任何政策环境下都不至于完全不可用。
 */
interface ActionExecutor {

    val channel: ExecutorChannel

    /** 该通道当前是否可用（权限已授予 / 服务已激活） */
    fun isAvailable(): Boolean

    /** 优先级，数值越大越优先尝试 */
    fun priority(): Int

    /** 该通道支持哪些动作类型 */
    fun supportedActions(): Set<ActionType>

    /**
     * 执行动作。
     *
     * 实现要求：
     * - 必须**先校验 [isAvailable]**，不可用时返回 [ActionResult.ChannelUnavailable]
     * - 执行后**不要自行判断成功与否**，那是 Verifier 的职责；本方法只报告「动作是否被派发」
     * - 异常不得外抛，一律转为 [ActionResult.Failed]
     */
    suspend fun perform(action: UiAction): ActionResult
}

enum class ExecutorChannel {
    ACCESSIBILITY,
    SHIZUKU,
    IME,
    OVERLAY_PROMPT,
}

/** 动作类型 */
enum class ActionType {
    CLICK,          // 点击元素
    TAP_COORD,      // 坐标点击（兜底）
    LONG_PRESS,     // 长按
    INPUT_TEXT,     // 输入文本
    CLEAR_TEXT,     // 清空输入框
    SCROLL,         // 滚动
    SWIPE,          // 滑动
    BACK,           // 返回
    HOME,           // 回桌面
    RECENTS,        // 最近任务
    OPEN_APP,       // 打开应用（含深层链接）
    WAIT,           // 等待
    ASK_USER,       // 询问用户
    FINISH,         // 任务完成
    ABORT,          // 中止任务
}

/**
 * 待执行的动作。
 *
 * 注意 [target] 与 [fallback] 的双重设计：模型优先给出语义目标（元素描述），
 * Grounder 解析出具体引用；解析失败时退化为坐标点击。
 */
data class UiAction(
    val type: ActionType,
    /** 语义目标描述，如「顶部的搜索输入框」 */
    val targetDescription: String? = null,
    /** Grounder 解析出的元素引用 */
    val elementRef: ElementRef? = null,
    /** 输入文本（INPUT_TEXT 用） */
    val text: String? = null,
    /** 坐标（TAP_COORD / SWIPE 用） */
    val point: Point? = null,
    val endPoint: Point? = null,
    /** 滚动方向与距离 */
    val direction: ScrollDirection? = null,
    val distancePx: Int? = null,
    /** 长按时长 */
    val durationMs: Long? = null,
    /** 打开 App 的参数 */
    val packageName: String? = null,
    val deepLink: String? = null,
    /** 等待时长 */
    val waitMs: Long? = null,
    /** 该动作的预期结果，供 Verifier 校验 */
    val expectation: String? = null,
    /** 是否已被 SafetyGuard 放行 */
    val safetyCleared: Boolean = false,
) {
    val isRisky: Boolean
        get() = type in setOf(ActionType.CLICK, ActionType.TAP_COORD, ActionType.LONG_PRESS) && !safetyCleared
}

/** 元素引用 —— Grounder 的输出，Executor 的输入 */
sealed interface ElementRef {
    /**
     * 通过稳定节点 ID 定位。优先使用。
     * nodeId 来自 [com.pocketagent.perception.UiNode.nodeId]，
     * 但注意：**跨快照的 nodeId 不保证稳定**，执行前必须用 [bounds] 二次校验。
     */
    data class ByNodeId(val nodeId: String, val bounds: Rect) : ElementRef

    /** 通过 viewIdResourceName 定位，比 nodeId 更稳定 */
    data class ByViewId(val viewIdResourceName: String, val bounds: Rect) : ElementRef

    /** 通过坐标定位。最不稳定但最通用 */
    data class ByCoord(val x: Int, val y: Int) : ElementRef

    /** 通过文本定位 */
    data class ByText(val text: String, val exact: Boolean = false) : ElementRef

    val bounds: Rect?
        get() = when (this) {
            is ByNodeId -> bounds
            is ByViewId -> bounds
            is ByCoord -> Rect(x, y, x, y)
            is ByText -> null
        }
}

data class Point(val x: Int, val y: Int)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** 动作执行结果 */
sealed interface ActionResult {
    /** 动作已派发。注意：不代表业务成功，需 Verifier 校验 */
    data class Dispatched(val channel: ExecutorChannel, val latencyMs: Long) : ActionResult

    /** 该通道不支持此动作 */
    data class Unsupported(val channel: ExecutorChannel) : ActionResult

    /** 该通道不可用 */
    data class ChannelUnavailable(val channel: ExecutorChannel, val reason: String) : ActionResult

    /** 目标元素未找到 */
    data class TargetNotFound(val description: String?) : ActionResult

    /** 执行失败 */
    data class Failed(val channel: ExecutorChannel, val reason: String) : ActionResult

    /** 需要用户介入（如命中敏感页面、出现验证码） */
    data class NeedUserIntervention(val reason: UserInterventionReason, val message: String) : ActionResult
}

enum class UserInterventionReason {
    SENSITIVE_PAGE,     // 敏感页面（支付/密码/验证码）
    CAPTCHA,            // 图形/短信验证码
    LOGIN_REQUIRED,     // 需要登录
    PERMISSION_DENIED,  // 权限被拒
    UNKNOWN_DIALOG,     // 无法识别的弹窗
    USER_REQUESTED,     // 用户主动要求接管
}

/**
 * 动作调度器：多通道编排与自动降级。
 *
 * 降级逻辑：
 * ```
 * 按 priority 降序遍历可用通道
 *   ├─ 支持该动作 → 执行
 *   │     ├─ Dispatched       → 返回（交 Verifier 校验）
 *   │     ├─ Unsupported      → 继续下一个通道
 *   │     ├─ TargetNotFound   → 继续下一个通道
 *   │     └─ Failed           → 继续下一个通道
 *   └─ 不支持/不可用 → 继续
 * 全部失败 → 返回 NeedUserIntervention
 * ```
 */
interface ActionDispatcher {
    suspend fun dispatch(action: UiAction): ActionResult

    /** 当前各通道的可用状态，用于 UI 展示与诊断 */
    fun channelStatus(): Map<ExecutorChannel, ChannelStatus>
}

data class ChannelStatus(
    val available: Boolean,
    val detail: String,
    val lastError: String? = null,
)

/**
 * 拟人化规范 —— 降低被风控识别的概率，同时提升真实用户观感。
 *
 * ⚠️ 这不是「规避检测」的黑产手段，而是让自动化行为更接近真人操作节奏，
 *    减少对第三方 App 的异常压力。所有数值必须随机化，禁止使用固定常量。
 */
object HumanizePolicy {
    /** 点击位置在元素内的随机偏移比例上限 */
    const val MAX_OFFSET_RATIO = 0.3f

    /** 操作间隔范围（毫秒） */
    val ACTION_INTERVAL_RANGE = 300L..800L

    /** 逐字输入的每字间隔范围（毫秒） */
    val TYPING_INTERVAL_RANGE = 40L..120L

    /** 同一 App 内的操作频率上限（次/分钟） */
    const val MAX_ACTIONS_PER_MINUTE = 20

    fun randomInterval(): Long = ACTION_INTERVAL_RANGE.random()

    fun randomTypingInterval(): Long = TYPING_INTERVAL_RANGE.random()
}
