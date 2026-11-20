package com.pocketagent.provider.ttsopenai

import com.pocketagent.provider.api.AudioFormat
import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.TtsCapability
import com.pocketagent.provider.api.VoiceInfo

/**
 * 内置语音合成厂商配置表。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 2026-11-20 晚：从"两家"扩到"市面上能说清的那几家" + 自定义
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户原话：「语音的 apikey 输入你现在还是默认只有硅基流动和 openai 的，
 * 我说的要适配市面上大部分的语音 apikey，最好还要支持自定义的。」
 *
 * 判断"能不能进这张表"的标准没有变 —— **我能不能说清它的端点、请求体、
 * 返回形态**。所以这次收进来的全都是**照抄 OpenAI `/audio/speech` 协议**的，
 * 加上 ElevenLabs（它协议不同，但它自己的协议极简且稳定，见 [ElevenLabs]）。
 *
 * ⚠️ **仍然刻意不收的**，以及理由（写在这里，免得下次又有人问"为什么不加"）：
 *  · **阿里 DashScope / 火山引擎 / 腾讯云 / 讯飞 / 百度** —— 语音合成主推
 *    WebSocket 或带签名的私有 HTTP（AppId + 时间戳 + HMAC），
 *    凭据不是"一个 Key"而是"一堆参数"。塞进这个"填 Key 就完事"的界面里，
 *    用户会以为填错了，然后反复重试。要接它们得先有"多字段凭据"这个概念。
 *  · **MiniMax** —— `/v1/t2a_v2` 返回的是 JSON 里套 hex 的音频，
 *    不是裸字节流，与本文件的流式契约（[OpenAiCompatTtsProvider.synthesize]
 *    边收边发）不兼容，硬接会让首包延迟退回到"整句合成完"。
 *  · **Fish Audio** —— 请求体是 msgpack，不是 JSON。
 *  · **OpenRouter** —— 只代理对话，没有语音端点。
 *  · **本地 TTS（Ollama / LM Studio）** —— 走的是对话协议，不提供
 *    `/audio/speech`。本地语音应该走 Android 原生 `TextToSpeech`。
 *  · **Together AI** —— 官方只说"Audio API 由 Cartesia Sonic 提供"，
 *    音色 id 是一串不透明的字符串，我拿不到可枚举的清单；
 *    填一个我看不懂的音色进去，配完必然 400。
 *  · **Groq / PlayAI** —— 只输出压缩格式（flac / mp3 / ogg / wav），
 *    **没有 PCM**。而播放端（`TtsSpeaker`）是把字节当裸 PCM 直接写进
 *    AudioTrack 的，给它 mp3 就是一片刺耳噪声。要收它得先做设备侧解码，
 *    那是独立一刀 —— 列在这里却不能播，比不列更糟。
 *
 * ⚠️ BaseUrl、模型名、音色清单、输出格式都会随厂商变更而失效。
 *    所以每一家都允许用户在应用内覆盖 BaseUrl，另外 2026-11-20 起
 *    还允许覆盖**模型名与音色 id**（[TtsProfile.userDefined] 的自定义项必须填）——
 *    这是我们对抗"厂商改了清单我们来不及发版"的逃生舱。
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
        // ★ 2026-11-20：CosyVoice2 **接受正文里的发声标记**。
        //
        // ⚠️ 只填官方明确写出来的那几个。不认识的标记不会被忽略 ——
        //    CosyVoice 会把它逐字念出来（"左方括号 laughter 右方括号"），
        //    对用户来说就是"她突然开始念代码"。
        //
        // ★ 这是不换模型也能让用户听到停顿/笑/换气的**唯一一条路**，
        //   所以它比 instructions 更值得填对（后者默认模型根本不支持）。
        inlineTokens = setOf("[uv_break]", "[laugh]", "[laughter]"),
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
     * 智谱 GLM 语音。
     *
     * 协议与 OpenAI 同构，端点 `https://open.bigmodel.cn/api/paas/v4/audio/speech`。
     * 文档：<https://docs.bigmodel.cn/cn/guide/models/sound-and-video/glm-tts>
     *
     * ⚠️ **它只吐 `wav` 与 `pcm`**（官方 options 里就这两个）。
     *    所以我们发 `response_format=mp3` 会被 400 掉。这不是"少一层优化"，
     *    是"一句也念不出来" —— 所以 [supportedFormats] 必须显式收窄，
     *    由 Provider 侧做降级（见 `OpenAiCompatTtsProvider.buildRequest`）。
     *
     * ⚠️ `input` 上限 **1024 字符**，是这批厂商里最小的一个。
     *    超了本地就报错，别等它 400（它的 400 不说上限是多少）。
     */
    val Zhipu = TtsProfile(
        id = "zhipu-tts",
        displayName = "智谱 GLM 语音",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            TtsCapability.RATE, TtsCapability.MULTI_VOICE, TtsCapability.VOICE_CLONE,
        ),
        defaultModel = "glm-tts",
        defaultVoiceId = "tongtong",
        maxCharacters = 1_024,
        supportedFormats = setOf(AudioFormat.WAV, AudioFormat.PCM),
        defaultVoices = listOf(
            VoiceInfo("tongtong", "彤彤（默认女声）", "zh-CN", "女", recommended = true),
            VoiceInfo("chuichui", "锤锤（女声）", "zh-CN", "女"),
            VoiceInfo("xiaochen", "小陈（女声）", "zh-CN", "女"),
            VoiceInfo("jam", "Jam（动物圈音色）", "zh-CN", null),
            VoiceInfo("kazi", "Kazi（动物圈音色）", "zh-CN", null),
            VoiceInfo("douji", "Douji（动物圈音色）", "zh-CN", null),
            VoiceInfo("luodo", "Luodo（动物圈音色）", "zh-CN", null),
        ),
    )

    /**
     * 阶跃星辰 StepFun。
     *
     * 端点 `https://api.stepfun.com/v1/audio/speech`，官方示例用的就是
     * OpenAI 的 SDK 直连 —— 协议同构，没有争议。
     * 文档：<https://platform.stepfun.com/docs/zh/api-reference/audio/create-audio>
     *
     * ⚠️ `input` 上限 **1000 字符**。
     *
     * ⚠️ 音色清单我只填了文档示例里出现过的 `cixingnansheng`。
     *    官方另有"七大场景数十种音色"的清单页，但那是会变的富内容页，
     *    把猜来的 id 写死在这里，用户配完就是 400。所以：
     *    **想用别的音色，在 Key 编辑页的「音色」里自己填**（2026-11-20 起支持）。
     *    这比"我给你一个看起来很长、其中一半是错的清单"诚实。
     *
     * ★ 它的 `instruction` 字段只有 `stepaudio-2.5-tts` / `stepaudio-3-tts`
     *   认，默认模型 `step-tts-mini` 不认，所以 [supportsInstructions] 保持 false。
     *   想让声音标签生效的用户，把模型改成 `stepaudio-2.5-tts` 即可 ——
     *   但 `supportsInstructions` 是**每个配置一份**的静态值，改模型名不会
     *   自动打开这个开关。这条已写进 `docs/工程/未接线清单.md`，属于已知限制。
     */
    val StepFun = TtsProfile(
        id = "stepfun-tts",
        displayName = "阶跃星辰语音",
        defaultBaseUrl = "https://api.stepfun.com/v1",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            TtsCapability.STREAM, TtsCapability.RATE, TtsCapability.VOLUME,
            TtsCapability.MULTI_VOICE, TtsCapability.VOICE_CLONE,
        ),
        defaultModel = "step-tts-mini",
        defaultVoiceId = "cixingnansheng",
        maxCharacters = 1_000,
        supportedFormats = setOf(
            AudioFormat.MP3, AudioFormat.WAV, AudioFormat.OPUS, AudioFormat.PCM,
        ),
        defaultVoices = listOf(
            VoiceInfo("cixingnansheng", "磁性男声（官方示例音色）", "zh-CN", "男", recommended = true),
        ),
    )

    /**
     * DeepInfra（Kokoro-82M）。
     *
     * 端点 `https://api.deepinfra.com/v1/openai/audio/speech` —— 注意
     * 它的前缀是 `/v1/openai` 而不是 `/v1`。
     * 文档：<https://deepinfra.com/hexgrad/Kokoro-82M>
     *
     * ★ **它有中文音色**（`zf_*` 女声 / `zm_*` 男声），而且 Kokoro 是
     *   开源模型、80 美分/百万字符 —— 是这份表里最便宜的中文选项之一。
     *
     * ⚠️ 音色 id 是 `语言-性别_名字` 的约定（`zf` = 中文女，`zm` = 中文男，
     *    `af` = 美式英语女 …）。清单来自模型的 voices 目录，不是我编的。
     */
    val DeepInfra = TtsProfile(
        id = "deepinfra-tts",
        displayName = "DeepInfra 语音（Kokoro）",
        defaultBaseUrl = "https://api.deepinfra.com/v1/openai",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            TtsCapability.STREAM, TtsCapability.MULTI_VOICE, TtsCapability.RATE,
        ),
        defaultModel = "hexgrad/Kokoro-82M",
        defaultVoiceId = "zf_xiaobei",
        maxCharacters = null,
        pricing = TtsPricing(
            unit = PricingUnit.PER_MILLION_CHARACTERS,
            usdPerMillionUnits = 0.80,
        ),
        defaultVoices = listOf(
            VoiceInfo("zf_xiaobei", "晓北（中文女声）", "zh-CN", "女", recommended = true),
            VoiceInfo("zf_xiaoni", "晓妮（中文女声）", "zh-CN", "女"),
            VoiceInfo("zf_xiaoxiao", "晓晓（中文女声）", "zh-CN", "女"),
            VoiceInfo("zf_xiaoyi", "晓伊（中文女声）", "zh-CN", "女"),
            VoiceInfo("zm_yunjian", "云健（中文男声）", "zh-CN", "男"),
            VoiceInfo("zm_yunxi", "云希（中文男声）", "zh-CN", "男"),
            VoiceInfo("zm_yunxia", "云夏（中文男声）", "zh-CN", "男"),
            VoiceInfo("zm_yunyang", "云扬（中文男声）", "zh-CN", "男"),
            VoiceInfo("af_heart", "Heart（英语女声）", "en-US", "女"),
            VoiceInfo("am_michael", "Michael（英语男声）", "en-US", "男"),
        ),
    )

    /**
     * ElevenLabs。
     *
     * ⚠️ **它不照抄 OpenAI 协议**，所以 [protocol] 是 [TtsProtocol.ELEVENLABS]：
     *  · 端点里带音色：`/v1/text-to-speech/{voice_id}`
     *  · 鉴权头是 `xi-api-key`，不是 `Authorization: Bearer`
     *  · 请求体是 `{"text": ..., "model_id": ...}`，不是 `{"input": ..., "voice": ...}`
     *  · 输出格式写在 **query** 里（`?output_format=mp3_44100_128`）
     *
     * ★ 收它的理由：它是"音色最像人"的那一档，而本项目的用户
     *   恰恰在追求"别像 AI"。协议差异是 15 行代码的事，不值得为它单开一个模块。
     *
     * ⚠️ 价格不算在 [TtsPricing] 里（它的计价是"字符数 × 套餐档位"，
     *    不是统一的每百万字符价）。pricing = null 表示**未知**，
     *    上层按"不知道价格"处理，而不是当成免费。
     */
    val ElevenLabs = TtsProfile(
        id = "elevenlabs-tts",
        displayName = "ElevenLabs",
        defaultBaseUrl = "https://api.elevenlabs.io",
        authScheme = AuthScheme.ApiKeyHeader,
        apiKeyHeaderName = "xi-api-key",
        speechPath = "/v1/text-to-speech",
        protocol = TtsProtocol.ELEVENLABS,
        capabilities = setOf(
            TtsCapability.STREAM, TtsCapability.MULTI_VOICE, TtsCapability.VOICE_CLONE,
        ),
        defaultModel = "eleven_multilingual_v2",
        defaultVoiceId = "21m00Tcm4TlvDq8ikWAM",
        maxCharacters = 5_000,
        // ⚠️ 2026-11-20：**PCM 必须在这里**，不能只写 MP3。
        //    Provider 的降级规则是"请求的格式不在清单里 → 先退 PCM"，
        //    而 ElevenLabs 的 PCM 走格式码 `pcm_24000`（见 elevenLabsFormat）。
        //    清单里没有 PCM 时，播放端要的 PCM 会被降成 MP3 ——
        //    而 TtsSpeaker 把 MP3 字节当裸 PCM 写进 AudioTrack，出来一片噪声。
        //    （这条是跑测试时真发现的：目录里少写一个枚举值，表现是"没声音"。）
        supportedFormats = setOf(AudioFormat.PCM, AudioFormat.MP3),
        defaultVoices = listOf(
            VoiceInfo("21m00Tcm4TlvDq8ikWAM", "Rachel（女声）", "multi", "女", recommended = true),
            VoiceInfo("EXAVITQu4vr4xnSDxMaL", "Bella（女声）", "multi", "女"),
            VoiceInfo("AZnzlk1XvdvUeBnXmlld", "Domi（女声）", "multi", "女"),
            VoiceInfo("MF3mGyEYCl7XYWbV9V6O", "Elli（女声）", "multi", "女"),
            VoiceInfo("pNInz6obpgDQGcFmaJgB", "Adam（男声）", "multi", "男"),
            VoiceInfo("ErXwobaYiN019PkySvjV", "Antoni（男声）", "multi", "男"),
            VoiceInfo("TxGEqnHWrfWFTfGW9XjX", "Josh（男声）", "multi", "男"),
            VoiceInfo("VR6AewLTigWG4xSOukaG", "Arnold（男声）", "multi", "男"),
        ),
    )

    /**
     * BreezeBlue **官方云端** —— 与上面那条自建是同一个模型，两种交付。
     *
     * ═══════════════════════════════════════════════════════════════
     *  协议：它走的是 ElevenLabs 那一套（不是 OpenAI）
     * ═══════════════════════════════════════════════════════════════
     *
     * 官方文档（`https://docs.breezeblue.ai`）写得很明确：
     *  · Base `https://api.breeze.blue/v1`
     *  · 鉴权头 `xi-api-key: brz_…`
     *  · `POST /v1/text-to-speech/{voice_id}?output_format=…&delivery=sync`
     *  · body `{text, model_id, language_code?, instructions?, voice_settings{guidance_scale, speed, volume, use_speaker_boost}}`
     *
     * ⇒ **与 ElevenLabs 同构**（音色在路径、格式在 query、鉴权头同名），
     *   所以直接复用 `TtsProtocol.ELEVENLABS`，零额外代码。这不是巧合：
     *   它的错误体 `{ok, code, detail, error}` 也是 ElevenLabs 的形状。
     *
     * ⚠️ **只声明 PCM**，理由很具体：我们的 `elevenLabsFormat` 把非 PCM
     *   一律写成 `mp3_44100_128`，而 Breeze 只认 `mp3_24000_*` ——
     *   41.1 kHz 那个码会 400，用户看到的是"一句也念不出来"。
     *   只留 PCM 就能保证请求永远落到 `pcm_24000`（它的枚举里有这个值），
     *   而且播放端（`TtsSpeaker`）本来就要裸 PCM。
     *
     * ⚠️ 音色 id 是**账号里的**（`voc_…`）。这里放的 `voc_xeh3w54cqvnp`
     *   是官方文档示例里的公开音色 —— 你自己的账号用不到它时，
     *   请在 Key 编辑页的「音色 id」里填自己的（或用 `/v1/voices` 查）。
     *
     * ⚠️ 价格按 credits 计（见官方 pricing），没有"每百万字符"单价 ⇒
     *   pricing = null，上层按"不知道价格"处理，而不是当免费。
     */
    val BreezeBlueCloud = TtsProfile(
        id = "breezeblue-cloud-tts",
        displayName = "BreezeBlue（官方云端）",
        defaultBaseUrl = "https://api.breeze.blue",
        authScheme = AuthScheme.ApiKeyHeader,
        apiKeyHeaderName = "xi-api-key",
        speechPath = "/v1/text-to-speech",
        protocol = TtsProtocol.ELEVENLABS,
        // 有 speed（voice_settings，0.5–2.0），音色按 voice_id 走，可以多套
        capabilities = setOf(
            TtsCapability.STREAM, TtsCapability.MULTI_VOICE, TtsCapability.RATE,
        ),
        defaultModel = "breeze-tts-2",
        defaultVoiceId = "voc_xeh3w54cqvnp",
        // 文档：默认 1000 字符，申请提高后最多 2000。按默认值拦，
        // 超了让用户分段 —— 别让他收到一个只说"too long"的 422。
        maxCharacters = 1_000,
        supportedFormats = setOf(AudioFormat.PCM),
        pricing = null,
        // 它的 instructions 就是"这句话该怎么演"，与自建那条同一个模型能力
        supportsInstructions = true,
        inlineTokens = setOf("(laughs)", "[笑]"),
        defaultVoices = listOf(
            VoiceInfo("voc_xeh3w54cqvnp", "官方示例音色（voc_xeh3w54cqvnp）", "zh", recommended = true),
        ),
    )

    /**
     * 自定义（OpenAI 兼容）。
     *
     * ★ 这是"市面上大部分"这个要求的真正兜底：只要对方的端点是
     *   `POST {baseUrl}/audio/speech`、吃 `{model, input, voice, response_format}`、
     *   返回裸音频字节，就能用 —— 自建网关、公司内网、OneAPI / New-API
     *   这类聚合转发、以及我们还没收录的厂商，全都走这一条。
     *
     * ⚠️ **BaseUrl 必填**（[userDefined] = true）。没有默认值是有意的：
     *    给一个 `https://api.example.com/v1` 的假默认值，用户不填就会
     *    收到一个与该厂商无关的网络错误，然后去怀疑自己的 Key。
     *
     * ⚠️ 模型名与音色 id 也由用户填（Key 编辑页的「模型」「音色」两个输入框）。
     *    这里给的 `tts-1` / `alloy` 只是**占位提示**，不是"能用"的承诺。
     */
    val Custom = TtsProfile(
        id = "custom-tts",
        displayName = "自定义（OpenAI 兼容）",
        defaultBaseUrl = "",
        authScheme = AuthScheme.Bearer,
        capabilities = setOf(
            TtsCapability.STREAM, TtsCapability.RATE, TtsCapability.MULTI_VOICE,
        ),
        defaultModel = "tts-1",
        defaultVoiceId = "alloy",
        maxCharacters = null,
        userDefined = true,
    )

    /**
     * BreezeBlue（Breeze-TTS-2）—— 跑在**你自己机器上**的那一档。
     *
     * ═══════════════════════════════════════════════════════════════
     *  为什么它和别家不一样：模型不是服务，是权重
     * ═══════════════════════════════════════════════════════════════
     *
     * Breeze-TTS-2 是 24 kHz 中英双语 TTS（3.5B），官方只发权重，
     * 由 vLLM-Omni 起一个 OpenAI 兼容服务：
     * `vllm-omni serve BreezeBlue/Breeze-TTS-2 --omni --host 0.0.0.0 --port 8091`
     * ⇒ 端点形如 `http://192.168.1.10:8091/v1`，**每台机器都不一样**。
     *
     * 所以这一行有三处与别家不同，都是有意的：
     *  1. [TtsProfile.requiresBaseUrlOverride] = true —— 地址必须用户填，
     *     不给假默认值（理由同 [Custom]）。
     *  2. [TtsProfile.pcmNeedsStreamFlags] = true —— 它只在
     *     `stream: true` + `stream_format: "audio"` + `response_format: "pcm"`
     *     三者齐备时才边生成边吐裸 PCM。
     *  3. [TtsProfile.supportsInstructions] = true —— 它的 **Voice Design**
     *     就是一句话描述音色（「年轻女性，声音温暖自然，使用标准普通话」）。
     *     这是我们目前唯一能"用文字捏音色"的厂商，所以值得单独列出来。
     *
     * ⚠️ **没有命名音色**。`voice` 收的是 checkpoint 的说话人标签
     *    （`S0` / `[S0]`），文档明确写"不是音色清单"。所以清单只放 S0
     *    —— 编几个看着像音色的 id 进去，用户选中必然 400。
     *    想换音色请用 instructions（Voice Design）或 ref_audio（克隆，
     *    本应用暂未接）。
     *
     * ⚠️ 只要 WAV / PCM：它不产 mp3 / opus / aac。缺省请求 mp3 会被
     *    [OpenAiCompatTtsProvider.effectiveFormat] 降到 PCM。
     *
     * ⚠️ 44100/16000 的设备采样率这里不处理 —— 本项目播放端固定
     *    24 kHz（`TtsSpeaker.PCM_SAMPLE_RATE`），与它正好一致。
     *
     * ⚠️ Key：vLLM 不设 `--api-key` 时不校验鉴权头，但 Key 编辑页要求
     *    非空才能保存 —— 随便填一个（比如 `local`）即可，它不会被校验。
     */
    val BreezeBlue = TtsProfile(
        id = "breezeblue-tts",
        displayName = "BreezeBlue（自建 vLLM）",
        // 空串 = 必须由用户填，见 requiresBaseUrlOverride
        defaultBaseUrl = "",
        authScheme = AuthScheme.Bearer,
        // ⚠️ 只声明 STREAM：它没有 speed 参数，也没有命名音色，
        //    硬声明 RATE / MULTI_VOICE 会让上层发 speed、让界面给出
        //    一张不存在的音色表。
        capabilities = setOf(TtsCapability.STREAM),
        // 只吐这两种；mp3 请求会被降到 PCM（见 effectiveFormat）
        supportedFormats = setOf(AudioFormat.WAV, AudioFormat.PCM),
        defaultModel = "BreezeBlue/Breeze-TTS-2",
        defaultVoiceId = "S0",
        defaultVoices = listOf(
            VoiceInfo("S0", "S0（默认说话人）", recommended = true),
        ),
        // 文档没给硬上限，由服务端拒绝
        maxCharacters = null,
        // 自建 = 没有单价。写 0.0 会让估算看起来"免费且精确"，
        // 而真实成本是你的电费和显卡。
        pricing = null,
        // Voice Design：用一句话描述音色 —— 这家的主打能力
        supportsInstructions = true,
        // 文档明确写 `(laughs)`、`[笑]` 这类发声标记会**原样**交给模型
        inlineTokens = setOf("(laughs)", "[笑]"),
        requiresBaseUrlOverride = true,
        pcmNeedsStreamFlags = true,
    )

    /**
     * 全部内置配置，用于 UI 展示。
     *
     * ⚠️ 顺序 = UI 展示顺序，按"中文用户最可能手里已经有 Key"排：
     *    硅基流动（中文首选、便宜）→ OpenAI（协议定义者）→ 智谱 → 阶跃
     *    → DeepInfra（最便宜的中文）→ ElevenLabs（最像人）
     *    → 自定义（永远排最后：它是逃生舱，不是首选）。
     */
    val all: List<TtsProfile> = listOf(
        SiliconFlow,
        OpenAI,
        Zhipu,
        StepFun,
        DeepInfra,
        ElevenLabs,
        BreezeBlueCloud,
        BreezeBlue,
        Custom,
    )

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
