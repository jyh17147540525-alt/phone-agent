package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **对话模式**（角色扮演 / 工作）的契约测试。
 *
 * ⚠️ 这里钉的不是"界面上有几个按钮"，是**三件会静默坏掉的事**：
 *
 *  1. `of()` 的兜底方向 —— 要是兜底成 `WORK`，一次存档损坏就让所有
 *     老用户"她怎么不演了"，而且没有任何地方会报错。
 *  2. 工作模式**只做减法**产品意义上的那一半：它还带着语言锁与称呼。
 *     这条要是坏了，工作模式会退化成"没有约束的助手"，
 *     用户可以接受它办事，但不能接受它忽然说英文。
 *  3. 语言锁是**复用**而不是手抄。手抄之后两份会漂移，
 *     漂移的表现是"闲聊不许夹外文、办事可以夹" —— 而用户抱怨的是夹外文本身。
 */
class ChatModeTest {

    // ── 持久化还原 ────────────────────────────────────────────────

    @Test
    fun `认不出的存档一律回到角色扮演`() {
        // ⚠️ 四种坏输入：没有值、空串、乱码、旧版本留下的别的名字。
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of(null))
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of(""))
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of("工作"))
        assertEquals(ChatMode.ROLEPLAY, ChatMode.of("WORK_MODE_V2"))
    }

    @Test
    fun `写进去什么就读回什么`() {
        for (m in ChatMode.entries) {
            assertEquals("往返丢了：${m.name}", m, ChatMode.of(m.name))
        }
    }

    @Test
    fun `只有 WORK 是工作模式`() {
        assertTrue(ChatMode.WORK.isWork)
        assertFalse(ChatMode.ROLEPLAY.isWork)
    }

    @Test
    fun `两个模式的标签都不为空且互不相同`() {
        val labels = ChatMode.entries.map { it.label }
        val shorts = ChatMode.entries.map { it.shortLabel }
        assertTrue(labels.none { it.isBlank() })
        assertTrue(shorts.none { it.isBlank() })
        assertEquals("label 撞了：$labels", labels.size, labels.distinct().size)
        assertEquals("shortLabel 撞了：$shorts", shorts.size, shorts.distinct().size)
    }

    // ── 工作纪律的内容 ────────────────────────────────────────────

    private val section = WorkDiscipline.promptSection()

    @Test
    fun `工作纪律六条都在`() {
        // ⚠️ 断言的是**标题原文**。改文案可以，但要一起改这里 ——
        //    这条测试的用途是"别在重构里悄悄删掉一条"。
        // ★ 2026-11-06：标题去掉了 `**` 加粗（提示词自己不能有 AI 味，
        //   见 docs/工程/提示词文风-学术依据-2026-11-06.md），文字未变。
        for (title in listOf(
            "先给答案。",
            "该动手就动手。",
            "可以分行。",
            "不知道就说不知道。",
            "不写动作，不写表情。",
            "不提后台那些东西。",
        )) {
            assertTrue("丢了这一条：$title", section.contains(title))
        }
    }

    @Test
    fun `工作纪律里不得出现被禁句子的原文`() {
        // ★ 依据 `docs/工程/提示词负例改造-依据.md`：
        //   否定框定会被模型读成肯定（arXiv:2601.21433，1–4B 模型摆幅最大），
        //   所以提示词里一律不列"不许说的话"，只描述形状与替代动作。
        //   ❌ 符号是那套写法的指纹 —— 它出现在提示词里就说明有人又抄回来了。
        assertFalse("工作纪律里出现了 ❌ 引用", section.contains("❌"))
        assertFalse("工作纪律里出现了负面框定", section.contains("不要这样写"))
    }

    @Test
    fun `工作模式仍然带语言锁`() {
        // ⚠️ 这是"工作模式不是冷淡客服"的一半：它是同一个人，
        //    所以她的语言约束照样在。
        assertTrue(section.contains("## 语言"))
        assertTrue(section.contains("只说中文"))
    }

    @Test
    fun `语言锁是复用而不是手抄`() {
        // ★ 逐字节比对：工作模式那一节必须是 `languageSection()` 的原文。
        //   要是有人为了"工作模式措辞不一样"手抄一份，这条会红。
        val shared = RoleplayDiscipline.languageSection()
        assertTrue(
            "工作模式里的语言锁与 RoleplayDiscipline.languageSection() 不一致 —— 有人手抄了一份",
            section.contains(shared),
        )
    }

    @Test
    fun `称呼只在给了称呼时出现`() {
        assertFalse("没给称呼却写了「称呼」那一节", section.contains("## 称呼"))
        val withAddr = WorkDiscipline.promptSection(userAddress = "主人")
        assertTrue(withAddr.contains("## 称呼"))
        assertTrue(withAddr.contains("「主人」"))
        // ⚠️ 只能有**一节**：两节称呼会互相矛盾（一节说"该叫的时候叫"，
        //    另一节说"撒娇时用"）。
        assertEquals(1, Regex("## 称呼").findAll(withAddr).count())
    }

    @Test
    fun `不编答案`() {
        // ⚠️ 反向钉子：编一个"听起来对的答案"在工作模式里是最坏的失败 ——
        //    用户会照着做。
        assertTrue(section.contains("查不到就说查不到"))
        assertTrue(section.contains("不编"))
    }
}
