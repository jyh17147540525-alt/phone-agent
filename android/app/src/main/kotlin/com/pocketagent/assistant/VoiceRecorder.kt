package com.pocketagent.assistant

import android.content.Context
import android.media.MediaRecorder
import timber.log.Timber
import java.io.File

/**
 * **按住说话** —— 录一段语音消息。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "长按输入框能给角色发送语音。"
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 必须诚实的两件事
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **这一版还听不懂语音。**
 *    要"听懂"需要语音识别（STT）。本机**没有 GMS**，Android 自带的
 *    `SpeechRecognizer` 用不了；而接一个云端 STT 等于要求用户再配一套 Key。
 *    ⇒ 录下来的音频会以**文件路径**交给模型，并在提示词里写清
 *      "这是录音文件，你还听不到内容"。**不写清楚它就会编**。
 *
 * 2. **音频不落盘到临时区之外。**
 *    录音写在 `cacheDir/voice`，发出去之后由调用方删；
 *    应用被杀时系统会随缓存一起回收。不上传、不进任何日志（只记时长）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用 MediaRecorder 而不是 AudioRecord
 * ═══════════════════════════════════════════════════════════════
 *
 * `AudioRecord` 给的是裸 PCM，要自己封容器（m4a/wav）才能被别处使用；
 * 而语音消息的诉求只是"录下来、能播、能交给模型当文件"。
 * `MediaRecorder` 直接产出 AAC/m4a，体积小、播放器都能放。
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var current: File? = null

    /** 录音起点，用于算时长（MediaRecorder 不给时长）。 */
    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recorder != null

    /**
     * 开始录。
     *
     * @return null = 开始成功；否则是一句"为什么不行"
     */
    fun start(): String? {
        if (recorder != null) return "已经在录了"
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val file = File(dir, "voice-${System.currentTimeMillis()}.m4a")
        val r = MediaRecorder()
        return try {
            // ⚠️ 顺序不能换：setAudioSource → setOutputFormat → 编码器 → 输出文件 → prepare。
            //    顺序错了 `prepare()` 会抛 IllegalStateException，而错误信息不会告诉你顺序错了。
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // 16kHz 单声道 32kbps —— 语音够用，体积约为 4KB/秒。
            r.setAudioSamplingRate(16_000)
            r.setAudioChannels(1)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            current = file
            startedAt = System.currentTimeMillis()
            null
        } catch (t: Throwable) {
            Timber.w(t, "开始录音失败")
            runCatching { r.release() }
            runCatching { file.delete() }
            "开始录音失败（${t.message ?: t::class.simpleName}）"
        }
    }

    /** 一段录好的语音。 */
    data class Clip(val file: File, val durationMs: Long, val bytes: Long)

    /**
     * 停止并返回这一段。
     *
     * @return null = 没在录 / 录得太短（< 600ms 视为误触）
     */
    fun stop(): Clip? {
        val r = recorder ?: return null
        val file = current
        val elapsed = System.currentTimeMillis() - startedAt
        recorder = null
        current = null
        return try {
            // ⚠️ `stop()` 在"没录到任何数据"时会抛 RuntimeException ——
            //    按住就松开（<300ms）正好是这个情况。必须吞掉它，
            //    否则一次误触会让整个发送流程崩掉。
            r.stop()
            r.release()
            if (file == null || !file.isFile || elapsed < MIN_DURATION_MS) {
                file?.delete()
                null
            } else {
                Clip(file = file, durationMs = elapsed, bytes = file.length())
            }
        } catch (t: Throwable) {
            Timber.d("录音停止异常（多半是按得太短）：%s", t.message)
            runCatching { r.release() }
            runCatching { file?.delete() }
            null
        }
    }

    /** 取消（例如手指滑走）。 */
    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        runCatching { current?.delete() }
        current = null
    }

    private companion object {
        /** 短于这个时长视为误触 —— 语音消息没人发 0.3 秒的。 */
        const val MIN_DURATION_MS = 600L
    }
}
