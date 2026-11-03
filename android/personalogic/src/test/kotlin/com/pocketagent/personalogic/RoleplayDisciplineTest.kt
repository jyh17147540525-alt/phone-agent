package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **扮演纪律** —— 她不该念自己的设定。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户报的病：说出"我不端着""记账上了"
 * ═══════════════════════════════════════════════════════════════
 *
 * 这叫**元语言泄漏**：模型把设定文本或系统机制当台词说了。
 *
 * ⚠️ 这个文件钉住的核心不是"禁止"，而是**禁止必须配替代动作** ——
 *   光说"不许出戏"模型会僵住，或者换个说法继续犯。
 */
class RoleplayDisciplineTest {

    private val section = RoleplayDiscipline.promptSection("主人")

    // ═══════════════════════════════════════════════════════════
    //  ★ 禁止项必须写全
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `禁掉念设定`() {
        assertTrue("必须禁「我不端着」这类自述", section.contains("我不端着"))
        assertTrue("必须禁「嘴硬」这类自评", section.contains("我这人就是嘴硬"))
    }

    @Test
    fun `禁掉念机制`() {
        // ⚠️ "记账上了" 是用户亲口报的
        assertTrue("必须禁「记账上了」", section.contains("记账上了"))
        assertTrue("必须禁「信誉分」", section.contains("信誉分"))
    }

    @Test
    fun `禁掉解释自己`() {
        assertTrue(section.contains("我是 AI"))
        assertTrue(section.contains("作为角色"))
        assertTrue(section.contains("符合人设"))
    }

