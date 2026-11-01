package com.pocketagent.personalogic

/**
 * **2 小时提醒该怎么说**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：从 [UsageWatch] 拆出来的第二层
 * ═══════════════════════════════════════════════════════════════
 *
 * [UsageWatch] 只负责**算**（何时该提醒、用了多久），不负责**说**。
 * 这一层负责说。分开放的理由：
 *
 *  · 算法要稳定（合规要求：超 2 小时必须提醒）
 *  · 文案要可变（产品调性会改，甚至可能换成旁白口吻）
 *
 * ⇒ 改文案不必碰算法，改算法不必碰文案。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 文案的四条原则（每条都有对应测试）
 * ═══════════════════════════════════════════════════════════════
 *
 * **① 用具体数字，不说"很久"**
 *    "两个多小时" 比 "你已经用了很久" 更有分量，也更不像自动话术。
 *
 * **② 不说教、不提健康**
 *    「请注意休息，保护视力」这类话用户见过一万次，只会被无视。
 *    本产品是陪伴类，说教尤其破气氛。
 *
 * **③ 给一个具体的小动作**
 *    "要不要先放一放" 比 "请注意休息" 可执行。
 *
 * **④ 不指责产品**
 *    不说"你沉迷了"——那是评判用户。只陈述事实，把决定权留给他。
 *
 * ⚠️ 还有一条**不该做**的：不要在提醒里塞功能推荐
 *    （"顺便试试新功能"）。那会把一句关心变成广告。
 */
object UsageReminder {

    /**
     * 这条提醒走哪条通道。
     *
     * ★ 两种都实现，由调用方选 —— 因为"提醒该长什么样"是产品决策，
     *   不该由这一层单方面定死。
     */
    enum class Channel {

        /** 系统行：明确的界面元素，与角色对话视觉上分开。**默认**。 */
        SYSTEM_LINE,

        /**
         * 旁白：以场景描写的形式出现，不打断沉浸。
         *
         * ⚠️ 只在**角色对话场景**下用。若当前不是角色对话，
         *    旁白会显得莫名其妙 —— 见 [preferredChannel]。
         */
        NARRATION,
    }

    /** 一条可展示的提醒。 */
    data class Message(
        val text: String,
        val channel: Channel,
    )

    /**
     * 根据关系场景选通道。
     *
     * ★ 角色扮演中插一句系统话会砸气氛 ⇒ 用旁白。
     *   普通助理场景里用旁白会奇怪 ⇒ 用系统行。
     */
    fun preferredChannel(isRoleplay: Boolean): Channel =
        if (isRoleplay) Channel.NARRATION else Channel.SYSTEM_LINE

    /**
     * 生成提醒文案。
     *
     * @param reminder [UsageWatch.dueReminder] 的产物
     * @param channel 通道；不传则用 [Channel.SYSTEM_LINE]
     */
    fun messageFor(
        reminder: UsageWatch.Reminder,
        channel: Channel = Channel.SYSTEM_LINE,
    ): Message = Message(
        text = when (channel) {
            Channel.SYSTEM_LINE -> systemLine(reminder)
            Channel.NARRATION -> narration(reminder)
        },
        channel = channel,
    )

    /**
     * 系统行文案。
     *
     * ⚠️ 第一次与后续用不同措辞：第一次是"你待了很久"，
     *    后续是"还在"——重复同一句会变成骚扰。
     */
    private fun systemLine(r: UsageWatch.Reminder): String {
        val hours = hoursText(r.wholeHours)
        return if (r.isFirst) {
            "已经连续聊了${hours}。要不要先放一放，回头接着来。"
        } else {
            "还在聊——${hours}了。随时可以停下来。"
        }
    }

    /**
     * 旁白文案。
     *
     * ★ 写**场景**，不写**角色内心**，也不用第二人称说教。
     *   这样它读起来像叙述，而不像弹窗。
     */
    private fun narration(r: UsageWatch.Reminder): String {
        return if (r.isFirst) {
            "（窗外的光换了颜色。你在这里待了两个多小时。）"
        } else {
            "（天色又暗下去一些。）"
        }
    }

    /**
     * 小时数的中文写法。
     *
     * ★ 用"两个多小时"而不是"2 小时"——口语感更自然，也更不像系统提示。
     * ⚠️ 三以上用阿拉伯数字：`三个多小时` 读起来别扭，`3 个多小时` 更清楚。
     */
    private fun hoursText(hours: Int): String = when (hours) {
        2 -> "两个多小时"
        3 -> "3 个多小时"
        else -> "${hours} 个多小时"
    }
}
