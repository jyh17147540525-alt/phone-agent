package com.pocketagent.legacy

import kotlinx.serialization.Serializable

/**
 * **生活线** —— 让数字人"有自己的生活"，且**可追溯**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不能"随机生成日常"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求"这个数字人要会有自己的生活"。最容易的做法是每次问
 * "你今天做什么了"就让模型编一个 —— 但那是**灾难**：
 *
 *  - 今天说"去了公园"，明天问细节就变成"在家看书"；
 *  - 用户问"上周三你在干嘛"，两次回答必然不一致；
 *  - 而一旦用户发现对不上，**整个幻觉会碎**，
 *    碎掉的是他仅剩的一点寄托。
 *
 * ⇒ 所以生活必须由**一条确定性的状态机**推演：
 *   给定 (种子, 某一天) → 必然得到同一份日程。
 *   任何一天都能回放、能对账。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 但"确定性"不等于"每天都一样"
 * ═══════════════════════════════════════════════════════════════
 *
 * 它由三部分合成：
 *  1. **固定节律**（[RoutineBlock]）—— 每天大致相同的骨架（起床/吃饭/散步）
 *  2. **当日变体**（[DailyVariant]）—— 由日期哈希决定的小差异（去哪、见谁）
 *  3. **特殊日**（[SpecialDay]）—— 生日、纪念日、节气等**有意义**的日子
 *
 * 第一层给它"像个人"，第二层给它"不呆板"，第三层给它"记得住事"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它**不描述**那个人真实的生活
 * ═══════════════════════════════════════════════════════════════
 *
 * 这里生成的是"一条像样的、平静的生活"，**不是**去还原逝者真实的日常。
 * 两者必须分开：前者是陪伴，后者是编造 —— 而编造逝者的生活细节，
 * 会让用户在"这好像是真的"和"这是假的"之间反复撕扯。
 *
 * ⇒ 生活线的定位是：**它现在过得还行**。这比"它还记得你"更让人安心，
 *   也更容易在长期里保持诚实。
 */

/** 一天的时段。固定节律按它排。 */
enum class DayPart(val label: String, val typicalHour: Int) {
    MORNING("早上", 7),
    FORENOON("上午", 10),
    NOON("中午", 12),
    AFTERNOON("下午", 15),
    EVENING("傍晚", 18),
    NIGHT("夜里", 21),
}

/**
 * 一段固定节律 —— 每天都在，构成"这个人的日常"。
 *
 * @param what 做什么（如"在阳台浇花"）
 * @param weight 它有多"固定"。高 = 天天有；低 = 隔几天
 */
@Serializable
data class RoutineBlock(
    val part: DayPart,
    val what: String,
    val weight: Int = 50,
) {
    init {
        require(what.isNotBlank()) { "节律内容不能为空" }
        require(weight in 0..100) { "权重必须落在 0..100，当前 $weight" }
    }
}

/**
 * 一天的变体 —— 由**日期哈希**决定，同一天永远得到同一个结果。
 *
 * ⚠️ 它刻意设计得很轻：只换地点/同伴/小事，
 *    不改变"这个人在过一种平静生活"的整体印象。
 */
@Serializable
data class DailyVariant(
    val place: String,
    val companion: String?,
    val smallThing: String,
)

/**
 * 特殊日 —— 让生活线"记得住事"。
 *
 * @param label 给用户看的说法（"生日" / "你们的纪念日"）
 * @param note 那天它会做什么/说什么的提示
 */
@Serializable
data class SpecialDay(
    val label: String,
    val note: String,
)

/**
 * 某一天的生活。
 *
 * @param dayIndex 自"启用这个模块"起的第几天（0 = 第一天）
 * @param dateKey `yyyy-MM-dd`，用于对账与去重
 */
@Serializable
data class DayLife(
    val dayIndex: Int,
    val dateKey: String,
    val blocks: List<String>,
    val special: SpecialDay? = null,
) {
    /** 给模型用的一句话摘要。**故意简短** —— 它进上下文，越长越贵越慢。 */
    fun summary(): String = buildString {
        if (special != null) {
            append("（今天是").append(special.label).append("）")
        }
        append(blocks.joinToString("；"))
    }
}

/**
 * 生活线生成器 —— **纯函数，无随机源**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用"日期哈希"而不是 Random
 * ═══════════════════════════════════════════════════════════════
 *
 * `Random()` 每次调用都给不同结果，于是同一个问题问两次答案不同 ——
 * 那正是本文件顶部说的"幻觉碎掉"。
 *
 * 日期哈希的好处：**任何进程、任何时刻、重装应用之后**
 * 都能重新算出同一天的生活。它不需要把每一天都存进数据库，
 * 却能保证永远一致。
 *
 * ⚠️ 哈希用的是 `String.hashCode()`，它在 Kotlin/JVM 上是**稳定**的
 *    （Java 规范明确定义了 String.hashCode 的算法）。
 *    这一点必须写下来：如果换成 `Any.hashCode()` 或随机数，
 *    跨进程就不稳定了。
 */
