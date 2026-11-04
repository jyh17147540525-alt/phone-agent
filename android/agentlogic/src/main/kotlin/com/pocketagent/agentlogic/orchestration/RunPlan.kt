package com.pocketagent.agentlogic.orchestration

/**
 * 一次任务跑起来的**最小数据结构**：目标、计划、每一步的期望、预算。
 *
 * ## 为什么要显式的"期望"
 *
 * 自主 agent 最常见的失败不是"做错了"，而是**"做完了但自己不知道"**：
 * 点了一下、界面上什么都没变，它当成成功了，接着往下走，最后交一份编出来的结果。
 * 所以每一步都带一个 [Expectation]：做完之后拿到的屏幕摘要必须满足它，否则算失败。
 *
 * 验收型 agent（Claude Code、Codex）都是这么做的：写代码 → 跑测试 → 看输出，
 * 而不是"写完就当好了"。手机上跑测试的位置换成了"屏幕摘要对不对"。
 */

/** 屏幕摘要：验证只依赖这一小块，不依赖完整截图。 */
data class ScreenDigest(
    /** 当前顶层包名，比如 `com.tencent.mm`。 */
    val packageName: String? = null,
    /** 屏幕上所有可见文字（已去重、已裁剪）。 */
    val texts: List<String> = emptyList(),
    /** 可点节点数量。用来判断"页面加载完了没有"。 */
    val nodeCount: Int = 0,
    /** 读回来的键值（设置项、属性）。 */
    val values: Map<String, String> = emptyMap(),
) {
    fun contains(text: String): Boolean = texts.any { it.contains(text) }

    companion object {
        val EMPTY = ScreenDigest()
    }
}

/** 这一步做完应该看到什么。 */
sealed interface Expectation {

    /** 不校验 —— 只适用于"观察/记录"这类没有可验证结果的步骤。 */
    data object None : Expectation

    /** 屏幕上应该出现这段文字。 */
    data class TextVisible(val text: String) : Expectation

    /** 应该停在某个应用里。 */
    data class PackageIs(val packageName: String) : Expectation

    /** 可点节点至少这么多个（页面渲染完了）。 */
    data class AtLeastNodes(val count: Int) : Expectation

    /** 读回来的值应该等于它。 */
    data class ValueIs(val key: String, val value: String) : Expectation

    /**
     * 校验。返回 null 表示通过，返回字符串表示**为什么没通过**（这句会进日志与用户可见的说明）。
     *
     * 摘要为空时一律不通过 —— "没拿到摘要"和"摘要里没有这段文字"是两件事，
     * 但都不能算成功：前者说明读屏失败，后者说明界面没变。
     */
    fun unmetOn(digest: ScreenDigest?): String? = when (this) {
        is None -> null
        is TextVisible -> when {
            digest == null -> "没读到屏幕，无法确认「$text」是否出现"
            digest.contains(text) -> null
            else -> "屏幕上没有「$text」"
        }
        is PackageIs -> when {
            digest == null -> "没读到屏幕，无法确认当前应用"
            digest.packageName == packageName -> null
            else -> "当前应用是 ${digest.packageName ?: "未知"}，不是 $packageName"
        }
        is AtLeastNodes -> when {
            digest == null -> "没读到屏幕，无法确认页面是否加载完"
            digest.nodeCount >= count -> null
            else -> "页面上只有 ${digest.nodeCount} 个可点节点，少于 $count，可能还没加载完"
        }
        is ValueIs -> when {
            digest == null -> "没读回值，无法确认 $key"
            digest.values[key] == value -> null
            else -> "$key 现在是 ${digest.values[key] ?: "（读不到）"}，不是 $value"
        }
    }
}

/** 计划里的一步。 */
data class PlannedStep(
    /** 稳定标识，用于日志与"卡在同一步"的判断。 */
    val id: String,
    /** 工具名，必须能在 [ToolCatalog] 里查到。 */
    val tool: String,
    /** 参数。值与 `:mcp` 的 `inputSchema` 对齐，这里只做透传。 */
    val args: Map<String, String> = emptyMap(),
    /** 做完的验收条件。 */
    val expect: Expectation = Expectation.None,
    /** 给用户看的这一步在干什么。 */
    val note: String = "",
) {
    init {
        require(id.isNotBlank()) { "步骤 id 不能为空" }
        require(tool.matches(Regex("""^[a-z][a-z0-9_]{2,47}$"""))) { "工具名不合法：$tool" }
    }

    /**
     * 步骤签名：工具 + 参数（按 key 排序）。
     *
     * 用来识别"打转"：同一个签名反复出现说明无论成功失败它都没推进任何东西。
     */
    val signature: String
        get() = buildString {
            append(tool)
            for ((k, v) in args.toSortedMap()) append('|').append(k).append('=').append(v)
        }
}

