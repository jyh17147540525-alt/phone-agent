package com.pocketagent.provider.gateway

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
import com.pocketagent.provider.api.ProviderException
import com.pocketagent.provider.api.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GatewayCore] 的离线单测。
 *
 * ⚠️ 用 `org.junit.Assert` 而不是 Truth（Truth 不在离线 classpath）。
 *
 * ⚠️ 刻意**不用 mockk** —— 手写 fake 更啰嗦，但它能精确控制
 *    "Provider 收到了什么请求""Key 什么时候被读"，而这些正是本类
 *    最需要钉住的行为（mockk 的 `verify` 表达不了"数组内容后来被清零了"）。
 */
class GatewayCoreTest {

    // ─────────────────────────────────────────────────────────────
    //  fake 仓储
    // ─────────────────────────────────────────────────────────────

    private class FakeModelRepo(private val items: List<ModelConfig>) : ModelConfigRepository {
        override val models: Flow<List<ModelConfig>> = MutableStateFlow(items)
        override suspend fun all() = items
        override suspend fun byId(id: String) = items.firstOrNull { it.id == id }
    }

    /**
     * 可编程的假 Provider。
     *
     * @param chunks 正常返回的 chunk 序列
     * @param failWith 若非 null，则在产出 [failAfterChunks] 个 chunk 后抛它
     */
    private class FakeProvider(
        override val id: String = "fake",
        private val chunks: List<ChatChunk> = listOf(
            ChatChunk.Delta("hello"),
            ChatChunk.Done(TokenUsage(inputTokens = 10, outputTokens = 5), "stop"),
        ),
        private val failWith: Throwable? = null,
        private val failAfterChunks: Int = 0,
    ) : LlmProvider {
        override val displayName = "Fake"
        override val authScheme: AuthScheme = AuthScheme.Bearer
        override val defaultBaseUrl = "https://example.invalid"

        /** 记录收到的请求，供断言 */
        var lastRequest: ChatRequest? = null
        var lastCredential: ProviderCredential? = null

        /** 记录调用时刻的 Key 内容快照 —— 用来验证"转发时 Key 还没被清零" */
        var keyAtCallTime: String? = null

        override fun capabilities() = setOf(Capability.STREAM)

        override suspend fun validateKey(credential: ProviderCredential) =
            KeyValidationResult.Valid(1, 1)

        override suspend fun listModels(credential: ProviderCredential): List<ModelInfo>? = null

        override fun chat(request: ChatRequest, credential: ProviderCredential): Flow<ChatChunk> {
            lastRequest = request
            lastCredential = credential
            // 快照此刻的 Key —— 若 GatewayCore 过早清零，这里会是空串
            keyAtCallTime = credential.apiKeyForRequest()

            return flow {
                chunks.take(failAfterChunks.coerceAtLeast(if (failWith != null) 0 else chunks.size))
                    .forEach { emit(it) }
                if (failWith != null) {
                    // 若 failAfterChunks == 0 则一个都不发就抛
                    if (failAfterChunks == 0) throw failWith
                    // 否则前面已经发了 failAfterChunks 个，这里抛
                    if (failAfterChunks < chunks.size) throw failWith
                }
            }
        }

        override fun countTokens(messages: List<ChatMessage>, model: String) = 42

        override fun estimateCost(usage: TokenUsage, model: String) = Cost(0.001)
    }

