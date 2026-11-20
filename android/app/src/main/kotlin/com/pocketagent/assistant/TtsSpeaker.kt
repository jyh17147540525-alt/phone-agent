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
                                synchronized(lock) { closeTrackLocked(); openTrackLocked() }
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
            synchronized(lock) { closeTrackLocked() }
        }
    }

    /**
     * 播**已经合成好的一段 PCM**（语音条用）。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么语音条不复用 [speak]
     * ═══════════════════════════════════════════════════════════════
     *
     * 语音条要的是「点了就出声、再点就停、时长写在条子上」。这三点都要求
     * 音频**已经在本地**：现场合成没法显示真实时长，也没法"再点一下停"。
     * 所以合成与播放分成两步，这里只负责第二步。
     *
     * ⚠️ 仍然遵守本类的老规矩：**不落盘**（落盘由调用方缓存，见 VoiceNoteStore）、
     *    **可被打断**（[stop] 会把循环打断）、**失败就安静**。
     *
     * @return true = 真的播过
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun playPcm(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return false
        //   播放第一遍才会，播放第二遍就不会了"。
        //
        //   同一条字节、同一段代码，第一遍沙沙、第二遍正常 ⇒ **不是数据问题**。
        //   （缓存里抽查过：首尾采样都在 ±10 以内，也不是容器格式冒充 PCM。）
        //   剩下的是 AudioTrack 这条路的起播状态：它要求调用方把采样率/声道
        //   说对，而首播那一下正好撞在输出通路上电、采样率未收敛的窗口里。
        //
        //   ⇒ 换成 **MediaPlayer 播一个标准 WAV**：格式转换与输出路由交给系统媒体栈，
        //     它自己会等设备就绪；我们不再手写 AudioTrack 的 24kHz 假设。
        //     WAV 头是死的 44 字节，字节内容仍是服务商原样 PCM —— 不重新编码。
        stopped = false
        //   抽查缓存（487680 字节 / 24kHz / 10.16s）证明：空白是**服务商给的** ——
        //   0.90s 起 0.50s、5.65s 起 1.15s 的纯数字静音（峰值 <60），不是我们垫的。
        //   所以这里先压掉内部长静音、剪掉首尾静音，**不额外垫开头**；
        //   结尾那 2 秒由调用方按用户要求补。
        val trimmed = com.pocketagent.voicelogic.PcmShape.collapseSilence(pcm, PCM_SAMPLE_RATE)
        val shaped = com.pocketagent.voicelogic.PcmShape.fadeEdges(trimmed, PCM_SAMPLE_RATE, EDGE_FADE_MS)
        val file = java.io.File(context.cacheDir, "voice_clip.wav")
        return try {
            writeWav(file, shaped)
            val mp = android.media.MediaPlayer()
            val done = java.util.concurrent.CountDownLatch(1)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            synchronized(lock) {
                releaseLocked()
                player = mp
                this.done = done
            }
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            mp.start()
            // 等它自然播完；stop() 会 release 并把 latch 放掉。
            done.await(60, java.util.concurrent.TimeUnit.SECONDS)
            true
        } catch (t: Throwable) {
            Timber.w(t, "语音条播放失败（不打扰用户）")
            false
        } finally {
            synchronized(lock) { releaseLocked() }
        }
    }

    /** 写一个标准 44 字节头的 WAV（单声道 16bit）。字节内容原样，不重编码。 */
    private fun writeWav(file: java.io.File, pcm: ByteArray) {
        val dataLen = pcm.size
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                       // PCM 子块大小
        header.putShort(1)                      // PCM
        header.putShort(1)                      // 单声道
        header.putInt(PCM_SAMPLE_RATE)
        header.putInt(PCM_SAMPLE_RATE * 2)      // 字节率
        header.putShort(2)                      // 块对齐
        header.putShort(16)                     // 位深
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        file.outputStream().use { out ->
            out.write(header.array())
            out.write(pcm)
        }
    }

    /**
     * 等缓冲区里的音频播完（最多等 DRAIN_TIMEOUT_MS）。
     *
     * ⚠️ 用 `playbackHeadPosition` 而不是 `sleep(时长)`：后者在卡顿时会等过头，
     *    而前者到点就返回；被打断（[stop]）也立刻退出。
     */
    private fun drainLocked(totalBytes: Int) {
        val totalFrames = totalBytes / 2
        val deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS
        while (!stopped && System.currentTimeMillis() < deadline) {
            val head = synchronized(lock) { track?.playbackHeadPosition ?: return }
            if (head >= totalFrames) return
            runCatching { Thread.sleep(20) }
        }
    }

    /** 立刻停。 */
    fun stop() {
        stopped = true
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        val p = player
        player = null
        done?.countDown()
        done = null
        if (p != null) {
            runCatching { p.stop() }
            runCatching { p.release() }
        }
    }

    private fun openTrackLocked() {
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

    private fun closeTrackLocked() {
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

    /** write / close 的互斥锁。见 [playPcm] 里那段崩溃记录。 */
    private val lock = Any()

    /** 当前播放器。用 MediaPlayer 而不是 AudioTrack，理由见 [playPcm]。 */
    private var player: android.media.MediaPlayer? = null

    /** 播完/出错/被停时放行 [playPcm] 的等待。 */
    private var done: java.util.concurrent.CountDownLatch? = null

    companion object {
        /** OpenAI 兼容 TTS 的 PCM 输出格式：24kHz / 16bit / 单声道。 */
        const val PCM_SAMPLE_RATE = 24_000

        /** 首尾淡变时长。20ms 仍在一个字的起音之内，听不出被压。 */
        private const val EDGE_FADE_MS = 20

        /** 等尾音播完的上限。一句话最多几十秒，超过这个数一定是有东西卡住了。 */
        private const val DRAIN_TIMEOUT_MS = 3_000L

        /** 一次写多少字节：太大时"打断"要等很久才停。 */
        private const val CHUNK_BYTES = 8 * 1024
    }
}
