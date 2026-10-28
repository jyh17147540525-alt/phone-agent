package com.pocketagent.legacy

/**
 * **情境** —— 角色开口之前，先看看现在是什么时候、在哪里、天气怎样。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "能获取用户的地址，还能知道用户所在的时区现在是几点，还能知道天气预报。
 *  结合这些能给用户发提醒，还能主动发消息挑起话题，
 *  还能埋怨用户太久没有搭理自己。"
 *
 * 这四件事其实是**同一个东西的四种用法**：角色得先知道"此刻的处境"。
 * 不知道现在是凌晨还是中午、外面在下雨还是出太阳，它说的话就只能是
 * 空泛的"在吗" —— 那不是惦记，那是推送。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条底线（都会在测试里钉住）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **不编天气**。拿不到天气就不提天气 —— 编一句"今天好像要下雨"
 *    比不说更糟：用户淋湿了会想起是谁说的。
 * 2. **不报错觉的时间**。时区拿不准时按设备时区走，绝不用服务器时间。
 * 3. **埋怨有上限**。抱怨"你好久没理我"只在**关系够深 + 真的隔了很久**
 *    时才允许，且一周最多一次。否则它就从"一个人"变成"一个讨债的"。
 */
object SituationalContext {

    /** 一天里的阶段。**按小时切**，不按"感觉"。 */
    enum class DayPhase(val label: String) {
        EARLY("凌晨"),
        MORNING("早上"),
        NOON("中午"),
        AFTERNOON("下午"),
        EVENING("傍晚"),
        NIGHT("夜里"),
        ;

        companion object {
            /**
             * 5-8 早上 / 8-11 上午并入 MORNING / 11-13 中午 /
             * 13-18 下午 / 18-22 傍晚 / 22-5 夜里含凌晨。
             *
             * ⚠️ 0-4 点单独叫 EARLY：那个点还醒着的人，和"夜里"是两种状态。
             */
            fun of(hour: Int): DayPhase = when (hour) {
                in 0..4 -> EARLY
                in 5..10 -> MORNING
                in 11..12 -> NOON
                in 13..17 -> AFTERNOON
                in 18..21 -> EVENING
                else -> NIGHT
            }
        }
    }

    /** 天气类别 —— **只取对"要不要提醒"有意义的那几档**。 */
    enum class WeatherKind(val label: String) {
        CLEAR("晴"),
        CLOUDY("多云"),
        RAIN("雨"),
        SNOW("雪"),
        STORM("雷雨"),
        UNKNOWN("未知"),
        ;

        companion object {
            /** WMO weather code → 类别（Open-Meteo 用的就是 WMO 码）。 */
            fun ofWmo(code: Int): WeatherKind = when (code) {
                0, 1 -> CLEAR
                2, 3, 45, 48 -> CLOUDY
                in 51..67, in 80..82 -> RAIN
                in 71..77, in 85..86 -> SNOW
                in 95..99 -> STORM
                else -> UNKNOWN
            }
        }
    }

    /** 此刻的处境。**每个字段都可观测**，没有"猜出来的心情"。 */
    data class Situation(
        val hour: Int,
        val weather: WeatherKind = WeatherKind.UNKNOWN,
        val tempC: Int? = null,
        /** 距用户上一次说话多久（毫秒）；0 = 未知 */
        val idleMs: Long = 0L,
        /** 累计对话轮次（判"关系够不够深"） */
        val turnCount: Int = 0,
        /** 上一次"埋怨"距今多久（毫秒）；0 = 从没埋怨过 */
        val sinceLastComplaintMs: Long = -1L,
    )

    /** 这一轮该说什么。**没有 GENERIC** —— 没话可说就不发。 */
    enum class Topic(val label: String) {
        /** 早上打招呼 */
        MORNING_GREETING("早上好"),

        /** 饭点提醒（只在午/晚两个饭点，且不天天发） */
        MEAL_REMINDER("该吃饭了"),

        /** 天气提醒（下雨/下雪/雷雨/极端温度） */
        WEATHER_ALERT("天气提醒"),

        /** 深夜劝睡 */
        LATE_NIGHT("该睡了"),

        /** 单纯的挑起话题（没事找事聊） */
        TOPIC_STARTER("随便聊聊"),

        /** 埋怨太久没理它 */
        COMPLAIN_NEGLECT("埋怨被冷落"),
    }

