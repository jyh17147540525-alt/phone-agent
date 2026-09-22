package com.pocketagent.keymgmt

import com.pocketagent.core.database.entity.ModelConfigEntity
import com.pocketagent.core.database.entity.ModelRoleEntity
import com.pocketagent.core.database.entity.ModelTierEntity
import com.pocketagent.modelrouter.DeclaredModel
import com.pocketagent.modelrouter.ModelRole
import com.pocketagent.modelrouter.ModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据库实体 → 调度层模型的映射（`keymgmt/ModelConfigMapping.kt`）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 这一层横跨**两个模块的两套枚举**（`core:database` 的 `ModelTierEntity` /
 * `ModelRoleEntity` 与 `modelrouter` 的 `ModelTier` / `ModelRole`）。
 * 跨模块的枚举转换是"改了这边忘了那边"的经典高发区，而且错误形态
 * 极其安静：
 *
 * · 档位映射错 → 所有任务都走同一个模型，用户以为省了钱
 * · 角色映射错 → "只该判断难度"的调度者开始接任务，或者
 *   "该干活的"被静默跳过
 * · 价格把 null 变成 0.0 → 预算熔断永不触发（**最危险的一条**）
 *
 * 三者都不会崩、不会报错、不会有日志。只能靠测试。
 *
 * ⚠️ 第 3 条其实由 `modelrouter` 的 `ModelConfigMappingTest` 守着，
 *    这里再断言一次是因为**本层是数据入口** —— 若这里先把 null 加工成 0.0，
 *    那边的用例再全绿也拦不住。
 */
class ModelConfigMappingTest {

    private fun entity(
        id: String = "m-1",
        credentialId: String = "c-1",
        modelId: String = "deepseek-chat",
        label: String? = null,
        tier: ModelTierEntity = ModelTierEntity.STANDARD,
        roles: Set<ModelRoleEntity> = setOf(ModelRoleEntity.WORKER),
        inputPriceOverride: Double? = null,
        outputPriceOverride: Double? = null,
        enabled: Boolean = true,
    ) = ModelConfigEntity(
        id = id,
        credentialId = credentialId,
        modelId = modelId,
        label = label,
        tier = tier,
        roleMask = ModelRoleEntity.toMask(roles),
        inputPriceOverride = inputPriceOverride,
        outputPriceOverride = outputPriceOverride,
        enabled = enabled,
        createdAtMillis = 1_700_000_000_000L,
    )

    // ── 档位 ─────────────────────────────────────────────────────

    @Test
    fun `三个档位都能正确转换`() {
        assertEquals(ModelTier.LIGHT, entity(tier = ModelTierEntity.LIGHT).toModelConfig().tier)
        assertEquals(
            ModelTier.STANDARD,
            entity(tier = ModelTierEntity.STANDARD).toModelConfig().tier,
        )
        assertEquals(ModelTier.HEAVY, entity(tier = ModelTierEntity.HEAVY).toModelConfig().tier)
    }

    /**
     * ★ 两个枚举的**取值集合必须完全一致**。
     *
     * 这条不是冗余 —— 它会在"给 `ModelTier` 加了档位但没给
     * `ModelTierEntity` 加"时立刻红。若只依赖上面的逐个断言，
     * 新档位会悄悄漏掉，直到有人写第三条断言时才发现。
     */
    @Test
    fun `两个模块的档位枚举取值一一对应`() {
        val entityTiers = ModelTierEntity.values().map { it.name }.toSet()
        val routerTiers = ModelTier.values().map { it.name }.toSet()

        assertEquals(
            "ModelTierEntity 与 ModelTier 的取值必须一致（见 ModelTierEntity 的类注释）",
            routerTiers,
            entityTiers,
        )
    }

    // ── 角色 ─────────────────────────────────────────────────────

    @Test
    fun `WORKER 角色正确转换`() {
        val config = entity(roles = setOf(ModelRoleEntity.WORKER)).toModelConfig()

        assertEquals(setOf(ModelRole.WORKER), config.roles)
        assertTrue(config.canWork)
        assertTrue(!config.canSchedule)
    }

