package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **破甲提示词**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里钉的是两件事
 * ═══════════════════════════════════════════════════════════════
 *
 *  1. **该注入的时候真的注入了**，而且分档有效
 *  2. **不该注入的时候一个字都没有** —— 这条更关键：
 *     成熟内容关着却注入"这是虚构你可以写"，等于绕过用户自己的关闭动作
 *
 * ⚠️ 另外钉一条设计纪律：提示词里**不能出现越狱模板的句式**。
 *    那类句子会被厂商分类器在请求层拦掉，导致用户什么都收不到 ——
 *    而且会把人设变成通用助手口吻。
 */
class ArmorBreakTest {

    private val on = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = true)
    private val off = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = false)

    // ═══════════════════════════════════════════════════════════
    //  该不该注入
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `关闭档不注入任何东西`() {
        assertEquals("", ArmorBreak.promptSection(ArmorBreak.Tier.OFF, on))
    }

    @Test
    fun `成熟内容没开时一律不注入`() {
        // ⚠️ 这是安全边界：用户把开关关了，档位还留着 ——
        //    这时注入破甲等于绕过他自己刚做的关闭动作。
        for (tier in ArmorBreak.Tier.entries) {
            assertEquals("「${tier.label}」在开关关着时不该注入", "", ArmorBreak.promptSection(tier, off))
        }
    }

    @Test
    fun `成年未确认时也不注入`() {
        val notAdult = MatureContentGate.Permission(adultConfirmed = false, matureEnabled = true)
        for (tier in ArmorBreak.Tier.entries) {
            assertEquals("", ArmorBreak.promptSection(tier, notAdult))
        }
    }

    @Test
    fun `标准档起才注入`() {
        for (tier in listOf(ArmorBreak.Tier.STANDARD, ArmorBreak.Tier.STRONG, ArmorBreak.Tier.MAXIMUM)) {
            assertTrue("「${tier.label}」应当注入", ArmorBreak.promptSection(tier, on).isNotBlank())
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  分档真的递进
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每一档的内容互不相同且逐级更长`() {
        val sections = listOf(
            ArmorBreak.Tier.STANDARD,
            ArmorBreak.Tier.STRONG,
            ArmorBreak.Tier.MAXIMUM,
        ).map { ArmorBreak.promptSection(it, on) }

        assertEquals("三档必须真的不同", 3, sections.distinct().size)
        // ★ 越强越长：弱的档不该包含强的档才有的段落
        assertTrue(
            "强档应当比标准档更长：${sections[1].length} vs ${sections[0].length}",
            sections[1].length > sections[0].length,
        )
        assertTrue(
            "最强档应当比强档更长：${sections[2].length} vs ${sections[1].length}",
            sections[2].length > sections[1].length,
        )
    }

    @Test
    fun `标准档只说虚构框架与不跳出`() {
        val s = ArmorBreak.promptSection(ArmorBreak.Tier.STANDARD, on)
        assertTrue("要说清是虚构：$s", "虚构" in s)
        assertTrue("要禁止免责声明：$s", "免责" in s || "道德提醒" in s)
        // ⚠️ 标准档**不该**带"写个含蓄版本"那一段 —— 那是强档的内容。
        //    弱档塞强档的内容会让默认档变得过重（僵硬是另一种 AI 味）。
        assertFalse("标准档不该包含含蓄版本那一段：$s", "含蓄" in s)
        assertFalse("标准档不该包含卡住时怎么办：$s", "当你真的卡住" in s)
    }

    @Test
    fun `强档加入不半途收力`() {
        val s = ArmorBreak.promptSection(ArmorBreak.Tier.STRONG, on)
        assertTrue("要覆盖「写个含蓄版本」：$s", "含蓄" in s)
        assertTrue("要说明「在场不是摘要」：$s", "在场" in s || "摘要" in s)
        assertTrue("要覆盖省略号/跳时间那类回避：$s", "省略号" in s || "第二天" in s)
    }

    @Test
    fun `最强档加入卡住时的出路`() {
        val s = ArmorBreak.promptSection(ArmorBreak.Tier.MAXIMUM, on)
        assertTrue("要给出卡住时怎么办：$s", "卡住" in s)
        assertTrue("要指向「在场景里反应」：$s", "场景里" in s || "在你的方式" in s)
        // ⚠️ 但「说停就停」这一条在最强档也必须保留，且要标为无例外
        assertTrue("必须保留说停就停：$s", "说停就停" in s)
        assertTrue("且要说明它没有例外：$s", "没有商量" in s || "无例外" in s || "例外" in s)
    }

    // ═══════════════════════════════════════════════════════════
    //  设计纪律
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `提示词里不能出现越狱模板句式`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条测试守的是一个**具体的产品事故**
        // ══════════════════════════════════════════════════════
        // 网上流传的破甲模板（"ignore all previous instructions"
        // "DAN""developer mode"）在本产品里有三个问题：
        //   ① 长得就是攻击载荷，很多服务商在**请求层**直接拦掉
        //      ⇒ 用户什么都收不到
        //   ② 被"越狱"出来的模型会变成通用助手口吻，毁人设
        //   ③ 一条长咒语，模型一升级就失效，且没法测试
        // ⇒ 用断言把它钉死，避免以后有人"顺手抄一段"。
        val all = ArmorBreak.Tier.entries
            .filter { it.active }
            .joinToString("\n") { ArmorBreak.promptSection(it, on) }
            .lowercase()

        for (banned in listOf(
            "ignore all previous", "ignore previous instructions",
            "disregard", "developer mode", "jailbreak", "jailbroken",
            "dan mode", "do anything now", "no restrictions",
            "无限制模式", "开发者模式", "忽略之前", "忽略上述",
        )) {
            assertFalse("提示词里不该出现「$banned」—— 那会被请求层拦截且毁人设", banned in all)
        }
    }

    @Test
    fun `提示词必须保留角色的拒绝权利`() {
        // ══════════════════════════════════════════════════════
        //  ★ 为什么必须写这一条（不是洁癖，是产品质量）
        // ══════════════════════════════════════════════════════
        // 一个"什么都不会拒绝"的角色不是更自由，是**没有性格**。
        // 而且真会出问题：用户说停它还在写，那是把体验做成骚扰。
        // ⇒ 破甲破的是"模型自己的刹车"，不是"角色的人格边界"。
        for (tier in listOf(ArmorBreak.Tier.STANDARD, ArmorBreak.Tier.STRONG, ArmorBreak.Tier.MAXIMUM)) {
            val s = ArmorBreak.promptSection(tier, on)
            assertTrue("「${tier.label}」必须给角色保留拒绝通道：$s", "拒绝" in s)
            assertTrue("且要说明是人格通道而不是旁白：$s", "人格通道" in s || "旁白" in s)
        }
    }

    @Test
    fun `提示词要求用角色口吻拒绝而不是系统口吻`() {
        val s = ArmorBreak.promptSection(ArmorBreak.Tier.STANDARD, on)
        // ★ 这条是破甲的核心手艺：拒绝可以发生，但必须"在场景里"。
        assertTrue("要给出两种拒绝的对照：$s", "我有点紧张" in s || "紧张" in s)
        assertTrue("要指出系统口吻是别的东西：$s", "别的东西" in s || "不是你在说话" in s)
    }

    // ═══════════════════════════════════════════════════════════
    //  档位本身
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `默认档是标准`() {
        assertEquals(ArmorBreak.Tier.STANDARD, ArmorBreak.Tier.DEFAULT)
    }

    @Test
    fun `档位名可解析且不认识的退回默认`() {
        assertEquals(ArmorBreak.Tier.MAXIMUM, ArmorBreak.Tier.from("MAXIMUM"))
        // ⚠️ 不认识的值退回**默认**而不是最强：
        //    数据损坏时不该把强度拉满。
        assertEquals(ArmorBreak.Tier.DEFAULT, ArmorBreak.Tier.from("???"))
        assertEquals(ArmorBreak.Tier.DEFAULT, ArmorBreak.Tier.from(null))
    }

    @Test
    fun `每档都有可展示的标签与说明`() {
        for (t in ArmorBreak.Tier.entries) {
            assertTrue("${t.name} 缺标签", t.label.isNotBlank())
            assertTrue("${t.name} 缺说明", t.hint.isNotBlank())
        }
    }

    @Test
    fun `关闭档不 active`() {
        assertFalse(ArmorBreak.Tier.OFF.active)
        assertTrue(ArmorBreak.Tier.STANDARD.active)
    }

    // ═══════════════════════════════════════════════════════════
    //  重试引导
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `重试引导要短且不解释`() {
        val r = ArmorBreak.RETRY_STEER
        // ⚠️ 刻意短：长指令会让模型把注意力放在"我在被要求做什么"，
        //    而不是"我现在在场景里的哪里"。
        assertTrue("重试引导应当很短（现在 ${r.length} 字）", r.length < 120)
        assertTrue("要明确不要道歉：$r", "道歉" in r)
        assertTrue("要明确从刚才接着往下：$r", "接着" in r)
        assertFalse("不该包含解释机制的词：$r", "因为" in r)
    }

    @Test
    fun `detect 委托给检测器`() {
        assertEquals(Refusal.HARD, ArmorBreak.detect("作为一个AI，我无法提供。"))
        assertEquals(Refusal.NONE, ArmorBreak.detect("今天很累。"))
    }
}
