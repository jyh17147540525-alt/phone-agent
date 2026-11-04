package com.pocketagent.rootlogic

/**
 * **屏幕租约** —— 普通模式干活时，向用户"借"屏幕的那套规矩。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要"租"，而不是"抢"
 * ═══════════════════════════════════════════════════════════════
 *
 * 没有 root 就只能靠无障碍，而无障碍只看得见**当前窗口**。所以要在别的
 * 应用里点一下，就得先把那个应用切到前台 —— 从这一刻起，这台手机的屏幕
 * 是**共用的**：agent 在里面点，用户也在里面点。
 *
 * 共用最坏的结果不是"点错"，是**用户不知道自己在和谁抢**：
 * 他想滑一下通知，agent 顺手把通知点了；他以为手机卡了，其实是模型在点。
 *
 * ⇒ 所以占屏期间必须有租约，且规矩只有一条最重要：
 *   **用户的手永远优先。** 检测到触摸就立刻让开，用户松手一会儿再继续。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"让开"之后要自动回来
 * ═══════════════════════════════════════════════════════════════
 *
 * 每次都要用户点"继续"会把 20 步的任务变成 20 次确认 —— 那还不如不做。
 * 所以设计成：手松开 [_grace] 之后自动收回；但用户明确点了"我要用手机"
 * （[YieldReason.USER_STOP]）就**不再自动回来**，等他真不要了再说。
 *
 * 这不是妥协，是分寸：**碰一下屏幕是"我在看"，按停是"我要用"。**
 *
 * ⚠️ 纯逻辑、可注入时钟：这套规矩全是时间边界，只有能控制时间才测得准
 *    （和 `PrivilegeClient` 的 id 配对同一个理由 —— 边界不测就是猜）。
 */
sealed interface LeaseState {

    /** 没借。 */
    data object Idle : LeaseState

    /** 借到了，agent 可以动手。 */
    data class Held(val since: Long, val expiresAt: Long, val reason: String) : LeaseState

    /** 让开了：用户在用手，或者被系统打断。 */
    data class Yielded(val since: Long, val because: YieldReason, val reason: String) : LeaseState

    /** 到期了：任务该收尾，别再动。 */
    data class Expired(val at: Long, val reason: String) : LeaseState
}

/** 为什么让开。 */
enum class YieldReason(val label: String) {

    /** 检测到用户的手指落在屏幕上。 */
    USER_TOUCH("你正在用手机"),

    /** 用户明确要求停下。 */
    USER_STOP("你让任务停一下"),

    /** 前台应用被别人换走了（来电、通知、用户切走）。 */
    APP_LEFT("前台应用被切换"),

    /** 借太久，超时收回。 */
    TIMEOUT("占屏时间到上限"),
}

/**
 * 租约。
 *
 * @param clock 注入时钟（测试用；生产传 `System::currentTimeMillis`）
 * @param holdMillis 单次最长占用；到点转 [LeaseState.Expired]
 * @param graceMillis 用户松手后多久自动回来
 */
