package com.pocketagent.voicelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `方括号情绪标签不会被念出来`() {
        val out = SpeechText.toSpeech("你来了。[轻笑]我等你好久。")
        assertFalse(out.contains("轻笑"))
        assertTrue(out.contains("你来了"))
    }

    @Test
    fun `星号动作整段删掉_而不是只剩动作文字`() {
        val out = SpeechText.toSpeech("我*轻轻点头*，然后笑了。")
        assertFalse(out.contains("轻轻点头"))
        assertTrue(out.contains("然后笑了"))
    }

    @Test
    fun `短圆括号动作删掉_长括号里的正话保留`() {
        val out = SpeechText.toSpeech("（凑近看）这句话我是认真说的，不是随口应付你的那种场面话。")
        assertFalse(out.contains("凑近看"))
        assertTrue(out.contains("认真说"))
    }

    @Test
    fun `表情符号与列表记号不进台词`() {
        val out = SpeechText.toSpeech("今天做了这些：\n- 修了语音\n- 写了测试 😊")
        assertFalse("表情没删：$out", out.contains("😊"))
        assertFalse("列表记号没删：$out", out.contains("- "))
        assertTrue("正文被删多了：$out", out.contains("修了语音"))
    }

    @Test
    fun `换行会变成句子停顿_而不是连成一口气`() {
        val out = SpeechText.toSpeech("第一句\n第二句")
        assertTrue(out, out.contains("第一句。第二句"))
    }

    @Test
    fun `标点两侧的空格会被清掉`() {
        val out = SpeechText.toSpeech("你好 ， 世界 。")
        assertEquals("你好，世界。", out)
    }

    @Test
    fun `代码块不进台词`() {
        val out = SpeechText.toSpeech("看这里```val x = 1```就这样")
        assertFalse(out.contains("val x"))
        assertTrue(out.contains("代码略"))
    }

    @Test
    fun `超长会截断到上限`() {
        val out = SpeechText.toSpeech("啊".repeat(50), maxChars = 20)
        assertEquals(20, out.length)
    }
}
