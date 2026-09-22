package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务难度评估器。
 *
 * 这些测试的**核心价值不是"验证它判得准"** —— 准不准只有真机跑才知道。
 * 它们的价值是钉住三条不变量：
 *
 * 1. 判高不判低（保守原则）
 * 2. 依据必须可读、可验证（不是空列表，不是抽象描述）
 * 3. 同样的输入永远给同样的输出（确定性，否则调度不可复现）
 */
class TaskDifficultyAssessorTest {

    private val assessor = TaskDifficultyAssessor()

    // ── 基本分档 ─────────────────────────────────────────────────

    @Test
    fun `极短指令判为轻量`() {
        val result = assessor.assess("打开微信")

        assertEquals(ModelTier.LIGHT, result.tier)
    }

    @Test
    fun `含多步骤连接词的指令升到均衡档`() {
        val result = assessor.assess("先打开微信，然后找到小明，再发一条消息")

        assertTrue(result.tier.rank >= ModelTier.STANDARD.rank)
    }

    @Test
    fun `复杂规划类指令判为重型`() {
        val result = assessor.assess(
            "帮我分析一下这个月的开销，先看看支付宝的账单，然后对比一下微信的，" +
                "最后整理成一份总结，并且给出省钱的建议"
        )

        assertEquals(ModelTier.HEAVY, result.tier)
    }

    // ── 保守原则：宁高勿低 ───────────────────────────────────────

    @Test
    fun `含单个连接词就已表明不是单步操作`() {
        // ⚠️ 这条测试来自一个真实的修正。初版连接词权重是 1，
        //    于是"先打开微信，然后发消息"（2 个连接词 → 2 分）勉强够；
        //    但"接着来做这个事"（1 个 → 1 分）仍判 LIGHT ——
        //    而"接着来"本身就意味着有前置步骤。
        //    权重提到 2 之后，单个连接词即可跨过均衡档阈值。
        val result = assessor.assess("先打开微信，然后找到小明")

        assertTrue(result.tier.rank >= ModelTier.STANDARD.rank)
    }

    @Test
    fun `含推理类要求的指令至少是均衡档`() {
        // ⚠️ 同样来自一次真实修正：初版推理词权重 1，
        //    "帮我分析一下这几个数据"只拿 1 分，判成 LIGHT。
        //    而"分析"恰恰是最需要强模型的动词之一 —— 派给最弱的模型
        //    会让任务办砸，比多花钱严重得多（保守原则）。
        val result = assessor.assess("帮我分析一下这几个数据")

        assertTrue(result.tier.rank >= ModelTier.STANDARD.rank)
    }

    // ── 依据必须可读 ─────────────────────────────────────────────

    @Test
    fun `任何情况下依据都不能为空`() {
        val result = assessor.assess("嗯")

        assertTrue(result.reasons.isNotEmpty())
        assertTrue(result.summary.isNotEmpty())
    }

    @Test
    fun `没有特征时明确说明是默认档而不是空列表`() {
        val result = assessor.assess("你好")

        assertTrue(result.summary.contains("未发现复杂特征"))
    }

    @Test
    fun `依据里包含具体数字而不是抽象描述`() {
        val result = assessor.assess("先打开淘宝，然后搜索，接着下单，最后付款")

        // 依据应该提到连接词数量这类**可验证的事实**
        assertTrue(result.summary.contains("多步骤连接词"))
    }

    // ── 上下文线索 ───────────────────────────────────────────────

    @Test
    fun `已知涉及三个以上App时升档`() {
        val result = assessor.assess(
            instruction = "处理一下",
            context = TaskContext(knownAppCount = 4),
        )

        assertTrue(result.tier.rank >= ModelTier.STANDARD.rank)
        assertTrue(result.summary.contains("4 个 App"))
    }

    @Test
    fun `需要视觉理解时加权`() {
        val plain = assessor.assess("看看这个")
        val withVision = assessor.assess("看看这个", TaskContext(requiresVision = true))

        // 视觉信号是正权重的 —— 同等文本下不应更简单
        assertTrue(withVision.tier.rank >= plain.tier.rank)
        assertTrue(withVision.summary.contains("屏幕"))
    }

    // ── 确定性 ───────────────────────────────────────────────────

    @Test
    fun `同样输入永远给同样输出`() {
        val instruction = "先打开设置，然后找到蓝牙，最后打开它"

        val first = assessor.assess(instruction)
        val second = assessor.assess(instruction)

        assertEquals(first, second)
    }

    // ── 阈值可注入 ───────────────────────────────────────────────

    @Test
    fun `阈值可注入以便测试与调参`() {
        // 把重型阈值降到 1 —— 那么几乎任何有信号的指令都会判 HEAVY。
        // 注意要用**有信号**的指令："打开微信"得 0 分，即使阈值降了也还是 LIGHT。
        val aggressive = TaskDifficultyAssessor(
            DifficultySignals(heavyThreshold = 1, standardThreshold = 0)
        )

        val result = aggressive.assess("先打开微信，然后发消息")

        assertEquals(ModelTier.HEAVY, result.tier)
    }

    @Test
    fun `自定义连接词表生效`() {
        val custom = TaskDifficultyAssessor(
            DifficultySignals(sequencingMarkers = listOf("接着来"))
        )

        // 用一个默认表里没有、但自定义表里有的词
        val result = custom.assess("接着来做这个事")

        // 自定义表命中 → 至少均衡档（权重 2 的设计使单个连接词即可升档）
        assertTrue(result.tier.rank >= ModelTier.STANDARD.rank)
    }
}
