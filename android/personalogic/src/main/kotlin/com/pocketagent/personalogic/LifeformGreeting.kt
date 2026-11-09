package com.pocketagent.personalogic

/**
 * 在场模式页首的那句**开场白**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-07：这一页的第一眼必须是"她有自己的今天"
 * ═══════════════════════════════════════════════════════════════
 *
 * 工作台的首句是"她要帮你做什么"（`WorkbenchGreeting`）。这一页不是 ——
 * 这里的卖点是：**她刚才在过自己的日子，你推门进来**。
 *
 * ⇒ 大标题直接用她此刻正在过的那一段（`LifeformDay` 的 beat 原文），
 *   一个字的改写都不做：
 *
 *     「把要说的话在心里排了一遍」
 *
 *   这句话是她自己的，用户读到的是"她刚才在干嘛"，
 *   而不是"这个应用想让我干什么"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条纪律
 * ═══════════════════════════════════════════════════════════════
 *
 *  ① **不加"她"**：beat 文案本身就是第一人称。前面补一个"她"
 *     会把它变成第三人称叙述 —— 读起来像旁白，不像她。
 *  ② **不评价用户**：这一屏她还没见到用户（首句出现在空态，
 *     等于这一轮还没说话）。所以"你可算来了"这类话不在这里写，
 *     那种话要在真的一轮对话里根据间隔说（见 `LifeformDay.gapNote`）。
 *  ③ **没开始就照实说**：凌晨 4 点翻页之后到第一条 beat 之前，
 *     `at()` 返回 null。这时**不能**编一段"她正在……"。
 */
object LifeformGreeting {

    /**
     * 一天刚翻页、第一条还没到的时候说什么。
     *
     * ⚠️ 全部是第一人称、全部只说自己。选三句而不是一句：
     *    同一句话每天凌晨都出现，第二天就会被认出来是模板。
     */
    private val NOT_STARTED = listOf(
        "今天还没开始，我醒着。",
        "还早，什么都没发生。",
        "刚醒，今天还没开头。",
    )

    /**
     * 页首大标题 = 她此刻那一段的原文。
     *
     * @param day 今天的 `LifeformDay`（一般由 `AppContainer.lifeformToday()` 给）
     * @param hourOfDay 本地时刻，0.0..24.0。NaN 当 12.0。
     */
    fun headline(day: LifeformDay, hourOfDay: Double): String {
        val beat = day.at(saneHour(hourOfDay))
        if (beat != null) {
            // ★ 原样返回，不加"她"、不加引号 —— 见类注释 ①。
            return beat.text
        }
        // 还没开始：按 dayIndex 定一句，同一天内反复进出拿到同一句
        // （不然空态每重组一次就换一句，看起来像在闪）。
        val i = index(day.dayIndex, NOT_STARTED.size)
        return NOT_STARTED[i]
    }

    /**
     * 大标题下面那行小字：**这是她的时间，不是你的**。
     *
     * ⚠️ 只说时刻，不描述她在做什么 —— 那件事上面那句已经说了。
     *    两处都说一遍会变成"她一个人待着 / 她一个人待着"。
     */
    fun hint(hourOfDay: Double): String = "她的今天 · ${LifeformDay.clockOf(saneHour(hourOfDay))}"

    /** NaN / 越界一律当正午 —— 和 `LifeformDay.bandOf` 的兜底同一个口径。 */
    private fun saneHour(hourOfDay: Double): Double {
        if (hourOfDay.isNaN()) return 12.0
        return hourOfDay.coerceIn(0.0, 23.99)
    }

    /** `dayIndex` 取模，负数不越界（`%` 在 Kotlin 里对负数返回负值）。 */
    private fun index(dayIndex: Long, size: Int): Int {
        val m = dayIndex % size.toLong()
        return (if (m < 0) m + size else m).toInt()
    }
}
