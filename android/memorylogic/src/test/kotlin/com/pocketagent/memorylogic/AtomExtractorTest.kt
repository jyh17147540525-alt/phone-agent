package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 原子提取的测试。
 *
 * ★ 这一层最典型的故障是「该记住的没记住」，而它**不抛异常**：
 * 用户只会觉得"你怎么又忘了"。所以这里重点压两件事：
 *
 * 1. **该丢的一定要丢，且要说出为什么丢**（[ExtractionReport.dropped]）
 * 2. **该合的一定要合**（重复提取不能变成两条记忆，否则 L3 画像会失真）
 */
class AtomExtractorTest {

    private fun turn(id: String, text: String = "内容", role: TurnRole = TurnRole.USER, at: Long = 0L) =
        Turn(id = id, sessionId = "s1", role = role, text = text, at = at)

    /** 可编排的假模型：返回预设文本，并记账调用次数与收到的提示词。 */
    private class FakeLlm(private val reply: String) : MemoryLlmPort {
        var calls = 0
            private set
        var lastPrompt: String? = null
            private set
        var lastPurpose: MemoryLlmPort.Purpose? = null
            private set

        override fun complete(prompt: String, purpose: MemoryLlmPort.Purpose): String {
            calls++
            lastPrompt = prompt
            lastPurpose = purpose
            return reply
        }
    }

    private val turns = listOf(turn("t1"), turn("t2", role = TurnRole.ASSISTANT))

    private fun extractor(reply: String) = AtomExtractor(FakeLlm(reply))

    // ── 正常路径 ───────────────────────────────────────────────

    @Test
    fun `四类原子都能解析出来`() {
        val reply = """{"atoms":[
            {"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":0.9},
            {"kind":"preference","text":"用户喜欢简短的回答","sourceTurnIds":["t1"],"confidence":0.8},
            {"kind":"constraint","text":"不要在工作时间打扰","sourceTurnIds":["t2"],"confidence":0.7},
            {"kind":"stage_conclusion","text":"已确认明天的行程","sourceTurnIds":["t2"],"confidence":0.6}
        ]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 1_000L)

        assertEquals(
            listOf(AtomKind.FACT, AtomKind.PREFERENCE, AtomKind.CONSTRAINT, AtomKind.STAGE_CONCLUSION),
            report.atoms.map { it.kind },
        )
        assertTrue(report.dropped.isEmpty())
        assertEquals(0, report.mergedCount)
        assertEquals(1_000L, report.atoms.first().at)
    }

    @Test
    fun `提示词包含每一轮的 id 与说话人`() {
        val llm = FakeLlm("""{"atoms":[]}""")
        AtomExtractor(llm).extract(turns, emptyList(), now = 0L)

        val prompt = llm.lastPrompt!!
        assertTrue("提示词必须带出 t1 的 id", prompt.contains("[t1] user:"))
        assertTrue("提示词必须带出 t2 的 id", prompt.contains("[t2] assistant:"))
        assertEquals(
            "用途必须标成原子提取 —— 上层的档位选择与计量靠它",
            MemoryLlmPort.Purpose.ATOM_EXTRACTION,
            llm.lastPurpose,
        )
    }

    @Test
    fun `空输入不调用模型`() {
        val llm = FakeLlm("""{"atoms":[]}""")
        val report = AtomExtractor(llm).extract(emptyList(), emptyList(), now = 0L)

        assertEquals(0, llm.calls)
        assertTrue(report.atoms.isEmpty())
        assertTrue(report.dropped.isEmpty())
    }

    // ── 模型的真实毛病：围栏 / 裸数组 / 不可解析 ─────────────────

    @Test
    fun `代码围栏包裹的 JSON 能解析`() {
        val reply = "```json\n" + """{"atoms":[{"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":0.9}]}""" + "\n```"

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertEquals(1, report.atoms.size)
        assertTrue(report.dropped.isEmpty())
    }

    @Test
    fun `裸数组也能解析`() {
        val reply = """[{"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":0.9}]"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertEquals(1, report.atoms.size)
    }

    @Test
    fun `整段无法解析时报告一条可观测的丢弃`() {
        val report = extractor("抱歉，我无法完成这个任务。").extract(turns, emptyList(), now = 0L)

        assertTrue(report.atoms.isEmpty())
        assertEquals(1, report.dropped.size)
        assertEquals(AtomDropReason.MALFORMED_RESPONSE, report.dropped.single().reason)
    }

    // ── 校验：该丢的必须丢，且要说清为什么 ───────────────────────

