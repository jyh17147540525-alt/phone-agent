package com.pocketagent.provider.ttsopenai

import com.google.common.truth.Truth.assertThat
import com.pocketagent.provider.api.AudioFormat
import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Cost
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.SpeechRequest
import com.pocketagent.provider.api.TtsCapability
import okhttp3.OkHttpClient
import org.junit.Test

/**
 * OpenAI 兼容 TTS 的纯逻辑测试 —— **不发起任何网络请求**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与对话侧对齐的测试纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * 上游的 `OpenAiCompatProviderTest` 定下了一条规矩：单测不联网。
 * 理由是联网测试会变成"今天通明天不通"的噪音源，而 CI 上没有网络时
 * 它们提供的只有红色。
 *
 * 这里沿用同一条规矩，但覆盖的东西比对话侧多 —— 因为语音的
 * **纯逻辑面比对话大**：请求体构造（字段名）、费用估算（字符 vs 字节）、
 * profile 表的完整性，这三块都不需要网络就能测透，而且正好是
 * "写错了不报错"的重灾区。
 *
 * ⚠️ 不覆盖的：流式读取、音频字节完整性、HTTP 错误映射。
 *    那三块需要真实响应，留给真机联调。**这个缺口是明确的**，
 *    写在这里以免下次有人以为它们被测过了。
 */
class OpenAiCompatTtsProviderTest {

    private val profile = TtsProfiles.OpenAI
    private val provider = OpenAiCompatTtsProvider(profile, OkHttpClient())

    // ═══════════════════════════════════════════════════════════
    //  请求体构造 —— 字段名写错是最隐蔽的一类 bug
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `请求体字段名符合协议`() {
        // ★ 特别注意是 `input` 而**不是** `text`。
        //   这个字段名与多数人凭直觉写出来的不一样，而写错的后果是
        //   服务端返回 400，报错只说"参数缺失"、不说缺哪个 ——
        //   排查时会把 Key、音色、模型名都试一遍，全都不是原因。
        val body = provider.renderRequestBody(
            SpeechRequest(text = "你好", voiceId = "alloy", format = AudioFormat.MP3)
        )

        assertThat(body).contains("\"input\":\"你好\"")
        assertThat(body).doesNotContain("\"text\"")
        assertThat(body).contains("\"model\":\"tts-1\"")
        assertThat(body).contains("\"voice\":\"alloy\"")
        assertThat(body).contains("\"response_format\":\"mp3\"")
    }

    @Test
    fun `未指定音色时填 profile 默认值而不是省略字段`() {
        // 部分厂商缺 voice 字段会 400。填一个默认值比省略更安全 ——
        // 而且"省略"的意图（用厂商默认音色）本来就等于填那个默认值。
        val body = provider.renderRequestBody(SpeechRequest(text = "hi", voiceId = null))

        assertThat(body).contains("\"voice\":\"alloy\"")
    }

    @Test
    fun `语速只在用户显式指定时才出现`() {
        // ★ 不指定时**不发** speed 字段，而不是发一个 1.0。
        //   理由：厂商的默认语速未必是 1.0（有的会把中文调慢），
        //   我们硬塞 1.0 会在用户什么都没做的情况下改变听感。
        val without = provider.renderRequestBody(SpeechRequest(text = "hi"))
        assertThat(without).doesNotContain("\"speed\"")

        val with = provider.renderRequestBody(SpeechRequest(text = "hi", rate = 1.5))
        assertThat(with).contains("\"speed\":1.5")
    }

    @Test
    fun `音频格式名转成小写`() {
        // 枚举名是大写（MP3 / WAV / OPUS），而协议要求小写。
        // 忘了 lowercase 的后果是 400，且报错说"不支持的格式" ——
        // 而用户看到的格式名明明是对的。
        val body = provider.renderRequestBody(
            SpeechRequest(text = "hi", format = AudioFormat.MP3)
        )
        assertThat(body).contains("\"response_format\":\"mp3\"")
        assertThat(body).doesNotContain("\"MP3\"")
    }

