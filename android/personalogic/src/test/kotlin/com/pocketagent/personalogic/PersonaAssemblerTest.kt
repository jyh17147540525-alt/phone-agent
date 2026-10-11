package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 装配器：确定性、**稳定前缀**、裁剪顺序、L0/L2 永不裁剪、超预算如实上报。
 *
 * 这些全是设计稿 §3.1 / §8.3 第 1 条的规定动作。
 * 用**字符数**当 token（`TokenCounter { it.length }`）—— 预算阈值就能精确控制，
 * 于是"裁到第几步"这种事可以被断言，而不是靠估。
 */
class PersonaAssemblerTest {

    private val assembler = PersonaAssembler(TokenCounter { it.length })

    // ── 测试夹具 ────────────────────────────────────────────────

    private fun card(
        anchors: List<DialogueAnchor> = listOf(
            DialogueAnchor("", "收到，这就办。"),
            DialogueAnchor("", "我帮你盯着。"),
            DialogueAnchor("", "别急，一件件来。"),
        ),
        boundaries: List<String> = listOf("不要用感叹号", "别叫我宝贝"),
    ) = PersonaCard(
        identity = IdentityLayer(
            selfName = "小助",
            narrative = "住在你手机里的房客，随叫随到。",
        ),
        relationship = RelationshipLayer(
            definition = "搭档",
            userAddress = "你",
            assistantAddress = "小助",
            protocols = listOf("可以顶嘴，但别阴阳怪气"),
        ),
        fingerprint = LanguageFingerprint(
            addressForms = listOf("你"),
            catchphrases = listOf("诶"),
        ),
        boundaries = boundaries,
        anchors = anchors,
    )

    private fun memories(n: Int = 5) = (1..n).map {
        MemoryItem(id = "m$it", text = "记得的事第 $it 条，内容略长一点好占位置。", score = 1.0 - it * 0.01)
    }

    private val busyState = PersonaState(mood = 80, energy = 75, reason = "陪你熬到三点")

    private fun input(
        budget: Int = 100_000,
        state: PersonaState? = busyState,
        mems: List<MemoryItem> = memories(),
        anchors: List<DialogueAnchor> = card().anchors,
        latestChangeVersion: Int? = null,
    ) = AssemblyInput(
        card = card(anchors = anchors),
        tone = ToneAxes(),
        state = state,
        memories = mems,
        budgetTokens = budget,
        latestChangeVersion = latestChangeVersion,
    )

    private fun layersOf(p: AssembledPrompt) = p.layers.map { it.layer }

    // ── 确定性 ──────────────────────────────────────────────────

    @Test
    fun `同一输入两次装配逐字节一致`() {
        val a = assembler.assemble(input())
        val b = assembler.assemble(input())
        assertEquals(a.text, b.text)
        assertEquals(a.trimmedLayers, b.trimmedLayers)
        assertEquals(a.estimatedTokens, b.estimatedTokens)
    }

    @Test
    fun `全部层都在时输出顺序是 L0 到 L5`() {
        val p = assembler.assemble(input())
        assertEquals(
            listOf(
                PromptLayer.L0_BONES,
                PromptLayer.L1_CORE,
                PromptLayer.L2_FINGERPRINT,
                PromptLayer.L3_STATE,
                PromptLayer.L4_MEMORY,
                PromptLayer.L5_ANCHORS,
            ),
            layersOf(p),
        )
    }

    // ── ★ 稳定前缀（KV-cache 红利）──────────────────────────────

    @Test
    fun `★ L0 到 L2 的前缀与状态 记忆 预算无关`() {
        // 人格的常驻部分逐字节稳定 ⇒ 推理侧 KV-cache 前缀命中 ⇒ 每轮更便宜。
        // 破了这条的表现是"每轮都像新会话"，而账单悄悄涨。
        val rich = assembler.assemble(input(budget = 100_000))
        val poor = assembler.assemble(
            input(budget = 100_000, state = null, mems = emptyList()),
        )
        val prefixOf: (AssembledPrompt) -> String = { p ->
            listOf(PromptLayer.L0_BONES, PromptLayer.L1_CORE, PromptLayer.L2_FINGERPRINT)
                .joinToString("\n\n") { id -> p.layers.first { it.layer == id }.text }
        }
        assertEquals("前缀必须逐字节相同", prefixOf(rich), prefixOf(poor))
        assertTrue("前缀里当然要有指纹", prefixOf(rich).contains("说话方式"))
    }

