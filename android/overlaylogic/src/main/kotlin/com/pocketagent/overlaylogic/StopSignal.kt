package com.pocketagent.overlaylogic

/**
 * 急停信号的语义契约。
 *
 * ## 为什么急停必须由悬浮球承担
 *
 * 悬浮球所在的进程与 agent 执行进程**相互独立**。
 * 如果 agent 跑飞（死循环、被模型带偏、卡在某个界面），
 * 用户需要一个**仍然能响应**的开关 —— 它不能和被卡住的东西在同一个进程里。
 *
 * > 这是"急停开关必须在另一个进程"的具体落地。
 *
 * ⚠️ **本文件是纯逻辑，不含任何进程间通信实现** ——
 * 实际的进程间投递（AIDL / Binder / 广播）依赖 Android，等真机阶段实现。
 * 这里定义的是**必须满足的语义**，可以被测试钉住。
 */
data class StopRequest(
    /** 请求唯一 ID，用于去重与回执匹配 */
    val requestId: String,
    /** 要停止的任务 ID；null 表示"停止当前一切任务" */
    val taskId: String?,
    /** 用户触发的时间戳（毫秒） */
    val timestampMs: Long,
    /** 来源渠道，用于诊断（悬浮球 / 通知栏 / 应用内按钮） */
    val source: StopSource,
)

/** 急停的触发来源 */
enum class StopSource {
    /** 悬浮球 —— 最重要的一个，独立于执行进程 */
    OVERLAY_BALL,

    /** 系统通知栏的"停止"按钮 */
    NOTIFICATION,

    /** 应用内界面 */
    IN_APP,

    /** 语音指令（"停下来"） */
    VOICE,
}

/** 急停的处理结果 */
sealed interface StopOutcome {
    /** 已受理，执行进程已收到信号 */
    data class Accepted(val requestId: String, val acknowledgementMs: Long) : StopOutcome

    /** 当前没有任务在跑，无需停止（仍应给用户反馈，不能"看起来没反应"） */
    data class NothingToStop(val requestId: String) : StopOutcome

    /**
     * 该请求已被处理过。
     *
     * 去重是必要的：用户可能连点急停，或者悬浮球与通知栏同时触发。
     * 重复执行停止逻辑没有意义，但**重复反馈**会让用户困惑。
     */
    data class Duplicate(val requestId: String, val originalOutcome: StopOutcome) : StopOutcome
}

/**
 * 急停请求的去重与状态跟踪。
 *
 * 纯逻辑，可离线测试。实际投递由 Android 层负责，但"这个请求是否已处理过"
 * 的判断必须在这里 —— 因为它是有状态逻辑，且错判会导致用户困惑。
 *
 * @param maxRetained 最多记住多少个已处理请求（防止无限增长）
 */
class StopRequestTracker(private val maxRetained: Int = 32) {

    init {
        require(maxRetained > 0) { "maxRetained 必须为正数，当前为 $maxRetained" }
    }

    // 用 LinkedHashMap 保持插入顺序，便于按最旧优先淘汰
    private val processed = LinkedHashMap<String, StopOutcome>()

    /** 记录一个已处理的请求；超出容量时淘汰最旧的 */
    fun record(outcome: StopOutcome) {
        val id = when (outcome) {
            is StopOutcome.Accepted -> outcome.requestId
            is StopOutcome.NothingToStop -> outcome.requestId
            is StopOutcome.Duplicate -> outcome.requestId
        }
        processed[id] = outcome

        while (processed.size > maxRetained) {
            val oldest = processed.keys.firstOrNull() ?: break
            processed.remove(oldest)
        }
    }

    /** 查询某请求是否已处理过，返回其原始结果 */
    fun findProcessed(requestId: String): StopOutcome? = processed[requestId]

    /** 已记录的请求数（供测试与诊断） */
    val trackedCount: Int get() = processed.size

    fun clear() = processed.clear()
}

/**
 * 急停语义判定器。
 *
 * 把"收到一个停止请求后该怎么办"的决策从 Android 层剥离出来，
 * 这样可以在没有设备的情况下验证：去重是否正确、
 * 空任务时的反馈是否到位（**急停不能"看起来没反应"**）。
 */
class StopHandler(private val tracker: StopRequestTracker = StopRequestTracker()) {

    /**
     * 处理一个停止请求。
     *
     * @param request 收到的请求
     * @param runningTaskId 当前正在执行的任务 ID（null = 无任务）
     * @param nowMs 当前时间，用于计算受理延迟
     */
    fun handle(request: StopRequest, runningTaskId: String?, nowMs: Long): StopOutcome {
        // 先查重：同一 requestId 重复投递直接返回原结果
        tracker.findProcessed(request.requestId)?.let { original ->
            return StopOutcome.Duplicate(request.requestId, original)
        }

        val outcome = when {
            // 没有任务在跑：仍然返回一个明确结果。
            // ⚠️ UI 层收到这个结果时必须给出反馈（例如球闪一下），
            //    否则用户会以为急停坏了，反复点击。
            runningTaskId == null -> StopOutcome.NothingToStop(request.requestId)

            // 指定了 taskId 但和当前跑的对不上：视为无事可停。
            // 这种情况通常是任务刚好结束了，属于正常竞态。
            request.taskId != null && request.taskId != runningTaskId ->
                StopOutcome.NothingToStop(request.requestId)

            else -> StopOutcome.Accepted(
                requestId = request.requestId,
                acknowledgementMs = (nowMs - request.timestampMs).coerceAtLeast(0),
            )
        }

        tracker.record(outcome)
        return outcome
    }

    /** 已跟踪的请求数（诊断用） */
    val trackedCount: Int get() = tracker.trackedCount
}
