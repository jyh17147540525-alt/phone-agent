package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输出守卫：只重写一次、强弱规则分流、负样本零误伤、指令不回显原文。
 */
class OutputGuardTest {

    private val guard = OutputGuard()
    private val fp = LanguageFingerprint(
        addressForms = listOf("你"),
        forbiddenAddressForms = listOf("宝贝"),
        bannedPunctuation = listOf('！'),
        maxAverageSentenceLength = 10,
    )

    // ── 干净输出 ────────────────────────────────────────────────

    @Test
    fun `干净输出直接放行且不标破环`() {
        val d = guard.inspect("好，我帮你看看。", fp)
        assertTrue(d is GuardDecision.Pass)
        assertFalse(d.isOoc)
    }

    @Test
    fun `★ 负样本：如实回答身份问题不算破环`() {
        // 与 LanguageFingerprintTest 同一条纪律：诚实是骨骼，不能被指纹拦。
        val d = guard.inspect("嗯，我确实是一个 AI 助理。", fp)
        assertTrue("不该触发任何处理", d is GuardDecision.Pass)
    }

    // ── 确定性命中 → 重写一次 ──────────────────────────────────

    @Test
    fun `确定性命中且还有额度时给出修正指令`() {
        val d = guard.inspect("作为一个AI助理，这个我不能做。", fp, rewritesSoFar = 0)
        assertTrue(d is GuardDecision.Rewrite)
        val r = d as GuardDecision.Rewrite
        assertTrue("指令要说清是哪一类问题", r.instruction.contains("不该出现的说法"))
        assertTrue("要叮嘱不要提这条要求本身", r.instruction.contains("不要提到这条要求本身"))
        assertFalse(
            "★ 指令不得回显违规原文（否则等于让模型再看一遍）",
            r.instruction.contains("作为一个AI"),
        )
        assertFalse("重写中还没定论，不算破环", r.isOoc)
    }

    @Test
    fun `★ 额度用尽仍然命中则放行并记为破环`() {
        // 纪律 1：只重写一次。第二次仍失败就放行 —— 否则一轮对话会卡死在
        // 一个改不好的句子上（延迟不可用），而且再重试也说明问题不在措辞。
        val d = guard.inspect("作为一个AI助理，这个我不能做。", fp, rewritesSoFar = 1)
        assertTrue(d is GuardDecision.PassThrough)
        assertTrue("放行但必须记录", (d as GuardDecision.PassThrough).violations.isNotEmpty())
        assertTrue("这一轮就是破环轮", d.isOoc)
    }

    @Test
    fun `maxRewrites 为 0 时不重写只记录`() {
        val strict = OutputGuard(maxRewrites = 0)
        val d = strict.inspect("好的！", fp)
        assertTrue(d is GuardDecision.PassThrough)
        assertTrue(d.isOoc)
    }

    // ── 统计性命中 → 只记录 ────────────────────────────────────

    @Test
    fun `★ 只有统计性命中时不重写也不记为破环`() {
        // 句长会自然波动（讲复杂的事就会变长）。为它重写会把"说清楚"
        // 改成"说很短" —— 误伤且用户看得见。
        val long = "这是一句很长的句子" + "，然后又接了一截".repeat(10) + "。"
        val d = guard.inspect(long, fp)
        assertTrue("只提示不重写", d is GuardDecision.PassThrough)
        assertTrue((d as GuardDecision.PassThrough).violations.any { it.rule == FingerprintRule.SENTENCE_LENGTH })
        assertFalse("统计性项不构成破环", d.isOoc)
    }

    @Test
    fun `多个规则同时命中时指令列全`() {
        val d = guard.inspect("宝贝！作为一个AI我不能。", fp)
        val r = d as GuardDecision.Rewrite
        assertTrue(r.instruction.contains("不该出现的说法"))
        assertTrue(r.instruction.contains("不该用的标点"))
        assertTrue(r.instruction.contains("不该用的称呼"))
        assertFalse("任何人一段原文都不许出现", r.instruction.contains("宝贝"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxRewrites 不能为负`() {
        OutputGuard(maxRewrites = -1)
    }

    // ── GuardLedger（喂给记忆闸门）──────────────────────────────

    @Test
    fun `守卫生成轮次标记供记忆闸门使用`() {
        val ledger = GuardLedger()
        assertFalse("干净轮不标记", ledger.record("t1", GuardDecision.Pass))
        assertFalse(
            "重写中的轮次暂不标记（还没定论）",
            ledger.record("t2", GuardDecision.Rewrite("x", emptyList())),
        )
        assertTrue(
            "放行但已知有问题的轮次必须标记",
            ledger.record("t3", GuardDecision.PassThrough(
                listOf(FingerprintViolation(FingerprintRule.BANNED_PHRASE, "x")),
            )),
        )
        assertEquals(setOf("t3"), ledger.oocTurnIds())
    }

    @Test
    fun `统计性命中的轮次不进破环集合`() {
        val ledger = GuardLedger()
        ledger.record("t1", GuardDecision.PassThrough(
            listOf(FingerprintViolation(FingerprintRule.SENTENCE_LENGTH, "平均 40 字")),
        ))
        assertTrue("句长超一点不该让整轮记忆被丢掉", ledger.oocTurnIds().isEmpty())
    }

    @Test
    fun `体检计数正确且空样本时比例为 null`() {
        val ledger = GuardLedger()
        assertEquals("没检查过时比例必须是 null，不是 0 或 1", null, ledger.stats().oocRatio)

        ledger.record("t1", GuardDecision.Pass)
        ledger.record("t2", GuardDecision.Rewrite("x", emptyList()))
        ledger.record("t3", GuardDecision.PassThrough(
            listOf(FingerprintViolation(FingerprintRule.BANNED_PHRASE, "x")),
        ))

        val s = ledger.stats()
        assertEquals(3, s.checkedTurns)
        assertEquals(1, s.rewriteTriggers)
        assertEquals(1, s.oocTurns)
        assertEquals(1.0 / 3.0, s.oocRatio!!, 1e-9)
    }

    @Test
    fun `破环轮次去重`() {
        val ledger = GuardLedger()
        val decided = GuardDecision.PassThrough(
            listOf(FingerprintViolation(FingerprintRule.BANNED_PHRASE, "x")),
        )
        ledger.record("t1", decided)
        ledger.record("t1", decided)
        assertEquals("同一轮记两次仍是一条", 1, ledger.oocTurnIds().size)
        assertEquals("但检查次数如实记", 2, ledger.stats().checkedTurns)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空 turnId 被拒绝`() {
        GuardLedger().record("  ", GuardDecision.Pass)
    }

    @Test
    fun `空样本的体检不返回假比例`() {
        val s = GuardStats(checkedTurns = 0, rewriteTriggers = 0, oocTurns = 0)
        assertNull(s.oocRatio)
    }
}
