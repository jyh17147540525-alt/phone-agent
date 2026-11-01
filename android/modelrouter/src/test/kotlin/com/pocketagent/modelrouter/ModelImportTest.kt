package com.pocketagent.modelrouter

import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **模型自动导入**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 自动导入最容易造成的两种伤害
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **把非对话模型导进来** —— `/models` 里有 whisper / tts / embedding。
 *    用户选中后必然 400，而他不知道为什么。
 *
 * ② **档位判错** —— 把便宜的判成重型，"自动选快的"就选了个贵的。
 */
class ModelImportTest {

    private fun m(
        id: String,
        price: Double? = 1.0,
        display: String = "",
    ) = ModelInfo(
        id = id,
        displayName = display.ifBlank { id },
        capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL),
        contextWindow = 128_000,
        outputPricePerMillion = price,
    )

    // ═══════════════════════════════════════════════════════════
    //  ★ 过滤非对话模型
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `非对话模型被排除`() {
        val obvious = listOf(
            "whisper-1",
            "tts-1",
            "tts-1-hd",
            "text-embedding-3-large",
            "text-embedding-ada-002",
            "dall-e-3",
            "omni-moderation-latest",
            "gpt-4o-audio-preview",
        )
        for (id in obvious) {
            assertFalse("「$id」不该被当成对话模型", ModelImport.isChatCapable(id))
        }
    }

    @Test
    fun `对话模型不被误杀`() {
        // ══════════════════════════════════════════════════════
        //  ★ 误杀的代价更大：用户"配了 Key 却看不到模型"
        // ══════════════════════════════════════════════════════
        val chat = listOf(
            "gpt-4o", "gpt-4o-mini", "gpt-4.1", "o4-mini",
            "claude-sonnet-4", "claude-3-5-haiku",
            "deepseek-chat", "deepseek-reasoner",
            "glm-5.2", "qwen3-coder", "kimi-k2", "grok-code",
            "llama-3.1-70b", "mistral-large",
            "gemini-2.0-flash", "doubao-pro-32k", "ernie-4.0",
        )
        for (id in chat) {
            assertTrue("「$id」应当可以对话", ModelImport.isChatCapable(id))
        }
    }

    @Test
    fun `plan 里不含非对话模型`() {
        val discovered = listOf(
            m("gpt-4o"),
            m("whisper-1"),
            m("text-embedding-3-large"),
            m("tts-1"),
        )
        val plan = ModelImport.plan(discovered)
        assertEquals(1, plan.size)
        assertEquals("gpt-4o", plan.first().modelId)
    }

    // ═══════════════════════════════════════════════════════════
    //  档位按相对价格分
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `便宜的是轻量 贵的是重型`() {
        val discovered = listOf(
            m("cheap", 1.0),
            m("mid", 5.0),
            m("pricey", 15.0),
        )
        assertEquals(ModelTier.LIGHT, ModelImport.tierFor("cheap", discovered))
        assertEquals(ModelTier.STANDARD, ModelImport.tierFor("mid", discovered))
        assertEquals(ModelTier.HEAVY, ModelImport.tierFor("pricey", discovered))
    }

    @Test
    fun `少于三个模型时都是标准`() {
        // ⚠️ 一两个模型分三档没有意义 —— 全是 STANDARD 最诚实
        val two = listOf(m("a", 1.0), m("b", 10.0))
        assertEquals(ModelTier.STANDARD, ModelImport.tierFor("a", two))
        assertEquals(ModelTier.STANDARD, ModelImport.tierFor("b", two))
    }

    @Test
    fun `价格未知的不被判成轻量`() {
        // ══════════════════════════════════════════════════════
        //  ★ 与 `ModelConfigMapping` 同一条纪律：未知 ≠ 免费
        // ══════════════════════════════════════════════════════
        // 把未知当便宜会让"自动选快的"选到一个其实很贵的模型。
        val discovered = listOf(
            m("a", 1.0), m("b", 5.0), m("c", 10.0),
            m("unknown", price = null),
        )
        assertEquals(
            ModelTier.STANDARD,
            ModelImport.tierFor("unknown", discovered),
        )
    }

    @Test
    fun `档位不依赖绝对价格区间`() {
        // ⚠️ 不同厂商价格差几十倍：某家最贵的可能比别人最便宜的还便宜。
        //    用固定阈值会在小厂商上全判轻量 —— 所以用**排位**。
        val cheapVendor = listOf(m("a", 0.01), m("b", 0.02), m("c", 0.03))
        val priceyVendor = listOf(m("x", 30.0), m("y", 60.0), m("z", 90.0))

        assertEquals(ModelTier.LIGHT, ModelImport.tierFor("a", cheapVendor))
        assertEquals(ModelTier.HEAVY, ModelImport.tierFor("c", cheapVendor))
        assertEquals(ModelTier.LIGHT, ModelImport.tierFor("x", priceyVendor))
        assertEquals(ModelTier.HEAVY, ModelImport.tierFor("z", priceyVendor))
    }

    // ═══════════════════════════════════════════════════════════
    //  去重
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `已配过的不会重复导入`() {
        val discovered = listOf(m("a"), m("b"))
        val plan = ModelImport.plan(discovered, alreadyConfigured = setOf("a"))
        assertEquals(1, plan.size)
        assertEquals("b", plan.first().modelId)
    }

    @Test
    fun `返回清单里的重复 id 会被去重`() {
        // `/models` 偶尔重复返回同一个 id
        val discovered = listOf(m("a"), m("a"), m("b"))
        val plan = ModelImport.plan(discovered)
        assertEquals(2, plan.size)
        assertEquals(listOf("a", "b"), plan.map { it.modelId }.sorted())
    }

    // ═══════════════════════════════════════════════════════════
    //  角色与排序
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `默认给对话和执行任务两个角色`() {
        // ★ 零配置的关键：用户不用再勾选
        val roles = ModelImport.rolesFor()
        assertTrue("必须有 CHAT", ModelRole.CHAT in roles)
        assertTrue("必须有 WORKER", ModelRole.WORKER in roles)
        // ⚠️ 不给 SCHEDULER：那需要较强模型，全给等于没有选择
        assertFalse("不该默认当调度者", ModelRole.SCHEDULER in roles)
    }

    @Test
    fun `导入清单重型排前面`() {
        // ★ 界面上第一个常被当默认，重型作默认更安全
        val discovered = listOf(
            m("cheap", 1.0),
            m("mid", 5.0),
            m("pricey", 15.0),
        )
        val plan = ModelImport.plan(discovered)
        assertEquals(ModelTier.HEAVY, plan.first().tier)
        assertEquals(ModelTier.LIGHT, plan.last().tier)
    }

    // ═══════════════════════════════════════════════════════════
    //  边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空清单返回空`() {
        assertTrue(ModelImport.plan(emptyList()).isEmpty())
    }

    @Test
    fun `全是非对话模型时返回空`() {
        val onlyNonChat = listOf(m("whisper-1"), m("tts-1"), m("text-embedding-3-small"))
        assertTrue(ModelImport.plan(onlyNonChat).isEmpty())
    }

    @Test
    fun `displayName 为空时用 id 兜底`() {
        val discovered = listOf(m("some-model", display = ""))
        val plan = ModelImport.plan(discovered)
        assertEquals("some-model", plan.first().label)
    }
}
