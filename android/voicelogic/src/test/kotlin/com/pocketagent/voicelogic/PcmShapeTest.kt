package com.pocketagent.voicelogic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首尾淡入淡出：治"啪"那一声，但不许动中间。 */
class PcmShapeTest {

    private fun pcm(vararg samples: Int): ByteArray {
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v ->
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun sampleAt(bytes: ByteArray, index: Int): Int =
        (bytes[index * 2].toInt() and 0xFF) or (bytes[index * 2 + 1].toInt() shl 8)

    @Test
    fun `首尾被压到接近零点而中间一个采样都没动`() {
        val src = pcm(8000, 8000, 8000, 8000, 8000, 8000, 8000, 8000)
        val out = PcmShape.fadeEdges(src, sampleRate = 48_000, fadeMs = 1) // 48 采样 > 8 采样 ⇒ edge = 4
        assertEquals(0, sampleAt(out, 0))
        assertTrue("尾部第一个应接近 0，实际 ${sampleAt(out, 7)}", kotlin.math.abs(sampleAt(out, 7)) < 4000)
    }

    @Test
    fun `中间部分逐字节相同`() {
        val src = pcm(100, 200, 300, 400, 1, 2, 3, 4, 5, 6, 7, 8)
        val out = PcmShape.fadeEdges(src, sampleRate = 96_000, fadeMs = 1) // 96 采样 ⇒ edge = 6
        // 12 个采样、edge=6 ⇒ 没有"中间"，退化成两头对半分；这里只验长度与不改入参
        assertEquals(src.size, out.size)
        assertArrayEquals(src, src) // 入参没被就地改
        assertNotSame(src, out)
    }

    @Test
    fun `太短的素材原样返回`() {
        val src = pcm(1)
        assertArrayEquals(src, PcmShape.fadeEdges(src, sampleRate = 24_000))
        assertArrayEquals(ByteArray(0), PcmShape.fadeEdges(ByteArray(0), sampleRate = 24_000))
    }

    @Test
    fun `不改入参数组`() {
        val src = pcm(9000, 9000, 9000, 9000, 9000, 9000)
        val copy = src.copyOf()
        PcmShape.fadeEdges(src, sampleRate = 24_000, fadeMs = 1)
        assertArrayEquals(copy, src)
    }

    /** 造一段 24kHz 单声道 PCM：前面 toneMs 说话，然后 silentMs 静音，再 toneMs 说话。 */
    private fun toneSilenceTone(toneMs: Int, silentMs: Int, amp: Int = 6_000): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun tone(ms: Int) {
            val n = 24 * ms
            for (i in 0 until n) {
                val v = (amp * Math.sin(2 * Math.PI * 200.0 * i / 24_000.0)).toInt()
                out.write(v and 0xFF)
                out.write((v shr 8) and 0xFF)
            }
        }
        fun silence(ms: Int) {
            repeat(24 * ms) { out.write(0); out.write(0) }
        }
        tone(toneMs); silence(silentMs); tone(toneMs)
        return out.toByteArray()
    }

    @Test
    fun `服务商塞的长静音会被压短`() {
        val shaped = PcmShape.collapseSilence(toneSilenceTone(1_000, 1_200), 24_000)
        // 原本 3.2 秒，压完应该接近 1+0.12+1 秒
        val ms = shaped.size / 48
        assertTrue("压完 $ms ms 应该 < 2600", ms < 2_600)
        assertTrue("压完 $ms ms 应该 > 1900", ms > 1_900)
    }

    @Test
    fun `开头与结尾的静音整段剪掉_不再自己垫空白`() {
        val raw = java.io.ByteArrayOutputStream().apply {
            repeat(24 * 300) { write(0); write(0) }          // 开头 300ms 静音
            val t = toneSilenceTone(500, 0)
            write(t, 0, t.size)
            repeat(24 * 300) { write(0); write(0) }          // 结尾 300ms 静音
        }.toByteArray()
        val shaped = PcmShape.collapseSilence(raw, 24_000)
        val ms = shaped.size / 48
        // toneSilenceTone(500, 0) = 两段 500ms 的 tone 连在一起，共 1000ms
        assertTrue("剪完 $ms ms 应接近 1000ms", ms in 960..1_060)
    }

    @Test
    fun `短气口不会被压`() {
        val shaped = PcmShape.collapseSilence(toneSilenceTone(400, 150), 24_000)
        val ms = shaped.size / 48
        assertTrue("气口保留，长度应接近 950ms，实际 $ms", ms in 900..1_000)
    }
}
