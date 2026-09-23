package com.pocketagent.provider.gateway.dsh

import com.pocketagent.modelrouter.ModelConfig
import com.pocketagent.modelrouter.ModelConfigRepository
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.ModelRouteCoordinator
import com.pocketagent.modelrouter.ModelTier
import com.pocketagent.modelrouter.RoutingMode
import com.pocketagent.provider.gateway.GatewayCallException
import com.pocketagent.provider.gateway.GatewayFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [DshRoutingModeProvider] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是"网关一个请求都服务不了"和"报错方向指错"
 * ═══════════════════════════════════════════════════════════════
 *
 * 本类只有一条实质逻辑（显式优先，否则推断），但它接在两条**很硬的边界**上：
 *
 * 1. **`HttpGatewayServer.routingModeFor` 的默认实现会抛** —— 所以没有本类，
 *    网关**一个请求都服务不了**。这条不是"少个功能"，是"整个 1.4.6c 白做"。
 * 2. **`inferMode()` 返回 `null` 是正常状态**（用户还没配模型），
 *    而 `routingModeFor` 的签名要求非空 —— `null` 在这里怎么翻译，
 *    决定用户被指向**配置页**还是"网关坏了"。
 *
 * 第 2 条尤其值得测：`GatewayFailure.NoModelConfigured.userMessage` 那句话
 * 会被 `describeFailure` 原样送到 dsh 那边。它写错了，用户就会去查错地方。
 */
class DshRoutingModeProviderTest {

    // ── 测试替身 ─────────────────────────────────────────────────

    /**
     * 内存版仓储。
     *
     * ⚠️ 用 `MutableStateFlow` 而不是 `flowOf` —— 真实仓储发的是持续更新的流
     *    （Room 在表变化时重发），`flowOf` 只发一次就当结束，测不出
     *    "配置改了之后推断有没有跟着变"。
     */
    private class FakeRepo(initial: List<ModelConfig> = emptyList()) : ModelConfigRepository {
        val state = MutableStateFlow(initial)
        override val models: Flow<List<ModelConfig>> get() = state
        override suspend fun all(): List<ModelConfig> = state.value
        override suspend fun byId(id: String): ModelConfig? = state.value.firstOrNull { it.id == id }
    }

    private fun model(
        id: String,
        tier: ModelTier = ModelTier.STANDARD,
        roles: Set<ModelRole> = setOf(ModelRole.WORKER),
        enabled: Boolean = true,
    ) = ModelConfig(
        id = id,
        label = "",
        credentialId = "cred",
        modelId = id,
        modelDisplayName = id,
        tier = tier,
        roles = roles,
        enabled = enabled,
    )

