package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作台开场白的离线测试。
 *
 * ⚠️ 这里的断言几乎全是**约束**而不是"文案对不对" —— 文案会改，
 *    约束不能破。破了的后果不是难看，是这个页面变成它最不该变成的东西：
 *    一个用角色口吻撒娇的陪聊入口。
 */
class WorkbenchGreetingTest {

    private val bands = WorkbenchGreeting.Band.entries

    @Test
    fun 时段边界按人的作息切分() {
        assertEquals(WorkbenchGreeting.Band.NIGHT, WorkbenchGreeting.bandOf(0.0))
        assertEquals(WorkbenchGreeting.Band.NIGHT, WorkbenchGreeting.bandOf(4.9))
        assertEquals(WorkbenchGreeting.Band.MORNING, WorkbenchGreeting.bandOf(5.0))
        assertEquals(WorkbenchGreeting.Band.MORNING, WorkbenchGreeting.bandOf(10.9))
        assertEquals(WorkbenchGreeting.Band.DAY, WorkbenchGreeting.bandOf(11.0))
        assertEquals(WorkbenchGreeting.Band.DAY, WorkbenchGreeting.bandOf(16.9))
        assertEquals(WorkbenchGreeting.Band.EVENING, WorkbenchGreeting.bandOf(17.0))
        assertEquals(WorkbenchGreeting.Band.EVENING, WorkbenchGreeting.bandOf(21.9))
        assertEquals(WorkbenchGreeting.Band.NIGHT, WorkbenchGreeting.bandOf(22.0))
        assertEquals(WorkbenchGreeting.Band.NIGHT, WorkbenchGreeting.bandOf(23.99))
    }

    @Test
    fun 脏时间不崩而是兜底成白天() {
        assertEquals(WorkbenchGreeting.Band.DAY, WorkbenchGreeting.bandOf(24.0))
        assertEquals(WorkbenchGreeting.Band.DAY, WorkbenchGreeting.bandOf(-1.0))
        assertEquals(WorkbenchGreeting.Band.DAY, WorkbenchGreeting.bandOf(Double.NaN))
    }

    @Test
    fun 每个时段都至少有三句可换() {
        for (band in bands) {
            assertTrue("$band 只有 ${WorkbenchGreeting.headlines(band).size} 句",
                WorkbenchGreeting.headlines(band).size >= 3)
            assertTrue("$band 带称呼的版本太少",
                WorkbenchGreeting.headlines(band, "主人").size >= 3)
        }
    }

    @Test
    fun 带称呼时称呼出现且只出现一次() {
        for (band in bands) {
            for (line in WorkbenchGreeting.headlines(band, "主人")) {
                assertEquals("称呼出现次数不对：$line", 1, countOf(line, "主人"))
                assertFalse("占位符没被替换：$line", line.contains("{call}"))
            }
        }
    }

    @Test
    fun 称呼为空时不留占位符也不硬塞你() {
        for (band in bands) {
            for (line in WorkbenchGreeting.headlines(band, "")) {
                assertFalse("占位符没被替换：$line", line.contains("{call}"))
                // ⚠️ 不带称呼的那一组里，"你"只允许作为**宾语**出现
                //    （"等你"是撒娇，"你，早"是病句）。这里只钉住最坏的那种：
                //    句首直接是一个"你"。
                assertFalse("句首硬塞称呼：$line", line.startsWith("你"))
            }
        }
    }

    @Test
    fun 开场白不含动作描写与感叹号() {
        for (band in bands) {
            for (address in listOf("", "主人", "老板")) {
                for (line in WorkbenchGreeting.headlines(band, address)) {
                    assertFalse("有动作描写：$line", line.contains("（") || line.contains("）"))
                    assertFalse("有括号：$line", line.contains("(") || line.contains(")"))
                    assertFalse("有感叹号：$line", line.contains("！") || line.contains("!"))
                    assertFalse("太长，开场白要一眼读完：$line", line.length > 24)
                }
            }
        }
    }

