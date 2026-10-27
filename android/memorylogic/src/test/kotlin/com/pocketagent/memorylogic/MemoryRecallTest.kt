package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryRecall] 的离线钉子。
 *
 * ★ 这些测试全都在钉"**不该出现的东西不出现**"，
 *   而不是"该出现的东西出现了"。理由：记忆模块的故障形态几乎全部是
 *   **多记**，而不是漏记 —— 多记在界面上完全看不出来（模型只是答得怪一点），
 *   而漏记用户至少能说"你忘了我刚说的"。
 */
class MemoryRecallTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun atom(
        kind: AtomKind,
        text: String,
        at: Long = now - day,
        confidence: Double = 0.8,
        id: String = Atom.idOf(kind, text),
    ) = Atom(
        id = id,
        kind = kind,
        text = text,
        sourceTurnIds = listOf("t-1"),
        confidence = confidence,
        at = at,
    )

    @Test
    fun `不相关的事实不该被想起来`() {
        val atoms = listOf(
            atom(AtomKind.FACT, "他住在杭州"),
            atom(AtomKind.FACT, "他的猫叫团子"),
        )
        val picked = MemoryRecall.rank("今天股市怎么样", atoms, now)
        assertTrue("完全不相关时一条都不该出现，实际=$picked", picked.isEmpty())
    }

    @Test
    fun `相关的事实会被想起来`() {
        val atoms = listOf(
            atom(AtomKind.FACT, "他住在杭州"),
            atom(AtomKind.FACT, "他的猫叫团子"),
        )
        val picked = MemoryRecall.rank("杭州今天下雨吗", atoms, now)
        assertEquals("只有杭州那条相关", 1, picked.size)
        assertTrue(picked.single().text.contains("杭州"))
    }

    @Test
    fun `约束类永远会出现，哪怕话题完全不相关`() {
        val atoms = listOf(atom(AtomKind.CONSTRAINT, "他不能吃辣，会胃疼"))
        val picked = MemoryRecall.rank("帮我查一下明天的航班", atoms, now)
        assertEquals("约束必须活下来 —— 它描述的是不能做的事，与话题无关", 1, picked.size)
    }

    @Test
    fun `偏好类在话题不相关时会掉出去`() {
        val atoms = listOf(atom(AtomKind.PREFERENCE, "他喜欢深色主题"))
        val picked = MemoryRecall.rank("帮我查一下明天的航班", atoms, now)
        assertTrue("偏好不该无条件常驻，否则每条回答里都会冒出不相干的话", picked.isEmpty())
    }

    @Test
    fun `旧记忆的分数低于新记忆`() {
        val fresh = atom(AtomKind.FACT, "他昨天在杭州出差", at = now - day)
        val stale = atom(AtomKind.FACT, "他三年前在杭州出差", at = now - 365 * day)
        val picked = MemoryRecall.rank("杭州", listOf(stale, fresh), now)
        assertEquals(2, picked.size)
        assertEquals("新的排前面", fresh.id, picked.first().id)
    }

    @Test
    fun `同一条记忆只出现一次`() {
        // 同一个 id 的两份（合并前的形态）不该被当成两条
        val a = atom(AtomKind.FACT, "他住在杭州")
        val b = atom(AtomKind.FACT, "他住在杭州")
        val picked = MemoryRecall.rank("杭州", listOf(a, b), now, limit = 6)
        assertEquals("同 id 去重后只剩一条", 1, picked.size)
    }

    @Test
    fun `条数上限被遵守`() {
        val atoms = (1..20).map { atom(AtomKind.FACT, "杭州的第 $it 件事") }
        val picked = MemoryRecall.rank("杭州", atoms, now, limit = 3)
        assertEquals(3, picked.size)
    }

    @Test
    fun `字符预算比条数预算更优先`() {
        // 每条 300 字，预算 800 → 最多装两条（第三条会越界）
        val long = "杭州" + "很".repeat(298)
        val atoms = (1..6).map { atom(AtomKind.FACT, "$long$it") }
        val picked = MemoryRecall.rank("杭州", atoms, now, limit = 6)
        assertTrue("不能只限条数：6 条长句会吃掉整个提示词预算，实际=${picked.size}", picked.size <= 3)
    }

    @Test
    fun `空查询时只有基础分起作用`() {
        val atoms = listOf(
            atom(AtomKind.CONSTRAINT, "他不能吃辣"),
            atom(AtomKind.FACT, "他住在杭州"),
        )
        val picked = MemoryRecall.rank("", atoms, now)
        assertEquals("没有线索时只剩约束", 1, picked.size)
        assertEquals(AtomKind.CONSTRAINT, picked.single().kind)
    }

    @Test
    fun `排序是确定性的 —— 两次调用结果一致`() {
        val atoms = (1..10).map { atom(AtomKind.FACT, "杭州第 $it 条", at = now - day) }
        val first = MemoryRecall.rank("杭州", atoms, now).map { it.id }
        val second = MemoryRecall.rank("杭州", atoms.reversed(), now).map { it.id }
        assertEquals("输入顺序不该影响结果 —— 否则'为什么这次没想起'不可复现", first, second)
    }

    @Test
    fun `提示词段落为空时不写任何东西`() {
        val section = MemoryRecall.promptSection(
            query = "帮我查航班",
            atoms = listOf(atom(AtomKind.FACT, "他住在杭州")),
            nowMs = now,
        )
        assertEquals("没有相关记忆就该返回空串，而不是写一句'（无记忆）'", "", section)
    }

    @Test
    fun `提示词段落里带类型标签并说明这是已知而非对方刚说的`() {
        val section = MemoryRecall.promptSection(
            query = "杭州",
            atoms = listOf(atom(AtomKind.FACT, "他住在杭州")),
            nowMs = now,
        )
        assertTrue("要标明类型，模型才知道这条的分量", section.contains("（事实）"))
        assertTrue(section.contains("他住在杭州"))
        assertTrue(
            "必须说清这些不是对方这一句里说的，否则模型会当成新信息复述一遍",
            section.contains("不是对方这一句里说的"),
        )
        assertFalse("不能写'没有记忆'那类句子", section.contains("没有相关"))
    }

    @Test
    fun `空库不会崩也不会产出段落`() {
        assertEquals("", MemoryRecall.promptSection("随便说点什么", emptyList(), now))
        assertTrue(MemoryRecall.rank("随便说点什么", emptyList(), now).isEmpty())
    }

    @Test
    fun `limit 为 0 时什么都不返回`() {
        val atoms = listOf(atom(AtomKind.CONSTRAINT, "他不能吃辣"))
        assertTrue(MemoryRecall.rank("杭州", atoms, now, limit = 0).isEmpty())
    }
}
