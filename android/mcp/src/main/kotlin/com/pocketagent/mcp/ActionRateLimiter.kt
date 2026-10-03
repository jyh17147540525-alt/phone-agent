package com.pocketagent.mcp

/**
 * 执行工具的频率闸 —— `android_tap` / `android_swipe` **共用同一个实例**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  两道闸，职责不同（数值参照 `:action` 与 `:safety` 的既定设计）
 * ═══════════════════════════════════════════════════════════════
 *
 * | 闸 | 位置 | 作用 | 数值 |
 * |---|---|---|---|
 * | 本类（拟人节奏闸） | 工具侧 | 防连点 / 防死循环，**先于**派发拒绝 | 20 次/分钟（同 `HumanizePolicy.MAX_ACTIONS_PER_MINUTE`） |
 * | `:safety` 安全硬闸 | `DefaultSafetyGuard` 第 5 步 | 兜底记录 + 审计 | 60 次/分钟（`DEFAULT_MAX_ACTIONS_PER_MINUTE`） |
 *
 * ⚠️ 20 与 60 是**刻意不同**的（见 `DefaultSafetyGuard` 的注释：安全硬闸必须
 *    ≥ 拟人节奏，否则正常任务会被安全层打断）。本类维护的计数会喂给安全上下文
 *    （[countInLastMinute] → `ActionSafetyQuery.actionsInLastMinute`），
 *    所以 `:safety` 那一步看到的是真实计数，而不是它自己数的。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么数值不直接从 `:action` / `:safety` 引
 * ═══════════════════════════════════════════════════════════════
 *
 * 那两个模块都依赖 `android.graphics`，本模块（纯 Kotlin）依赖不起 ——
 * 与所有端口的理由相同。**数值若漂移，以那两个模块为准改这里**；
 * 这是一份有意的、被注释钉住的副本，不是"忘了抽公共"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  语义
 * ═══════════════════════════════════════════════════════════════
 *
 * - 「动作」= **被放行并即将派发**的调用（被安全关卡拒掉的不算，也不占额度）；
 * - 采用**滚动 60 秒窗口**（不是整分钟清零）；
 * - [MIN_GAP_MS] 是"防连点"下限：两次派发之间必须有这个间隔 ——
 *   它挡的是"死循环里的高频重试"，正常的一次点击→screen_read→再点击
 *   天然远大于这个间隔。
 */
class ActionRateLimiter(
    private val maxActionsPerMinute: Int = MAX_ACTIONS_PER_MINUTE,
    private val minGapMs: Long = MIN_GAP_MS,
    /** 时钟注入 —— 测试里换成假钟，生产用系统钟。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val recent = ArrayDeque<Long>()

    sealed interface Decision {

        data object Allowed : Decision

        /** @param retryAfterMs 距可以再次执行的等待时间（毫秒） */
        data class Denied(val retryAfterMs: Long, val reason: String) : Decision
    }

    /** 供安全上下文喂计数（滚动窗口内的已派发动作数）。不清空、不占额。 */
    @Synchronized
    fun countInLastMinute(): Int {
        prune(clock())
        return recent.size
    }

    /**
     * 尝试占用一次动作额度。
     *
     * ⚠️ 放行即**记录** —— 调用方必须在"确定要派发"的紧前一步调用它，
     *    不要在参数校验/安全判定之前调用（否则被关卡拒掉的调用会白占额度，
     *    表现为"安全策略拦了几次之后正常操作也被限流"）。
     */
    @Synchronized
    fun tryAcquire(): Decision {
        val now = clock()
        prune(now)

        val last = recent.lastOrNull()
        if (last != null && now - last < minGapMs) {
            return Decision.Denied(
                retryAfterMs = minGapMs - (now - last),
                reason = "与上一次操作的间隔过短（防连点保护）。",
            )
        }
        if (recent.size >= maxActionsPerMinute) {
            val oldest = recent.first()
            return Decision.Denied(
                retryAfterMs = WINDOW_MS - (now - oldest),
                reason = "最近一分钟内的操作次数已达上限（每分钟 $maxActionsPerMinute 次）。",
            )
        }
        recent.addLast(now)
        return Decision.Allowed
    }

    private fun prune(now: Long) {
        while (recent.isNotEmpty() && now - recent.first() >= WINDOW_MS) {
            recent.removeFirst()
        }
    }

    companion object {
        /** 同 `HumanizePolicy.MAX_ACTIONS_PER_MINUTE`（`:action`）。 */
        const val MAX_ACTIONS_PER_MINUTE: Int = 20

        /** 防连点下限。比 `HumanizePolicy.ACTION_INTERVAL_RANGE` 的下沿（300ms）略松。 */
        const val MIN_GAP_MS: Long = 250

        const val WINDOW_MS: Long = 60_000
    }
}
