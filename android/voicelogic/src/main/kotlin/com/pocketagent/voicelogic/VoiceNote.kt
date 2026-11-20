package com.pocketagent.voicelogic

/**
 * **语音条** —— 把一段回复切成"一条条能听的语音"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "就像微信那样的语音，语音下面还能显示文本……语音的时间上限最高 60 秒，
 *  可以一次性发送多条语音"。
 *
 * 微信的语音条有三个特征，缺一个就不像：
 *   ① **一条不超过 60 秒** —— 超了就切成几条，而不是压缩成一条长的；
 *   ② **时长看得见** —— 条子上写着「12″」，用户据此决定要不要点；
 *   ③ **下面还有字** —— 听不见的时候能读，这是"语音条"和"语音文件"的区别。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这里只算"怎么切"和"多久"，不算音频
 * ═══════════════════════════════════════════════════════════════
 *
 * 切成几条、每条多长，是**纯算术**（可离线验证）；真正合成要网络与 Key，
 * 那是 `:app` 的事。分开的理由和 `:legacy/ProactivePlanner` 一样：
 * 判断错了要能用测试抓住，而不是靠听。
 *
 * 字数估时长用的是中文语速经验值（[CHARS_PER_SECOND]，约每秒 4.2 字）。
 * 估偏的代价可控：它只影响"切在哪"，而每条的真实时长由合成后
 * 的 PCM 字节数回算（见 [pcmDurationMs]）—— 界面显示的是回算值。
 */
object VoiceNote {

    /** 一条语音的上限：60 秒（用户明确要求）。 */
    const val MAX_SECONDS_PER_CLIP: Int = 60

    /** 中文朗读语速：每秒约 4.2 个字（新闻联播 5 字/秒偏快，日常对话慢一些）。 */
    const val CHARS_PER_SECOND: Float = 4.2f

    /** 一条回复最多切几条 —— 再多就是"刷屏"，用户要的是一条消息里几条，不是十几条。 */
    const val MAX_CLIPS: Int = 8

    /** OpenAI 兼容 TTS 的裸 PCM 输出：24 kHz / 16 bit / 单声道。 */
    const val PCM_SAMPLE_RATE: Int = 24_000
    const val PCM_BYTES_PER_SAMPLE: Int = 2
    const val PCM_CHANNELS: Int = 1

    /** 一条语音：说什么、大概多久。 */
    data class Clip(val text: String, val estimateMs: Int)

    /**
     * 按句切。
     *
     * ⚠️ 只在**句子边界**上切：从句子中间切开会被听成"话说到一半断了"，
     *   而那是"网络卡了"的听感，不是"分成两条"的听感。
     *
     * 一句话本身就超过 60 秒（罕见，约 250 字）时才按字数硬切。
     */
    fun plan(text: String, maxSeconds: Int = MAX_SECONDS_PER_CLIP): List<Clip> {
        val budget = (maxSeconds.coerceAtLeast(1) * CHARS_PER_SECOND).toInt().coerceAtLeast(1)
        val body = text.trim()
        if (body.isEmpty()) return emptyList()

        val out = ArrayList<Clip>()
        val buf = StringBuilder()

        fun flush() {
            val s = buf.toString().trim()
            buf.setLength(0)
            if (s.isNotEmpty()) out.add(Clip(s, estimateMs(s)))
        }

        body.split(SENTENCE_END).forEach { piece ->
            val sentence = piece.trim()
            if (sentence.isEmpty()) return@forEach
            if (sentence.length > budget) {
                // 单句超长：先把手上的话说完，再把这句切块
                flush()
                var rest = sentence
                while (rest.length > budget) {
                    out.add(Clip(rest.take(budget), estimateMs(rest.take(budget))))
                    rest = rest.drop(budget)
                }
                if (rest.isNotEmpty()) buf.append(rest)
                return@forEach
            }
            if (buf.length + sentence.length + 1 > budget) flush()
            // ⚠️ 不再补句号：切分用的是 lookbehind，标点本来就在句子尾巴上。
            //    再补一个会变成「。。」—— 合成出来是明显的长停顿。
            buf.append(sentence)
        }
        flush()

        // 切太多条反而难用：并回最后几条（并出来会超 60 秒的，就让最后一条略长 ——
        // 由真实时长回算后再决定要不要显示，不在这里硬塞）。
        if (out.size > MAX_CLIPS) {
            val head = out.subList(0, MAX_CLIPS - 1).toList()
            val tailText = out.subList(MAX_CLIPS - 1, out.size).joinToString("") { it.text }
            return head + Clip(tailText, estimateMs(tailText))
        }
        return out
    }

    /** 估算一段话念多久。 */
    fun estimateMs(text: String): Int {
        val chars = text.count { !it.isWhitespace() }
        if (chars == 0) return 0
        return (chars / CHARS_PER_SECOND * 1000f).toInt().coerceAtLeast(MIN_CLIP_MS)
    }

    /**
     * 合成之后**按真实字节数回算时长**。
     *
     * ⚠️ 界面显示必须用这个，不能用估算值：估出来的 12 秒播出来 17 秒，
     *   用户会以为进度条坏了 —— 而这只值一次减法。
     */
    fun pcmDurationMs(
        byteCount: Int,
        sampleRate: Int = PCM_SAMPLE_RATE,
        channels: Int = PCM_CHANNELS,
        bytesPerSample: Int = PCM_BYTES_PER_SAMPLE,
    ): Int {
        val perSecond = sampleRate * channels * bytesPerSample
        if (perSecond <= 0 || byteCount <= 0) return 0
        return (byteCount.toLong() * 1000L / perSecond).toInt()
    }

    /** 「12″」/「1′05″」——微信就是这种写法，不是「12 秒」。 */
    fun formatDuration(ms: Int): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        return if (total < 60) "$total\u2033" else "${total / 60}\u2032${"%02d".format(total % 60)}\u2033"
    }

    private const val MIN_CLIP_MS = 1_000

    /** 句末标点（保留标点本身，读起来才有停顿）。 */
    private val SENTENCE_END = Regex("(?<=[。！？!?…；;\n])")
}
