package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **勾选联动**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 五条错法，逐条钉住
 * ═══════════════════════════════════════════════════════════════
 *
 * 这些错法的共同点是**看起来像正常工作**，但用户会删错东西。
 */
class SelectionStateTest {

    private val items = listOf("a", "b", "c", "d")

    // ═══════════════════════════════════════════════════════════
    //  三态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空列表的全选态是未选`() {
        // ⚠️ "全部 0 项都选中了"在数学上成立（空真），但界面上毫无意义。
        //    画成 ON 会让用户点一下"全选"但什么都没发生。
        val s = SelectionState.empty(0)
        assertEquals(SelectionState.TriState.OFF, s.allState)
        assertFalse("空列表不能全选", s.canSelectAll)
    }

    @Test
    fun `选了部分是全选态是半选`() {
        val s = SelectionState.Snapshot(setOf("a"), 4)
        assertEquals(SelectionState.TriState.PARTIAL, s.allState)
    }

    @Test
    fun `全选中`() {
        val s = SelectionState.Snapshot(items.toSet(), 4)
        assertEquals(SelectionState.TriState.ON, s.allState)
        assertTrue(s.canClearAll)
    }

    @Test
    fun `一个都没选`() {
        val s = SelectionState.Snapshot(emptySet(), 4)
        assertEquals(SelectionState.TriState.OFF, s.allState)
        assertFalse(s.canClearAll)
    }

    // ═══════════════════════════════════════════════════════════
    //  单项切换
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `勾一项再加到已选`() {
        val s = SelectionState.toggle(SelectionState.empty(4), "a", items)
        assertEquals(setOf("a"), s.selected)
    }

    @Test
    fun `再点一次取消`() {
        val s1 = SelectionState.toggle(SelectionState.empty(4), "a", items)
        val s2 = SelectionState.toggle(s1, "a", items)
        assertTrue(s2.selected.isEmpty())
    }

    @Test
    fun `不存在的 id 被忽略`() {
        // ⚠️ 不报错：列表刚刷新掉某一项时用户正好点了它，是正常竞态。
        val s = SelectionState.toggle(SelectionState.empty(4), "zzz", items)
        assertTrue(s.selected.isEmpty())
    }

    @Test
    fun `取消一项后全选态变成半选`() {
        // ══════════════════════════════════════════════════════
        //  ★ 第 3 种错法
        // ══════════════════════════════════════════════════════
        // "全选后又取消一项，全选框不更新" ⇒ 用户看到全选仍勾着，
        // 但他刚才取消了一项。
        val all = SelectionState.Snapshot(items.toSet(), 4)
        assertEquals(SelectionState.TriState.ON, all.allState)

        val after = SelectionState.toggle(all, "b", items)
        assertEquals(
            "取消一项之后必须变半选",
            SelectionState.TriState.PARTIAL,
            after.allState,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  全选 / 清空
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `未选时点全选变全选`() {
        val s = SelectionState.toggleAll(SelectionState.empty(4), items)
        assertEquals(items.toSet(), s.selected)
        assertEquals(SelectionState.TriState.ON, s.allState)
    }

    @Test
    fun `全选时点全选变清空`() {
        val all = SelectionState.Snapshot(items.toSet(), 4)
        val s = SelectionState.toggleAll(all, items)
        assertTrue(s.selected.isEmpty())
    }

    @Test
    fun `半选时点全选变全选`() {
        // ★ 通行做法：半选 → 全选 → 清空 → 全选…
        //   反过来（半选 → 清空）会让用户连点两次才达到目的。
        val partial = SelectionState.Snapshot(setOf("a", "b"), 4)
        val s = SelectionState.toggleAll(partial, items)
        assertEquals(
            "半选状态下点击应当全选，而不是清空",
            items.toSet(),
            s.selected,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  列表变化后重算
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删掉一项后幽灵 id 被裁掉`() {
        // ══════════════════════════════════════════════════════
        //  ★ 第 4 种错法（最容易漏）
        // ══════════════════════════════════════════════════════
        // 勾全选 → 删一项 → 不重算的话 selected 里留着已删除的 id。
        // 那个幽灵会被当成"选了一项看不见的东西"传下去。
        val all = SelectionState.Snapshot(items.toSet(), 4)
        val remaining = listOf("a", "b", "c") // d 被删了

        val s = SelectionState.normalize(all, remaining)
        assertFalse("已删除的 id 必须被裁掉", "d" in s.selected)
        assertEquals(3, s.total)
    }

    @Test
    fun `删掉一项后全选态保持全选`() {
        // ★ 这是正确的行为：剩下的 3 项**都**还选着 ⇒ 仍是全选。
        //   错误实现会因为"selected.size(4) != total(3)"而误判成半选。
        val all = SelectionState.Snapshot(items.toSet(), 4)
        val s = SelectionState.normalize(all, listOf("a", "b", "c"))
        assertEquals(SelectionState.TriState.ON, s.allState)
    }

    @Test
    fun `增项后全选态变半选`() {
        val all = SelectionState.Snapshot(items.toSet(), 4)
        val s = SelectionState.normalize(all, items + "e")
        assertEquals("多了一项没选 ⇒ 半选", SelectionState.TriState.PARTIAL, s.allState)
    }

    @Test
    fun `清空列表后回到未选`() {
        val all = SelectionState.Snapshot(items.toSet(), 4)
        val s = SelectionState.normalize(all, emptyList())
        assertTrue(s.selected.isEmpty())
        assertEquals(0, s.total)
        assertEquals(SelectionState.TriState.OFF, s.allState)
    }

    @Test
    fun `normalize 不改动仍然存在的勾选`() {
        val s = SelectionState.Snapshot(setOf("a", "c"), 4)
        val after = SelectionState.normalize(s, items)
        assertEquals(setOf("a", "c"), after.selected)
    }

    // ═══════════════════════════════════════════════════════════
    //  摘要文案
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空列表的摘要不说零杠零`() {
        // ⚠️ "已选 0 / 0" 读起来像故障。
        val s = SelectionState.summary(SelectionState.empty(0))
        assertFalse("不该出现 0 / 0：$s", "0 / 0" in s)
        assertTrue("应当说没有可选的：$s", "没有" in s)
    }

    @Test
    fun `有选择时给出计数`() {
        val s = SelectionState.summary(SelectionState.Snapshot(setOf("a", "b"), 4))
        assertTrue("要给出已选数：$s", "2" in s)
        assertTrue("要给出总数：$s", "4" in s)
    }

    @Test
    fun `没选时给总数而不是已选零`() {
        val s = SelectionState.summary(SelectionState.Snapshot(emptySet(), 4))
        assertTrue("要说明共几项：$s", "4" in s)
        assertFalse("不该强调已选 0：$s", "已选 0" in s)
    }

    // ═══════════════════════════════════════════════════════════
    //  不变式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `任何操作之后 selected 都不超过 total`() {
        var s = SelectionState.empty(4)
        s = SelectionState.toggleAll(s, items)
        s = SelectionState.toggle(s, "a", items)
        s = SelectionState.toggle(s, "b", items)
        s = SelectionState.toggle(s, "c", items)
        s = SelectionState.toggle(s, "d", items)
        assertTrue("勾选数不能超过总数（${s.count} vs ${s.total}）", s.count <= s.total)
    }
}
