package com.pocketagent.agent

import com.pocketagent.action.Bounds
import com.pocketagent.action.ElementRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **文本定位器的匹配规则**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这是「感知 → 定位 → 校验 → 消歧」链路里第一个有实现的部分
 * ═══════════════════════════════════════════════════════════════
 *
 * `Grounder` 此前只有契约、零实现，所以 `GroundResult.Ambiguous`
 * 在运行时永远不会产生，`AmbiguityResolver` 也就一直没人调用。
 * 本文件钉住第一个实现的规则。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三类必须防的错
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **误命中**：把不相干的东西当真（"包含"类最容易）
 * ② **假装成功**：点到不可点的容器，什么都不会发生，但模型以为点到了
 * ③ **该问不问**：多个候选同分却直接选一个 —— 可能选错
 */
class TextGrounderTest {

    private fun node(
        id: String,
        text: String = "",
        desc: String = "",
        viewId: String? = null,
        clickable: Boolean = true,
    ) = TextGrounder.CandidateNode(
        nodeId = id,
        text = text,
        contentDescription = desc,
        viewId = viewId,
        bounds = Bounds(0, 0, 100, 50),
        clickable = clickable,
    )

    // ═══════════════════════════════════════════════════════════
    //  精确匹配
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `文本完全相同且可点 置信度最高`() {
        // ══════════════════════════════════════════════════════
        //  ★ 「搜索历史」**包含**「搜索」，所以它也是候选（0.6 分）
        // ══════════════════════════════════════════════════════
        //
        // ⇒ `ground` 会如实报出两个候选，**不替调用方做主**。
        //   谁来做主是 `AmbiguityResolver` 的职责：
        //   0.95 vs 0.60 差 0.35 ≥ 阈值 0.2 ⇒ 它会自动选前面那个。
        //
        // ⚠️ 一开始我把这条测试写成"应当返回 Found"，
        //    那是把 resolver 的职责塞进了 ground —— 测试红了才对。
        val scored = TextGrounder.scoredCandidates(
            "搜索",
            listOf(node("n1", text = "搜索"), node("n2", text = "搜索历史")),
        )
        assertEquals("n1", scored.first().first.nodeId)
        assertEquals(
            TextGrounder.EXACT_TEXT_CLICKABLE,
            scored.first().second,
            1e-4f,
        )
        // 包含匹配也进候选，但分数低得多
        assertEquals(2, scored.size)
        assertEquals(TextGrounder.CONTAINS_TEXT, scored[1].second, 1e-4f)

        // 差距足够大 —— resolver 会直接选定
        assertTrue(
            "0.95 与 0.60 的差距应当 ≥ 0.2，让 resolver 能自动选",
            scored[0].second - scored[1].second >= 0.2f - 1e-4f,
        )
    }