    @Test
    fun `SCHEDULER 角色正确转换`() {
        val config = entity(roles = setOf(ModelRoleEntity.SCHEDULER)).toModelConfig()

        assertEquals(setOf(ModelRole.SCHEDULER), config.roles)
        assertTrue(config.canSchedule)
        assertTrue("只有调度者角色不该能接任务", !config.canWork)
    }

    @Test
    fun `同时持有两个角色时两个都保留`() {
        val config = entity(
            roles = setOf(ModelRoleEntity.SCHEDULER, ModelRoleEntity.WORKER)
        ).toModelConfig()

        assertEquals(setOf(ModelRole.SCHEDULER, ModelRole.WORKER), config.roles)
        assertTrue(config.canWork)
        assertTrue(config.canSchedule)
    }

    /**
     * ★ 空角色集合必须**原样传下去**，不能兜底成 WORKER。
     *
     * 兜底的后果：`ModelSetupSummary.noRole` 永远为空 →
     * 界面上那条"有模型还没设置用途"的提示永远不出现 →
     * 用户看到一个"已启用"但永远不被调度的模型，无从排查。
     */
    @Test
    fun `没有角色时保持空集而不兜底`() {
        val config = entity(roles = emptySet()).toModelConfig()

        assertTrue("空角色必须如实传递", config.roles.isEmpty())
        assertTrue(!config.canWork)
        assertTrue(!config.canSchedule)
    }

    @Test
    fun `两个模块的角色枚举位值必须一致`() {
        // 若哪边加了第三种角色，这条会红 —— 提示需要同步
        assertEquals(2, ModelRoleEntity.values().size)
        assertEquals(2, ModelRole.values().size)
        assertEquals(1, ModelRoleEntity.SCHEDULER.bit)
        assertEquals(2, ModelRoleEntity.WORKER.bit)
    }

    // ── 价格 ─────────────────────────────────────────────────────

    /**
     * ★★ 本文件最重要的一条。
     *
     * 用户配置没填价格 + Provider 也没声明价格 → 结果**必须**是 null。
     *
     * 若变成 0.0，`estimatedCost` 会算出 0 → 预算熔断永不触发 →
     * 用户以为在省钱，实际在烧钱，而**界面上一切正常**。
     */
    @Test
    fun `两边都没有价格时是 null 而不是 0`() {
        val config = entity(inputPriceOverride = null, outputPriceOverride = null)
            .toModelConfig(declared = null)

        assertNull(config.inputPricePerMillion)
        assertNull(config.outputPricePerMillion)
        assertNull("价格未知时必须返回 null，绝不能算成 0 成本", config.estimatedCost(1000, 500))
    }

