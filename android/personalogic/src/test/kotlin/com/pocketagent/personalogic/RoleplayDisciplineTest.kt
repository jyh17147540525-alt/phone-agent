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
    //  ★★ 这些句子**一个字都不许出现在提示词里**
    // ═══════════════════════════════════════════════════════════
    //
    // ⚠️ 2026-11-04 改动：原来是断言 contains（"必须把要禁的句子写出来"）。
    //    那个写法是错的，而且错得很隐蔽 —— 它把"禁令"当成"提示词内容"钉住了。
    //
    //    证据：
    //    · arXiv:2601.21433 —— 1–4B 模型在否定框定下把被禁动作的支持率
    //      从 24% 抬到最高 100%（摆幅 76pp）；跨模型一致率 73%→59%。
    //    · prompt-architects（引 Anthropic 原文）—— 禁令失效主因是欠指定；
    //      治法：精确命名 + 说明什么来占位，然后**删掉禁令**。
    //    · few-shot 另有叠加效应：混在范例里的反面样本也是范例。
    //
    // ⇒ 现在钉的是反面：**这些原句不许再进提示词**。
    //   检测能力不受影响 —— [RoleplayDiscipline.inspect] 仍逐条命中。

    private val nonPromptSentences = listOf(
        "我不端着", "我这人就是嘴硬", "按我的性格",
        "记账上了", "记你一笔", "信誉分", "额度用完了",
        "我是 AI", "作为角色", "按设定我应该", "符合人设",
        "心里有点失落",
    )

    @Test
    fun `被禁的原句不进提示词`() {
        for (line in nonPromptSentences) {
            assertFalse(
                "提示词里不许出现被禁的原句：「$line」—— 否定框定会把它读成肯定",
                section.contains(line),
            )
        }
    }

    @Test
    fun `但检测器仍然认得出这些句子`() {
        // 提示词里不写 ≠ 不检查。离线体检照旧逐条命中。
        assertEquals(listOf("我不端着"), RoleplayDiscipline.inspect("我不端着，就是随便坐坐"))
        assertEquals(listOf("记账"), RoleplayDiscipline.inspect("行，记账上了"))
        assertTrue(RoleplayDiscipline.inspect("作为角色，我应该……").isNotEmpty())
    }

    @Test
    fun `第四条情绪总结目前没有检测器`() {
        // ⚠️ 已知缺口（2026-11-04 审计发现）：四条纪律里前三条都有
        //    BANNED_IN_SPEECH / SELF_NARRATION_PATTERNS 兜底，
        //    ④「别替自己的情绪下结论」只有提示词，没有任何离线检测。
        //    钉住现状，免得以后误以为它是被检的。
        assertTrue(
            "如果哪天给 ④ 加了检测器，这条测试就该改成非空",
            RoleplayDiscipline.inspect("我心里有点失落，但我不想表现出来").isEmpty(),
        )
    }

    @Test
    fun `四类毛病都用形状描述`() {
        // 不引用原句也得说清"禁的是哪一种写法"
        assertTrue("① 自述脾气", section.contains("交代自己的脾气"))
        assertTrue("② 后台名字", section.contains("后台那套名字"))
        assertTrue("③ 解释自己", section.contains("不向对方解释自己是什么"))
        assertTrue("④ 情绪总结", section.contains("不替自己的情绪下结论"))
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
    fun `惊疑组合不该被折叠`() {
        // ⚠️ 2026-11-03 真机 bug：`！？` 被当成重复标点吃掉问号
        assertEquals("嗯！？你说什么", OutputSanitizer.clean("嗯！？你说什么"))
    }

    @Test
    fun `省略号不该被折叠`() {
        assertEquals("我……不知道", OutputSanitizer.clean("我……不知道"))
    }

    @Test
    fun `同一种标点重复才折叠`() {
        assertEquals("好的。", OutputSanitizer.clean("好的。。。"))
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
 * **示例对话** —— 比禁令管用，但**结构不能重复**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-03：用户抓到的第二个 AI 味
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户："你没有发现第三声白送成了新的 ai 味了吗"
 *
 * ⚠️ 他说得对，而且根源在我写的示例上。第一版四条示例是：
 * ```
 *   「累就躺下。……别躺我腿上。」（还是把腿挪开了点）
 *   「谁脸红了。灯的问题。」
 *   「……大概吧。」（说完自己愣了一下）
 *   「随你。」（十分钟后把热水放在他手边）
 * ```
 * **四条全是同一个公式**：嘴硬 + 小动作暴露真心。
 *
 * ⇒ 模型学会了这个公式，于是每句都在套：
 *    「叫两声就够了，第三声是白送的」
 *    铺垫 → 转折 → 抖个机灵。**这本身就是模板。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 病根：few-shot 的**结构同一性**
 * ═══════════════════════════════════════════════════════════════
 *
 * 示例给的从来不只是"语气"，还有**节奏与结构**。
 * 四条同构的例子，等于教模型"每句话都要有反转"。
 *
 * ⇒ 现在的示例刻意参差：有长有短，有一条只有「哦。」，
 *    有一条干脆在干活没抬头。**并显式把那个套路列为反面。**
 */
class VoiceSamplesTest {

    private val s = VoiceSamples.promptSection("主人")

    @Test
    fun `有正面的台词样例`() {
        assertTrue("必须给真正的台词", s.contains("他说："))
        assertTrue("必须给出她的回应", s.contains("她："))
    }

    @Test
    fun `示例必须长短不齐`() {
        // ⚠️ 这是本组测试的**核心**
        // 四条同构的示例等于教模型"每句都要有反转"
        val replies = Regex("她：「([^」]*)」").findAll(s).map { it.groupValues[1] }.toList()
        assertTrue("示例至少 4 条：${replies.size}", replies.size >= 4)
        val lens = replies.map { it.length }
        assertTrue("最长的一句要明显长于最短的一句（否则结构太齐）：$lens",
            lens.max() >= lens.min() + 4)
        assertTrue("必须有一条极短（像「哦。」这种）：$lens", lens.any { it <= 6 })
    }

    @Test
    fun `必须有一条不接话的`() {
        // 真人不总是回应。她也可能在干活。
        assertTrue("要有一条她在做别的事", s.contains("头都没抬") || s.contains("正在擦桌子"))
    }

    @Test
    fun `把嘴硬加小动作那个套路列为反面`() {
        // ⚠️ 用户报的具体问题
        assertTrue("必须点明这个套路", s.contains("表面拒绝"))
        assertTrue("必须说明用多了是模板", s.contains("模板"))
    }

    @Test
    fun `写坏的样子只描述形状不引用原句`() {
        // ⚠️ 2026-11-04：原来这里是 `has 反面样例` —— 要求把四句 AI 腔原样列出来。
        //    few-shot 里的反面样本同时也是样本；否定框定还会把它读成肯定
        //    （证据见 RoleplayDiscipline 类注释）。现在只查形状。
        assertTrue("要点明「最容易写坏」这件事", s.contains("最容易写坏"))
        assertTrue("① 自述脾气", s.contains("交代自己的脾气"))
        assertTrue("② 后台名字", s.contains("后台的名字"))
        assertTrue("③ 情绪结论", s.contains("给自己的情绪下结论"))
        assertTrue("④ 身份声明", s.contains("拿身份当理由"))
        for (banned in listOf("我不端着", "记账上了", "心里有点失落", "作为 的女仆")) {
            assertFalse("反面样本里不许引用原句：「$banned」", s.contains(banned))
        }
    }

    @Test
    fun `禁止每句金句`() {
        // 真人大部分话是废话
        assertTrue(s.contains("别每句都有金句"))
        assertTrue("允许她只说废话", s.contains("废话"))
    }

    @Test
    fun `禁止每句带动作`() {
        assertTrue(s.contains("别每句都带动作"))
        assertTrue("括号动作不是标配", s.contains("不是标配"))
    }

    @Test
    fun `禁止替对方说话`() {
        assertTrue(s.contains("别替对方说话"))
    }

    @Test
    fun `带上称呼`() {
        assertTrue(VoiceSamples.promptSection("鱼片").contains("鱼片"))
    }

    @Test
    fun `没有称呼时不出现空位`() {
        assertFalse(VoiceSamples.promptSection("").contains("作为 的女仆"))
    }
}