package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **语音通话状态机**的测试，重点是"随时打断"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是三个用户能直接感觉到的失败
 * ═══════════════════════════════════════════════════════════════
 *
 *  ① 打断了但声音没停（或停了但生成还在跑）
 *  ② 打断后**已经说出的话从历史里消失**（用户明明听见了）
 *  ③ 说话时麦克风还开着 ⇒ 它听见自己的声音（回声）
 */
class VoiceCallStateTest {

    // ═══════════════════════════════════════════════════════════
    //  基本流转
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `接通即听不说空闲`() {
        // ★ 用户点"语音通话"就是要说话，多一次点击是无谓门槛。
        val s = VoiceCall.start()
        assertEquals(VoiceCall.Phase.LISTENING, s.phase)
        assertTrue("接通时麦克风该开着", s.micOpen)
    }

    @Test
    fun `说完进入思考`() {
        val s = VoiceCall.userFinishedTalking(VoiceCall.start())
        assertEquals(VoiceCall.Phase.THINKING, s.phase)
    }

    @Test
    fun `想出字后才进入说`() {
        // ⚠️ 不能在"请求发出"时就进 SPEAKING ——
        //    模型可能想两三秒，那段时间必须显示"在想"，
        //    否则用户以为"它开口了但没声音"。
        val thinking = VoiceCall.userFinishedTalking(VoiceCall.start())
        val speaking = VoiceCall.beginSpeaking(thinking, "你好")
        assertEquals(VoiceCall.Phase.SPEAKING, speaking.phase)
    }

    @Test
    fun `不在思考时 beginSpeaking 无效`() {
        val listening = VoiceCall.start()
        val r = VoiceCall.beginSpeaking(listening, "你好")
        assertEquals("听的时候不该直接开始说", VoiceCall.Phase.LISTENING, r.phase)
    }

    @Test
    fun `说完回到听不是挂断`() {
        // ★ 一轮说完应当**接着听**，这才是对话。挂断要用户明确点。
        var s = VoiceCall.userFinishedTalking(VoiceCall.start())
        s = VoiceCall.beginSpeaking(s, "你好")
        s = VoiceCall.appendSpoken(s, "你好")
        s = VoiceCall.finishSpeaking(s)
        assertEquals(VoiceCall.Phase.LISTENING, s.phase)
    }

    @Test
    fun `对话结束后清空已播文本`() {
        var s = VoiceCall.userFinishedTalking(VoiceCall.start())
        s = VoiceCall.beginSpeaking(s, "你好")
        s = VoiceCall.appendSpoken(s, "你好")
        s = VoiceCall.finishSpeaking(s)
        assertEquals("新一轮开始时不该带着上一轮的话", "", s.spokenText)
    }