/** 一份计划。 */
data class RunPlan(val goal: String, val steps: List<PlannedStep>) {
    init {
        require(goal.isNotBlank()) { "目标不能为空" }
        require(steps.isNotEmpty()) { "计划里一步都没有 —— 这种情况应当返回 Refuse 而不是空计划" }
    }

    val tools: List<String> get() = steps.map { it.tool }
}

/** 预算。超了就停，不无限跑。 */
data class RunBudget(
    /** 最多执行多少步（含重试与重规划产生的新步）。 */
    val maxSteps: Int = 24,
    /** 最多重规划几次。 */
    val maxReplans: Int = 2,
    /** 总时长上限。 */
    val maxDurationMs: Long = 180_000,
    /** 同一个步骤签名最多出现几次。 */
    val maxRepeats: Int = 3,
    /** 单步允许自动重试几次（只有幂等工具才会真的重试）。 */
    val maxRetries: Int = 1,
) {
    init {
        require(maxSteps in 1..500) { "maxSteps 越界：$maxSteps" }
        require(maxReplans in 0..10) { "maxReplans 越界：$maxReplans" }
        require(maxDurationMs in 1_000..3_600_000) { "maxDurationMs 越界：$maxDurationMs" }
        require(maxRepeats in 1..20) { "maxRepeats 越界：$maxRepeats" }
        require(maxRetries in 0..5) { "maxRetries 越界：$maxRetries" }
    }
}

/** 单步的执行结局。 */
enum class StepStatus(val label: String) {
    DONE("完成"),
    FAILED("失败"),
    /** 被安全/档位闸门拦下 —— **这是最终结论，不许重试**。 */
    REFUSED("被拦下"),
    /** 超时。幂等工具可以重试一次。 */
    TIMEOUT("超时"),
}

/** 单步执行报告。 */
data class StepOutcomeReport(
    val status: StepStatus,
    val detail: String,
    val digest: ScreenDigest? = null,
)

/** 记一笔执行历史。报告与"卡住"的判断都靠它。 */
data class StepAttempt(
    val stepId: String,
    val tool: String,
    val signature: String,
    val status: StepStatus,
    val detail: String,
    val elapsedMs: Long,
    val retries: Int = 0,
    val verified: Boolean? = null,
) {
    fun describe(): String = buildString {
        append(stepId).append(' ').append(tool).append(" → ").append(status.label)
        if (retries > 0) append("（重试 ").append(retries).append(" 次）")
        if (verified == false) append("（验收没通过）")
        if (detail.isNotBlank()) append("：").append(detail)
    }
}

/** 整个任务的结局。 */
enum class RunPhase(val label: String) {
    DONE("做完了"),
    /** 规划阶段就拒绝了 —— 这件事不该做（比如涉及越权）。 */
    REFUSED("不做"),
    /** 缺信息，问用户。 */
    NEED_INFO("要你补一句"),
    FAILED("没做成"),
    STOPPED("你叫停的"),
    /** 需要用户点一次确认才能继续（钱、删除、停用）。 */
    AWAITING_CONFIRMATION("等你确认"),
    OUT_OF_BUDGET("超出预算停了"),
    LOOPING("在打转，停了"),
}

/** 任务报告。 */
data class RunReport(
    val phase: RunPhase,
    val goal: String,
    val plan: RunPlan?,
    val attempts: List<StepAttempt>,
    val summary: String,
    /** [RunPhase.AWAITING_CONFIRMATION] 时要确认的那一步。 */
    val awaitingStep: PlannedStep? = null,
    /** [RunPhase.NEED_INFO] 时要问的问题。 */
    val question: String? = null,
) {
    val ok: Boolean get() = phase == RunPhase.DONE
    val steps: Int get() = attempts.size
}
