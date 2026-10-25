package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义角色的测试。
 *
 * ★ 重点不在"能存下来"，而在**"填了会发生什么"** ——
 *   自定义角色最容易的失败是用户填了一堆、结果没有任何效果。
 */
class CustomPersonaTest {

    private fun persona(
        name: String = "老王",
        identity: String = "",
        catchphrases: List<String> = emptyList(),
        relationTo: String = "",
        addressUser: String = "",
    ) = CustomPersona(
        id = "p1",
        name = name,
        identity = identity,
        catchphrases = catchphrases,
        relationTo = relationTo,
        addressUser = addressUser,
    )

    @Test
    fun `必须有名字`() {
        try {
            CustomPersona(id = "p", name = "")
            throw AssertionError("空名字应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("错误信息要说明为什么：${e.message}", "名字" in e.message!!)
        }
    }

    @Test
    fun `没填的字段不该出现在提示词里`() {
        // ═══════════════════════════════════════════════════════
        //  ★ 这是本文件最重要的一条
        // ═══════════════════════════════════════════════════════
        //
        // 如果无条件写"你的口癖是：「」"，模型会以为那是一个真的空口癖，
        // 然后**自己编一个来填坑**。编出来的口癖对用户来说完全是陌生人。
        val p = persona(name = "老王", identity = "", catchphrases = emptyList())
        val text = p.describeForPrompt()

        assertTrue("应当有名字", "老王" in text)
        assertFalse("没填口癖就不该提口癖：$text", "口癖" in text)
        assertFalse("没填关系就不该提关系：$text", "关系" in text)
        assertFalse("不该出现空的引号对：$text", "「」" in text)
    }

    @Test
    fun `填了的字段要真的进提示词`() {
        val p = persona(
            name = "老王",
            identity = "一位退休的中学语文老师",
            catchphrases = listOf("这事儿啊", "慢慢来"),
            relationTo = "我的大学室友",
            addressUser = "小陈",
        )
        val text = p.describeForPrompt()

        assertTrue("身份要进去", "退休的中学语文老师" in text)
        assertTrue("口癖要进去", "这事儿啊" in text)
        assertTrue("关系要进去", "大学室友" in text)
        assertTrue("称呼要进去", "小陈" in text)
    }

    @Test
    fun `口癖数量有上限`() {
        // ⚠️ 太多会挤占上下文，且模型会混着用 —— 十个口癖同时出现不像人。
        val tooMany = (1..20).map { "口癖$it" }
        try {
            CustomPersona(id = "p", name = "x", catchphrases = tooMany)
            throw AssertionError("超量口癖应当被拒绝")
        } catch (e: IllegalArgumentException) {
            // 别断言具体措辞 —— 文案会改，这条该继续绿。要断言的是**它说清了数字与理由**。
            val msg = e.message!!
            assertTrue("要说清具体条数：$msg", "8" in msg)
            assertTrue("要说清为什么：$msg", "上下文" in msg || "混" in msg)
        }
    }

    @Test
    fun `名字过长被拒绝`() {
        try {
            CustomPersona(id = "p", name = "名".repeat(CustomPersona.MAX_NAME + 1))
            throw AssertionError("超长名字应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("要说清上限：${e.message}", "上限" in e.message!!)
        }
    }

    @Test
    fun `不指定性别是合法状态`() {
        // ⚠️ 很多角色（"我的老同学"）不需要性别，
        //    强行二选一会逼用户做一个他没想过的决定。
        val p = persona()
        assertEquals(PersonaGender.UNSPECIFIED, p.gender)
    }

    @Test
    fun `转成内置模板时保留关键字段`() {
        val p = persona(
            name = "老王",
            identity = "退休教师",
            catchphrases = listOf("这事儿啊"),
            addressUser = "小陈",
        )
        val preset = p.toPreset()

        assertEquals("老王", preset.displayName)
        assertEquals("小陈", preset.addressStyle)
        assertEquals("这事儿啊", preset.catchphrase)
        assertEquals("p1", preset.id)
    }
}

/**
 * 完整性提示的测试。
 */
class PersonaCompletenessTest {

    @Test
    fun `空白角色会提示补最有价值的项`() {
        val hints = PersonaCompleteness.hints(
            CustomPersona(id = "p", name = "老王"),
        )

        assertFalse("空角色应当有提示", hints.isEmpty())
        // ★ "这是一个什么人"信息量最大，必须排第一
        assertTrue(
            "最有价值的提示应当排第一，实际第一条是：${hints.first().field}",
            "什么人" in hints.first().field,
        )
    }

    @Test
    fun `填完后不再提示`() {
        val complete = CustomPersona(
            id = "p",
            name = "老王",
            identity = "一位退休的中学语文老师，脾气慢",
            catchphrases = listOf("慢慢来"),
            relationTo = "我的大学室友",
            addressUser = "小陈",
        )
        assertTrue(
            "已经完整了还在提示：${PersonaCompleteness.hints(complete)}",
            PersonaCompleteness.isComplete(complete),
        )
    }

    @Test
    fun `每条提示都要说清为什么`() {
        // ⚠️ 只写"请填写身份"没有用 —— 用户不知道填了能得到什么。
        for (h in PersonaCompleteness.hints(CustomPersona(id = "p", name = "x"))) {
            assertTrue("提示「${h.field}」缺少原因说明", h.why.length >= 10)
        }
    }
}