    @Test
    fun `请求打到 profile 声明的合成端点`() {
        val request = provider.requestFor(
            credential(baseUrl = "https://api.openai.com/v1"),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.url.toString()).isEqualTo("https://api.openai.com/v1/audio/speech")
        assertThat(request.method).isEqualTo("POST")
    }

    @Test
    fun `BaseUrl 末尾的斜杠不会产生双斜杠`() {
        // 用户粘贴 BaseUrl 时带不带结尾斜杠纯属随机。
        // 不做 trimEnd 会得到 `//audio/speech` —— 多数服务端会 404，
        // 而报错说的是"路径不存在"，用户会去怀疑端点写错了。
        val request = provider.requestFor(
            credential(baseUrl = "https://api.openai.com/v1/"),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.url.toString()).doesNotContain("//audio")
    }

    @Test
    fun `用户覆盖的 BaseUrl 优先于 profile 默认值`() {
        val request = provider.requestFor(
            credential(baseUrl = "https://my-proxy.example.com/v1"),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.url.toString())
            .isEqualTo("https://my-proxy.example.com/v1/audio/speech")
    }

    @Test
    fun `Bearer 鉴权头被带上`() {
        val request = provider.requestFor(
            credential(key = "sk-abc123"),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.header("Authorization")).isEqualTo("Bearer sk-abc123")
    }

    @Test
    fun `硅基流动的端点路径与 OpenAI 同构`() {
        // ★ 这是"一份代码覆盖两家"的依据。若哪天有一家把路径改了，
        //   这条会红 —— 而不是等到用户配完发现 404。
        val sf = OpenAiCompatTtsProvider(TtsProfiles.SiliconFlow, OkHttpClient())
        val request = sf.requestFor(
            ProviderCredential(
                apiKey = "sk-x".toByteArray(),
                baseUrlOverride = "https://api.siliconflow.cn/v1",
            ),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.url.toString()).isEqualTo("https://api.siliconflow.cn/v1/audio/speech")
    }

    @Test
    fun `硅基流动请求体带完整音色 id`() {
        // ⚠️ 该厂商要求音色写成 `模型名:音色名`。漏掉前缀会 400，
        //    而报错说"音色不存在" —— 用户会去音色页反复找。
        val sf = OpenAiCompatTtsProvider(TtsProfiles.SiliconFlow, OkHttpClient())
        val body = sf.renderRequestBody(SpeechRequest(text = "hi", voiceId = null))

        assertThat(body).contains("\"voice\":\"FunAudioLLM/CosyVoice2-0.5B:alex\"")
        assertThat(body).contains("\"model\":\"FunAudioLLM/CosyVoice2-0.5B\"")
    }

    // ═══════════════════════════════════════════════════════════
    //  费用估算 —— 字符 vs 字节
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `OpenAI 按字符计费`() {
        val cost = provider.estimateCost(SpeechRequest(text = "a".repeat(1_000_000)))

        assertThat(cost).isEqualTo(Cost(15.0))
    }

    @Test
    fun `硅基流动按 UTF-8 字节计费`() {
        // ★ 本文件最容易被写错的一条。
        //
        //   一个汉字在 UTF-8 里是 **3 字节**。把字节数当字符数，
        //   中文成本会低估到三分之一 —— 而中文正是这家服务商的
        //   主要用例，也就是说**误差恰好全落在主路径上**。
        val sf = OpenAiCompatTtsProvider(TtsProfiles.SiliconFlow, OkHttpClient())
        val cost = sf.estimateCost(SpeechRequest(text = "啊".repeat(1_000_000)))

        // 300 万字节 × $7 = $21
        assertThat(cost).isEqualTo(Cost(21.0))
    }

