package com.pocketagent.agentui

/**
 * **语音通话的状态机** —— 含"随时打断"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 用户要的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * "还有打电话的页面互动动画，你可以参考 chatgpt 和豆包的互动效果和
 *  互动动画，用户可以随时打断模型的说话等等等等"
 *
 * 三个可判真假的要求：
 *  ① 有**通话页面**（不是聊天框里的一条消息）
 *  ② 有**随状态变化的动效**（听、想、说三种明显不同的形态）
 *  ③ **随时打断**（模型正在说话时，用户一开口就停）
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么"打断"必须是一个独立状态，而不是"停止播放"
 * ═══════════════════════════════════════════════════════════════
 *
 * 直觉做法：用户说话时调 `tts.stop()`。但那**不够** ——
 * 停止播放只解决了"声音停了"，还有三件事没做：
 *
 *  1. **模型那边还在生成**。它可能已经在写第三段了；
 *     不取消请求的话，那些字会继续流过来（用户已经不想听了）
 *  2. **已经说出半句的话要不要留**。ChatGPT 的做法是
 *     **保留已播出的部分**在对话历史里，后面的丢掉 ——
 *     否则用户会看到"它说的话在历史里消失了"
 *  3. **打断之后要立刻进入"听"**。若停在"说"的动效上，
 *     用户会以为没打断成功，再喊一次
 *
 * ⇒ 打断是一个**从 `SPEAKING` 直接切到 `LISTENING`** 的原子转换，
 *   同时携带"取消生成"与"截断已播出文本"两个副作用意图。
 *
 * ★ 全纯 Kotlin，可离线测（见 `VoiceCallStateTest`）。
 */
object VoiceCall {

    /**
     * 通话当前在做什么。
     *
     * ⚠️ 四态**必须互相排斥**：用两个布尔（`isPlaying` / `isRecording`）
     * 表达会出现"两个都为真"的中间态 —— 那时界面的动效该播哪个？
     * 用户看到的就是"动画在乱跳"。
     */
    enum class Phase {
        /** 空闲（刚进页面、或刚挂断）。 */
        IDLE,

        /** 正在**听**用户说话（麦克风开）。动效：呼吸式外扩。 */
        LISTENING,

        /** 正在**想**（模型还没出字）。动效：缓慢旋转的粒子。 */
        THINKING,

        /** 正在**说**（TTS 播放中）。动效：随音量起伏的波形。 */
        SPEAKING,
    }

    /** 一次通话的状态。 */
    data class State(
        val phase: Phase = Phase.IDLE,
        /**
         * 已经播出去的文本（被打断时**保留**这一段）。
         *
         * ★ 为什么要存：用户打断之后，界面和历史里都必须能看到
         *   "它刚才说到哪"。直接清空会让那段话凭空消失，
         *   而用户明明听见了。
         */
        val spokenText: String = "",
        /** 模型正在生成、还没播完的文本（被打断时**丢弃**）。 */
        val pendingText: String = "",
        /** 麦克风是否真的开着（权限/设备问题会让它为假）。 */
        val micOpen: Boolean = false,
        /**
         * 这一次通话是否已经被用户打断过。
         *
         * ⚠️ 它不是"当前是否在打断"，而是"这一轮被打断过" ——
         *   用来在界面上给一句轻提示（"你打断了我"），
         *   以及避免自动重播。
         */
        val interrupted: Boolean = false,
    )

    /**
     * 开始通话。
     *
     * ⚠️ 初始相位是 [Phase.LISTENING] 而不是 IDLE：
     *   用户点"语音通话"就是要说话，多一次点击（"请说话"）是无谓门槛。
     *   ChatGPT / 豆包都是接通即听。
     */
    fun start(micOpen: Boolean = true): State =
        State(phase = Phase.LISTENING, micOpen = micOpen)

    /**
     * 用户说完了，开始等模型。
     *
     * @return 新的状态；如果当前不在 LISTENING 则**原样返回**
     *
     * ⚠️ 幂等性很重要：语音端点检测（VAD）可能在静音边界上
     *   触发两次 `finishSpeaking`。不判相位的话会发出两个请求。
     */
    fun userFinishedTalking(current: State): State =
        if (current.phase != Phase.LISTENING) current
        else current.copy(phase = Phase.THINKING, interrupted = false)

    /**
     * 模型的第一个字到了 —— 可以开始播。
     *
     * ★ 为什么不在"请求发出"时就进 SPEAKING：
     *   模型可能要想两三秒。那段时间界面必须显示"在想"
     *   （缓慢旋转），而不是"在说"（波形跳动）。
     *   否则用户会以为"它开口了但没声音"。
     */
    fun beginSpeaking(current: State, text: String): State =
        if (current.phase != Phase.THINKING) current
        else current.copy(phase = Phase.SPEAKING, pendingText = text)

    /**
     * 播放进度更新（累积已播出的文本）。
     *
     * ⚠️ **只累加，不覆盖**：TTS 是逐段播的，覆盖会丢掉前面的话。
     */
    fun appendSpoken(current: State, chunk: String): State {
        if (current.phase != Phase.SPEAKING) return current
        if (chunk.isEmpty()) return current
        return current.copy(
            spokenText = current.spokenText + chunk,
            // 已播出的从 pending 里扣掉 —— 但这里只清空，
            // 因为 pending 的语义是"还没播的"，播完就该没了。
            pendingText = "",
        )
    }

