package com.pocketagent.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pocketagent.mcp.TtsPort
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 系统 TTS 朗读（离线，不上传）。
 *
 * 坑都在这儿：
 * 1. `TextToSpeech` 初始化是**异步**的 —— 建完立刻 `speak` 必然丢字。
 * 2. `speak` 也是异步的，要等 `onDone`；不等的话工具会立刻返回"念完了"，
 *    而实际上用户还在听。
 * 3. 有的机器没中文引擎，`setLanguage` 返回 `LANG_MISSING_DATA`；这时候不能假装
 *    成功，要如实抛出来，让模型换一句普通话术或改用文字。
 */
class AndroidTtsPort(context: Context) : TtsPort {

    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = false

    override suspend fun speak(text: String): String = withContext(Dispatchers.Main) {
        val tts = ensureEngine()
        val result = tts.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            throw IllegalStateException("这台手机没有装中文语音数据，念不了")
        }
        val utteranceId = UUID.randomUUID().toString()
        val done = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("老接口，Android 21 之前用")
                    override fun onError(id: String?) {
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("语音引擎报错，可能是这句念不出来"))
                    }
                })
                val code = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (code != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resumeWithException(IllegalStateException("语音引擎忙或没准备好（code=$code）"))
                }
            }
        }
        if (done == null) {
            // 超时不代表失败，可能是长句还在念；如实说"还在念"
            return@withContext "（还没念完，超过 ${TIMEOUT_MS / 1000} 秒）" + engineName(tts)
        }
        engineName(tts)
    }

    private companion object {
        /** 一句话最长等这么久；超时不报错，只回「还没念完」。 */
        const val TIMEOUT_MS = 20_000L
    }

    private fun engineName(tts: TextToSpeech): String =
        runCatching { tts.defaultEngine ?: "" }.getOrDefault("")

    private suspend fun ensureEngine(): TextToSpeech {
        engine?.let { if (ready) return it }
        return suspendCancellableCoroutine { cont ->
            val created = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                    cont.resume(engine!!)
                } else if (cont.isActive) {
                    cont.resumeWithException(IllegalStateException("语音引擎没起来（status=$status），这台手机可能没有 TTS"))
                }
            }
            engine = created
        }
    }
}