class ScreenLease(
    private val clock: () -> Long,
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
) {

    var state: LeaseState = LeaseState.Idle
        private set

    /** 用户最后一次碰屏的时刻（没碰过是 null）。 */
    private var lastTouchAt: Long? = null

    /**
     * 让开的时刻。
     *
     * ★ 单独存一份，因为"让开"不一定来自触摸：[YieldReason.APP_LEFT]
     *   是来电/通知/用户切走，那时 [lastTouchAt] 是 null，用触摸时间
     *   当锚点会让租约永远回不来 —— 一次来电就把任务卡死。
     */
    private var yieldedAt: Long = 0L

    /**
     * 这次租约的**总**截止时刻。
     *
     * ★ 让开不延长租约：用户碰屏三次，不代表可以借三次各 90 秒。
     *   没有这个总闸，"让开一会儿"就变成了无限续期 —— 用户会发现自己
     *   的手机被一个说"马上就好"的东西占了一下午。
     */
    private var deadline: Long = 0L

    /** 让开之后用户是否明确按了停。 */
    private var userStopped = false

    /**
     * 申请占用。
     *
     * ★ 用户在用手时**不抢**：返回当前的 [LeaseState.Yielded]，
     *   调用方据此告诉模型"等一下"，而不是硬点。
     */
    fun acquire(reason: String): LeaseState {
        val now = clock()
        when (val s = state) {
            is LeaseState.Held -> return s
            is LeaseState.Yielded -> {
                // ★ 让开之后再申请，恢复的是**原来那份**租约，不是新的一份。
                //   否则"用户碰一下 → 重新计时"就成了无限续期。
                if (now >= deadline) {
                    state = LeaseState.Expired(deadline, s.reason)
                    return state
                }
                if (!canResume(now)) return s
                state = LeaseState.Held(since = now, expiresAt = deadline, reason = s.reason)
                return state
            }
            else -> Unit
        }
        lastTouchAt = null
        userStopped = false
        deadline = now + holdMillis
        state = LeaseState.Held(since = now, expiresAt = deadline, reason = reason)
        return state
    }

    /** 主动交还（任务结束、或切到 root 路径时）。 */
    fun release() {
        state = LeaseState.Idle
        lastTouchAt = null
        userStopped = false
        deadline = 0L
    }

    /** 用户的手指落在屏幕上。 */
    fun onUserTouch(): LeaseState {
        val now = clock()
        lastTouchAt = now
        yieldedAt = now
        val held = state as? LeaseState.Held ?: return state
        state = LeaseState.Yielded(since = now, because = YieldReason.USER_TOUCH, reason = held.reason)
        return state
    }

    /** 用户按了"我要用手机"。 */
    fun onUserStop(): LeaseState {
        val now = clock()
        userStopped = true
        yieldedAt = now
        val reason = (state as? LeaseState.Held)?.reason ?: (state as? LeaseState.Yielded)?.reason ?: ""
        state = LeaseState.Yielded(since = now, because = YieldReason.USER_STOP, reason = reason)
        return state
    }

    /** 前台应用被换走了。 */
    fun onAppLeft(): LeaseState {
        val now = clock()
        yieldedAt = now
        val reason = (state as? LeaseState.Held)?.reason ?: ""
        if (state is LeaseState.Idle) return state
        state = LeaseState.Yielded(since = now, because = YieldReason.APP_LEFT, reason = reason)
        return state
    }

    /**
     * 推进时间。
     *
     * ★ 顺序有意义：先判超时，再判能不能回来。反过来会出现
     *   "已经过期 30 秒又自己复活了"这种鬼故事。
     */
    fun tick(): LeaseState {
        val now = clock()
        val s = state
        if (s is LeaseState.Idle) return s
        // 总时长上限对"占着"和"让开"都生效
        if (now >= deadline) {
            state = LeaseState.Expired(deadline, (s as? LeaseState.Held)?.reason ?: (s as? LeaseState.Yielded)?.reason ?: "")
            return state
        }
        if (s is LeaseState.Yielded) {
            if (!canResume(now)) return s
            state = LeaseState.Held(since = now, expiresAt = deadline, reason = s.reason)
        }
        return state
    }

    /** 现在能不能动手。 */
    fun canAct(): Boolean {
        val s = state
        return s is LeaseState.Held && clock() < deadline
    }

    /** 不能动手时说一句为什么（模型与界面共用这句话）。 */
    fun blockedReason(): String? = when (val s = state) {
        is LeaseState.Held -> if (clock() >= deadline) "占屏已到期，任务该收尾了" else null
        is LeaseState.Yielded -> when (s.because) {
            YieldReason.USER_TOUCH -> "你正在用手机 —— 手松开一会儿我就接着做"
            YieldReason.USER_STOP -> "你让任务停一下 —— 需要继续时再叫我"
            YieldReason.APP_LEFT -> "前台被切走了，要重新把目标应用推回来"
            YieldReason.TIMEOUT -> "占屏时间到上限了"
        }
        is LeaseState.Expired -> "占屏已到期，任务该收尾了"
        LeaseState.Idle -> "还没申请占屏"
    }

    /**
     * 能不能自己回来。
     *
     * ★ 用户按停就是不回来 —— 这条如果做错，用户会觉得"我让它停它不听"。
     */
    private fun canResume(now: Long): Boolean {
        if (userStopped) return false
        // 触摸优先（手还在屏幕上），否则用"让开的时刻"
        val anchor = maxOf(lastTouchAt ?: 0L, yieldedAt)
        return now - anchor >= graceMillis
    }

    companion object {
        const val DEFAULT_HOLD_MILLIS: Long = 90_000

        /** 1.5 秒：够用户松手、又不至于让他干等。 */
        const val DEFAULT_GRACE_MILLIS: Long = 1_500
    }
}
