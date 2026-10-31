package com.pocketagent.agentui

/**
 * **完成反馈按钮**的状态机。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这需要一个状态机，而不是一个 `loading: Boolean`
 * ═══════════════════════════════════════════════════════════════
 *
 * "点一下就完成"的按钮有四种状态，用两个布尔表达不清：
 *
 * ```
 *   Idle ──点击──▶ Working ──成功──▶ Done（短暂显示，然后锁定）
 *                    │
 *                    └──失败──▶ Failed（可重试）
 * ```
 *
 * ⚠️ 用一个 `loading` 会丢掉三件重要的事：
 *
 *  1. **`Working` 与 `Done` 必须分开。** 它们的文案和颜色完全不同：
 *     "处理中"是灰色的、不可再点；"已完成"是强调色的、带勾的。
 *     合并成一个 true 会导致"完成之后看起来像还在转"。
 *  2. **`Done` 要能锁定。** 已经完成的事再点一次，最轻的后果是
 *     重复提交，最重的是把已完成的状态改回去。
 *  3. **`Failed` 必须能重试，且要知道"是不是同一次点击"。**
 *     用户点了两次、第一次失败第二次成功 —— 如果状态机只看
 *     "最后一次"，界面会闪回"失败"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一个具体的真机失败（写在这里避免重复踩）
 * ═══════════════════════════════════════════════════════════════
 *
 * 连点两次"完成"：第一次的请求还没回来，第二次又发一次。
 * 两次都成功 ⇒ 服务端收到两条完成记录；两次一成一败 ⇒ 界面显示
 * "失败"，但事情其实做成了。用户看到的就是"它说失败了，可明明好了"。
 *
 * ⇒ [begin] 在 `Working` 或 `Done` 时**直接拒绝**，返回 null。
 *   调用方拿到 null 就不该发请求。这是**纯函数层面的防重**，
 *   不依赖界面把按钮置灰（那种防护在快速点击下并不可靠）。
 *
 * ★ 它不碰 Android，所以整段都能离线测（见 `CompletionStateTest`）。
 */
object CompletionState {

    /**
     * 一次完成动作的完整状态。
     *
     * @param phase 当前阶段
     * @param attempt 第几次尝试（从 1 开始）。★ 它让"两次请求"可区分 ——
     *   见 [shouldApplyResult]。
     * @param error 失败原因；只在 [Phase.FAILED] 时有值
     */
    data class State(
        val phase: Phase = Phase.IDLE,
        val attempt: Int = 0,
        val error: String? = null,
    ) {
        /** 能不能点。 */
        val clickable: Boolean get() = phase == Phase.IDLE || phase == Phase.FAILED

        /**
         * 要不要显示"已完成"的视觉反馈。
         *
         * ★ 单独暴露而不是让调用方 `phase == DONE`：
         *   将来若加一个"完成但同步中"的中间态，这里改一处即可。
         */
        val showsDone: Boolean get() = phase == Phase.DONE

        /** 要不要显示转圈/处理中。 */
        val showsWorking: Boolean get() = phase == Phase.WORKING
    }

    enum class Phase {
        /** 可点。 */
        IDLE,

        /** 已点，等结果。**不可再点。** */
        WORKING,

        /** 完成。**锁定，不可再点。** */
        DONE,

        /** 失败，可重试。 */
        FAILED,
    }

    /** 初始状态。 */
    fun initial(): State = State()

    /**
     * 用户点了按钮。
     *
     * @return 新的状态 + 该发的尝试序号；**null = 不该发请求**
     *
     * ⚠️ 返回 null 的两种情况都要拦住：
     *   · `WORKING` —— 上一次还没回来（连点）
     *   · `DONE` —— 已经做完了（重复提交）
     *
     * ★ 用 `Int` 而不是 Boolean 表示"要发"：调用方要把这个序号带进
     *   异步结果，才能判断回来的是不是当前这一次（见 [shouldApplyResult]）。
     */
    fun begin(current: State): Pair<State, Int>? {
        if (!current.clickable) return null
        val next = current.attempt + 1
        return State(phase = Phase.WORKING, attempt = next, error = null) to next
    }

    /**
     * 请求回来了。
     *
     * @param attempt 发起时拿到的序号
     */
    fun succeed(current: State, attempt: Int): State {
        if (!shouldApplyResult(current, attempt)) return current
        return State(phase = Phase.DONE, attempt = current.attempt, error = null)
    }

    fun fail(current: State, attempt: Int, error: String): State {
        if (!shouldApplyResult(current, attempt)) return current
        return State(
            phase = Phase.FAILED,
            attempt = current.attempt,
            error = error.ifBlank { "没成功，可以再试一次" },
        )
    }

    /**
     * 这个结果**该不该被应用**。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★★ 这条防的是一个很隐蔽的竞态
     * ═══════════════════════════════════════════════════════════
     *
     * 场景：第 1 次请求超时（用户看到"失败"）→ 用户重试 →
     *       第 2 次还在飞的时候，**第 1 次的迟到响应回来了**，说失败。
     *
     * 如果无条件应用，界面会：
     *   第 2 次成功 → 显示"已完成" → 第 1 次的失败响应到达 → **闪回失败**
     *
     * 用户看到的是"它明明说好了，又变成失败了"。
     *
     * ⇒ 只有**序号等于当前 attempt** 的结果才能落地。
     *   迟到的那次应当被丢弃（它不是当前这一次的答案）。
     *
     * ⚠️ 另外 `DONE` 之后一律不应用：完成是一个**终态**，
     *   不该被任何迟到的失败拉回去。
     */
    fun shouldApplyResult(current: State, attempt: Int): Boolean {
        if (current.phase == Phase.DONE) return false
        if (current.phase != Phase.WORKING) return false
        return attempt == current.attempt
    }
}
