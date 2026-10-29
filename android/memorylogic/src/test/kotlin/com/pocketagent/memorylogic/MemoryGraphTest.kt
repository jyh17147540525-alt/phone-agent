package com.pocketagent.memorylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MemoryGraph] 的离线钉子。
 *
 * ★ 这个对象最容易出的错不是"画不出来"，而是**每次画得不一样** ——
 *   图上的位置在用户心里是认知锚点（"那条重要的事在右上角"），
 *   位置变了就等于记忆变了。所以最重要的几条测试都在钉"确定性"。
 */
class MemoryGraphTest {

    private fun atom(
        id: String,
        kind: AtomKind = AtomKind.FACT,
        text: String = "记忆 $id",
        at: Long = 1_700_000_000_000L,
        confidence: Double = 0.8,
        src: List<String> = listOf("t-$id"),
    ) = Atom(
        id = id,
        kind = kind,
        text = text,
        sourceTurnIds = src,
        confidence = confidence,
        at = at,
    )

    @Test
    fun `空库建出空图`() {
        val g = MemoryGraph.build(emptyList())
        assertTrue(g.isEmpty)
        assertTrue(g.nodes.isEmpty())
        assertTrue(g.edges.isEmpty())
    }

    @Test
    fun `单条记忆也能建图且位置有限`() {
        val g = MemoryGraph.build(listOf(atom("a1")))
        assertEquals(1, g.nodes.size)
        val n = g.nodes.single()
        assertTrue("坐标不能是 NaN：${n.x}", n.x.isFinite() && n.y.isFinite() && n.z.isFinite())
        assertTrue(kotlin.math.abs(n.x) < 10 && kotlin.math.abs(n.y) < 10)
    }

    @Test
    fun `同样的输入永远得到同样的图`() {
        // ⚠️ 这是本文件最重要的一条：位置是认知锚点，不能每次都变。
        val atoms = (1..40).map { atom("a$it") }
        val g1 = MemoryGraph.build(atoms)
        val g2 = MemoryGraph.build(atoms)
        assertEquals(g1.nodes.map { it.atom.id }, g2.nodes.map { it.atom.id })
        for (i in g1.nodes.indices) {
            assertEquals("第 $i 个点 x 变了", g1.nodes[i].x, g2.nodes[i].x, 1e-9)
            assertEquals("第 $i 个点 y 变了", g1.nodes[i].y, g2.nodes[i].y, 1e-9)
        }
    }

    @Test
    fun `输入顺序不影响结果`() {
        val atoms = (1..30).map { atom("a$it") }
        val a = MemoryGraph.build(atoms)
        val b = MemoryGraph.build(atoms.reversed())
        assertEquals("点的集合必须一致", a.nodes.map { it.atom.id }.sorted(), b.nodes.map { it.atom.id }.sorted())
        // 位置也必须一致（按 id 对齐后比较）
        val mapA = a.nodes.associateBy { it.atom.id }
        for (n in b.nodes) {
            assertEquals(n.atom.id, mapA.getValue(n.atom.id).x, n.x, 1e-9)
        }
    }

    @Test
    fun `约束比事实更重要`() {
        assertTrue(
            MemoryGraph.weightOf(atom("c", kind = AtomKind.CONSTRAINT)) >
                MemoryGraph.weightOf(atom("f", kind = AtomKind.FACT)),
        )
    }

    @Test
    fun `溯源越广越重要`() {
        val narrow = atom("n", src = listOf("t1"))
        val wide = atom("w", src = (1..10).map { "t$it" })
        assertTrue(MemoryGraph.weightOf(wide) > MemoryGraph.weightOf(narrow))
    }

    @Test
    fun `重点事件会被标出来`() {
        val key = atom("k", kind = AtomKind.CONSTRAINT, confidence = 1.0, src = (1..10).map { "t$it" })
        val normal = atom("f", kind = AtomKind.FACT, confidence = 0.3, src = listOf("t1"))
        val g = MemoryGraph.build(listOf(key, normal))
        val keyNode = g.nodes.first { it.atom.id == "k" }
        val normalNode = g.nodes.first { it.atom.id == "f" }
        assertTrue("约束+高置信+广溯源应当是重点", keyNode.isKeyEvent)
        assertFalse("低置信的事实不应当是重点", normalNode.isKeyEvent)
    }

    @Test
    fun `共用溯源且带因果词的，会连成有方向的因果边`() {
        // ⚠️ 2026-10-29 用户指出的错误：原来只按"共现"连边，
        //    一段对话里提到的两件事被连成一条线，而它们其实没关系。
        val cause = atom(
            "cause",
            kind = AtomKind.FACT,
            text = "他胃一直不好，做过胃镜",
            at = 1_700_000_000_000L,
            src = listOf("t1", "t2"),
        )
        val effect = atom(
            "effect",
            kind = AtomKind.CONSTRAINT,
            text = "他不能吃辣，因为胃疼",
            at = 1_700_000_000_000L + 60_000L,
            src = listOf("t2", "t3"),
        )
        val g = MemoryGraph.build(listOf(effect, cause))
        assertEquals(1, g.edges.size)
        val e = g.edges.single()
        assertEquals("因在前：$e", "cause", e.fromAtomId)
        assertEquals("果在后：$e", "effect", e.toAtomId)
        assertEquals("有因果词就是 CAUSES", MemoryGraph.Relation.CAUSES, e.relation)
    }

