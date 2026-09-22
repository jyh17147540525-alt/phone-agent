package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 路由器。
 *
 * ⚠️ 这组测试守的是一条**不报错的失败**：
 *    路由选错了模型，程序不会崩、不会抛异常，只会"静默地走了贵的"或"任务办砸"。
 *    这类 bug 读代码发现不了，必须有测试钉住。
 */
class ModelRouterTest {

    private val router = ModelRouter()

    // ── 测试夹具 ─────────────────────────────────────────────────

    private fun model(
        id: String,
        tier: ModelTier,
        enabled: Boolean = true,
        roles: Set<ModelRole> = setOf(ModelRole.WORKER),
    ) = ModelConfig(
        id = id,
        label = id,
        credentialId = "cred-$id",
        modelId = "model-$id",
        modelDisplayName = id,
        tier = tier,
        roles = roles,
        enabled = enabled,
    )

    private val light = model("cheap", ModelTier.LIGHT)
    private val standard = model("mid", ModelTier.STANDARD)
    private val heavy = model("strong", ModelTier.HEAVY)
    private val scheduler = model(
        "router",
        ModelTier.LIGHT,
        roles = setOf(ModelRole.SCHEDULER, ModelRole.WORKER),
    )

    private val allModels = listOf(light, standard, heavy, scheduler)

    // ── 单模型模式 ───────────────────────────────────────────────

    @Test
    fun `单模型模式总是返回指定的模型`() {
        val result = router.route(
            mode = RoutingMode.Single(standard.id),
            models = allModels,
            instruction = "先做这个，然后做那个，最后总结一下复杂的内容",
        )

        assertTrue(result is RoutingOutcome.Success)
        assertEquals(standard.id, result.decisionOrNull()?.modelConfigId)
    }

    @Test
    fun `单模型模式不做难度评估`() {
        val result = router.route(
            mode = RoutingMode.Single(standard.id),
            models = allModels,
            instruction = "先做这个，然后做那个，最后分析并总结",
        )

        // 依据里不应出现难度评估的痕迹 —— 评估是白费 CPU，影响耗电。
        // 这正是"减少对用户影响"落到代码上的一处：不可选项也算，是纯浪费。
        //
        // ⚠️ 注意 List.contains 是**全等**匹配不是子串匹配，
        //    所以这里断言的是"恰好只有这一条依据"。
        val reasons = result.decisionOrNull()!!.reasons
        assertEquals(listOf("当前为单模型模式"), reasons)
    }

    @Test
    fun `单模型模式指定不存在的模型时返回 ModelNotFound`() {
        val result = router.route(
            mode = RoutingMode.Single("ghost"),
            models = allModels,
            instruction = "任何指令",
        )

        assertTrue(result is RoutingOutcome.Failure)
        assertTrue(result.failureOrNull() is RoutingFailure.ModelNotFound)
    }

    @Test
    fun `单模型模式指定被禁用的模型时返回 ModelDisabled`() {
        val disabled = model("off", ModelTier.STANDARD, enabled = false)

        val result = router.route(
            mode = RoutingMode.Single(disabled.id),
            models = allModels + disabled,
            instruction = "任何指令",
        )

        assertTrue(result.failureOrNull() is RoutingFailure.ModelDisabled)
    }

    // ── 调度模式：正常路径 ───────────────────────────────────────

