package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L0 原始对话层的测试。
 *
 * 重点覆盖三件容易写错的事：
 * - **顺序**：L0 的顺序就是真实发生的顺序，`recent()` 取的是尾部而不能反过来
 * - **幂等**：L0 的写入会重试，重试撞上"已写入"是正常路径，不是崩溃
 * - **字节口径**：卸载阈值按 UTF-8 字节算，一个汉字 3 字节
 */
class TurnLogTest {

    private fun turn(
        id: String,
        text: String = "内容",
        session: String = "s1",
        role: TurnRole = TurnRole.USER,
        at: Long = 0L,
    ) = Turn(id = id, sessionId = session, role = role, text = text, at = at)

    /** 记录收到的每一轮 —— 用来断言"重复的那一轮没有再落库"。 */
    private class RecordingSink : TurnSink {
        val received = mutableListOf<String>()
        override fun append(turn: Turn) {
            received += turn.id
        }
    }

    // ── 顺序与读取 ─────────────────────────────────────────────

    @Test
    fun `追加与读取保持发生顺序`() {
        val log = TurnLog()
        log.append(turn("t1", at = 1L))
        log.append(turn("t2", at = 2L))
        log.append(turn("t3", at = 3L))

        assertEquals(listOf("t1", "t2", "t3"), log.all().map { it.id })
        assertEquals(3, log.size())
    }

    @Test
    fun `recent 返回最后 n 条且保持时间顺序`() {
        val log = TurnLog()
        for (i in 1..5) log.append(turn("t$i", at = i.toLong()))

        // ⚠️ 刻意断言"最早的在前"。反过来（倒序）会让提示词里对话看起来
        //    是从后往前发生的，而模型不会报错，只会答得莫名其妙。
        assertEquals(listOf("t3", "t4", "t5"), log.recent(3).map { it.id })
    }

    @Test
    fun `recent 为 0 时返回空而不是全部`() {
        val log = TurnLog()
        log.append(turn("t1"))
        assertTrue(log.recent(0).isEmpty())
    }

    @Test
    fun `按会话过滤`() {
        val log = TurnLog()
        log.append(turn("t1", session = "a"))
        log.append(turn("t2", session = "b"))
        log.append(turn("t3", session = "a"))

        assertEquals(listOf("t1", "t3"), log.bySession("a").map { it.id })
        assertTrue(log.bySession("c").isEmpty())
    }

    @Test
    fun `contains 能识别已写入的轮次`() {
        val log = TurnLog()
        log.append(turn("t1"))
        assertTrue(log.contains("t1"))
        assertFalse(log.contains("t2"))
    }

    // ── 幂等 ───────────────────────────────────────────────────

    @Test
    fun `重复 id 不重复落库且返回 false`() {
        val sink = RecordingSink()
        val log = TurnLog(sink)

        assertTrue("第一次写入应成功", log.append(turn("t1", text = "第一次")))
        assertFalse("同 id 重试应被识别为已写入", log.append(turn("t1", text = "重试")))

        assertEquals(1, log.size())
        assertEquals("重试不该再写一次 sink", listOf("t1"), sink.received)
        assertEquals("保留的是第一次的内容", "第一次", log.all().single().text)
    }

    // ── 字节口径 ───────────────────────────────────────────────

    @Test
    fun `byteSize 是 UTF-8 字节数而不是字符数`() {
        assertEquals(3, turn("t1", text = "中").byteSize)
        assertEquals(6, turn("t2", text = "中文").byteSize)
        assertEquals(1, turn("t3", text = "a").byteSize)
    }

    @Test
    fun `oversized 按 UTF-8 字节筛选`() {
        val log = TurnLog()
        // 100 个汉字 = 300 字节；100 个 ASCII = 100 字节。
        // 阈值 200 时只有前者该被选出来 —— 若按字符数算，两者都是 100，谁也选不出来。
        log.append(turn("cn", text = "中".repeat(100)))
        log.append(turn("ascii", text = "a".repeat(100)))

        assertEquals(listOf("cn"), log.oversized(200).map { it.id })
    }

    @Test
    fun `oversized 不修改任何内容`() {
        val log = TurnLog()
        log.append(turn("t1", text = "中".repeat(100)))
        log.oversized(0)
        assertEquals(1, log.size())
        assertEquals(100, log.all().single().text.length)
    }

    // ── 构造校验 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `空 id 构造失败`() {
        turn("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空会话号构造失败`() {
        turn("t1", session = "")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `负的卸载阈值被拒绝`() {
        TurnLog().oversized(-1)
    }
}