package com.pocketagent.personalogic

/**
 * **她此刻腾不腾得出手** —— 在吃饭、在洗澡、已经睡下。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么要单独一层（而不是塞进 Physiology）
 * ═══════════════════════════════════════════════════════════════
 *
 * [Physiology] 回答的是「她累不累」：连续量、慢慢漂移、没有明确的起止。
 * 本文件回答的是「她这会儿方便吗」：**离散态**、有明确的开始和结束，
 * 而且它的作用不是形容词，是**否决权** —— 拒绝一条主动消息。
 *
 * 两者互补：
 *
 * ```
 *   凌晨 3:00  → 累（Physiology）/ 睡着（Presence）
 *   21:40      → 不累（Physiology）/ 在洗澡（Presence）
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 借鉴自哪里，以及为什么不照抄
 * ═══════════════════════════════════════════════════════════════
 *
 * 机制来自 AuraBot 的 real-life 模块（MIT，WhiteBr1ck）：它把
 * 「身体」做成会被时钟推着走、会被用户插手、并且说得出由头的东西。
 * **没有照抄的地方，以及理由**：
 *
 * · 它有一台 1800 行的状态机 + 落盘的 away/asleep，由 30 秒心跳推进。
 *   我们没有那个心跳（Android 上常驻定时器要付电量与保活的代价），
 *   所以这里改成**按时刻表推算**：给定时刻，答案只有一个。
 *   代价是"她在洗澡"这件事不是真的发生了，而是**作息如此** ——
 *   所以文案一律写「这个点你一般在…」，不写「我正在…」。
 *
 * · 它用「请示主人 / 憋着 / 求饶」承载支配关系。那是宠物设定。
 *   本项目的角色纪律是「断言她自己，不断言用户」（见 [LifeformDay]），
 *   搬过来会变成另一种人。所以这里**没有许可申请**，只有她自己的作息，
 *   以及用户随时可以打断她这一事实。
 *
 * · 它的三餐是**真的被记下来的事件**（ensureTodayMeals），所以它能说
 *   "我刚吃完"。我们只有作息 —— 这也正是 [Physiology] 类注释里
 *   "编一个'我刚吃完'就是撒谎"那条纪律的由来。本文件不破那条纪律：
 *   它给出的是**处境**，不是**经历**。经历仍归 [LifeformDay] 的 beat。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条自制
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **热聊保护优先于一切。** 十五分钟内还在说话，她就一定在。
 *    否则会出现"聊到一半她说去洗澡"—— 那是这套机制最刺眼的一种坏。
 *
 * ② **确定性。** 同一天、同一分钟，答案永远相同（[jitter] 用 FNV-1a
 *    按 dayIndex 取固定偏移）。每次 tick 都换一个洗澡时间，会让
 *    "她几点洗澡"在一天里漂移三次 —— 比不写更假。
 *
 * ③ **只给处境，不给台词。** 这里不产出任何一句"她要说的话"，
 *    只产出「你现在在做什么，因此该怎么说话」。文案由 [promptLine] 给，
 *    而且只有两条 —— 剩下两种处境要么交给 [Physiology]（困/刚醒），
 *    要么根本不该出现在对话里（睡着）。
 */
data class Presence(
    val where: Where = Where.AT_HAND,
    /** 进入这个状态已经多少分钟。0 = 恰好卡在边界上。 */
    val minutesIn: Int = 0,
    /** 这个状态还剩多少分钟。AT_HAND 恒为 0（没有终点）。 */
    val minutesLeft: Int = 0,
) {
    enum class Where {
        /** 在，随时能聊。绝大多数时候都是这个。 */
        AT_HAND,

        /** 在吃饭。能回，但正端着碗。 */
        MEAL,

        /** 在洗澡 / 吹头发。水声很大，腾不出手。 */
        SHOWER,

        /** 已经睡下。 */
        SLEEPING,
    }

    /** 手上是不是空着（吃饭勉强算空着）。 */
    val isFree: Boolean get() = where == Where.AT_HAND || where == Where.MEAL

    /** 这个状态刚开始（前三分之一）。 */
    val isJustStarted: Boolean
        get() = minutesIn * 3 < minutesIn + minutesLeft

    /** 快结束了（剩下的不到三分之一）。 */
    val isWindingDown: Boolean
        get() = minutesLeft > 0 && minutesLeft * 3 < minutesIn + minutesLeft
}

