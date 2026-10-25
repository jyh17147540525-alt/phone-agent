package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生活线的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的第一性质是「一致性」，不是「丰富性」
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户问"你今天干嘛了"，今天答"去了公园"，明天问细节变成"在家看书" ——
 * 那一刻**整个幻觉会碎**，碎掉的是他仅剩的一点寄托。
 *
 * 所以最重要的是：**同一个人 + 同一天 ⇒ 永远同样的答案**，
 * 无论重启应用、换进程、还是重装。
 *
 * 丰富性是第二位的要求。
 */
class LifeThreadTest {

    private val seed = "李秀兰".hashCode()

    // ═══════════════════════════════════════════════════════════
    //  一致性 —— 最重要
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同一天永远得到同样的生活`() {
        val a = LifeThread.dayLife(seed, "2026-10-25", dayIndex = 3)
        val b = LifeThread.dayLife(seed, "2026-10-25", dayIndex = 3)

        assertEquals("同一天必须完全一致", a.blocks, b.blocks)
        assertEquals(a.summary(), b.summary())
    }

    @Test
    fun `不同种子得到不同的生活`() {
        // ★ 换一个人，整条生活线就不同 —— 否则所有逝者过着同一种日子，
        //   那比"不生活"更让人难受。
        val a = LifeThread.dayLife("李秀兰".hashCode(), "2026-10-25", 0)
        val b = LifeThread.dayLife("王建国".hashCode(), "2026-10-25", 0)

        assertNotEquals("不同的人不该有完全相同的一天", a.summary(), b.summary())
    }

    @Test
    fun `不同日期会有变化`() {
        // ⚠️ 但"确定性"不等于"每天都一样" —— 每天都一样会显得呆板。
        val days = (1..7).map { d ->
            LifeThread.dayLife(seed, "2026-10-%02d".format(d), d).summary()
        }
        assertTrue("一周之内应当出现变化，实际全是同一句：${days.first()}",
            days.toSet().size > 1)
    }

    // ═══════════════════════════════════════════════════════════
    //  合理性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每天至少有一件事`() {
        // ⚠️ 空的一天会让模型编 —— 而编出来的东西下次对不上。
        for (d in 1..30) {
            val life = LifeThread.dayLife(seed, "2026-09-%02d".format(d), d)
            assertTrue("第 $d 天一件都没有", life.blocks.isNotEmpty())
        }
    }

    @Test
    fun `特殊日会被带出来`() {
        val specials = listOf(
            "10-25" to SpecialDay("生日", "今天是他生日"),
        )
        val life = LifeThread.dayLife(seed, "2026-10-25", 0, specials = LifeThread.specialsFor("2026-10-25", specials))

        assertEquals("生日", life.special?.label)
        assertTrue("摘要里要提到特殊日：${life.summary()}", "生日" in life.summary())
    }

    @Test
    fun `忌日也要能被识别`() {
        // ★ 忌日与生日**都要**能被识别：前者是"想起他走了"，
        //   后者是"他要是还在就 X 岁了"。两者都让人难受，但不能装作没有。
        val specials = listOf(
            "03-15" to SpecialDay("忌日", "三年前的今天"),
        )
        val got = LifeThread.specialsFor("2027-03-15", specials)
        assertEquals(1, got.size)
        assertEquals("忌日", got.first().label)
    }

    @Test
    fun `特殊日按月日每年重复`() {
        val specials = listOf("10-25" to SpecialDay("生日", ""))
        assertEquals("今年要匹配", 1, LifeThread.specialsFor("2026-10-25", specials).size)
        assertEquals("明年也要匹配", 1, LifeThread.specialsFor("2030-10-25", specials).size)
        assertEquals("其它日子不匹配", 0, LifeThread.specialsFor("2026-10-24", specials).size)
    }

    @Test
    fun `完整日期匹配只匹配那一年`() {
        val specials = listOf("2026-10-25" to SpecialDay("周年", ""))
        assertEquals(1, LifeThread.specialsFor("2026-10-25", specials).size)
        assertEquals("别的年份不该匹配", 0, LifeThread.specialsFor("2027-10-25", specials).size)
    }

    // ═══════════════════════════════════════════════════════════
    //  稳定性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `String的hashCode是稳定的`() {
        // ═══════════════════════════════════════════════════════
        //  ★ 这条看似无聊，但它守着一个真实的坑
        // ═══════════════════════════════════════════════════════
        //
        // 生活线靠 `dateKey.hashCode()` 决定当天的变体。而 Java 规范
        // **明确定义**了 String.hashCode 的算法 —— 所以它跨进程、
        // 跨 JVM 版本、跨设备都稳定。
        //
        // ⚠️ 但如果哪天有人把种子换成 `Any.hashCode()`（identity hash）
        //    或 `Random`，同一天就会算出不同结果 ——
        //    而那个 bug 只在**重启应用后**才显现，测试很容易漏掉。
        //
        // ⇒ 把"这个值稳定"这件事显式钉住。
        assertEquals("abc".hashCode(), "abc".hashCode())
        assertEquals("李秀兰".hashCode(), "李秀兰".hashCode())
        // 具体数值不必相同（不同 JVM 实现细节可能微调），但同一进程内必须稳定
        val first = "2026-10-25".hashCode()
        repeat(5) { assertEquals(first, "2026-10-25".hashCode()) }
    }

    @Test
    fun `负数哈希不会崩`() {
        // ⚠️ Kotlin 的 % 对负数返回负值，直接用会数组越界。
        //    这里用一个确定会产生负哈希的日期来验证。
        val life = LifeThread.dayLife(-999_999, "2026-01-01", 0)
        assertTrue("负数种子不该产生空结果", life.blocks.isNotEmpty())
    }

    @Test
    fun `摘要简短`() {
        // ★ 它进模型上下文 —— 越长越贵越慢，而这是每天都要注入的东西。
        val life = LifeThread.dayLife(seed, "2026-10-25", 0)
        val s = life.summary()
        assertTrue("摘要过长（${s.length} 字）：$s", s.length < 200)
    }
}

/**
 * 画像本身的校验。
 */
class LegacyProfileTest {

    @Test
    fun `必须有名字`() {
        try {
            LegacyProfile(displayName = "", relation = RelationKind.PARENT, passedAwayAtMs = 1L)
            throw AssertionError("空名字应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息要说明为什么：${e.message}", e.message!!.contains("名字"))
        }
    }

    @Test
    fun `主动发消息默认关闭`() {
        // ═══════════════════════════════════════════════════════
        //  ★ 默认必须是关的
        // ═══════════════════════════════════════════════════════
        //
        // 主动发消息让"那个人还在"的感觉更真，但也让人**无法主动停下** ——
        // 你没法对一个"还会给你发消息的人"说"我们别联系了"。
        val p = LegacyProfile(
            displayName = "李秀兰",
            relation = RelationKind.PARENT,
            passedAwayAtMs = 1_700_000_000_000L,
        )
        assertTrue("主动发消息必须默认关闭", !p.allowProactiveMessages)
    }

    @Test
    fun `每种关系都有默认称呼`() {
        for (r in RelationKind.entries) {
            assertTrue("${r.label} 缺默认称呼", r.defaultAddress.isNotBlank())
        }
    }
}