    @Test
    fun `未知类别被丢弃而不是硬塞进事实`() {
        val reply = """{"atoms":[{"kind":"emotion","text":"用户今天心情不错","sourceTurnIds":["t1"],"confidence":0.9}]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertTrue(report.atoms.isEmpty())
        assertEquals(AtomDropReason.UNKNOWN_KIND, report.dropped.single().reason)
    }

    @Test
    fun `空文本被丢弃`() {
        val reply = """{"atoms":[{"kind":"fact","text":"   ","sourceTurnIds":["t1"],"confidence":0.9}]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertTrue(report.atoms.isEmpty())
        assertEquals(AtomDropReason.EMPTY_TEXT, report.dropped.single().reason)
    }

    @Test
    fun `置信度越界被丢弃且不被钳制`() {
        // ⚠️ 模型很爱写 90 当 90%。把它钳到 1.0 会让一条"模型其实很确信"的记忆
        //    和一个真正的 1.0 无法区分；钳到 0.9 更是凭空造了一个数。
        //    正确做法是丢弃 —— 因为 90 的含义只有模型自己知道。
        val reply = """{"atoms":[
            {"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":90},
            {"kind":"fact","text":"用户姓张","sourceTurnIds":["t1"],"confidence":-0.1}
        ]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertTrue(report.atoms.isEmpty())
        assertEquals(2, report.dropped.size)
        assertTrue(report.dropped.all { it.reason == AtomDropReason.BAD_CONFIDENCE })
    }

    @Test
    fun `溯源指向不存在的 Turn 时整条丢弃`() {
        // ★ 编造的出处比没有出处更危险：它会一路流到 L3 画像与人格微调，
        //   让助理"记住"一件从没发生过的事。
        val reply = """{"atoms":[{"kind":"preference","text":"用户喜欢喝茶","sourceTurnIds":["t99"],"confidence":0.9}]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertTrue(report.atoms.isEmpty())
        assertEquals(AtomDropReason.UNKNOWN_SOURCE_TURN, report.dropped.single().reason)
    }

    @Test
    fun `溯源部分存在时只保留真实存在的那些`() {
        val reply = """{"atoms":[{"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1","t99"],"confidence":0.9}]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertEquals(listOf("t1"), report.atoms.single().sourceTurnIds)
    }

    @Test
    fun `一条坏的不影响同一批里好的`() {
        val reply = """{"atoms":[
            {"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":0.9},
            {"kind":"emotion","text":"用户今天心情不错","sourceTurnIds":["t1"],"confidence":0.9}
        ]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertEquals(1, report.atoms.size)
        assertEquals(1, report.dropped.size)
    }

    // ── 合并 ───────────────────────────────────────────────────

    @Test
    fun `同一批内重复的原子被合并而不是变成两条`() {
        val reply = """{"atoms":[
            {"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":0.5},
            {"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t2"],"confidence":0.9}
        ]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)

        assertEquals(1, report.atoms.size)
        assertEquals(1, report.mergedCount)
        val atom = report.atoms.single()
        assertEquals("置信度取更高，不能取平均", 0.9, atom.confidence, 1e-9)
        assertEquals("溯源取并集", listOf("t1", "t2"), atom.sourceTurnIds)
    }

    @Test
    fun `与已有原子重复时合并而不是新增`() {
        val existing = Atom(
            id = Atom.idOf(AtomKind.FACT, "用户住在杭州"),
            kind = AtomKind.FACT,
            text = "用户住在杭州",
            sourceTurnIds = listOf("t0"),
            confidence = 0.4,
            at = 100L,
        )
        val reply = """{"atoms":[{"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":0.95}]}"""

        val report = extractor(reply).extract(turns, listOf(existing), now = 500L)

        val atom = report.atoms.single()
        assertEquals(existing.id, atom.id)
        assertEquals(1, report.mergedCount)
        assertEquals(0.95, atom.confidence, 1e-9)
        assertEquals(listOf("t0", "t1"), atom.sourceTurnIds)
        assertEquals("时间取更早的一次", 100L, atom.at)
    }

    @Test
    fun `原子 id 由类别与文本确定性决定`() {
        val reply = """{"atoms":[{"kind":"fact","text":"用户住在杭州","sourceTurnIds":["t1"],"confidence":0.9}]}"""

        val first = extractor(reply).extract(turns, emptyList(), now = 0L).atoms.single().id
        val second = extractor(reply).extract(turns, emptyList(), now = 999L).atoms.single().id

        assertEquals("同一件事重复提取必须是同一条记忆", first, second)
        assertNotEquals(
            "类别不同则不是同一条",
            Atom.idOf(AtomKind.FACT, "用户住在杭州"),
            Atom.idOf(AtomKind.PREFERENCE, "用户住在杭州"),
        )
    }

    // ── 丢弃片段必须可展示 ─────────────────────────────────────

    @Test
    fun `丢弃片段里的控制字符被转义且超长被截断`() {
        val text = "甲\n乙\t丙" + "长".repeat(200)
        // ⚠️ 必须先把控制字符转成 JSON 转义再拼进回复里：直接拼的话
        //    JSON 字符串里出现裸换行 = **非法 JSON**，于是这条会走到
        //    MALFORMED_RESPONSE，测的根本不是 snippet 的行为。
        val jsonText = text.replace("\n", "\\n").replace("\t", "\\t")
        val reply = """{"atoms":[{"kind":"emotion","text":"$jsonText","sourceTurnIds":["t1"],"confidence":0.9}]}"""

        val report = extractor(reply).extract(turns, emptyList(), now = 0L)
        val snippet = report.dropped.single().snippet

        assertFalse("换行必须转成可见转义，否则会撑坏日志与审计界面", snippet.contains('\n'))
        assertFalse(snippet.contains('\t'))
        assertTrue(snippet.contains("\\n"))
        assertTrue(snippet.length <= MAX_SNIPPET_LENGTH + 1)
        assertTrue(snippet.endsWith("…"))
    }
}