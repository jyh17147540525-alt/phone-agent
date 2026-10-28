package com.pocketagent.legacy

/**
 * **主动消息** —— 角色什么时候可以自己来找你说话。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "app 加入通知功能，角色可以自行给用户发消息，不用等用户发消息后
 *  才能被动回复。"
 *
 * ⚠️ 这件事**很容易做坏**，坏法和"坏角色"一模一样：
 *   · 一天弹八次 —— 那不是惦记，那是推送营销；
 *   · 凌晨三点弹 —— 用户只会去系统设置里把通知关掉，**再也不开**；
 *   · 每次都同一句 —— 一周之后就变成"系统通知"，不再像一个人。
 *
 * ⇒ 所以这里只做**什么时候可以**，不做"发什么"。
 *   判据是纯逻辑、可离线钉住；文案由模型按人设写（见 `:app` 的调用方）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条硬门槛，全部是"宁可不发"
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **关系够深**：聊过足够多轮才允许主动。刚认识就发，是骚扰。
 * 2. **真的隔了一会儿**：至少 [MIN_IDLE_MS]。刚说完话就追一条，是黏人。
 * 3. **不在深夜**：[QUIET_START_HOUR]~[QUIET_END_HOUR] 一律不发，
 *    哪怕关系再深 —— 那是"体贴"和"没分寸"的分界线。
 */
object ProactivePlanner {

    /** 至少聊过这么多轮，才允许主动开口。 */
    const val MIN_TURNS: Int = 12

    /** 用户最后一次说话之后，至少隔这么久。 */
    const val MIN_IDLE_MS: Long = 3 * 60 * 60 * 1000L // 3 小时

    /** 两次主动消息之间至少隔这么久（同一天最多两三条）。 */
    const val MIN_GAP_MS: Long = 5 * 60 * 60 * 1000L // 5 小时

    /** 免打扰时段：这个小时起（含）到那个小时止（不含）不发。 */
    const val QUIET_START_HOUR: Int = 23
    const val QUIET_END_HOUR: Int = 8

    /** 主动消息想要什么口气 —— 调用方据此写不同的提示词。 */
    enum class Tone(val label: String) {
        /** 隔了半天：随口一句，不索取回应 */
        CASUAL("随口一提"),

        /** 隔了一两天：有点想念，但不缠人 */
        MISSING("有点想你"),

        /** 很久没说话：只报个平安，把决定权交给对方 */
        CHECK_IN("打个招呼"),
    }

    /** 输入：全部是**可观测的事实**，不含"心情"这种猜不出来的东西。 */
    data class Facts(
        val nowMs: Long,
        /** 用户最后一次发言的时间；0 = 从没说过话 */
        val lastUserAtMs: Long,
        /** 上一次主动消息的时间；0 = 从没发过 */
        val lastProactiveAtMs: Long,
        /** 累计对话轮次 */
        val turnCount: Int,
        /** 时区偏移（小时），用于免打扰判断。默认东八区 */
        val utcOffsetHours: Int = 8,
    )

    /** 结论。**只有 [Send] 才发**，其余都带一句原因以便如实记录。 */
    sealed interface Decision {
        data class Send(val tone: Tone) : Decision
        data class Skip(val reason: String) : Decision
    }

    /**
     * 判断现在能不能主动开口。
     *
     * ⚠️ 顺序有意：先判"关系"，再判"时机"，最后判"免打扰"。
     *    这样日志里的第一个原因就是最根本的那个 ——
     *    "聊得还不够"和"现在太晚了"是两件完全不同的事，混着报没法排查。
     */
    fun decide(facts: Facts): Decision {
        if (facts.turnCount < MIN_TURNS) {
            return Decision.Skip("还在互相认识（${facts.turnCount}/$MIN_TURNS 轮）")
        }
        if (facts.lastUserAtMs <= 0L) {
            return Decision.Skip("还没说过话")
        }
        val idle = facts.nowMs - facts.lastUserAtMs
        if (idle < MIN_IDLE_MS) {
            return Decision.Skip("刚聊过 ${idle / 60_000} 分钟，不去打扰")
        }
        if (facts.lastProactiveAtMs > 0L) {
            val gap = facts.nowMs - facts.lastProactiveAtMs
            if (gap < MIN_GAP_MS) {
                return Decision.Skip("上一次主动是 ${gap / 3_600_000} 小时前，太密了")
            }
        }
        val hour = hourOf(facts.nowMs, facts.utcOffsetHours)
        if (isQuietHour(hour)) {
            return Decision.Skip("现在是 $hour 点，免打扰时段")
        }
        return Decision.Send(toneFor(idle))
    }

    /**
     * 口气按"隔了多久"分档。
     *
     * ★ 用时间而不是随机数：同一段空白每次跑都必须得到同一个口气。
     *   随机会让"今天她怎么这么冷淡/这么黏"变成不可解释的抖动。
     */
    fun toneFor(idleMs: Long): Tone = when {
        idleMs < 12 * 60 * 60 * 1000L -> Tone.CASUAL
        idleMs < 3 * 24 * 60 * 60 * 1000L -> Tone.MISSING
        else -> Tone.CHECK_IN
    }

    /** 某个时刻在 [utcOffsetHours] 时区是几点（0..23）。 */
    fun hourOf(nowMs: Long, utcOffsetHours: Int = 8): Int {
        val shifted = nowMs + utcOffsetHours * 3_600_000L
        return ((shifted / 3_600_000L) % 24L).toInt().let { if (it < 0) it + 24 else it }
    }

    /**
     * 是不是免打扰时段。
     *
     * ⚠️ 这个区间**跨零点**（23 点到次日 8 点），所以要分两段判断。
     *    写成 `hour in START..END` 的话，23..8 是空区间 —— 免打扰永远不生效，
     *    而界面上一切正常，只有用户在凌晨三点被吵醒时才知道出了事。
     */
    fun isQuietHour(hour: Int): Boolean =
        hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR
}
