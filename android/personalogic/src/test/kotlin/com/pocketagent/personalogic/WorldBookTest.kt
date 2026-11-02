package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **世界书的规矩** —— 尤其是"AI 不许改设定"这条。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件主要防"世界书崩"
 * ═══════════════════════════════════════════════════════════════
 *
 * 四种崩法（见 `WorldBook` 的类注释），每一类在这里都有钉子。
 */
class WorldBookTest {

    private fun setting(
        id: String,
        keys: List<String> = emptyList(),
        content: String = "内容$id",
        priority: Int = 50,
        always: Boolean = false,
    ) = WorldEntry(
        id = id,
        title = "标题$id",
        keys = keys,
        content = content,
        priority = priority,
        alwaysInclude = always,
        kind = WorldEntryKind.SETTING,
    )

    private fun fact(id: String, keys: List<String>, content: String = "事实$id") = WorldEntry(
        id = id,
        title = "事实$id",
        keys = keys,
        content = content,
        kind = WorldEntryKind.FACT,
    )

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 1：SETTING 与 FACT 分开
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `事实不能通过设定的入口写入`() {
        // ⚠️ 类型挡死，不靠提示词说"不要改设定"
        val book = WorldBook(emptyList())
        var threw = false
        try {
            book.withSetting(fact("f1", listOf("钥匙")))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("withSetting 必须拒绝 FACT", threw)
    }

    @Test
    fun `设定不能通过事实的入口写入`() {
        val book = WorldBook(emptyList())
        var threw = false
        try {
            book.withFact(setting("s1", listOf("钥匙")), confirmed = true)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("withFact 必须拒绝 SETTING", threw)
    }

    @Test
    fun `未经确认的事实不会被写入`() {
        // ══════════════════════════════════════════════════════
        //  ★ "AI 不许自己写设定"的执行点
        // ══════════════════════════════════════════════════════
        val book = WorldBook(emptyList()).withFact(
            fact("f1", listOf("钥匙")),
            confirmed = false,
        )
        assertTrue("未确认的事实不该进库", book.all().isEmpty())
    }

    @Test
    fun `确认后的事实会写入`() {
        val book = WorldBook(emptyList()).withFact(fact("f1", listOf("钥匙")), confirmed = true)
        assertEquals(1, book.all().size)
    }

    @Test
    fun `事实永远不能覆盖同名的设定`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这是"崩"的经典路径
        // ══════════════════════════════════════════════════════
        // AI 为了圆这一轮的台词，把用户的设定改成别的内容。
        val book = WorldBook(emptyList())
            .withSetting(setting("x", keys = listOf("戒指"), content = "设定：戒指是母亲留下的"))
            .withFact(
                WorldEntry(
                    id = "x",
                    title = "事实",
                    keys = listOf("戒指"),
                    content = "事实：戒指是买来的",
                    kind = WorldEntryKind.FACT,
                ),
                confirmed = true,
            )

        val kept = book.all().first { it.id == "x" }
        assertEquals(WorldEntryKind.SETTING, kept.kind)
        assertTrue("设定不该被事实改写：${kept.content}", kept.content.contains("母亲"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 2：检索确定性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `同样的输入永远给同样的输出`() {
        val book = WorldBook(
            listOf(
                setting("a", listOf("钥匙"), priority = 50),
                setting("b", listOf("钥匙"), priority = 50),
                setting("c", listOf("钥匙"), priority = 50),
            ),
        )
        val first = book.select("钥匙在哪").map { it.id }
        repeat(5) {
            assertEquals("同样的输入必须稳定：$first", first, book.select("钥匙在哪").map { it.id })
        }
    }

    @Test
    fun `没命中的条目不会出现`() {
        val book = WorldBook(listOf(setting("a", listOf("钥匙")), setting("b", listOf("雨伞"))))
        val picked = book.select("钥匙在哪").map { it.id }
        assertTrue("a" in picked)
        assertFalse("b" in picked)
    }

    @Test
    fun `空查询只给核心条目`() {
        val book = WorldBook(
            listOf(
                setting("core", content = "你们是恋人", always = true),
                setting("scene", listOf("钥匙")),
            ),
        )
        val picked = book.select("").map { it.id }
        assertEquals(listOf("core"), picked)
    }

    @Test
    fun `长触发词比短触发词更具体`() {
        // 「左脚踝」比「脚」更能定位
        val book = WorldBook(
            listOf(
                setting("generic", listOf("脚"), content = "泛泛的脚"),
                setting("specific", listOf("左脚踝"), content = "具体的左脚踝"),
            ),
        )
        val picked = book.select("我的左脚踝疼")
        assertEquals("更具体的应当排前面", "specific", picked.first().id)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 不变量 3：预算
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `超出预算会被裁掉`() {
        val many = (1..30).map {
            setting("s$it", listOf("钥匙"), content = "第 $it 条".repeat(20), priority = it)
        }
        val book = WorldBook(many, RetrievalBudget(totalChars = 300, coreChars = 100))
        val picked = book.select("钥匙")
        val total = picked.sumOf { it.content.length }
        assertTrue("总字数应当受控：$total", total <= 300 + 200) // 首条无条件放行
        assertTrue("不该全塞进来", picked.size < 30)
    }

    @Test
    fun `预算再小也至少留一条`() {
        // ⚠️ 否则"预算配小了"会表现为"世界书完全没生效"，很难查
        val book = WorldBook(
            listOf(setting("a", listOf("钥匙"), content = "很长的一条".repeat(50))),
            RetrievalBudget(totalChars = 10, coreChars = 0),
        )
        val picked = book.select("钥匙")
        assertEquals("至少保留一条", 1, picked.size)
    }

    @Test
    fun `核心条目不参与场景竞争`() {
        val book = WorldBook(
            listOf(
                setting("core", content = "核心", always = true, priority = 100),
                setting("scene", listOf("钥匙"), priority = 100),
            ),
            RetrievalBudget(totalChars = 200, coreChars = 100),
        )
        val picked = book.select("钥匙").map { it.id }
        assertTrue("核心必须在场", "core" in picked)
    }

    @Test
    fun `核心太多时按优先级裁`() {
        val book = WorldBook(
            listOf(
                setting("low", content = "低".repeat(60), always = true, priority = 10),
                setting("high", content = "高".repeat(60), always = true, priority = 90),
            ),
            RetrievalBudget(totalChars = 200, coreChars = 60),
        )
        val picked = book.select("").map { it.id }
        assertEquals("应当只留优先级高的那个", listOf("high"), picked)
    }

    // ═══════════════════════════════════════════════════════════
    //  提示词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有条目时不写任何东西`() {
        assertEquals("", WorldBook(emptyList()).promptSection("钥匙"))
    }

    @Test
    fun `有条目时写出标题与内容`() {
        val book = WorldBook(listOf(setting("a", listOf("钥匙"), content = "钥匙能开地下室")))
        val s = book.promptSection("钥匙在哪")
        assertTrue("应当有标题：$s", s.contains("世界的设定"))
        assertTrue("应当有内容：$s", s.contains("地下室"))
        assertTrue("应当提醒不要复述：$s", s.contains("不要主动复述"))
    }

    @Test
    fun `提示词里不该出现条目 id 或内部字段`() {
        // 那是给程序看的，不是给模型看的
        val book = WorldBook(listOf(setting("internal-id-123", listOf("钥匙"), content = "钥匙能开地下室")))
        val s = book.promptSection("钥匙在哪")
        assertFalse("不该把 id 写进提示词：$s", s.contains("internal-id-123"))
        assertFalse("不该把优先级写进提示词：$s", s.contains("priority"))
    }

    // ═══════════════════════════════════════════════════════════
    //  配置错误要早失败
    // ═══════════════════════════════════════════════════════════

    @Test(expected = IllegalArgumentException::class)
    fun `没有触发词又不是常驻的条目会被拒绝`() {
        // ⚠️ 这是最容易犯的配置错误：写了条目但永远选不中
        WorldEntry(id = "x", title = "孤儿", keys = emptyList(), content = "没人会看到我")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空正文会被拒绝`() {
        WorldEntry(id = "x", title = "空", keys = listOf("钥匙"), content = "   ")
    }

    @Test
    fun `删除条目`() {
        val book = WorldBook(listOf(setting("a", listOf("钥匙")))).without("a")
        assertNull(book.all().firstOrNull { it.id == "a" })
    }
}