    @Test
    fun `调度模式按判定档位精确匹配`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(
                ModelTier.LIGHT to light.id,
                ModelTier.STANDARD to standard.id,
                ModelTier.HEAVY to heavy.id,
            ),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "随便",
            tierOverride = ModelTier.HEAVY,
        )

        assertEquals(heavy.id, result.decisionOrNull()?.modelConfigId)
        assertFalse(result.decisionOrNull()!!.degraded)
    }

    @Test
    fun `调度模型的判定优先于本地评估`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(
                ModelTier.LIGHT to light.id,
                ModelTier.HEAVY to heavy.id,
            ),
        )

        // 文本本身有复杂特征（本地会判 HEAVY），但调度模型说是 LIGHT
        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "先分析，然后比较，最后总结并给出建议，涉及微信和支付宝",
            tierOverride = ModelTier.LIGHT,
        )

        assertEquals(light.id, result.decisionOrNull()?.modelConfigId)
        assertTrue(result.decisionOrNull()!!.summary.contains("调度模型判为"))
    }

    // ── 调度模式：本地兜底 ───────────────────────────────────────

    @Test
    fun `没有调度判定时用本地评估兜底`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(
                ModelTier.LIGHT to light.id,
                ModelTier.HEAVY to heavy.id,
            ),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "先打开支付宝，然后打开微信，再打开淘宝，最后分析并总结趋势",
            tierOverride = null,
        )

        assertEquals(heavy.id, result.decisionOrNull()?.modelConfigId)
        // 必须标明这次是本地判的 —— 用户要知道调度模型没参与
        assertTrue(result.decisionOrNull()!!.summary.contains("本地评估"))
    }

    @Test
    fun `本地兜底判为轻量时走轻量档`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(
                ModelTier.LIGHT to light.id,
                ModelTier.HEAVY to heavy.id,
            ),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "打开微信",
            tierOverride = null,
        )

        assertEquals(light.id, result.decisionOrNull()?.modelConfigId)
    }

    // ── 缺档位的降级 ─────────────────────────────────────────────

    @Test
    fun `任务判为重型但没配重型时降级到最高档并标记 degraded`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(
                ModelTier.LIGHT to light.id,
                ModelTier.STANDARD to standard.id,
            ),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "随便",
            tierOverride = ModelTier.HEAVY,
        )

        assertEquals(standard.id, result.decisionOrNull()?.modelConfigId)
        assertTrue(result.decisionOrNull()!!.degraded)
    }

    @Test
    fun `任务判为轻量但只配了重型时用重型`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(ModelTier.HEAVY to heavy.id),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "随便",
            tierOverride = ModelTier.LIGHT,
        )

        assertEquals(heavy.id, result.decisionOrNull()?.modelConfigId)
        assertTrue(result.decisionOrNull()!!.degraded)
    }

    @Test
    fun `任务判为均衡但没配均衡时优先升档而不是降档`() {
        // 理由：办成事优先于省钱。均衡任务降级到轻量可能办砸。
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(
                ModelTier.LIGHT to light.id,
                ModelTier.HEAVY to heavy.id,
            ),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "随便",
            tierOverride = ModelTier.STANDARD,
        )

        assertEquals(heavy.id, result.decisionOrNull()?.modelConfigId)
        assertTrue(result.decisionOrNull()!!.degraded)
    }

    @Test
    fun `任务判为均衡 只有轻量档时才降到轻量`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(ModelTier.LIGHT to light.id),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "随便",
            tierOverride = ModelTier.STANDARD,
        )

        assertEquals(light.id, result.decisionOrNull()?.modelConfigId)
        assertTrue(result.decisionOrNull()!!.degraded)
    }

    @Test
    fun `降级的依据里说明了缺哪个档`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(ModelTier.LIGHT to light.id),
        )

        val result = router.route(
            mode = mode,
            models = allModels,
            instruction = "随便",
            tierOverride = ModelTier.HEAVY,
        )

        assertTrue(result.decisionOrNull()!!.summary.contains("未配置"))
        assertTrue(result.decisionOrNull()!!.summary.contains("重型"))
    }

    // ── 失败路径 ─────────────────────────────────────────────────

    @Test
    fun `没有任何工作模型时返回 NoWorkerModels`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = emptyMap(),
        )

        val result = router.route(mode, allModels, "随便")

        assertTrue(result.failureOrNull() is RoutingFailure.NoWorkerModels)
    }

    @Test
    fun `映射里的模型全被禁用时返回 NoModelsConfigured`() {
        val disabledLight = model("cheap2", ModelTier.LIGHT, enabled = false)
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(ModelTier.LIGHT to disabledLight.id),
        )

        val result = router.route(mode, allModels + disabledLight, "随便")

        assertTrue(result.failureOrNull() is RoutingFailure.NoModelsConfigured)
    }

    @Test
    fun `映射指向不存在的模型时视为不可用而不是崩溃`() {
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = scheduler.id,
            tierModels = mapOf(
                ModelTier.LIGHT to "ghost",
                ModelTier.HEAVY to heavy.id,
            ),
        )

        val result = router.route(mode, allModels, "随便", tierOverride = ModelTier.HEAVY)

        // ghost 被丢弃，HEAVY 仍然可用 —— 不该因为一条坏映射就整体失败
        assertEquals(heavy.id, result.decisionOrNull()?.modelConfigId)
    }

    @Test
    fun `只有 SCHEDULER 角色没有 WORKER 角色的模型不能被调度`() {
        val onlyScheduler = model(
            "pure-router",
            ModelTier.LIGHT,
            roles = setOf(ModelRole.SCHEDULER),
        )
        val mode = RoutingMode.Scheduled(
            schedulerModelConfigId = onlyScheduler.id,
            tierModels = mapOf(ModelTier.LIGHT to onlyScheduler.id),
        )

        val result = router.route(mode, allModels + onlyScheduler, "随便")

        // 模型存在，只是角色不对 → NoModelsConfigured（"配了但用不了"），
        // 而不是 NoWorkerModels（"没配"）。两者的用户动作不同：
        // 前者去改角色设置，后者去挑模型。
        assertTrue(result.failureOrNull() is RoutingFailure.NoModelsConfigured)
    }

    // ── 调度者自身 ───────────────────────────────────────────────

    @Test
    fun `调度者可以是轻量档 因为分类不需要强模型`() {
        // 这条钉住一个反直觉但重要的设计：调度者用便宜模型是**正确的**，
        // 不是配置错误。避免将来有人"顺手优化"成用最强模型当调度者。
        assertEquals(ModelTier.LIGHT, scheduler.tier)
        assertTrue(scheduler.canSchedule)
        assertTrue(scheduler.canWork)
    }
}
