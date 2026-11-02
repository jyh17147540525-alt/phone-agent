package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **场记的刹车** —— 别把角色做成连续剧。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"导演太勤奋"
 * ═══════════════════════════════════════════════════════════════
 *
 * 一个每轮都注入事件、每轮都有"我想起伤心事"的导演，
 * 会让角色变成肥皂剧。真实的生活**大部分时间是没事发生的**。
 *
 * ⇒ 限频、上限、去重这些**可判定的规则必须写在代码里**。
 *   写在提示词里只是"请不要太频繁" —— 模型会照做吗？偶尔。
 */
class DirectorGateTest {

    private fun ev(kind: DirectorEventKind, reason: String = "用户说他今天没睡") =
        DirectorEvent(kind, reason)

    private fun out(vararg events: DirectorEvent) = DirectorOutput(events = events.toList())

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 1：一轮最多一个事件
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `一轮最多放行一个事件`() {
        val r = DirectorGate.accept(
            output = out(
                ev(DirectorEventKind.USER_UNWELL),
                ev(DirectorEventKind.WARMED),
                ev(DirectorEventKind.OUTSIDE_CHANGED),
            ),
            trace = DirectorTrace(),
            turnIndex = 10,
            dayIndex = 1,
        )
        assertEquals(1, r.events.size)
        assertEquals("被拦掉的要能看见", 2, r.droppedEvents)
    }