    @Test
    fun `精确匹配优先于包含匹配`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防"误命中"
        // ══════════════════════════════════════════════════════
        // 屏幕上有「搜索」和「搜索历史」，目标是「搜索」时
        // 必须选前者，哪怕后者在列表里更靠前。
        val r = TextGrounder.scoredCandidates(
            "搜索",
            listOf(
                node("n1", text = "搜索历史"),
                node("n2", text = "搜索"),
            ),
        )
        assertEquals("n2", r.first().first.nodeId)
        assertEquals(TextGrounder.EXACT_TEXT_CLICKABLE, r.first().second, 1e-4f)
    }

    @Test
    fun `大小写不敏感`() {
        val r = TextGrounder.ground("Search", listOf(node("n1", text = "search")))
        assertTrue(r is GroundResult.Found)
    }

    @Test
    fun `描述匹配`() {
        val r = TextGrounder.ground(
            "返回",
            listOf(node("n1", desc = "返回")),
        )
        assertEquals(
            TextGrounder.EXACT_DESCRIPTION,
            (r as GroundResult.Found).confidence,
            1e-4f,
        )
    }

    @Test
    fun `viewId 支持全名与短名`() {
        val n = node("n1", viewId = "com.foo:id/search")
        assertEquals(TextGrounder.EXACT_VIEW_ID, confidence("com.foo:id/search", n), 1e-4f)
        assertEquals(TextGrounder.EXACT_VIEW_ID, confidence("search", n), 1e-4f)
        assertEquals("不相关的 id 不该命中", 0f, confidence("login", n), 1e-4f)
    }

    @Test
    fun `不可点的精确文本分数略低但不为零`() {
        // ⚠️ 不可点不等于"不是它" —— 只是不能直接点。
        //    分数略低是提示，不是否定。
        assertEquals(
            TextGrounder.EXACT_TEXT,
            confidence("搜索", node("n1", text = "搜索", clickable = false)),
            1e-4f,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  包含匹配
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `包含匹配的置信度刻意低`() {
        // ══════════════════════════════════════════════════════
        //  ★ 给高了会让 AmbiguityResolver 的 0.2 差距判据失效
        // ══════════════════════════════════════════════════════
        // 列表里十条都含"确定"是常态。若包含匹配给 0.8，
        // 它和其它候选的差距小于 0.2，resolver 就会"问用户"；
        // 但若误给 0.9 而精确匹配才 0.95，差距 0.05 —— 还是问。
        // 真正危险的是：包含匹配的分数高于精确匹配 → 选错。
        assertEquals(
            TextGrounder.CONTAINS_TEXT,
            confidence("确定", node("n1", text = "确定要删除吗")),
            1e-4f,
        )
        assertTrue(
            "包含匹配必须明显低于精确匹配",
            TextGrounder.CONTAINS_TEXT < TextGrounder.EXACT_TEXT,
        )
    }

    @Test
    fun `低于下限的命中不算候选`() {
        // 一个既不完全相同、包含也不算的文本 ⇒ 0 分 ⇒ 不进候选
        assertEquals(0f, confidence("重启", node("n1", text = "设置")), 1e-4f)
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 不能假装成功
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `唯一命中但不可点时要求更多信息`() {
        // ══════════════════════════════════════════════════════
        //  ★ 点一个不可点的容器什么都不会发生，
        //    而模型会以为点成功了 —— 那是最坏的一种失败。
        // ══════════════════════════════════════════════════════
        val r = TextGrounder.ground(
            "搜索",
            listOf(node("n1", text = "搜索", clickable = false)),
        )
        assertTrue("应当要求补充采集，而不是假装成功：$r", r is GroundResult.NeedMoreInfo)
    }

    @Test
    fun `多个候选里有一个可点时不要求更多信息`() {
        // 有可点选项时交给消歧，不上升为 NeedMoreInfo
        val r = TextGrounder.ground(
            "搜索",
            listOf(
                node("n1", text = "搜索", clickable = false),
                node("n2", text = "搜索"),
            ),
        )
        assertTrue(r is GroundResult.Ambiguous)
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 该问就问
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `多个同分候选返回 Ambiguous`() {
        val r = TextGrounder.ground(
            "确定",
            listOf(node("n1", text = "确定"), node("n2", text = "确定")),
        )
        assertTrue("同分时不能替用户做主：$r", r is GroundResult.Ambiguous)
        assertEquals(2, (r as GroundResult.Ambiguous).candidates.size)
    }

    @Test
    fun `Ambiguous 的候选是 ElementRef`() {
        val r = TextGrounder.ground(
            "确定",
            listOf(node("n1", text = "确定"), node("n2", text = "确定")),
        ) as GroundResult.Ambiguous
        assertTrue(r.candidates.all { it is ElementRef.ByNodeId })
    }

    // ═══════════════════════════════════════════════════════════
    //  找不到 / 边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有节点时 NotFound`() {
        assertTrue(TextGrounder.ground("搜索", emptyList()) is GroundResult.NotFound)
    }

    @Test
    fun `全都不匹配时 NotFound`() {
        val r = TextGrounder.ground("搜索", listOf(node("n1", text = "设置")))
        assertTrue(r is GroundResult.NotFound)
    }

    @Test
    fun `空目标描述直接 NotFound`() {
        // ⚠️ 空串会让 `contains("")` 恒真 —— 必须提前拦住，
        //    否则每个节点都会被当成命中。
        assertTrue(TextGrounder.ground("", listOf(node("n1", text = "搜索"))) is GroundResult.NotFound)
        assertTrue(TextGrounder.ground("   ", listOf(node("n1", text = "搜索"))) is GroundResult.NotFound)
    }

    @Test
    fun `空目标描述不产生候选`() {
        assertTrue(TextGrounder.scoredCandidates("", listOf(node("n1", text = "x"))).isEmpty())
    }

    @Test
    fun `空文本字段不会被当成命中`() {
        // 全是空字段的节点在任何目标下都不该命中
        val r = TextGrounder.ground("搜索", listOf(node("n1")))
        assertTrue("空节点不该命中任何目标：$r", r is GroundResult.NotFound)
    }

    @Test
    fun `置信度不累加`() {
        // ══════════════════════════════════════════════════════
        //  ★ 文本与描述都命中不更可信
        // ══════════════════════════════════════════════════════
        // 一个按钮同时有 text 和 contentDescription 是常态。
        // 若累加，它会虚高到压过真正的精确匹配。
        val both = node("n1", text = "搜索", desc = "搜索")
        val onlyText = node("n2", text = "搜索")
        assertEquals(
            confidence("搜索", onlyText),
            confidence("搜索", both),
            1e-4f,
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  辅助
    // ─────────────────────────────────────────────────────────────

    private fun confidence(target: String, n: TextGrounder.CandidateNode): Float =
        TextGrounder.scoredCandidates(target, listOf(n)).firstOrNull()?.second ?: 0f

    private fun nodeIdOf(r: GroundResult): String? =
        ((r as? GroundResult.Found)?.ref as? ElementRef.ByNodeId)?.nodeId
}
