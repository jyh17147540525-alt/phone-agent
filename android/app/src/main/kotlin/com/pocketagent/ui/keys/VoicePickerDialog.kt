package com.pocketagent.ui.keys

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.pocketagent.ui.design.PaTextField
import com.pocketagent.ui.design.PaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从厂商的 `/v1/voices` 里挑一个音色。
 *
 * ★ 为什么必须做这个：`/v1/text-to-speech/{voice_id}` 只认 **voice_id**。
 *   让用户手打 id，他多半会填成界面上的**名字**（「月夜低语者」），
 *   然后每次合成都是 `404 Voice not found.` —— 2026-11-20 真机上就是这么
 *   一点声音都没有的。
 *
 * ★ 2026-11-20 晚补：一页 100 条装不下整个音色库（官网几百个，还分
 *   公开库 / 我的音色）。所以这里给三样东西：
 *   ① **搜索框**（用户在官网试听完，记住名字，回来搜）；
 *   ② **加载更多**（翻页，直到翻完）；
 *   ③ **直接填 id**（官网地址里那串 UUID 最准，粘进来就行）。
 */
@Composable
fun VoicePickerDialog(
    fetch: suspend (query: String, page: Int) -> Pair<List<TtsVoice>, Boolean>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var more by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf<List<TtsVoice>>(emptyList()) }
    var pasted by remember { mutableStateOf("") }

    // ★ 搜索防抖：每敲一个字就发一次请求，等于拿用户的额度打自己的脸。
    LaunchedEffect(query) {
        delay(450)
        if (query != appliedQuery) {
            appliedQuery = query
            page = 1
        }
    }

    LaunchedEffect(appliedQuery, page) {
        val first = page == 1
        if (first) loading = true else more = true
        val result = runCatching { fetch(appliedQuery, page) }
        if (first) loading = false else more = false
        result.onSuccess { (list, moreAvailable) ->
            voices = if (first) list else voices + list
            hasMore = moreAvailable
            error = null
        }.onFailure { t ->
            if (first) error = t.message
        }
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
                text = "在官网试听完，记住名字来这里搜；也可以直接把地址里的 id 粘进来。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            Spacer(Modifier.height(PaSpace.s))

            PaTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "搜音色（名字 / 气质 / 语言，例如 低语、calm、zh）",
            )

            // ★ 直接填 id：官网地址里 `voice_id=…` 那段是最准的，
            //   比在几百条里翻更省事。只接受"像 id"的输入，中文名会被拦。
            Spacer(Modifier.height(PaSpace.xs))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    PaTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "或直接粘 voice_id（868dcfd9-…）",
                    )
                }
                Spacer(Modifier.width(PaSpace.xs))
                val usable = TtsVoices.looksLikeId(pasted)
                PaButton(
                    text = "用这个",
                    onClick = { if (usable) onPick(pasted.trim()) },
                    style = if (usable) PaButtonStyle.Primary else PaButtonStyle.Glass,
                )
            }

            Spacer(Modifier.height(PaSpace.s))

            when {
                loading -> Text(text = "正在取音色列表…", style = PaType.body, color = PaColor.TextSecondary)
                voices.isEmpty() -> Text(
                    text = error ?: "没搜到音色。换个词试试，或者直接把 id 粘在上面。",
                    style = PaType.body,
                    color = if (error != null) PaColor.Danger else PaColor.TextSecondary,
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
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
                    if (hasMore) {
                        item {
                            Spacer(Modifier.height(PaSpace.xs))
                            PaButton(
                                text = if (more) "正在取…" else "加载更多",
                                onClick = { if (!more) page += 1 },
                                style = PaButtonStyle.Glass,
                                fillWidth = true,
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