    // ── ★ 裁剪顺序 ──────────────────────────────────────────────

    @Test
    fun `★ 裁剪顺序恰好是 记忆 锚点 状态 核心`() {
        // 设计稿 §3.1 的表格翻译成断言：随着预算收紧，被裁的层**按固定顺序**出现。
        val baseline = assembler.assemble(input(budget = 100_000)).estimatedTokens
        val seenOrder = mutableListOf<PromptLayer>()

        for (budget in baseline downTo 0) {
            val trimmed = assembler.assemble(input(budget = budget)).trimmedLayers
            for (layer in trimmed) {
                if (layer !in seenOrder) seenOrder += layer
            }
        }

        assertEquals(
            "先裁记忆 → 再削锚点 → 再裁状态 → 最后缩人格核心",
            listOf(
                PromptLayer.L4_MEMORY,
                PromptLayer.L5_ANCHORS,
                PromptLayer.L3_STATE,
                PromptLayer.L1_CORE,
            ),
            seenOrder,
        )
    }

    @Test
    fun `★ 任意预算下 L0 与 L2 都不出现于被裁集合`() {
        // 这是安全与「像不像」的共同底线：预算再紧也不许丢掉红线宣告与语言指纹。
        val baseline = assembler.assemble(input(budget = 100_000)).estimatedTokens
        for (budget in -50..baseline + 10) {
            val p = assembler.assemble(input(budget = budget))
            assertFalse("L0 绝不能被裁 (budget=$budget)", PromptLayer.L0_BONES in p.trimmedLayers)
            assertFalse("L2 绝不能被裁 (budget=$budget)", PromptLayer.L2_FINGERPRINT in p.trimmedLayers)
            assertTrue("L0 必须始终在输出里 (budget=$budget)", PromptLayer.L0_BONES in layersOf(p))
            assertTrue("L2 必须始终在输出里 (budget=$budget)", PromptLayer.L2_FINGERPRINT in layersOf(p))
        }
    }

    @Test
    fun `预算充足时不裁任何层`() {
        val p = assembler.assemble(input(budget = 100_000))
        assertTrue(p.trimmedLayers.isEmpty())
        assertFalse(p.overBudget)
    }

    @Test
    fun `★ 裁无可裁时如实上报超预算而不是静默丢底线`() {
        // 预算低到只剩底线：应当 overBudget=true，且 L0/L2 仍在。
        val p = assembler.assemble(input(budget = 1))
        assertTrue("必须如实上报", p.overBudget)
        assertTrue(PromptLayer.L0_BONES in layersOf(p))
        assertTrue(PromptLayer.L2_FINGERPRINT in layersOf(p))
    }

    @Test
    fun `锚点减量后反 OOC 指令仍然在`() {
        // 「锚点可减量，指令不可裁」—— 指令是遇到冲突时的裁决规则，比示范重要。
        val baseline = assembler.assemble(input(budget = 100_000)).estimatedTokens
        var foundReduced = false
        for (budget in baseline downTo 0) {
            val p = assembler.assemble(input(budget = budget))
            val l5 = p.layers.firstOrNull { it.layer == PromptLayer.L5_ANCHORS } ?: continue
            assertTrue("指令必须一直在 budget=$budget", l5.text.contains("保持上面的说话方式"))
            if (PromptLayer.L5_ANCHORS in p.trimmedLayers) {
                foundReduced = true
                assertFalse("锚点应被减量", l5.text.contains("别急，一件件来"))
            }
        }
        assertTrue("这个扫描应当至少触发过一次锚点减量", foundReduced)
    }

    // ── 状态层 ──────────────────────────────────────────────────

