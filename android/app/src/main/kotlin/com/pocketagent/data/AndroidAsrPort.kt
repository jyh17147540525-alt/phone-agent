package com.pocketagent.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.pocketagent.mcp.AsrPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 听写：系统 `SpeechRecognizer`。
 *
 * 四个真机上的硬约束，任何一个搞错都会"看着能跑、实际听不到"：
 *
 * 1. **必须在主线程建与调**：`SpeechRecognizer.createSpeechRecognizer` 和
 *    `startListening` 在主线程之外调会抛 `RuntimeException`，或者静默不回调。
 * 2. **麦克风权限是运行时权限**：没给就直接说清楚去哪儿给，不要返回"没听到"，
 *    否则用户会以为手机坏了。
 * 3. **一定要 `destroy()`**：识别器持有录音会话，不释放的话下一次 `listen`
 *    会拿到 `ERROR_RECOGNIZER_BUSY`，而且会一直占着麦克风。
 * 4. **`onResults` 与 `onError` 只来一个**：两边都要 resume，且用 `isActive` 防重。
 *
 * 音频**不上传**：识别由系统引擎完成（有的引擎走云端、有的离线），
 * 应用只拿文字、不碰音频字节。
 */
class AndroidAsrPort(context: Context) : AsrPort {

    private val appContext = context.applicationContext

    override suspend fun listen(maxMillis: Long): String = withContext(Dispatchers.Main) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException(
                "没有麦克风权限。去 设置 → 应用 → PocketAgent → 权限 → 麦克风，允许之后再叫我听。",
            )
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            throw IllegalStateException("这台手机没有装语音识别服务，听不了。可以先打字。")
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        try {
            val text = withTimeoutOrNull(maxMillis + GRACE_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit

                        override fun onResults(results: Bundle?) {
                            val best = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            if (cont.isActive) cont.resume(best)
                        }

                        override fun onError(error: Int) {
                            if (!cont.isActive) return
                            // 安静/没听到不是故障 —— 回空串，让工具说"没听到"
                            if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            ) {
                                cont.resume("")
                            } else {
                                cont.resumeWithException(IllegalStateException(explain(error)))
                            }
                        }
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    runCatching { recognizer.startListening(intent) }.onFailure { error ->
                        if (cont.isActive) {
                            cont.resumeWithException(
                                IllegalStateException("麦克风打不开：${error.message ?: "被系统拒绝了"}"),
                            )
                        }
                    }
                }
            }
            // 超时说明用户一直没说话；当作"没听到"，不要把超时报成故障
            text.orEmpty()
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    private fun explain(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "录音出错了，可能是麦克风被别的应用占着。"
        SpeechRecognizer.ERROR_CLIENT -> "识别器被系统断开了，再试一次。"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限，去设置里打开。"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "识别引擎要联网但网不通。可以换离线的识别引擎，或者直接打字。"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器正忙（上一次还没结束），等一下再试。"
        SpeechRecognizer.ERROR_SERVER -> "识别服务那边出错了，过一会儿再试。"
        else -> "识别失败（code=$error）。"
    }

    private companion object {
        /** 给引擎一点收尾时间：`maxMillis` 是"最多听多久"，之后可能还要几百毫秒出结果。 */
        const val GRACE_MS = 4_000L
    }
}
