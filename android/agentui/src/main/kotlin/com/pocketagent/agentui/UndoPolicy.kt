package com.pocketagent.agentui

/**
 * 哪些动作能撤销，哪些不能。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 假的撤销比没有撤销更糟
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户看到"撤销"两个字，就会放心地让助理去做。如果那个按钮实际上撤不回来
 * （消息已经发出去了、表单已经提交了），他失去的不是一次操作，
 * 而是**对"撤销"这个词的信任** —— 下次真正能撤销时他也不敢信了。
 *
 * ⇒ 只有真正能撤回原状的三类动作才允许出现撤销入口；其余一律如实标注。
 */
object UndoPolicy {

    /** 撤销的结论。 */
    sealed interface Verdict {
        /** 可以撤销。 */
        data class Undoable(val how: String) : Verdict

        /**
         * 不可撤销。
         *
         * @param why 必须能直接展示给用户的一句话
         */
        data class NotUndoable(val why: String) : Verdict
    }

    /**
     * 按动作种类判定。
     *
     * ★ 这里的分类刻意**只认"能不能真的回到原状"**，不认"重不重要"。
     */
    fun verdict(action: ActionClass): Verdict = when (action) {
        // 输入框里的字：删掉即可，界面回到原样。
        ActionClass.TEXT_ENTRY -> Verdict.Undoable("把输入框清空即可")
        // 草稿 / 未提交的表单：本地状态，没有对外副作用。
        ActionClass.DRAFT -> Verdict.Undoable("清掉草稿即可")
        // 启动应用 / 打开页面：退出即可，没有对外副作用。
        ActionClass.LAUNCH -> Verdict.Undoable("退出去即可")
        // 点击：我们无法知道它触发了什么（可能是一次提交）。
        ActionClass.TAP -> Verdict.NotUndoable("已经点下去了，界面可能已经变了")
        // 滑动：同上，且可能已经触发了"删除"这类手势。
        ActionClass.SWIPE -> Verdict.NotUndoable("已经滑过去了，界面可能已经变了")
        // 对外发送：对方的手机已经收到了。
        ActionClass.SEND -> Verdict.NotUndoable("对方可能已经看到了")
        // 删除：很多应用的删除不进回收站。
        ActionClass.DELETE -> Verdict.NotUndoable("有些应用删掉就不留回收站")
        // 支付：绝对不可撤销，且我们根本不做。
        ActionClass.PAY -> Verdict.NotUndoable("付款不能由我撤回")
    }

    /** 这个动作种类是否可撤销（界面据此决定要不要画撤销按钮）。 */
    fun isUndoable(action: ActionClass): Boolean = verdict(action) is Verdict.Undoable
}

/** 动作种类 —— 只按"能不能回到原状"分类。 */
enum class ActionClass {
    TEXT_ENTRY,
    DRAFT,
    LAUNCH,
    TAP,
    SWIPE,
    SEND,
    DELETE,
    PAY,
}
