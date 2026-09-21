package com.pocketagent.agent

import kotlinx.coroutines.flow.Flow

/**
 * 任务编排 —— 决策层的总入口。
 *
 * 采用**双脑架构**，把「长程规划」与「精确元素定位」分离：
 *
 * ```
 * ┌──────────────────────────────────────────────────────────┐
 * │  Orchestrator（规划脑）—— 云端强模型                       │
 * │  输入：用户指令 + 任务历史 + 记忆 + 上一步的执行反馈         │
 * │  输出：NextStep { action, target, expectation }           │
 * └────────────────────────┬─────────────────────────────────┘
 *                          ↓ 语义目标描述
 * ┌──────────────────────────────────────────────────────────┐
 * │  Grounder（定位脑）—— 端侧小模型 / 便宜多模态              │
 * │  输入：ScreenSnapshot + 目标描述                           │
 * │  输出：ElementRef + confidence                            │
 * └────────────────────────┬─────────────────────────────────┘
 *                          ↓ 可执行动作
 * ┌──────────────────────────────────────────────────────────┐
 * │  ActionDispatcher（执行）—— 多通道自动降级                 │
 * └────────────────────────┬─────────────────────────────────┘
 *                          ↓ 执行反馈
 * ┌──────────────────────────────────────────────────────────┐
 * │  Verifier（校验）—— 规则 + 轻量模型                        │
 * │  对比 expectation 与实际屏幕 → 成功 / 失败 / 异常           │
 * │  失败则回传 Orchestrator 重规划（上限 [maxReplans] 次）      │
 * └──────────────────────────────────────────────────────────┘
 * ```
 *
 * 为什么必须分离？长程规划与坐标定位是两种完全不同的能力。
 * 混在一个模型里会得到「规划很好但点不准」或「点得准但不会规划」。
 * 分离后还能把高频、廉价的定位任务下沉到端侧模型，显著降低用户的 token 支出。
 */
interface AgentOrchestrator {

    /**
     * 执行一个用户任务。
     *
     * 返回冷流，每个事件代表任务推进的一个节点，UI 据此渲染「执行过程时间线」。
     * ⚠️ 执行过程必须对用户可见 —— 黑盒执行 = 用户不敢用。
     */
    fun execute(task: AgentTask): Flow<AgentEvent>

    /** 中止正在执行的任务 */
    suspend fun abort(taskId: String, reason: String)
}

/** 一个用户任务 */
data class AgentTask(
    val id: String,
    val userInput: String,
    /** 任务来源，影响交互策略（悬浮球 / 通知栏 / 语音 / 快捷方式） */
    val origin: TaskOrigin,
    /** 是否要求每步确认（首次使用建议开启，用于建立信任） */
    val requireStepConfirmation: Boolean = false,
    /** 最大步数保护 */
    val maxSteps: Int = 20,
    /** 失败重规划上限 */
    val maxReplans: Int = 3,
)

enum class TaskOrigin { FLOATING_BALL, NOTIFICATION, VOICE, SHORTCUT, ASSISTANT_ROLE, WIDGET }

/** 任务推进事件 */
sealed interface AgentEvent {
    /** 任务开始，附带规划出的步骤概览 */
    data class Started(val taskId: String, val plan: List<PlanStep>) : AgentEvent

    /** 即将执行某一步 —— UI 必须在此展示「我要做什么」 */
    data class StepStarted(val stepIndex: Int, val step: PlanStep, val snapshotSummary: String) : AgentEvent

    /** 定位结果 */
    data class Grounded(val stepIndex: Int, val ref: String, val confidence: Float) : AgentEvent

    /** 动作已执行 */
    data class StepExecuted(val stepIndex: Int, val result: String, val latencyMs: Long) : AgentEvent

    /** 校验结果 */
    data class StepVerified(val stepIndex: Int, val success: Boolean, val note: String?) : AgentEvent

    /** 需要用户确认（危险动作 / 敏感页面） */
    data class ConfirmationRequired(val stepIndex: Int, val message: String, val timeoutMs: Long) : AgentEvent

    /** 需要用户接管 */
    data class UserInterventionRequired(
        val reason: String,
        val message: String,
        /** 是否可降级为「展示步骤让用户手动完成」 */
        val fallbackToManual: Boolean,
    ) : AgentEvent

