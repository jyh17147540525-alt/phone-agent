package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ModelConfigSource` → `ModelConfig` 的映射。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这个文件值得单独存在
 * ═══════════════════════════════════════════════════════════════
 *
 * 映射函数是那种"看着像胶水、实际是地雷"的代码。它的每一次错误
 * 都符合本项目最贵的那类失效模式：**不报错、不崩溃，只是安静地做错一件事**。
 *
 * 尤其是价格：填错一级回落，用户界面上一切正常，
 * 只有预算熔断悄悄失效了 —— 而那时他已经在烧钱。
 *
 * 所以这里对每一条规则都写"反例断言"（"不能是 X"），
 * 而不只是"等于 Y"。**恒过的测试比没有测试更糟。**
 */
class ModelConfigMappingTest {

    /** 测试用的最小实现，顺便把"接口是否好实现"这件事也一并验证了 */
    private class FakeSource(
        override val id: String = "mc-1",
        override val credentialId: String = "cred-1",
        override val modelId: String = "deepseek-chat",
        override val label: String? = null,
        override val tier: ModelTier = ModelTier.STANDARD,
        override val roles: Set<ModelRole> = setOf(ModelRole.WORKER),
        override val inputPriceOverride: Double? = null,
        override val outputPriceOverride: Double? = null,
        override val enabled: Boolean = true,
    ) : ModelConfigSource

    // ── 字段直通 ─────────────────────────────────────────────────

    @Test
    fun `基本字段原样传下去`() {
        val config = FakeSource(
            id = "mc-42",
            credentialId = "cred-7",
            modelId = "gpt-4o-mini",
            label = "我的便宜货",
            tier = ModelTier.LIGHT,
            roles = setOf(ModelRole.WORKER, ModelRole.SCHEDULER),
            enabled = false,
        ).toModelConfig()

        assertEquals("mc-42", config.id)
        assertEquals("cred-7", config.credentialId)
        assertEquals("gpt-4o-mini", config.modelId)
        assertEquals("我的便宜货", config.label)
        assertEquals(ModelTier.LIGHT, config.tier)
        assertEquals(setOf(ModelRole.WORKER, ModelRole.SCHEDULER), config.roles)
        assertEquals(false, config.enabled)
    }

    @Test
    fun `label 为 null 时映射成空串而不是 modelId`() {
        // ⚠️ 这条看似吹毛求疵，但它守住了一个分工：
        //    label = "用户叫它什么"，modelDisplayName = "它自己叫什么"。
        //    若这里拿 modelId 兜底，用户清空 label 后展示名会变成
        //    "旧 label 与 modelId 混合"的怪东西。
        val config = FakeSource(label = null, modelId = "deepseek-chat").toModelConfig()

        assertEquals("", config.label)
        // 展示名该是 modelId（因为没声明）—— 由 effectiveLabel 兜底
        assertEquals("deepseek-chat", config.effectiveLabel)
    }

    @Test
    fun `label 为空白串时 effectiveLabel 回落到展示名`() {
        val config = FakeSource(label = "   ", modelId = "gpt-4o").toModelConfig()

        assertEquals("   ", config.label)
        assertEquals("gpt-4o", config.effectiveLabel)
    }

    // ── 价格回落：三级链路 ───────────────────────────────────────

    @Test
    fun `用户覆盖价格优先于 Provider 声明`() {
        val config = FakeSource(
            inputPriceOverride = 0.1,
            outputPriceOverride = 0.2,
        ).toModelConfig(
            DeclaredModel(
                displayName = "DeepSeek Chat",
                inputPricePerMillion = 9.9,
                outputPricePerMillion = 9.9,
            )
        )

        assertEquals(0.1, config.inputPricePerMillion!!, 1e-9)
        assertEquals(0.2, config.outputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `用户没填时用 Provider 声明的价格`() {
        val config = FakeSource(
            inputPriceOverride = null,
            outputPriceOverride = null,
        ).toModelConfig(
            DeclaredModel(
                displayName = "DeepSeek Chat",
                inputPricePerMillion = 0.27,
                outputPricePerMillion = 1.1,
            )
        )

        assertEquals(0.27, config.inputPricePerMillion!!, 1e-9)
        assertEquals(1.1, config.outputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `用户填 0 是合法的免费声明而不是没填`() {
        // ⚠️ 这条是 0.0 与 null 的分水岭。若实现用 `?:` 之外的
        //    任何"真值判断"（如 `if (override != 0.0)`），
        //    本地 Ollama 用户的 0 成本会被声明的价格覆盖掉。
        val config = FakeSource(
            inputPriceOverride = 0.0,
            outputPriceOverride = 0.0,
        ).toModelConfig(
            DeclaredModel("Local Llama", inputPricePerMillion = 5.0, outputPricePerMillion = 5.0)
        )

        assertEquals(0.0, config.inputPricePerMillion!!, 1e-9)
        assertEquals(0.0, config.outputPricePerMillion!!, 1e-9)
    }

    @Test
    fun `两边都没有价格时是 null 而不是 0`() {
        // ⚠️⚠️ 本文件最重要的一条。
        //    null → estimatedCost 返回 null → 预算熔断知道"算不出来"，会提示用户
        //    0.0  → estimatedCost 返回 0    → 熔断以为免费，永远不触发
        //    后者是用户**自己发现不了**的烧钱故障。
        val config = FakeSource(
            inputPriceOverride = null,
            outputPriceOverride = null,
        ).toModelConfig(DeclaredModel("Mystery Model"))

        assertNull(config.inputPricePerMillion)
        assertNull(config.outputPricePerMillion)
        assertNull("缺价格时必须返回 null，不能是 0.0", config.estimatedCost(1_000, 500))
    }

    @Test
    fun `用户只填输入价时输出价仍可回落到声明值`() {
        // 两个价格是**独立**回落的两条链，不是捆在一起的
        val config = FakeSource(
            inputPriceOverride = 1.0,
            outputPriceOverride = null,
        ).toModelConfig(
            DeclaredModel("X", inputPricePerMillion = 9.0, outputPricePerMillion = 3.0)
        )

        assertEquals(1.0, config.inputPricePerMillion!!, 1e-9)
        assertEquals(3.0, config.outputPricePerMillion!!, 1e-9)
    }

    // ── 展示名回落 ───────────────────────────────────────────────

    @Test
    fun `有声明时用声明的展示名`() {
        val config = FakeSource(modelId = "deepseek-chat")
            .toModelConfig(DeclaredModel("DeepSeek Chat"))

        assertEquals("DeepSeek Chat", config.modelDisplayName)
    }

    @Test
    fun `声明缺失时展示名回落到 modelId`() {
        // 场景真实存在：用户配完之后厂商下架了模型。
        // 此时**不能报错** —— 他的配置还在，只是拿不到展示名与价格了。
        val config = FakeSource(modelId = "my-custom-endpoint-model").toModelConfig(null)

        assertEquals("my-custom-endpoint-model", config.modelDisplayName)
    }

    @Test
    fun `声明的展示名是空白串时回落到 modelId`() {
        // Provider 返回 `"display_name": ""` 的情况真实出现过
        val config = FakeSource(modelId = "gpt-4o").toModelConfig(DeclaredModel("   "))

        assertEquals("gpt-4o", config.modelDisplayName)
    }

    @Test
    fun `展示名不会回落到用户的 label`() {
        // 若实现写成 `declared?.displayName ?: label ?: modelId`，
        // 用户在界面上把 label 改掉之后，展示名会跟着乱跳。
        val config = FakeSource(modelId = "gpt-4o", label = "我的最爱").toModelConfig(null)

        assertEquals("gpt-4o", config.modelDisplayName)
        assertEquals("我的最爱", config.effectiveLabel)
    }

    // ── 批量转换 ─────────────────────────────────────────────────

    @Test
    fun `批量转换按 modelId 匹配声明`() {
        val sources = listOf(
            FakeSource(id = "a", modelId = "cheap-model"),
            FakeSource(id = "b", modelId = "strong-model"),
        )
        val declared = mapOf(
            "cheap-model" to DeclaredModel("便宜货", inputPricePerMillion = 0.1),
            "strong-model" to DeclaredModel("强模型", inputPricePerMillion = 5.0),
        )

        val configs = sources.toModelConfigs(declared)

        assertEquals(2, configs.size)
        assertEquals("便宜货", configs[0].modelDisplayName)
        assertEquals("强模型", configs[1].modelDisplayName)
    }

    @Test
    fun `批量转换保留原列表顺序`() {
        // ⚠️ 界面按"用户添加顺序"排列。若实现内部用了 associateBy().values，
        //    顺序会丢 —— 那是"不报错、只是安静地把用户的东西排乱了"。
        val sources = listOf(
            FakeSource(id = "3", modelId = "m3"),
            FakeSource(id = "1", modelId = "m1"),
            FakeSource(id = "2", modelId = "m2"),
        )

        val configs = sources.toModelConfigs()

        assertEquals(listOf("3", "1", "2"), configs.map { it.id })
    }

    @Test
    fun `批量转换中查不到声明的项不报错`() {
        val sources = listOf(
            FakeSource(id = "a", modelId = "known"),
            FakeSource(id = "b", modelId = "unknown"),
        )

        val configs = sources.toModelConfigs(mapOf("known" to DeclaredModel("Known")))

        assertEquals(2, configs.size)
        assertEquals("Known", configs[0].modelDisplayName)
        assertEquals("unknown", configs[1].modelDisplayName)
        assertNull(configs[1].inputPricePerMillion)
    }

    @Test
    fun `空列表转换得到空列表`() {
        assertTrue(emptyList<ModelConfigSource>().toModelConfigs().isEmpty())
    }
}
