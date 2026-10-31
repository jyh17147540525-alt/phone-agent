package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **叠卡展开**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条会被用户直接感觉到的规则
 * ═══════════════════════════════════════════════════════════════
 *
 *  ① 少于一叠的数量不该叠（两张做成叠卡，要多点一次才看到第二张）
 *  ② 露出的层数必须封顶（露 10 张是"歪了一堆"，不是设计）
 *  ③ 点击超限区的卡，它必须被换进可见区（否则用户点了没反应）
 */
class CardStackTest {

    private fun ids(n: Int): List<String> = (1..n).map { "task:$it" }

    // ═══════════════════════════════════════════════════════════
    //  ① 该不该叠
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `两张不叠`() {
        val s = CardStack.Stack(ids(2))
        assertFalse("两张做成叠卡要多点一次才看到第二张", CardStack.shouldStack(s))
    }

    @Test
    fun `三张起叠`() {
        assertTrue(CardStack.shouldStack(CardStack.Stack(ids(3))))
        assertTrue(CardStack.shouldStack(CardStack.Stack(ids(10))))
    }

    @Test
    fun `空栈不崩`() {
        val s = CardStack.Stack(emptyList())
        assertEquals(0, s.size)
        assertFalse(CardStack.shouldStack(s))
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 露出层数封顶
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `叠起来时只露三张的边`() {
        // ★ 露 10 张就是"一堆卡片往右下角歪" —— 那是脏，不是设计。
        assertEquals(3, CardStack.STACK_VISIBLE)
        for (i in 0 until CardStack.STACK_VISIBLE) {
            assertNotNull("第 $i 张应当有偏移", CardStack.stackOffset(i))
        }
        assertNull("第 3 张往后不该再画", CardStack.stackOffset(CardStack.STACK_VISIBLE))
        assertNull("更靠后的也不该画", CardStack.stackOffset(10))
    }

    @Test
    fun `叠起来时的偏移递增`() {
        val offsets = (0 until CardStack.STACK_VISIBLE).mapNotNull { CardStack.stackOffset(it) }
        assertEquals("偏移必须逐张增大：$offsets", offsets.sorted(), offsets)
        assertEquals("第一张不该偏移", 0f, offsets.first(), 0.0001f)
    }

    @Test
    fun `透明度递减但有下限`() {
        val alphas = (0 until CardStack.STACK_VISIBLE).mapNotNull { CardStack.stackAlpha(it) }
        // 递减
        assertEquals("透明度必须递减：$alphas", alphas.sortedDescending(), alphas)
        // ⚠️ 最后一张不能淡到看不见 —— 用户会以为那一叠只有两张。
        assertTrue("最后一张的透明度不能低于 0.55：$alphas", alphas.last() >= 0.55f)
    }

    @Test
    fun `缩放递减但不小于零点九`() {
        val scales = (0 until CardStack.STACK_VISIBLE).mapNotNull { CardStack.stackScale(it) }
        assertEquals("缩放必须递减：$scales", scales.sortedDescending(), scales)
        assertEquals("第一张原尺寸", 1f, scales.first(), 0.0001f)
        // 缩太多会看起来像"另一张卡"
        assertTrue("最后一张不能缩太多：$scales", scales.last() >= 0.9f)
    }

    @Test
    fun `负数下标返回 null`() {
        assertNull(CardStack.stackOffset(-1))
        assertNull(CardStack.stackAlpha(-1))
        assertNull(CardStack.stackScale(-1))
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 展开
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `少量全部展开`() {
        val s = CardStack.Stack(ids(5))
        val e = CardStack.expand(s)
        assertEquals(5, e.shown.size)
        assertEquals(0, e.hiddenCount)
        assertFalse(e.isTruncated)
    }

    @Test
    fun `超限只展开前八张`() {
        val s = CardStack.Stack(ids(20))
        val e = CardStack.expand(s)
        assertEquals(CardStack.EXPAND_LIMIT, e.shown.size)
        assertEquals("超出部分要报数", 12, e.hiddenCount)
        assertTrue(e.isTruncated)
    }

    @Test
    fun `点击超限区的卡会被换进可见区`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条守的是"点了没反应"
        // ══════════════════════════════════════════════════════
        // 用户点的是第 12 张，而展开只显示前 8 张 ——
        // 如果不做替换，用户会以为点击没生效。
        val all = ids(20)
        val focus = all[15]
        val e = CardStack.expand(CardStack.Stack(all), focusId = focus)

        assertTrue("被点的那张必须出现在展开结果里", focus in e.shown)
        assertEquals("总显示数不变", CardStack.EXPAND_LIMIT, e.shown.size)
        assertTrue("被点的那张应当在末尾（换掉最后一个）", e.shown.last() == focus)
    }

    @Test
    fun `点击可见区的卡正常展开`() {
        val all = ids(20)
        val e = CardStack.expand(CardStack.Stack(all), focusId = all[2])
        assertEquals("不该因为 focus 在前段而改变顺序", all.take(CardStack.EXPAND_LIMIT), e.shown)
    }

    @Test
    fun `focusId 不存在时不崩`() {
        val e = CardStack.expand(CardStack.Stack(ids(20)), focusId = "not-there")
        assertEquals(CardStack.EXPAND_LIMIT, e.shown.size)
    }

    @Test
    fun `不超限时 focusId 不影响`() {
        val all = ids(4)
        val e = CardStack.expand(CardStack.Stack(all), focusId = all.last())
        assertEquals(all, e.shown)
    }

    // ═══════════════════════════════════════════════════════════
    //  分组
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `按任务前缀分组`() {
        val ids = listOf("t1:a", "t1:b", "t2:c", "t1:d")
        val groups = CardStack.groupByTask(ids)
        assertEquals(2, groups.size)
        // 每组的成员
        val t1 = groups.first { "t1:a" in it.ids }
        assertEquals(listOf("t1:a", "t1:b", "t1:d"), t1.ids)
    }

    @Test
    fun `分组保持原顺序`() {
        // ⚠️ 顺序很重要：卡片是按时间排的，乱了会让用户看到"未来在前"。
        val groups = CardStack.groupByTask(listOf("t1:a", "t2:b", "t1:c"))
        assertEquals("第一组应当是 t1", "t1:a", groups.first().ids.first())
    }

    @Test
    fun `格式不认识的 id 各自成组`() {
        // ★ 退化成"每个 id 自己一组" —— 叠卡不生效，但**不会错组**。
        //   错组比不分组糟得多（会把两件事的卡摞在一起）。
        val groups = CardStack.groupByTask(listOf("a", "b", "c"))
        assertEquals(3, groups.size)
    }

    @Test
    fun `groupKeyOf 取最后一段前缀`() {
        assertEquals("t1", CardStack.groupKeyOf("t1:a"))
        assertEquals("t1:a", CardStack.groupKeyOf("t1:a:b"))
        assertEquals("plain", CardStack.groupKeyOf("plain"))
        assertEquals(":lead", CardStack.groupKeyOf(":lead"))
    }

    @Test
    fun `空输入返回空分组`() {
        assertTrue(CardStack.groupByTask(emptyList()).isEmpty())
    }
}
