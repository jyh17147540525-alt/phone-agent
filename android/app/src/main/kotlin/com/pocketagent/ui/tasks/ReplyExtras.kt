package com.pocketagent.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketagent.filelogic.ReplyFile
import com.pocketagent.filelogic.ReplyFileKind
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaRadius
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import com.pocketagent.voicelogic.VoiceNote

/**
 * **语音条 + 文件卡片** —— 回复气泡下面的两种"可点东西"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是什么（原话）
 * ═══════════════════════════════════════════════════════════════
 *
 * "就像微信那样的语音，语音下面还能显示文本……可以文件输出，用户点击后
 *  能打开这个文件……用户点击语音的时候会播放出来，语音的时间上限最高 60 秒，
 *  可以一次性发送多条语音"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三个界面决定
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **宽度随时长变**（微信就是这么做的）。固定的条子让人分不出
 *    "两秒" 和 "四十秒"，只能挨个点 —— 那是让用户做本来不必做的事。
 * 2. **时长写在条子上**，用估算值（真实值要等合成，而合成要花钱）。
 *    估算偏一点没关系，写"约"更诚实的是不要写 —— 用户只想知道大概多长。
 * 3. **文件卡片不做缩略图**。缩略图要先把文件读出来，而卡片可能一次出现
 *    六张 —— 那是把一个列表变成六次磁盘读，且失败时卡片还是空的。
 */

/** 一条语音。 */
@Composable
fun VoiceClipBar(
    clip: VoiceNote.Clip,
    playing: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 60 秒映射到满宽；短句子也要有 88dp 的可点宽度，否则拇指点不中。
    val fraction = (clip.estimateMs / 60_000f).coerceIn(0.18f, 1f)
    Row(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(min = 88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.25f))
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(PaRadius.m))
                .background(if (playing) PaColor.AccentSoft else PaColor.SurfaceHigh)
                .clickable(onClick = onPlay)
                .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (playing) "■" else "▶",
                style = PaType.caption,
                color = PaColor.Accent,
            )
            Spacer(Modifier.width(PaSpace.xs))
            // 三根小竖条当"波形"：比图标更像语音，也不需要图片资源
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(8.dp, 14.dp, 10.dp).forEach { h ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 1.dp)
                            .width(2.dp)
                            .height(h)
                            .background(PaColor.Accent.copy(alpha = 0.55f)),
                    )
                }
            }
            Spacer(Modifier.width(PaSpace.xs))
            Text(
                text = VoiceNote.formatDuration(clip.estimateMs),
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }
    }
}

/** 一组语音（最多三条；再多的部分用一行小字说明）。 */
@Composable
fun VoiceClipGroup(
    clips: List<VoiceNote.Clip>,
    playingText: String?,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxShown: Int = 3,
) {
    if (clips.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PaSpace.xxs),
    ) {
        clips.take(maxShown).forEach { clip ->
            VoiceClipBar(
                clip = clip,
                playing = playingText == clip.text,
                onPlay = { onPlay(clip.text) },
            )
        }
        if (clips.size > maxShown) {
            Text(
                text = "还有 ${clips.size - maxShown} 条语音",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
        }
    }
}

/** 文件卡片：一行一个文件。 */
@Composable
fun ReplyFileCards(
    files: List<ReplyFile>,
    onOpen: (ReplyFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(0.96f),
        verticalArrangement = Arrangement.spacedBy(PaSpace.xxs),
    ) {
        files.forEach { file ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PaRadius.s))
                    .background(PaColor.SurfaceHigh)
                    .clickable { onOpen(file) }
                    .padding(horizontal = PaSpace.s, vertical = PaSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(PaRadius.xs))
                        .background(PaColor.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tagOf(file.kind),
                        style = PaType.caption,
                        color = PaColor.Accent,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Spacer(Modifier.width(PaSpace.xs))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        style = PaType.body,
                        color = PaColor.TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = file.path,
                        style = PaType.caption,
                        color = PaColor.TextTertiary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(PaSpace.xs))
                Text(
                    text = if (com.pocketagent.filelogic.ReplyFiles.openableInApp(file.kind)) "打开" else "看看",
                    style = PaType.caption,
                    color = PaColor.Accent,
                )
            }
        }
    }
}