    /** 记录"解析了几次、什么时候解析"的假凭据源 */
    private class FakeCredentialSource(
        private val credential: ResolvedCredential? = ResolvedCredential(
            apiKey = "sk-secret-value".toByteArray(),
            providerId = "fake",
        ),
        private val throwOnResolve: Throwable? = null,
    ) : CredentialSource {
        /** 解析次数 —— 用来验证"超预算时不该解密" */
        var resolveCount = 0
            private set

        /** 持有的凭据引用，供断言"是否被清零" */
        var lastResolved: ResolvedCredential? = null
            private set

        override suspend fun resolve(credentialId: String): ResolvedCredential? {
            resolveCount++
            if (throwOnResolve != null) throw throwOnResolve
            lastResolved = credential
            return credential
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  组装
    // ─────────────────────────────────────────────────────────────

    private fun config(
        id: String = "m1",
        credentialId: String = "cred-1",
        modelId: String = "real-model-id",
        tier: ModelTier = ModelTier.STANDARD,
        enabled: Boolean = true,
        inputPrice: Double? = 1.0,
        outputPrice: Double? = 2.0,
    ) = ModelConfig(
        id = id,
        label = "测试模型",
        credentialId = credentialId,
        modelId = modelId,
        modelDisplayName = "Test Model",
        tier = tier,
        inputPricePerMillion = inputPrice,
        outputPricePerMillion = outputPrice,
        roles = setOf(ModelRole.WORKER),
        enabled = enabled,
    )

    private class Harness(
        val core: GatewayCore,
        val provider: FakeProvider,
        val credentials: FakeCredentialSource,
        val recorder: InMemoryUsageRecorder,
        val config: ModelConfig,
    )

    private fun harness(
        config: ModelConfig = config(),
        provider: FakeProvider = FakeProvider(),
        credentials: FakeCredentialSource = FakeCredentialSource(),
        recorder: InMemoryUsageRecorder = InMemoryUsageRecorder(),
        defaultBudget: RequestBudget = GatewayCore.DEFAULT_REQUEST_BUDGET,
    ): Harness {
        val repo = FakeModelRepo(listOf(config))
        val bridge = RoutingBridge(
            coordinator = ModelRouteCoordinator(repo),
            modelById = { id -> repo.byId(id) },
        )
        val core = GatewayCore(
            bridge = bridge,
            credentials = credentials,
            providers = listOf(provider),
            usageRecorder = recorder,
            defaultBudget = defaultBudget,
        )
        return Harness(core, provider, credentials, recorder, config)
    }

    private fun simpleRequest(modelFromClient: String = "client-said-gpt-4o") = ChatRequest(
        model = modelFromClient,
        messages = listOf(ChatMessage.user("你好")),
    )

    // ─────────────────────────────────────────────────────────────
    //  快乐路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `正常调用产出全部 chunk`() = runTest {
        val h = harness()

        val chunks = h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        assertEquals(2, chunks.size)
        assertTrue(chunks[0] is ChatChunk.Delta)
        assertTrue(chunks[1] is ChatChunk.Done)
    }

    @Test
    fun `客户端给的 model 字段被覆盖为路由选中的模型`() = runTest {
        // ⚠️ 这是本类最重要的一条契约。
        //    客户端（dsh）会传它自己以为的模型名，而真实用哪个模型
        //    由我们的路由决定 —— 用户配的可能是 DeepSeek，
        //    却收到"找不到 gpt-4o"是他完全没法修复的错误。
        val h = harness(config = config(modelId = "deepseek-chat"))

        h.core.complete(
            RoutingMode.Single("m1"),
            simpleRequest(modelFromClient = "gpt-4o"),
        ).toList()

        assertEquals("deepseek-chat", h.provider.lastRequest?.model)
    }

    @Test
    fun `转发时 Key 尚未被清零`() = runTest {
        // ⚠️ 这条防的是"清零过早" —— 若 withResolvedCredential 的 finally
        //    跑在 Provider 真正消费凭据之前，请求会带空 Key 发出去，
        //    而表现是上游返回 401，排查方向会跑到"Key 是不是过期了"。
        val h = harness()

        h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        assertEquals("sk-secret-value", h.provider.keyAtCallTime)
    }

    @Test
    fun `调用结束后明文 Key 被清零`() = runTest {
        val credentials = FakeCredentialSource()
        val h = harness(credentials = credentials)

        h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        // ★ 这是项目的安全红线之一（原则 7：Key 永不落明文）
        val resolved = credentials.lastResolved
        assertNotNull("应解析过凭据", resolved)
        assertTrue(
            "明文 Key 必须在调用结束后被清零，实际：${resolved!!.apiKey.toList()}",
            resolved.apiKey.all { it == 0.toByte() },
        )
    }

    @Test
    fun `上游抛异常时明文 Key 仍然被清零`() = runTest {
        // ⚠️ 这是"只在正常路径清零"最典型的漏点 —— 异常路径是明文
        //    停留最久的路径之一，必须先于快乐路径被验证。
        val credentials = FakeCredentialSource()
        val provider = FakeProvider(failWith = ProviderException.AuthFailed("401"))
        val h = harness(provider = provider, credentials = credentials)

        try {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        } catch (_: ProviderException) {
            // 预期
        }

        val resolved = credentials.lastResolved
        assertNotNull(resolved)
        assertTrue(
            "异常路径下明文 Key 也必须清零",
            resolved!!.apiKey.all { it == 0.toByte() },
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 顺序保证：熔断在解密之前
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `超预算时不解密凭据`() = runTest {
        // ═══════════════════════════════════════════════════════════
        //  ★ 这条是本类顺序保证的核心测试
        // ═══════════════════════════════════════════════════════════
        //
        // 超预算的请求**根本不该碰 Keystore**：解密有实测开销，
        // 而且会让明文在内存里多待一段时间。
        //
        // 若有人把预算检查挪到解密之后，这条会红 —— 而那种改动
        // 从代码上看"更自然"（先拿到 Provider 才能 countTokens），
        // 所以必须用测试把它钉住。
        //
        // ⚠️ 构造"必然超"的请求要用**明确的**长输入，不能靠"预算设得小
        //    所以一定超" —— 那种写法依赖 simpleRequest 里恰好有多少字符，
        //    改一个字面量就会静默变成"其实没超但测试还以为超了"，
        //    表现是断言失败在一次**看起来毫不相关**的改动之后。
        val oversized = ChatRequest(
            model = "x",
            messages = listOf(ChatMessage.user("A".repeat(200))),
        )

        val credentials = FakeCredentialSource()
        val h = harness(
            credentials = credentials,
            defaultBudget = RequestBudget(maxTokens = 100), // 输入 200 > 100
        )

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), oversized).toList()
        }.exceptionOrNull()

        assertTrue("应抛 GatewayCallException，实际 $ex", ex is GatewayCallException)
        assertTrue(
            "失败原因应是超预算，实际 ${(ex as GatewayCallException).failure}",
            ex.failure is GatewayFailure.BudgetExceeded,
        )
        assertEquals("超预算时不应解密凭据", 0, credentials.resolveCount)
    }

