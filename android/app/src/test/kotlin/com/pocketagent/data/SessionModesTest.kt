package com.pocketagent.data

import com.pocketagent.core.database.entity.SessionEntity
import com.pocketagent.personalogic.ChatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话模式翻译。
 *
 * ★ 2026-11-21：这三条断言对应一个真机 bug —— 在场模式的会话以前被写成 WORK，
 *   于是"在场"和"工作台"共用一本账，用户点开历史两边都空的。
 */
class SessionModesTest {

    @Test
    fun `三种模式各自一个值`() {
        assertEquals(SessionEntity.MODE_ROLEPLAY, SessionModes.of(ChatMode.ROLEPLAY))
        assertEquals(SessionEntity.MODE_WORK, SessionModes.of(ChatMode.WORK))
        assertEquals(SessionEntity.MODE_LIFEFORM, SessionModes.of(ChatMode.LIFEFORM))
    }

    @Test
    fun `三个值互不相同`() {
        val all = ChatMode.entries.map { SessionModes.of(it) }.toSet()
        assertEquals("有模式共用了同一个值，它们的历史会混在一起", 3, all.size)
    }

    @Test
    fun `只有聊天模式不是自己一条线`() {
        assertFalse(SessionModes.dedicated(ChatMode.ROLEPLAY))
        assertTrue(SessionModes.dedicated(ChatMode.WORK))
        assertTrue(SessionModes.dedicated(ChatMode.LIFEFORM))
    }
}