    @Test
    fun `事实解释约束时会连成解释边`() {
        val fact = atom(
            "f",
            kind = AtomKind.FACT,
            text = "他做过胃镜",
            at = 1_700_000_000_000L,
            src = listOf("t1"),
        )
        val constraint = atom(
            "c",
            kind = AtomKind.CONSTRAINT,
            text = "他不能吃辣",
            at = 1_700_000_000_000L + 60_000L,
            src = listOf("t1"),
        )
        val g = MemoryGraph.build(listOf(constraint, fact))
        assertEquals(1, g.edges.size)
        assertEquals(MemoryGraph.Relation.EXPLAINS, g.edges.single().relation)
        assertEquals("f", g.edges.single().fromAtomId)
    }

    @Test
    fun `没有因果也不解释的，宁可不连边`() {
        // 同一段对话里同时提到"喜欢猫"和"住在杭州" —— 两者没有关系。
        val a = atom("a", kind = AtomKind.PREFERENCE, text = "他喜欢猫", at = 1_700_000_000_000L, src = listOf("t1"))
        val b = atom("b", kind = AtomKind.PREFERENCE, text = "他住在杭州", at = 1_700_000_000_000L + 30_000L, src = listOf("t1"))
        val g = MemoryGraph.build(listOf(a, b))
        assertTrue("共现不等于因果，不该连线：${g.edges}", g.edges.isEmpty())
    }

    @Test
    fun `因果词表保守，不会把转折判成因果`() {
        assertTrue(MemoryGraph.hasCausalCue("因为胃疼所以不能吃辣"))
        assertTrue(MemoryGraph.hasCausalCue("他加班太多，结果病了"))
        assertFalse("「但是」是转折不是因果", MemoryGraph.hasCausalCue("他喜欢狗，但是不养"))
        assertFalse(MemoryGraph.hasCausalCue("他住在杭州"))
    }

    @Test
    fun `能查出某个点的因与果`() {
        val cause = atom("k1", kind = AtomKind.FACT, text = "因为长期熬夜", at = 1_700_000_000_000L, src = listOf("t1"))
        val effect = atom("k2", kind = AtomKind.STAGE_CONCLUSION, text = "所以身体垮了", at = 1_700_000_000_000L + 1000L, src = listOf("t1"))
        val g = MemoryGraph.build(listOf(cause, effect))
        assertEquals(listOf("k1"), MemoryGraph.causesOf(g, "k2").map { it.id })
        assertEquals(listOf("k2"), MemoryGraph.effectsOf(g, "k1").map { it.id })
    }

    @Test
    fun `共用溯源且有因果线索的，强度明显更高`() {
        val a = atom("a", kind = AtomKind.FACT, text = "因为医生说要多喝水", src = listOf("t1", "t2"))
        val b = atom("b", kind = AtomKind.PREFERENCE, text = "所以他现在天天带水杯", src = listOf("t2", "t3"))
        val g = MemoryGraph.build(listOf(a, b))
        assertEquals(1, g.edges.size)
        assertTrue("有因果线索，强度应当高：${g.edges.single().strength}", g.edges.single().strength >= 0.9)
    }

    @Test
    fun `完全无关的记忆之间不连边`() {
        val a = atom("a", kind = AtomKind.FACT, at = 1_700_000_000_000L, src = listOf("t1"))
        val b = atom("b", kind = AtomKind.FACT, at = 1_700_000_000_000L + 10L * 86_400_000L, src = listOf("t9"))
        val g = MemoryGraph.build(listOf(a, b))
        assertTrue("不同类、时间又隔了十天，不该连线", g.edges.isEmpty())
    }

    @Test
    fun `点太多时按重要度截断，不画成一团毛线`() {
        val atoms = (1..400).map {
            atom("a$it", kind = if (it % 50 == 0) AtomKind.CONSTRAINT else AtomKind.FACT)
        }
        val g = MemoryGraph.build(atoms)
        assertEquals(MemoryGraph.MAX_NODES, g.nodes.size)
        // 约束类一定要留下来（它们是最重要的）
        assertTrue("重要的那些必须留下", g.nodes.any { it.atom.kind == AtomKind.CONSTRAINT })
    }

    @Test
    fun `每个节点的连边数量有上限`() {
        // 全部共用同一批溯源 → 本来会连成完全图
        val turns = (1..5).map { "t$it" }
        val atoms = (1..40).map { atom("a$it", src = turns) }
        val g = MemoryGraph.build(atoms)
        val perNode = HashMap<String, Int>()
        for (e in g.edges) {
            perNode[e.fromAtomId] = (perNode[e.fromAtomId] ?: 0) + 1
            perNode[e.toAtomId] = (perNode[e.toAtomId] ?: 0) + 1
        }
        assertTrue(
            "单个点最多 ${MemoryGraph.MAX_NODES} 条边（实际最多 ${perNode.values.maxOrNull()}）",
            (perNode.values.maxOrNull() ?: 0) <= 6,
        )
    }

    @Test
    fun `所有坐标都是有限值`() {
        val atoms = (1..120).map { atom("a$it") }
        val g = MemoryGraph.build(atoms)
        for (n in g.nodes) {
            assertTrue("NaN/Inf 会让画布整块变空", n.x.isFinite() && n.y.isFinite() && n.z.isFinite())
        }
    }

    @Test
    fun `建出的图归一化到单位球内`() {
        // ⚠️ 不归一化的话，点一多半径就变大，同一套渲染参数会让整团跑到屏幕外 ——
        //    真机第一次就是这个症状：只看得见几颗孤星。
        for (count in listOf(5, 40, 200)) {
            val g = MemoryGraph.build((1..count).map { atom("a$it") })
            val maxR = g.nodes.maxOf { kotlin.math.sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }
            assertTrue("$count 条时半径 $maxR 应约为 1", maxR in 0.9..1.001)
        }
    }
}
