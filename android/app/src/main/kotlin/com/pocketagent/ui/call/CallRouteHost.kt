package com.pocketagent.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketagent.agentui.VoiceCall
import com.pocketagent.data.AppContainer
import com.pocketagent.ui.design.PaMotion
import com.pocketagent.ui.design.PaSpace
import timber.log.Timber

/**
 * **通话页宿主** —— 把状态机、TTS、录音编排起来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一层的职责边界
 * ═══════════════════════════════════════════════════════════════
 *
 * · `agentui/VoiceCall`  —— **状态**（含打断的原子转换，有 30 条测试）
 * · `CallScreen`          —— **画**（三种不同形状的动效）
 * · 本文件                —— **编排**（谁在什么时候调 TTS / 录音 / 请求）
 *
 * ⚠️ 编排里有三件必须做对的事，都不是"能不能跑"的问题：
 *
 *  1. **离开页面必须挂断**。用户按返回键走人时，如果只 `popBackStack`
 *     而不 stop TTS，声音会继续播（他以为挂断了，其实还在说）。
 *     见下面的 `DisposableEffect`。
 *  2. **打断要真的停**。停 TTS + 取消生成 + 保留已播出文本，
 *     三件事一起做。只做第一件的话，字还会继续流过来。
 *  3. **说话时必须关麦**。否则它听见自己的声音（回声）。
 *     见 `VoiceCall.shouldMicBeOpen`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 当前这一版的边界（如实说明）
 * ═══════════════════════════════════════════════════════════════
 *
 * 真正的**实时语音链路**（连续录音 + 流式 STT + 边生成边播报 TTS）
 * 需要三个组件同时到位，本版只接通了其中两个半：
 *   · TTS 播放     ✓ 用现有的 `TtsSpeaker`
 *   · 录音          ✓ 用现有的 `VoiceRecorder`（按段录，不是流式）
 *   · 流式 STT      ✗ 没有（系统 `SpeechRecognizer` 在无 GMS 设备上不可用）
 *
 * ⇒ 本版是**按段对话**：录一段 → 转文字（暂用输入的文本）→ 请求 → 播报。
 *   不是"边说边识别"的实时通话。这个差别必须让用户知道 ——
 *   所以界面上不写"实时"，而是"按住说话"式的分段。
 */
@Composable
fun CallRouteHost(
    container: AppContainer,
    onHangUp: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // ⚠️ 状态机是**唯一事实来源**。界面与副作用都从它派生，
    //    不在这里另存一份"isPlaying"之类的布尔 —— 那必然漂移。
    var state by remember { mutableStateOf(VoiceCall.start()) }
    var spoken by remember { mutableStateOf("") }

    // 角色名：通话页上显示"在跟谁说话"。
    val personaName = remember {
        container.currentCustomPersona()?.name.orEmpty()
    }

    // ══════════════════════════════════════════════════════════
    //  ★★ 挂断 / 离开页面时**真的停**
    // ══════════════════════════════════════════════════════════
    //
    // ⚠️ 不用 `LaunchedEffect`，用 `DisposableEffect`：
    //    它的 `onDispose` 在**页面被销毁时**一定执行（包括按返回键、
    //    被系统回收）。而 `LaunchedEffect` 的取消是协作式的 ——
    //    如果 `stopSpeaking` 里有阻塞调用，它可能来不及跑。
    //
    // ⚠️ 这是语音类应用最常见的投诉之一："我挂了它还在说"。
    DisposableEffect(Unit) {
        onDispose {
            runCatching { container.stopSpeaking() }
                .onFailure { Timber.w(it, "离开通话页时停止播报失败") }
            Timber.i("通话页销毁：已停止播报")
        }
    }

    // ══════════════════════════════════════════════════════════
    //  相位 → 副作用
    // ══════════════════════════════════════════════════════════
    //
    // ⚠️ 这里**不启动任何东西** —— 只负责"状态变了之后要跟上什么"。
    //    启动由用户动作触发（见 onInterrupt / onReplay）。
    //    在 LaunchedEffect 里启动请求会让"重组"变成"重发请求"。
    LaunchedEffect(state.phase) {
        if (state.phase == VoiceCall.Phase.SPEAKING) {
            // 真实播报：把 pendingText 交给 TTS。
            // ⚠️ 逐字回调由 `TtsSpeaker` 的进度接口给（如果有）。
            //    本版没有逐字进度，所以整段播完之后才 appendSpoken。
            val text = state.pendingText.ifBlank { spoken }
            if (text.isNotBlank()) {
                runCatching { container.speakAsync(text) }
                    .onFailure { Timber.w(it, "通话播报失败") }
                spoken = text
                state = VoiceCall.appendSpoken(state, text)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CallScreen(
            state = state,
            personaName = personaName,
            spokenText = spoken,
            onInterrupt = {
                // ══════════════════════════════════════════════════
                //  ★★ **用户打断** —— 三件事一起做
                // ══════════════════════════════════════════════════
                //
                // ⚠️ 只停 TTS 是不够的：模型那边还在生成，
                //    那些字会继续流过来（用户已经不想听了）。
                val result = VoiceCall.interrupt(state)
                if (result.didInterrupt) {
                    runCatching { container.stopSpeaking() }
                        .onFailure { Timber.w(it, "打断时停止播报失败") }
                    // ⚠️ 已播出的话**保留**（`result.keepText`）——
                    //    直接清空会让那段话从历史里凭空消失，
                    //    而用户明明听见了。
                    spoken = result.keepText
                    state = result.state
                    Timber.i("通话被打断，保留已播 %d 字", result.keepText.length)
                }
            },
            onReplay = {
                val result = VoiceCall.interrupt(state)
                val keep = result.keepText.ifBlank { spoken }
                if (keep.isNotBlank()) {
                    state = VoiceCall.replay(state, keep)
                    spoken = ""
                }
            },
            onHangUp = {
                runCatching { container.stopSpeaking() }
                state = VoiceCall.hangUp(state)
                onHangUp()
            },
        )

        // 麦克风状态徽章 —— 顶部一个小指示。
        // ⚠️ 必须有：麦克风没开却没提示，会让用户对着手机说半天
        //    然后以为应用坏了。
        AnimatedVisibility(
            visible = state.phase != VoiceCall.Phase.IDLE,
            enter = fadeIn(PaMotion.fast()),
            exit = fadeOut(PaMotion.fast()),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = PaSpace.m),
        ) {
            MicBadge(open = VoiceCall.shouldMicBeOpen(state))
        }
    }
}