private fun tagOf(kind: ReplyFileKind): String = when (kind) {
    ReplyFileKind.IMAGE -> "图"
    ReplyFileKind.PDF -> "PDF"
    ReplyFileKind.TEXT -> "文"
    ReplyFileKind.OFFICE -> "OFF"
    ReplyFileKind.ARCHIVE -> "ZIP"
    ReplyFileKind.AUDIO -> "音"
    ReplyFileKind.OTHER -> "?"
}

/**
 * 语音条 / 文件卡片需要的东西。
 *
 * ★ 为什么用 CompositionLocal 而不是一层层传参：真正渲染回复的是
 *   `ConversationList.kt` 里的 `TurnBubbles`，它离 `TasksScreen` 隔着
 *   LazyColumn 的 item 与 `ConversationTurn`。三层签名各加三个参数，
 *   只为了把两个回调送到最里面，而这三层里没有一层用得上它们。
 */
class VoiceReplyHooks(
    /** 这条回复切成几条语音；返回空表就不画语音条。 */
    val clipsOf: (String) -> List<com.pocketagent.voicelogic.VoiceNote.Clip>,
    /** 正在播的那一条（用文本当 id）。 */
    val playingText: String?,
    /**
     * 现在**真的能出声**吗（有没有可用的 TTS Key）。
     *
     * ⚠️ 没 Key 时不画语音条：画一条点了没声音的条子，比不画更让人以为应用坏了。
     */
    val canSpeak: Boolean,
    /** 点语音条：同一条再点就是停。 */
    val onToggle: (String) -> Unit,
    /** 点文件卡片：在应用内打开。 */
    val onOpenFile: (com.pocketagent.filelogic.ReplyFile) -> Unit,
)

internal val LocalVoiceReply =
    androidx.compose.runtime.staticCompositionLocalOf<VoiceReplyHooks?> { null }

/**
 * 回复下面挂的那几样东西：语音条 + 文件卡片。
 *
 * ★ 用户原话：「就像微信那样的语音，语音下面还能显示文本……可以文件输出，
 *   用户点击后能打开这个文件，最好能做到不需要跳转其他软件」。
 *
 * ⚠️ 没有 TTS Key（`clipsOf` 返回空）时**一条都不画** —— 画一条点了没声音的
 *   条子，比不画更让人以为应用坏了。
 */
@androidx.compose.runtime.Composable
internal fun VoiceRowAbove(replyText: String, visible: Boolean) {
    if (!visible) return
    val hooks = LocalVoiceReply.current ?: return
    if (!hooks.canSpeak) return
    val clips = hooks.clipsOf(replyText)
    if (clips.isEmpty()) return
    VoiceClipGroup(
        clips = clips,
        playingText = hooks.playingText,
        onPlay = hooks.onToggle,
        modifier = androidx.compose.ui.Modifier.padding(bottom = PaSpace.xs),
    )
}

/**
 * 回复**下面**那张文件卡片。
 *
 * ★ 用户 m09943 定的顺序：「语音在上，文字在下」—— 语音条在正文之前，
 *   文件卡片在正文之后（它是"这轮做出来的东西"，属于附注，不属于发言）。
 */
@androidx.compose.runtime.Composable
internal fun FilesRowBelow(replyText: String, visible: Boolean) {
    if (!visible) return
    val hooks = LocalVoiceReply.current ?: return
    val files = com.pocketagent.filelogic.ReplyFiles.parse(replyText)
    if (files.isEmpty()) return
    Spacer(Modifier.height(PaSpace.xs))
    ReplyFileCards(files = files, onOpen = hooks.onOpenFile)
}