    @Test
    fun `禁掉总结自己的情绪`() {
        assertTrue(section.contains("心里有点失落"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★ 禁止必须配替代动作
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每条禁止都给了替代动作`() {
        // 这是扮演提示词最要紧的一条经验：
        // 光说"不许"模型会僵住，或者换个说法继续犯。
        val alternatives = listOf(
            "就把手里的活干得马虎一点",   // 替代"不端着"
            "嘴上说「随便你」",           // 替代"嘴硬"
            "这事我记着",                 // 替代"记账上了"
            "把脸转开",                   // 替代"心里有点失落"
        )
        for (a in alternatives) {
            assertTrue("缺替代动作：「$a」", section.contains(a))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 语言锁定
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `语言锁死中文`() {
        assertTrue("必须明说不许夹外文", section.contains("不许夹英文单词"))
        assertTrue("必须给替代策略", section.contains("换个说法"))
    }

    // ═══════════════════════════════════════════════════════════
    //  称呼
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `带上称呼但不要求每句都叫`() {
        assertTrue(section.contains("主人"))
        assertTrue("不能让她每句都带称呼", section.contains("不用每句都带"))
    }

    @Test
    fun `没有称呼时不留空段`() {
        val s = RoleplayDiscipline.promptSection("")
        assertFalse(s.contains("## 称呼"))
    }

    // ═══════════════════════════════════════════════════════════
    //  检测器
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `能检出用户报的原句`() {
        assertTrue(
            "「我不端着」必须被检出",
            RoleplayDiscipline.inspect("我不端着，就是随便坐坐").isNotEmpty(),
        )
        assertTrue(
            "「记账上了」必须被检出",
            RoleplayDiscipline.inspect("行，记账上了").isNotEmpty(),
        )
    }

    @Test
    fun `干净的话不会被误判`() {
        val clean = "行行行，听你的。不过话说回来，这鱼真难吃。"
        assertTrue("正常台词不该被拦：${RoleplayDiscipline.inspect(clean)}",
            RoleplayDiscipline.inspect(clean).isEmpty())
    }

    @Test
    fun `空文本不炸`() {
        assertTrue(RoleplayDiscipline.inspect("").isEmpty())
    }
}

/**
 * **输出清洗** —— 把中文里夹的外文剔掉。
 */
class OutputSanitizerTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ 非中文文字系统：直接删
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `西里尔字母被删掉`() {
        assertEquals("今天天气不错。", OutputSanitizer.clean("今天天气привет不错。"))
    }

    @Test
    fun `谚文被删掉`() {
        assertEquals("我们出去走走吧。", OutputSanitizer.clean("我们안녕出去走走吧。"))
    }

    @Test
    fun `假名被删掉`() {
        assertEquals("这个不行。", OutputSanitizer.clean("这个ダメ不行。"))
    }

    @Test
    fun `阿拉伯与泰文被删掉`() {
        assertEquals("好的。", OutputSanitizer.clean("好的مرحباครับ。"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 拉丁词：白名单之外删
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `无关英文词被删掉`() {
        assertEquals("今天我不想说话。", OutputSanitizer.clean("今天interpolate我不想说话。"))
    }

    @Test
    fun `白名单里的词保留`() {
        val s = OutputSanitizer.clean("你家 wifi 又断了？")
        assertTrue("wifi 该留下：$s", s.contains("wifi"))
    }

    @Test
    fun `允许英文时不删`() {
        val s = OutputSanitizer.clean("today is fine", allowLatin = true)
        assertEquals("today is fine", s)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 删完要收拾残局
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删词后中文之间不留空格`() {
        val s = OutputSanitizer.clean("今天 hello 天气")
        assertFalse("不该留双空格：[$s]", s.contains("  "))
        assertFalse("中文之间不该有空格：[$s]", Regex("[\\u4e00-\\u9fff] [\\u4e00-\\u9fff]").containsMatchIn(s))
    }

    @Test
    fun `删词后不留连续标点`() {
        val s = OutputSanitizer.clean("好的，，okWorld。")
        assertFalse("不该有连续逗号：[$s]", s.contains("，，"))
    }

    @Test
    fun `中文场景常见标点正常保留`() {
        val src = "行行行，听你的。不过话说回来——这鱼真难吃，对吧？"
        assertEquals(src, OutputSanitizer.clean(src))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 安全阀
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `整段外文胡话会被判为近乎空`() {
        val raw = "привет мир 안녕하세요 これはテストです مرحبا"
        val cleaned = OutputSanitizer.clean(raw)
        assertTrue(
            "清洗后几乎没内容，应当触发重试而不是返回空：[$cleaned]",
            OutputSanitizer.isNearlyEmpty(cleaned, raw),
        )
    }

    @Test
    fun `正常中文不会被判为空`() {
        val raw = "行吧，那你自己看着办。"
        assertFalse(OutputSanitizer.isNearlyEmpty(OutputSanitizer.clean(raw), raw))
    }

    @Test
    fun `检测器能指出夹了什么`() {
        val bits = OutputSanitizer.foreignBits("今天привет不错，interpolate一下")
        assertTrue("应当指出西里尔", bits.any { it == "п" || it.contains("п") })
        assertTrue("应当指出英文词", bits.any { it.equals("interpolate", true) })
    }

    @Test
    fun `干净文本检测器返回空`() {
        assertTrue(OutputSanitizer.foreignBits("行行行，听你的。").isEmpty())
    }
}

/**
 * **示例对话** —— 比禁令管用。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 依据（SillyTavern 社区反复验证的一条）
 * ═══════════════════════════════════════════════════════════════
 *
 * > The more clinical your character card is with bracketed sections
 * > dryly explaining the character, the more the LLM will just think
 * > that that type of language is acceptable.
 *
 * 反过来说：**给它看台词，它就会写台词；给它看设定文，它就会写设定文。**
 *
 * ⚠️ 用户的人格设定正是临床腔，所以模型开始自述「我不端着」。
 *    光禁不够 —— 得给它最后一屏"人话"。
 */
class VoiceSamplesTest {

    private val s = VoiceSamples.promptSection("主人")

    @Test
    fun `有正面的台词样例`() {
        assertTrue("必须给真正的台词", s.contains("他说："))
        assertTrue("必须给出她的回应", s.contains("她："))
    }

    @Test
    fun `有反面样例做对照`() {
        // ⚠️ 关键是让模型**看到差别**，而不是只被告知"不要那样"
        assertTrue("必须有反面", s.contains("反面"))
        assertTrue("反面里要包含念性格", s.contains("我不端着"))
        assertTrue("反面里要包含念系统", s.contains("记账上了"))
        assertTrue("反面里要包含总结情绪", s.contains("心里有点失落"))
    }

    @Test
    fun `样例体现不确定感`() {
        // ★ 调研里的 imperfect certainty —— 真人不会把话说满
        assertTrue("要有「大概吧」这类不确定", s.contains("大概吧"))
    }

    @Test
    fun `禁止替对方说话`() {
        assertTrue(s.contains("别替对方说话"))
    }

    @Test
    fun `禁止把话说满`() {
        assertTrue(s.contains("别把话说满"))
    }

    @Test
    fun `带上称呼`() {
        val withAddr = VoiceSamples.promptSection("鱼片")
        assertTrue(withAddr.contains("鱼片"))
    }

    @Test
    fun `没有称呼时不出现空称呼`() {
        val noAddr = VoiceSamples.promptSection("")
        assertFalse("不该出现「作为 的女仆」这种空位", noAddr.contains("作为 的女仆"))
    }
}