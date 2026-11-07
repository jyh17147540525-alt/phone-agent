package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PromptVoice] 的测试。
 *
 * ⚠️ 这里最要紧的不是"每个规则能命中"（那是最低要求），
 *    而是**最后那组存量体检** —— 它会往生产段落上钉钉子。
 *    以后任何人往提示词里加一个破折号或列表符号，这组测试就会红。
 */
class PromptVoiceTest {

    // ──────────────────────────────────────────────────────────────
    //  每条规则都要能命中，并且能说清命中了什么
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `行首列表符号是最严重的一条`() {
        val report = PromptVoice.audit("## 说话方式\n- 不要每句都完整。\n")
        val bullet = report.findings.first { it.code == PromptVoice.Code.BULLET }
        assertEquals(PromptVoice.Level.LOUD, bullet.code.level)
        assertTrue(bullet.evidence.contains("不要每句都完整"))
    }

    @Test
    fun `编号列表一样会教坏格式`() {
        val report = PromptVoice.audit("1. 短一点\n2. 别总结")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.BULLET })
    }

    @Test
    fun `星号加粗算教坏格式`() {
        val report = PromptVoice.audit("下面这些是**她自己**以前的原话")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.BOLD })
    }

    @Test
    fun `破折号只抓装饰性的那种`() {
        val hit = PromptVoice.audit("不是范例 —— 是她说话的样子")
        assertTrue(hit.findings.any { it.code == PromptVoice.Code.EMDASH })

        // 单个连字符不算：命令行片段、负号都在这一档。
        val miss = PromptVoice.audit("用 -Pandroid.overridePathCheck=true 编译")
        assertFalse(miss.findings.any { it.code == PromptVoice.Code.EMDASH })
    }

    @Test
    fun `emoji 会被抓到`() {
        val report = PromptVoice.audit("说得对 👍")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.EMOJI })
    }

    @Test
    fun `勾和星号不算 emoji`() {
        // ⚠️ 这两个符号在项目注释里到处都是，检查器不能把它们当表情
        val report = PromptVoice.audit("已经验证 ✓ 关键路径 ★")
        assertFalse(report.findings.any { it.code == PromptVoice.Code.EMOJI })
    }

    @Test
    fun `不是而是这种对偶会被抓到`() {
        val report = PromptVoice.audit("它不是范例，而是她说话的样子")
        val hit = report.findings.first { it.code == PromptVoice.Code.NOT_BUT }
        assertTrue(hit.hint.contains("后面那半句"))
    }

    @Test
    fun `抽象三连会被抓到`() {
        val report = PromptVoice.audit("角色要有连续性、一致性、情境感。")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.TRICOLON })
    }

    @Test
    fun `三个具体名词不算三连`() {
        // 具体名词没有抽象后缀，不该被判成排比腔
        val report = PromptVoice.audit("桌上有杯子、遥控器、袜子。")
        assertFalse(report.findings.any { it.code == PromptVoice.Code.TRICOLON })
    }

    @Test
    fun `元话语会被抓到`() {
        val report = PromptVoice.audit("换句话说，你不用把话说完整。")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.METATALK })
    }

    @Test
    fun `务必和请注意同属元话语`() {
        val report = PromptVoice.audit("务必记住这一点。")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.METATALK })
    }

    @Test
    fun `口号会被抓到`() {
        val report = PromptVoice.audit("关键在于把语气做对。")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.SLOGAN })
    }

    @Test
    fun `连续两行等长且同标点算排比`() {
        val report = PromptVoice.audit("她现在没多少力气了。\n她现在不太想说话了。")
        assertTrue(report.findings.any { it.code == PromptVoice.Code.PARALLEL })
    }

    @Test
    fun `两行长短差得多就不算排比`() {
        val report = PromptVoice.audit("累了。\n她现在一句话都不想说，只想靠着。")
        assertFalse(report.findings.any { it.code == PromptVoice.Code.PARALLEL })
    }

    // ──────────────────────────────────────────────────────────────
    //  不误伤：人写的短句、说话的口吻，不该被判成 AI 味
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `干净的一段就没有 findings`() {
        val clean = "累就躺下。\n别躺我腿上。"
        assertTrue(PromptVoice.audit(clean).isClean)
    }

    @Test
    fun `空段落不算干净也不算脏`() {
        assertTrue(PromptVoice.audit("").isClean)
    }

    @Test
    fun `日志只报数量不报原文`() {
        val line = PromptVoice.audit("- 不要每句都完整，比如「我爱你」这种。").logLine()
        assertFalse(line.contains("我爱你"))
        assertTrue(line.contains("1 处"))
    }

    @Test
    fun `breakdown 按严重级排序`() {
        val report = PromptVoice.audit("换句话说 - 短一点")
        val text = report.breakdown()
        assertTrue(text.indexOf("列表符号") < text.indexOf("元话语"))
    }

    // ──────────────────────────────────────────────────────────────
    //  ★ 存量体检：生产里现在真的会进 system prompt 的那些段
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `生产段落里没有会教坏格式的记号`() {
        val sections = productionSections()
        val dirty = sections.mapValues { PromptVoice.audit(it.value) }.filterValues { it.loud > 0 }
        val message = dirty.entries.joinToString("\n") { "${it.key}：\n${it.value.breakdown()}" }
        assertTrue("这些段落里还有会被照抄的形状：\n$message", dirty.isEmpty())
    }

    @Test
    fun `生产段落里也没有排比和口号`() {
        val sections = productionSections()
        val dirty = sections.mapValues { PromptVoice.audit(it.value) }.filterValues { it.tone > 0 }
        val message = dirty.entries.joinToString("\n") { "${it.key}：\n${it.value.breakdown()}" }
        assertTrue("这些段落的腔调会传染：\n$message", dirty.isEmpty())
    }

    /**
     * 现在真的会被拼进 system prompt 的段落。
     *
     * ⚠️ 这张表是**手维护**的 —— 加新的 promptSection 时忘了登记，
     *    它就没人看着。所以每个新段落都应该顺手加一行。
     */
    private fun productionSections(): Map<String, String> {
        val anchors = listOf(
            StyleDrift.Anchor("随你。", 92, "3 字、1 句、没句号"),
            StyleDrift.Anchor("我不困。就是不想动。", 88, "9 字、2 句"),
        )
        val contract = StyleContract.of(PersonaVerbosity.NORMAL, ModelFamily.REASONING)
        val findings = Humanizer.diagnose(
            replies = listOf("好的，还有什么需要我帮忙的吗？", "希望对你有帮助。"),
            stage = RelationshipStage.CLOSE,
            turnsSinceStageChange = 20,
        )
        return mapOf(
            "HumanText.alwaysSection" to HumanText.alwaysSection(),
            "Humanizer.promptSection" to Humanizer.promptSection(
                findings,
                stage = RelationshipStage.CLOSE,
                verbosity = PersonaVerbosity.NORMAL,
            ),
            "RoleplayDiscipline.promptSection" to RoleplayDiscipline.promptSection("老板"),
            "Reanchor.section" to Reanchor.section(anchors, contract),
            "Reanchor.section（无尺寸行）" to Reanchor.section(anchors),
            "VoiceSamples.promptSection" to VoiceSamples.promptSection("老板"),
            // ★ 2026-11-06 第七批：这三段原本漏在体检表外，各自都带着列表/加粗/元话语。
            "LanguageFingerprint.render" to LanguageFingerprint(
                addressForms = listOf("老板"),
                bannedPunctuation = listOf('…'),
                catchphrases = listOf("啊", "吧"),
                maxAverageSentenceLength = 30,
                emojiRule = EmojiRule.RARE,
            ).render(),
            "WorkDiscipline.promptSection" to WorkDiscipline.promptSection("老板"),
            "WorldBook.promptSection" to WorldBook(
                listOf(
                    WorldEntry(
                        id = "kitchen",
                        title = "厨房",
                        keys = listOf("厨房"),
                        content = "厨房的灯是坏的，晚上得摸黑进去。",
                    ),
                ),
            ).promptSection("厨房的灯"),
            // ★ 2026-11-06：角色边界三段。这一段教的是"别像文档"，
            //   自己要是带了列表符就是自打嘴巴。
            "CharacterEdge.promptSection" to CharacterEdge.promptSection(),
            "CharacterEdge.promptSection（带 known）" to CharacterEdge.promptSection(
                listOf("做饭", "擦桌子"),
            ),
        )
    }
}