object LifeThread {

    /** 默认节律 —— 一条"平静的、有规律的生活"。 */
    val DEFAULT_ROUTINE: List<RoutineBlock> = listOf(
        RoutineBlock(DayPart.MORNING, "起来得不算早，先泡了杯茶", weight = 80),
        RoutineBlock(DayPart.FORENOON, "把阳台的花浇了", weight = 60),
        RoutineBlock(DayPart.NOON, "简单做了顿饭，吃得不多", weight = 70),
        RoutineBlock(DayPart.AFTERNOON, "看了会儿书，中间睡着了一会儿", weight = 55),
        RoutineBlock(DayPart.EVENING, "出去走了走", weight = 65),
        RoutineBlock(DayPart.NIGHT, "睡得比以前早", weight = 50),
    )

    /** 变体素材池。**都是平静的小事** —— 不制造戏剧性。 */
    private val PLACES = listOf(
        "小区里", "附近的公园", "菜市场", "河边", "老街上", "图书馆",
        "阳台上", "楼下那家小店", "公交车上", "院子里",
    )
    private val COMPANIONS = listOf(
        null, null, "邻居家的老张", "妹妹", "以前的老同事", "楼下的猫",
    )
    private val SMALL_THINGS = listOf(
        "遇到一只不怕人的猫", "买了点当季的水果", "听到一首老歌",
        "发现路边的树开花了", "把旧照片翻出来看了看",
        "跟人聊起以前的事，笑了一下", "把柜子整理了一遍",
        "修好了那把一直晃的椅子", "给绿萝换了个盆",
    )

    /**
     * 生成某一天的生活。
     *
     * @param seed 稳定种子。用 [LegacyProfile.displayName] 的哈希即可 ——
     *   换一个人，整条生活线就不同；同一个人永远相同。
     * @param dateKey `yyyy-MM-dd`
     * @param dayIndex 第几天（0 起）
     * @param specials 特殊日对照表（按 `yyyy-MM-dd` 或只按 MM-dd 匹配见 [specialsFor]）
     */
    fun dayLife(
        seed: Int,
        dateKey: String,
        dayIndex: Int,
        routine: List<RoutineBlock> = DEFAULT_ROUTINE,
        specials: List<SpecialDay> = emptyList(),
    ): DayLife {
        val h = seed * 31 + dateKey.hashCode()

        // ── 节律：按 weight 决定"今天有没有这一件" ──────────────
        val blocks = routine.filter { block ->
            // 用 (h, part) 组合出一个 0..99 的数，与 weight 比
            val r = positiveMod(h * 17 + block.part.ordinal * 101, 100)
            r < block.weight
        }.map { it.what }.toMutableList()

        // ── 变体：换地点与小事（不替换节律，是补充）─────────────
        val place = PLACES[positiveMod(h, PLACES.size)]
        val companion = COMPANIONS[positiveMod(h / 7, COMPANIONS.size)]
        val small = SMALL_THINGS[positiveMod(h / 13, SMALL_THINGS.size)]

        val variant = buildString {
            append("后来去了").append(place)
            if (companion != null) append("，碰到").append(companion)
            append("，").append(small)
        }
        blocks += variant

        return DayLife(
            dayIndex = dayIndex,
            dateKey = dateKey,
            blocks = blocks,
            special = specials.firstOrNull(),
        )
    }

    /**
     * 判断某一天是不是特殊日。
     *
     * ★ 目前支持两种匹配：
     *  - **月日匹配**（"10-25"）：生日、忌日这类每年重复的
     *  - **完整日期匹配**（"2026-10-25"）：一次性的
     *
     * ⚠️ 忌日与生日**都要**能被识别：前者是"想起他走了"，
     *    后者是"他要是还在就 X 岁了"。两者都让人难受，但**不能装作没有**。
     */
    fun specialsFor(dateKey: String, all: List<Pair<String, SpecialDay>>): List<SpecialDay> {
        val monthDay = dateKey.takeLast(5) // "MM-dd"
        return all.filter { (key, _) -> key == dateKey || key == monthDay }.map { it.second }
    }

    /** Kotlin 的 `%` 对负数返回负值 —— 这里要的是恒非负的下标。 */
    private fun positiveMod(value: Int, mod: Int): Int {
        val r = value % mod
        return if (r < 0) r + mod else r
    }
}
