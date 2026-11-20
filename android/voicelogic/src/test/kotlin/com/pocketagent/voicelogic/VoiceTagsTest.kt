package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTagsTest {

    @Test
    fun `从官方文档文本里捞出三种写法的标签`() {
        val raw = "可用标签： [laughs] 、 (sighs) 、 <whispers> ，其余不支持"
        val set = VoiceTags.parse("v", raw)
        assertTrue(set.tags.contains("[laughs]"))
        assertTrue(set.tags.contains("(sighs)"))
        assertTrue(set.tags.contains("<whispers>"))
    }

    @Test
    fun `垃圾文本捞不到东西就返回空_不编造`() {
        assertTrue(VoiceTags.parse("v", "这里没有任何标签，只是说明文字").tags.isEmpty())
    }

    @Test
    fun `空库不拼提示词`() {
        assertEquals("", VoiceTags.promptSection(VoiceTagSet("v", emptyList())))
        assertEquals("", VoiceTags.promptSection(null))
    }

    @Test
    fun `提示词里带标签且限制条数`() {
        val many = (1..20).map { "[t$it]" }
        val section = VoiceTags.promptSection(VoiceTagSet("v", many))
        assertTrue(section.contains("语音标签"))
        assertTrue(section.contains("[t1]"))
        assertFalse(section.contains("[t13]"))
    }

    @Test
    fun `用户看到的正文里标签被切掉_括号动作留着`() {
        val set = VoiceTagSet("v", listOf("[laughs]"))
        val out = VoiceTags.visibleText("[laughs] 你回来了（轻轻笑了一下）", set)
        assertFalse(out.contains("laughs"))
        assertTrue(out.contains("你回来了"))
        assertTrue(out.contains("轻轻笑了一下"))
    }

    @Test
    fun `未知方括号内容不擅自删`() {
        val out = VoiceTags.visibleText("看这里 [重要] 再说一遍", VoiceTagSet("v", listOf("[laughs]")))
        assertTrue(out.contains("[重要]"))
    }

    @Test
    fun `内置兜底里有该厂商的标签`() {
        assertTrue(VoiceTags.builtinFor("breezeblue-cloud-tts").tags.isNotEmpty())
    }
}
