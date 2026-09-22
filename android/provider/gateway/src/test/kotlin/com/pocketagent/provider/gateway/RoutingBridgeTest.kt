package com.pocketagent.provider.gateway

import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelConfigRepository
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.ModelRouteCoordinator
import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.modelrouter.RoutingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RoutingBridge] 的离线单测。
 *
 * ⚠️ 用 `org.junit.Assert` 而不是 Truth（Truth 不在离线 classpath）。
 */
class RoutingBridgeTest {

    // ─────────────────────────────────────────────────────────────
    //  fake 仓储
    // ─────────────────────────────────────────────────────────────

    private class FakeModelRepo(
        private val items: List<ModelConfig>,
    ) : ModelConfigRepository {
        private val state = MutableStateFlow(items)
        override val models: Flow<List<ModelConfig>> get() = state
        override suspend fun all(): List<ModelConfig> = items
        override suspend fun byId(id: String): ModelConfig? = items.firstOrNull { it.id == id }
    }

    private fun config(
        id: String,
        credentialId: String = "cred-$id",
        modelId: String = "test-model-$id",
        tier: ModelTier = ModelTier.STANDARD,
        roles: Set<ModelRole> = setOf(ModelRole.WORKER),
        enabled: Boolean = true,
        label: String = id,
    ) = ModelConfig(
        id = id,
        label = label,
        credentialId = credentialId,
        modelId = modelId,
        modelDisplayName = "Model $id",
        tier = tier,
        inputPricePerMillion = 1.0,
        outputPricePerMillion = 2.0,
        roles = roles,
        enabled = enabled,
    )

    private fun bridgeOf(vararg items: ModelConfig): RoutingBridge {
        val repo = FakeModelRepo(items.toList())
        return RoutingBridge(
            coordinator = ModelRouteCoordinator(repo),
            modelById = { id -> repo.byId(id) },
        )
    }

    private fun value(outcome: GatewayOutcome<ResolvedTarget>): ResolvedTarget {
        assertTrue(
            "期望成功，实际是 ${(outcome as? GatewayOutcome.Failure)?.reason}",
            outcome is GatewayOutcome.Success,
        )
        return (outcome as GatewayOutcome.Success).value
    }

    private fun failure(outcome: GatewayOutcome<ResolvedTarget>): GatewayFailure {
        assertTrue("期望失败，实际是 $outcome", outcome is GatewayOutcome.Failure)
        return (outcome as GatewayOutcome.Failure).reason
    }

    // ─────────────────────────────────────────────────────────────
    //  单模型模式：最常用路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `单模型模式解析出配置的凭据与模型 id`() = runTest {
        val bridge = bridgeOf(config("m1", credentialId = "key-a", modelId = "deepseek-chat"))

        val target = value(bridge.resolve(RoutingMode.Single("m1"), GatewayContext()))

