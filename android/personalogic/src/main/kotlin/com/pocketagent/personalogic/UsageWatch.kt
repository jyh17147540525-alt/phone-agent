package com.pocketagent.personalogic

/**
 * **连续使用时长看护** —— 到点该提醒用户歇一下。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：这是一条**没实现的合规要求**
 * ═══════════════════════════════════════════════════════════════
 *
 * `docs/协作/TO-CLAUDE.md` 把「连续使用超 2 小时 → 必须提醒」列为
 * "合规四件"之一（年龄识别 / 危机干预 / 2 小时提醒 / 安全评估）。
 *
 * ⚠️ 而代码里**零实现** —— 没有任何使用时长追踪。
 *
 * 这一条和别的合规项（年龄识别、危机干预）性质不同：
 * 那两条需要产品决策，这一条**纯粹是时间计算**，可以完全离线做完。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条设计取舍
 * ═══════════════════════════════════════════════════════════════
 *
 * **① "连续"要有断点判据**
 *    用户关掉应用三小时再回来，那**不是**"连续用了三小时"。
 *    ⇒ 两次活动之间隔超过 [IDLE_TOLERANCE_MS]，视为新的一段。
 *
 *    ⚠️ 判据不能是"应用是否在前台"：那样切出去看一眼消息就重置了，
 *       而"连续使用"的本质是"人一直在这个产品里"。
 *       用一个**较宽的**空档阈值（10 分钟）更贴近真实体验。
 *
 * **② 提醒要能重复，但不能烦**
 *    只提醒一次的话，用户点掉之后又用了两小时，就再没人管了。
 *    每次都提醒的话，那是在骚扰。
 *    ⇒ 首次在 2 小时，之后**每小时一次**。频率随使用时长变长而保持不变，
 *      不递增（递增会变成惩罚）。
 *
 * **③ 时钟可能倒流**
 *    用户改系统时间、NTP 校时都会让 `now < lastSeen`。
 *    按"刚活动过"处理（差值为 0），而不是判定为"来自未来"而拒绝 ——
 *    与 `TaskCheckpointManager.offerResume` 同一条纪律。
 */
object UsageWatch {

    /**
     * 两次活动之间超过这个间隔，就算**新的一段**。
     *
     * ★ 10 分钟：足够容纳"切出去回个消息 / 去倒杯水 / 接个电话"，
     *   又不至于把"上午用了一次、下午又用一次"算成连续。
     */
    const val IDLE_TOLERANCE_MS: Long = 10 * 60_000L

    /** 首次提醒门槛：2 小时。规章原文就是这个数。 */
    const val FIRST_REMIND_MS: Long = 2 * 60 * 60_000L

    /** 之后每隔这么久提醒一次。 */
    const val REPEAT_REMIND_MS: Long = 60 * 60_000L

    /**
     * 看护状态。
     *
     * @param startedAtMs 这一段的起点。null = 还没开始（或刚被重置）
     * @param lastActiveMs 最后一次活动时刻
     * @param lastRemindAtMs 上次提醒的时刻。null = 还没提醒过
     */
    data class State(
        val startedAtMs: Long? = null,
        val lastActiveMs: Long = 0L,
        val lastRemindAtMs: Long? = null,
    ) {
        /** 这一段的时长（毫秒）。没开始就是 0。 */
        fun elapsed(nowMs: Long): Long =
            if (startedAtMs == null) 0L else (nowMs - startedAtMs).coerceAtLeast(0L)
    }

    /**
     * 记一次活动。
     *
     * @param nowMs 当前时刻
     * @return 更新后的状态。空档超过 [IDLE_TOLERANCE_MS] 时会**重置起点**。
     */
    fun onActive(state: State, nowMs: Long): State {
        val started = state.startedAtMs
        // 还没开始 → 现在开始
        if (started == null) {
            return State(startedAtMs = nowMs, lastActiveMs = nowMs, lastRemindAtMs = null)
        }

        // ⚠️ 时钟倒流：按"刚活动过"处理，不重置也不崩。
        //    重置会白丢用户已经积累的时长 —— 而他什么都没做错。
        val gap = nowMs - state.lastActiveMs
        if (gap < 0L) {
            return state.copy(lastActiveMs = maxOf(state.lastActiveMs, nowMs))
        }

        // 空档太久 ⇒ 新的一段。提醒记录也一并清掉：
        // 新的一段当然应该重新有机会被提醒。
        if (gap > IDLE_TOLERANCE_MS) {
            return State(startedAtMs = nowMs, lastActiveMs = nowMs, lastRemindAtMs = null)
        }

        return state.copy(lastActiveMs = nowMs)
    }

    /**
     * 现在该不该提醒。
     *
     * @return null = 不提醒
     */
    fun dueReminder(state: State, nowMs: Long): Reminder? {
        val started = state.startedAtMs ?: return null
        val elapsed = (nowMs - started).coerceAtLeast(0L)

        if (elapsed < FIRST_REMIND_MS) return null

        val last = state.lastRemindAtMs
        if (last != null) {
            // ⚠️ 间隔用 `now - last` 而不是"距起点多久"：
            //    用户的注意力在"距上次提醒过了多久"，而不是"总共用了多久"。
            val sinceLast = nowMs - last
            if (sinceLast < REPEAT_REMIND_MS) return null
            // 时钟倒流时 sinceLast 为负 ⇒ 不提醒（上面已经拦住）
        }

        return Reminder(
            elapsedMs = elapsed,
            // 第一次提醒和后续提醒的措辞不同：第一次是"你待了很久"，
            // 后续是"还在"——后者更轻，不重复说教。
            isFirst = last == null,
        )
    }

    /** 记下"已经提醒过了"。 */
    fun onReminded(state: State, nowMs: Long): State = state.copy(lastRemindAtMs = nowMs)

    /**
     * 一次提醒。
     *
     * ★ [elapsedMs] 给出去是为了让调用方**用具体数字说话** ——
     *   "两个多小时了" 比 "很久了" 更有分量，也更不像系统的自动话术。
     *
     * ⚠️ 这里**不含文案**：文案要按场景变（旁白 / 系统提示 / 助手口气），
     *   而那是产品层的决定。1970-01-01 与 1970-01-01 之间放一处，
     *   以后改文案不用动这个文件。
     */
    data class Reminder(
        val elapsedMs: Long,
        /** 是不是这一段里的第一次提醒。 */
        val isFirst: Boolean,
    ) {
        /** 整小时数，至少 2。用于"你已经待了 N 个小时"。 */
        val wholeHours: Int
            get() = (elapsedMs / 3_600_000L).toInt().coerceAtLeast(2)
    }
}
