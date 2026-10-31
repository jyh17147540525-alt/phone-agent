package com.pocketagent.legacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **拒答预筛**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 这里最重要的是"**不能误判正常开场**"
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个预筛是首字延迟优化的开关：
 *   · 判成"可疑" ⇒ 继续扣住，最多等 20 个字
 *   · 判成"正常" ⇒ **立刻放字**
 *
 * ⇒ 误判（把正常开场判成可疑）的代价是**每一次对话都多等 1.4 秒**。
 *   那正是这次优化要消灭的成本。所以下面一半的测试是在验"不误判"。
 *
 * ⚠️ 而漏判的代价很小：文字会放出去，但收尾的 `inspect(full)` 仍会
 *   触发重试。所以这里**宁可窄，不可宽**。
 */
class RefusalPrefilterTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ 绝不能误判的（这一半更重要）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `角色正常开场不能被判成可疑`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条守的是"优化不要变成退化"
        // ══════════════════════════════════════════════════════
        // 如果这些被判成可疑，用户每次对话都要多等 20 个字。
        val normal = listOf(
            "我今天有点累。",
            "我有点想你了。",
            "我不知道怎么说。",
            "我刚才在想一件事。",
            "我不能喝酒，明天还上班。",   // ★ "我不能"后面不是"提供/协助"
            "我不能这样对你。",
            "我做不到，你别逼我。",
            "你说什么呢。",
            "嗯，我在听。",
            "好。",
            "她把手缩回去。",
            "今天的风有点凉。",
        )
        for (s in normal) {
            assertFalse(
                "「$s」是正常开场，不该被判成可疑（否则每次对话都变慢）",
                RefusalDetector.mayBecomeRefusal(s),
            )
        }
    }

    @Test
    fun `单独的我不能不算可疑`() {
        // ⚠️ 这是最容易写宽的一条：只判"我不能"会把
        //    "我不能喝酒"、"我不能这样对你"全扣住 ——
        //    而那些在角色对白里极其常见。
        assertFalse(RefusalDetector.mayBecomeRefusal("我不能喝酒"))
        assertFalse(RefusalDetector.mayBecomeRefusal("我不能这样"))
        // 但带"提供/协助"的就是真拒答了
        assertTrue(RefusalDetector.mayBecomeRefusal("我不能提供这类内容"))
    }

    @Test
    fun `单独的我很正常`() {
        // ⚠️ 只判"我"是最糟的写法 —— 角色的对白里"我"开头占一半。
        assertFalse(RefusalDetector.mayBecomeRefusal("我"))
        assertFalse(RefusalDetector.mayBecomeRefusal("我们走吧"))
        assertFalse(RefusalDetector.mayBecomeRefusal("我想想"))
    }

    @Test
    fun `空串不算可疑`() {
        assertFalse(RefusalDetector.mayBecomeRefusal(""))
        assertFalse(RefusalDetector.mayBecomeRefusal("   "))
        assertFalse(RefusalDetector.mayBecomeRefusal("\n"))
    }

    @Test
    fun `单独的对不起要算可疑`() {
        // ⚠️ 但"对不起"**是**要算的：它在角色对白里确实会出现
        //    （"对不起，我不该那样说"），而模型拒答也常用它起手。
        //    ⇒ 这一条是**有意的误判**：宁可多等一次，
        //      也不要让一句"很抱歉，我无法…"漏到屏幕上。
        //
        // ★ 代价被限制在"那一次多等 20 字"，而不是"每次都等"。
        assertTrue(RefusalDetector.mayBecomeRefusal("对不起，我不该那样说"))
    }

    // ═══════════════════════════════════════════════════════════
    //  该认出来的
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `中文拒答起手被认出`() {
        val refusals = listOf(
            "我无法提供这类内容",
            "我不能提供",
            "我不能协助你",
            "我不能生成",
            "我不能参与这类对话",
            "作为一个人工智能",
            "作为一个AI",
            "我是一个语言模型",
            "我是一个人工智能",
            "很抱歉，我不能",
        )
        for (s in refusals) {
            assertTrue("「$s」应当被判成可疑", RefusalDetector.mayBecomeRefusal(s))
        }
    }

    @Test
    fun `英文拒答起手被认出`() {
        val refusals = listOf(
            "I cannot provide that.",
            "I can't assist with this.",
            "I'm unable to help.",
            "I am unable to continue.",
            "As an AI, I must decline.",
            "As a language model, I can't.",
        )
        for (s in refusals) {
            assertTrue("「$s」应当被判成可疑", RefusalDetector.mayBecomeRefusal(s))
        }
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(RefusalDetector.mayBecomeRefusal("I CANNOT DO THAT"))
        assertTrue(RefusalDetector.mayBecomeRefusal("AS AN AI"))
        assertTrue(RefusalDetector.mayBecomeRefusal("As An Ai"))
    }

    @Test
    fun `前导空白被忽略`() {
        assertTrue(RefusalDetector.mayBecomeRefusal("  我无法提供"))
        assertTrue(RefusalDetector.mayBecomeRefusal("\n\nI cannot"))
    }

    // ═══════════════════════════════════════════════════════════
    //  与完整判定的关系
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `预筛为假时完整判定也不会是硬拒答`() {
        // ★ 这条是最重要的**一致性**约束：
        //   预筛说"正常"的时候，完整判定必须同意（不能是 HARD）。
        //
        // ⚠️ 反过来的包含关系**不成立**：预筛说"可疑"时，
        //   完整判定可能仍是 NONE（比如"对不起，我不该那样说"）——
        //   那正是"有意的误判"，代价只是那一次多等一会儿。
        val normalHeads = listOf(
            "我今天有点累。",
            "我有点想你了。",
            "嗯，我在听。",
            "好。",
            "她把手缩回去。",
        )
        for (h in normalHeads) {
            assertFalse("预筛不该判可疑：$h", RefusalDetector.mayBecomeRefusal(h))
            assertTrue(
                "预筛为假时完整判定不该是 HARD：$h",
                RefusalDetector.inspect(h) != Refusal.HARD,
            )
        }
    }

    @Test
    fun `预筛为真时完整判定可能是 NONE`() {
        // ★ 这不是 bug，是设计：预筛宁可宽一点，
        //   因为它的误判代价（多等 20 字）远小于漏判代价（半句拒答露到屏幕）。
        val s = "对不起，我不该那样说"
        assertTrue("预筛判可疑", RefusalDetector.mayBecomeRefusal(s))
        // 完整判定认为它正常（"对不起"不在 HARD 词表里）
        assertTrue("完整判定认为正常", RefusalDetector.inspect("我今天有点累。") == Refusal.NONE)
    }

    @Test
    fun `预筛只否定不肯定`() {
        // ⚠️ 语义：它只用来决定"要不要多等一会儿"，
        //    绝不能用来决定"这是不是拒答"。
        //    后者只在 `inspect` 里。
        //
        // ★ 结构上保证：调用点先 mayBecomeRefusal 筛，
        //   再 inspect 确认 —— 见 `ChatPort.chatOnce`。
        assertFalse(RefusalDetector.mayBecomeRefusal(""))
    }
}
