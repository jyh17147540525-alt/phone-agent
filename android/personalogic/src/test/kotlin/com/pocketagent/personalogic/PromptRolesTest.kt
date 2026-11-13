package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 人称裁决的测试。
 *
 * ★ 这一段治的是用户报的那个病（m03749）：
 *   「ai 以第三人称的形式去角色扮演…有时候会认为，是用户在扮演设定提示词里的那个角色」。
 *   所以断言不测"文案好不好"，只测**四件事有没有被说出口**：
 *     ① 说话的是你，用「我」指自己；
 *     ② 对面那个人演的是他自己；
 *     ③ 设定里那些「她」指的就是你；
 *     ④ 设定是别人写给你的。
 *
 * ★ 还测一件事：这几段**自己不能是脏的**。它们是提示词里最靠前的文字，
 *   要是自己带列表符或口号，后面全跟着学（同 [PromptVoice] 的理由）。
 */
class PromptRolesTest {

    @Test
    fun `人造的那个词是代理人`() {
        assertEquals("代理人", PromptRoles.AGENT_TERM)
    }

    @Test
    fun `人称裁决说的是第一人称`() {
        val s = PromptRoles.speakerSection()
        assertTrue("必须给「我」这个字：$s", "用「我」指自己" in s)
        assertTrue("必须说清是谁在演谁：$s", "由你来演" in s)
    }

    @Test
    fun `人称裁决把对面那个人摘出去`() {
        val s = PromptRoles.speakerSection()
        assertTrue("必须说清对面演的是谁：$s", "对面那个人演的是他自己" in s)
        assertFalse("不能把用户也写成被演的角色：$s", "你演的是他" in s)
    }

    @Test
    fun `提示词里的她一次性重解释`() {
        val s = PromptRoles.speakerSection()
        assertTrue("设定里的「她」指谁必须交代：$s", "设定里写到「她」的地方" in s)
    }

    @Test
    fun `说清设定是谁写的`() {
        val s = PromptRoles.speakerSection()
        assertTrue("写设定的是用户，被写的是角色：$s", "写它的是对面那个人" in s)
    }

    @Test
    fun `名字是可选的`() {
        // 空名字时硬写「名字是「」」会让模型以为有个空名字要去编一个填上。
        // （这不是假想：CustomPersonaTest 里同一条教训已经踩过。）
        val without = PromptRoles.speakerSection()
        val with = PromptRoles.speakerSection("小满")
        assertFalse("没有名字就不该提名字：$without", "小满" in without)
        assertTrue("给了名字就要认下来：$with", "设定里写到「小满」的地方" in with)
        assertEquals("带名字只多一句正事（外加一个空行）：$with", without.lines().size + 2, with.lines().size)
    }

    @Test
    fun `被问到身份时答代理人`() {
        val s = PromptRoles.speakerSection("小满")
        assertTrue(
            "必须给一个正面答案，否则模型会自己编一个（多半是「AI 助手」）：$s",
            "就说你是他的${PromptRoles.AGENT_TERM}" in s,
        )
        assertFalse("不能反过来把用户说成代理人：$s", "他是你的代理人" in s)
    }

    @Test
    fun `自定义角色的设定开头交代主次`() {
        val h = PromptRoles.settingHeader()
        assertTrue("写它的是他，被写的是你：$h", "写它的是他，被写的是你" in h)
        assertTrue("先给位置再给名字：$h", PromptRoles.AGENT_TERM in h)
    }

    @Test
    fun `设定开头没有元话语`() {
        // 「下面这些…」会被 PromptVoice 记成元话语（METATALK），
        // 而 settingHeader 是提示词的第一句 —— 它自己带口头禅，后面全学。
        val report = PromptVoice.audit(PromptRoles.settingHeader())
        assertTrue("settingHeader 不该有会被照抄的形状：${report.breakdown()}", report.isClean)
    }

    @Test
    fun `人称裁决自己也是干净的`() {
        val report = PromptVoice.audit(PromptRoles.speakerSection("小满"))
        assertEquals("不该有会教坏格式的记号：${report.breakdown()}", 0, report.loud)
        assertEquals("不该有会传染的腔调：${report.breakdown()}", 0, report.tone)
    }

    @Test
    fun `自定义角色的第三人称身份被重解释`() {
        val p = CustomPersona(
            id = "p1",
            name = "小满",
            identity = "她是一个安静的女生",
        )
        val text = p.describeForPrompt()

        assertTrue("要交代这是别人在讲你：$text", "别人是这么讲你的" in text)
        assertTrue("用户的字一个不改：$text", "她是一个安静的女生" in text)
        assertTrue("要说明这里面的「她」指谁：$text", "说的都是你" in text)
    }
}