    /**
     * 说完了。
     *
     * @return 回到 [Phase.LISTENING]（不是 IDLE）——
     *   一轮说完应当**接着听**，这才是对话。
     *   要挂断得由用户明确点。
     */
    fun finishSpeaking(current: State): State =
        if (current.phase != Phase.SPEAKING) current
        else current.copy(
            phase = Phase.LISTENING,
            spokenText = "",
            pendingText = "",
        )

    /**
     * ═══════════════════════════════════════════════════════════════
     *  ★★ **用户打断** —— 这个方法是整个功能的重点
     * ═══════════════════════════════════════════════════════════════
     *
     * @param current 当前状态
     * @return 新状态 + **是否真的发生了打断**
     *
     * 只在 `SPEAKING` 或 `THINKING` 时有效：
     *  · `SPEAKING` ⇒ 停播放、取消生成、进 LISTENING
     *  · `THINKING` ⇒ 取消生成、进 LISTENING（用户不想等了）
     *  · 其它相位 ⇒ 不做事（已经在听了，再"打断"没有意义）
     *
     * ⚠️ 两个必须一起做的副作用（返回值让调用方执行）：
     *   · **取消模型请求** —— 不取消的话字会继续流过来
     *   · **保留 spokenText** —— 已经说出去的话不能从历史里消失
     *
     * ⚠️ 返回 `interrupted = true` 而不是让调用方自己判：
     *   界面上要据此显示"你打断了我"，而那必须与"真的打断成功"
     *   严格一致 —— 两处各判一次必然漂移。
     */
    data class InterruptResult(
        val state: State,
        /** 真的打断了（调用方应当：停 TTS、取消请求）。 */
        val didInterrupt: Boolean,
        /**
         * 已经被说出去、应当**保留进历史**的文本。
         *
         * ⚠️ 这是打断功能里最容易漏的一环：大多数实现直接
         *   `tts.stop()` 就完事，于是历史里那句话凭空消失，
         *   而用户明明听见了 —— 下次它"不记得自己说过什么"。
         */
        val keepText: String,
    )

    fun interrupt(current: State): InterruptResult {
        val canInterrupt = current.phase == Phase.SPEAKING || current.phase == Phase.THINKING
        if (!canInterrupt) {
            return InterruptResult(current, didInterrupt = false, keepText = "")
        }
        return InterruptResult(
            state = current.copy(
                phase = Phase.LISTENING,
                // ★ 已播出的**保留**在 state 里（供历史落库）——
                //   但它不再属于"当前要说的话"，所以 phase 已经变了。
                interrupted = true,
                // pending（没播的）直接丢 —— 用户不要听了。
                pendingText = "",
            ),
            didInterrupt = true,
            keepText = current.spokenText,
        )
    }

    /**
     * 用户主动点了"重听"。
     *
     * ⚠️ 只在被打断过、且已经回到 LISTENING 时有效：
     *   它不是"暂停/继续"，是把被打断的那段**重新说一遍**。
     */
    fun replay(current: State, text: String): State =
        if (!current.interrupted || text.isBlank()) current
        else current.copy(
            phase = Phase.SPEAKING,
            spokenText = "",
            pendingText = text,
            interrupted = false,
        )

    /** 挂断。 */
    fun hangUp(current: State): State = State()

    // ═══════════════════════════════════════════════════════════
    //  给界面用的派生量
    // ═══════════════════════════════════════════════════════════

    /**
     * 麦克风此刻**该不该开**。
     *
     * ★ 只有 LISTENING 该开 —— 说话时开麦会把 TTS 的声音
     *   录回去（回声），用户会听到自己助理的声音被它自己"听见"。
     *   这是语音通话里最常见的低级 bug。
     */
    fun shouldMicBeOpen(state: State): Boolean = state.phase == Phase.LISTENING

    /**
     * 这一相位要不要显示"取消/打断"按钮。
     *
     * ⚠️ 只在 SPEAKING / THINKING 时显示：LISTENING 时显示一个
     *   "打断"按钮会让用户困惑（打断什么？我在说啊）。
     */
    fun showsInterruptButton(state: State): Boolean =
        state.phase == Phase.SPEAKING || state.phase == Phase.THINKING

    /**
     * 相位对应的**动效类型**。
     *
     * ★ 三种动效必须**形状不同**，不能只是"颜色变一变"：
     *   · 听 → 从中心向外的呼吸环（麦克风在收音）
     *   · 想 → 缓慢旋转的粒子（在计算）
     *   · 说 → 随音量起伏的波形（在出声）
     *   形状不同，用户不用看字就知道轮到谁了。
     */
    enum class Motion {
        /** 呼吸式外扩环。 */
        BREATHING_RING,

        /** 缓慢旋转。 */
        SPINNING,

        /** 音量波形。 */
        WAVEFORM,

        /** 静止。 */
        STILL,
    }

    fun motionFor(phase: Phase): Motion = when (phase) {
        Phase.LISTENING -> Motion.BREATHING_RING
        Phase.THINKING -> Motion.SPINNING
        Phase.SPEAKING -> Motion.WAVEFORM
        Phase.IDLE -> Motion.STILL
    }

    /**
     * 给用户看的一句话状态。
     *
     * ⚠️ 措辞不能是"正在处理…"这类系统词 —— 用户在打电话，
     *   听到/看到的应当是**人的反应**。
     */
    fun labelFor(state: State): String = when (state.phase) {
        Phase.IDLE -> "已挂断"
        Phase.LISTENING -> if (state.micOpen) "我在听" else "麦克风没有打开"
        Phase.THINKING -> "嗯……"
        Phase.SPEAKING -> "在说"
    }
}
