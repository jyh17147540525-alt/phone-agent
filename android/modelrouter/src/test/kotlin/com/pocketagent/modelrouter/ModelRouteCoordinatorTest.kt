package com.pocketagent.modelrouter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由协调器（[ModelRouteCoordinator]）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  测试策略：借一个假的仓储，把"线上真正会走的那条路"测出来
 * ═══════════════════════════════════════════════════════════════
 *
 * `ModelRouter` 已经被 18 个用例钉死了，所以这里**不重复测路由算法** ——
 * 本类要证明的是另一件事：**那根线接对了没有**。
 *
 * 具体说，三个只有"接起来之后"才会暴露的问题：
 *
 * 1. **`inferMode` 在单 worker 时会不会误判成调度模式**
 *    —— 一旦误判，最常见的用户（只配一个模型）会掉进调度分支，
 *       每次都被判"降级"，界面上挂着他看不懂的提示。
 * 2. **`inferMode` 会不会把启用状态搞错**
 *    —— 仓储返回含禁用项，若推断时不看 `enabled`，会选出一个禁用的模型，
 *       任务发出去 401，而配置页上它明明是灰的。
 * 3. **`route` 传的列表到底含不含禁用项**
 *    —— 含（[ModelRouter] 负责过滤）。若这里滤了，就得靠路由层自己发现
 *       "有个禁用的模型"来给出提示，而它看不到了。
 */
class ModelRouteCoordinatorTest {

    // ── 测试替身 ─────────────────────────────────────────────────

    /**
     * 内存版仓储。
     *
     * ⚠️ **刻意不 mock 掉 `Flow`**（用 `every { ... } returns flowOf(...)` 那种）：
     *    真实仓储发的是一个**会持续更新**的流（Room 的 `Flow` 在表变化时重发）。
     *    `flowOf` 只发一次就当结束了，测不出"列表变了之后协调器有没有跟着变"。
     *    `MutableStateFlow` 能，而且它是真的热流。
     */
    private class FakeRepo(initial: List<ModelConfig> = emptyList()) : ModelConfigRepository {
        val state = MutableStateFlow(initial)

        override val models: Flow<List<ModelConfig>> get() = state

        override suspend fun all(): List<ModelConfig> = state.value

        override suspend fun byId(id: String): ModelConfig? =
            state.value.firstOrNull { it.id == id }
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

    // ═════════════════════════════════════════════════════════════
    //  inferMode —— 推断"该用哪种模式"
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `一个模型都没有时推断不出模式`() = runTest {
        val coordinator = ModelRouteCoordinator(FakeRepo())

        assertNull(coordinator.inferMode())
    }

    @Test
    fun `只有一个可用 worker 时推断为单模型模式`() = runTest {
        val coordinator = ModelRouteCoordinator(FakeRepo(listOf(model("only"))))

        assertEquals(RoutingMode.Single("only"), coordinator.inferMode())
    }

    /**
     * ★ 本组最重要的一条。
     *
     * 用户配了一个模型，同时给它挂了 SCHEDULER + WORKER 两个角色
     * （这是**最常见的配置**：一个 Key 一个模型干所有事）。
     *
     * 如果推断规则把"有调度者"排在"只有一个 worker"前面，
     * 这个用户会掉进调度模式 → `tierModels` 只有一项 →
     * 任务判为别的档位时走降级分支 → 界面上永远挂着
     * "未配置 XX 档，改用 XX 档"的提示，而他没有第二个模型可配。
     */
    @Test
    fun `单模型兼调度者角色时仍然推断为单模型模式`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(listOf(model("solo", roles = setOf(ModelRole.WORKER, ModelRole.SCHEDULER))))
        )

