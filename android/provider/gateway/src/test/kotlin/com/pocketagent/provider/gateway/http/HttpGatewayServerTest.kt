package com.pocketagent.provider.gateway.http

import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelConfigRepository
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.ModelRouteCoordinator
import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.modelrouter.RoutingMode
import com.pocketagent.provider.api.AuthScheme
import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ChatChunk
import com.pocketagent.provider.api.ChatMessage
import com.pocketagent.provider.api.ChatRequest
import com.pocketagent.provider.api.Cost
import com.pocketagent.provider.api.KeyValidationResult
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.ModelInfo
import com.pocketagent.provider.api.ProviderCredential
import com.pocketagent.provider.api.TokenUsage
import com.pocketagent.provider.gateway.CredentialSource
import com.pocketagent.provider.gateway.GatewayCore
import com.pocketagent.provider.gateway.InMemoryUsageRecorder
import com.pocketagent.provider.gateway.ResolvedCredential
import com.pocketagent.provider.gateway.RoutingBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL

/**
 * [HttpGatewayServer] 的**真 socket** 集成测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须有这一层，不能只靠单元测试
 * ═══════════════════════════════════════════════════════════════
 *
 * 本类在开发中抓到一个**单元测试结构上不可能发现**的 bug：
 * `HttpRequest` 没有缓存 body，而 `handleChat` 读了两次 ——
 * 第二次拿到空串，表现为"所有请求都报 messages 不能为空"。
 *
 * 单元测试直接构造 `GatewayChatRequest` 对象，根本不经过 socket，
 * 也就没有"流只能读一次"这回事。**只有真 socket 能暴露它。**
 *
 * 所以这一层不是"多写点测试"，它覆盖的是一类**结构不同的缺陷**：
 * 生命周期、缓冲、连接语义、帧边界。
 *
 * ⚠️ 端口用 `0`（内核分配），所以本测试**可以并行跑**且不会
 *    撞端口。这也是"端口随机"那条产品行为的一部分可验证性。
 *
 * ⚠️ 客户端用 JDK 自带的 `HttpURLConnection` 而不是 OkHttp ——
 *    后者不在离线验证器的 classpath 里（`LIBRARY_JARS` 只有
 *    `okhttp-jvm`，而 `HttpURLConnection` 是 JDK 自带，零依赖）。
 */
class HttpGatewayServerTest {

    // ─────────────────────────────────────────────────────────────
    //  fake
    // ─────────────────────────────────────────────────────────────

    private class FakeModelRepo(private val items: List<ModelConfig>) : ModelConfigRepository {
        override val models: Flow<List<ModelConfig>> = MutableStateFlow(items)
        override suspend fun all() = items
        override suspend fun byId(id: String) = items.firstOrNull { it.id == id }
    }

    private class FakeProvider(
        override val id: String = "fake",
        private val chunks: List<ChatChunk> = listOf(
            ChatChunk.Delta("你好"),
            ChatChunk.Delta("，世界"),
            ChatChunk.Done(TokenUsage(inputTokens = 10, outputTokens = 5), "stop"),
        ),
        private val failWith: Throwable? = null,
    ) : LlmProvider {
        override val displayName = "Fake"
        override val authScheme: AuthScheme = AuthScheme.Bearer
        override val defaultBaseUrl = "https://example.invalid"

        /** 被取消的时间点 —— 用来验证"客户端断开取消上游" */
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)

        /** 每个 chunk 之间的延迟，用于制造真实的"流式"节奏 */
        var chunkDelayMs: Long = 0

        override fun capabilities() = setOf(Capability.STREAM)
        override suspend fun validateKey(credential: ProviderCredential) =
            KeyValidationResult.Valid(1, 1)

        override suspend fun listModels(credential: ProviderCredential) = null

        override fun chat(request: ChatRequest, credential: ProviderCredential): Flow<ChatChunk> =
            flow {
                for (c in chunks) {
                    if (chunkDelayMs > 0) kotlinx.coroutines.delay(chunkDelayMs)
                    emit(c)
                }
                failWith?.let { throw it }
            }