    @Test
    fun `同样字数的中文比英文贵`() {
        // 这条把上面那个差异钉成一个可读的性质。
        // 两家服务商在这一点上的排序是一致的：中文的计量单位更大。
        val text = "啊".repeat(100)

        val openai = provider.estimateCost(SpeechRequest(text = text))
        val sf = OpenAiCompatTtsProvider(TtsProfiles.SiliconFlow, OkHttpClient())
            .estimateCost(SpeechRequest(text = text))

        // ⚠️ 这里不断言绝对值（价格会变），只断言"中文不会比同长度的
        //    英文便宜"这个结构性事实 —— 若哪天计费口径反了，它会红。
        val openaiAscii = provider.estimateCost(SpeechRequest(text = "a".repeat(100)))
        assertThat(openai.usd).isAtLeast(openaiAscii.usd)
        assertThat(sf.usd).isAtLeast(openaiAscii.usd)
    }

    @Test
    fun `空文本估算为 0`() {
        assertThat(provider.estimateCost(SpeechRequest(text = ""))).isEqualTo(Cost(0.0))
        val sf = OpenAiCompatTtsProvider(TtsProfiles.SiliconFlow, OkHttpClient())
        assertThat(sf.estimateCost(SpeechRequest(text = ""))).isEqualTo(Cost(0.0))
    }