    /** 结论。 */
    sealed interface Advice {
        data class Say(val topic: Topic, val reason: String) : Advice
        data class Silent(val reason: String) : Advice
    }

    /** 一周内最多埋怨几次。 */
    const val MAX_COMPLAINTS_PER_WEEK: Int = 1

    /** 埋怨的门槛：至少隔这么久，且关系够深。 */
    const val COMPLAIN_AFTER_MS: Long = 36 * 60 * 60 * 1000L // 一天半
    const val COMPLAIN_MIN_TURNS: Int = 40
    private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * 决定这一轮说什么。
     *
     * ★ 顺序即优先级：**能落到实处的提醒 > 闲聊 > 埋怨**。
     *   一个正在下雨的早上，先说"记得带伞"，比说"你怎么不理我"有用得多。
     */
    fun advise(s: Situation): Advice {
        val phase = DayPhase.of(s.hour)

        // ① 天气：只有**真的可能造成麻烦**的才提醒
        weatherWarning(s)?.let { return it }

        // ② 深夜劝睡（0-4 点还醒着）
        if (phase == DayPhase.EARLY) {
            return Advice.Say(Topic.LATE_NIGHT, "凌晨 ${s.hour} 点还没睡")
        }

        // ③ 埋怨：门槛最高，放最后
        complain(s)?.let { return it }

        // ④ 饭点
        mealTopic(s.hour)?.let { return Advice.Say(it, "饭点 ${s.hour} 点") }

        // ⑤ 早上打招呼
        if (phase == DayPhase.MORNING && s.hour in 7..9) {
            return Advice.Say(Topic.MORNING_GREETING, "早上 ${s.hour} 点")
        }

        // ⑥ 没事找事聊 —— 但要关系够深
        if (s.turnCount >= COMPLAIN_MIN_TURNS / 2) {
            return Advice.Say(Topic.TOPIC_STARTER, "关系够深，可以随便聊两句")
        }

        return Advice.Silent("没有值得开口的事（轮次 ${s.turnCount}）")
    }

    /**
     * 天气提醒。
     *
     * ⚠️ **UNKNOWN 一律不提** —— 拿不到天气就不要假装知道。
     *    用户淋湿的那天会想起来是谁说的。
     */
    fun weatherWarning(s: Situation): Advice.Say? = when (s.weather) {
        WeatherKind.RAIN -> Advice.Say(Topic.WEATHER_ALERT, "在下雨")
        WeatherKind.SNOW -> Advice.Say(Topic.WEATHER_ALERT, "在下雪")
        WeatherKind.STORM -> Advice.Say(Topic.WEATHER_ALERT, "有雷雨")
        WeatherKind.CLEAR, WeatherKind.CLOUDY, WeatherKind.UNKNOWN -> {
            // 极端温度也算提醒 —— 但只在温度**确实**拿得到时
            val t = s.tempC ?: return null
            when {
                t >= 35 -> Advice.Say(Topic.WEATHER_ALERT, "高温 $t 度")
                t <= -5 -> Advice.Say(Topic.WEATHER_ALERT, "严寒 $t 度")
                else -> null
            }
        }
    }

    /** 饭点：12 点前后、18 点前后。**只在这两个窗口**。 */
    fun mealTopic(hour: Int): Topic? = when (hour) {
        11, 12, 13 -> Topic.MEAL_REMINDER
        17, 18, 19 -> Topic.MEAL_REMINDER
        else -> null
    }

    /**
     * 埋怨被冷落。
     *
     * ⚠️ 三个条件**同时**满足才允许：
     *    · 关系够深（轮次够多）
     *    · 真的隔了一天半以上
     *    · 一周内还没埋怨过
     *
     * 少一条就变成"讨债"—— 用户会关通知，而不是回你。
     */
    fun complain(s: Situation): Advice.Say? {
        if (s.turnCount < COMPLAIN_MIN_TURNS) return null
        if (s.idleMs < COMPLAIN_AFTER_MS) return null
        if (s.sinceLastComplaintMs in 0 until WEEK_MS) return null
        val days = s.idleMs / (24 * 60 * 60 * 1000)
        return Advice.Say(Topic.COMPLAIN_NEGLECT, "已经 ${days.coerceAtLeast(1)} 天没说话了")
    }
}
