package com.pocketagent.provider.openaicompat

import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.ModelInfo
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider 的纯逻辑测试 —— 不发起任何网络请求。
 *
 * 覆盖：token 估算、费用估算、厂商配置表完整性、凭据对象的零泄漏保证。
 *
 * ⚠️ 网络相关的行为（流式解析、错误映射）不在这里测，靠真机联调与
 *    后续的 MockWebServer 测试覆盖。
 */
class OpenAiCompatProviderTest {

    private val provider = OpenAiCompatProvider(ProviderProfiles.OpenRouter)

    // ═══════════════════════════════════════════════════════════
    //  token 估算
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同样字数的中文比英文更贵`() {
        // 这是估算公式的核心假设：中文约 1.5 字/token，英文约 4 字符/token
        val zh = "这是一段用来测试分词估算的中文文本内容"
        val en = "a".repeat(zh.length)

        val zhTokens = provider.countTokens(listOf(ChatMessage.user(zh)), "m")
        val enTokens = provider.countTokens(listOf(ChatMessage.user(en)), "m")

        assertTrue("中文($zhTokens) 应多于英文($enTokens)", zhTokens > enTokens)
    }

    @Test
    fun `中文估算落在合理区间内`() {
        // 100 个汉字，按 1.5 字/token 约 67 token，加消息开销 4
        val text = "测".repeat(100)

        val tokens = provider.countTokens(listOf(ChatMessage.user(text)), "m")

        assertTrue("估算值 $tokens 偏离预期区间", tokens in 60..80)
    }

    @Test
    fun `空消息列表估算为 0`() {
        assertEquals(0, provider.countTokens(emptyList(), "m"))
    }

    @Test
    fun `图片按固定值计入而不是按字节数`() {
        // 一张 WebP 截图可能有 200KB，若按字节估算会算出 5 万 token，
        // 直接把预算熔断器打爆。所以图片必须走固定估值。
        val smallImage = ChatMessage(
            ChatMessage.Role.USER,
            listOf(ContentPart.Text("看图"), ContentPart.Image(ByteArray(1_000))),
        )
        val bigImage = ChatMessage(
            ChatMessage.Role.USER,
            listOf(ContentPart.Text("看图"), ContentPart.Image(ByteArray(500_000))),
        )

        val a = provider.countTokens(listOf(smallImage), "m")
        val b = provider.countTokens(listOf(bigImage), "m")

        assertEquals("图片大小不应影响估算", a, b)
        assertTrue("图片应被计入", a > provider.countTokens(listOf(ChatMessage.user("看图")), "m"))
    }

    @Test
    fun `工具调用与工具结果的参数都被计入`() {
        val msg = ChatMessage(
            ChatMessage.Role.ASSISTANT,
            listOf(ContentPart.ToolCall("id", "open_app", """{"pkg":"com.tencent.mm"}""")),
        )

        assertTrue(provider.countTokens(listOf(msg), "m") > 4)
    }

    // ═══════════════════════════════════════════════════════════
    //  费用估算
    // ═══════════════════════════════════════════════════════════

    private val pricedProfile = ProviderProfile(
        id = "test-priced",
        displayName = "测试用（带价格）",
        defaultBaseUrl = "http://127.0.0.1:1/v1",
        authScheme = AuthScheme.None,
        defaultModels = listOf(
            ModelInfo(
                id = "m1",
                displayName = "m1",
                capabilities = setOf(Capability.STREAM),
                contextWindow = 8_192,
                inputPricePerMillion = 10.0,
                outputPricePerMillion = 30.0,
            )
        ),
    )

    private val pricedProvider = OpenAiCompatProvider(pricedProfile)

    @Test
    fun `按输入输出价格分别计费`() {
        val cost = pricedProvider.estimateCost(
            TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000),
            "m1",
        )

        assertEquals(40.0, cost.usd, 1e-9)
    }

    @Test
    fun `缓存命中的输入按折扣计费`() {
        // 100 万输入 token 全部命中缓存 → 只按 10% 计费
        val cost = pricedProvider.estimateCost(
            TokenUsage(inputTokens = 1_000_000, outputTokens = 0, cachedInputTokens = 1_000_000),
            "m1",
        )

        assertEquals(1.0, cost.usd, 1e-9)
    }

    @Test
    fun `缓存 token 不会超过输入 token 导致负费用`() {
        // 防御性用例：厂商若给出 cached > prompt（协议异常），不能算出负数
        val cost = pricedProvider.estimateCost(
            TokenUsage(inputTokens = 100, outputTokens = 0, cachedInputTokens = 500),
            "m1",
        )

        assertTrue("费用不能为负：${cost.usd}", cost.usd >= 0.0)
    }

    @Test
    fun `未知模型估算为 0 而不是抛异常`() {
        // 用户填了厂商表里没有的模型名时，费用只能记 0 —— 宁可漏算不能崩
        val cost = pricedProvider.estimateCost(TokenUsage(1000, 1000), "不存在的模型")

        assertEquals(0.0, cost.usd, 1e-9)
    }

    @Test
    fun `零用量估算为 0`() {
        assertEquals(0.0, pricedProvider.estimateCost(TokenUsage(0, 0), "m1").usd, 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  厂商配置表
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `内置厂商的 id 不重复`() {
        val ids = ProviderProfiles.all.map { it.id }

        assertEquals("存在重复的 provider id", ids.size, ids.toSet().size)
    }

    @Test
    fun `除 Azure 外所有厂商都必须有默认 BaseUrl`() {
        // Azure 的资源地址是每个用户独有的，只能由用户填写
        val missing = ProviderProfiles.all
            .filter { it.id != "azure-openai" }
            .filter { it.defaultBaseUrl.isBlank() }
            .map { it.id }

        assertEquals("这些厂商缺少默认 BaseUrl：$missing", emptyList<String>(), missing)
    }

    @Test
    fun `所有厂商都声明了流式能力`() {
        // 非流式的 Provider 在这个产品里没有意义（agent 需要边想边做）
        val noStream = ProviderProfiles.all
            .filterNot { Capability.STREAM in it.capabilities }
            .map { it.id }

        assertEquals("这些厂商没有声明 STREAM：$noStream", emptyList<String>(), noStream)
    }

    @Test
    fun `byId 能查到内置厂商，未知 id 返回 null`() {
        assertNotNull(ProviderProfiles.byId("openrouter"))
        assertEquals("DeepSeek", ProviderProfiles.byId("deepseek")?.displayName)
        assertNull(ProviderProfiles.byId("不存在的厂商"))
    }

    @Test
    fun `本地厂商被标记为 LOCAL 能力`() {
        // 这是"纯本地模式"（数据不出设备）的判定依据
        for (id in listOf("local-ollama", "local-lmstudio")) {
            val p = ProviderProfiles.byId(id)
            assertNotNull(p)
            assertTrue("$id 应声明 LOCAL", Capability.LOCAL in p!!.capabilities)
        }
    }

    @Test
    fun `本地厂商不发送 stream_options`() {
        // Ollama 与 LM Studio 不认 stream_options，发了会直接报错
        assertFalse(ProviderProfiles.Ollama.supportsStreamUsage)
        assertFalse(ProviderProfiles.LmStudio.supportsStreamUsage)
    }

    @Test
    fun `Azure 使用 api-key 头而不是 Bearer`() {
        val azure = ProviderProfiles.AzureOpenAi

        assertTrue(azure.authScheme is AuthScheme.ApiKeyHeader)
        assertEquals("api-key", azure.apiKeyHeaderName)
        assertNull("Azure 没有统一的 /models 端点", azure.modelsPath)
    }

    @Test
    fun `Provider 的对外属性与配置表一致`() {
        assertEquals(ProviderProfiles.OpenRouter.id, provider.id)
        assertEquals(ProviderProfiles.OpenRouter.displayName, provider.displayName)
        assertEquals(ProviderProfiles.OpenRouter.defaultBaseUrl, provider.defaultBaseUrl)
        assertTrue(provider.capabilities().contains(Capability.VISION))
    }

    // ═══════════════════════════════════════════════════════════
    //  凭据对象的零泄漏保证
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `clear 之后明文字节全部归零`() {
        val cred = ProviderCredential("sk-abcdef1234567890".encodeToByteArray())
        val before = cred.keyLength
        assertTrue(before > 0)

        cred.clear()

        assertEquals("长度不应改变", before, cred.keyLength)
        assertTrue("字节应被清零", cred.apiKey.all { it == 0.toByte() })
    }

    @Test
    fun `clear 之后再也取不出原明文`() {
        // 注意：清零后 apiKeyForRequest() 返回的是一串 NUL 字符（\u0000），
        // 不是空串 —— 字节被置零了，但长度没变。这个区别很重要：
        // 如果哪个调用方在 clear 之后还去构造请求头，服务端会收到一串
        // NUL 字节并报鉴权失败，而不是"看起来没带 Key"。
        // 属于 fail-closed，可以接受；但它意味着 clear 之后不该再发请求。
        val secret = "sk-abcdef1234567890"
        val cred = ProviderCredential(secret.encodeToByteArray())

        cred.clear()

        assertFalse("清空后仍能取出明文", cred.apiKeyForRequest().contains("sk-abcdef"))
        assertEquals("sk-abcdef1234567890".length, cred.apiKeyForRequest().length)
        assertTrue(cred.apiKeyForRequest().all { it == '\u0000' })
    }

    @Test
    fun `toString 不泄漏 Key 内容`() {
        val cred = ProviderCredential(
            "sk-super-secret-value".encodeToByteArray(),
            baseUrlOverride = "https://example.com",
        )

        val s = cred.toString()

        assertFalse("toString 泄漏了 Key：$s", s.contains("sk-super-secret-value"))
        assertFalse(s.contains("super"))
        assertTrue(s.contains("***"))
    }

    @Test
    fun `keyLength 不暴露内容只暴露长度`() {
        val cred = ProviderCredential("sk-1234567890".encodeToByteArray())

        assertEquals(13, cred.keyLength)
    }

    @Test
    fun `apiKeyForRequest 返回的是明文副本，清空原对象后副本不受影响`() {
        // 这条用例记录一个**已知的、无法避免的**安全特性：
        // ByteArray → String 会复制数据，String 进入字符串池后无法擦除。
        // 因此 apiKeyForRequest() 必须"只在构造鉴权头的那一行调用"。
        val cred = ProviderCredential("sk-test".encodeToByteArray())
        val plain = cred.apiKeyForRequest()

        cred.clear()

        assertEquals("sk-test", plain)   // 副本依然存在 —— 这就是必须压缩暴露窗口的原因
    }
}
