package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **模型选择器**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是"用户会不会被静默骗"
 * ═══════════════════════════════════════════════════════════════
 *
 * 模型选择这件事最怕的不是"选错"，而是**选错了却不告诉用户**：
 *  · 他用 A，实际跑的是 B → 排查方向全错
 *  · 他选的模型被停用了 → 静默换成别的，他不知道
 *
 * ⇒ 每条回落路径都必须有**可展示的理由**。
 */
class ModelPickerTest {

    private fun model(
        id: String,
        tier: ModelTier,
        roles: Set<ModelRole> = setOf(ModelRole.CHAT, ModelRole.WORKER),
        enabled: Boolean = true,
        outPrice: Double? = 1.0,
    ) = ModelConfig(
        id = id,
        label = "",
        credentialId = "cred",
        modelId = "model-$id",
        modelDisplayName = "模型$id",
        tier = tier,
        inputPricePerMillion = outPrice?.div(4),
        outputPricePerMillion = outPrice,
        roles = roles,
        enabled = enabled,
    )

    private val light = model("light", ModelTier.LIGHT)
    private val standard = model("std", ModelTier.STANDARD)
    private val heavy = model("heavy", ModelTier.HEAVY)
    private val all = listOf(light, standard, heavy)

    // ═══════════════════════════════════════════════════════════
    //  ★ 钉死优先
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `钉死的模型就直接用它`() {
        val r = pickModel(
            all,
            ModelChoice.Pinned(heavy.id),
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertEquals(heavy, r.config)
        assertTrue("理由要提到它的名字：${r.reason}", "模型heavy" in r.reason)
    }

    @Test
    fun `钉死的模型被停用时回落到自动并说明`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条防的是"静默换模型"
        // ══════════════════════════════════════════════════════
        // 用户以为在用 X，实际在跑 Y —— 他说"回答变笨了"时
        // 完全查不出原因。
        val disabled = model("gone", ModelTier.HEAVY, enabled = false)
        val r = pickModel(
            listOf(light, standard, disabled),
            ModelChoice.Pinned(disabled.id),
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertTrue("必须回落到别的模型", r.found)
        assertTrue("不得选已停用的那个", r.config!!.enabled)
        assertTrue(
            "理由必须说清「为什么不是你以为的那个」：${r.reason}",
            "停用" in r.reason,
        )
    }

    @Test
    fun `钉死的模型被删掉时回落到自动并说明`() {
        val r = pickModel(
            all,
            ModelChoice.Pinned("不存在的id"),
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertTrue(r.found)
        assertTrue("理由要说明已被删除：${r.reason}", "删除" in r.reason)
    }

    // ═══════════════════════════════════════════════════════════
    //  思考强度 → 档位
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `快选最轻档`() {
        val r = pickModel(all, ModelChoice.Auto, ThinkingEffort.LOW, Scenario.CHAT)
        assertEquals(ModelTier.LIGHT, r.config!!.tier)
    }

    @Test
    fun `最高档选最重档`() {
        // ★ 四档改为 off/low/high/max 后：MAX 才映射到 HEAVY，
        //    HIGH 映射到 STANDARD。见 ThinkingEffort.preferredTier。
        val r = pickModel(all, ModelChoice.Auto, ThinkingEffort.MAX, Scenario.CHAT)
        assertEquals(ModelTier.HEAVY, r.config!!.tier)
    }

    @Test
    fun `标准选中间档`() {
        val r = pickModel(all, ModelChoice.Auto, ThinkingEffort.HIGH, Scenario.CHAT)
        assertEquals(ModelTier.STANDARD, r.config!!.tier)
    }

    @Test
    fun `四档到三档的映射`() {
        // ★ 用户指定四档 off/low/high/max。映射见 ThinkingEffort.preferredTier：
        //   off/low → LIGHT，high → STANDARD，max → HEAVY
        assertEquals(ModelTier.LIGHT, ThinkingEffort.OFF.preferredTier)
        assertEquals(ModelTier.LIGHT, ThinkingEffort.LOW.preferredTier)
        assertEquals(ModelTier.STANDARD, ThinkingEffort.HIGH.preferredTier)
        assertEquals(ModelTier.HEAVY, ThinkingEffort.MAX.preferredTier)
        // 且只有 MAX 是 apex（界面据此进入方格模式）
        assertTrue(ThinkingEffort.MAX.isApex)
        assertFalse(ThinkingEffort.HIGH.isApex)
    }
    // ═══════════════════════════════════════════════════════════
    //  降级（用户没配那个档位）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `想要重档但只配了轻档时用轻档`() {
        // ⚠️ 不能用"没有可用模型"把用户卡住 —— 他明明配了一个。
        val r = pickModel(
            listOf(light),
            ModelChoice.Auto,
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertTrue("必须能回落到手上有的：${r.reason}", r.found)
        assertEquals(ModelTier.LIGHT, r.config!!.tier)
    }

    @Test
    fun `想要轻档但只配了重档时用重档`() {
        val r = pickModel(
            listOf(heavy),
            ModelChoice.Auto,
            ThinkingEffort.LOW,
            Scenario.CHAT,
        )
        assertTrue(r.found)
        assertEquals(ModelTier.HEAVY, r.config!!.tier)
    }

    @Test
    fun `够用就好 —— 有两档时优先不低于目标里最低的`() {
        // 目标 STANDARD，手上有 STANDARD 和 HEAVY ⇒ 选 STANDARD
        val r = pickModel(
            listOf(standard, heavy),
            ModelChoice.Auto,
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertEquals(ModelTier.STANDARD, r.config!!.tier)
    }

    // ═══════════════════════════════════════════════════════════
    //  角色不匹配时不卡死
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `只勾了工作角色也要能在聊天里用`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是"配了模型却提示没有可用模型"
        // ══════════════════════════════════════════════════════
        // 用户只配了一个模型、只勾了"执行任务"，然后在聊天框说话 ——
        // 如果硬按角色过滤，他会看到"还没有可用的模型"，
        // 而他明明配了一个。
        val workerOnly = model("w", ModelTier.STANDARD, roles = setOf(ModelRole.WORKER))
        val r = pickModel(
            listOf(workerOnly),
            ModelChoice.Auto,
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertTrue("不该说没有可用模型：${r.reason}", r.found)
        assertEquals(workerOnly, r.config)
    }

    @Test
    fun `有匹配角色的模型时优先用它`() {
        val chatOnly = model("c", ModelTier.STANDARD, roles = setOf(ModelRole.CHAT))
        val workOnly = model("w", ModelTier.STANDARD, roles = setOf(ModelRole.WORKER))
        val r = pickModel(
            listOf(workOnly, chatOnly),
            ModelChoice.Auto,
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertEquals("聊天场景该优先 CHAT 角色", chatOnly, r.config)
    }

    // ═══════════════════════════════════════════════════════════
    //  同档位的取舍
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同档位优先便宜的`() {
        val cheap = model("cheap", ModelTier.STANDARD, outPrice = 1.0)
        val pricey = model("pricey", ModelTier.STANDARD, outPrice = 10.0)
        val r = pickModel(
            // ⚠️ 故意把贵的排在前面：结果不该受顺序影响
            listOf(pricey, cheap),
            ModelChoice.Auto,
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertEquals("同档位该选便宜的", cheap, r.config)
    }

    @Test
    fun `价格未知的不会被当成免费`() {
        // ══════════════════════════════════════════════════════
        //  ★ 与 `ModelConfigMapping` 同一条纪律
        // ══════════════════════════════════════════════════════
        // `outputPricePerMillion = null` 的含义是**未知**，
        // 不是 0。把它当成免费会让"自动选便宜的"选错 ——
        // 而用户完全看不出为什么。
        val unknown = model("unknown", ModelTier.STANDARD, outPrice = null)
        val known = model("known", ModelTier.STANDARD, outPrice = 5.0)
        val r = pickModel(
            listOf(unknown, known),
            ModelChoice.Auto,
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertEquals("未知价格不该胜出", known, r.config)
    }

    // ═══════════════════════════════════════════════════════════
    //  空清单
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `一个都没配时说清怎么办`() {
        val r = pickModel(emptyList(), ModelChoice.Auto, ThinkingEffort.HIGH, Scenario.CHAT)
        assertNull(r.config)
        assertTrue("要给出下一步：${r.reason}", "密钥" in r.reason || "配置" in r.reason)
    }

    @Test
    fun `全停用时说清怎么办`() {
        val r = pickModel(
            listOf(model("a", ModelTier.STANDARD, enabled = false)),
            ModelChoice.Auto,
            ThinkingEffort.HIGH,
            Scenario.CHAT,
        )
        assertNull(r.config)
        assertTrue("要说明是停用：${r.reason}", "停用" in r.reason || "启用" in r.reason)
    }

    // ═══════════════════════════════════════════════════════════
    //  每种结局都必须有理由
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `任何情况都要给出非空理由`() {
        val choices = listOf(
            ModelChoice.Auto,
            ModelChoice.Pinned(standard.id),
            ModelChoice.Pinned("不存在"),
        )
        val efforts = ThinkingEffort.entries
        val scenarios = Scenario.entries
        val lists = listOf(all, listOf(light), emptyList(), listOf(model("x", ModelTier.LIGHT, enabled = false)))

        for (c in choices) for (e in efforts) for (s in scenarios) for (m in lists) {
            val r = pickModel(m, c, e, s)
            assertTrue(
                "理由不能为空：choice=$c effort=$e scenario=$s models=${m.size}",
                r.reason.isNotBlank(),
            )
            assertEquals("found 与 config 必须一致", r.found, r.config != null)
        }
    }

    @Test
    fun `选了模型时理由里一定有它的名字`() {
        // ★ 用户问"这次用的哪个"时，理由要能直接回答
        for (e in ThinkingEffort.entries) {
            val r = pickModel(all, ModelChoice.Auto, e, Scenario.CHAT)
            assertNotNull(r.config)
            assertTrue(
                "理由里没有模型名：${r.reason}",
                r.config!!.effectiveLabel in r.reason,
            )
        }
    }
}