    @Test
    fun `用户覆盖价格优先于声明价格`() {
        val config = entity(inputPriceOverride = 99.0, outputPriceOverride = 88.0)
            .toModelConfig(DeclaredModel("X", 1.0, 2.0))

        assertEquals(99.0, config.inputPricePerMillion!!, 1e-9)
        assertEquals(88.0, config.outputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `用户没填时回落到声明价格`() {
        val config = entity().toModelConfig(DeclaredModel("X", 1.5, 2.5))

        assertEquals(1.5, config.inputPricePerMillion!!, 1e-9)
        assertEquals(2.5, config.outputPricePerMillion!!, 1e-9)
    }

    /** 0.0 是合法价格（本地模型免费），不能被当成"没填"而回落 */
    @Test
    fun `用户填 0 表示免费而不是未填`() {
        val config = entity(inputPriceOverride = 0.0, outputPriceOverride = 0.0)
            .toModelConfig(DeclaredModel("X", 5.0, 5.0))

        assertEquals(0.0, config.inputPricePerMillion!!, 1e-9)
        assertEquals(0.0, config.outputPricePerMillion!!, 1e-9)
        assertNotNull(config.estimatedCost(1000, 500))
    }

    // ── 展示名 ───────────────────────────────────────────────────

    @Test
    fun `有声明时展示名用声明的`() {
        val config = entity().toModelConfig(DeclaredModel("DeepSeek Chat", 1.0, 2.0))

        assertEquals("DeepSeek Chat", config.modelDisplayName)
    }

    /**
     * 查不到声明时**不报错** —— 用户可能配了自建端点的模型名，
     * 或厂商下架了它。裸 modelId 至少还能认出是自己配的。
     */
    @Test
    fun `没有声明时展示名回落到 modelId 而不报错`() {
        val config = entity(modelId = "my-custom-model").toModelConfig(declared = null)

        assertEquals("my-custom-model", config.modelDisplayName)
    }

    @Test
    fun `声明展示名为空时回落到 modelId`() {
        val config = entity(modelId = "abc").toModelConfig(DeclaredModel("   ", 1.0, 2.0))

        assertEquals("abc", config.modelDisplayName)
    }

    /**
     * ⚠️ 展示名**不吃用户的 label**。
     *
     * `modelDisplayName` 回答"模型自己叫什么"，`effectiveLabel` 才是
     * "用户叫它什么"。若这里拿 label 兜底，用户清空 label 后会看到
     * 展示名变成旧 label —— 一个他刚刚删掉的名字。
     */
    @Test
    fun `展示名不会回落到用户的 label`() {
        val config = entity(label = "我的主力模型").toModelConfig(declared = null)

        assertEquals("deepseek-chat", config.modelDisplayName)
        assertEquals("我的主力模型", config.effectiveLabel)
    }

    @Test
    fun `label 为空时 effectiveLabel 回落到展示名`() {
        val config = entity(label = null).toModelConfig(DeclaredModel("DS Chat", 1.0, 2.0))

        assertEquals("DS Chat", config.effectiveLabel)
    }

    // ── 其他字段透传 ─────────────────────────────────────────────

    @Test
    fun `基本字段原样透传`() {
        val config = entity(
            id = "cfg-9",
            credentialId = "cred-7",
            modelId = "gpt-4o-mini",
            enabled = false,
        ).toModelConfig()

        assertEquals("cfg-9", config.id)
        assertEquals("cred-7", config.credentialId)
        assertEquals("gpt-4o-mini", config.modelId)
        assertEquals(false, config.enabled)
    }

    // ── 批量 ─────────────────────────────────────────────────────

    @Test
    fun `批量转换保留输入顺序`() {
        val configs = listOf(
            entity(id = "a", modelId = "m-a"),
            entity(id = "b", modelId = "m-b"),
            entity(id = "c", modelId = "m-c"),
        ).toModelConfigs()

        assertEquals(listOf("a", "b", "c"), configs.map { it.id })
    }

    @Test
    fun `批量转换按 modelId 匹配声明`() {
        val configs = listOf(
            entity(id = "a", modelId = "m-a"),
            entity(id = "b", modelId = "m-b"),
        ).toModelConfigs(
            declaredById = mapOf("m-b" to DeclaredModel("B 模型", 3.0, 4.0))
        )

        // a 没有声明 → 回落到裸 id，价格 null
        assertEquals("m-a", configs[0].modelDisplayName)
        assertNull(configs[0].inputPricePerMillion)

        // b 有声明 → 用声明的
        assertEquals("B 模型", configs[1].modelDisplayName)
        assertEquals(3.0, configs[1].inputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `空列表转换得到空列表`() {
        assertEquals(emptyList<Any>(), emptyList<ModelConfigEntity>().toModelConfigs())
    }

    /**
     * 多条配置引用了同一个 modelId 时，它们共享同一份声明 ——
     * 但各自的**用户覆盖价格互不影响**。
     *
     * 这是同一模型走不同渠道（价差可达十倍）的常见场景。
     */
    @Test
    fun `同一 modelId 的两条配置各自保留自己的价格覆盖`() {
        val configs = listOf(
            entity(id = "cheap-channel", modelId = "same", inputPriceOverride = 0.5),
            entity(id = "official", modelId = "same", inputPriceOverride = 5.0),
        ).toModelConfigs(mapOf("same" to DeclaredModel("Same", 1.0, 1.0)))

        assertEquals(0.5, configs[0].inputPricePerMillion!!, 1e-9)
        assertEquals(5.0, configs[1].inputPricePerMillion!!, 1e-9)
    }
}