    private fun provider(
        models: List<ModelConfig>,
        explicit: (suspend () -> RoutingMode?)? = null,
    ): DshRoutingModeProvider {
        val coordinator = ModelRouteCoordinator(FakeRepo(models))
        return if (explicit == null) {
            DshRoutingModeProvider(coordinator)
        } else {
            DshRoutingModeProvider(coordinator, explicit)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  规则 1：用户显式选过 → 用他的选择
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `用户显式选过时用他的选择而不是推断`() = runTest {
        // 用户配了两个 worker（推断会得到 Scheduled），但他显式选了 Single。
        // ⚠️ 若这里被推断覆盖，用户的设定会被**悄悄改回去**，
        //    而他找不到地方改回来（`inferMode` 的注释专门警告过这条）。
        val p = provider(
            models = listOf(
                model("cheap", ModelTier.LIGHT),
                model("strong", ModelTier.HEAVY),
            ),
            explicit = { RoutingMode.Single("strong") },
        )

        assertEquals(RoutingMode.Single("strong"), p.current())
    }

    @Test
    fun `显式选择指向的模型即使不在列表里也照用`() = runTest {
        // ⚠️ 本类**不校验**显式选择是否指向存在的模型 —— 那是 `ModelRouter`
        //    的职责（它会给出 `ModelNotFound` 这种可展示的失败）。
        //    在这里"顺手校验"会导致：用户在配置页删了一个模型，
        //    而网关直接抛"没有可用模型"，把"某个模型没了"误报成"一个都没配"。
        val p = provider(
            models = listOf(model("only")),
            explicit = { RoutingMode.Single("deleted-model") },
        )

        assertEquals(RoutingMode.Single("deleted-model"), p.current())
    }

    @Test
    fun `显式模式返回 null 时回落到推断`() = runTest {
        val p = provider(
            models = listOf(model("only")),
            explicit = { null },
        )

        assertEquals(RoutingMode.Single("only"), p.current())
    }

    // ─────────────────────────────────────────────────────────────
    //  规则 2：没选过 → 推断
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `没选过且只有一个 worker 时得到单模型模式`() = runTest {
        val p = provider(listOf(model("only")))
        assertEquals(RoutingMode.Single("only"), p.current())
    }

    @Test
    fun `没选过且有多个 worker 时得到调度模式`() = runTest {
        val p = provider(
            listOf(
                model("cheap", ModelTier.LIGHT),
                model("strong", ModelTier.HEAVY),
            ),
        )

        val mode = p.current()
        assertTrue("应为调度模式，实际 $mode", mode is RoutingMode.Scheduled)
        mode as RoutingMode.Scheduled
        assertEquals("cheap", mode.tierModels[ModelTier.LIGHT])
        assertEquals("strong", mode.tierModels[ModelTier.HEAVY])
    }

    @Test
    fun `单模型兼调度者角色时得到单模型模式`() = runTest {
        // 最常见配置：一个 Key 一个模型干所有事。
        // ⚠️ 误判成调度模式的话，这个用户会永远看到"未配置 XX 档"的提示，
        //    而他没有第二个模型可配。
        val p = provider(
            listOf(model("solo", roles = setOf(ModelRole.WORKER, ModelRole.SCHEDULER))),
        )

        assertEquals(RoutingMode.Single("solo"), p.current())
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 关键边界：没配好时必须给出"用户能照做的一句话"
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `一个模型都没配时抛 NoModelConfigured 而不是别的`() = runTest {
        val p = provider(emptyList())

        try {
            p.current()
            fail("应当抛 GatewayCallException")
        } catch (e: GatewayCallException) {
            // ⚠️ 必须是**这一个**失败类型 —— 它携带的 userMessage
            //    会把用户指向配置页。换成别的（比如 IllegalStateException）
            //    会让 dsh 侧看到"模型服务出错"，用户去查网关是不是坏了。
            assertSame(GatewayFailure.NoModelConfigured, e.failure)
        }
    }

    @Test
    fun `NoModelConfigured 的提示语指向模型页`() = runTest {
        val p = provider(emptyList())

        try {
            p.current()
            fail("应当抛")
        } catch (e: GatewayCallException) {
            val msg = e.failure.userMessage
            // 这句话会被 describeFailure 原样送到 dsh 那边 ——
            // 必须包含"去哪做"和"做什么"，不能只是"没有可用模型"
            assertTrue("提示语应提到「模型」页：$msg", msg.contains("模型"))
            assertTrue("提示语应给出动作：$msg", msg.contains("添加"))
        }
    }

    @Test
    fun `全部模型被禁用时也抛 NoModelConfigured`() = runTest {
        // ⚠️ 若推断不看 `enabled`，会选中一个禁用的模型 → 上游 401 →
        //    用户去查"Key 是不是过期了"，而配置页上那个模型明明是灰的。
        val p = provider(listOf(model("disabled-one", enabled = false)))

        try {
            p.current()
            fail("应当抛 GatewayCallException")
        } catch (e: GatewayCallException) {
            assertSame(GatewayFailure.NoModelConfigured, e.failure)
        }
    }

    @Test
    fun `没有 worker 角色时也抛 NoModelConfigured`() = runTest {
        // 配了模型但角色是空的（用户建了条目、还没配用途）。
        // `ModelSetupSummary.noRole` 就是为这种情况准备的。
        val p = provider(listOf(model("no-role", roles = emptySet())))

        try {
            p.current()
            fail("应当抛")
        } catch (e: GatewayCallException) {
            assertSame(GatewayFailure.NoModelConfigured, e.failure)
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 不缓存：配置改了，下一个请求就该看到
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `配置变化后下一次调用立即反映`() = runTest {
        // ⚠️ 缓存会引入"用户在配置页改了模型，但 dsh 还按旧配置走"
        //    这种看不出原因的诡异行为 —— 用户会以为"改了没生效"。
        val repo = FakeRepo(emptyList())
        val coordinator = ModelRouteCoordinator(repo)
        val p = DshRoutingModeProvider(coordinator)

        // 还没配 → 抛
        try {
            p.current()
            fail("初始应抛")
        } catch (e: GatewayCallException) {
            assertSame(GatewayFailure.NoModelConfigured, e.failure)
        }

        // 用户去配置页加了一个模型
        repo.state.value = listOf(model("just-added"))

        // 下一个请求就该用上，不需要重启任何东西
        assertEquals(RoutingMode.Single("just-added"), p.current())
    }

    @Test
    fun `用户删掉唯一模型后立刻回到 NoModelConfigured`() = runTest {
        val repo = FakeRepo(listOf(model("only")))
        val p = DshRoutingModeProvider(ModelRouteCoordinator(repo))

        assertEquals(RoutingMode.Single("only"), p.current())

        repo.state.value = emptyList()

        try {
            p.current()
            fail("应抛")
        } catch (e: GatewayCallException) {
            assertSame(GatewayFailure.NoModelConfigured, e.failure)
        }
    }

    @Test
    fun `从单模型变成多模型时模式随之切换`() = runTest {
        val repo = FakeRepo(listOf(model("only")))
        val p = DshRoutingModeProvider(ModelRouteCoordinator(repo))

        assertEquals(RoutingMode.Single("only"), p.current())

        repo.state.value = listOf(
            model("cheap", ModelTier.LIGHT),
            model("strong", ModelTier.HEAVY),
        )

        assertTrue(p.current() is RoutingMode.Scheduled)
    }

    // ─────────────────────────────────────────────────────────────
    //  asProvider —— 交给 HttpGatewayServer 的形态
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `asProvider 与 current 行为一致`() = runTest {
        val p = provider(listOf(model("only")))
        assertEquals(p.current(), p.asProvider().invoke())
    }

    @Test
    fun `asProvider 在未配置时同样抛 NoModelConfigured`() = runTest {
        // ⚠️ 这是**真正会被 HttpGatewayServer 调用的那个形态** ——
        //    必须确认它不会把异常吞掉变成别的行为。
        val p = provider(emptyList())

        try {
            p.asProvider().invoke()
            fail("应当抛")
        } catch (e: GatewayCallException) {
            assertSame(GatewayFailure.NoModelConfigured, e.failure)
        }
    }

    @Test
    fun `asProvider 每次调用都重新读配置`() = runTest {
        val repo = FakeRepo(listOf(model("first")))
        val p = DshRoutingModeProvider(ModelRouteCoordinator(repo))
        val fn = p.asProvider()

        assertEquals(RoutingMode.Single("first"), fn())

        repo.state.value = listOf(model("second"))
        assertEquals(RoutingMode.Single("second"), fn())
    }
}