    @Test
    fun `挂断回到初态`() {
        var s = VoiceCall.userFinishedTalking(VoiceCall.start())
        s = VoiceCall.beginSpeaking(s, "x")
        val hung = VoiceCall.hangUp(s)
        assertEquals(VoiceCall.Phase.IDLE, hung.phase)
        assertEquals("", hung.spokenText)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★ 打断
    // ═══════════════════════════════════════════════════════════

    private fun speakingState(spoken: String = "", pending: String = ""): VoiceCall.State {
        var s = VoiceCall.userFinishedTalking(VoiceCall.start())
        s = VoiceCall.beginSpeaking(s, pending.ifEmpty { "一段话" })
        if (spoken.isNotEmpty()) s = VoiceCall.appendSpoken(s, spoken)
        return s
    }

    @Test
    fun `说话时可以打断`() {
        val s = speakingState()
        val r = VoiceCall.interrupt(s)
        assertTrue("说话时必须能打断", r.didInterrupt)
        assertEquals(VoiceCall.Phase.LISTENING, r.state.phase)
    }

    @Test
    fun `思考时也可以打断`() {
        // ★ 用户在"嗯……"的时候改主意同样常见 ——
        //   只说"打断播放"是不够的。
        val thinking = VoiceCall.userFinishedTalking(VoiceCall.start())
        val r = VoiceCall.interrupt(thinking)
        assertTrue("思考时也必须能打断", r.didInterrupt)
        assertEquals(VoiceCall.Phase.LISTENING, r.state.phase)
    }

    @Test
    fun `听的时候打断没有意义`() {
        // 已经在听了，再"打断"会让状态莫名其妙。
        val r = VoiceCall.interrupt(VoiceCall.start())
        assertFalse("听的时候不该算打断", r.didInterrupt)
    }

    @Test
    fun `空闲时打断没有意义`() {
        val r = VoiceCall.interrupt(VoiceCall.State())
        assertFalse(r.didInterrupt)
    }

    @Test
    fun `打断要保留已经说出去的话`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是打断功能里最容易漏的一环
        // ══════════════════════════════════════════════════════
        // 大多数实现直接 `tts.stop()` 就完事，于是历史里那句话
        // **凭空消失**，而用户明明听见了 —— 下次它"不记得自己说过什么"。
        val s = speakingState(spoken = "我今天想说的是")
        val r = VoiceCall.interrupt(s)

        assertEquals(
            "已经播出去的话必须交给调用方保留",
            "我今天想说的是",
            r.keepText,
        )
    }

    @Test
    fun `打断要丢弃还没播的部分`() {
        val s = speakingState(spoken = "前半句", pending = "后半句")
        val r = VoiceCall.interrupt(s)
        assertEquals("没播的不该留下（用户不要听了）", "", r.state.pendingText)
    }

    @Test
    fun `打断后标记 interrupted`() {
        val r = VoiceCall.interrupt(speakingState())
        assertTrue("界面要据此显示「你打断了我」", r.state.interrupted)
    }

    @Test
    fun `打断后立刻进入听`() {
        // ⚠️ 若停在"说"的动效上，用户会以为没打断成功，再喊一次。
        val r = VoiceCall.interrupt(speakingState())
        assertEquals(VoiceCall.Phase.LISTENING, r.state.phase)
        assertTrue("打断后麦克风必须开", VoiceCall.shouldMicBeOpen(r.state))
    }

    @Test
    fun `新一轮开始时清掉 interrupted`() {
        val r = VoiceCall.interrupt(speakingState())
        assertTrue(r.state.interrupted)
        val next = VoiceCall.userFinishedTalking(r.state)
        assertFalse("新的一轮不该带着上次的标记", next.interrupted)
    }

    // ═══════════════════════════════════════════════════════════
    //  重听
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `被打断后可以重听`() {
        val r = VoiceCall.interrupt(speakingState(spoken = "刚才那段"))
        val replay = VoiceCall.replay(r.state, r.keepText)
        assertEquals(VoiceCall.Phase.SPEAKING, replay.phase)
    }

    @Test
    fun `没被打断时重听无效`() {
        val s = VoiceCall.start()
        val replay = VoiceCall.replay(s, "随便什么")
        assertEquals("不该在听的时候突然开始说", VoiceCall.Phase.LISTENING, replay.phase)
    }