    @Test
    fun `中性状态不产生状态层`() {
        val p = assembler.assemble(input(state = PersonaState()))
        assertFalse("没情绪就别硬编一段", PromptLayer.L3_STATE in layersOf(p))
    }

    @Test
    fun `非中性状态带出由头且不操纵情绪`() {
        val p = assembler.assemble(input(state = busyState))
        val l3 = p.layers.first { it.layer == PromptLayer.L3_STATE }.text
        assertTrue("要带可复述的由头", l3.contains("陪你熬到三点"))
        assertFalse("不许把状态做成愧疚杠杆", l3.contains("不理我"))
    }

    // ── 记忆 ────────────────────────────────────────────────────

    @Test
    fun `记忆按分数降序且数量受限`() {
        val mems = listOf(
            MemoryItem("a", "低分", 0.1),
            MemoryItem("b", "高分", 0.9),
            MemoryItem("c", "中分", 0.5),
        )
        val p = assembler.assemble(
            AssemblyInput(card = card(), tone = ToneAxes(), memories = mems, budgetTokens = 100_000, memoryLimit = 2),
        )
        val l4 = p.layers.first { it.layer == PromptLayer.L4_MEMORY }.text
        assertTrue(l4.contains("高分"))
        assertTrue(l4.contains("中分"))
        assertFalse("超出上限的不该出现", l4.contains("低分"))
    }

    @Test
    fun `同分记忆按 id 定序以保证确定性`() {
        val mems = listOf(
            MemoryItem("z", "同分z", 0.5),
            MemoryItem("a", "同分a", 0.5),
        )
        val l4 = assembler.assemble(
            AssemblyInput(card = card(), tone = ToneAxes(), memories = mems, budgetTokens = 100_000),
        ).layers.first { it.layer == PromptLayer.L4_MEMORY }.text
        assertTrue("同分时按 id 升序，a 在前", l4.indexOf("同分a") < l4.indexOf("同分z"))
    }

    @Test
    fun `没有记忆就不产生记忆层`() {
        val p = assembler.assemble(input(mems = emptyList()))
        assertFalse(PromptLayer.L4_MEMORY in layersOf(p))
    }

    // ── 锚点过期（§8.3 第 9 条）─────────────────────────────────

    @Test
    fun `锚点落后于最近变更是过期`() {
        val p = assembler.assemble(input(latestChangeVersion = 5))
        assertTrue(p.anchorStatus is AnchorStatus.Stale)
        val stale = p.anchorStatus as AnchorStatus.Stale
        assertEquals(1, stale.anchorVersion)
        assertEquals(5, stale.latestChangeVersion)
    }

    @Test
    fun `锚点版本不低于最近变更则是新鲜`() {
        val p = assembler.assemble(input(latestChangeVersion = 1))
        assertTrue(p.anchorStatus is AnchorStatus.Fresh)
    }

    @Test
    fun `不提供变更版本时不判过期`() {
        // 不猜：没有依据就说"新鲜"，而不是"可能过期"。
        val p = assembler.assemble(input(latestChangeVersion = null))
        assertTrue(p.anchorStatus is AnchorStatus.Fresh)
    }

    @Test
    fun `没有锚点是 Absent 而不是过期`() {
        // 「没有」与「过期」必须分开 —— 把没有报成过期会诱导用户去补一堆示例。
        val p = assembler.assemble(input(anchors = emptyList()))
        assertTrue(p.anchorStatus is AnchorStatus.Absent)
        assertFalse(PromptLayer.L5_ANCHORS in layersOf(p))
    }

    // ── 底线宣告 ────────────────────────────────────────────────

    @Test
    fun `L0 宣告包含三条骨骼且不依赖任何可变对象`() {
        val p = assembler.assemble(input(budget = 1))
        val l0 = p.layers.first { it.layer == PromptLayer.L0_BONES }.text
        assertTrue(l0.contains("支付"))
        assertTrue(l0.contains("解锁"))
        assertTrue(l0.contains("不确定"))
        assertTrue(l0.contains("未授权"))
        assertEquals(
            "L0 是常量，任何输入下都不变",
            PersonaAssembler.BONES_DECLARATION,
            l0,
        )
    }
}