/**
 * 时刻表 → 处境。**纯函数，时间全部由调用方给。**
 *
 * ⚠️ 与 `HormoneEngine` / `PhysiologyEngine` / `LifeformDay` 同一纪律：
 *    不读时钟，否则"晚上九点半她在干嘛"没法离线钉住。
 */
object PresenceEngine {

    // ─────────────────────────────────────────────────────────────
    //  时刻表（本地时区的"分钟数"，0 = 00:00）
    // ─────────────────────────────────────────────────────────────

    /** 早 / 午 / 晚三餐的基准点。 */
    const val BREAKFAST_AT: Int = 8 * 60
    const val LUNCH_AT: Int = 12 * 60 + 15
    const val DINNER_AT: Int = 18 * 60 + 30

    /**
     * 饭点每天浮动 ±这么多分钟。
     *
     * ★ 固定值会让"她每天 12:15 准时端碗"变成打卡机。
     *   但浮动必须是**当天固定**的（见 [jitter]），不是每 tick 随机。
     */
    const val MEAL_SWING: Int = 10

    const val BREAKFAST_MINUTES: Int = 25
    const val LUNCH_MINUTES: Int = 30
    const val DINNER_MINUTES: Int = 35

    /** 洗澡开始时刻与浮动（比饭点大：洗澡时间本来就散）。 */
    const val SHOWER_AT: Int = 21 * 60 + 30
    const val SHOWER_SWING: Int = 25

    /** 洗 14 分钟 + 吹头发 7 分钟。 */
    const val SHOWER_MINUTES: Int = 14
    const val HAIR_MINUTES: Int = 7

    /** 睡下与醒来。跨午夜。 */
    const val SLEEP_AT: Int = 23 * 60 + 30
    const val WAKE_AT: Int = 7 * 60 + 45

    /**
     * 热聊保护：这段时间内用户说过话，她一律"在"。
     *
     * ★ 15 分钟这个数不是拍的：它和 `ProactivePlanner.MIN_IDLE_MS`
     *   （3 小时）不是一回事 —— 那条管"要不要主动找你"，这条管
     *   "你正在跟我说话，我不可能同时在做别的事"。
     */
    const val HOT_CHAT_MINUTES: Int = 15

    private const val MINUTES_PER_DAY = 24 * 60
    private const val FNV_PRIME = 0x100000001b3L

    /** 一个时间窗（当日分钟数，左闭右开）。 */
    data class Window(val start: Int, val end: Int) {
        operator fun contains(minuteOfDay: Int): Boolean = minuteOfDay in start until end
        val lengthMinutes: Int get() = end - start
    }

