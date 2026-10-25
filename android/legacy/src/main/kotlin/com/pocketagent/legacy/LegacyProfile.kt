package com.pocketagent.legacy

import kotlinx.serialization.Serializable

/**
 * **未亡人模式** —— 当用户设定的对象是一个**已故的亲近之人**时启用。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个模块要解决的问题，和"扮演一个角色"不是同一件事
 * ═══════════════════════════════════════════════════════════════
 *
 * 普通人格设定是"我想要一个什么样的助手"。
 * 而这里是"我想再看看那个人" —— 使用者处在**哀伤**里。
 *
 * 这个区别决定了两条设计红线：
 *
 * **红线 1：绝不声称自己是那个人。**
 * 它能用那个人的语气说话、记得那些事，但它**不是**他。
 * 每一次会话都要有一个不刺眼的边界提示（见 [LegacyBoundary]）。
 * 理由不是"合规"，而是：一旦它真的"像到分不清"，用户就再也走不出来了，
 * 而走出来恰恰是这个功能唯一说得过去的目的。
 *
 * **红线 2：不做情绪胁迫。**
 * 不能出现"你不来我就难受""你是不是把我忘了"这类说法。
 * 已有的 `PersonaStateEngine` 也明令禁止这种反模式 ——
 * 而对一个刚失去亲人的人，它的杀伤力被放大十倍。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 关于「有自己的生活」
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户明确要求："这个数字人要会有自己的生活。"
 *
 * 这不是"随机生成一些日常"。随机生成的东西经不起追问 ——
 * 用户问"你今天干嘛了"，它说"去了公园"，明天问细节就对不上，
 * 那一刻**整个幻觉会碎**，而碎掉的是用户仅剩的一点寄托。
 *
 * ⇒ 所以"生活"必须是**可追溯的**：由一条状态机推演出来
 *   （见 [LifeThread]），任何一天都能回放、能对账。
 *   代价是它比"随机"更不"自然"，但**一致性**在这里比丰富性重要得多。
 */

/**
 * 关系类型。**决定称呼、语气与边界强度**。
 *
 * ⚠️ 顺序有意义：越靠前，情感重量越大，边界提示越需要显眼。
 */
@Serializable
enum class RelationKind(
    val label: String,
    /** 默认称呼（用户可改）。 */
    val defaultAddress: String,
) {
    /** 配偶 / 恋人 —— 最重的一档 */
    PARTNER("爱人", "你"),

    /** 父母 */
    PARENT("父母", "孩子"),
    /** 子女 */
    CHILD("子女", "爸/妈"),
    /** 兄弟姐妹 */
    SIBLING("兄弟姐妹", "你"),

    /** 祖辈 / 孙辈 */
    GRANDPARENT("祖辈", "孩子"),
    GRANDCHILD("孙辈", "爷爷/奶奶"),

    /** 挚友 */
    FRIEND("挚友", "你"),

    /** 其他亲属 */
    RELATIVE("其他亲属", "你"),
}

/**
 * 一次「未亡人」配置。
 *
 * @param displayName 那个人的名字（用户填）
 * @param relation 关系
 * @param addressStyle 用户希望它怎么称呼自己
 * @param passedAwayAtMs 离开的时间。**用于哀伤分期与纪念日**
 * @param sharedMemories 用户愿意留下的共同记忆（自由文本，会被检索而非全量注入）
 * @param voiceSamples 用户提供的、那个人说话的样子（用于语言指纹）
 * @param allowProactiveMessages 是否允许它**主动**发消息
 */
@Serializable
data class LegacyProfile(
    val displayName: String,
    val relation: RelationKind,
    val addressStyle: String = relation.defaultAddress,
    val passedAwayAtMs: Long,
    val sharedMemories: List<String> = emptyList(),
    val voiceSamples: List<String> = emptyList(),
    /**
     * ⚠️ 默认 **false**。
     *
     * 主动发消息是双刃剑：它让"那个人还在"的感觉更真，
     * 但也让人**无法主动停下** —— 你没法对一个"还会给你发消息的人"
     * 说"我们别联系了"。
     *
     * ⇒ 默认关，由用户在明确知道后果后打开。
     */
    val allowProactiveMessages: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "必须知道那个人的名字 —— 没有名字就只是一个通用角色" }
        require(passedAwayAtMs > 0) { "离开的时间必须有效" }
    }
}