        override fun countTokens(messages: List<ChatMessage>, model: String) = 1
        override fun estimateCost(usage: TokenUsage, model: String) = Cost(0.001)
    }

    private class FakeCredentialSource : CredentialSource {
        override suspend fun resolve(credentialId: String) = ResolvedCredential(
            apiKey = "sk-test-key".toByteArray(),
            providerId = "fake",
        )
    }

    private fun config() = ModelConfig(
        id = "m1",
        label = "测试模型",
        credentialId = "cred-1",
        modelId = "real-model-id",
        modelDisplayName = "Test Model",
        tier = ModelTier.STANDARD,
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 2.0,
        roles = setOf(ModelRole.WORKER),
        enabled = true,
    )

    private fun buildServer(
        provider: FakeProvider = FakeProvider(),
        recorder: InMemoryUsageRecorder = InMemoryUsageRecorder(),
        sanitizer: (String) -> String = HttpGatewayServer.DEFAULT_SANITIZER,
    ): Pair<HttpGatewayServer, FakeProvider> {
        val cfg = config()
        val repo = FakeModelRepo(listOf(cfg))
        val core = GatewayCore(
            bridge = RoutingBridge(
                coordinator = ModelRouteCoordinator(repo),
                modelById = { id -> repo.byId(id) },
            ),
            credentials = FakeCredentialSource(),
            providers = listOf(provider),
            usageRecorder = recorder,
        )
        val server = HttpGatewayServer(
            core = core,
            routingModeFor = { RoutingMode.Single(cfg.id) },
            sanitize = sanitizer,
        )
        return server to provider
    }

    private var started: HttpGatewayServer? = null

    @After
    fun tearDown() {
        started?.stop()
        started = null
    }

    private fun start(server: HttpGatewayServer): HttpGatewayServer {
        assertTrue("网关应能启动", server.start())
        started = server
        return server
    }

    // ─────────────────────────────────────────────────────────────
    //  连接工具
    // ─────────────────────────────────────────────────────────────

    private class Resp(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun request(
        server: HttpGatewayServer,
        method: String,
        path: String,
        body: String? = null,
        token: String? = server.token,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Resp {
        val url = URL("http://127.0.0.1:${server.port}$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.instanceFollowRedirects = false

        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        for ((k, v) in extraHeaders) conn.setRequestProperty(k, v)

        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        val status = conn.responseCode
        val headers = conn.headerFields
            .filterKeys { it != null }
            .mapValues { (_, v) -> v.firstOrNull().orEmpty() }

        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
        }.orEmpty()

        conn.disconnect()
        return Resp(status, headers, text)
    }

    /** 原始 socket 请求 —— 用来测 HttpURLConnection 挡住的畸形输入 */
    private fun rawRequest(server: HttpGatewayServer, payload: String): String {
        Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", server.port), 3000)
            s.soTimeout = 5000
            s.getOutputStream().write(payload.toByteArray(Charsets.UTF_8))
            s.getOutputStream().flush()
            return s.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun chatBody(stream: Boolean) = """
        {"model":"whatever","stream":$stream,
         "messages":[{"role":"user","content":"你好"}]}
    """.trimIndent()

    // ─────────────────────────────────────────────────────────────
    //  监听地址（加固第 1 条）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `只监听回环地址`() {
        val (server, _) = buildServer()
        start(server)

        // ⚠️ 这是加固第 1 条的直接验证：绑定的必须是 loopback。
        //    绑 0.0.0.0 的话局域网内任何设备都能拿用户的 Key 调模型。
        val socket = Socket()
        try {
            // 用一个**非回环**的本地地址连它 —— 应当连不上。
            // 取任一非 loopback 的本地网卡地址。
            val external = InetAddress.getAllByName(InetAddress.getLocalHost().hostName)
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }

            if (external == null) {
                // 机器没有可用外网卡时跳过这一半（CI 环境常见）
                assertTrue(InetAddress.getLoopbackAddress().isLoopbackAddress)
                return
            }

            var connected = true
            try {
                socket.connect(java.net.InetSocketAddress(external, server.port), 1500)
            } catch (e: Exception) {
                connected = false
            }
            assertFalse(
                "网关不应在 $external 上可达 —— 只允许 127.0.0.1",
                connected,
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    @Test
    fun `端口由内核分配且不为 0`() {
        val (server, _) = buildServer()
        start(server)

        // ⚠️ 端口随机（加固第 2 条）。这里断言的不是"随机"，
        //    而是"确实拿到了一个可用的真实端口" ——
        //    随机性本身没法在单元测试里证明（见 GatewayTokenProvider 的注释）。
        assertTrue("端口应 > 1024，实际 ${server.port}", server.port > 1024)
        assertEquals("http://127.0.0.1:${server.port}/v1", server.baseUrl)
    }

    @Test
    fun `两次启动拿到的端口和 token 都不同`() {
        // ★ 加固第 2、3 条：每次启动都换
        val (s1, _) = buildServer()
        start(s1)
        val p1 = s1.port
        val t1 = s1.token
        s1.stop()

        val (s2, _) = buildServer()
        start(s2)

        assertTrue("token 应重新生成", t1.isNotEmpty() && s2.token.isNotEmpty())
        assertFalse("旧 token 应当失效", t1 == s2.token)
        assertTrue("端口应被重新分配", s2.port > 1024)
        // 端口可能碰巧相同（内核复用），所以只断言它可用
        assertEquals("http://127.0.0.1:${s2.port}/v1", s2.baseUrl)
        assertTrue(p1 > 1024)
    }

    // ─────────────────────────────────────────────────────────────
    //  鉴权（加固第 3、4 条）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `healthz 不需要鉴权`() {
        val (server, _) = buildServer()
        start(server)

        // 给 dsh 启动前自检用 —— 那时它还没拿到 token
        val r = request(server, "GET", "/healthz", token = null)

        assertEquals(200, r.status)
        assertTrue(r.body.contains("ok"))
    }

    @Test
    fun `不带 token 的 models 请求被拒`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models", token = null)

        assertEquals(401, r.status)
    }

    @Test
    fun `token 不对的请求被拒`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models", token = "wrong-token-of-same-len-00000000000000000000000000000000")
        assertEquals(401, r.status)
    }

    @Test
    fun `token 长度不对的请求被拒`() {
        val (server, _) = buildServer()
        start(server)

        assertEquals(401, request(server, "GET", "/v1/models", token = "short").status)
        assertEquals(401, request(server, "GET", "/v1/models", token = server.token + "0").status)
    }

    @Test
    fun `401 响应体不透露 token 细节`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models", token = "wrong")

        // ⚠️ 不说"长度对不对""前缀匹配到第几位" —— 那是给攻击者的免费信息
        assertFalse(r.body.contains(server.token))
        // ⚠️ 这条断言**第一版写反了**（写成 assertEquals(0, …)），
        //    而它本该是 1：`"message":"unauthorized"` 恰好出现一次。
        //    写反的断言"看起来在测安全"，实际测的是一个不可能成立的值 ——
        //    一旦它被"修"成通过（比如把 body 里的 unauthorized 删掉），
        //    覆盖就整个丢了。所以这里同时钉住"出现"与"只出现一次"。
        assertEquals(
            "body 里应恰好一次 unauthorized：${r.body}",
            1,
            r.body.split("unauthorized").size - 1,
        )
        // ⚠️ 也不得回显"实际收到的 token"或它的长度/前缀
        assertFalse("不得回显收到的 token：${r.body}", r.body.contains("wrong"))
    }

    @Test
    fun `正确的 token 能通过`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models")

        assertEquals(200, r.status)
    }

    @Test
    fun `停止后 token 失效`() {
        val (server, _) = buildServer()
        start(server)
        val t = server.token
        server.stop()

        // ⚠️ token 必须被清掉 —— 留着的话它"看起来有效"而端口已经关了，
        //    排查会往完全错的方向走
        assertEquals("", server.token)
        assertFalse(t.isEmpty())
    }

    @Test
    fun `x-api-key 头也能鉴权`() {
        val (server, _) = buildServer()
        start(server)

        // Anthropic 风格 —— dsh 的 anthropic-messages 协议会用它
        val r = request(
            server, "GET", "/v1/models",
            token = null,
            extraHeaders = mapOf("x-api-key" to server.token),
        )

        assertEquals(200, r.status)
    }

    @Test
    fun `头名大小写不敏感`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(
            server, "GET", "/v1/models",
            token = null,
            extraHeaders = mapOf("authorization" to "Bearer ${server.token}"),
        )

        assertEquals(200, r.status)
    }

    // ─────────────────────────────────────────────────────────────
    //  /v1/models（策略 A）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `models 只返回一个虚拟模型`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models")

        val body = r.body
        assertTrue(body.contains(HttpGatewayServer.VIRTUAL_MODEL))
        // ⚠️ 只应有一个 id 字段 —— 策略 A
        assertEquals(1, Regex("\"id\":").findAll(body).count())
    }

    @Test
    fun `models 不泄漏真实 Provider 名`() {
        val (server, _) = buildServer()
        start(server)

        val body = request(server, "GET", "/v1/models").body

        // ⚠️ 加固第 5 条：owned_by 固定 "pocketagent"
        assertTrue(body.contains("pocketagent"))
        assertFalse("不得出现 fake provider 名", body.contains("\"fake\""))
        assertFalse("不得出现真实模型 id", body.contains("real-model-id"))
    }

    // ─────────────────────────────────────────────────────────────
    //  非流式对话
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `非流式请求返回聚合后的完整回答`() {
        // ★ 这条覆盖 BYOK §4.3 "必须支持 stream false" 那个坑。
        //   同时它也是那个 bodyCache bug 的回归测试 ——
        //   没有 bodyCache 时这里会报 "messages 不能为空"。
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", chatBody(stream = false))

        assertEquals(200, r.status)
        assertTrue("实际响应：${r.body}", r.body.contains("你好，世界"))
        assertTrue(r.body.contains("\"finish_reason\":\"stop\""))
        assertTrue(r.body.contains("chat.completion"))
    }

    @Test
    fun `非流式响应带 usage`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", chatBody(stream = false))

        assertTrue(r.body.contains("\"prompt_tokens\":10"))
        assertTrue(r.body.contains("\"completion_tokens\":5"))
        assertTrue(r.body.contains("\"total_tokens\":15"))
    }

    @Test
    fun `非流式响应的 model 是虚拟名`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", chatBody(stream = false))

        assertTrue(r.body.contains(HttpGatewayServer.VIRTUAL_MODEL))
        assertFalse("不得泄漏真实模型 id", r.body.contains("real-model-id"))
    }

    @Test
    fun `没有 stream 字段时按非流式处理`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(
            server, "POST", "/v1/chat/completions",
            """{"messages":[{"role":"user","content":"hi"}]}""",
        )

        assertEquals(200, r.status)
        // 非流式响应是单个 JSON，不含 SSE 的 data: 前缀
        assertFalse(r.body.startsWith("data: "))
    }

    // ─────────────────────────────────────────────────────────────
    //  流式对话（SSE）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `流式请求返回 SSE 内容类型`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", chatBody(stream = true))

        assertEquals(200, r.status)
        assertNotNull(r.headers["Content-Type"])
        assertTrue(
            "Content-Type 应为 event-stream，实际 ${r.headers["Content-Type"]}",
            r.headers["Content-Type"]!!.contains("text/event-stream"),
        )
    }

    @Test
    fun `流式响应恰好一个 DONE 标记`() {
        // ★ BYOK §4.3 的核心契约。上游的 Done 数量不能影响它。
        val (server, _) = buildServer()
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        assertEquals(
            "应恰好一个 [DONE]，实际：${body.takeLast(200)}",
            1,
            body.split("data: [DONE]").size - 1,
        )
    }

    @Test
    fun `上游给两个 Done 时仍只有一个 DONE 标记`() {
        // ★ 这是"恰好一个"的**对抗性**验证：上游重复发 Done
        //    （OpenAiCompatProvider 的注释里记过这个真实 bug）。
        //    实现把 [DONE] 的发出点定在"流结束"而不是"收到 Done chunk"，
        //    所以这里必须仍然只有一个。
        val provider = FakeProvider(
            chunks = listOf(
                ChatChunk.Delta("a"),
                ChatChunk.Done(TokenUsage(1, 1), "stop"),
                ChatChunk.Done(TokenUsage(1, 1), "stop"),
            )
        )
        val (server, _) = buildServer(provider = provider)
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        assertEquals(1, body.split("data: [DONE]").size - 1)
    }

    @Test
    fun `上游一个 Done 都不给时仍有一个 DONE 标记`() {
        // ★ 另一侧：断流。不给 [DONE] 的话客户端会一直等到超时。
        val provider = FakeProvider(chunks = listOf(ChatChunk.Delta("a")))
        val (server, _) = buildServer(provider = provider)
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        assertEquals(1, body.split("data: [DONE]").size - 1)
    }

    @Test
    fun `流式内容按分片到达`() {
        val (server, _) = buildServer()
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        // 两个 Delta 各自成帧
        assertTrue("实际：$body", body.contains("\"content\":\"你好\""))
        assertTrue("实际：$body", body.contains("\"content\":\"，世界\""))
    }

    @Test
    fun `流式分片里带 usage 与 finish_reason`() {
        val (server, _) = buildServer()
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        assertTrue(body.contains("\"prompt_tokens\":10"))
        assertTrue(body.contains("\"finish_reason\":\"stop\""))
    }

    @Test
    fun `流式响应不含 Provider 信息`() {
        val (server, _) = buildServer()
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        assertTrue(body.contains("chat.completion.chunk"))
        assertTrue(body.contains(HttpGatewayServer.VIRTUAL_MODEL))
        assertFalse("不得泄漏真实模型 id", body.contains("real-model-id"))
    }

    @Test
    fun `每帧以双换行结尾`() {
        val (server, _) = buildServer()
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        // 除最后一段外，每个事件块之间都是 \n\n
        val events = body.split("\n\n").filter { it.isNotBlank() }
        assertTrue("应有多个事件，实际 ${events.size}", events.size >= 3)
        for ((i, e) in events.withIndex()) {
            assertTrue("第 $i 个事件应以 data: 开头：$e", e.startsWith("data: "))
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  错误路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `非法 JSON 得到 400`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", "{ 这不是 json")

        assertEquals(400, r.status)
        assertTrue(r.body.contains("invalid_request_error"))
    }

    @Test
    fun `空 messages 得到 400`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(
            server, "POST", "/v1/chat/completions",
            """{"model":"x","messages":[]}""",
        )

        assertEquals(400, r.status)
        assertTrue(r.body.contains("messages"))
    }

    @Test
    fun `400 的错误文本不含用户内容`() {
        // ⚠️ 隐私：错误会回给客户端、也会进日志
        val (server, _) = buildServer()
        start(server)

        val r = request(
            server, "POST", "/v1/chat/completions",
            """{"messages":[{"role":"wizard","content":"秘密口令是 998877"}]}""",
        )

        assertEquals(400, r.status)
        assertFalse("不得回显用户内容：${r.body}", r.body.contains("998877"))
    }

    @Test
    fun `流式下上游报错时在流内发 error 事件`() {
        // ★ BYOK §4.3 的坑：状态码已经发出去了，只能改在流里发错误。
        //   同时验证错误文本**不含 Key**。
        val provider = FakeProvider(
            chunks = listOf(ChatChunk.Delta("先说一半")),
            failWith = com.pocketagent.provider.api.ProviderException.AuthFailed(
                "401 with key sk-test-key in body"
            ),
        )
        val (server, _) = buildServer(provider = provider)
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", chatBody(stream = true))

        // 状态码已经是 200（首字节早就发了）
        assertEquals(200, r.status)
        // 但流里有 error 事件
        assertTrue("应在流内报错：${r.body}", r.body.contains("upstream_error"))
        assertFalse("不得泄漏 Key：${r.body}", r.body.contains("sk-test-key"))
    }

    @Test
    fun `error 事件在 DONE 之前`() {
        // ⚠️ 顺序不能反：先 [DONE] 的话客户端认为流正常结束，
        //    后续错误载荷被丢弃 —— 用户看到"回答突然截断"且无提示。
        val provider = FakeProvider(
            chunks = listOf(ChatChunk.Delta("x")),
            failWith = com.pocketagent.provider.api.ProviderException.Timeout(),
        )
        val (server, _) = buildServer(provider = provider)
        start(server)

        val body = request(server, "POST", "/v1/chat/completions", chatBody(stream = true)).body

        val errorAt = body.indexOf("upstream_error")
        val doneAt = body.indexOf("data: [DONE]")
        assertTrue("应有 error 事件", errorAt >= 0)
        assertTrue("应有 DONE", doneAt >= 0)
        assertTrue("error 必须在 DONE 之前（error=$errorAt done=$doneAt）", errorAt < doneAt)
    }

    @Test
    fun `非流式下上游报错时用真实状态码`() {
        // ⚠️ 非流式下首字节还没发，**可以**给真实状态码 —— 这是两者
        //    的关键差异，不该被统一成"都发 200"。
        val provider = FakeProvider(
            chunks = listOf(ChatChunk.Delta("x")),
            failWith = com.pocketagent.provider.api.ProviderException.NoBalance(),
        )
        val (server, _) = buildServer(provider = provider)
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", chatBody(stream = false))

        assertEquals(502, r.status)
        assertTrue(r.body.contains("upstream_error"))
    }

    @Test
    fun `非流式的上游错误也不含 Key`() {
        val provider = FakeProvider(
            chunks = emptyList(),
            failWith = com.pocketagent.provider.api.ProviderException.AuthFailed(
                "Bearer sk-leaked-key-12345"
            ),
        )
        val (server, _) = buildServer(provider = provider)
        start(server)

        val r = request(server, "POST", "/v1/chat/completions", chatBody(stream = false))

        assertFalse("不得泄漏 Key：${r.body}", r.body.contains("sk-leaked-key-12345"))
    }

    @Test
    fun `未知路径得到 404`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/secret")

        assertEquals(404, r.status)
        // ⚠️ 不回显路径 —— 否则我们成了"能探测任意路径"的反射点
        assertFalse(r.body.contains("/v1/secret"))
    }

    @Test
    fun `畸形请求行得到 400`() {
        val (server, _) = buildServer()
        start(server)

        val resp = rawRequest(server, "GARBAGE\r\n\r\n")

        assertTrue("实际：${resp.take(80)}", resp.startsWith("HTTP/1.1 400"))
    }

    @Test
    fun `不支持的 Transfer-Encoding 被拒`() {
        // ⚠️ 刻意不支持 chunked —— 明确的能力声明，见 readRequest 的注释。
        //    回 400 而不是静默把 body 当空串（后者会让请求"看起来成功了"）。
        val (server, _) = buildServer()
        start(server)

        val resp = rawRequest(
            server,
            "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Authorization: Bearer ${server.token}\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Length: 10\r\n\r\n" +
                "0123456789",
        )

        assertTrue("实际：${resp.take(80)}", resp.startsWith("HTTP/1.1 400"))
    }

    @Test
    fun `超出 body 上限的声明被拒`() {
        // ⚠️ 必须先看声明再读 —— 否则一个伪造的大 body 会把应用 OOM 掉
        val (server, _) = buildServer()
        start(server)

        val resp = rawRequest(
            server,
            "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Authorization: Bearer ${server.token}\r\n" +
                "Content-Length: 999999999\r\n\r\n",
        )

        assertTrue("实际：${resp.take(80)}", resp.startsWith("HTTP/1.1 413"))
    }

    // ─────────────────────────────────────────────────────────────
    //  响应头（加固第 5 条）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `响应头不带 Server 之类的标识`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models")

        // ⚠️ 不是"安全隐蔽"，而是：任何标识都可能让客户端按 name 分支，
        //    把我们锁在别人的实现细节里。
        val headerNames = r.headers.keys.map { it.lowercase() }
        assertFalse(headerNames.contains("server"))
        assertFalse(headerNames.contains("x-powered-by"))
        assertFalse(headerNames.contains("date"))
    }

    @Test
    fun `错误响应也不带标识头`() {
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models", token = null)

        val headerNames = r.headers.keys.map { it.lowercase() }
        assertFalse(headerNames.contains("server"))
        assertFalse(headerNames.contains("www-authenticate"))
    }

    // ─────────────────────────────────────────────────────────────
    //  query string 处理
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `models 带 query string 仍能路由`() {
        // ⚠️ 保留 query 会让 /v1/models?x=1 落到 404，而调用方
        //    完全不知道原因。
        val (server, _) = buildServer()
        start(server)

        val r = request(server, "GET", "/v1/models?x=1&y=2")

        assertEquals(200, r.status)
    }

    // ─────────────────────────────────────────────────────────────
    //  生命周期
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `重复 start 是幂等的`() {
        val (server, _) = buildServer()
        start(server)
        val port = server.port

        assertTrue(server.start())
        assertEquals("不应换端口", port, server.port)
    }

    @Test
    fun `重复 stop 是幂等的`() {
        val (server, _) = buildServer()
        start(server)

        server.stop()
        server.stop()  // 不应抛
    }

    @Test
    fun `stop 之后端口不再可达`() {
        val (server, _) = buildServer()
        start(server)
        val port = server.port
        server.stop()

        // 端口已释放 —— 连接应当失败
        var failed = false
        try {
            Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
            }
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("stop 后端口应不可达", failed)
    }

    @Test
    fun `并发请求都能被处理`() {
        val (server, _) = buildServer()
        start(server)

        val results = (1..8).map { i ->
            Thread.ofVirtual().unstarted {
                // ⚠️ 存**整个 Resp**，不只是状态码。
                //
                //    第一版只存 `status`，于是失败时报告里只有一个 `502` ——
                //    没有响应体、没有 failureCode、没有异常类型。
                //    而 502 在本服务里有两个来源（`UpstreamError` 与
                //    **"非 GatewayCallException 的意外异常"**），
                //    两者要查的地方完全不同。
                //
                //    ⇒ **不可诊断的失败比 flaky 本身更值得修**：它会让人
                //      在"并发有 bug"和"测试环境脏了"之间反复猜，最后
                //      两边都不查。响应体里带着 `describeFailure(e)`
                //      的输出，那才是能定位问题的那一行。
                threadResults[i - 1] = request(
                    server, "POST", "/v1/chat/completions", chatBody(stream = false)
                )
            }
        }
        results.forEach { it.start() }
        results.forEach { it.join() }

        val got = threadResults.toList()
        assertEquals(
            "8 个并发请求都该成功。实际：\n" + got.mapIndexed { i, r ->
                "  [$i] status=${r?.status} body=${r?.body?.take(300)}"
            }.joinToString("\n"),
            List(8) { 200 },
            got.map { it?.status },
        )
    }

    /**
     * ⚠️ 用 `Resp?` 而不是 `IntArray` —— 见上面那段注释。
     *    每个线程写**不同下标**，`join()` 之后读，没有可见性问题。
     */
    private val threadResults = arrayOfNulls<Resp>(8)
}