    /** 重规划 */
    data class Replanning(val attempt: Int, val reason: String) : AgentEvent

    /** 任务完成 */
    data class Completed(val taskId: String, val summary: String, val stepsExecuted: Int, val cost: String) : AgentEvent

    /** 任务失败或中止 */
    data class Failed(val taskId: String, val reason: String, val stepsExecuted: Int) : AgentEvent
}

/** 规划出的单个步骤 */
data class PlanStep(
    val index: Int,
    /** 动作类型，如 CLICK / INPUT_TEXT */
    val actionType: String,
    /** 语义目标描述，交给 Grounder 解析 */
    val targetDescription: String?,
    /** 预期结果，交给 Verifier 校验 */
    val expectation: String,
    /** 是否需要用户确认 */
    val requiresConfirmation: Boolean = false,
)

/**
 * 元素定位器 —— 定位脑。
 *
 * 实现要点：
 * 1. **优先命中 UI 图谱缓存**：同一 App 的同一页面重复出现时，
 *    直接用缓存的选择器定位，跳过模型调用。这是把重复任务成本降到接近 0 的关键。
 * 2. **输入用序号而非坐标**：把候选元素列表编号后交给模型，让它输出序号。
 *    这比让模型回归坐标的准确率高得多，token 也更省。
 * 3. **低置信度时补充采集**：若树信息不足以判断，返回 [GroundResult.NeedMoreInfo]，
 *    由调用方补一张截图后重试。
 */
interface Grounder {
    suspend fun locate(
        targetDescription: String,
        snapshot: com.pocketagent.perception.ScreenSnapshot,
        /** 是否允许使用缓存 */
        allowCache: Boolean = true,
    ): GroundResult
}

sealed interface GroundResult {
    data class Found(
        val ref: com.pocketagent.action.ElementRef,
        val confidence: Float,
        /** 是否来自 UI 图谱缓存 */
        val fromCache: Boolean,
    ) : GroundResult

    /** 信息不足，需要补充采集（通常是补一张截图） */
    data class NeedMoreInfo(val reason: String) : GroundResult

    data class NotFound(val reason: String) : GroundResult

    /** 匹配到多个候选，需要消歧 */
    data class Ambiguous(val candidates: List<com.pocketagent.action.ElementRef>) : GroundResult
}

/**
 * 结果校验器。
 *
 * 两层策略：
 * 1. **规则层**（零成本）：包名/Activity 是否变化、目标文本是否出现、元素是否消失
 * 2. **模型层**（低成本小模型）：规则无法判断时，把 expectation 与当前快照摘要交给小模型
 *
 * ⚠️ 规则层必须优先，且要能覆盖 80% 的场景。每一步都调模型校验会把用户的钱烧光。
 */
interface Verifier {
    suspend fun verify(
        expectation: String,
        beforeSnapshot: com.pocketagent.perception.ScreenSnapshot,
        afterSnapshot: com.pocketagent.perception.ScreenSnapshot,
    ): VerifyResult
}

sealed interface VerifyResult {
    data object Success : VerifyResult
    /** 明确失败，附带原因 */
    data class Failed(val reason: String) : VerifyResult
    /** 无法判断 —— 交由上层决定是重规划还是继续 */
    data class Unknown(val reason: String) : VerifyResult
    /** 页面异常（弹窗、崩溃、跳转到无关页面） */
    data class Unexpected(val description: String) : VerifyResult
}

/**
 * 任务状态机 —— 防止长任务失控。
 *
 * 关键保护：
 * - 最大步数限制（默认 20）
 * - 失败重规划上限（默认 3）
 * - 卡死检测：同一动作重复 2 次且屏幕无变化 → 判定卡死
 * - 敏感页面强制中止（不可配置绕过）
 */
interface TaskStateMachine {
    fun currentState(taskId: String): TaskState
    fun canContinue(taskId: String): ContinueDecision
}

sealed interface TaskState {
    data object Idle : TaskState
    data class Running(val stepIndex: Int, val replanCount: Int) : TaskState
    data class WaitingUser(val reason: String) : TaskState
    data class Finished(val success: Boolean, val reason: String) : TaskState
}

sealed interface ContinueDecision {
    data object Continue : ContinueDecision
    data class Stop(val reason: String) : ContinueDecision
}
