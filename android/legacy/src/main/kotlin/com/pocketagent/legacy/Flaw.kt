package com.pocketagent.legacy

import kotlinx.serialization.Serializable

/**
 * **那个人的毛病** —— 「不完美模拟」的唯一燃料。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 核心矛盾：要吵架，但不能 OOC
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求：数字人要在长期交互后**闹出隔阂**，且以后会反复提起那次矛盾；
 * 当用户说"你以前不是这样的"时，要**降低提及频率**。
 *
 * 同时要求：**不能让用户觉得 OOC**，而要让他知道"这确实是那个人生前会做的事"。
 *
 * 这两件事天然冲突 —— **系统随机编出来的脾气，一定 OOC**。
 * 因为那个人的吵架方式不是随机的：他固执在哪些事上、为什么沉默、
 * 道歉的方式是什么，都是**具体的**。系统再怎么生成也编不像。
 *
 * ⇒ 唯一的解法：冲突**必须来自用户自己写下来的毛病**。
 *   系统只负责"什么时候触发"和"怎么收场"，不负责"他是什么脾气"。
 *
 *   那样一来，"他不像"这个指责就不成立了 —— 因为它做的事，
 *   是用户自己说的"他就是会这样"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这是本模块里最需要克制的地方
 * ═══════════════════════════════════════════════════════════════
 *
 * 在被爱的人身上"复现缺点"，和"复现温柔"是两件完全不同分量的事：
 * 后者让人怀念，前者可能让人**受伤**。
 *
 * 所以设计上做了三道限制：
 *  1. 毛病**只能由用户写**，系统不生成（见上）
 *  2. 冲突**只在重度交互后**发生（见 [ConflictScheduler]）
 *  3. 冲突**必然收场**，且收场方式也要用户定义（见 [MakeupStyle]）
 */

/**
 * 一条毛病。
 *
 * @param label 简短标签（"爱较真" / "嘴上不饶人"）—— 进上下文用
 * @param detail 具体表现（"一说到我的工作就要数落我，说我不该辞职"）
 * @param weight 这条毛病有多容易被触发（0..100）
 * @param hurtful 这条毛病伤不伤人。
 *
 *   ⚠️ **这一栏是安全阀**：`hurtful = true` 的毛病**永不自动触发**，
 *      只能由用户在会话里主动提起。理由见 [ConflictScheduler]。
 */
@Serializable
data class Flaw(
    val label: String,
    val detail: String,
    val weight: Int = 50,
    val hurtful: Boolean = false,
) {
    init {
        require(label.isNotBlank()) { "毛病必须有标签 —— 没有标签就没法在对话里被指认" }
        require(detail.isNotBlank()) { "毛病必须写清具体表现 —— 否则模型只能瞎编" }
        require(weight in 0..100) { "权重必须落在 0..100，当前 $weight" }
    }
}

/**
 * 吵完之后**怎么和好** —— 同样必须由用户定义。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"怎么和好"比"怎么吵"更重要
 * ═══════════════════════════════════════════════════════════════
 *
 * 一次没有收场的冲突，对使用者是**纯粹的伤害** ——
 * 他没法对一个已经不在的人把话说开，那段隔阂会一直挂着。
 *
 * ⇒ 所以每一场冲突都必须有**确定的收场方式**，而且必须是
 *   **那个人生前真实的和好方式**：
 *   - 有人吵完会主动递台阶（"吃饭了"）
 *   - 有人一辈子不会道歉，但会默默做事
 *   - 有人第二天跟没吵过一样
 *
 *   用系统默认的"对不起是我不好"来收场，是最 OOC 的一种。
 */
@Serializable
enum class MakeupStyle(
    val label: String,
    /** 给模型看的指示 —— 描述"这个人会怎么收场"。 */
    val instruction: String,
) {
    /** 主动递台阶，不说道歉 */
    OFFER_STEP("递个台阶",
        "你不会说对不起。你会用一件小事把话头接回来 —— 比如问一句吃饭了没有，" +
            "或者突然说件不相关的事。这就是你的道歉方式。"),

    /** 沉默一段时间，然后像没发生过 */
    SILENT_THEN_NORMAL("过一阵子就好了",
        "你会沉默一阵子，然后跟没吵过一样继续说别的。" +
            "你不会主动提那件事，也不希望对方提。"),

    /** 直接道歉（有些人是这样） */
    APOLOGIZE("直接道歉",
        "你会直说对不起。你不擅长绕弯子，觉得说开了就好。"),

    /** 用做事代替说话 */
    ACT_INSTEAD("用做的事代替说话",
        "你不道歉也不提那件事，但你会默默为对方做点什么 —— " +
            "倒杯水、把东西收拾好、留一盏灯。"),

    /** 嘴上还是不松，但态度软了 */
    STUBBORN_SOFTEN("嘴上不认但态度软了",
        "你嘴上还是不肯认，但语气会明显软下来，会主动找话说。"),
}

/**
 * 一次冲突的记录。
 *
 * ⚠️ 它必须**持久化**：用户下次来的时候，这个角色要"记得"吵过。
 *    不记得的话，那场冲突就变成了一次性的随机情绪 —— 正是最 OOC 的形态。
 *
 * @param flawLabel 是因为哪条毛病吵的
 * @param topic 吵的是什么（一句话）
 * @param atMs 什么时候
 * @param mentionsAtMs 每次被提起时记下时间 —— 用于"越提越少"的衰减
 * @param resolved 是否已经收场
 */
@Serializable
data class Conflict(
    val flawLabel: String,
    val topic: String,
    val atMs: Long,
    val mentionsAtMs: List<Long> = emptyList(),
    val resolved: Boolean = false,
) {
    init {
        require(flawLabel.isNotBlank()) { "冲突必须关联到具体毛病 —— 否则它就是随机情绪" }
        require(topic.isNotBlank()) { "冲突必须有具体话题" }
    }

    /** 被提起过几次。 */
    val mentionCount: Int get() = mentionsAtMs.size
}

/**
 * 冲突倾向 —— 那个人的"脾气参数"。
 *
 * ★ 这些数字**不是系统拍脑袋的**，而是由用户填的毛病推出来的
 *   （见 [ConflictScheduler.temperamentOf]）。用户填得越具体，它就越像。
 *
 * @param irritability 易怒程度（0..100）
 * @param grudgeDays 记仇多久（天）—— 决定那件事会被提多久
 * @param makeupStyle 怎么和好
 */
@Serializable
data class Temperament(
    val irritability: Int,
    val grudgeDays: Int,
    val makeupStyle: MakeupStyle,
) {
    init {
        require(irritability in 0..100) { "易怒程度必须落在 0..100，当前 $irritability" }
        require(grudgeDays >= 0) { "记仇天数不能为负：$grudgeDays" }
    }
}