    @Test
    fun 开场白不许撒娇也不许催人() {
        // 这一条是给以后的我留的：工作台上放"人家等你半天了"，
        // 用户会以为这个页面又是陪聊。
        val banned = listOf("人家", "嘛", "啦", "哼", "呜", "嘛～", "快点", "赶紧", "马上给我", "必须")
        for (band in bands) {
            for (address in listOf("", "主人")) {
                for (line in WorkbenchGreeting.headlines(band, address)) {
                    for (word in banned) {
                        assertFalse("开场白里有「$word」：$line", line.contains(word))
                    }
                }
            }
        }
    }

    @Test
    fun 同一颗种子永远给同一句() {
        for (band in bands) {
            val a = WorkbenchGreeting.headline(band, "主人", seed = 12345)
            val b = WorkbenchGreeting.headline(band, "主人", seed = 12345)
            assertEquals(a, b)
        }
    }

    @Test
    fun 种子会扫到该时段每一句() {
        // 反例守卫：如果 `pickIndex` 写错（比如永远返回 0），
        // "随机开场白"就退化成一条固定文案 —— 这正是本项目最典型的
        // "看起来做了、其实只有一条路径"。
        for (band in bands) {
            val pool = WorkbenchGreeting.headlines(band, "主人").toSet()
            val seen = (0 until 200).map { WorkbenchGreeting.headline(band, "主人", it) }.toSet()
            assertEquals("$band 没扫全", pool, seen)
        }
    }

