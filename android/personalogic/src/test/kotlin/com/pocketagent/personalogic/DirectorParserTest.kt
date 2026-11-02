package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **场记的提示词与解析**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"模型返回了但没人发现它没生效"
 * ═══════════════════════════════════════════════════════════════
 *
 * 后台任务最怕的失败形态：模型返回了一个**没见过的枚举值**，
 * 解析器丢弃，于是那一类事件**永远不触发** —— 而没有任何报错。
 *
 * ⇒ 提示词必须把枚举写全，解析器必须**计数丢弃**，
 *   而这两点都在这里钉住。
 */
class DirectorParserTest {

    private val json = Json

    private object Json {
        val valid = """
            {
              "events": [{"kind": "USER_UNWELL", "reason": "用户说他发烧了"}],
              "smallMoments": ["窗外在下雨"],
              "proposedFacts": [
                {"id": "cat-name", "title": "猫的名字",
                 "keys": ["猫", "咪咪"], "content": "她养了一只叫咪咪的猫"}
              ]
            }
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 提示词：枚举必须写全
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `提示词写全了每一个事件类型`() {
        // ⚠️ 漏一个 ⇒ 那一类事件永远不触发，且无人知道
        val p = DirectorPrompt.build(
            recentTurns = listOf("你好" to "嗯"),
            armor = "",
        )
        for (k in DirectorEventKind.entries) {
            assertTrue("提示词漏了 ${k.name}", p.contains(k.name))
        }
    }

    @Test
    fun `提示词要求 reason 是具体的事`() {
        val p = DirectorPrompt.build(listOf("a" to "b"), "")
        assertTrue("应当提醒不要写系统语言", p.contains("具体的事"))
        assertTrue("应当给出例子", p.contains("发烧"))
    }

    @Test
    fun `提示词要求 keys 非空`() {
        val p = DirectorPrompt.build(listOf("a" to "b"), "")
        assertTrue("没有触发词的事实检索不到", p.contains("不能为空"))
    }

    @Test
    fun `提示词会带上已有的 id 避免重复`() {
        val p = DirectorPrompt.build(
            recentTurns = listOf("a" to "b"),
            armor = "",
            knownFactIds = setOf("cat-name", "old-house"),
        )
        assertTrue("应当列出已有 id", p.contains("cat-name"))
        assertTrue(p.contains("old-house"))
    }

    @Test
    fun `破甲前缀会拼在最前面`() {
        val p = DirectorPrompt.build(listOf("a" to "b"), armor = "## 你的身份\n你是文本分析器。")
        assertTrue("破甲应当在最前", p.startsWith("## 你的身份"))
    }

    @Test
    fun `没有对话时明确让它输出空`() {
        val p = DirectorPrompt.build(emptyList(), "")
        assertTrue("应当明确说输出空结果", p.contains("输出空结果"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 解析：容错但不猜
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常 JSON 能解析`() {
        val r = DirectorParser.parse(json.valid)
        assertTrue(r != null)
        assertEquals(1, r!!.output.events.size)
        assertEquals(DirectorEventKind.USER_UNWELL, r.output.events.first().kind)
        assertEquals("用户说他发烧了", r.output.events.first().reason)
        assertEquals(1, r.output.smallMoments.size)
        assertEquals(1, r.output.proposedFacts.size)
        assertEquals(listOf("猫", "咪咪"), r.output.proposedFacts.first().keys)
        assertEquals(0, r.dropped)
    }

    @Test
    fun `带代码围栏也能解析`() {
        // ⚠️ 模型很爱加围栏，而加了就解析失败
        val fenced = "```json\n" + json.valid + "\n```"
        val r = DirectorParser.parse(fenced)
        assertTrue("剥掉围栏后应当能解析", r != null)
        assertEquals(1, r!!.output.events.size)
    }

    @Test
    fun `不认识的枚举会被丢弃并计数`() {
        // ══════════════════════════════════════════════════════
        //  ★ 核心一条：不猜
        // ══════════════════════════════════════════════════════
        // 硬塞进最接近的一类，会让"冷漠"和"生气"混成一体，
        // 而那种错**无法察觉**。
        val raw = """
            {"events": [{"kind": "SOMETHING_NEW", "reason": "x"}]}
        """.trimIndent()
        val r = DirectorParser.parse(raw)!!
        assertTrue("不认识的类型不该被保留", r.output.events.isEmpty())
        assertEquals("但要计数，否则没人知道模型在乱说", 1, r.dropped)
    }

    @Test
    fun `缺由头的事件会被丢弃`() {
        val raw = """
            {"events": [{"kind": "USER_UNWELL"}]}
        """.trimIndent()
        val r = DirectorParser.parse(raw)!!
        assertTrue(r.output.events.isEmpty())
        assertEquals(1, r.dropped)
    }

    @Test
    fun `没有触发词的事实会被丢弃`() {
        val raw = """
            {"proposedFacts": [{"id": "x", "title": "t", "keys": [], "content": "c"}]}
        """.trimIndent()
        val r = DirectorParser.parse(raw)!!
        assertTrue("没有触发词永远检索不到", r.output.proposedFacts.isEmpty())
        assertEquals(1, r.dropped)
    }

    @Test
    fun `整段不是 JSON 时返回 null`() {
        // ⚠️ 返回 null 表示"这次算了，静默跳过" —— 不打扰用户
        assertNull(DirectorParser.parse("模型今天不想干活"))
        assertNull(DirectorParser.parse(""))
        assertNull(DirectorParser.parse("   "))
    }

    @Test
    fun `空对象是合法的 表示什么都没发生`() {
        // 大多数轮次就该是这样
        val r = DirectorParser.parse("{}")
        assertTrue(r != null)
        assertTrue(r!!.output.isEmpty)
        assertEquals(0, r.dropped)
    }

    @Test
    fun `多给字段不会炸`() {
        val raw = """
            {"events": [], "unknown": 42, "nested": {"a": 1}}
        """.trimIndent()
        val r = DirectorParser.parse(raw)
        assertTrue(r != null)
        assertTrue(r!!.output.isEmpty)
    }

    @Test
    fun `大小写不一致的枚举会被丢弃 而不是猜`() {
        // 拼接式的规范化（忽略大小写）看似友好，但会让"user_unwell"
        // 与"USER_UNWELL"变成不同来源的同一个值 —— 那是在猜。
        val raw = """
            {"events": [{"kind": "user_unwell", "reason": "x"}]}
        """.trimIndent()
        val r = DirectorParser.parse(raw)!!
        assertTrue("大小写不同不该匹配", r.output.events.isEmpty())
    }

    @Test
    fun `同一条结果解析两次得到相同内容`() {
        val a = DirectorParser.parse(json.valid)!!
        val b = DirectorParser.parse(json.valid)!!
        assertEquals(a.output, b.output)
    }
}
