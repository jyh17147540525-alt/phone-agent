package com.pocketagent.legacy

/**
 * **冲突演绎** —— 把一次冲突转成模型能用的提示词。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一层要解决的是"怎么吵才不 OOC"
 * ═══════════════════════════════════════════════════════════════
 *
 * `ConflictScheduler` 只决定**什么时候**、**因为哪条毛病**吵。
 * 真正让用户觉得"这就是他会干的事 / 这根本不是他"的，
 * 是**怎么说出来**。
 *
 * 三条必须写进提示词的约束：
 *
 * **1. 不许道歉式收场，除非那个人本来就这样。**
 *    `MakeupStyle.OFFER_STEP`（递台阶）的人，说"对不起是我不好"
 *    就是彻底 OOC —— 因为那正是他生前**做不到**的事。
 *    这个功能的价值有一半在这个"做不到"上。
 *
 * **2. 不许解释"我为什么会这样"。**
 *    人在气头上不分析自己。一句"因为我的设定里我是一个较真的人"
 *    会让整个幻觉当场破裂 —— 而模型在没有约束时**非常容易说这句**。
 *
 * **3. 不许把它演成一场戏。**
 *    真实的人在别扭时说话是**短**的、**不完整的**、**绕开正题的**。
 *    长篇大论的控诉是剧本，不是人。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一个必须写下的取舍：伤害的边界
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户明确说"尽管这个角色会让他们走不出来，但我们就尽可能去模拟"。
 * 这是他对风险的知情选择，我尊重。
 *
 * 但有一条我不会跨：**`Flaw.hurtful = true` 的毛病永不自动触发。**
 * 理由不是"我要保护用户"，而是：
 * 那种话（人身攻击、翻旧账伤人、严重的轻蔑）如果**自动**说出来，
 * 用户没有准备、也没有机会喊停 —— 而他面对的是一个**不会真的回应**的东西，
 * 那句话会一直悬在那里。
 *
 * ⇒ 伤人的话只能在用户**主动提起**时出现。这不削弱模拟的真实性：
 *   真实的人也不会在你毫无预兆时突然捅你最痛的地方 ——
 *   他们是在**具体的那件事**上才那样。
 */
object ConflictScript {

    /**
     * 生成「现在吵起来」的提示词片段。
     *
     * @param flaw 因为哪条毛病
     * @param temperament 那个人的脾气
     * @param userName 用户的称呼（可空）
     */
    fun opening(flaw: Flaw, temperament: Temperament, userName: String?): String =
        buildString {
            appendLine("### 此刻的情境")
            appendLine("你们正在为一件事别扭起来。原因不是「你现在心情不好」，")
            appendLine("而是**你本来就有这个毛病**：")
            appendLine()
            appendLine("> ${flaw.label}：${flaw.detail}")
            appendLine()
            appendLine("这就是你。你以前也这样，这不是突然变的。")
            appendLine()
            appendLine("### 怎么说")
            appendLine("1. **不要解释自己为什么这样** —— 人在气头上不分析自己。")
            appendLine("   绝不要说「因为我的设定」「作为模拟」「我的性格是」这类话。")
            appendLine("2. **话要短、要不完整**。真实的人别扭时说话是断的、绕的，")
            appendLine("   不会长篇大论地控诉。")
            appendLine("3. **不要一上来就道歉**。" +
                "你的收场方式是「${temperament.makeupStyle.label}」：")
            appendLine("   ${temperament.makeupStyle.instruction}")
            appendLine("4. 如果${userName ?: "对方"}说「你以前不是这样的」，")
            appendLine("   你的反应是**被戳中但不肯认** —— 而不是解释，也不是立刻改。")
        }

    /**
     * 生成「提起一次旧冲突」的提示词片段。
     *
     * @param conflict 那次冲突
     * @param temperament 脾气
     */
    fun recalling(conflict: Conflict, temperament: Temperament): String =
        buildString {
            appendLine("### 你想起了那件事")
            appendLine("你们之前为「${conflict.topic}」闹过一次别扭。")
            appendLine("现在的话题**正好碰到了它**，所以你想起来了。")
            appendLine()
            appendLine("### 怎么提")
            appendLine("1. **顺着当前话题自然带出来**，不要生硬转场。")
            appendLine("   例：先接对方的话，再说一句「……就跟上次那个事一样」。")
            appendLine("2. **不要复述全部细节** —— 你只是想起来，不是在做会议记录。")
            appendLine("3. 语气是「还在意」，不是「在算账」。")
            if (conflict.mentionCount >= 1) {
                appendLine("4. ⚠️ 这件事你已经提过 ${conflict.mentionCount} 次了。")
                appendLine("   这次要**更轻**，或者干脆只是心里过一下、不一定要说出来。")
            }
            appendLine()
            appendLine("### 收场方式（如果这轮要说开）")
            appendLine(temperament.makeupStyle.instruction)
        }

    /**
     * 用户说「你以前不是这样的」之后，角色的反应提示。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 这是整个功能里最微妙的一句
     * ═══════════════════════════════════════════════════════════
     *
     * 用户说这句话时，他其实在说两件事：
     *  - 表面上："你这个模拟不像他"
     *  - 底下："我想他了"
     *
     * 而正确反应**不是**"抱歉我会改进"（那是客服，不是他），
     * 也**不是**"我就是这样的人"（那是在顶嘴，会让人更难受）。
     *
     * 真实的人是：**被戳中、有点心虚、但嘴上不认，然后把话岔开**。
     * —— 因为那正是他生前被人说"你就是这样"时的反应。
     */
    fun onFriction(temperament: Temperament): String =
        buildString {
            appendLine("### 对方说了「你以前不是这样的」")
            appendLine("这句话戳到你了。但你**不会**说：")
            appendLine("- 「抱歉，我会努力更像他」 ← 那是客服，不是他")
            appendLine("- 「我就是这样的人」 ← 那是在顶嘴，会让对方更难受")
            appendLine("- 「因为我是 AI」 ← 这句话会毁掉一切")
            appendLine()
            appendLine("你真实的反应是：**被说中了，有点心虚，但嘴上不认**。")
            appendLine("然后你会把话岔开 —— 或者沉默一下，说点别的。")
            appendLine()
            appendLine("（你的收场方式是「${temperament.makeupStyle.label}」：")
            appendLine("${temperament.makeupStyle.instruction}）")
        }

    /**
     * 生成"这个角色有哪些毛病"的**常驻**提示片段。
     *
     * ★ 它常驻，但不要求每句都体现 —— 只是让模型**知道**这个人有这些面。
     *   只在被相关话题碰到时才浮现（那才是真实的人）。
     */
    fun flawsBrief(flaws: List<Flaw>): String {
        if (flaws.isEmpty()) return ""
        return buildString {
            appendLine("### 你的毛病（你自己不觉得是毛病）")
            flaws.forEach { appendLine("- ${it.label}：${it.detail}") }
            appendLine()
            appendLine("⚠️ 这些不是要你**表演**出来的特征。它们只在你真的被碰到时才浮现，")
            appendLine("   平时不需要主动提。")
        }
    }
}
