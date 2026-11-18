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
        performance: Performance? = null,
        presence: Boolean = false,
    ) = AssemblyInput(
        card = card(anchors = anchors),
        tone = ToneAxes(),
        state = state,
        memories = mems,
        budgetTokens = budget,
        latestChangeVersion = latestChangeVersion,
        performance = performance,
        presence = presence,
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

    // ── 表演指导（后台 AI 写的那一句）────────────────────────────

    /** 让指导的生成时刻与状态的"现在"对齐 —— 两处用的是同一个时钟。 */
    private fun guideAt(
        line: String = "先别追问，把话说短。",
        state: PersonaState = busyState,
        ageMs: Long = 60_000,
    ) = Performance(
        line = line,
        atMs = state.physiology.lastActiveAtMs - ageMs,
        hormonesKey = "有点紧",
    )

    @Test
    fun `有指导时状态层多出一段怎么演`() {
        val p = assembler.assemble(input(state = busyState, performance = guideAt()))
        val l3 = p.layers.first { it.layer == PromptLayer.L3_STATE }.text
        assertTrue("指导要进 L3", l3.contains("这一句怎么演"))
        assertTrue(l3.contains("先别追问，把话说短。"))
        assertTrue("状态本身还在", l3.contains("陪你熬到三点"))
        assertFalse(
            "两句会打架：一句说别管状态，一句说把话说短",
            l3.contains("语气随之自然一点即可"),
        )
    }

    @Test
    fun `过期的指导不进提示词`() {
        val p = assembler.assemble(
            input(state = busyState, performance = guideAt(ageMs = Performance.TTL_MS + 1)),
        )
        val l3 = p.layers.first { it.layer == PromptLayer.L3_STATE }.text
        assertFalse("过期就该丢掉", l3.contains("这一句怎么演"))
        assertTrue("没有指导时用回模板句", l3.contains("语气随之自然一点即可"))
    }

    @Test
    fun `中性状态下只有指导也会产生状态层`() {
        val neutral = PersonaState()
        val p = assembler.assemble(
            input(state = neutral, performance = guideAt(state = neutral)),
        )
        assertTrue("指导本身就有内容，不该被中性状态吞掉", PromptLayer.L3_STATE in layersOf(p))
        val l3 = p.layers.first { it.layer == PromptLayer.L3_STATE }.text
        assertTrue(l3.contains("这一句怎么演"))
        assertFalse("中性状态没有状态句可写", l3.contains("此刻的状态"))
    }

    @Test
    fun `没有指导时 L3 与以前逐字一致`() {
        // ⚠️ 这一条防"加了功能顺手改坏了默认文案" —— 它会影响每一轮。
        //    只钉状态句那一段：后面还跟着「此刻的身体」，那段由生理引擎决定，
        //    钉死它会让这条测试在生理引擎迭代时无端变红。
        val p = assembler.assemble(input(state = busyState))
        val l3 = p.layers.first { it.layer == PromptLayer.L3_STATE }.text
        assertTrue(
            "默认文案变了会静默影响每一轮，实际：$l3",
            l3.startsWith(
                "## 此刻的状态\n你现在心情不错、精神很好（由头：陪你熬到三点）。" +
                    "语气随之自然一点即可，不要刻意强调自己的状态。",
            ),
        )
        assertFalse("没有指导时不该出现指导段", l3.contains("这一句怎么演"))
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

    // ── 她自己的此刻（只有「在场」模式开）────────────────────────

    /**
     * 按**设备时区**取今天的某个时刻。
     *
     * ⚠️ 不写死 +8：`renderState` 用的是 `TimeZone.getDefault()`，
     *    测试若假设东八区，在 CI（UTC）上会整体偏 8 小时，
     *    断言的就不是"吃饭时她手上正有事"，而是"随便某个时刻"。
     */
    private fun todayAt(hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, hour)
        cal.set(java.util.Calendar.MINUTE, minute)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    @Test
    fun `在场模式会写她这个点在做什么`() {
        // 12:30 恒在午饭窗口内：窗口起点 = 12:15 ± 10 分钟、长 30 分钟，
        // 所以 [12:05, 12:25] 任一时刻开始的窗口都覆盖 12:30（jitter 按天固定，测试不该猜它）。
        val noon = todayAt(12, 30)
        val state = PersonaState(
            mood = 80,
            energy = 75,
            physiology = Physiology(lastActiveAtMs = noon),
        )
        val p = assembler.assemble(input(state = state, presence = true))
        val l3 = p.layers.first { it.layer == PromptLayer.L3_STATE }.text
        assertTrue("开了 presence 却没写她手上在做什么：\n$l3", l3.contains("## 你手上正有事"))
    }

    @Test
    fun `没开 presence 的模式不替她编作息`() {
        // ★ 角色扮演 / 工作模式里时间是停的 —— 用户不说话她就不存在，
        //   一个不存在的人没有"吃饭时间"。给她安一个作息是给幻觉递素材：
        //   模型会照着"我正在吃饭"编出用户从没经历过的场景。
        val noon = todayAt(12, 30)
        val state = PersonaState(
            mood = 80,
            energy = 75,
            physiology = Physiology(lastActiveAtMs = noon),
        )
        val p = assembler.assemble(input(state = state)) // presence 默认 false
        val l3 = p.layers.firstOrNull { it.layer == PromptLayer.L3_STATE }?.text.orEmpty()
        assertFalse("没开 presence 也写了她在做什么：\n$l3", l3.contains("## 你手上正有事"))
        // 反向确认这一段不是因为 L3 空着才没有（否则测试是假的绿色）。
        assertTrue("L3 本来就该有内容：\n$l3", l3.isNotBlank())
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
        // ★ 2026-11-12：L0 现在 = 骨骼 + 人称裁决（谁在说话）。
        //    两段都是常量，仍然与任何输入无关 —— 断言跟着改，判据不变。
        assertEquals(
            "L0 是常量，任何输入下都不变",
            PersonaAssembler.BONES_DECLARATION + "\n\n" + PromptRoles.speakerSection(),
            l0,
        )
    }
}
