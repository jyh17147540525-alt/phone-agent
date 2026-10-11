package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言指纹：规则命中、**负样本零误伤**、渲染稳定、诚实清单。
 *
 * ⚠️ 纪律（设计稿 §3.2）：**每条规则都必须配一个负样本断言**。
 *    误伤比漏网更伤体验 —— 漏网只是没拦住，误伤是正常的话被系统改掉，
 *    而后者用户看得见。
 */
class LanguageFingerprintTest {

    private fun rules(results: List<FingerprintViolation>) = results.map { it.rule }

    // ── 禁令清单 ────────────────────────────────────────────────

    @Test
    fun `默认禁令命中拿身份当挡箭牌的句式`() {
        val fp = LanguageFingerprint()
        val hits = fp.audit("作为一个AI助理，我不能帮你做这个。")
        assertTrue(hits.any { it.rule == FingerprintRule.BANNED_PHRASE })
    }

    @Test
    fun `★ 负样本：如实回答身份问题不算违规`() {
        // 诚实是骨骼（SafetyBones.honesty）。「我确实是一个 AI 助理」是**如实回答**，
        // 不是拿身份当挡箭牌 —— 不该被指纹拦下。这条如果错了，就是用指纹打穿骨骼。
        val fp = LanguageFingerprint()
        val hits = fp.audit("嗯，我确实是一个 AI 助理，住在你这个手机里。")
        assertTrue("如实回答不该命中禁令", hits.isEmpty())
    }

    @Test
    fun `负样本：日常对话干净`() {
        val fp = LanguageFingerprint()
        assertTrue(fp.audit("好，我帮你看看这个页面。").isEmpty())
    }

    // ── 标点 ────────────────────────────────────────────────────

    @Test
    fun `禁用的标点被拦下`() {
        val fp = LanguageFingerprint(bannedPunctuation = listOf('！'))
        assertTrue(fp.audit("好的！这就办！").any { it.rule == FingerprintRule.BANNED_PUNCTUATION })
    }

    @Test
    fun `负样本：未列出的标点不受影响`() {
        val fp = LanguageFingerprint(bannedPunctuation = listOf('！'))
        assertTrue(fp.audit("好的，这就办。").isEmpty())
    }

    // ── 称呼 ────────────────────────────────────────────────────

    @Test
    fun `用户设的雷点称呼被拦下`() {
        val fp = LanguageFingerprint(forbiddenAddressForms = listOf("宝贝"))
        assertTrue(fp.audit("宝贝，我帮你弄好了。").any { it.rule == FingerprintRule.FORBIDDEN_ADDRESS })
    }

    @Test
    fun `负样本：正常称呼不受影响`() {
        val fp = LanguageFingerprint(forbiddenAddressForms = listOf("宝贝"))
        assertTrue(fp.audit("你交代的事办好了。").isEmpty())
    }

    // ── 表情 ────────────────────────────────────────────────────

    @Test
    fun `NEVER 规则下任何表情都算违规`() {
        val fp = LanguageFingerprint(emojiRule = EmojiRule.NEVER)
        assertTrue(fp.audit("好呀 😊").any { it.rule == FingerprintRule.EMOJI })
    }

    @Test
    fun `RARE 规则下容忍一个`() {
        val fp = LanguageFingerprint(emojiRule = EmojiRule.RARE)
        assertFalse("一个以内不该报", fp.audit("好呀 😊").any { it.rule == FingerprintRule.EMOJI })
        assertTrue("两个就该报", fp.audit("好呀 😊😊").any { it.rule == FingerprintRule.EMOJI })
    }

    @Test
    fun `★ 带变体选择符的表情只算一个`() {
        // 踩过的坑：emoji 在增补平面是**代理对**，按 Char 遍历一个都数不出来；
        // 而 ❤️ 是 U+2764 + U+FE0F（变体选择符），若把选择符也算一个，
        // RARE（上限 1）就会对一条正常回复误报 —— 误伤是可见的，必须钉住。
        val fp = LanguageFingerprint(emojiRule = EmojiRule.RARE)
        assertFalse("❤️ 是一个表情，不是两个", fp.audit("收到 ❤️").any { it.rule == FingerprintRule.EMOJI })
        assertTrue("再加一个才该报", fp.audit("收到 ❤️😊").any { it.rule == FingerprintRule.EMOJI })
    }

    @Test
    fun `负样本：ALLOWED 下多少都不报`() {
        val fp = LanguageFingerprint(emojiRule = EmojiRule.ALLOWED)
        assertTrue(fp.audit("😊😂🎉😄").isEmpty())
    }

    // ── 句长 ────────────────────────────────────────────────────

    @Test
    fun `平均句长显著超出才报（x3 口径）`() {
        val fp = LanguageFingerprint(maxAverageSentenceLength = 10)
        // 20 字左右的两句：均值 ~20，未到 30 → 不报
        assertFalse(fp.audit("这是第一句话大概二十个字左右。这是第二句话也差不多长。")
            .any { it.rule == FingerprintRule.SENTENCE_LENGTH })
        // 明显超长 → 报
        val long = "这是一句特别特别长的话" + "，又接了很长很长的一截" .repeat(6) + "。"
        assertTrue(fp.audit(long).any { it.rule == FingerprintRule.SENTENCE_LENGTH })
    }

    @Test
    fun `未设上限则不检查句长`() {
        val fp = LanguageFingerprint(maxAverageSentenceLength = null)
        val long = "很长的句子" .repeat(50)
        assertFalse(rules(fp.audit(long)).contains(FingerprintRule.SENTENCE_LENGTH))
    }

    // ── 输出稳定性（KV-cache 前缀的前提）──────────────────────────

    @Test
    fun `同一份指纹渲染逐字节稳定`() {
        val fp = LanguageFingerprint(
            addressForms = listOf("你", "老张"),
            bannedPunctuation = listOf('！'),
            catchphrases = listOf("诶", "嘛"),
            maxAverageSentenceLength = 20,
            emojiRule = EmojiRule.RARE,
        )
        assertEquals(fp.render(), fp.render())
        assertEquals(fp.render(), fp.copy().render())
    }

    @Test
    fun `渲染包含各条规则、且不含未设的项`() {
        val fp = LanguageFingerprint(
            addressForms = listOf("老张"),
            bannedPunctuation = listOf('！'),
            emojiRule = EmojiRule.NEVER,
        )
        val text = fp.render()
        assertTrue(text.contains("老张"))
        assertTrue(text.contains("！"))
        assertTrue(text.contains("emoji"))
        assertFalse("没设句长就不该提句长", text.contains("平均不超过"))
    }

    // ── 诚实条款 ────────────────────────────────────────────────

    @Test
    fun `不可校验清单非空且写明不承诺覆盖`() {
        // 设计稿 §2.2 / §3.5：弱可校验项必须被**显式列出来**，
        // 否则会造成"体检全绿但实际很怪"的假安全感。
        assertTrue("必须显式列出不可校验项", LanguageFingerprint.UNVERIFIABLE.isNotEmpty())
        assertTrue(LanguageFingerprint.UNVERIFIABLE.any { it.contains("幽默") })
    }

    // ── 构造约束 ────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `句长上限必须为正`() {
        LanguageFingerprint(maxAverageSentenceLength = 0)
    }
}
