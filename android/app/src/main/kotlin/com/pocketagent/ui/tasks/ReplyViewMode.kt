package com.pocketagent.ui.tasks

/**
 * 聊天界面里，一轮回复该露出哪一部分。
 *
 * 说话的方式因人而异：有人只想读字，有人在地铁上只听声音，
 * 有人两样都要。把它做成**一个滑条**而不是三层设置项 —— 这是
 * 用户会随时改的东西，藏进设置页等于没有。
 */
enum class ReplyViewMode(
    val key: String,
    val label: String,
) {
    TEXT_ONLY("text", "只看文字"),
    BOTH("both", "文字 + 语音"),
    VOICE_ONLY("voice", "只听语音"),
    ;

    companion object {
        val ordered: List<ReplyViewMode> = listOf(TEXT_ONLY, BOTH, VOICE_ONLY)

        fun of(raw: String?): ReplyViewMode =
            ordered.firstOrNull { it.key == raw } ?: BOTH

        fun indexOf(mode: ReplyViewMode): Int = ordered.indexOf(mode)

        fun at(index: Int): ReplyViewMode = ordered[index.coerceIn(0, ordered.lastIndex)]
    }
}
