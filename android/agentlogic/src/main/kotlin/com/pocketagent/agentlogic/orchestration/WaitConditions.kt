package com.pocketagent.agentlogic.orchestration

/**
 * "等界面变成某个样子" —— 把等待从**模型往返**里拿出来，交给代码。
 *
 * ## 为什么值得为它单独写一层
 *
 * 手机上大量动作之后都要等：点搜索等结果、滑到底等加载、开应用等首页。
 * 现在的做法是把这件事交给模型：它调一次 `screen_read`，看到"还在加载"，
 * 再调一次 —— 每一轮都是 800–3000ms 的模型往返，而真正变化的界面往往在 300ms 内就好了。
 *
 * 把等待做成条件 + 超时，由执行侧循环读摘要（一次 150–400ms，不经过模型），
 * 省下的是**往返**，不是读屏次数。
 *
 * ## 为什么超时是硬上限
 *
 * 条件写错（比如等一段永远不出现的文字）时，没有上限的等待等于任务挂死。
 * 上限 20 秒来自手机上的经验：一次页面切换超过 20 秒，多半是网络断了或应用卡死，
 * 这时候该把"可能卡住"这件事告诉用户，而不是继续等。
 */
sealed interface WaitCondition {

    /** 等多久算超时。 */
    val timeoutMs: Long

    /**
     * 判一次。
     *
     * @param digest 这一次读到的摘要（null = 没读到）
     * @param previous 上一次读到的摘要（`Stable` 用得上）
     * @param elapsedMs 从开始等算起过了多久
     */
    fun check(digest: ScreenDigest?, previous: ScreenDigest?, elapsedMs: Long): WaitOutcome

    /** 屏幕上要出现这段文字。 */
    data class TextAppears(val text: String, override val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : WaitCondition {
        init { requireTimeout(timeoutMs) }
        override fun check(digest: ScreenDigest?, previous: ScreenDigest?, elapsedMs: Long): WaitOutcome =
            settle(digest != null && digest.contains(text), elapsedMs, timeoutMs)
    }

    /** 屏幕上这段文字要消失（比如"加载中…"）。 */
    data class TextDisappears(val text: String, override val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : WaitCondition {
        init { requireTimeout(timeoutMs) }
        override fun check(digest: ScreenDigest?, previous: ScreenDigest?, elapsedMs: Long): WaitOutcome =
            // 读不到时不当作"消失了"：那会把读屏故障变成"加载完成"
            settle(digest != null && !digest.contains(text), elapsedMs, timeoutMs)
    }

    /** 要停在某个应用里。 */
    data class PackageIs(val packageName: String, override val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : WaitCondition {
        init { requireTimeout(timeoutMs) }
        override fun check(digest: ScreenDigest?, previous: ScreenDigest?, elapsedMs: Long): WaitOutcome =
            settle(digest?.packageName == packageName, elapsedMs, timeoutMs)
    }

    /** 某个值要变成这样（设置项、属性）。 */
    data class ValueIs(
        val key: String,
        val value: String,
        override val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) : WaitCondition {
        init { requireTimeout(timeoutMs) }
        override fun check(digest: ScreenDigest?, previous: ScreenDigest?, elapsedMs: Long): WaitOutcome =
            settle(digest?.values?.get(key) == value, elapsedMs, timeoutMs)
    }

    /**
     * 界面要**稳定下来**：连续两次读到的摘要一样。
     *
     * ⚠️ 这是"看起来不动了"，不是"加载完了"。所以它只适合"等动画/滚动停下"，
     * 不适合等数据：一个还在转圈的加载页同样可以两次读到一样。
     */
    data class Stable(override val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : WaitCondition {
        init { requireTimeout(timeoutMs) }
        override fun check(digest: ScreenDigest?, previous: ScreenDigest?, elapsedMs: Long): WaitOutcome {
            val same = digest != null && previous != null &&
                digest.packageName == previous.packageName &&
                digest.nodeCount == previous.nodeCount &&
                digest.texts == previous.texts
            return settle(same, elapsedMs, timeoutMs)
        }
    }

    companion object {
        /** 默认 8 秒：一次页面切换通常 1–3 秒，留一倍余量。 */
        const val DEFAULT_TIMEOUT_MS = 8_000L

        /** 硬上限 20 秒 —— 见类注释。 */
        const val MAX_TIMEOUT_MS = 20_000L

        /** 下限 200ms：比这更短的等待没有意义（一次读屏就要 150–400ms）。 */
        const val MIN_TIMEOUT_MS = 200L

        internal fun requireTimeout(timeoutMs: Long) {
            require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
                "等待超时必须在 $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS 毫秒，实际：$timeoutMs"
            }
        }

        /**
         * 三态收敛：成立就是 MET；不成立但还没超时是 PENDING；不成立且超时了是 TIMED_OUT。
         *
         * ⚠️ 顺序不能反：先判成立再判超时。反过来的话，"刚好在超时那一刻成立"会被判成失败。
         */
        internal fun settle(met: Boolean, elapsedMs: Long, timeoutMs: Long): WaitOutcome = when {
            met -> WaitOutcome.MET
            elapsedMs >= timeoutMs -> WaitOutcome.TIMED_OUT
            else -> WaitOutcome.PENDING
        }
    }
}

/** 等待的三种结局。 */
enum class WaitOutcome(val label: String) {
    MET("等到了"),
    PENDING("还在等"),
    TIMED_OUT("等超时了"),
}
