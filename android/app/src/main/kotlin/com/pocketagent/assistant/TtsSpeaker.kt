package com.pocketagent.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.pocketagent.provider.api.AudioChunk
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.SpeechRequest
import com.pocketagent.provider.api.TtsProvider
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * **把回复念出来** —— TTS 的播放端。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "还有 tts 的 api 接口也该加入了。"
 *
 * `:provider:tts-openai` 早就写好了、契约也很完整（流式合成、音色枚举、
 * 成本估算），但**没有任何消费方** —— 于是"角色会说话"这件事一直停在
 * 一个没人调用的接口上。本文件是它的第一个调用方。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 边收边播，而不是收完再播
 * ═══════════════════════════════════════════════════════════════
 *
 * `TtsProvider.synthesize` 返回的是 `Flow<AudioChunk>`，理由写在它的注释里：
 * 一句话可能有几百 KB，等整段合成完再播，用户会觉得"卡住了"。
 *
 * 所以这里用 `AudioTrack` 的**流式写**模式：每收到一块就写进去，
 * 开口延迟从"整句时长"降到"首包时长"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **不落盘**。音频只进扬声器，不写文件、不进日志
 *    （`TtsProvider` 的注释把音频定为与截图同级的高敏感数据）。
 * 2. **可以被立刻打断**。用户再说一句、或点停止，必须马上停 ——
 *    一个停不下来的声音比没有声音更烦。
 * 3. **失败就安静**。念不出来不是错误弹窗的理由：
 *    文字回复已经在屏幕上了，用户并没有少拿到东西。
 */
class TtsSpeaker(private val context: Context) {

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    /** 当前是否在念。 */
    val isSpeaking: Boolean get() = track != null

    /**
     * 念一段。
     *
     * ⚠️ 阻塞到念完或被 [stop] 打断；调用方必须在**后台线程**上调。
     *
     * @return true = 真的念过（至少写进去过一块）；false = 没念（无 Key/失败/被打断）
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun speak(
        provider: TtsProvider,
        credential: ProviderCredential,
        request: SpeechRequest,
    ): Boolean {
        stopped = false
        var wrote = false
        return try {
            kotlinx.coroutines.runBlocking {
                provider.synthesize(request, credential).collect { chunk ->
                    if (stopped) throw CancellationException("用户打断")
                    when (chunk) {
                        is AudioChunk.Audio -> {
                            if (!wrote) {
                                openTrack()
                                wrote = true
                            }
                            track?.write(chunk.bytes, 0, chunk.bytes.size)
                        }
                        // 结束/用量都不需要额外处理：写完就自然结束。
                        is AudioChunk.Done -> Unit
                    }
                }
            }
            wrote
        } catch (e: CancellationException) {
            Timber.d("朗读被用户打断")
            wrote
        } catch (t: Throwable) {
            // ★ 念不出来不弹错误 —— 文字已经在屏幕上了。
            Timber.w(t, "朗读失败（不打扰用户）")
            wrote
        } finally {
            closeTrack()
        }
    }

    /** 立刻停。 */
    fun stop() {
        stopped = true
        closeTrack()
    }

    private fun openTrack() {
        // ⚠️ 必须和请求里的格式对上：PCM 采样率写错会变成"快放/慢放"的怪声，
        //    而它听起来像"模型嗓子坏了"，不会让人想到是这里写错了数。
        //    OpenAI 兼容的 PCM 输出是 24kHz / 16bit / 单声道。
        val sampleRate = PCM_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(16 * 1024)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
    }

    private fun closeTrack() {
        val t = track
        track = null
        if (t != null) {
            runCatching { t.stop() }
            runCatching { t.release() }
        }
    }

    /**
     * 系统媒体音量（0..1），用来判断"用户把声音关了"。
     *
     * ★ 静音时**不播**：在静音的手机上突然出声，比不念更糟。
     */
    fun mediaVolume(): Float = runCatching {
        val am = context.getSystemService(AudioManager::class.java) ?: return 1f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }.getOrDefault(1f)

    companion object {
        /** OpenAI 兼容 TTS 的 PCM 输出格式：24kHz / 16bit / 单声道。 */
        const val PCM_SAMPLE_RATE = 24_000
    }
}