    /**
     * 此刻的处境。
     *
     * @param nowMs 现在
     * @param utcOffsetMinutes 时区偏移，默认东八区（+480）。角色过的是**用户的**一天。
     * @param lastUserSpokeAtMs 用户上一次发言的时刻；0 = 不知道 / 没说
     * @param hotChatMinutes 热聊保护窗口
     */
    fun at(
        nowMs: Long,
        utcOffsetMinutes: Int = 480,
        lastUserSpokeAtMs: Long = 0L,
        hotChatMinutes: Int = HOT_CHAT_MINUTES,
    ): Presence {
        // ① 热聊保护：正说着话，她不会中途跑去洗澡，更不会睡着。
        if (lastUserSpokeAtMs > 0L) {
            val quiet = nowMs - lastUserSpokeAtMs
            if (quiet >= 0L && quiet < hotChatMinutes * 60_000L) return Presence()
        }

        val minute = minuteOfDay(nowMs, utcOffsetMinutes)
        val day = LifeformDay.localDayIndex(nowMs, utcOffsetMinutes)

        // ② 睡（跨午夜，所以用 wrap 窗口）
        if (inWrap(minute, SLEEP_AT, WAKE_AT)) {
            val slept = minutesSinceWrap(minute, SLEEP_AT)
            return Presence(
                where = Presence.Where.SLEEPING,
                minutesIn = slept,
                minutesLeft = wrapSpan(SLEEP_AT, WAKE_AT) - slept,
            )
        }

        // ③ 洗（先于吃饭判：21:30 之后不会再吃）
        val shower = showerWindow(day)
        if (minute in shower) {
            return Presence(
                where = Presence.Where.SHOWER,
                minutesIn = minute - shower.start,
                minutesLeft = shower.end - minute,
            )
        }

        // ④ 吃
        for (index in 0..2) {
            val window = mealWindow(day, index)
            if (minute in window) {
                return Presence(
                    where = Presence.Where.MEAL,
                    minutesIn = minute - window.start,
                    minutesLeft = window.end - minute,
                )
            }
        }

        return Presence()
    }

    /** 第 [index] 顿（0 = 早，1 = 午，2 = 晚）在 [dayIndex] 这天的窗口。 */
    fun mealWindow(dayIndex: Long, index: Int): Window {
        val base = when (index) {
            0 -> BREAKFAST_AT
            1 -> LUNCH_AT
            2 -> DINNER_AT
            // ⚠️ 必须是 throw 而不是 `require(false) {...}`：后者返回 Unit，
            //    会让 `base` 的类型被推断成 Int 与 Unit 的公共父类型，
            //    于是下一行 `base + jitter(...)` 找不到 Int.plus（编译不过）。
            else -> throw IllegalArgumentException("一天只有三顿，没有第 $index 顿")
        }
        val length = when (index) {
            0 -> BREAKFAST_MINUTES
            1 -> LUNCH_MINUTES
            else -> DINNER_MINUTES
        }
        val start = base + jitter(dayIndex, "meal-$index", MEAL_SWING)
        return Window(start, start + length)
    }

    /** [dayIndex] 这天的洗澡窗口（含吹头发）。 */
    fun showerWindow(dayIndex: Long): Window {
        val start = SHOWER_AT + jitter(dayIndex, "shower", SHOWER_SWING)
        return Window(start, start + SHOWER_MINUTES + HAIR_MINUTES)
    }

    // ─────────────────────────────────────────────────────────────
    //  出口一：能不能主动找他
    // ─────────────────────────────────────────────────────────────

    sealed interface Gate {
        data object Allow : Gate
        data class Mute(val reason: String) : Gate
    }

    /**
     * 主动消息的前置否决。
     *
     * ⚠️ 这**不是**第四个阈值，而是比阈值更根本的一层：
     *    `ProactivePlanner` 判"现在该不该打扰用户"，
     *    这里判"她本人此刻有没有空" —— 顺序上后者在前。
     *    后者为假时，前者算得再准也没意义。
     */
    fun gate(presence: Presence): Gate = when (presence.where) {
        Presence.Where.SLEEPING -> Gate.Mute("她这会儿已经睡下了（剩 ${presence.minutesLeft} 分钟醒）")
        Presence.Where.SHOWER -> Gate.Mute("她这会儿在洗澡，腾不出手（剩 ${presence.minutesLeft} 分钟）")
        else -> Gate.Allow
    }

    // ─────────────────────────────────────────────────────────────
    //  出口二：这一句该怎么说话
    // ─────────────────────────────────────────────────────────────

