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
    /**
     * **把内部的长静音压短，首尾静音整段剪掉。**
     *
     * 真机实测（2026-11-20，`e8bc38c0…pcm`，487 680 字节 / 24kHz 单声道 / 10.16 秒）：
     * 服务商返回的音频里，句子之间塞着 0.5 秒、1.15 秒的纯数字静音。
     * 用户听到的是"整个音频前半段有一段空白" —— 那不是我们加的，
     * 是服务商自己在句子之间停顿，而我们的 60 秒分条把多句装进了同一条。
     *
     * 规则：连续静音 > [minRunMs] 的，压到 [keepMs]（保留一点气口，不然像卡带）；
     * 开头结尾的静音直接剪掉 —— 用户明确要求"空白只留在结尾"，
     * 而结尾那 2 秒由调用方自己补（[com.pocketagent.voicelogic.VoiceNote.TAIL_PAD_MS]）。
     *
     * @param threshold 判定为静音的峰值。真机静音段峰值都在 60 以内，正常说话 5000 以上。
     */
    fun collapseSilence(
        bytes: ByteArray,
        sampleRate: Int = 24_000,
        bytesPerSample: Int = 2,
        keepMs: Int = 120,
        minRunMs: Int = 400,
        threshold: Int = 120,
    ): ByteArray {
        if (bytes.size < bytesPerSample * 2) return bytes
        val step = bytesPerSample.coerceAtLeast(1)
        val total = bytes.size / step
        fun sampleAt(i: Int): Int {
            val lo = bytes[i * step].toInt() and 0xFF
            val hi = bytes[i * step + 1].toInt()
            return (hi shl 8) or lo
        }
        val frameMs = 10
        val frameSamples = (sampleRate / 1000 * frameMs).coerceAtLeast(1)
        val frames = total / frameSamples
        if (frames < 4) return bytes
        val quiet = BooleanArray(frames)
        for (f in 0 until frames) {
            var peak = 0
            val base = f * frameSamples
            for (i in 0 until frameSamples) {
                val v = kotlin.math.abs(sampleAt(base + i))
                if (v > peak) peak = v
            }
            quiet[f] = peak < threshold
        }
        val minRunFrames = (minRunMs / frameMs).coerceAtLeast(1)
        val keepFrames = (keepMs / frameMs).coerceAtLeast(0)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var f = 0
        // 开头静音：整段丢
        while (f < frames && quiet[f]) f++
        while (f < frames) {
            if (!quiet[f]) {
                out.write(bytes, f * frameSamples * step, frameSamples * step)
                f++
                continue
            }
            var end = f
            while (end < frames && quiet[end]) end++
            val run = end - f
            val atTail = end >= frames
            if (atTail) {
                // 结尾静音丢掉，由调用方补固定长度
            } else if (run > minRunFrames) {
                var k = 0
                while (k < keepFrames) {
                    out.write(bytes, (f + k.coerceAtMost(run - 1)) * frameSamples * step, frameSamples * step)
                    k++
                }
            } else {
                out.write(bytes, f * frameSamples * step, run * frameSamples * step)
            }
            f = end
        }
        return out.toByteArray()
    }

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
