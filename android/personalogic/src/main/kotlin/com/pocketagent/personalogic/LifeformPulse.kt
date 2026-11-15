package com.pocketagent.personalogic

/**
 * 在场模式的「她自己来找你」。
 *
 * ★ 这里只决定**说什么**，不决定**该不该说**。
 *   该不该说仍由 `:legacy` 的 `ProactivePlanner.decide` 判
 *   （聊够 12 轮、3 小时没说话、5 小时没发过、23 点到 8 点静默）。
 *
 * ⚠️ 为什么不在这儿再写一套门槛：两边各有一份阈值，迟早漂移到
 *   「这边以为能发、那边以为不能发」，而那种错不会让任何测试变红 ——
 *   它只会让用户觉得"她怎么有时候半夜发消息"。所以门槛只留一份。
 */
object LifeformPulse {

    /** 一天最多两次。再多就不是想他，是催他。 */
    const val MAX_PER_DAY: Int = 2

    /** 短于这个小时数就别用「想你了」当由头 —— 刚聊完就说想，是假的。 */
    const val MIN_IDLE_HOURS_FOR_MISSING: Long = 6

    /**
     * 这一句的由头。
     *
     * ⚠️ `what` 会原样进提示词，所以每条都得是「她自己的事」，
     *    不能写成「催用户回消息」那种。
     */
    enum class Reason(val what: String) {
        WEB("你今天在网上翻到的东西"),
        NOW("你手上正在做的这件事"),
        EARLIER("今天早些时候做过的一件事"),
        MISS("没什么由头，就是想跟他说句话"),
    }

    /**
     * 判断所需的全部事实。**自带**，不去读时钟、不读盘 —— 于是可离线测。
     *
     * @param hasWeb 今天有没有翻到东西（有没有网都算，兜底池也算）
     * @param hasBeatNow 这个点她手上有没有事
     * @param hasBeatEarlier 今天早些时候有没有事
     * @param sentToday 今天已经主动发过几条
     * @param lastReason 上一次的由头（防止连着两次同一句）
     */
    data class Facts(
        val hourOfDay: Double,
        val idleHours: Long,
        val sentToday: Int,
        val seed: Long = 0L,
        val hasWeb: Boolean = false,
        val hasBeatNow: Boolean = false,
        val hasBeatEarlier: Boolean = false,
        val lastReason: Reason? = null,
    )

    /** 一个由头 + 拼好的那一段提示词（不含她的人设与她的今天，那两段由容器拼）。 */
    data class Plan(val reason: Reason, val promptText: String)

    /** null = 今天不该再发了。 */
    fun plan(facts: Facts): Plan? {
        if (facts.sentToday >= MAX_PER_DAY) return null
        val reason = choose(facts) ?: return null
        return Plan(reason = reason, promptText = promptText(reason, facts.hourOfDay))
    }

    private fun choose(facts: Facts): Reason? {
        val order = ArrayList<Reason>(4)
        if (facts.hasWeb) order += Reason.WEB
        if (facts.hasBeatNow) order += Reason.NOW
        if (facts.hasBeatEarlier) order += Reason.EARLIER
        if (facts.idleHours >= MIN_IDLE_HOURS_FOR_MISSING) order += Reason.MISS
        if (order.isEmpty()) return null

        // 同一个由头一天里会重复 —— 所以要掺进"几点"，不能只用日期当种子。
        val key = Math.floorMod(
            facts.seed * 31L + facts.hourOfDay.toInt().toLong(),
            order.size.toLong(),
        ).toInt()
        val picked = order[key]
        if (order.size == 1 || picked != facts.lastReason) return picked
        return order[(key + 1) % order.size]
    }

    /**
     * 那一段提示词。
     *
     * ⚠️ 这四句要求每一句都对应一个真实的失败模式：
     *    写长 → 变成小作文；解释 → "我注意到你很久没说话了"；
     *    催 → 变成讨债的；带解释 → 用户看见的是"系统在凑字数"。
     */
    fun promptText(reason: Reason, hourOfDay: Double): String = buildString {
        appendLine("## 你想发条消息给他")
        appendLine()
        appendLine("现在是 ${LifeformDay.clockOf(hourOfDay)}。")
        if (reason == Reason.MISS) {
            appendLine("这一句没什么由头，就是想跟他说句话。")
        } else {
            appendLine("这一句的由头：${reason.what}。")
        }
        appendLine("一到两句就够，像发微信那样短。")
        appendLine("不解释你为什么突然发消息。")
        appendLine("不问他为什么不回，也不催。")
        append("只输出这句话本身，不要前缀、引号或解释。")
    }
}
