package com.pocketagent.agentlogic

/**
 * 检查点存储的**接口** —— 落盘实现由 Android 层提供。
 *
 * ## 为什么是接口而不是直接调 Room
 *
 * 一旦这里 `import androidx.room.*`，本模块就再也进不了
 * `tools/verify/run_logic_tests.py` 离线验证器。
 * 而"恢复点算错了"恰好是那种**不报错、只是恢复到一个错误的步骤**的问题 ——
 * 必须能被离线测试钉住。
 *
 * 契约在这里、实现在上层，这是本项目既定模式
 * （与 `:provider:gateway` 的 `UsageRecorder` 同构）。
 *
 * ## 为什么接口是 `suspend`
 *
 * 落盘可能涉及磁盘 IO 与加密。做成同步会让调用方在协程里阻塞，
 * 而"每步都落盘"（D-AW）意味着这个调用在热路径上。
 */
interface CheckpointStore {

    /**
     * 保存一个检查点。
     *
     * ⚠️ 实现必须是**幂等**的：同一个 taskId 重复保存应当覆盖而不是追加。
     * 否则用户中断恢复一次就会看到两份同名记录。
     */
    suspend fun save(checkpoint: TaskCheckpoint)

    /** 载入某个任务的检查点；不存在返回 null */
    suspend fun load(taskId: String): TaskCheckpoint?

    /**
     * 载入**最近**一个未完成的检查点，用于"上次任务没跑完"的恢复提示。
     *
     * ⚠️ 只返回未完成的 —— 返回已完成的会让用户在每次启动时
     * 被问"要不要恢复这个早已完成的任务"。
     */
    suspend fun loadLatestIncomplete(): TaskCheckpoint?

    /** 删除某个任务的检查点（任务正常结束、用户放弃恢复时调用） */
    suspend fun delete(taskId: String)
}

/**
 * 任务检查点 —— **移动端必需，不是补丁**。
 *
 * ## 为什么移动端必须有
 *
 * 桌面 agent 可以假设"进程一直活着"。移动端不行：
 *
 * | 中断源 | 频率 |
 * |---|---|
 * | 系统低内存杀后台 | 高 |
 * | 用户切到别的 App，本应用被冻结 | 极高 |
 * | HyperOS / MIUI 的后台管控 | 高 |
 * | 用户主动锁屏 | 高 |
 * | 电量优化触发的清理 | 中 |
 *
 * 也就是说：**任务跑到一半进程死掉是常态，不是异常。**
 *
 * 一个没有恢复能力的 agent，在移动端会表现为"十次里有三次白跑，
 * 而且用户不知道跑到哪儿了"。这不是体验问题，是可用性问题。
 *
 * ## ★ D-AW 的落地：**每步都落盘**
 *
 * 规划文档的建议是"每步"，理由是：
 * 省这一次写入会让**恢复点变得不可预测** ——
 * 用户看到"已恢复"，但恢复到哪一步取决于快照时机，
 * 而那种不确定性会让人不敢用恢复功能。
 *
 * 每步落盘的成本是一条小记录的写入（任务状态只有几十字节），
 * 相比之下一次截图就上百 KB。**这笔账不需要权衡。**
 *
 * ## ⚠️ 检查点里**绝不含截图**
 *
 * 截图是最高敏感度数据（可能含验证码、聊天内容、支付页面）。
 * 检查点要长期驻留磁盘，把截图放进去等于把敏感数据
 * 从"用完即回收"变成"永久留档"。
 *
 * 需要视觉信息的续跑，应当**重新采集** —— 用户中断后回到的界面
 * 本来也可能已经变了，旧截图的价值本就有限。
 */
data class TaskCheckpoint(
    val taskId: String,
    /** 用户原始指令 —— 恢复时要重新展示给用户确认 */
    val userInput: String,
    /** 已完成到第几步（下一步应当执行 index = [completedSteps]） */
    val completedSteps: Int,
    /** 重规划次数。**必须持久化** —— 否则中断恢复后重规划上限被重置，失控保护失效 */
    val replanCount: Int,

    /**
     * 已有的计划步骤（归一化后的动作名）。
     *
     * ⚠️ 存的是**枚举名**（`StepAction.name`）而不是序号 ——
     * 序号在枚举增删后会指向另一个动作，那是最难查的一类 bug
     * （恢复后"点了另一个按钮"）。
     */
    val plannedActions: List<String>,

    /** 已累计的预算用量。**必须持久化** —— 否则恢复后预算从零开始，等于没有预算 */
    val usage: BudgetUsage,

    /** 落盘时刻 */
    val savedAtMs: Long,

    /** 是否是正常结束的检查点。true 的检查点不参与"恢复上次任务"的提示 */
    val finished: Boolean = false,
) {

    init {
        require(taskId.isNotBlank()) { "taskId 不能为空" }
        require(completedSteps >= 0) { "completedSteps 不能为负，当前为 $completedSteps" }
        require(replanCount >= 0) { "replanCount 不能为负，当前为 $replanCount" }
        require(completedSteps <= plannedActions.size) {
            "completedSteps($completedSteps) 不能超过计划步数(${plannedActions.size})"
        }
    }

    /** 是否还有未执行的步骤 */
    val hasRemaining: Boolean get() = completedSteps < plannedActions.size

    /** 剩余步骤数 */
    val remainingSteps: Int get() = (plannedActions.size - completedSteps).coerceAtLeast(0)
}

