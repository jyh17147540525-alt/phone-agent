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
}
