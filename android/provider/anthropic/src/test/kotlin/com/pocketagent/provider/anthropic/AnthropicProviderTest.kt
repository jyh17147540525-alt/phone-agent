package com.pocketagent.provider.anthropic

import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`AnthropicProvider` 的公开行为**（不发网络请求的那部分）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 重点是 `estimateCost` 的"未知"语义
 * ═══════════════════════════════════════════════════════════════
 *
 * 改之前它对未知模型返回 `Cost(0.0)` —— 那是项目自己在
 * `ModelConfigMapping` 里警告过的「未知当免费」陷阱。
 *
 * 一旦有人把这个方法接进预算熔断，未知价格的模型就会**永远不被拦**。
 * ⇒ 改成返回 `null`（= 算不出），并在这里钉住。
 */
class AnthropicProviderTest {

    private val provider = AnthropicProvider()

    // ═══════════════════════════════════════════════════════════
    //  身份与能力
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `身份与鉴权方式`() {
        assertEquals("anthropic", provider.id)
        // ⚠️ Anthropic 用 `x-api-key` 而不是 Bearer ——
        //    这是它与 OpenAI 兼容 provider 的第一处结构性差异
        assertEquals(AuthScheme.ApiKeyHeader, provider.authScheme)
        assertEquals("https://api.anthropic.com/v1", provider.defaultBaseUrl)
    }

    @Test
    fun `声明的能力包含工具与视觉`() {
        val caps = provider.capabilities()
        assertTrue(Capability.STREAM in caps)
        assertTrue(Capability.TOOL_CALL in caps)
        assertTrue(Capability.VISION in caps)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ estimateCost：未知必须返回 null
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `未知模型的价格是 null 而不是零`() {
        // ══════════════════════════════════════════════════════
        //  ★ 本文件最重要的一条
        // ══════════════════════════════════════════════════════
        // 返回 0 会让预算熔断认为"不花钱"，于是永远不拦。
        val c = provider.estimateCost(
            TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000),
            "some-unknown-model",
        )
        assertNull("未知价格必须返回 null，不能是 Cost(0.0)：$c", c)
    }

    @Test
    fun `空模型名的价格也是 null`() {
        assertNull(provider.estimateCost(TokenUsage(1, 1), ""))
    }

    @Test
    fun `已知模型能算出费用`() {
        // 100 万输入 + 100 万输出，haiku 档是 $0.8 / $4.0
        val c = provider.estimateCost(
            TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000),
            "claude-haiku-5-5",
        )
        assertNotNull(c)
        assertEquals(4.8, c!!.usd, 0.01)
    }

    @Test
    fun `档位越贵单价越高`() {
        val u = TokenUsage(inputTokens = 1_000_000, outputTokens = 1_000_000)
        val haiku = provider.estimateCost(u, "claude-haiku-5-5")!!.usd
        val sonnet = provider.estimateCost(u, "claude-sonnet-4")!!.usd
        val opus = provider.estimateCost(u, "claude-opus-4")!!.usd
        assertTrue("sonnet 应当比 haiku 贵：$haiku vs $sonnet", sonnet > haiku)
        assertTrue("opus 应当比 sonnet 贵：$sonnet vs $opus", opus > sonnet)
    }

    @Test
    fun `模型名大小写不敏感`() {
        val u = TokenUsage(1_000_000, 0)
        assertEquals(
            provider.estimateCost(u, "claude-sonnet-4")!!.usd,
            provider.estimateCost(u, "CLAUDE-SONNET-4")!!.usd,
            1e-9,
        )
    }

    @Test
    fun `零用量是零费用`() {
        assertEquals(0.0, provider.estimateCost(TokenUsage(0, 0), "claude-sonnet-4")!!.usd, 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  countTokens 的启发式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `countTokens 至少返回 1`() {
        // ⚠️ 返回 0 会让"按 token 计的预算"永远不触发
        assertTrue(provider.countTokens(emptyList(), "claude-sonnet-4") >= 1)
    }

    @Test
    fun `文本越长估算越大`() {
        val short = provider.countTokens(listOf(ChatMessage.user("你好")), "m")
        val long = provider.countTokens(
            listOf(ChatMessage.user("你好".repeat(100))),
            "m",
        )
        assertTrue("长文本的估算应当更大：$short vs $long", long > short)
    }

    @Test
    fun `图片按固定成本估算`() {
        // ⚠️ 图片的 token 成本与字节数不成正比（取决于分辨率档位），
        //    所以这里用固定值。0 会让"只发图"的请求看起来不花钱。
        val textOnly = provider.countTokens(listOf(ChatMessage.user("x")), "m")
        val withImage = provider.countTokens(
            listOf(
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = listOf(
                        ContentPart.Text("x"),
                        ContentPart.Image(bytes = ByteArray(4), mimeType = "image/png"),
                    ),
                ),
            ),
            "m",
        )
        assertTrue("带图应当明显更贵：$textOnly vs $withImage", withImage > textOnly + 1000)
    }
}
