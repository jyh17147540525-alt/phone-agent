package com.pocketagent.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import kotlin.math.roundToInt

/**
 * **聊天页顶部的显示形态滑条**。
 *
 * 三档：只看文字 / 文字 + 语音 / 只听语音。
 *
 * 为什么用滑条而不是三个开关：这是一件"随时会调"的事 —— 在地铁上只想听，
 * 在办公室只想读，回到家用两样。三个开关要读、要想、要点两次；
 * 滑条是一下拖到位，而且拖的时候就能看见结果。
 *
 * [requested] 是用户选的那一档，[mode] 是实际生效的那一档：
 * 没有语音 Key 时"只听语音"会退回"两样都要"，这里如实显示差别。
 */
@Composable
internal fun ReplyModeSlider(
    mode: ReplyViewMode,
    requested: ReplyViewMode,
    canSpeak: Boolean,
    onPick: (ReplyViewMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = ReplyViewMode.indexOf(requested).toFloat()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PaSpace.screenH, vertical = PaSpace.xxs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PaSpace.xs),
        ) {
            Text(
                text = "显示",
                style = PaType.caption,
                color = PaColor.TextTertiary,
            )
            Slider(
                value = index,
                onValueChange = { onPick(ReplyViewMode.at(it.roundToInt())) },
                valueRange = 0f..(ReplyViewMode.ordered.size - 1).toFloat(),
                // 三档 ⇒ 中间一个台阶
                steps = ReplyViewMode.ordered.size - 2,
                colors = SliderDefaults.colors(
                    thumbColor = PaColor.Accent,
                    activeTrackColor = PaColor.Accent,
                    inactiveTrackColor = PaColor.SurfaceHigh,
                ),
                modifier = Modifier
                    .weight(1f)
                    // 默认滑条有 48dp 的触控高度，这里压到 36dp：
                    // 它常驻在会话上方，太高会挤掉一屏对话。
                    .height(36.dp),
            )
            Text(
                text = requested.label,
                style = PaType.caption,
                color = PaColor.TextSecondary,
            )
        }

        // 想看语音、但还没配 Key —— 这句必须出现，否则用户只会觉得"滑了没反应"。
        if (requested == ReplyViewMode.VOICE_ONLY && !canSpeak) {
            Text(
                text = "还没配语音 Key，这一档暂时按「文字 + 语音」显示。去 设置 → API Key 加一条语音的。",
                style = PaType.caption,
                color = PaColor.Warning,
                modifier = Modifier.padding(top = 2.dp),
            )
        } else if (mode != requested) {
            Text(
                text = "当前按「${mode.label}」显示。",
                style = PaType.caption,
                color = PaColor.TextTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
