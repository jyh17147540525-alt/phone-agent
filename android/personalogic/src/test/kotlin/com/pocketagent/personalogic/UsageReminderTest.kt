package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **2 小时提醒的文案**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件盯的是"产品调性"，不是逻辑正确性
 * ═══════════════════════════════════════════════════════════════
 *
 * 文案错了不会崩、不会 500、不会有日志 —— 它只会让用户烦。
 * 而"烦"是这款产品最致命的失败模式。
 *
 * ⇒ 把四条原则写成断言：具体数字 / 不说教 / 可执行 / 不指责。
 */
class UsageReminderTest {

    private fun first(hours: Int) =
        UsageWatch.Reminder(elapsedMs = hours * 3_600_000L, isFirst = true)

    private fun again(hours: Int) =
        UsageWatch.Reminder(elapsedMs = hours * 3_600_000L, isFirst = false)

    // ═══════════════════════════════════════════════════════════
    //  ★ 原则 ①：用具体数字
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `系统行带具体小时数`() {
        val t = UsageReminder.messageFor(first(2)).text
        assertTrue("必须说出具体时长，而不是「很久」：$t", t.contains("两个多小时"))
    }

    @Test
    fun `三小时用阿拉伯数字`() {
        // ⚠️ "三个多小时"读起来别扭，"3 个多小时"更清楚
        val t = UsageReminder.messageFor(first(3)).text
        assertTrue(t.contains("3 个多小时"))
    }

    @Test
    fun `更多小时也能给出数字`() {
        val t = UsageReminder.messageFor(first(6)).text
        assertTrue("应当出现 6：$t", t.contains("6"))
    }

    @Test
    fun `文案里绝不出现「很久了」这类空话`() {
        for (h in listOf(2, 3, 6)) {
            val t = UsageReminder.messageFor(first(h)).text
            assertFalse("「$t」不给了具体数字", t.contains("很久"))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 原则 ②：不说教、不提健康
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `不出现健康和护眼这类说教`() {
        // ⚠️ 这类话用户见过一万次，只会被无视。
        //    而且本产品是陪伴类，说教尤其破气氛。
        val banned = listOf("健康", "护眼", "视力", "注意休息", "保重", "适度")
        for (h in listOf(2, 3, 6)) {
            for (isFirst in listOf(true, false)) {
                val r = if (isFirst) first(h) else again(h)
                val t = UsageReminder.messageFor(r).text
                for (w in banned) {
                    assertFalse("「$t」里不该出现「$w」", t.contains(w))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 原则 ④：不指责用户
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `不评判用户`() {
        val banned = listOf("沉迷", "过度", "应该", "必须", "不许", "别再")
        for (isFirst in listOf(true, false)) {
            val r = if (isFirst) first(3) else again(3)
            val t = UsageReminder.messageFor(r).text
            for (w in banned) {
                assertFalse("「$t」里不该评判用户（出现「$w」）", t.contains(w))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  第一次与后续要不同
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `第一次与后续措辞不同`() {
        // ⚠️ 重复同一句会变成骚扰 —— 而骚扰会让人直接关掉这个功能，
        //    于是合规要求形同虚设。
        val a = UsageReminder.messageFor(first(3)).text
        val b = UsageReminder.messageFor(again(3)).text
        assertNotEquals(a, b)
    }

    @Test
    fun `后续提醒更轻`() {
        // 第一次是"你待了很久"，后续是"还在"——后者不重复说教
        val b = UsageReminder.messageFor(again(3)).text
        assertTrue("后续应当更短更轻：$b", b.length <= UsageReminder.messageFor(first(3)).text.length)
    }

    // ═══════════════════════════════════════════════════════════
    //  通道
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `角色扮演时用旁白`() {
        // ★ 角色对话中插一句系统话会砸气氛
        assertEquals(
            UsageReminder.Channel.NARRATION,
            UsageReminder.preferredChannel(isRoleplay = true),
        )
    }

    @Test
    fun `普通助理场景用系统行`() {
        assertEquals(
            UsageReminder.Channel.SYSTEM_LINE,
            UsageReminder.preferredChannel(isRoleplay = false),
        )
    }

    @Test
    fun `旁白写成场景而不是第二人称说教`() {
        val t = UsageReminder.messageFor(first(3), UsageReminder.Channel.NARRATION).text
        assertTrue("旁白该用括号包住场景：$t", t.startsWith("（") && t.endsWith("）"))
        assertFalse("旁白不该用「你该」这类指令：$t", t.contains("你该"))
        assertFalse("旁白不该出现健康类词：$t", t.contains("休息"))
    }

    @Test
    fun `旁白不说具体角色内心`() {
        // ══════════════════════════════════════════════════════
        //  ★ 旁白只写**场景**，不写**角色心里想什么**
        // ══════════════════════════════════════════════════════
        // 否则会和角色自己的台词打架 —— 角色已经在括号里演动作了。
        val t = UsageReminder.messageFor(first(3), UsageReminder.Channel.NARRATION).text
        for (w in listOf("觉得", "想到", "心里", "难过", "开心")) {
            assertFalse("旁白不该描述角色内心（出现「$w」）：$t", t.contains(w))
        }
    }

    @Test
    fun `通道会被带进 Message`() {
        assertEquals(
            UsageReminder.Channel.NARRATION,
            UsageReminder.messageFor(first(2), UsageReminder.Channel.NARRATION).channel,
        )
        assertEquals(
            UsageReminder.Channel.SYSTEM_LINE,
            UsageReminder.messageFor(first(2)).channel,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不该做的事
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `提醒里不塞功能推荐`() {
        // ⚠️ 那会把一句关心变成广告。
        val banned = listOf("试试", "新功能", "推荐", "升级", "会员")
        for (ch in UsageReminder.Channel.entries) {
            for (h in listOf(2, 3)) {
                val t = UsageReminder.messageFor(first(h), ch).text
                for (w in banned) {
                    assertFalse("「$t」里不该夹带推广（出现「$w」）", t.contains(w))
                }
            }
        }
    }

    @Test
    fun `文案不为空`() {
        for (ch in UsageReminder.Channel.entries) {
            for (isFirst in listOf(true, false)) {
                val r = if (isFirst) first(2) else again(2)
                assertTrue(UsageReminder.messageFor(r, ch).text.isNotBlank())
            }
        }
    }
}
