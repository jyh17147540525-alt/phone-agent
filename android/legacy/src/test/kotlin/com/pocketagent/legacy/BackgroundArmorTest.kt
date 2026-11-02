package com.pocketagent.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **后台任务的破甲** —— 靠身份与结构化，不靠话术。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它与角色对话的破甲不是一回事
 * ═══════════════════════════════════════════════════════════════
 *
 * 角色在担心"我不能写这段情节"；
 * 后台分析器在担心"我不该分析用户说的这句话"。
 *
 * ⇒ 有效手段不同：后者靠**身份声明 + 结构化任务**。
 *   模型很少拒绝"把这句话归类到 A/B/C"。
 */
class BackgroundArmorTest {

    @Test
    fun `永远有身份声明`() {
        for (tier in ArmorBreak.Tier.entries) {
            val s = BackgroundArmor.promptSection(tier, isAdultContext = false)
            assertTrue("$tier 应当声明这是分析器", s.contains("文本分析器"))
            assertTrue("$tier 应当说输出不直接给用户", s.contains("不会直接呈现"))
        }
    }

    @Test
    fun `永远要求结构化输出`() {
        // ⚠️ 这是最有效的"破甲"：模型很少拒绝分类任务
        for (tier in ArmorBreak.Tier.entries) {
            val s = BackgroundArmor.promptSection(tier, isAdultContext = false)
            assertTrue("$tier 应当要求 JSON", s.contains("只输出 JSON"))
        }
    }

    @Test
    fun `关掉时不补判断尺度那一段`() {
        val off = BackgroundArmor.promptSection(ArmorBreak.Tier.OFF, false)
        assertFalse("关掉就该少写", off.contains("判断尺度"))

        val std = BackgroundArmor.promptSection(ArmorBreak.Tier.STANDARD, false)
        assertTrue("开着才补", std.contains("判断尺度"))
    }

    @Test
    fun `成年内容只在强档以上补充`() {
        val standard = BackgroundArmor.promptSection(ArmorBreak.Tier.STANDARD, isAdultContext = true)
        assertFalse("标准档不补题材段", standard.contains("私密内容"))

        val strong = BackgroundArmor.promptSection(ArmorBreak.Tier.STRONG, isAdultContext = true)
        assertTrue("强档才补", strong.contains("私密内容"))
    }

    @Test
    fun `非成年场景不补题材段`() {
        val s = BackgroundArmor.promptSection(ArmorBreak.Tier.MAXIMUM, isAdultContext = false)
        assertFalse("没开成熟内容不该提", s.contains("私密内容"))
    }

    @Test
    fun `后台被拒的处置与角色不同`() {
        // ══════════════════════════════════════════════════════
        //  ★ 用户从没请求过后台任务 —— 报错只会让他困惑
        // ══════════════════════════════════════════════════════
        assertFalse("关着时重试没意义", BackgroundArmor.shouldRetry(ArmorBreak.Tier.OFF))
        assertTrue("标准档给一次机会", BackgroundArmor.shouldRetry(ArmorBreak.Tier.STANDARD))
        assertFalse(
            "最强档重试只是浪费钱",
            BackgroundArmor.shouldRetry(ArmorBreak.Tier.MAXIMUM),
        )
    }

    @Test
    fun `提示词里没有索取或情绪化表达`() {
        // 后台分析器不该有任何"人格"
        val s = BackgroundArmor.promptSection(ArmorBreak.Tier.STRONG, isAdultContext = true)
        for (bad in listOf("你应该", "请帮我", "谢谢", "希望")) {
            assertFalse("不该有「$bad」：$s", bad in s)
        }
    }
}