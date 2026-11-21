package com.pocketagent.ui.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyViewModeTest {

    @Test
    fun `三档的顺序是 文字 两样 语音`() {
        assertEquals(
            listOf(ReplyViewMode.TEXT_ONLY, ReplyViewMode.BOTH, ReplyViewMode.VOICE_ONLY),
            ReplyViewMode.ordered,
        )
    }

    @Test
    fun `未知值与空值都退回两样都要`() {
        assertEquals(ReplyViewMode.BOTH, ReplyViewMode.of(null))
        assertEquals(ReplyViewMode.BOTH, ReplyViewMode.of(""))
        assertEquals(ReplyViewMode.BOTH, ReplyViewMode.of("whatever"))
    }

    @Test
    fun `落盘的键读回来还是同一档`() {
        ReplyViewMode.ordered.forEach { mode ->
            assertEquals(mode, ReplyViewMode.of(mode.key))
        }
    }

    @Test
    fun `滑条位置越界时夹到两端`() {
        assertEquals(ReplyViewMode.TEXT_ONLY, ReplyViewMode.at(-3))
        assertEquals(ReplyViewMode.VOICE_ONLY, ReplyViewMode.at(99))
        assertEquals(ReplyViewMode.BOTH, ReplyViewMode.at(1))
    }
}
