package com.pocketagent.ui.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.pocketagent.provider.ttsopenai.TtsVoice
import com.pocketagent.provider.ttsopenai.TtsVoices
import com.pocketagent.ui.design.PaButton
import com.pocketagent.ui.design.PaButtonStyle
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从厂商的 `/v1/voices` 里挑一个音色。
 *
 * ★ 为什么必须做这个：`/v1/text-to-speech/{voice_id}` 只认 **voice_id**。
 *   让用户手打"音色 id"，他多半会填成界面上的**名字**（「月夜低语者」），
 *   然后每次合成都是 `404 Voice not found.` —— 2026-11-20 真机上就是这么
 *   一点声音都没有的。列表把"名字"和"要填的 id"摆在一起，就没这个坑了。
 */
@Composable
fun VoicePickerDialog(
    baseUrl: String,
    apiKey: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var voices by remember { mutableStateOf<List<TtsVoice>>(emptyList()) }

    LaunchedEffect(baseUrl, apiKey) {
        loading = true
        val result = runCatching { fetchVoices(baseUrl, apiKey) }
        voices = result.getOrDefault(emptyList())
        error = result.exceptionOrNull()?.message
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PaSpace.m),
        ) {
            Text(text = "选择音色", style = PaType.headline, color = PaColor.TextPrimary)
            Spacer(Modifier.height(PaSpace.xs))
            Text(
                text = "从你的账号里能用的音色里挑一个。点一下就把它的 id 填进去。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.height(PaSpace.s))

            when {
                loading -> Text(text = "正在取音色列表…", style = PaType.body, color = PaColor.TextSecondary)
                voices.isEmpty() -> Text(
                    text = error ?: "这个账号下没有取到音色（也可能 Key 没有列音色的权限）。",
                    style = PaType.body,
                    color = PaColor.Danger,
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(voices) { v ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(v.id) }
                                .padding(vertical = PaSpace.xs),
                        ) {
                            Text(text = v.name, style = PaType.body, color = PaColor.TextPrimary)
                            Text(
                                text = if (v.note.isBlank()) v.id else "${v.id} · ${v.note}",
                                style = PaType.caption,
                                color = PaColor.TextTertiary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(PaSpace.s))
            PaButton(
                text = "收起",
                onClick = onDismiss,
                style = PaButtonStyle.Glass,
                fillWidth = true,
            )
        }
    }
}

/** 取一次音色列表。失败抛异常，由界面显示原因（不编假数据）。 */
private suspend fun fetchVoices(baseUrl: String, apiKey: String): List<TtsVoice> =
    withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/').ifEmpty { "https://api.breeze.blue" }
        val url = URL("$base/v1/voices?page_size=100&language_code=zh")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("xi-api-key", apiKey)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code：${body.take(160)}")
            }
            TtsVoices.parse(body).ifEmpty {
                throw IllegalStateException("列表是空的（返回 $body 开头 ${body.take(120)}）")
            }
        } finally {
            conn.disconnect()
        }
    }