        assertEquals("m1", target.modelConfigId)
        // ★ 这两个才是真正要传下去的东西 —— 它们错了请求就发给错的地方
        assertEquals("key-a", target.credentialId)
        assertEquals("deepseek-chat", target.modelId)
    }

    @Test
    fun `单模型模式下配置被禁用时返回 ModelDisabled`() = runTest {
        val bridge = bridgeOf(config("m1", enabled = false))

        val f = failure(bridge.resolve(RoutingMode.Single("m1"), GatewayContext()))

        // ⚠️ 必须是 ModelDisabled 而不是 NoModelConfigured ——
        //    用户的补救动作是"把它启用"，而"去配置页加一个模型"是错的指引
        assertTrue("期望 ModelDisabled，实际 $f", f is GatewayFailure.ModelDisabled)
        assertEquals("m1", (f as GatewayFailure.ModelDisabled).modelConfigId)
    }

    @Test
    fun `单模型模式下指定的配置不存在时返回 NoModelConfigured`() = runTest {
        val bridge = bridgeOf(config("m1"))

        val f = failure(bridge.resolve(RoutingMode.Single("nope"), GatewayContext()))

        assertEquals(GatewayFailure.NoModelConfigured, f)
    }

    @Test
    fun `没有任何模型时返回 NoModelConfigured`() = runTest {
        val bridge = bridgeOf()

        assertEquals(
            GatewayFailure.NoModelConfigured,
            failure(bridge.resolve(RoutingMode.Single("m1"), GatewayContext())),
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  调度模式
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `调度模式按难度选档`() = runTest {
        val light = config("light", tier = ModelTier.LIGHT)
        val heavy = config("heavy", tier = ModelTier.HEAVY)
        val bridge = bridgeOf(light, heavy)

        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = "sched",
            tierModels = mapOf(ModelTier.LIGHT to "light", ModelTier.HEAVY to "heavy"),
        )

        // 用 HEAVY 覆写难度 → 应选 heavy
        val target = value(
            bridge.resolve(mode, GatewayContext(tierOverride = ModelTier.HEAVY))
        )

        assertEquals("heavy", target.modelConfigId)
        assertTrue("应带出决策依据", target.reasons.isNotEmpty())
    }

    @Test
    fun `调度模式缺档位时降级并标记 degraded`() = runTest {
        val heavy = config("heavy", tier = ModelTier.HEAVY)
        val bridge = bridgeOf(heavy)

        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = "",
            tierModels = mapOf(ModelTier.HEAVY to "heavy"),
        )

        // 任务判为 LIGHT，但用户只配了 HEAVY → 只能用 HEAVY，且降级
        val target = value(
            bridge.resolve(mode, GatewayContext(tierOverride = ModelTier.LIGHT))
        )

        assertEquals("heavy", target.modelConfigId)
        // ⚠️ degraded 必须为 true —— 界面要靠它提示"你以为配了省钱方案，
        //    实际每次都走贵的那个"。不告诉用户就等于让他白配。
        assertTrue("缺档位应标记为降级", target.degraded)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ modelOverride：绕开路由但**不绕过校验**
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `modelOverride 生效时改用指定模型`() = runTest {
        val a = config("a", tier = ModelTier.LIGHT)
        val b = config("b", tier = ModelTier.HEAVY)
        val bridge = bridgeOf(a, b)

        val target = value(
            bridge.resolve(RoutingMode.Single("a"), GatewayContext(modelOverride = "b"))
        )

        assertEquals("b", target.modelConfigId)
        assertEquals("cred-b", target.credentialId)
    }

    @Test
    fun `modelOverride 指定的模型被禁用时仍然拒绝`() = runTest {
        // ⚠️ 这条是本次实施特意加的 —— 第一版实现直接信任 override，
        //    于是"指定一个被禁用的模型"能绕过 ModelRouter 里的启用检查，
        //    请求照样发出去。表现是"用户禁用了它，但它还在被用"，
        //    而且没有任何报错。
        val a = config("a")
        val disabled = config("b", enabled = false)
        val bridge = bridgeOf(a, disabled)

        val f = failure(
            bridge.resolve(RoutingMode.Single("a"), GatewayContext(modelOverride = "b"))
        )

        assertTrue("期望 ModelDisabled，实际 $f", f is GatewayFailure.ModelDisabled)
    }

    @Test
    fun `modelOverride 指向不存在的配置时失败`() = runTest {
        val bridge = bridgeOf(config("a"))

        assertEquals(
            GatewayFailure.NoModelConfigured,
            failure(
                bridge.resolve(RoutingMode.Single("a"), GatewayContext(modelOverride = "ghost"))
            ),
        )
    }

    @Test
    fun `modelOverride 生效时 reasons 要说明跳过了调度`() = runTest {
        val a = config("a", tier = ModelTier.LIGHT)
        val b = config("b", tier = ModelTier.HEAVY)
        val bridge = bridgeOf(a, b)

        val target = value(
            bridge.resolve(RoutingMode.Single("a"), GatewayContext(modelOverride = "b"))
        )

        // ⚠️ 不加这句话，用户会看到"判为轻量档"的说明却用着重型模型 ——
        //    他会以为调度坏了，然后去配置页反复检查（那里没问题）。
        val joined = target.reasons.joinToString("；")
        assertTrue("reasons 应说明已跳过调度，实际：$joined", joined.contains("跳过调度"))
    }

    @Test
    fun `modelOverride 生效时不应标记为降级`() = runTest {
        val a = config("a", tier = ModelTier.LIGHT)
        val b = config("b", tier = ModelTier.HEAVY)
        val bridge = bridgeOf(a, b)

        val target = value(
            bridge.resolve(RoutingMode.Single("a"), GatewayContext(modelOverride = "b"))
        )

        // 用户（或重试逻辑）明确指定了模型，这不是"降级"——
        // 标成降级会让界面弹出"你缺了某个档位"的提示，而那是误导。
        assertTrue("指定模型不算降级", !target.degraded)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 凭据缺失
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `credentialId 为空时返回 CredentialMissing`() = runTest {
        // ⚠️ 这条防的是"坏配置一路走到解密那步"：那时报的是
        //    "凭据不存在"，而用户会去找一条根本不存在的 Key，
        //    排查方向完全错。
        val broken = config("m1", credentialId = "")
        val bridge = bridgeOf(broken)

        val f = failure(bridge.resolve(RoutingMode.Single("m1"), GatewayContext()))

        assertTrue("期望 CredentialMissing，实际 $f", f is GatewayFailure.CredentialMissing)
    }

    @Test
    fun `credentialId 全为空格时也返回 CredentialMissing`() = runTest {
        // isBlank 而非 isEmpty —— 手改库或旧数据可能留下空白串
        val broken = config("m1", credentialId = "   ")
        val bridge = bridgeOf(broken)

        val f = failure(bridge.resolve(RoutingMode.Single("m1"), GatewayContext()))

        assertTrue("期望 CredentialMissing，实际 $f", f is GatewayFailure.CredentialMissing)
    }

    // ─────────────────────────────────────────────────────────────
    //  GatewayOutcome 便捷函数
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `valueOrNull 与 failureOrNull 语义互补`() = runTest {
        val bridge = bridgeOf(config("m1"))

        val ok = bridge.resolve(RoutingMode.Single("m1"), GatewayContext())
        assertNotNull(ok.valueOrNull())
        assertNull(ok.failureOrNull())

        val bad = bridge.resolve(RoutingMode.Single("nope"), GatewayContext())
        assertNull(bad.valueOrNull())
        assertNotNull(bad.failureOrNull())
    }

    // ─────────────────────────────────────────────────────────────
    //  失败类型自带的提示语
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `每种失败都有可直接展示的用户提示`() = runTest {
        // ⚠️ userMessage 是要直接显示给用户的，空串会让界面出现一个
        //    没有文字的红色错误块 —— 比不显示更糟（用户不知道发生了什么）
        val failures = listOf(
            GatewayFailure.NoModelConfigured,
            GatewayFailure.ModelDisabled("x"),
            GatewayFailure.ProviderNotRegistered("p"),
            GatewayFailure.CredentialMissing("c"),
            GatewayFailure.CredentialUndecryptable,
            GatewayFailure.BudgetExceeded("token", "100", "200"),
            GatewayFailure.UpstreamError("boom", retryable = true),
            GatewayFailure.UpstreamError("boom", retryable = false),
        )

        failures.forEach { f ->
            assertTrue(
                "${f::class.simpleName} 的 userMessage 不应为空",
                f.userMessage.isNotBlank(),
            )
        }
    }

    @Test
    fun `预算超限的提示语要含具体数字`() = runTest {
        val f = GatewayFailure.BudgetExceeded("单次请求 token 上限", "1000", "1500")

        // ⚠️ "超出预算"这四个字对用户没有任何用处 —— 他没法据此调整。
        //    必须给出是哪条限制、上限多少、当前多少。
        assertTrue("应含限制名", f.userMessage.contains("单次请求 token 上限"))
        assertTrue("应含上限值", f.userMessage.contains("1000"))
        assertTrue("应含当前值", f.userMessage.contains("1500"))
    }
}
