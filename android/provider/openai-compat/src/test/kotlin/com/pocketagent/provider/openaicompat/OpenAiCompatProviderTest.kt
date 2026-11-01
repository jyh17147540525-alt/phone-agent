package com.pocketagent.provider.openaicompat

import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.ContentPart
import com.pocketagent.provider.api.ModelInfo
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.TokenUsage
import com.pocketagent.provider.api.ToolSpec
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

    // ═══════════════════════════════════════════════════════════
    //  ★★ 2026-11-01：OpenCode Zen —— 只能列 chat/completions 兼容的模型
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `OpenCode Zen 已在内置清单里`() {
        val p = ProviderProfiles.byId("opencode")
        assertNotNull("用户反馈缺少 opencode", p)
        assertEquals("https://opencode.ai/zen/v1", p!!.defaultBaseUrl)
        // ★ 官方文档：OpenAI 兼容端用 Authorization: Bearer
        assertTrue("应当用 Bearer 鉴权", p.authScheme is AuthScheme.Bearer)
    }

    @Test
    fun `OpenCode 不得列出走 Anthropic 或 Responses 协议的模型`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条测试防的是一个**会让用户必然踩到 400** 的误加
        // ══════════════════════════════════════════════════════
        // OpenCode Zen 的模型走三种协议（官方文档明确列出）：
        //   · claude-*  → `/zen/v1/messages`（Anthropic）
        //   · gpt-5     → `/zen/v1/responses`（OpenAI Responses）
        //   · qwen3-coder / grok-code / kimi-k2 → `/chat/completions` ✅
        //
        // ⚠️ 本类是 OpenAI **chat/completions** 实现。
        //    把前两类加进来，用户选中后请求必然 400 ——
        //    而错误信息对用户毫无意义（他只会觉得"app 坏了"）。
        //
        // ⇒ 这条测试把"只能列哪些"钉住。将来要支持另两种协议，
        //   需要的是新的 Provider 实现类，不是往这张表加名字。
        val p = ProviderProfiles.byId("opencode")!!
        val forbidden = p.defaultModels.filter { m ->
            val id = m.id.lowercase()
            id.startsWith("claude") || id.startsWith("gpt-5")
        }
        assertEquals(
            "这些模型走非 chat/completions 协议，不能列在 OpenCode 下：$forbidden",
            emptyList<ModelInfo>(),
            forbidden,
        )
    }

    @Test
    fun `OpenCode 的默认模型都声明了工具调用能力`() {
        // ★ 本产品是 agent —— 模型不能调工具就没有意义。
        //   （`grok-code` / `qwen3-coder` / `kimi-k2` 都是 agent 模型，确实支持）
        val p = ProviderProfiles.byId("opencode")!!
        assertTrue("至少要有一个默认模型", p.defaultModels.isNotEmpty())
        val noTool = p.defaultModels
            .filter { Capability.TOOL_CALL !in it.capabilities }
            .map { it.id }
        assertEquals("这些模型缺 TOOL_CALL：$noTool", emptyList<String>(), noTool)
    }

    @Test
    fun `OpenCode 不谎报视觉能力`() {
        // ⚠️ 它目前列出的三个都是**代码模型**，没有视觉。
        //    谎报 VISION 会让 `screen_shot` 把图片发过去然后被拒 ——
        //    而用户看到的是"截图功能坏了"。
        val p = ProviderProfiles.byId("opencode")!!
        assertFalse(
            "没有视觉能力就不该声明 VISION",
            Capability.VISION in p.capabilities,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★ OpenCode Go（包月档）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `OpenCode Go 已在内置清单里且端点是 go 那档`() {
        val p = ProviderProfiles.byId("opencode-go")
        assertNotNull("用户追加要求：还有一个 opencode go", p)
        // ⚠️ 与 Zen 的区别就在 `/go` 这一段，写错会连到另一档
        assertEquals("https://opencode.ai/zen/go/v1", p!!.defaultBaseUrl)
        assertTrue(p.authScheme is AuthScheme.Bearer)
    }

    @Test
    fun `OpenCode Go 不得列出 Qwen 与 MiniMax`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条防的是最容易犯的一个错
        // ══════════════════════════════════════════════════════
        // **Zen** 上 `qwen3-coder` 走 `/chat/completions` ✅
        // 但 **Go** 上所有 Qwen 都走 `/messages`（Anthropic）❌
        //
        // ⚠️ 照着 Zen 的习惯往 Go 里加 qwen，用户选中后**必然 400**。
        //    同理 `minimax-*` 也全是 Anthropic。
        //
        // ⇒ 把"不能加哪些"钉死。
        val p = ProviderProfiles.byId("opencode-go")!!
        val forbidden = p.defaultModels
            .map { it.id.lowercase() }
            .filter { it.startsWith("qwen") || it.startsWith("minimax") }
        assertEquals(
            "Go 上这些模型走 Anthropic /messages，不能列进来：$forbidden",
            emptyList<String>(),
            forbidden,
        )
    }

    @Test
    fun `OpenCode Go 的默认模型都声明了工具调用能力`() {
        val p = ProviderProfiles.byId("opencode-go")!!
        assertTrue("至少要有一个默认模型", p.defaultModels.isNotEmpty())
        val noTool = p.defaultModels
            .filter { Capability.TOOL_CALL !in it.capabilities }
            .map { it.id }
        assertEquals("这些模型缺 TOOL_CALL：$noTool", emptyList<String>(), noTool)
    }

    @Test
    fun `OpenCode Go 的价格填 0 是包月制而不是未知`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条记录了"0 的两种含义"的区别
        // ══════════════════════════════════════════════════════
        // `ModelConfigMapping` 警告过"把不知道多少钱当成免费"会让
        // 预算熔断失效。而 Go 的 0 是**真的 0**（包月已付过）。
        //
        // ⚠️ 但 Go 的真实约束是**配额**（滚动 5h $12 / 周 $30 / 月 $60），
        //    那个只能从 `/zen/go/v1/usage` 读，本 app 看不到。
        // ⇒ AgentBudget 的另外三维（轮次/时长/能耗）仍然生效。
        val p = ProviderProfiles.byId("opencode-go")!!
        val priced = p.defaultModels.filter {
            // ⚠️ 价格字段是 `Double?` —— null 表示"未知"，
            //    对包月制来说两者都不该出现非零值。
            (it.inputPricePerMillion ?: 0.0) > 0.0 ||
                (it.outputPricePerMillion ?: 0.0) > 0.0
        }
        assertEquals(
            "包月制下边际成本是 0，不该有非零价格：$priced",
            emptyList<ModelInfo>(),
            priced,
        )
    }

    @Test
    fun `两档 OpenCode 的 id 不撞车`() {
        // ⚠️ 同一把 Key 两档，但 id 必须区分 ——
        //    否则 `byId` 只能查到其中一个，而用户会配错端点。
        val zen = ProviderProfiles.byId("opencode")
        val go = ProviderProfiles.byId("opencode-go")
        assertNotNull(zen)
        assertNotNull(go)
        // ⚠️ 用 assertFalse 而不是 assertNotEquals（后者没导入，
        //    而且这里本来要断言的就是"两个 BaseUrl 不同"）
        assertFalse(
            "两档的 BaseUrl 必须不同，否则连到同一档",
            zen!!.defaultBaseUrl == go!!.defaultBaseUrl,
        )
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

    // ═══════════════════════════════════════════════════════════
    //  ★ 请求体编码 —— 走**生产真正调用的那个函数**
    // ═══════════════════════════════════════════════════════════

    /**
     * 回归测试 —— 2026-10-19 真机抓到的 400。
     *
     * ═══════════════════════════════════════════════════════════
     *  这个 bug 为什么离线全绿、上机就炸
     * ═══════════════════════════════════════════════════════════
     *
     * `buildChatPayload` 曾经用**自己内联的一份** `Json`（`encodeDefaults = false`），
     * 而带修复的那份 `PROVIDER_JSON`（`encodeDefaults = true`）**只有测试在用**。
     * 于是 `tools[].type: "function"` 被静默删掉，模型回：
     *
     *     tools[0].type:type cannot be empty.
     *
     * 关键教训：`OpenAiCompatDtosTest` 测的是**它自己引用的那个 Json 实例**，
     * 不是生产路径真正用的那个。这类"测了副本"的 bug 有个共同特征 ——
     * 两边各自演化，而且**永远不会有一方失败**。
     *
     * ⇒ 所以这条用例**不碰任何 DTO 的 Json**，只调 `buildChatPayload`，
     *   然后检查它真正吐出来的字符串。它比上面那些更接近真机。
     */
    @Test
    fun `生产路径的工具定义必须带上 type 字段`() {
        val out = provider.buildChatPayload(
            ChatRequest(
                model = "m",
                messages = listOf(ChatMessage.user("打开设置")),
                tools = listOf(
                    ToolSpec(
                        name = "android_tap",
                        description = "点一下",
                        parametersJsonSchema = """{"type":"object"}""",
                    )
                ),
            ),
            stream = false,
        )

        assertTrue("tools[].type 丢了，模型会回 400：$out", out.contains("\"type\":\"function\""))
        assertTrue(out.contains("\"name\":\"android_tap\""))
        assertTrue(out.contains("\"parameters\""))
    }

    /**
     * 同一条链路的另一半：**没有工具时不该出现 `tools` 字段**。
     *
     * 理由：部分厂商对空数组直接报错；而且空 `tools` 会让模型以为
     * "有工具但都不可用"，反而更容易瞎猜工具名。
     */
    @Test
    fun `没有工具时请求体里不该出现 tools 字段`() {
        val out = provider.buildChatPayload(
            ChatRequest(model = "m", messages = listOf(ChatMessage.user("你好"))),
            stream = false,
        )

        assertFalse("空工具列表不该出现在请求体里：$out", out.contains("\"tools\""))
    }

    /**
     * 工具的历史调用也必须带上 `type`。
     *
     * 这一条与上一条**同源但不同字段**：模型多轮对话时，
     * 我们回传的 assistant 消息里含 `tool_calls`，它同样依赖
     * `RequestToolCall.type` 的默认值被编码出来。
     * 漏了它的表现不是 400，而是**第二轮开始模型不认得自己调过工具**。
     */
    @Test
    fun `生产路径的历史工具调用必须带上 type 字段`() {
        val out = provider.buildChatPayload(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(
                        role = ChatMessage.Role.ASSISTANT,
                        content = listOf(ContentPart.ToolCall("call_1", "android_tap", """{"x":1}""")),
                    ),
                    ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        content = listOf(ContentPart.ToolResult("call_1", "已点击")),
                        toolCallId = "call_1",
                    ),
                ),
            ),
            stream = false,
        )

        assertTrue("tool_calls[].type 丢了：$out", out.contains("\"type\":\"function\""))
        assertTrue("tool_call_id 丢了，模型无法把结果对上是哪次调用：$out", out.contains("\"tool_call_id\":\"call_1\""))
    }

    /**
     * 回归测试 —— 2026-10-19 真机抓出的**第二个** bug：工具结果被吞成 `[]`。
     *
     * ═══════════════════════════════════════════════════════════
     *  这个 bug 的症状与"无障碍权限没开"一模一样
     * ═══════════════════════════════════════════════════════════
     *
     * 日志显示 `screen_read` **成功返回 1119 字 / 36 个节点**，
     * 但模型对用户说「读屏返回了空结果」。
     *
     * 根因：`role=tool` 的消息里装的是 `ContentPart.ToolResult`，
     * 而 `toRequestMessage()` 的 `when` 把它落进 `else -> Unit`，
     * 于是 content 变成空数组：
     *
     *     {"role":"tool","content":[],"tool_call_id":"call_1"}
     *
     * 模型拿到的是空字符串 ⇒ 它只能如实说"看不到"。
     * **用户会去查无障碍权限，而权限根本没问题** —— 这是本项目
     * 头号 bug 形态「安静地少做一件事」里最难查的一种：
     * 错误信息把排查方向指向了完全无关的地方。
     *
     * ⇒ 协议要求 `role=tool` 的 content 是**字符串**，不是数组。
     */
    @Test
    fun `工具结果必须作为字符串内容回传给模型`() {
        val out = provider.buildChatPayload(
            ChatRequest(
                model = "m",
                messages = listOf(
                    ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        content = listOf(
                            ContentPart.ToolResult("call_1", "【屏幕快照】节点 36 · 来源=无障碍树"),
                        ),
                        toolCallId = "call_1",
                    ),
                ),
            ),
            stream = false,
        )

        assertTrue("工具结果丢了，模型会以为读屏返回空：$out", out.contains("屏幕快照"))
        assertTrue("tool_call_id 丢了：$out", out.contains("\"tool_call_id\":\"call_1\""))
        assertFalse("role=tool 的 content 不能是空数组：$out", out.contains("\"content\":[]"))
    }
}
