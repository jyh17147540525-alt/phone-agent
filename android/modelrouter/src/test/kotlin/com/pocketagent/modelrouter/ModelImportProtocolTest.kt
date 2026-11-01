package com.pocketagent.modelrouter

import com.pocketagent.provider.api.Capability
import com.pocketagent.provider.api.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **自动导入的协议过滤**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：为什么单独测这一层
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户报的 bug 是"OpenCode 请求被拒"。修完那个之后读文档发现，
 * 还有一类**同样的失败**在等着：部分模型走别的接口，
 * 而本 provider 只会 chat/completions。
 *
 * ⇒ 导入时就该拦住，并且**说清楚为什么**。
 *
 * ⚠️ 这个文件盯的是"跳过是否可见" —— 静默丢弃是这里最大的风险。
 */
class ModelImportProtocolTest {

    private fun m(id: String) = ModelInfo(
        id = id,
        displayName = id,
        capabilities = setOf(Capability.STREAM, Capability.TOOL_CALL),
        contextWindow = 128_000,
        outputPricePerMillion = 1.0,
    )

    @Test
    fun `OpenCode 的 Claude 不会被导入`() {
        val r = ModelImport.planDetailed(
            discovered = listOf(m("glm-5.2"), m("claude-haiku-5-5")),
            providerId = "opencode-go",
        )
        assertEquals(listOf("glm-5.2"), r.plans.map { it.modelId })
        assertEquals(1, r.skippedByProtocol.size)
        assertEquals("claude-haiku-5-5", r.skippedByProtocol.first().modelId)
    }

    @Test
    fun `被跳过的必须有原因`() {
        // ══════════════════════════════════════════════════════
        //  ★ 核心纪律：不能静默丢弃
        // ══════════════════════════════════════════════════════
        // 用户明确要求"填完 Key 自动识别所有模型"。
        // 悄悄少几个，他会以为厂商没有那些模型。
        val r = ModelImport.planDetailed(
            discovered = listOf(m("claude-haiku-5-5"), m("gpt-6-luna")),
            providerId = "opencode-go",
        )
        assertEquals(2, r.skippedByProtocol.size)
        for (s in r.skippedByProtocol) {
            assertTrue("「${s.modelId}」被跳过却没给原因", s.reason.isNotBlank())
            assertNotNull(s.reason)
        }
    }

    @Test
    fun `discoveredChatCount 把跳过的一起算`() {
        // ★ 界面要能说"厂商给了 28 个，其中 5 个要另一种接口，导入了 23 个"。
        val r = ModelImport.planDetailed(
            discovered = listOf(m("glm-5.2"), m("gpt-6-luna"), m("kimi-k3")),
            providerId = "opencode-go",
        )
        assertEquals(2, r.plans.size)
        assertEquals(1, r.skippedByProtocol.size)
        assertEquals(
            "总数应当是导入 + 跳过",
            3,
            r.discoveredChatCount,
        )
    }

    @Test
    fun `别的厂商不受协议闸门影响`() {
        // ⚠️ `gpt-6-luna` 在 OpenCode 上走 /responses，
        //    但 OpenAI 直连走 chat/completions 完全正常。
        val r = ModelImport.planDetailed(
            discovered = listOf(m("gpt-6-luna")),
            providerId = "openai",
        )
        assertEquals(1, r.plans.size)
        assertTrue(r.skippedByProtocol.isEmpty())
    }

    @Test
    fun `未知厂商不拦任何东西`() {
        // ★ 默认值 providerId = "" ⇒ 一律按 chat/completions。
        //   宁可放进来（用户自己会发现），也不要因为不认识厂商就全拦掉。
        val r = ModelImport.planDetailed(
            discovered = listOf(m("claude-haiku-5-5"), m("gpt-6-luna")),
            providerId = "",
        )
        assertEquals(2, r.plans.size)
        assertTrue(r.skippedByProtocol.isEmpty())
    }

    @Test
    fun `非对话模型优先被过滤 不进跳过清单`() {
        // ⚠️ whisper / embedding 是"根本不是对话模型"，
        //    不该混进"因为协议不支持而跳过"的统计里 ——
        //    那会让用户以为我们有朝一日能支持 whisper 对话。
        val r = ModelImport.planDetailed(
            discovered = listOf(m("glm-5.2"), m("whisper-1"), m("text-embedding-3-large")),
            providerId = "opencode-go",
        )
        assertEquals(1, r.plans.size)
        assertTrue("非对话模型不该出现在跳过清单里", r.skippedByProtocol.isEmpty())
    }

    @Test
    fun `plan 的旧签名仍然只返回清单`() {
        // ★ 保留旧签名是为了不动既有 14 条测试；它内部委托 planDetailed。
        val list = ModelImport.plan(listOf(m("a-model"), m("b-model")))
        assertEquals(2, list.size)
    }
}
