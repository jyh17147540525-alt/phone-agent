package com.pocketagent.provider.ttsopenai

import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.TtsCapability
import com.pocketagent.provider.api.VoiceInfo

/**
 * 内置语音合成厂商配置表。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里**只收协议确定的服务商**
 * ═══════════════════════════════════════════════════════════════
 *
 * 判据是"我能不能说清它的端点、请求体、返回形态"。说不清的就不进这张表。
 *
 * **刻意不收的**：
 *  · **阿里 DashScope / 火山引擎** —— 语音合成主推 WebSocket
 *    （`wss://.../api-ws/v1/inference`），非实时 HTTP 端点的行为
 *    没有可靠文档可依。写一个"看起来能配、配完必然失败"的服务商，
 *    比不写更糟 —— 用户会以为自己填错了 Key，然后反复重试。
 *  · **OpenRouter** —— 它只代理对话，没有语音合成端点。
 *  · **各家本地 TTS（Ollama / LM Studio）** —— 那两条走的是对话协议，
 *    不提供 `/audio/speech`。本地语音将来应该走 Android 原生的
 *    `TextToSpeech`（系统 TTS 引擎），而不是绕一圈 HTTP。
 *
 * ⚠️ BaseUrl、模型名、音色清单都会随厂商变更而失效，需定期核对。
 *    用户可在应用内覆盖 BaseUrl —— 这是应对厂商改协议的逃生舱。
 */
object TtsProfiles {

    /**
     * OpenAI 官方。
     *
     * ⚠️ 价格按**字符**计费。`tts-1` 是 $15 / 100 万字符，
     *    `tts-1-hd` 是 $30 / 100 万字符，`gpt-4o-mini-tts` 更贵。
     *    这里取 `tts-1` 的价位 —— 它是默认模型，也是绝大多数用户
     *    实际会用的那个。**估算不要求精确，但量级必须对**。
     */
    val OpenAI = TtsProfile(
        id = "openai-tts",
        displayName = "OpenAI 语音",
        defaultBaseUrl = "https://api.openai.com/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            TtsCapability.STREAM, TtsCapability.RATE, TtsCapability.MULTI_VOICE,
        ),
        // 不做成可配置项：这几个模型是协议内置的固定值，
        // 让用户手填只会制造"填错模型名 → 400"的无谓失败
        defaultModel = "tts-1",
        defaultVoiceId = "alloy",
        // ⚠️ 4096 是官方文档写死的 input 上限
        maxCharacters = 4_096,
        pricing = TtsPricing(
            unit = PricingUnit.PER_MILLION_CHARACTERS,
            usdPerMillionUnits = 15.0,
        ),
        defaultVoices = listOf(
            VoiceInfo("alloy", "Alloy（中性）", language = null, gender = "中性", recommended = true),
            VoiceInfo("ash", "Ash（低沉男声）", gender = "男"),
            VoiceInfo("ballad", "Ballad（叙事男声）", gender = "男"),
            VoiceInfo("coral", "Coral（明亮女声）", gender = "女"),
            VoiceInfo("echo", "Echo（沉稳男声）", gender = "男"),
            VoiceInfo("fable", "Fable（英式女声）", gender = "女"),
            VoiceInfo("onyx", "Onyx（厚重男声）", gender = "男"),
            VoiceInfo("nova", "Nova（轻快女声）", gender = "女"),
            VoiceInfo("sage", "Sage（沉静女声）", gender = "女"),
            VoiceInfo("shimmer", "Shimmer（清亮女声）", gender = "女"),
            VoiceInfo("verse", "Verse（播音男声）", gender = "男"),
            VoiceInfo("marin", "Marin（温暖女声）", gender = "女"),
            VoiceInfo("cedar", "Cedar（自然男声）", gender = "男"),
        ),
    )

    /**
     * 硅基流动。
     *
     * ★ 这是本项目**中文场景的默认推荐**，理由有三：
     *  1. 协议与 OpenAI 完全同构（`/audio/speech`，字段名一模一样），
     *     所以复用同一个实现类，零额外代码
     *  2. CosyVoice2 对中文的支持明显好于 OpenAI 的音色，
     *     且支持粤语 / 四川话等方言
     *  3. **价格便宜一个数量级**（按 UTF-8 字节计费）
     *
     * ⚠️ 音色 id 的格式是 `模型名:音色名`（如
     *    `FunAudioLLM/CosyVoice2-0.5B:alex`）—— 漏掉模型前缀会 400。
     *    这里直接写成完整形式，不让用户自己拼。
     *
     * ⚠️ 计费口径是 **UTF-8 字节**（见 [PricingUnit]）。一个汉字 3 字节，
     *    所以中文的"每百万字符"成本是标称值的 3 倍 ——
     *    这个换算必须在 [PricingUnit] 那一侧完成，不能在这里假装是字符。
     */
    val SiliconFlow = TtsProfile(
        id = "siliconflow-tts",
        displayName = "硅基流动语音",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            TtsCapability.STREAM, TtsCapability.RATE,
            TtsCapability.MULTI_VOICE, TtsCapability.VOICE_CLONE,
        ),
        defaultModel = "FunAudioLLM/CosyVoice2-0.5B",
        // 音色 id 必须带模型前缀，见上
        defaultVoiceId = "FunAudioLLM/CosyVoice2-0.5B:alex",
        // 厂商文档没给硬上限，留给它自己拒绝
        maxCharacters = null,
        pricing = TtsPricing(
            unit = PricingUnit.PER_MILLION_BYTES,
            usdPerMillionUnits = 7.0,
        ),
        defaultVoices = listOf(
            VoiceInfo(
                id = "FunAudioLLM/CosyVoice2-0.5B:alex",
                displayName = "Alex（沉稳男声）",
                language = "zh-CN", gender = "男", recommended = true,
            ),
            VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:benjamin", "Benjamin（低沉男声）", "zh-CN", "男"),
            VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:charles", "Charles（磁性男声）", "zh-CN", "男"),
            VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:david", "David（欢快男声）", "zh-CN", "男"),
            VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:anna", "Anna（沉稳女声）", "zh-CN", "女"),
            VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:bella", "Bella（激情女声）", "zh-CN", "女"),
            VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:claire", "Claire（温柔女声）", "zh-CN", "女"),
            VoiceInfo("FunAudioLLM/CosyVoice2-0.5B:diana", "Diana（欢快女声）", "zh-CN", "女"),
        ),
    )

    /**
     * 全部内置配置，用于 UI 展示。
     *
     * ⚠️ 顺序 = UI 展示顺序。硅基流动排第一，因为它是中文场景的
     *    默认推荐（见上）；OpenAI 排第二，因为它是协议的定义者，
     *    也是海外用户最可能已经持有 Key 的一家。
     */
    val all: List<TtsProfile> = listOf(SiliconFlow, OpenAI)

    fun byId(id: String): TtsProfile? = all.firstOrNull { it.id == id }

    /**
     * 默认音色 id 的服务商。
     *
     * 用于"用户什么都没配"时的兜底 —— 但**不用于自动选用**：
     * 它只是告诉界面"如果要推荐，推这一个"。真正要不要用，
     * 取决于用户有没有配那个服务商的 Key。
     */
    val recommended: TtsProfile get() = SiliconFlow
}