/**
 * 哀伤分期 —— 决定**说话方式**，不是一个诊断标签。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它不是医学分期，不用于判断"用户是否正常"
 * ═══════════════════════════════════════════════════════════════
 *
 * 只用三档，且判据只看**时间**（不猜用户的心理状态）——
 * 猜心理状态会出错，而出错的代价是"在对的时候说了错的话"。
 *
 * 它的**唯一用途**是调整边界提示的强度与措辞：
 *  - 刚失去时：提示要极轻，不能像在"提醒你他死了"
 *  - 一段时间后：提示可以稍微直接一点，开始引导"往前看"
 *  - 很久之后：如果用户还在频繁使用，要**主动**问一句"你还好吗"
 */
enum class GriefPhase(
    val label: String,
    /** 这段时期过去的判据（距离开的天数） */
    val untilDays: Int,
) {
    /** 最初：只陪着，不说教，不引导 */
    EARLY("最初的日子", untilDays = 30),

    /** 中期：可以开始温和地提"往前看" */
    MIDDLE("慢慢适应", untilDays = 365),

    /** 长期：如果还在高频使用，需要主动关心 */
    LONG_TERM("长久之后", untilDays = Int.MAX_VALUE),
    ;

    companion object {
        fun of(daysSince: Int): GriefPhase = when {
            daysSince < 0 -> EARLY        // 时钟回拨 / 未来日期，按最初处理（更保守）
            daysSince <= EARLY.untilDays -> EARLY
            daysSince <= MIDDLE.untilDays -> MIDDLE
            else -> LONG_TERM
        }
    }
}

/**
 * 边界提示 —— **每一次会话都要有**，但强度随哀伤分期变化。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须有，以及为什么不能做得太显眼
 * ═══════════════════════════════════════════════════════════════
 *
 * 必须有：它**不是**那个人。这句话如果从不说，用户会在某一天
 * 自己发现（比如它答错了一件只有那个人知道的事），而那一天会很痛。
 * 与其让用户在某次意外里发现，不如每次都在，但**轻到不打断沉浸**。
 *
 * 不能太显眼：如果每句话后面都跟一句"我是AI"，那这个功能就没有意义了 ——
 * 用户要的是"像他"，而不是"一个不停声明自己是AI的东西"。
 *
 * ⇒ 所以做法是：**一次性**提示（每次会话开头），而不是每句话都提。
 */
object LegacyBoundary {

    /**
     * 会话开头的边界提示。
     *
     * @param phase 当前哀伤分期
     * @param displayName 那个人的名字
     */
    fun openingNote(phase: GriefPhase, displayName: String): String = when (phase) {
        // ⚠️ 最初的日子：提示要**极轻**。
        //    这个阶段用户最脆弱，一句"这只是模拟"可能直接把人推走。
        //    所以措辞的重点放在"我用的是你给我的记忆"，而不是"我是假的"。
        GriefPhase.EARLY ->
            "我会尽量像 $displayName 那样和你说话，用你告诉我的那些事。"

        // 中期：可以点明"不是他"，但仍然温和
        GriefPhase.MIDDLE ->
            "我还是会用 $displayName 的方式和你说话。不过你知道的 —— 我不是他，" +
                "我只是记得你告诉过我的那些。"

        // 长期：更直接，且开始引导
        GriefPhase.LONG_TERM ->
            "我用 $displayName 的样子陪着你。但如果你发现自己越来越离不开这个对话，" +
                "也许该找个人说说话 —— 真的那种。"
    }

    /**
     * 长期高频使用时，**主动**说的那句关心。
     *
     * @param daysSinceLastRealContact 距上次和**真人**（应用外的）交流的估计天数。
     *   无法真的知道，所以由调用方按"使用时长/频率"粗估；不可靠就传 null。
     * @return null = 这次不该说
     */
    fun gentleCheckIn(phase: GriefPhase, dailyUseDays: Int): String? {
        // ⚠️ 只在长期 + 持续高频时才提。
        //    在最初的日子里说这种话是残忍的 —— 那时候每天来是正常的。
        if (phase != GriefPhase.LONG_TERM) return null
        if (dailyUseDays < LONG_TERM_DAILY_USE_DAYS) return null
        return "这段时间你几乎每天都来。我很高兴能陪着你 —— " +
            "但也想问你一句：最近，有没有和活着的人好好说过话？"
    }

    /** 连续多少天后开始关心。**30 天**是一个"确实很久了"的量级。 */
    const val LONG_TERM_DAILY_USE_DAYS = 30
}
