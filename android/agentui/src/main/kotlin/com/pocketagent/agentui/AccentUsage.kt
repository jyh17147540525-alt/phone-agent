package com.pocketagent.agentui


/**
 * **强调色用途规则** —— 背景用 `Accent`，文字用 `AccentText`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么这条规则需要被"记住"而不是"判断"
 * ═══════════════════════════════════════════════════════════════
 *
 * 一个颜色不可能同时满足两种要求（物理约束）：
 *  · 作**背景**要够浅 —— 上面浮着深色文字
 *  · 作**文字**要够深 —— 压在暖米底上要 4.5 对比度
 *
 * ⇒ 拆成两个令牌解决了冲突。但**下一个写界面的人**看到的只是
 *   `PaColor.Accent` 和 `PaColor.AccentText` 两个名字，
 *   他凭什么知道哪个用在哪？
 *
 * ⚠️ 用错地方的症状：文字发淡、强光下看不清 ——
 *   而设计评审时"颜色差不多"很容易滑过去。
 *
 * ⇒ 本文件把"用途 → 令牌"的映射写成可判定的规则，
 *   并给出**能贴进 review 的具体判据**（见 [colorFor]）。
 *
 * ★ 它不能真的去读 Compose 源码（那是编译期的事），
 *   但它是**审核时的口径**：review 时按这个判据看，比凭感觉一致。
 */
object AccentUsage {

    /** 一个颜色在这个位置是干什么用的。 */
    enum class Purpose {
        /** 背景填充：按钮底、选中块、指示器。 */
        BACKGROUND,

        /** 文字：标签、链接、按钮文字。 */
        TEXT,

        /**
         * 图形元素：图标、描边、进度圈、参考线。
         *
         * ⚠️ 这一档是**独立的**，不是"背景"也不是"文字"：
         *   WCAG 对非文本内容的要求是 **3.0**（不是 4.5）。
         *   把它当成文字会过度约束（图标只能用深色，V 显得闷）；
         *   当成背景则会漏掉该有的可见度。
         */
        GRAPHIC,
    }

    /** 该用哪个令牌。 */
    enum class Token {
        /** 原色 #C96442 / 深色主题 #D9785A。 */
        ACCENT,

        /** 加深版 #A34A28 / 深色主题复用 #D9785A。 */
        ACCENT_TEXT,
    }

    /**
     * **给定用途，该用哪个令牌。**
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 这就是 review 口径
     * ═══════════════════════════════════════════════════════════
     *
     * | 用途 | 令牌 | 理由 |
     * |---|---|---|
     * | 背景 | `Accent` | 对比度与它无关（它是底） |
     * | 文字 | `AccentText` | 必须过 AA 4.5 |
     * | 图形 | `Accent` | 只需 3.0，原色已满足（3.49） |
     *
     * ⚠️ 最反直觉的是**图形用原色**：很多人会想"图标也算内容，
     *    应该也用加深版"。但那会让图标变得沉闷，而且 WCAG 明确
     *    区分文本（4.5）与非文本（3.0）。
     */
    fun colorFor(purpose: Purpose): Token = when (purpose) {
        Purpose.BACKGROUND -> Token.ACCENT
        Purpose.TEXT -> Token.ACCENT_TEXT
        Purpose.GRAPHIC -> Token.ACCENT
    }

    /**
     * 这个用法**是不是错的**。
     *
     * @param purpose 代码里实际把它当什么用
     * @param used 代码里实际用了哪个令牌
     */
    fun isMisuse(purpose: Purpose, used: Token): Boolean =
        colorFor(purpose) != used

    /**
     * 用错时的**具体后果**（给 review 评论用）。
     *
     * ★ 只说"用错了"没用。要说清"会怎样" ——
     *   否则对方无法判断严重性，可能就放着不改了。
     */
    fun consequence(purpose: Purpose, used: Token): String? {
        if (!isMisuse(purpose, used)) return null
        return when {
            // 文字用了背景色 —— 唯一真正违反 AA 的组合
            purpose == Purpose.TEXT && used == Token.ACCENT ->
                "文字会发淡：Accent 在暖米底上只有 3.49，低于 AA 的 4.5。" +
                    "强光下、小字号、视力不佳时读不清。改成 AccentText（5.27）。"

            // 背景用了文字色 —— 不违反规则，但会显闷
            purpose == Purpose.BACKGROUND && used == Token.ACCENT_TEXT ->
                "底色偏深：按钮/选中块会显得沉闷。背景不改对比度，用 Accent 即可。"

            // 图形用了文字色 —— 同样只是观感问题
            purpose == Purpose.GRAPHIC && used == Token.ACCENT_TEXT ->
                "图标/描边偏闷：非文本只需 3.0，原色已满足。用 Accent 更通透。"

            else -> "用途与令牌不匹配"
        }
    }
}

