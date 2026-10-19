package com.pocketagent.agentui

/**
 * 时间线上发生的一件事。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么事件是"输入"，而不是直接改卡片
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为界面上看到的一切都必须能**离线重放**：给同一串事件，结果必须逐字节相同。
 * 一旦允许 UI 直接改卡片，回放就会和当初不一致 —— 而"回放不一致"意味着
 * 用户看到的复盘是**编出来的**，比没有复盘更糟。
 *
 * ⇒ 所有变更只能经 [Timeline.reduce]，它是纯函数。
 */
sealed interface CardEvent {

    /** 事件发生时刻。★ 由调用方传入，内核不读时钟（离线可测的前提）。 */
    val at: Long

    /** 提出一个预演（Intent 卡）。 */
    data class IntentProposed(
        override val at: Long,
        val id: CardId,
        val action: String,
        val target: String?,
        val risk: RiskLevel,
        val reversible: Boolean,
        val evidence: List<Evidence> = emptyList(),
    ) : CardEvent

    /** 用户点头，预演转执行。 */
    data class IntentConfirmed(override val at: Long) : CardEvent

    /** 用户否决（或改主意）。 */
    data class IntentDeclined(override val at: Long, val reason: String) : CardEvent

    /** 一次工具调用开始。 */
    data class ToolStarted(
        override val at: Long,
        val id: CardId,
        val tool: String,
        val summary: String,
        val channel: String? = null,
        val evidence: List<Evidence> = emptyList(),
    ) : CardEvent

    /** 工具成功返回。 */
    data class ToolSucceeded(
        override val at: Long,
        val id: CardId,
        val detail: String,
        val latencyMs: Long? = null,
        val uncertainty: String? = null,
    ) : CardEvent

    /** 被拒绝或失败 —— 走 [Gate] 分流，出口由 [FailurePlan] 给出。 */
    data class ToolFailed(
        override val at: Long,
        val id: CardId,
        val gate: Gate,
        val reason: String,
        val exits: List<Exit>,
    ) : CardEvent

    /** 一次观察（读屏 / 截图摘要）。 */
    data class Observed(
        override val at: Long,
        val id: CardId,
        val summary: String,
        val nodeCount: Int,
        val redacted: Boolean = false,
        val evidence: List<Evidence> = emptyList(),
    ) : CardEvent

    /** 助理说话的增量（流式）。 */
    data class AssistantDelta(override val at: Long, val text: String) : CardEvent

    /** 助理说完了。 */
    data class AssistantDone(override val at: Long) : CardEvent

    /** 用户接管：立刻停手。 */
    data class TakeOver(override val at: Long) : CardEvent

    /** 预算耗尽（轮次 / 时长 / 能耗），被动暂停。 */
    data class BudgetExhausted(override val at: Long) : CardEvent

    /** 恢复（用户说"接着来"）。 */
    data class Resume(override val at: Long) : CardEvent
}

/** 暂停的原因。 */
enum class PauseReason {
    /** 用户点了「我来」。 */
    USER_TAKEOVER,

    /** 预算耗尽（轮次 / 时长 / 能耗）。 */
    BUDGET_EXHAUSTED,
}

/** 一次 reduce 被拒绝的原因。 */
enum class RejectReason {
    /** 卡片已经进入终态（DONE / BLOCKED / FAILED / CANCELLED）。 */
    CARD_ALREADY_TERMINAL,

    /** 事件指向的卡片不存在。 */
    CARD_NOT_FOUND,

    /**
     * 事件与卡片的类型不匹配（例如"工具成功"打在观察卡上）。
     *
     * ★ 必须与 [CARD_NOT_FOUND] 分开：那张卡**明明存在**。
     *   报"找不到"会把排查方向带到"是不是 id 传错了"，而真正的问题是接线错了。
     */
    WRONG_CARD_KIND,

    /** 这个 id 已经被别的卡片占用。 */
    ID_REUSED,

    /** 已暂停（用户接管 / 预算耗尽），不接受任何执行类事件。 */
    PAUSED,

    /** 当前没有待确认的预演。 */
    NO_PENDING_INTENT,

    /** 已经有一个预演在等确认。 */
    INTENT_ALREADY_PENDING,

    /** 空文本增量 —— 不产生卡片。 */
    EMPTY_DELTA,

    /**
     * 事件时间早于时间线上最后一条。
     *
     * ★ 这不是洁癖：真机上事件来自三条线程（模型流、无障碍回调、UI），
     *   一旦乱序，卡片顺序就和"实际发生顺序"不一致 —— 而复盘的全部价值
     *   就是顺序。宁可显式拒绝一条，也不要静默拼出一个看起来合理、
     *   其实错位的历史。
     */
    OUT_OF_ORDER,
}

/** reduce 的结果。 */
sealed interface Reduction {

    /** 已应用。 */
    data class Applied(val timeline: Timeline) : Reduction

    /**
     * 未应用，时间线**原样返回**。
     *
     * ★ 拒绝必须是**显式返回值**，不能"静默忽略然后返回同一条时间线" ——
     *   后者会让"事件被丢掉"和"事件没发生"在调用方看来一模一样，
     *   而这正是本项目反复踩的坑：不报错，只是安静地少做一件事。
     */
    data class Rejected(val timeline: Timeline, val reason: RejectReason) : Reduction
}