    // ═══════════════════════════════════════════════════════════
    //  Profile 表本身的约束
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `内置 profile 的 id 互不重复`() {
        assertThat(TtsProfiles.all.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `每个内置 profile 的默认音色都在音色清单里`() {
        // ★ 防的是"改了 defaultVoiceId 却忘了同步清单"。
        //   症状是配置页把推荐音色显示成"未知"，而默认值本身还能用 ——
        //   于是没人会发现，直到用户去音色列表里找不到它。
        //
        // ⚠️ 2026-11-20：清单可以为空 —— 那正是「自定义」的样子。
        //   它的音色 id 由用户在 Key 编辑页自己填，我们**没有**诚实的
        //   清单可列（见 TtsProfiles.Custom 的注释）。空清单时这条
        //   失去意义，跳过；但有清单就必须自洽。
        TtsProfiles.all.forEach { p ->
            if (p.defaultVoices.isEmpty()) return@forEach
            assertThat(p.defaultVoices.map { it.id })
                .contains(p.defaultVoiceId)
        }
    }

    @Test
    fun `每个内置 profile 都至少有一个推荐音色`() {
        // 配置页要靠它给"用哪个音色"一个默认答案。
        // 一个都没有时界面只能显示空白，用户得自己猜。
        //
        // ⚠️ 例外同样只有「自定义」：它的音色由用户填，
        //   我们推荐任何一个具体 id 都是在替一个不认识的端点做主。
        TtsProfiles.all.filterNot { it.userDefined }.forEach { p ->
            assertThat(p.defaultVoices.count { it.recommended }).isEqualTo(1)
        }
    }

    @Test
    fun `只有智谱不声明流式能力`() {
        // 2026-11-20：加智谱时才发现原来这条测试假设"人人流式"。
        // 事实不是：智谱的 /audio/speech 是**整段返回**的，
        // 它没有 chunked 输出。声明 STREAM 会让上层按流式去读，
        // 读到的是一整块 —— 表现是"要等全部生成完才出声"。
        //
        // 所以这里改成**显式钉住例外**：想再放宽必须来这里改一行，
        // 而不是让一个新 profile 悄悄少声明一项能力。
        val noStream = TtsProfiles.all.filterNot { TtsCapability.STREAM in it.capabilities }
        assertThat(noStream.map { it.id }).containsExactly("zhipu-tts")
    }

    @Test
    fun `每个内置 profile 都有价格信息`() {
        // ⚠️ 价格未知会导致 estimateCost 恒返回 0 ——
        //    而语音是最容易被忽略的开销（"就说句话嘛"）。
        //    新建 profile 时忘了填 pricing，这条会拦下。
        //
        // ⚠️ 2026-11-20 的例外清单（每条都有理由，不是"懒得填"）：
        //    · elevenlabs-tts —— 订阅档位（按 credits 扣），没有每百万字符单价
        //    · zhipu-tts / stepfun-tts —— 官方价目表按"万字符/元"标，
        //      而且随套餐浮动；换算成美元单价会得出一个看着精确、其实错的数。
        //      宁可显式写"未知"（estimateCost 返回 0），也不要编一个数字。
        //    · custom-tts —— 用户自己付，我们连它按什么计费都不知道
        //    · breezeblue-tts —— 自建服务，跑在用户自己的显卡上；
        //      没有单价（写 0.0 会显得"免费且精确"，真实成本是电费）
        val allowedUnknown = setOf(
            "elevenlabs-tts", "zhipu-tts", "stepfun-tts", "custom-tts",
            "breezeblue-tts", "breezeblue-cloud-tts",
        )
        TtsProfiles.all.filterNot { it.id in allowedUnknown }.forEach { p ->
            assertThat(p.pricing).isNotNull()
        }
        // 反向钉住：例外清单里的那些**确实**是 null。
        // 谁哪天补上了价格，这条会提醒把它从清单里挪走 ——
        // 否则清单会慢慢变成"什么都允许"的垃圾桶。
        TtsProfiles.all.filter { it.id in allowedUnknown }.forEach { p ->
            assertThat(p.pricing).isNull()
        }
    }

    @Test
    fun `硅基流动的音色 id 都带模型前缀`() {
        val p = TtsProfiles.SiliconFlow
        p.defaultVoices.forEach { voice ->
            assertThat(voice.id).contains(":")
            assertThat(voice.id).startsWith(p.defaultModel)
        }
    }

    @Test
    fun `推荐 profile 排在展示顺序第一位`() {
        // 顺序即 UI 顺序。中文场景默认推荐排第一。
        assertThat(TtsProfiles.all.first()).isEqualTo(TtsProfiles.recommended)
    }

    @Test
    fun `按 id 查找能命中每一个内置 profile`() {
        TtsProfiles.all.forEach { p ->
            assertThat(TtsProfiles.byId(p.id)).isEqualTo(p)
        }
        assertThat(TtsProfiles.byId("不存在的服务商")).isNull()
    }

    @Test
    fun `OpenAI 的字符上限与官方文档一致`() {
        // 4096 是写死在官方文档里的。改了它要么拦得太早（用户莫名被拒），
        // 要么拦得太晚（发了请求才发现超限）。
        assertThat(TtsProfiles.OpenAI.maxCharacters).isEqualTo(4096)
    }

    @Test
    fun `价格未知的 profile 估算为 0 且不抛异常`() {
        // ⚠️ 与对话侧同一个原则：未知**不等于**免费。
        //    对话侧的 parsePrice 用 null 表达未知；这里 profile 没有
        //    pricing 时返回 Cost(0.0) 表达的是"没得算"。
        //    有价格的两个 profile 已在上面测过（都不是 0），
        //    所以 Cost(0.0) 只可能来自"没有定价信息"。
        val noPricing = profile.copy(id = "test-no-pricing", pricing = null)
        val p = OpenAiCompatTtsProvider(noPricing, OkHttpClient())

        assertThat(p.estimateCost(SpeechRequest(text = "a".repeat(1_000_000))))
            .isEqualTo(Cost(0.0))
    }

    @Test
    fun `自定义端点的 profile 不需要额外支持`() {
        // 用户填自己的 OpenAI 兼容端点时，只需覆盖 BaseUrl ——
        // 这也是"自定义语音服务商"功能的底层支撑（与对话侧同一设计）。
        val custom = profile.copy(
            id = "custom-tts",
            displayName = "自建语音端点",
            defaultBaseUrl = "https://tts.internal.example/v1",
        )
        val p = OpenAiCompatTtsProvider(custom, OkHttpClient())
        val request = p.requestFor(
            ProviderCredential(apiKey = "k".toByteArray(), baseUrlOverride = null),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.url.toString())
            .isEqualTo("https://tts.internal.example/v1/audio/speech")
    }

    // ═══════════════════════════════════════════════════════════
    //  协议分派 / 格式降级 / 凭据覆盖（2026-11-20 新增）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `ElevenLabs 的音色在路径里_格式在 query 里`() {
        // ★ 它与 OpenAI 协议在**四个地方**不同：路径、鉴权头、请求体、
        //   格式位置。少改一处就是一个 400，而报错不会告诉你是哪一处。
        val p = OpenAiCompatTtsProvider(TtsProfiles.ElevenLabs, OkHttpClient())
        val request = p.requestFor(
            credential(key = "el-key"),
            SpeechRequest(text = "你好", format = AudioFormat.PCM),
        )

        // 音色拼在路径末段，格式走 query
        assertThat(request.url.toString()).isEqualTo(
            "https://api.elevenlabs.io/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM?output_format=pcm_24000"
        )
        // 鉴权走 xi-api-key，不是 Bearer
        assertThat(request.header("xi-api-key")).isEqualTo("el-key")
        assertThat(request.header("Authorization")).isNull()
    }

    @Test
    fun `ElevenLabs 的请求体是 text 加 model_id`() {
        // ⚠️ 与 OpenAI 正好相反：那边是 input/model，这边是 text/model_id。
        val p = OpenAiCompatTtsProvider(TtsProfiles.ElevenLabs, OkHttpClient())
        val body = p.renderRequestBody(SpeechRequest(text = "你好", format = AudioFormat.PCM))

        assertThat(body).contains("\"text\":\"你好\"")
        assertThat(body).contains("\"model_id\":\"eleven_multilingual_v2\"")
        // 不发 OpenAI 那几个字段
        assertThat(body).doesNotContain("\"input\"")
        assertThat(body).doesNotContain("\"response_format\"")
        assertThat(body).doesNotContain("\"voice\"")
    }

    @Test
    fun `ElevenLabs 要 PCM 时给 24k_而不是 16k`() {
        // ⚠️ 24k 必须对上 TtsSpeaker 的 AudioTrack 采样率。
        //   给 16k 不会报错，只会变慢 —— 听起来像"嗓子坏了"，
        //   而日志里一切正常，这种问题极难归因。
        val p = OpenAiCompatTtsProvider(TtsProfiles.ElevenLabs, OkHttpClient())
        val request = p.requestFor(
            credential(key = "k"),
            SpeechRequest(text = "hi", format = AudioFormat.PCM),
        )

        assertThat(request.url.toString()).contains("output_format=pcm_24000")
    }

    @Test
    fun `智谱只吐 wav_要 mp3 时降到 PCM 而不是 mp3`() {
        // ★ 这是"一句也念不出来"那类 bug 的正面修复：
        //   智谱的 response_format 只接受 wav/pcm，发 mp3 直接 400。
        //   ⚠️ 降级必须优先选 PCM —— 播放端只吃裸 PCM，
        //   降到 mp3 是"把压缩字节当 PCM 播"，出来一片噪声，比没声音更糟。
        val p = OpenAiCompatTtsProvider(TtsProfiles.Zhipu, OkHttpClient())
        val body = p.renderRequestBody(SpeechRequest(text = "hi", format = AudioFormat.MP3))

        assertThat(body).contains("\"response_format\":\"pcm\"")
        assertThat(body).doesNotContain("\"response_format\":\"mp3\"")
    }

    @Test
    fun `支持的格式原样发出去_不被降级`() {
        val p = OpenAiCompatTtsProvider(TtsProfiles.Zhipu, OkHttpClient())
        val body = p.renderRequestBody(SpeechRequest(text = "hi", format = AudioFormat.WAV))

        assertThat(body).contains("\"response_format\":\"wav\"")
    }

    @Test
    fun `凭据上的模型名覆盖 profile 默认值`() {
        // ★ 自定义端点/聚合转发的命门：它们要求一个特定的模型名，
        //   而那是用户私有的，我们不可能预置。
        val p = OpenAiCompatTtsProvider(TtsProfiles.Custom, OkHttpClient())
        val body = p.renderRequestBody(
            SpeechRequest(text = "hi"),
            credential(key = "k", model = "my-gateway-tts"),
        )

        assertThat(body).contains("\"model\":\"my-gateway-tts\"")
        assertThat(body).doesNotContain("\"model\":\"tts-1\"")
    }

    @Test
    fun `凭据上的音色覆盖默认_但请求里的音色仍最优先`() {
        val p = OpenAiCompatTtsProvider(TtsProfiles.Custom, OkHttpClient())
        val cred = credential(key = "k", voice = "from-credential")

        // 请求没指定 → 用凭据上的
        val byCredential = p.renderRequestBody(SpeechRequest(text = "hi"), cred)
        assertThat(byCredential).contains("\"voice\":\"from-credential\"")

        // 请求指定了 → 用请求的（试听场景）
        val byRequest = p.renderRequestBody(
            SpeechRequest(text = "hi", voiceId = "from-request"),
            cred,
        )
        assertThat(byRequest).contains("\"voice\":\"from-request\"")
    }

    @Test
    fun `空白的覆盖值按没填处理_不会发出空 model`() {
        // ⚠️ 空串会让上游收到 `model=""` 并报"参数缺失" ——
        //   而用户明明在界面上填过了（只是填了个空格）。
        val p = OpenAiCompatTtsProvider(TtsProfiles.Custom, OkHttpClient())
        val body = p.renderRequestBody(
            SpeechRequest(text = "hi"),
            credential(key = "k", model = "   ", voice = "  "),
        )

        assertThat(body).contains("\"model\":\"tts-1\"")
        assertThat(body).contains("\"voice\":\"alloy\"")
    }

    @Test
    fun `自定义项声明自己必须填端点地址`() {
        // 仓储层靠这个标记拦住"选了自定义却没填地址"。
        // 不在保存时拦住的话，用户每次合成都是"连不上"，
        // 而界面上一切正常 —— 他只会觉得这 App 的语音坏了。
        val custom = OpenAiCompatTtsProvider(TtsProfiles.Custom, OkHttpClient())
        val builtin = OpenAiCompatTtsProvider(TtsProfiles.OpenAI, OkHttpClient())

        assertThat(custom.requiresCustomEndpoint).isTrue()
        assertThat(builtin.requiresCustomEndpoint).isFalse()
    }

    @Test
    fun `QueryParam 鉴权在已有 query 时用 & 拼接`() {
        // ⚠️ 无脑拼 `?` 会得到 `...?output_format=pcm_24000?key=xxx`：
        //   服务端把 key 当成 output_format 的一部分，报"不支持的格式"，
        //   而真正的 Key 根本没被收到 —— 报错方向与真因完全无关。
        val queryEleven = TtsProfiles.ElevenLabs.copy(
            id = "el-query-like",
            authScheme = AuthScheme.QueryParam("key"),
        )
        val p = OpenAiCompatTtsProvider(queryEleven, OkHttpClient())
        val request = p.requestFor(
            credential(key = "qk"),
            SpeechRequest(text = "hi", format = AudioFormat.PCM),
        )

        val url = request.url.toString()
        assertThat(url).contains("output_format=pcm_24000&key=qk")
        assertThat(url).doesNotContain("pcm_24000?key")
    }

    // ═══════════════════════════════════════════════════════════
    //  鉴权方式的通用性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `ApiKeyHeader 鉴权走 profile 指定的头名`() {
        // Azure 风格的 `api-key` 头。放在这里是因为**协议实现是共用的** ——
        // 将来接 Azure 语音时不用改实现代码，加一个 profile 即可。
        val azureLike = profile.copy(
            id = "azure-like",
            authScheme = AuthScheme.ApiKeyHeader,
            apiKeyHeaderName = "api-key",
        )
        val p = OpenAiCompatTtsProvider(azureLike, OkHttpClient())
        val request = p.requestFor(
            ProviderCredential(apiKey = "secret".toByteArray(), baseUrlOverride = null),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.header("api-key")).isEqualTo("secret")
        assertThat(request.header("Authorization")).isNull()
    }

