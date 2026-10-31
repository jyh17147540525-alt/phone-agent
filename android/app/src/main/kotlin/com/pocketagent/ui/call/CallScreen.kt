package com.pocketagent.ui.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.VoiceCall
import com.pocketagent.ui.design.PaColor
import com.pocketagent.ui.design.PaSpace
import com.pocketagent.ui.design.PaType
import com.pocketagent.ui.design.rememberHaptics

/**
 * **语音通话页** —— 不是聊天框里的一条消息，是一整屏。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么它必须是一整屏
 * ═══════════════════════════════════════════════════════════════
 *
 * 打电话时用户**不看屏幕**。如果通话做成聊天框里的一个气泡流：
 *   · 屏幕上是大量他不需要读的文字
 *   · 状态（轮到谁了）变成一行小字，瞥一眼看不到
 *   · 打断按钮和发送按钮挤在一起，容易点错
 *
 * ⇒ 一整屏只做四件事：角色名、状态动效、状态文字、挂断/打断。
 *   其他全部消失 —— 这才是"通话"该有的信息密度。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 打断按钮的位置
 * ═══════════════════════════════════════════════════════════════
 *
 * 它出现在**挂断按钮的左边**，而不是屏幕中央 ——
 * 因为用户的拇指在下方，中断动作要够得着。
 * 放在中央会让"打断"变成一个需要瞄准的动作，而那是个
 * **紧急动作**（他不想听下去了）。
 */
@Composable
fun CallScreen(
    state: VoiceCall.State,
    personaName: String,
    modifier: Modifier = Modifier,
    /** 已播出的文本（显示在动效下方，让用户能回看）。 */
    spokenText: String = "",
    volume: Float = 0.5f,
    onInterrupt: () -> Unit = {},
    onReplay: () -> Unit = {},
    onHangUp: () -> Unit = {},
) {
    val haptics = rememberHaptics()

    // 通话页用深底 —— 这是全站唯一一屏刻意压暗的界面。
    // 理由：它多半在暗环境下用（躺着、夜里），而且
    // "通话"这件事本身就要求视觉退出、听觉进入。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0E13)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(PaSpace.l),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(PaSpace.xxl))

            // ── 角色名 ─────────────────────────────────────────
            Text(
                text = personaName.ifBlank { "通话中" },
                style = PaType.title,
                color = Color(0xFFE8EAF0),
            )
            Spacer(Modifier.height(PaSpace.xxs))
            Text(
                text = VoiceCall.labelFor(state),
                style = PaType.caption,
                color = Color(0xFF8A93A6),
            )

            // ── 动效区（占屏幕中央最大一块） ────────────────────
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(260.dp)) {
                    CallMotion(
                        phase = state.phase,
                        modifier = Modifier.fillMaxSize(),
                        accent = Color(0xFFB9D4FF),
                        volume = volume,
                    )
                }
            }

            // ── 已播出的文本（可回看，但不抢注意力） ─────────────
            if (spokenText.isNotBlank()) {
                Text(
                    text = spokenText,
                    style = PaType.body,
                    color = Color(0xFF9AA3B5),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PaSpace.m),
                )
                Spacer(Modifier.height(PaSpace.m))
            }

            // ── 按钮行 ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⚠️ 打断：只在能打断时出现（说/想）。
                //    听的时候显示它会让用户困惑（打断什么？我在说啊）。
                if (VoiceCall.showsInterruptButton(state)) {
                    CallButton(
                        icon = Icons.Default.MicOff,
                        label = "打断",
                        tint = Color(0xFFB9D4FF),
                        onClick = {
                            haptics.confirm()
                            onInterrupt()
                        },
                    )
                    Spacer(Modifier.size(PaSpace.l))
                }

                // 重听：只在被打断过之后出现。
                if (state.interrupted) {
                    CallButton(
                        icon = Icons.Default.Replay,
                        label = "重听",
                        tint = Color(0xFF8A93A6),
                        onClick = {
                            haptics.light()
                            onReplay()
                        },
                    )
                    Spacer(Modifier.size(PaSpace.l))
                }

                CallButton(
                    icon = Icons.Default.CallEnd,
                    label = "挂断",
                    tint = Color(0xFFE5484D),
                    background = Color(0xFFE5484D).copy(alpha = 0.16f),
                    onClick = {
                        haptics.confirm()
                        onHangUp()
                    },
                )
            }

            Spacer(Modifier.height(PaSpace.xxl))
        }
    }
}

/**
 * 通话页的圆形按钮。
 *
 * ★ 为什么标签在**下方**而不是按钮内：
 *   圆按钮里塞文字会让它变成"药丸"，而药丸按钮在通话页看起来
 *   像"设置项"。圆形 + 下方小字是电话应用几十年的惯例。
 */
@Composable
private fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    background: Color = Color.White.copy(alpha = 0.08f),
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(PaSpace.xxs))
        Text(text = label, style = PaType.label, color = Color(0xFF8A93A6))
    }
}

/**
 * 麦克风状态徽章 —— 顶部一个很小的指示。
 *
 * ⚠️ 必须有：用户需要知道"它现在能不能听见我"。
 *   麦克风没开却没提示，会让用户对着手机说半天
 *   然后以为应用坏了 —— 这是语音类应用最常见的投诉。
 */
@Composable
fun MicBadge(open: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(100.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(horizontal = PaSpace.xs, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (open) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = null,
            tint = if (open) Color(0xFF7CFFB2) else Color(0xFFE5484D),
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = if (open) "麦克风开" else "麦克风关",
            style = PaType.label,
            color = Color(0xFF8A93A6),
        )
    }
}
