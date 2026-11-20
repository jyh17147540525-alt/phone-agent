package com.pocketagent.voicelogic

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PCM 首尾整形 —— 专门治"啪"那一声。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要它
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户真机原话："声刚合成出来的声音，马上打开会出现爆音"。
 *
 * 爆音的物理来源只有两种：
 *  1. **跳变** —— 波形从 0 直接跳到某个非零值（或反过来）。听感是"啪"。
 *     合成器输出通常带一点直流偏置或从半周期开始，头尾都不在零点上。
 *  2. **截断** —— 最后一个缓冲区里的音频还没来得及播完就被 release。
 *
 * 本条管第 1 种：给首尾各加一个很短的**线性淡入淡出**。8 ms 在语音上听不出来
 * （比一个字的起音还短），但足以把跳变压成渐变。
 *
 * ⚠️ 淡出长度不能太长：超过约 20 ms 会让人听到"最后一个字被吞掉"。
 * ⚠️ 只改首尾，中间一个采样都不动 —— 这不是"音量归一化"，不做任何压缩。
 */
object PcmShape {

    /** 默认淡入/淡出时长：8 毫秒。 */
    const val DEFAULT_FADE_MS = 8

    /**
     * 给 16bit 单声道 PCM 的首尾加线性淡入淡出。
     *
     * @param bytes 原始 PCM（小端 16bit）
     * @param sampleRate 采样率，用来把毫秒换算成采样数
     * @param fadeMs 首尾各淡多少毫秒
     * @return **新数组**（不改入参：缓存里存的是干净数据，播一次不能把缓存改了）
     */
    fun fadeEdges(bytes: ByteArray, sampleRate: Int, fadeMs: Int = DEFAULT_FADE_MS): ByteArray {
        val fadeSamples = (sampleRate.toLong() * fadeMs / 1000L).toInt()
        val totalSamples = bytes.size / 2
        if (fadeSamples <= 0 || totalSamples <= 2) return bytes

        // 素材比两段淡变还短时，从中间对半分，保证头尾都淡得到
        val edge = min(fadeSamples, totalSamples / 2)
        if (edge <= 0) return bytes

        val out = bytes.copyOf()
        for (i in 0 until edge) {
            // 淡入：第 i 个采样乘 i/edge
            scaleSample(out, i, i.toDouble() / edge)
            // 淡出：对称地从尾部往前
            scaleSample(out, totalSamples - 1 - i, i.toDouble() / edge)
        }
        return out
    }

    private fun scaleSample(buf: ByteArray, index: Int, factor: Double) {
        val at = index * 2
        if (at + 1 >= buf.size) return
        val raw = ((buf[at].toInt() and 0xFF) or (buf[at + 1].toInt() shl 8)).toShort().toInt()
        val scaled = (raw * factor).roundToInt().coerceIn(-32768, 32767)
        buf[at] = (scaled and 0xFF).toByte()
        buf[at + 1] = ((scaled shr 8) and 0xFF).toByte()
    }
}