    @Test
    fun `QueryParam 鉴权把 Key 放进 URL`() {
        val geminiLike = profile.copy(
            id = "query-like",
            authScheme = AuthScheme.QueryParam("key"),
        )
        val p = OpenAiCompatTtsProvider(geminiLike, OkHttpClient())
        val request = p.requestFor(
            ProviderCredential(apiKey = "qk".toByteArray(), baseUrlOverride = null),
            SpeechRequest(text = "hi"),
        )

        assertThat(request.url.toString()).contains("key=qk")
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助
    // ═══════════════════════════════════════════════════════════

    private fun credential(
        key: String = "sk-test",
        baseUrl: String? = null,
        /** 用户指定的模型名（自定义端点用）；null = 用 profile 默认 */
        model: String? = null,
        /** 用户指定的音色 id；null = 用 profile 默认 */
        voice: String? = null,
    ) = ProviderCredential(
        apiKey = key.toByteArray(),
        baseUrlOverride = baseUrl,
        modelOverride = model,
        voiceOverride = voice,
    )

    // ═══════════════════════════════════════════════════════════
    //  BreezeBlue（vLLM-Omni 自建）—— 2026-11-20
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `BreezeBlue 必须自己填地址`() {
        // 权重跑在用户自己的机器上，端点每台都不一样。
        // ★ 这条钉住的是"不要给它编一个看着能用的默认地址"：
        //   填了假地址，用户不填就存下来，之后每次合成都是连不上，
        //   而界面上一切正常。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlue, OkHttpClient())

        assertThat(TtsProfiles.BreezeBlue.defaultBaseUrl).isEmpty()
        assertThat(p.requiresCustomEndpoint).isTrue()
    }

    @Test
    fun `BreezeBlue 要 PCM 时补上 stream 标记`() {
        // vLLM-Omni 的裸 PCM 流：stream + stream_format 两项缺一不可。
        // 漏了不出错，只是退化成"整段算完再给" —— 首包要等好几秒。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlue, OkHttpClient())
        val body = p.renderRequestBody(
            SpeechRequest(text = "你好", format = AudioFormat.PCM),
        )

        assertThat(body).contains("\"stream\":true")
        assertThat(body).contains("\"stream_format\":\"audio\"")
        assertThat(body).contains("\"response_format\":\"pcm\"")
    }

    @Test
    fun `BreezeBlue 要 WAV 时不加 stream 标记`() {
        // ⚠️ 反向钉住：加给所有厂商/所有格式会 400。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlue, OkHttpClient())
        val body = p.renderRequestBody(
            SpeechRequest(text = "你好", format = AudioFormat.WAV),
        )

        assertThat(body).doesNotContain("stream_format")
        assertThat(body).contains("\"response_format\":\"wav\"")
    }

    @Test
    fun `BreezeBlue 缺省音色是 S0 且 instructions 会被带上`() {
        // 它没有命名音色，voice 只是 checkpoint 的说话人标签；
        // 而 Voice Design（用一句话描述音色）走 instructions。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlue, OkHttpClient())
        val body = p.renderRequestBody(
            SpeechRequest(
                text = "你好",
                format = AudioFormat.WAV,
                instructions = "年轻女性，声音温暖自然，使用标准普通话。",
            ),
        )

        assertThat(body).contains("\"voice\":\"S0\"")
        assertThat(body).contains("年轻女性")
    }

    @Test
    fun `BreezeBlue 请求 mp3 会被降到 PCM`() {
        // 它只产 wav / pcm。请求 mp3 若不降级就是 400（一句也念不出来）。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlue, OkHttpClient())
        val body = p.renderRequestBody(SpeechRequest(text = "你好", format = AudioFormat.MP3))

        assertThat(body).contains("\"response_format\":\"pcm\"")
        assertThat(body).doesNotContain("\"response_format\":\"mp3\"")
    }

    @Test
    fun `BreezeBlue 不声明调速与多音色`() {
        // 它没有 speed 参数，也没有音色清单。硬声明会让上层发 speed、
        // 让界面画出一张不存在的音色表 —— 用户选了必然 400。
        val caps = TtsProfiles.BreezeBlue.capabilities

        assertThat(caps).contains(TtsCapability.STREAM)
        assertThat(caps).doesNotContain(TtsCapability.RATE)
        assertThat(caps).doesNotContain(TtsCapability.MULTI_VOICE)
    }

    // ═══════════════════════════════════════════════════════════
    //  BreezeBlue 官方云端（ElevenLabs 同构）—— 2026-11-20
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `BreezeBlue 云端的路径与鉴权头是 ElevenLabs 那一套`() {
        // 官方文档：Base https://api.breeze.blue/v1，
        // `POST /v1/text-to-speech/{voice_id}`，头 `xi-api-key: brz_…`。
        // ★ 写错任何一处都是 401/404，而报错不会说"你把协议搞错了"。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlueCloud, OkHttpClient())
        val request = p.requestFor(
            credential(key = "brz_test"),
            SpeechRequest(text = "hi", format = AudioFormat.PCM),
        )

        assertThat(request.url.toString()).startsWith(
            "https://api.breeze.blue/v1/text-to-speech/",
        )
        assertThat(request.header("xi-api-key")).isEqualTo("brz_test")
        assertThat(request.header("Authorization")).isNull()
    }

    @Test
    fun `BreezeBlue 云端要的格式是 pcm_24000`() {
        // 它的格式枚举里只有 mp3_24000_* / wav_24000 / pcm_24000 这些，
        // 没有 ElevenLabs 的 mp3_44100_128 —— 所以云端这一条只留 PCM。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlueCloud, OkHttpClient())
        val request = p.requestFor(
            credential(key = "brz_test"),
            SpeechRequest(text = "hi", format = AudioFormat.MP3),
        )

        assertThat(request.url.toString()).contains("output_format=pcm_24000")
    }

    @Test
    fun `BreezeBlue 云端会带上语气说明与语速`() {
        // instructions → body.instructions；rate → voice_settings.speed。
        // ★ 这两项决定"声音标签"能不能生效：`VoiceTagRenderer` 只在
        //   profile 声明 supportsInstructions 时才走第一档。
        val p = OpenAiCompatTtsProvider(TtsProfiles.BreezeBlueCloud, OkHttpClient())
        val body = p.renderRequestBody(
            SpeechRequest(
                text = "你好",
                format = AudioFormat.PCM,
                instructions = "轻声说，像怕被人听见。",
                rate = 0.9,
            ),
        )

        assertThat(body).contains("\"instructions\":\"轻声说，像怕被人听见。\"")
        assertThat(body).contains("\"voice_settings\"")
        assertThat(body).contains("\"speed\":0.9")
    }

    @Test
    fun `ElevenLabs 自己不会被塞进 instructions`() {
        // ⚠️ 反向钉住：ElevenLabs 收到顶层 instructions 会 422。
        //    所以"发不发"必须按 profile 开关，不能因为"它有这个能力"就发。
        val p = OpenAiCompatTtsProvider(TtsProfiles.ElevenLabs, OkHttpClient())
        val body = p.renderRequestBody(
            SpeechRequest(
                text = "hi",
                instructions = "say it softly",
                format = AudioFormat.PCM,
            ),
        )

        assertThat(body).doesNotContain("instructions")
    }

    @Test
    fun `BreezeBlue 云端按 1000 字符拦长度`() {
        // 文档：默认 1000 字符。拦在这里比等它回 422 便宜 ——
        // 那条报错只说 "text too long"，不会告诉我们上限。
        assertThat(TtsProfiles.BreezeBlueCloud.maxCharacters).isEqualTo(1_000)
    }
}
