package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **端到端替身**：后台返回的一整段 JSON → 到底出现在前台的 L3 里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件替代的是"真机上跑一遍"
 * ═══════════════════════════════════════════════════════════════
 *
 * 真机上要确认的三件事（后台返不返回 `performance`、断网是否安静、
 * 会不会越演越夸张）里，**后两件是纯逻辑**，可以在这里钉住：
 *
 * - 断网 = 没有新 JSON → 走的是"空值"，Slot 保留旧值 / 过期自然失效；
 * - 越演越夸张 = 同一激素档位反复换措辞 → Gate 的防抖。
 *
 * 真机剩下的唯一未知是"模型到底会不会写这个字段" —— 那是提示词问题，
 * 不是代码问题，改起来不影响这一层。
 *
 * ⚠️ 这一层测的是**拼接**：解析器的字段名、Gate 的激素签名、装配器的
 *    注入点，三处任何一处对不上（比如一边叫 `performance`、
 *    另一边读 `perform`），单元测试全绿、线上永远没有指导。
 */
class PerformancePipelineTest {

    private var now = 1_600_000_000_000L

    private val slot = PerformanceSlot { now }

    private val assembler = PersonaAssembler()

    private fun card() = PersonaCard(
        identity = IdentityLayer(selfName = "小助", narrative = "住在你手机里的房客，随叫随到。"),
        relationship = RelationshipLayer(
            definition = "搭档",
            userAddress = "你",
            assistantAddress = "小助",
        ),
        fingerprint = LanguageFingerprint(addressForms = listOf("你")),
        boundaries = listOf("不要用感叹号"),
        anchors = listOf(DialogueAnchor("", "收到，这就办。")),
    )

    /** 状态与 Slot 用同一个"现在"，否则有效期判断会错位。 */
    private fun state() = PersonaState(
        mood = 80,
        energy = 75,
        reason = "陪你熬到三点",
        physiology = Physiology(lastActiveAtMs = now),
    )

    private fun prompt(performance: Performance? = slot.current()): AssembledPrompt =
        assembler.assemble(
            AssemblyInput(
                card = card(),
                tone = ToneAxes(),
                state = state(),
                memories = emptyList(),
                budgetTokens = 100_000,
                performance = performance,
            ),
        )

    private fun l3(p: AssembledPrompt): String =
        p.layers.first { it.layer == PromptLayer.L3_STATE }.text

    // ═══════════════════════════════════════════════════════════
    //  ★ 一整段 JSON 走到底
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `后台返回的 JSON 里的指导最终出现在 L3`() {
        val raw = """
            {
              "events": [{"kind": "USER_UNWELL", "reason": "用户说他发烧了"}],
              "smallMoments": ["窗外在下雨"],
              "proposedFacts": [],
              "performance": "先别追问，把话说短，认一句就够。"
            }
        """.trimIndent()

        val parsed = DirectorParser.parse(raw)
        assertTrue("解析失败", parsed != null)
        assertTrue(
            "字段名对不上 —— 解析器没读到 performance",
            parsed!!.output.performance != null,
        )

        val verdict = slot.offer(parsed.output.performance, hormonesKey = "有点紧")
        assertTrue("应当被接受，实际：$verdict", verdict is PerformanceVerdict.Accepted)

        val l3 = l3(prompt())
        assertTrue("L3 里应有一段「这一句怎么演」，实际：$l3", l3.contains("## 这一句怎么演"))
        assertTrue(l3.contains("先别追问，把话说短，认一句就够。"))
        assertTrue("状态本身还在", l3.contains("陪你熬到三点"))
    }

    @Test
    fun `指导被拒时事件仍然生效`() {
        // ══════════════════════════════════════════════════════
        //  ★ 两条链必须独立：指导写坏了不该连累状态
        // ══════════════════════════════════════════════════════
        val raw = """
            {
              "events": [{"kind": "USER_UNWELL", "reason": "用户说他发烧了"}],
              "performance": "皮质醇 0.8，别说话了。"
            }
        """.trimIndent()
        val parsed = DirectorParser.parse(raw)!!

        val verdict = slot.offer(parsed.output.performance, hormonesKey = "有点紧")
        assertTrue("含激素名与数字，应当被拒", verdict is PerformanceVerdict.Rejected)

        val accepted = DirectorGate.accept(
            output = parsed.output,
            trace = DirectorTrace(),
            turnIndex = 5,
            dayIndex = 1,
        )
        assertEquals("事件照常生效", 1, accepted.events.size)
        assertEquals(DirectorEventKind.USER_UNWELL, accepted.events.first().kind)
    }