/**
 * 检查点的纯逻辑层：**决定"该不该恢复"与"恢复到什么状态"**。
 *
 * 落盘由 [CheckpointStore] 负责，本类只管决策。
 * 这样"恢复决策"可以被离线测试完整覆盖 —— 而它恰恰是最容易出错的部分：
 * 一个错误的恢复决策会让用户在毫无察觉的情况下，把一个已中断的任务
 * 从半路继续，而前置步骤的效果可能早已消失（比如 App 被切走了）。
 */
class TaskCheckpointManager(
    private val store: CheckpointStore,
    /**
     * 检查点的有效期限。超过此时长的检查点**不再建议恢复**。
     *
     * 默认 30 分钟。理由：手机上的界面状态存活不了更久 ——
     * 半小时后用户可能已经重启过手机、App 早已被卸载重装、
     * 页面上那个按钮的位置也变了。恢复一个陈旧的检查点
     * 比重新开始更容易出错。
     */
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {

    init {
        require(maxAgeMs > 0) { "maxAgeMs 必须为正数，当前为 $maxAgeMs" }
    }

    /** 保存检查点。★ D-AW：每步都调 */
    suspend fun save(checkpoint: TaskCheckpoint) {
        store.save(checkpoint)
    }

    /** 任务正常结束：落一个 finished 检查点，使它不再参与恢复提示 */
    suspend fun markFinished(taskId: String, usage: BudgetUsage, nowMs: Long): TaskCheckpoint? {
        val existing = store.load(taskId) ?: return null
        val done = existing.copy(
            usage = usage,
            finished = true,
            savedAtMs = nowMs,
            completedSteps = existing.plannedActions.size,
        )
        store.save(done)
        return done
    }

    /**
     * 询问用户是否恢复上次未完成的任务。
     *
     * @param nowMs 当前时间
     * @return 恢复建议；无可恢复内容时返回 [ResumeOffer.Nothing]
     */
    suspend fun offerResume(nowMs: Long): ResumeOffer {
        val checkpoint = store.loadLatestIncomplete() ?: return ResumeOffer.Nothing

        val ageMs = nowMs - checkpoint.savedAtMs
        // ⚠️ 时钟可能因为用户改时间/NTP 校时而倒流，ageMs 会是负数。
        //    这种情况按"刚保存的"处理（ageMs = 0），而不是判定为"来自未来"而拒绝。
        val normalizedAge = ageMs.coerceAtLeast(0L)

        return when {
            normalizedAge > maxAgeMs -> ResumeOffer.TooOld(
                taskId = checkpoint.taskId,
                userInput = checkpoint.userInput,
                ageMs = normalizedAge,
                maxAgeMs = maxAgeMs,
            )

            !checkpoint.hasRemaining -> ResumeOffer.Nothing

            else -> ResumeOffer.Available(
                taskId = checkpoint.taskId,
                userInput = checkpoint.userInput,
                completedSteps = checkpoint.completedSteps,
                remainingSteps = checkpoint.remainingSteps,
                ageMs = normalizedAge,
                replanCount = checkpoint.replanCount,
            )
        }
    }

    /** 用户放弃恢复 */
    suspend fun discard(taskId: String) {
        store.delete(taskId)
    }

    /**
     * 恢复出的初始预算用量。
     *
     * ★ 从检查点**继承** usage，而不是从零开始 ——
     * 否则"中断恢复"会成为绕过预算的后门：
     * 用户（或某次系统杀进程）反复恢复，预算就永远用不完。
     *
     * 这一点没有检查点就实现不了，也说明了为什么
     * `usage` 必须是检查点的一部分。
     */
    fun restoreUsage(checkpoint: TaskCheckpoint): BudgetUsage = checkpoint.usage

    /**
     * 恢复出的重规划次数。
     *
     * 与预算同理：不继承的话，中断恢复能无限刷新重规划上限。
     */
    fun restoreReplanCount(checkpoint: TaskCheckpoint): Int = checkpoint.replanCount

    /**
     * 把已存的步骤名还原成动作枚举。
     *
     * @return 成功还原的列表；**任一解析失败即返回 null**
     *   —— 半个计划比没有计划更危险（会从一个错误的序号往下执行）
     */
    fun restorePlan(checkpoint: TaskCheckpoint): List<StepAction>? {
        val actions = ArrayList<StepAction>(checkpoint.plannedActions.size)
        for (name in checkpoint.plannedActions) {
            val action = StepAction.parseOrNull(name) ?: return null
            actions += action
        }
        return actions
    }

    companion object {
        /** 30 分钟 —— 见 [maxAgeMs] 的参数说明 */
        const val DEFAULT_MAX_AGE_MS = 30 * 60 * 1000L
    }
}

/** 恢复建议 */
sealed interface ResumeOffer {

    /** 没有可恢复的任务 */
    data object Nothing : ResumeOffer

    /** 有可恢复的任务 */
    data class Available(
        val taskId: String,
        val userInput: String,
        val completedSteps: Int,
        val remainingSteps: Int,
        val ageMs: Long,
        val replanCount: Int,
    ) : ResumeOffer

    /**
     * 有检查点但已过期。
     *
     * ⚠️ 这一支必须存在，而不是直接归入 [Nothing]。
     * 两者对用户的含义完全不同：
     * - `Nothing` = "上次没有任务没跑完"
     * - `TooOld`  = "上次有个任务没跑完，但已经过去太久了"
     *
     * 后者如果不说，用户会疑惑"我明明记得有个任务没做完"。
     */
    data class TooOld(
        val taskId: String,
        val userInput: String,
        val ageMs: Long,
        val maxAgeMs: Long,
    ) : ResumeOffer
}