    @Test
    fun `路由失败时不解密凭据`() = runTest {
        val credentials = FakeCredentialSource()
        val h = harness(credentials = credentials)

        runCatching {
            h.core.complete(RoutingMode.Single("不存在的 id"), simpleRequest()).toList()
        }

        assertEquals(0, credentials.resolveCount)
    }

    // ─────────────────────────────────────────────────────────────
    //  失败路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `模型被禁用时抛出带 ModelDisabled 的异常`() = runTest {
        val h = harness(config = config(enabled = false))

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }.exceptionOrNull()

        assertTrue(ex is GatewayCallException)
        assertTrue((ex as GatewayCallException).failure is GatewayFailure.ModelDisabled)
    }

    @Test
    fun `凭据不存在时抛出 CredentialMissing`() = runTest {
        val h = harness(credentials = FakeCredentialSource(credential = null))

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }.exceptionOrNull()

        assertTrue(ex is GatewayCallException)
        assertTrue((ex as GatewayCallException).failure is GatewayFailure.CredentialMissing)
    }

    @Test
    fun `凭据解不开时抛出 CredentialUndecryptable`() = runTest {
        val h = harness(
            credentials = FakeCredentialSource(
                throwOnResolve = CredentialDecryptException()
            )
        )

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }.exceptionOrNull()

        assertTrue(ex is GatewayCallException)
        // ⚠️ 必须是 CredentialUndecryptable 而不是 CredentialMissing ——
        //    前者用户要"重新填"，后者他会去找一个不存在的条目
        assertTrue(
            "实际 ${(ex as GatewayCallException).failure}",
            ex.failure is GatewayFailure.CredentialUndecryptable,
        )
    }

    @Test
    fun `Provider 未注册时抛出 ProviderNotRegistered`() = runTest {
        // 凭据说它属于 "unknown-provider"，但 providers 列表里没有
        val h = harness(
            credentials = FakeCredentialSource(
                credential = ResolvedCredential(
                    apiKey = "sk-x".toByteArray(),
                    providerId = "unknown-provider",
                )
            )
        )

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }.exceptionOrNull()

        assertTrue(ex is GatewayCallException)
        val f = (ex as GatewayCallException).failure
        assertTrue("实际 $f", f is GatewayFailure.ProviderNotRegistered)
        assertEquals("unknown-provider", (f as GatewayFailure.ProviderNotRegistered).providerId)
    }

    @Test
    fun `未注册 Provider 时也要清零刚解密的 Key`() = runTest {
        // ⚠️ 这是另一个漏点：withResolvedCredential 的 block 里**早于**
        //    Provider 解析的失败，必须仍然走 finally 清零。
        val credentials = FakeCredentialSource(
            credential = ResolvedCredential(
                apiKey = "sk-x".toByteArray(),
                providerId = "unknown-provider",
            )
        )
        val h = harness(credentials = credentials)

        runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }

        assertTrue(
            "Provider 解析失败路径下 Key 仍须清零",
            credentials.lastResolved!!.apiKey.all { it == 0.toByte() },
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  用量记录
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `成功调用记录上游给的真实输出用量`() = runTest {
        // ⚠️ 这条防的是"finalUsage 从未被赋值" —— 那样 outputTokens 会
        //    恒为 0，表现是"用量统计里输出永远是 0"，不报错、
        //    只是安静地少记了一件事。第一版实现确实有这个 bug。
        val h = harness()

        h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        assertEquals(1, h.recorder.records.size)
        val rec = h.recorder.records.first()
        assertEquals(5, rec.outputTokens)
        assertNotNull("应保存上游权威用量", rec.upstreamUsage)
        assertEquals(10, rec.upstreamUsage!!.inputTokens)
        assertNull("成功时不应有失败原因", rec.failure)
    }

    @Test
    fun `用量记录携带消费方身份`() = runTest {
        val h = harness()

        h.core.complete(
            RoutingMode.Single("m1"),
            simpleRequest(),
            GatewayContext(consumer = Consumer.Dsh),
        ).toList()

        assertEquals(Consumer.Dsh, h.recorder.records.first().consumer)
    }

    @Test
    fun `用量记录携带正确的 providerId 与模型 id`() = runTest {
        val h = harness()

        h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        val rec = h.recorder.records.first()
        assertEquals("m1", rec.modelConfigId)
        assertEquals("fake", rec.providerId)
    }

    @Test
    fun `上游失败时仍然记录用量并标记失败原因`() = runTest {
        // ⚠️ 失败的调用**也要记账** —— 上游可能已经计费了
        //    （比如首字节已发出、token 已经烧掉）。不记的话
        //    "用户看到的账单"与"我们显示的用量"对不上，而他会
        //    怀疑我们在偷跑请求。
        val h = harness(provider = FakeProvider(failWith = ProviderException.Timeout()))

        runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }

        assertEquals(1, h.recorder.records.size)
        assertEquals("timeout", h.recorder.records.first().failure)
    }

    @Test
    fun `失败原因只保留类型不含原始 message`() = runTest {
        // ⚠️ ProviderException 的 message 有时带请求片段（各家 SDK 风格不一），
        //    而我们要落库 —— 落库就意味着它可能被导出、被上报。
        //    所以 describe() 只取类型，绝不透传 message。
        val secret = "sk-leaked-1234567890"
        val h = harness(
            provider = FakeProvider(
                failWith = ProviderException.ProtocolError("bad response, key=$secret")
            )
        )

        runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }

        val recorded = h.recorder.records.first().failure
        assertEquals("protocol error", recorded)
        assertTrue(
            "失败原因里绝不能出现 Key 片段，实际：$recorded",
            !recorded!!.contains(secret),
        )
    }

    @Test
    fun `价格未知时用量记录带上警告`() = runTest {
        // ⚠️ 必须落库 —— 否则"用户的预算对某个模型静默失效"这件事
        //    事后完全无法追溯，而它造成的损失是真金白银。
        val h = harness(
            config = config(inputPrice = null, outputPrice = null),
            defaultBudget = RequestBudget(maxTokens = 100_000, maxCostUsd = 1.0),
        )

        h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        val warning = h.recorder.records.first().warning
        assertNotNull("价格未知时应带警告落库", warning)
        assertTrue("警告应说明预算不生效，实际：$warning", warning!!.contains("不生效"))
    }

    @Test
    fun `价格已知时用量记录不带警告`() = runTest {
        val h = harness(
            defaultBudget = RequestBudget(maxTokens = 100_000, maxCostUsd = 1.0),
        )

        h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        assertNull(h.recorder.records.first().warning)
    }

    // ─────────────────────────────────────────────────────────────
    //  输出额度收敛
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `输出额度按预算收敛后传给上游`() = runTest {
        val h = harness(
            defaultBudget = RequestBudget(maxTokens = 200),
        )

        h.core.complete(
            RoutingMode.Single("m1"),
            simpleRequest().copy(maxTokens = 10_000),
        ).toList()

        // 输入估算 2 字符（"你好"）→ 剩 198，要 10000 → 收敛到 198
        val sent = h.provider.lastRequest?.maxTokens
        assertNotNull(sent)
        assertTrue("输出额度应被收敛，实际 $sent", sent!! < 10_000)
    }

    @Test
    fun `调用方未指定 maxTokens 时按剩余额度填`() = runTest {
        val h = harness(defaultBudget = RequestBudget(maxTokens = 500))

        h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        assertNotNull("应填充剩余额度", h.provider.lastRequest?.maxTokens)
    }

    // ─────────────────────────────────────────────────────────────
    //  输入估算：必须保守（估高）
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `图片按固定 token 计入输入估算`() = runTest {
        // ⚠️ 截图是数 MB 的 base64，绝不该按 0 token 算 ——
        //    那样带图的请求会完全绕过 token 熔断。
        val credentials = FakeCredentialSource()
        val h = harness(
            credentials = credentials,
            defaultBudget = RequestBudget(maxTokens = 100), // 图就 1000，必然超
        )

        val withImage = ChatRequest(
            model = "x",
            messages = listOf(
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = listOf(
                        com.pocketagent.provider.api.ContentPart.Text("看这个"),
                        com.pocketagent.provider.api.ContentPart.Image(ByteArray(64)),
                    ),
                )
            ),
        )

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), withImage).toList()
        }.exceptionOrNull()

        // ⚠️ 断言"因为超预算"而不只是"抛了异常" —— 否则路由失败、
        //    Provider 未注册之类的失败也会让这条测试变绿。
        assertTrue("带图请求应被判超预算，实际 $ex", ex is GatewayCallException)
        assertTrue(
            "失败原因应是超预算，实际 ${(ex as GatewayCallException).failure}",
            ex.failure is GatewayFailure.BudgetExceeded,
        )
    }

    @Test
    fun `工具 schema 计入输入估算`() = runTest {
        val credentials = FakeCredentialSource()
        val h = harness(
            credentials = credentials,
            defaultBudget = RequestBudget(maxTokens = 50),
        )

        val withTools = ChatRequest(
            model = "x",
            messages = listOf(ChatMessage.user("hi")),
            tools = listOf(
                com.pocketagent.provider.api.ToolSpec(
                    name = "tap",
                    description = "A" .repeat(500),
                    parametersJsonSchema = "{}",
                )
            ),
        )

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), withTools).toList()
        }.exceptionOrNull()

        // 工具 schema 只在请求体里出现一次，但它是常被忽略的一大块
        assertTrue("工具 schema 应计入估算，实际 $ex", ex is GatewayCallException)
        assertTrue(
            "失败原因应是超预算，实际 ${(ex as GatewayCallException).failure}",
            ex.failure is GatewayFailure.BudgetExceeded,
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  默认预算
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `默认预算不是无限制`() {
        // ⚠️ "忘了传预算"必须有一个保守的兜底 —— 若默认是"不限制"，
        //    新写的调用点就成了用户无法察觉的烧钱口子。
        val d = GatewayCore.DEFAULT_REQUEST_BUDGET
        assertTrue("默认应有 token 上限", d.maxTokens in 1..1_000_000)

        // ⚠️ 刻意**不设**默认金额上限 —— 费用与"用哪个模型"强相关，
        //    而且价格未知时算不出来（本地模型会因此被误拒）。
        assertNull("默认不应有金额上限", d.maxCostUsd)
    }

    // ─────────────────────────────────────────────────────────────
    //  流式语义
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `恰好一个 Done`() = runTest {
        // ⚠️ 本项目在对话侧踩过"Done 发了两次或零次"的真实 bug。
        //    走网关后它表现为"客户端卡住等不到结束"。这里钉住。
        val h = harness()

        val chunks = h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()

        assertEquals(1, chunks.count { it is ChatChunk.Done })
    }

    @Test
    fun `上游异常穿透到调用方`() = runTest {
        // ⚠️ GatewayCore 刻意不把流中错误协议化 —— 那是 HttpGatewayServer
        //    的职责（它才有 SSE 事件可发）。这里保持"异常向上抛"。
        val h = harness(provider = FakeProvider(failWith = ProviderException.RateLimited(30)))

        val ex = runCatching {
            h.core.complete(RoutingMode.Single("m1"), simpleRequest()).toList()
        }.exceptionOrNull()

        assertTrue("上游异常应穿透，实际 $ex", ex is ProviderException.RateLimited)
    }

    @Test
    fun `GatewayCallException 携带可枚举的失败原因`() {
        // ⚠️ 异常只是**传输手段**，分类仍由 GatewayFailure 完成 ——
        //    这个区分保证"新增一种失败原因"不需要新增异常类型。
        val e = GatewayCallException(GatewayFailure.CredentialUndecryptable)

        assertSame(GatewayFailure.CredentialUndecryptable, e.failure)
        assertEquals(GatewayFailure.CredentialUndecryptable.userMessage, e.message)
    }
}
