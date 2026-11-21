package com.pocketagent.data

import com.pocketagent.core.database.entity.SessionEntity
import com.pocketagent.personalogic.ChatMode

/**
 * 「这一页是哪个模式」→「会话表里那个 mode 值」。
 *
 * ★ 2026-11-21：只此一处翻译。
 *
 * 以前这条翻译写了两份：`ChatPort` 里一份（`isWork ? WORK : ROLEPLAY`），
 * `TasksScreen` 里一份（`dedicatedTitle == null ? ROLEPLAY : WORK`）。
 * 两份对上之前，在场模式的会话被**一处写成 ROLEPLAY、另一处当成 WORK** ——
 * 结果是三条记录线互相都看不见对方，用户点开历史只有聊天页是好的。
 */
object SessionModes {
    fun of(mode: ChatMode): String = when {
        mode.isWork -> SessionEntity.MODE_WORK
        mode.isAlive -> SessionEntity.MODE_LIFEFORM
        else -> SessionEntity.MODE_ROLEPLAY
    }

    /** 这一页是不是"自己一条线"（工作台/在场各自认领一条会话）。 */
    fun dedicated(mode: ChatMode): Boolean = of(mode) != SessionEntity.MODE_ROLEPLAY
}