    @Test
    fun `大多数轮次什么都没发生`() {
        val r = DirectorGate.accept(
            output = DirectorOutput(),
            trace = DirectorTrace(),
            turnIndex = 5,
            dayIndex = 1,
        )
        assertTrue(r.events.isEmpty())
        assertTrue(r.smallMoments.isEmpty())
        assertEquals(0, r.droppedEvents)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 2：冷却
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同一类事件不会连着触发`() {
        val trace = DirectorTrace()
        val first = DirectorGate.accept(
            out(ev(DirectorEventKind.HURT)),
            trace, turnIndex = 10, dayIndex = 1,
        )
        assertEquals(1, first.events.size)

        // 紧接着的几轮：同类不该再来
        for (t in 11..15) {
            val again = DirectorGate.accept(
                out(ev(DirectorEventKind.HURT)),
                first.trace, turnIndex = t, dayIndex = 1,
            )
            assertTrue("冷却期内不该再触发（第 $t 轮）", again.events.isEmpty())
        }
    }

    @Test
    fun `冷却结束后可以再触发`() {
        val trace = DirectorTrace()
        val first = DirectorGate.accept(
            out(ev(DirectorEventKind.HURT)), trace, turnIndex = 0, dayIndex = 1,
        )
        val later = DirectorGate.accept(
            out(ev(DirectorEventKind.HURT)),
            first.trace,
            turnIndex = DirectorPolicy.Default.cooldownTurns,
            dayIndex = 1,
        )
        assertEquals(1, later.events.size)
    }

    @Test
    fun `不同类事件不受彼此的冷却影响`() {
        val first = DirectorGate.accept(
            out(ev(DirectorEventKind.HURT)), DirectorTrace(), turnIndex = 0, dayIndex = 1,
        )
        val other = DirectorGate.accept(
            out(ev(DirectorEventKind.WARMED)), first.trace, turnIndex = 1, dayIndex = 1,
        )
        assertEquals("另一类不该被前一类挡住", 1, other.events.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 3：每日上限
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `一天之内事件数有上限`() {
        var trace = DirectorTrace(dayIndex = 1)
        var fired = 0
        var turn = 0
        // 每轮换个类，绕开同类冷却，专门压每日上限
        val kinds = DirectorEventKind.entries
        for (i in 0 until 40) {
            val k = kinds[i % kinds.size]
            val r = DirectorGate.accept(out(ev(k)), trace, turnIndex = turn, dayIndex = 1)
            if (r.events.isNotEmpty()) fired++
            trace = r.trace
            turn += DirectorPolicy.Default.cooldownTurns
        }
        assertTrue(
            "一天不该触发超过 ${DirectorPolicy.Default.maxEventsPerDay} 次：实际 $fired",
            fired <= DirectorPolicy.Default.maxEventsPerDay,
        )
    }

    @Test
    fun `换一天会清零`() {
        var trace = DirectorTrace(dayIndex = 1, eventsToday = 8)
        val r = DirectorGate.accept(
            out(ev(DirectorEventKind.WARMED)), trace, turnIndex = 100, dayIndex = 2,
        )
        assertEquals("新的一天应当重置", 1, r.events.size)
        assertEquals(1, r.trace.eventsToday)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 4：事实去重
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `已存在的事实不会重复提议`() {
        val r = DirectorGate.accept(
            output = DirectorOutput(
                proposedFacts = listOf(
                    ProposedFact("f1", "钥匙", listOf("钥匙"), "钥匙在她那"),
                ),
            ),
            trace = DirectorTrace(),
            turnIndex = 0,
            dayIndex = 1,
            knownFactIds = setOf("f1"),
        )
        assertTrue("已有的 id 不该再提议", r.proposedFacts.isEmpty())
    }

    @Test
    fun `没有触发词的事实会被丢弃`() {
        // ⚠️ 没有触发词的条目**永远检索不到** —— 那是世界书最常见的死法
        val r = DirectorGate.accept(
            output = DirectorOutput(
                proposedFacts = listOf(
                    ProposedFact("f1", "孤儿", emptyList(), "没人会检索到我"),
                    ProposedFact("f2", "正常", listOf("钥匙"), "能被检索到"),
                ),
            ),
            trace = DirectorTrace(),
            turnIndex = 0,
            dayIndex = 1,
        )
        assertEquals(listOf("f2"), r.proposedFacts.map { it.id })
    }

    @Test
    fun `同一批里重复的 id 只留一个`() {
        val r = DirectorGate.accept(
            output = DirectorOutput(
                proposedFacts = listOf(
                    ProposedFact("f1", "A", listOf("钥匙"), "第一次"),
                    ProposedFact("f1", "A", listOf("钥匙"), "第二次"),
                ),
            ),
            trace = DirectorTrace(),
            turnIndex = 0,
            dayIndex = 1,
        )
        assertEquals(1, r.proposedFacts.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  小事
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `小事最多两条`() {
        // 多了会把这一句变成散文
        val r = DirectorGate.accept(
            output = DirectorOutput(
                smallMoments = listOf("窗外下雨", "楼下有猫", "暖气有点响", "手机震了一下"),
            ),
            trace = DirectorTrace(),
            turnIndex = 0,
            dayIndex = 1,
        )
        assertEquals(2, r.smallMoments.size)
    }

    @Test
    fun `小事不消耗事件额度`() {
        // ⚠️ 混在一起会让"窗外下雨"变成"所以她很抑郁"
        val r = DirectorGate.accept(
            output = DirectorOutput(smallMoments = listOf("窗外下雨")),
            trace = DirectorTrace(),
            turnIndex = 0,
            dayIndex = 1,
        )
        assertEquals(0, r.trace.eventsToday)
    }

    // ═══════════════════════════════════════════════════════════
    //  由头
    // ═══════════════════════════════════════════════════════════

    @Test(expected = IllegalArgumentException::class)
    fun `没有由头的事件会被拒绝`() {
        // ══════════════════════════════════════════════════════
        //  ★ 项目既有纪律："没有由头的情绪就是随机情绪，
        //    而随机情绪 = 崩人设"
        // ══════════════════════════════════════════════════════
        DirectorEvent(DirectorEventKind.HURT, "   ")
    }

    @Test
    fun `由头要是具体的事 不是系统语言`() {
        // 这条是**写作提醒**而不是断言 —— 保留它是因为下一个改提示词的人
        // 会先看到这个测试名。
        val good = DirectorEvent(DirectorEventKind.USER_UNWELL, "用户说他发烧了")
        assertTrue("具体的事可以复述", good.reason.contains("发烧"))
        assertFalse("不该是系统语言", good.reason.contains("检测到"))
    }
}