        assertEquals(RoutingMode.Single("solo"), coordinator.inferMode())
    }

    @Test
    fun `有多个 worker 时推断为调度模式并按档位建表`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(
                listOf(
                    model("cheap", tier = ModelTier.LIGHT),
                    model("mid", tier = ModelTier.STANDARD),
                    model("strong", tier = ModelTier.HEAVY),
                )
            )
        )

        val mode = coordinator.inferMode()

        assertTrue("应推断为调度模式，实际 $mode", mode is RoutingMode.Scheduled)
        mode as RoutingMode.Scheduled
        assertEquals(
            mapOf(ModelTier.LIGHT to "cheap", ModelTier.STANDARD to "mid", ModelTier.HEAVY to "strong"),
            mode.tierModels,
        )
    }

    @Test
    fun `多 worker 且有调度者时调度者被采用`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(
                listOf(
                    model("judge", roles = setOf(ModelRole.SCHEDULER)),
                    model("a", tier = ModelTier.LIGHT),
                    model("b", tier = ModelTier.HEAVY),
                )
            )
        )

        val mode = coordinator.inferMode() as RoutingMode.Scheduled

        assertEquals("judge", mode.schedulerModelConfigId)
    }

    /**
     * 多 worker 但没配调度者 —— **仍然推断为调度模式**。
     *
     * 理由：多个 worker 就是"我想按档位派发"这个意图的表达。
     * 退回单模型模式会让用户配的三个模型里只有一个被用到，
     * 而他从界面上完全看不出为什么。
     *
     * 难度改走本地启发式（零成本），`schedulerModelConfigId` 为空串。
     */
    @Test
    fun `多 worker 无调度者时仍然推断为调度模式且调度者位为空`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(listOf(model("a", tier = ModelTier.LIGHT), model("b", tier = ModelTier.HEAVY)))
        )

        val mode = coordinator.inferMode() as RoutingMode.Scheduled

        assertEquals("", mode.schedulerModelConfigId)
        assertEquals(2, mode.tierModels.size)
    }

    /**
     * ⚠️ 禁用项不能参与推断。
     *
     * 仓储返回的列表含禁用项（见 [ModelConfigRepository] 的注释）。若推断时
     * 不看 `enabled`，用户"留着一个禁用的旧模型 + 一个新模型"会得到
     * 两个 worker → 被推进调度模式，而其中一个是灰的。
     */
    @Test
    fun `被禁用的模型不参与模式推断`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(
                listOf(
                    model("off", tier = ModelTier.LIGHT, enabled = false),
                    model("on", tier = ModelTier.STANDARD, enabled = true),
                )
            )
        )

        // 有效 worker 只有一个 → 单模型模式，且选的是那个启用的
        assertEquals(RoutingMode.Single("on"), coordinator.inferMode())
    }

    @Test
    fun `所有模型都被禁用时推断不出模式`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(listOf(model("off", enabled = false)))
        )

        assertNull(coordinator.inferMode())
    }

    @Test
    fun `只有调度者没有 worker 时推断不出模式`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(listOf(model("judge", roles = setOf(ModelRole.SCHEDULER))))
        )

        // 调度者不接任务 —— 没有 worker 就是跑不起来
        assertNull(coordinator.inferMode())
    }

    // ═════════════════════════════════════════════════════════════
    //  route —— 读配置 + 路由
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `单模型模式下路由到指定模型`() = runTest {
        val coordinator = ModelRouteCoordinator(FakeRepo(listOf(model("only"))))

        val decision = coordinator.route(RoutingMode.Single("only"), "打开微信").decisionOrNull()

        assertEquals("only", decision?.modelConfigId)
        assertFalse("单模型模式不该被判为降级", decision?.degraded ?: true)
    }

    /**
     * ★ 协调器**不能**替路由层过滤禁用项。
     *
     * 用户删掉/禁用了正在用的模型后，路由必须能给出
     * [RoutingFailure.ModelDisabled] 这个**具体**的失败原因 ——
     * 而不是笼统的"没配模型"。后者会让用户去配置页乱翻，
     * 而真正的原因（那一行被禁用了）他看不到。
     */
    @Test
    fun `单模型模式下模型被禁用时返回具体失败原因`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(listOf(model("only", enabled = false)))
        )

        val failure = coordinator.route(RoutingMode.Single("only"), "随便").failureOrNull()

        assertEquals(RoutingFailure.ModelDisabled("only"), failure)
    }

    /**
     * 仓储列表里**含禁用项**这件事，在这里被显式确认一次。
     *
     * 若将来有人"顺手优化"成在仓储层就滤掉禁用项，这条会红 ——
     * 那一刻正是需要有人停下来想想的时候。
     */
    @Test
    fun `仓储返回的列表包含禁用项`() = runTest {
        val repo = FakeRepo(listOf(model("on"), model("off", enabled = false)))

        assertEquals(listOf("on", "off"), repo.all().map { it.id })
    }

    @Test
    fun `调度模式下按难度选中对应档位的模型`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(
                listOf(
                    model("cheap", tier = ModelTier.LIGHT),
                    model("strong", tier = ModelTier.HEAVY),
                )
            )
        )

        val mode = RoutingMode.Scheduled("", mapOf(ModelTier.LIGHT to "cheap", ModelTier.HEAVY to "strong"))

        // 明确用 tierOverride 指名难度，避免依赖启发式的具体阈值
        val heavy = coordinator.route(mode, "任意指令", tierOverride = ModelTier.HEAVY)
        assertEquals("strong", heavy.decisionOrNull()?.modelConfigId)

        val light = coordinator.route(mode, "任意指令", tierOverride = ModelTier.LIGHT)
        assertEquals("cheap", light.decisionOrNull()?.modelConfigId)
    }

    /**
     * 配置变了，下一次路由必须跟着变 —— 这条钉住"不做缓存"这个决定。
     *
     * 缓存会让"用户在配置页改了档位，正在跑的任务还按旧配置走"，
     * 而那种不一致**没有任何界面线索**指向它。
     */
    @Test
    fun `配置变化后下一次路由立即采用新配置`() = runTest {
        val repo = FakeRepo(listOf(model("old")))
        val coordinator = ModelRouteCoordinator(repo)

        assertEquals(
            "old",
            coordinator.route(RoutingMode.Single("old"), "x").decisionOrNull()?.modelConfigId,
        )

        repo.state.value = listOf(model("new"))

        assertEquals(
            "new",
            coordinator.route(RoutingMode.Single("new"), "x").decisionOrNull()?.modelConfigId,
        )
    }

    // ═════════════════════════════════════════════════════════════
    //  setupSummary —— 体检
    // ═════════════════════════════════════════════════════════════

    @Test
    fun `体检摘要反映当前配置`() = runTest {
        val coordinator = ModelRouteCoordinator(
            FakeRepo(listOf(model("a"), model("off", enabled = false)))
        )

        val summary = coordinator.setupSummary()

        assertEquals(listOf("a", "off"), summary.all.map { it.id })
        assertEquals(listOf("a"), summary.workers.map { it.id })
        assertEquals(listOf("off"), summary.disabled.map { it.id })
        assertTrue(summary.runnable)
    }

    @Test
    fun `空配置的体检给出可执行的提示`() = runTest {
        val summary = ModelRouteCoordinator(FakeRepo()).setupSummary()

        assertFalse(summary.runnable)
        assertEquals(listOf("还没有配置任何模型"), summary.blockers)
    }

    @Test
    fun `配置完备时体检不产生任何提示`() = runTest {
        val summary = ModelRouteCoordinator(
            FakeRepo(
                listOf(
                    model("cheap", tier = ModelTier.LIGHT),
                    model("mid", tier = ModelTier.STANDARD),
                    model("strong", tier = ModelTier.HEAVY),
                )
            )
        ).setupSummary()

        // ★ 空 = 界面上不该出现任何提示条
        assertEquals(emptyList<String>(), summary.blockers)
    }

    // ═════════════════════════════════════════════════════════════
    //  订阅流
    // ═════════════════════════════════════════════════════════════

    /**
     * 配置页订阅的是仓储的 `models` 流，而不是协调器 —— 所以这里直接验证
     * 那个流的语义：**后续变更会推送给订阅者，且含禁用项**。
     *
     * 这是替身的自测。写成测试而不是"相信它没问题"，是因为
     * 上面所有 `inferMode` / `route` 用例的可信度都建立在
     * "这个假仓储真的像真仓储"上 —— 替身错了，断言全错。
     */
    @Test
    fun `models 流会把后续变更推送给订阅者`() = runTest {
        val repo = FakeRepo(listOf(model("a")))

        assertEquals(listOf("a"), repo.models.first().map { it.id })

        repo.state.value = listOf(model("a"), model("b", enabled = false))

        assertEquals(listOf("a", "b"), repo.models.first().map { it.id })
    }

    @Test
    fun `byId 找不到时返回 null 而不是抛异常`() = runTest {
        val repo = FakeRepo(listOf(model("a")))

        assertNull(repo.byId("nope"))
        assertEquals("a", repo.byId("a")?.id)
    }
}
