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
        TtsProfiles.all.forEach { p ->
            assertThat(p.defaultVoices.map { it.id })
                .contains(p.defaultVoiceId)
        }
    }

    @Test
    fun `每个内置 profile 都至少有一个推荐音色`() {
        // 配置页要靠它给"用哪个音色"一个默认答案。
        // 一个都没有时界面只能显示空白，用户得自己猜。
        TtsProfiles.all.forEach { p ->
            assertThat(p.defaultVoices.count { it.recommended }).isEqualTo(1)
        }
    }

    @Test
    fun `每个内置 profile 都声明了 STREAM 能力`() {
        TtsProfiles.all.forEach { p ->
            assertThat(p.capabilities).contains(TtsCapability.STREAM)
        }
    }

    @Test
    fun `每个内置 profile 都有价格信息`() {
        // ⚠️ 价格未知会导致 estimateCost 恒返回 0 ——
        //    而语音是最容易被忽略的开销（"就说句话嘛"）。
        //    新建 profile 时忘了填 pricing，这条会拦下。
        TtsProfiles.all.forEach { p ->
            assertThat(p.pricing).isNotNull()
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
    ) = ProviderCredential(
        apiKey = key.toByteArray(),
        baseUrlOverride = baseUrl,
    )
}
