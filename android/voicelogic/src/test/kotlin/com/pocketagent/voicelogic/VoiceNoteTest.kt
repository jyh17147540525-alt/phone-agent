package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNoteTest {

    @Test
    fun `短回复只有一条语音`() {
        val clips = VoiceNote.plan("我在，你说。")
        assertEquals(1, clips.size)
        assertEquals("我在，你说。", clips.first().text)
    }

    @Test
    fun `空回复一条都不切`() {
        assertTrue(VoiceNote.plan("   ").isEmpty())
    }

    @Test
    fun `超过六十秒的回复会切成多条`() {
        // 400 字 ≈ 95 秒 ⇒ 必须切开
        val long = (1..40).joinToString("") { "这是第${it}句话，用来把长度撑起来。" }
        val clips = VoiceNote.plan(long)
        assertTrue("应切成多条，实际 ${clips.size}", clips.size >= 2)
        clips.forEach {
            assertTrue("单条估算 ${it.estimateMs}ms 超了 60 秒", it.estimateMs <= 61_000)
        }
    }

    @Test
    fun `最多只切八条`() {
        val huge = (1..400).joinToString("") { "这是第${it}句话。" }
        assertTrue(VoiceNote.plan(huge).size <= VoiceNote.MAX_CLIPS)
    }

    @Test
    fun `切出来的字不丢`() {
        val long = (1..30).joinToString("") { "第${it}句。" }
        val joined = VoiceNote.plan(long).joinToString("") { it.text }
        assertEquals(long.replace(" ", ""), joined.replace(" ", "").replace("。", "。"))
    }

    @Test
    fun `估算按时长给出一条合理值`() {
        // 42 字 ≈ 10 秒
        val ms = VoiceNote.estimateMs("一".repeat(42))
        assertTrue("估算 $ms", ms in 9_000..11_000)
    }

    @Test
    fun `真实时长按字节回算`() {
        // 24kHz 16bit 单声道 ⇒ 每秒 48000 字节
        assertEquals(1_000, VoiceNote.pcmDurationMs(48_000))
        assertEquals(60_000, VoiceNote.pcmDurationMs(2_880_000))
        assertEquals(0, VoiceNote.pcmDurationMs(0))
        assertEquals(0, VoiceNote.pcmDurationMs(-5))
    }

    @Test
    fun `时长显示成微信那样`() {
        assertEquals("9\u2033", VoiceNote.formatDuration(9_200))
        assertEquals("1\u2032 05\u2033".replace(" ", ""), VoiceNote.formatDuration(65_000))
        assertEquals("0\u2033", VoiceNote.formatDuration(0))
    }

    @Test
    fun `六十字上限可调`() {
        val text = (1..20).joinToString("") { "第${it}句话。" }
        val short = VoiceNote.plan(text, maxSeconds = 5)
        assertTrue(short.size > 1)
        short.forEach { assertTrue(it.estimateMs <= 6_000) }
    }
}
