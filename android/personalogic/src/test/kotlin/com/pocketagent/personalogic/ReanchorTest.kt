package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Reanchor] / [StyleContract] / [ModelFamily] 的离线钉子。
 *
 * ⚠️ 这里钉的是**形状与触发条件**，不是"插进去之后她真的更像人"。
 *    位置效应（system 末尾 vs 紧跟历史之后）必须真机比，见 [Reanchor] 类注释。
 */
class ReanchorTest {

    private fun anchor(text: String) = StyleDrift.Anchor(text, 95, "9 字、1 句")

    // ═══════════════════════════════════════════════════════════
    //  一、段落渲染
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有锚点就不写这一段`() {
        assertEquals("", Reanchor.section(emptyList()))
    }

    @Test
    fun `锚点原文必须逐字出现`() {
        // ⚠️ 改一个字就变成"我们写的仿句"，模仿强度会掉 —— 必须是原话。
        val picks = listOf(anchor("「随你。」（把杯子往你那边推了推）"), anchor("嗯。那你先去睡吧。"))
        val s = Reanchor.section(picks)
        for (p in picks) {
            assertTrue("原文没出现：${p.text}\n---\n$s", s.contains(p.text))
        }
        assertTrue("要有「」包起来，让她知道这是引用：$s", s.contains("「「随你。」"))
    }

    @Test
    fun `段落里写的是正面样本不是禁令清单`() {
        val s = Reanchor.section(listOf(anchor("随你。")))
        assertFalse("不该出现「禁止」这类框定：$s", s.contains("禁止"))
        assertFalse("不该出现「人设」这类元词：$s", s.contains("人设"))
        assertTrue("要交代这是她自己的原话：$s", s.contains("原话"))
        assertTrue("要防止整句照抄：$s", s.contains("不要照抄"))
    }

    @Test
    fun `尺寸行跟着说话长度档位走`() {
        val brief = Reanchor.section(
            listOf(anchor("随你。")),
            StyleContract.of(PersonaVerbosity.BRIEF),
        )
        assertTrue("惜字如金该是 40 字：$brief", brief.contains("40 字"))

        val chatty = Reanchor.section(
            listOf(anchor("随你。")),
            StyleContract.of(PersonaVerbosity.CHATTY),
        )
        assertTrue("话多该是 160 字：$chatty", chatty.contains("160 字"))
    }

    @Test
    fun `没有约定就不写尺寸行`() {
        val s = Reanchor.section(listOf(anchor("随你。")), contract = null)
        assertFalse("不该凭空规定字数：$s", s.contains("别超过"))
        assertFalse(s.contains("这一轮："))
    }

    @Test
    fun `超长时丢掉尾部的锚点而不是截断单条`() {
        // 半句话的样本会被学成"她说话爱断在半截"，所以宁可少给一条。
        val a = anchor("第一句" + "啊".repeat(20))
        val b = anchor("第二句" + "呀".repeat(20))
        val s = Reanchor.section(listOf(a, b), maxChars = 100)
        assertTrue("该保住第一条：$s", s.contains(a.text))
        assertFalse("该丢掉第二条：$s", s.contains(b.text))
    }

    // ═══════════════════════════════════════════════════════════
    //  二、触发条件
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有锚点就不插`() {
        // 插一段"你要像人"却没有样本，等于纯噪声 + 一次缓存失效。
        assertFalse(Reanchor.shouldInject(listOf(40, 40), modelChanged = true, anchors = emptyList()))
    }

    @Test
    fun `连续两条偏低才插`() {
        val a = listOf(anchor("随你。"))
        assertTrue(Reanchor.shouldInject(listOf(88, 69, 65), modelChanged = false, anchors = a))
        assertFalse(Reanchor.shouldInject(listOf(88, 88, 65), modelChanged = false, anchors = a))
    }

    @Test
    fun `刚换模型就插一次`() {
        // ★ 用户原话：「特别是切换模型的时候」。新模型对她的全部认知只有提示词和历史，
        //   所以第一轮就把声音样本摆到它面前，不等它先漂。
        val a = listOf(anchor("随你。"))
        assertTrue(Reanchor.shouldInject(listOf(95, 95), modelChanged = true, anchors = a))
    }

    // ═══════════════════════════════════════════════════════════
    //  三、prefill 候选（当前未接线）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `只挑没有句末标点的锚点当 prefill`() {
        val picks = listOf(anchor("随你。"), anchor("……你听谁说的"))
        val p = Reanchor.prefillCandidate(picks)
        assertEquals("她：「……你听谁说的", p)
    }

    @Test
    fun `全都收尾完整时不给 prefill`() {
        assertNull(Reanchor.prefillCandidate(listOf(anchor("随你。"), anchor("嗯。"))))
        assertNull(Reanchor.prefillCandidate(emptyList()))
    }

    // ═══════════════════════════════════════════════════════════
    //  四、尺寸约定与模型族
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `说话长度映射到字数与条数`() {
        val b = StyleContract.of(PersonaVerbosity.BRIEF)
        assertEquals(40, b.maxChars)
        assertEquals(1..2, b.messages)

        val n = StyleContract.of(PersonaVerbosity.NORMAL)
        assertEquals(90, n.maxChars)

        val c = StyleContract.of(PersonaVerbosity.CHATTY)
        assertEquals(160, c.maxChars)
        assertEquals(1..3, c.messages)
    }

    @Test
    fun `通用族不写族提示`() {
        assertNull(StyleContract.of(PersonaVerbosity.NORMAL, ModelFamily.GENERIC).familyLine())
    }

    @Test
    fun `各族提示都是正面描述`() {
        // ⚠️ 「不要说思考过程」这种否定框定会被读成肯定 —— 这个项目已为此改过两轮。
        for (f in ModelFamily.values()) {
            val line = StyleContract.of(PersonaVerbosity.NORMAL, f).familyLine() ?: continue
            assertFalse("$f 的提示里有否定句：$line", line.contains("不要"))
            assertFalse("$f 的提示里有否定句：$line", line.contains("别"))
        }
    }

    @Test
    fun `按模型名认族`() {
        assertEquals(ModelFamily.REASONING, ModelFamily.of("deepseek-r1"))
        assertEquals(ModelFamily.REASONING, ModelFamily.of("o3-mini"))
        assertEquals(ModelFamily.REASONING, ModelFamily.of("qwen3-thinking"))
        assertEquals(ModelFamily.CREATIVE, ModelFamily.of("kimi-k2"))
        assertEquals(ModelFamily.CREATIVE, ModelFamily.of("moonshot-v1"))
        assertEquals(ModelFamily.INSTRUCT_HEAVY, ModelFamily.of("gpt-4o"))
        assertEquals(ModelFamily.INSTRUCT_HEAVY, ModelFamily.of("claude-3-5-sonnet"))
        assertEquals(ModelFamily.INSTRUCT_HEAVY, ModelFamily.of("deepseek-chat"))
    }

    @Test
    fun `认不出来就落通用`() {
        assertEquals(ModelFamily.GENERIC, ModelFamily.of(null))
        assertEquals(ModelFamily.GENERIC, ModelFamily.of(""))
        assertEquals(ModelFamily.GENERIC, ModelFamily.of("some-local-gguf"))
    }

    @Test
    fun `GPT-4o 不会被误认成推理族`() {
        // 「o1」是子串判据，名字里带 4o 的不该命中。
        assertEquals(ModelFamily.INSTRUCT_HEAVY, ModelFamily.of("gpt-4o-mini"))
    }
}