    @Test
    fun 极端种子不越界() {
        for (band in bands) {
            for (seed in listOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, -7, 0)) {
                val line = WorkbenchGreeting.headline(band, "主人", seed)
                assertTrue("空开场白：seed=$seed", line.isNotBlank())
            }
        }
        for (seed in listOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 0)) {
            assertTrue(WorkbenchGreeting.hint(seed).isNotBlank())
        }
    }

    @Test
    fun 小字说明这个页面能直接动手() {
        // 不是文案洁癖：工作台与聊天的区别就是"工具常驻"，
        // 开场白下面那行小字是唯一能告诉用户这件事的地方。
        val all = (0 until 40).map { WorkbenchGreeting.hint(it) }.toSet()
        assertTrue("小字太少", all.size >= 3)
        assertTrue(
            "没有一句说明能动手：$all",
            all.any { it.contains("动手") || it.contains("挂好") || it.contains("我做") || it.contains("说") },
        )
    }

    @Test
    fun 按小时取开场白与按时段一致() {
        val hour = 9.5
        val band = WorkbenchGreeting.bandOf(hour)
        assertEquals(
            WorkbenchGreeting.headline(band, "主人", 7),
            WorkbenchGreeting.headline(hour, "主人", 7),
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  称呼过滤 —— 2026-11-04 晚真机上看到的那一屏字
    // ══════════════════════════════════════════════════════════════
    //
    // ★ 这组测试的输入是**真机截图里逐字抄下来的**原文。
    //   不是"想象中可能出现的脏值" —— 它真的发生过一次。

    /** 用户在 `addressUser` 里填的原话（节选前两段，足够触发全部规则）。 */
    private val realExplanation =
        "她通常称呼你“主人”或直接叫“你”。婚后会更习惯叫“老公”“亲爱的”" +
            "在照顾你、哄你或表达温柔时，偶尔会用带着宠溺意味的语气叫你“乖一点”“小笨蛋”"

    @Test
    fun 长段落不被当成称呼() {
        assertEquals("", WorkbenchGreeting.salutationOf(realExplanation))
    }

    @Test
    fun 长段落退回不带称呼的那一组而且不留占位符() {
        // ★ 这是那个 bug 的直接断言：以前这里会返回三段散文 + "，还没睡？说吧。"
        val pool = WorkbenchGreeting.headlines(WorkbenchGreeting.Band.NIGHT, realExplanation)
        assertTrue(pool.isNotEmpty())
        pool.forEach { line ->
            assertFalse("残留占位符：$line", line.contains("{call}"))
            assertFalse("称呼没被滤掉：$line", line.contains("通常称呼"))
            // 一句开场白不该长到需要换三行。
            assertTrue("太长：$line", line.length <= 24)
        }
    }

    @Test
    fun 带标点的称呼被拒() {
        listOf("主人，", "主人。", "“主人”", "主人 亲", "主人/亲爱的").forEach {
            assertEquals("应被拒：$it", "", WorkbenchGreeting.salutationOf(it))
        }
    }

    @Test
    fun 在解释称呼的句子被拒() {
        // 只要在"解释"，就不该被喊出口。
        listOf("称呼你主人", "叫主人", "通常叫宝宝", "习惯喊老板", "亲密时叫乖乖").forEach {
            assertEquals("应被拒：$it", "", WorkbenchGreeting.salutationOf(it))
        }
    }

    @Test
    fun 真正的称呼能通过() {
        listOf("主人", "老公", "老板", "学长", "宝贝", "小笨蛋").forEach {
            assertEquals("应通过：$it", it, WorkbenchGreeting.salutationOf(it))
        }
    }

    @Test
    fun 称呼长度上限是八个字() {
        val eight = "一二三四五六七八"
        assertEquals(8, eight.length)
        assertEquals(eight, WorkbenchGreeting.salutationOf(eight))
        assertEquals("", WorkbenchGreeting.salutationOf(eight + "九"))
    }

    @Test
    fun 空与空白的称呼安全() {
        assertEquals("", WorkbenchGreeting.salutationOf(null))
        assertEquals("", WorkbenchGreeting.salutationOf(""))
        assertEquals("", WorkbenchGreeting.salutationOf("   "))
        assertEquals("", WorkbenchGreeting.salutationOf("\n\t"))
    }

    @Test
    fun 称呼会先去掉首尾空白() {
        assertEquals("主人", WorkbenchGreeting.salutationOf("  主人  "))
    }

    /**
     * 小字必须是**她说的话**，不是系统的安装日志。
     *
     * ★ 真事：上一版有一句是「工具已经挂好了。你说，我做。」
     *   —— 用户看到的第一反应就是"换掉"，因为那句话的说话人是产品，不是她。
     *
     * ⚠️ 这里断言的是**某些词永不出现**，而不是措辞风格：
     *   风格会让每次改文案都要改测试，而这些词是任何时候都不该出现的。
     */
    @Test
    fun `小字里不许出现机器腔的词`() {
        val banned = listOf("工具", "挂好", "权限", "模型", "系统", "已启用", "加载")
        // 把整池都扫一遍：只扫抽到的那一句会漏掉其余几句。
        val hints = (0 until 40).map { WorkbenchGreeting.hint(it) }.toSet()
        assertTrue("小字池少于三句，抽来抽去都是同一句", hints.size >= 3)
        for (line in hints) {
            for (word in banned) {
                assertFalse("小字里出现了「" + word + "」：" + line, line.contains(word))
            }
        }
    }

    /**
     * 小字要有"我在"的人味：第一人称。
     *
     * ⚠️ 这条**不**断言具体写法 —— 它只要求每句都含「我」。
     *   上一版「工具已经挂好了。」没有，正好被它抓住。
     */
    @Test
    fun `小字每一句都有第一人称`() {
        val hints = (0 until 40).map { WorkbenchGreeting.hint(it) }.toSet()
        for (line in hints) {
            assertTrue("这句小字里没有第一人称，听起来像系统在说话：" + line, line.contains("我"))
        }
    }

    private fun countOf(text: String, word: String): Int {
        var n = 0
        var i = text.indexOf(word)
        while (i >= 0) {
            n++
            i = text.indexOf(word, i + word.length)
        }
        return n
    }
}