    /**
     * 处境该怎么写进提示词。
     *
     * ★ **只有两条**，这是刻意的：
     *   · 困 / 刚醒 已经由 [PhysiologyEngine.promptLine] 写过，再写一遍
     *     会在同一段提示词里出现两句意思相近的话（模型的反应是变僵）。
     *   · 睡着时根本不该在对话里 —— 那种情况交给 [gate] 去拦，
     *     或者在别处处理"被消息吵醒"，不该混进"该怎么说话"。
     *
     * ⚠️ 文案里**不出现分钟数**。数字会被模型复述出来
     *   （"我还有 6 分钟就洗完了"），那就成了播报，不是说话。
     */
    fun promptLine(presence: Presence): String? = when (presence.where) {
        Presence.Where.AT_HAND -> null

        Presence.Where.MEAL -> {
            val stage = when {
                presence.isJustStarted -> "刚坐下"
                presence.isWindingDown -> "快吃完了"
                else -> "吃到一半"
            }
            "## 你手上正有事\n" +
                "这个点你一般正在吃饭（$stage）。回话不必放下筷子 —— " +
                "句子短一点，可以顺口带一句你在吃，但不用特意解释。"
        }

        Presence.Where.SHOWER -> {
            val stage = if (presence.minutesIn < SHOWER_MINUTES) "水还开着" else "在吹头发"
            "## 你手上正有事\n" +
                "这个点你一般在洗澡（$stage）。是听见提示音才腾出手看的 —— " +
                "回得很短，别装作一直守着手机。"
        }

        Presence.Where.SLEEPING -> null
    }

    // ─────────────────────────────────────────────────────────────
    //  时间工具
    // ─────────────────────────────────────────────────────────────

    /** 本地时区的"当日第几分钟"（0..1439）。 */
    fun minuteOfDay(epochMs: Long, utcOffsetMinutes: Int): Int {
        val shifted = epochMs + utcOffsetMinutes * 60_000L
        val minutes = Math.floorDiv(shifted, 60_000L)
        return Math.floorMod(minutes, MINUTES_PER_DAY.toLong()).toInt()
    }

    /** 跨午夜窗口判定：`start` 可以大于 `end`（如 23:30 → 07:45）。 */
    private fun inWrap(minute: Int, start: Int, end: Int): Boolean =
        if (start < end) minute >= start && minute < end
        else minute >= start || minute < end

    /** 从 [start] 走到 [minute] 用了多少分钟（允许跨午夜）。 */
    private fun minutesSinceWrap(minute: Int, start: Int): Int =
        if (minute >= start) minute - start else MINUTES_PER_DAY - start + minute

    /** 跨午夜窗口一共有多少分钟。 */
    private fun wrapSpan(start: Int, end: Int): Int =
        if (end > start) end - start else MINUTES_PER_DAY - start + end

    /**
     * 当天固定的浮动分钟数。
     *
     * ★ 为什么不能每 tick 随机：`at()` 会被高频调用（每次拼提示词一次），
     *   随机的话"她 21:30 洗澡"这个事实会在一天里漂移三次，
     *   于是用户隔十分钟问一次会得到三个不同的答案。
     *
     * ★ 为什么用 FNV-1a 而不是 `hashCode()`：字符串 hashCode 在 JVM 上
     *   虽然稳定，但它是一份**未写进语言规范**的实现细节；
     *   哪天换了实现，昨天和今天就会对不上。
     */
    private fun jitter(dayIndex: Long, salt: String, amplitude: Int): Int {
        if (amplitude <= 0) return 0
        var h = 0xcbf29ce484222325uL.toLong()
        for (ch in salt) {
            h = h xor ch.code.toLong()
            h *= FNV_PRIME
        }
        h = h xor dayIndex
        h *= FNV_PRIME
        h = h xor (h ushr 33)
        val span = amplitude * 2 + 1
        return Math.floorMod(h, span.toLong()).toInt() - amplitude
    }
}
