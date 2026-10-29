package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Humanizer] 的离线钉子。
 *
 * ⚠️ 这些测试**不能证明"角色真的不像 AI 了"** —— 那是真机上的观感。
 *    它们能钉住的只有两件事：
 *      · 判据会不会把正常人话误判成 AI 味（误判会让提示词塞满禁令，
 *        而长禁令本身就会让模型变僵 —— 那是另一种 AI 味）；
 *      · 该出现的规则有没有出现。
 */
class HumanizerTest {

    private val stage = RelationshipStage.STRANGER

    @Test
    fun `正常的一两句微信不会被判成 AI 味`() {
        val replies = listOf("嗯。", "你怎么这么晚还不睡", "行吧，那我也去躺一会儿。")
        val f = Humanizer.diagnose(replies, RelationshipStage.CLOSE, 5)
        assertTrue("别把正常人话判成 AI 味：$f", f.isEmpty())
    }

    @Test
    fun `两轮以上分点会被诊断出来`() {
        val replies = listOf(
            "**第一点**：要注意休息\n**第二点**：多喝水",
            "1. 早点睡\n2. 别熬夜",
        )
        val f = Humanizer.diagnose(replies, RelationshipStage.CLOSE, 5)
        assertTrue(f.any { it.code == Humanizer.Code.STRUCTURED })
    }

    @Test
    fun `只分点一次不算问题`() {
        // ★ 偶尔一次分条说清一件事，是正常人也会做的。
        //   判太严会让提示词里天天挂着"不要分点"，反而僵硬。
        val replies = listOf("1. 先这样\n2. 再那样", "嗯嗯知道了")
        val f = Humanizer.diagnose(replies, RelationshipStage.CLOSE, 5)
        assertFalse(f.any { it.code == Humanizer.Code.STRUCTURED })
    }

    @Test
    fun `客服尾巴会被抓出来`() {
        val f = Humanizer.diagnose(
            listOf("好的，已经帮你记下了。还有什么需要吗？"),
            RelationshipStage.CLOSE,
            5,
        )
        assertTrue(f.any { it.code == Humanizer.Code.SERVICE_TAIL })

        val section = Humanizer.promptSection(f, RelationshipStage.CLOSE, PersonaVerbosity.NORMAL)
        assertTrue("要明确禁止客服尾巴：$section", section.contains("说完就停"))
    }

    @Test
    fun `同一句口头禅反复出现会被抓出来`() {
        val replies = listOf("说实话，我不太想去", "说实话，这有点麻烦", "说实话吧，算了")
        val f = Humanizer.diagnose(replies, RelationshipStage.CLOSE, 5)
        assertTrue(f.any { it.code == Humanizer.Code.CATCHPHRASE_LOOP })
    }

    @Test
    fun `长度几乎一样会被抓出来`() {
        // 四条都是 10 个字左右 —— 真人的长短由情绪决定，不会这么均匀
        val replies = listOf(
            "好的我知道了呀",
            "这事我不太清楚",
            "那就先这样办吧",
            "回头再说也不迟",
        )
        val f = Humanizer.diagnose(replies, RelationshipStage.CLOSE, 5)
        assertTrue("长度太均匀本身是模板痕迹：$f", f.any { it.code == Humanizer.Code.FLAT_LENGTH })
    }

    @Test
    fun `长短不一的回复不算问题`() {
        val replies = listOf("嗯。", "我今天真的累坏了，从早上八点一直忙到现在，中间连口水都没顾上喝", "睡吧")
        val f = Humanizer.diagnose(replies, RelationshipStage.CLOSE, 5)
        assertFalse(f.any { it.code == Humanizer.Code.FLAT_LENGTH })
    }

    @Test
    fun `关系长期停在初识会被诊断出来`() {
        val f = Humanizer.diagnose(
            listOf("你好。"),
            RelationshipStage.STRANGER,
            turnsSinceStageChange = 80,
        )
        assertTrue(f.any { it.code == Humanizer.Code.STUCK_RELATION })
    }

    @Test
    fun `刚认识的几轮不会被判关系停滞`() {
        val f = Humanizer.diagnose(
            listOf("你好。"),
            RelationshipStage.STRANGER,
            turnsSinceStageChange = 3,
        )
        assertFalse(f.any { it.code == Humanizer.Code.STUCK_RELATION })
    }

    @Test
    fun `没有问题时提示词段落是空的`() {
        // ★ 关键：不能无条件往提示词里塞规则 —— 长禁令清单本身让模型变僵。
        val section = Humanizer.promptSection(
            findings = emptyList(),
            stage = RelationshipStage.CLOSE,
            verbosity = PersonaVerbosity.NORMAL,
        )
        assertEquals("", section)
    }

    @Test
    fun `有问题时只写那几条，不写整张清单`() {
        val section = Humanizer.promptSection(
            findings = listOf(Humanizer.Finding(Humanizer.Code.SERVICE_TAIL, "x")),
            stage = RelationshipStage.CLOSE,
            verbosity = PersonaVerbosity.NORMAL,
        )
        assertTrue(section.contains("说完就停"))
        assertFalse("没诊断出的问题不该出现在提示词里", section.contains("不要分点"))
        assertFalse(section.contains("旁白"))
    }

    @Test
    fun `重复的同类结论只写一次规则`() {
        val section = Humanizer.promptSection(
            findings = listOf(
                Humanizer.Finding(Humanizer.Code.SERVICE_TAIL, "a"),
                Humanizer.Finding(Humanizer.Code.SERVICE_TAIL, "b"),
            ),
            stage = RelationshipStage.CLOSE,
            verbosity = PersonaVerbosity.NORMAL,
        )
        assertEquals(1, Regex("说完就停").findAll(section).count())
    }

    @Test
    fun `自述旁白会被抓出来`() {
        val f = Humanizer.diagnose(
            listOf("我这么说是想让你别太勉强。"),
            RelationshipStage.CLOSE,
            5,
        )
        assertTrue(f.any { it.code == Humanizer.Code.SELF_NARRATION })
    }

    @Test
    fun `分点判据认加粗和数字列表`() {
        assertTrue(Humanizer.looksStructured("**结论**：早点睡"))
        assertTrue(Humanizer.looksStructured("1. 第一件事"))
        assertFalse(Humanizer.looksStructured("那就早点睡吧"))
    }
}
