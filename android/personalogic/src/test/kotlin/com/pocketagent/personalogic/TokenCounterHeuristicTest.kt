package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`TokenCounter.Heuristic` 必须与 Provider 的估算一致**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：它已经漂过一次，而且没人发现
 * ═══════════════════════════════════════════════════════════════
 *
 * 接口注释本来就写着：
 *
 * > [Heuristic] 的系数**必须与 `OpenAiCompatProvider.estimateTextTokens` 一致**
 * > （中文约 1.5 字/token、英文约 4 字符/token）。两处若漂移，表现为
 * > "装配器以为还剩空间、网关那边已经超了"，而那是**静默的**。
 *
 * ⚠️ 而它**真的漂了**：
 *
 * | 输入 | 原公式 `(cjk*2+other)/3+1` | Provider `(cjk/1.5)+other/4+1` |
 * |---|---|---|
 * | 纯英文 100 字符 | **34** | **26** |
 * | 纯中文 100 字 | 67 | 67 |
 *
 * 纯英文差 **31%**。之所以没人发现：`Heuristic` **零测试** ——
 * 装配器的测试用的是 `TokenCounter { it.length }` 假实现。
 *
 * ⇒ 这个文件逐项钉住**具体数值**。两个模块不能共享代码
 *   （`:personalogic` 不依赖 `:provider:*`），只能靠数值钉。
 */
class TokenCounterHeuristicTest {

    private val counter = TokenCounter.Heuristic

    // ═══════════════════════════════════════════════════════════
    //  ★ 与 Provider 逐项对齐
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `纯英文按 4 字符每 token`() {
        // Provider: (0/1.5).toInt() + 100/4 + 1 = 26
        // ⚠️ 原公式给 34 —— 这里是回归钉子
        assertEquals(26, counter.count("a".repeat(100)))
    }

    @Test
    fun `纯中文按 1_5 字每 token`() {
        // Provider: (100/1.5).toInt() + 0 + 1 = 66 + 1 = 67
        assertEquals(67, counter.count("字".repeat(100)))
    }

    @Test
    fun `中英混排分别计算再相加`() {
        // cjk=50, other=40 → (50/1.5)=33 + 40/4=10 + 1 = 44
        val text = "字".repeat(50) + "a".repeat(40)
        assertEquals(44, counter.count(text))
    }

    @Test
    fun `空串仍算一个 token`() {
        // ⚠️ 返回 0 会让"按 token 计的预算"永远不触发 ——
        //    与 `LlmProvider.countTokens` 的 `coerceAtLeast(1)` 同一纪律。
        assertEquals(1, counter.count(""))
    }

    @Test
    fun `不可见字符按 ASCII 处理`() {
        // 空格、换行不算 CJK
        val withSpaces = counter.count("a".repeat(100) + " ".repeat(20))
        // other=120 → 120/4=30 + 1 = 31
        assertEquals(31, withSpaces)
    }

    @Test
    fun `中文标点归入 CJK`() {
        // 全角标点（0xFF00..0xFFEF）与 CJK 标点（0x3000..0x303F）都算 CJK
        val punctuation = "，。！？".repeat(25) // 100 个
        assertEquals(
            "全角标点应与汉字同样计价",
            counter.count("字".repeat(100)),
            counter.count(punctuation),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  单调性
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `文本越长估算越大`() {
        var prev = 0
        for (n in listOf(10, 50, 200, 1000)) {
            val cur = counter.count("字".repeat(n))
            assertTrue("长度 $n 的估算应当递增：$prev → $cur", cur > prev)
            prev = cur
        }
    }

    @Test
    fun `中文比等长英文贵`() {
        // 中文 1.5 字/token，英文 4 字符/token ⇒ 中文约贵 2.7 倍
        val zh = counter.count("字".repeat(100))
        val en = counter.count("a".repeat(100))
        assertTrue("中文($zh) 应当比同长度英文($en) 贵", zh > en)
    }
}