    @Test
    fun `第二轮模型写垃圾时 旧指导仍在提示词里`() {
        // 断网/超时/返回垃圾，走的是同一条路：offer 一个用不了的值
        val first = DirectorParser.parse("""{"performance": "把话说短。"}""")!!
        slot.offer(first.output.performance, hormonesKey = "有点紧")
        assertTrue(l3(prompt()).contains("把话说短。"))

        val junk = DirectorParser.parse("""{"performance": "你现在有点紧绷，回答温柔一点。"}""")!!
        val v = slot.offer(junk.output.performance, hormonesKey = "有点紧")
        assertTrue("含「紧绷/温柔」这类系统语，应当被拒", v is PerformanceVerdict.Rejected)

        val l3 = l3(prompt())
        assertFalse("垃圾句子不能进提示词", l3.contains("紧绷"))
        assertTrue("旧句还在", l3.contains("把话说短。"))
    }

    @Test
    fun `连说三句而激素没变时 指导只换一次`() {
        // ══════════════════════════════════════════════════════
        //  ★ "越演越夸张"的离线替身
        // ══════════════════════════════════════════════════════
        val lines = listOf("把话说短。", "少说两句。", "这轮别开玩笑。")
        val verdicts = lines.map { slot.offer(it, hormonesKey = "有点紧") }

        assertTrue(verdicts.first() is PerformanceVerdict.Accepted)
        assertTrue(
            "第二句起应被防抖拦下，实际：$verdicts",
            verdicts.drop(1).all { it is PerformanceVerdict.Rejected },
        )
        assertEquals("最终只有第一句生效", "把话说短。", slot.current()?.line)
    }

    @Test
    fun `过期之后提示词回到旧文案`() {
        slot.offer("把话说短。", hormonesKey = "有点紧")
        assertTrue(l3(prompt()).contains("## 这一句怎么演"))

        now += Performance.TTL_MS + 1
        val l3 = l3(prompt())
        assertFalse("过期就该消失", l3.contains("## 这一句怎么演"))
        assertTrue("回到模板句", l3.contains("语气随之自然一点即可"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 前缀稳定性：指导**不能**碰 L0–L2
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `指导只改 L3 前缀层逐字不变`() {
        // 这是"为什么只让它写一句话"的硬保证：
        // 前缀一变，KV-cache 全失效（每轮贵一倍以上）。
        val before = prompt(performance = null)
        val guide = Performance(
            line = "把话说短。",
            atMs = now,
            hormonesKey = "有点紧",
        )
        val after = prompt(performance = guide)

        fun prefix(p: AssembledPrompt) = p.layers
            .takeWhile { it.layer != PromptLayer.L3_STATE }
            .map { it.text }

        assertEquals("L0–L2 必须逐字节一致", prefix(before), prefix(after))
        assertFalse("指导不该出现在前缀里", prefix(after).any { it.contains("这一句怎么演") })
    }

    @Test
    fun `预算很紧时指导跟着 L3 一起被裁 而不是只剩半句`() {
        // 记一笔**已知行为**：L3 是低优先级层，预算不够时整层被裁。
        // 与"指导过期"是同一个结果（没有指导），不能变成"半句话进提示词"。
        val guide = Performance(line = "把话说短。", atMs = now, hormonesKey = "有点紧")
        val p = assembler.assemble(
            AssemblyInput(
                card = card(),
                tone = ToneAxes(),
                state = state(),
                memories = emptyList(),
                budgetTokens = 1,
                performance = guide,
            ),
        )
        val l3Text = p.layers.firstOrNull { it.layer == PromptLayer.L3_STATE }?.text
        if (l3Text == null) {
            assertTrue("整层被裁：可接受", true)
        } else {
            assertFalse(
                "要么整段在，要么整段不在；被截断的半句会读成残句",
                l3Text.contains("## 这一句怎么演") && !l3Text.contains("把话说短。"),
            )
        }
    }
}