    @Test
    fun `重听空文本无效`() {
        val r = VoiceCall.interrupt(speakingState())
        val replay = VoiceCall.replay(r.state, "")
        assertEquals(VoiceCall.Phase.LISTENING, replay.phase)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 麦克风（回声这条最容易错）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `只有听的时候麦克风才开`() {
        // ══════════════════════════════════════════════════════
        //  ★ 语音通话里最常见的低级 bug
        // ══════════════════════════════════════════════════════
        // 说话时开麦 ⇒ 把 TTS 的声音录回去 ⇒ 它"听见"自己在说话。
        // 表现是：回音、或者它开始回应自己。
        for (phase in VoiceCall.Phase.entries) {
            val s = VoiceCall.State(phase = phase, micOpen = true)
            val should = VoiceCall.shouldMicBeOpen(s)
            if (phase == VoiceCall.Phase.LISTENING) {
                assertTrue("听的时候必须开麦", should)
            } else {
                assertFalse("${phase} 时不该开麦（会把 TTS 声音录回去）", should)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  按钮可见性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `打断按钮只在能打断时出现`() {
        for (phase in VoiceCall.Phase.entries) {
            val s = VoiceCall.State(phase = phase)
            val shows = VoiceCall.showsInterruptButton(s)
            when (phase) {
                VoiceCall.Phase.SPEAKING, VoiceCall.Phase.THINKING ->
                    assertTrue("${phase} 时该显示打断按钮", shows)
                // ⚠️ 听的时候显示"打断"会让用户困惑（打断什么？我在说啊）。
                else -> assertFalse("${phase} 时不该显示打断按钮", shows)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  动效必须形状不同
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `三种相位对应三种不同动效`() {
        // ★ 形状不同，用户不用看字就知道轮到谁了。
        //   只"变个颜色"是不够的。
        val listening = VoiceCall.motionFor(VoiceCall.Phase.LISTENING)
        val thinking = VoiceCall.motionFor(VoiceCall.Phase.THINKING)
        val speaking = VoiceCall.motionFor(VoiceCall.Phase.SPEAKING)

        assertEquals(VoiceCall.Motion.BREATHING_RING, listening)
        assertEquals(VoiceCall.Motion.SPINNING, thinking)
        assertEquals(VoiceCall.Motion.WAVEFORM, speaking)
        assertEquals(
            "三种动效必须互不相同",
            3,
            setOf(listening, thinking, speaking).size,
        )
    }

    @Test
    fun `空闲时静止`() {
        assertEquals(VoiceCall.Motion.STILL, VoiceCall.motionFor(VoiceCall.Phase.IDLE))
    }

    // ═══════════════════════════════════════════════════════════
    //  文案
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `文案要像人在说话不像系统提示`() {
        // ⚠️ 用户在打电话，看到的应当是**人的反应**，
        //   不是"正在处理…"这类系统词。
        for (phase in VoiceCall.Phase.entries) {
            val label = VoiceCall.labelFor(VoiceCall.State(phase = phase))
            assertTrue("「$label」不该是空串", label.isNotBlank())
            assertFalse("「$label」不该用系统腔", "正在处理" in label || "loading" in label.lowercase())
        }
    }

    @Test
    fun `麦克风没开时说清楚`() {
        val s = VoiceCall.State(phase = VoiceCall.Phase.LISTENING, micOpen = false)
        val label = VoiceCall.labelFor(s)
        assertTrue("要说清麦克风的问题：$label", "麦克风" in label)
    }

    // ═══════════════════════════════════════════════════════════
    //  幂等（VAD 会重复触发）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `重复的说完不会发两个请求`() {
        // ⚠️ 语音端点检测在静音边界上可能触发两次。
        //    不判相位的话会发出两个请求 —— 用户听到两个回答。
        val s1 = VoiceCall.userFinishedTalking(VoiceCall.start())
        val s2 = VoiceCall.userFinishedTalking(s1)
        assertEquals("第二次该是空操作", s1, s2)
    }

    @Test
    fun `appendSpoken 只累加不覆盖`() {
        var s = VoiceCall.userFinishedTalking(VoiceCall.start())
        s = VoiceCall.beginSpeaking(s, "你好世界")
        s = VoiceCall.appendSpoken(s, "你好")
        s = VoiceCall.appendSpoken(s, "世界")
        assertEquals("逐段播必须累加", "你好世界", s.spokenText)
    }

    @Test
    fun `appendSpoken 空串是空操作`() {
        var s = VoiceCall.userFinishedTalking(VoiceCall.start())
        s = VoiceCall.beginSpeaking(s, "x")
        val before = s
        assertEquals(before, VoiceCall.appendSpoken(s, ""))
    }
}
