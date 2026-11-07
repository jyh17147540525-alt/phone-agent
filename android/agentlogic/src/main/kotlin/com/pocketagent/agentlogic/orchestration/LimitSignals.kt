package com.pocketagent.agentlogic.orchestration

/**
 * 屏幕上的"你到上限了"信号。
 *
 * ## 为什么这一类要单独识别
 *
 * 绝大多数失败重试一次就好了（网络抖、页面慢）。但**限额类**失败是反的：
 * 重试只会把次数用得更干净，甚至触发风控。而它长得又很像普通失败 ——
 * 界面上只是一行小字，模型看到"没成功"就会再试一次。
 *
 * ⇒ 识别到就停在该步、把原文交给用户，让他决定等多久。
 *
 * ## 词表刻意保守
 *
 * 只收"几乎不可能是别的原因"的说法。「请稍后再试」「操作失败」这类**没有收**：
 * 它们同样出现在网络错误页上，把网络错误当成限额会让任务白停一天。
 * 判断错的方向不对称 —— 漏判的代价是多重试两次，误判的代价是把用户的任务停掉。
 */
object LimitSignals {

    /**
     * 命中即判限额。全部是**界面上真的会出现的原文**，不是我们编的说法。
     */
    private val PHRASES = listOf(
        "今日已达上限",
        "今日次数已用完",
        "已达上限",
        "达到上限",
        "已到上限",
        "操作过于频繁",
        "操作频繁",
        "次数已用完",
        "额度已用尽",
        "明天再来",
        "每日上限",
    )

    /**
     * @return 命中的那句话（原样，交给用户判断撞的是哪个上限）；没命中返回 null。
     */
    fun match(digest: ScreenDigest?): String? {
        if (digest == null) return null
        for (text in digest.texts) {
            val hit = PHRASES.firstOrNull { text.contains(it) }
            if (hit != null) return text.trim()
        }
        return null
    }

    /** 给用户看的一句建议。 */
    fun advice(limitText: String): String =
        "被平台限额拦住了（屏上写着「$limitText」）。重试没用，等一会儿或换个时间再试。"

    /** 供测试与文档核对：词表内容。 */
    internal val phrases: List<String> get() = PHRASES
}
