package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **漂移模拟** —— 用一个小玩具模型，把"越聊越 AI 味"这条假设跑出来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这是一个关于**机制**的模型，不是对某个模型的测量
 * ═══════════════════════════════════════════════════════════════
 *
 * 假设只有两条，都是可辩论的：
 *
 * 1. **向参照物回归**：模型下一句的风格，向它看到的 few-shot（历史里的
 *    assistant 消息）靠拢。权重 `PULL = 0.4`；
 * 2. **模板税**：每轮"照着自己写"都会丢一点分（重复、句长趋同、语气词消失）。`TAX = 1.2`。
 *
 * 这两个数字**是任意的**。本测试不对"掉多快"负责，只对**方向**负责：
 *
 * | 场景 | 结论 |
 * |---|---|
 * | 参照物 = 最近 8 条（现状） | 单调下滑，最后落到底 |
 * | 参照物 = 全程最高分的 3 条（接锚点） | 稳在一个高平台，从不崩塌 |
 *
 * 这就是本次改造的核心主张 —— 不是"再加一条规则"，而是**换参照物**。
 * 真机上掉多快、锚点值多少分，必须实测（归 WorkBuddy）。
 *
 * ★ 顺带交叉验证：把模拟出来的分数序列喂给真实的 [StyleDrift.measure]，
 *   看它判出来的趋势对不对 —— 这样这两个部件的口径不会各说各话。
 */
class StyleDriftSimulationTest {

    /** 向参照物回归的权重。 */
    private val pull = 0.4

    /** 模板税：每轮丢多少分。 */
    private val tax = 1.2

    /** 模型的固有口吻分。**换模型就是换这个数** —— 这是用户抱怨"切模型就变味"的建模方式。 */
    private val innate = 96.0

    /** 分数下限（真实场景里 REPEAT/FLAT 会饱和，不会无限掉）。 */
    private val floor = 20

    private data class Run(val scores: List<Int>, val injections: Int)

    /**
     * 跑 [rounds] 轮。
     *
     * @param gated true = 接再锚定（按 [Reanchor.shouldInject] 的真实判据触发）
     */
    private fun simulate(rounds: Int, gated: Boolean): Run {
        val scores = mutableListOf<Int>()
        var s = innate
        var injections = 0
        repeat(rounds) {
            val recent = scores.takeLast(8)
            val recentMean = if (recent.isEmpty()) innate else recent.average()

            // 锚点 = 全程最高分的三条（对应 pickAnchors 的"取高分 + 时间上偏向早期"）。
            // ⚠️ 关键：它从**全程**里选，不是从最近 8 条里选 —— 后者已经被污染了。
            val anchors = if (scores.size < 3) {
                emptyList()
            } else {
                scores.sortedDescending().take(3)
            }
            val anchorMean = if (anchors.isEmpty()) innate else anchors.average()

            val inject = gated && Reanchor.shouldInject(
                recentScores = recent,
                modelChanged = false,
                anchors = anchors.map { StyleDrift.Anchor("样本$it", it, "") },
            )
            if (inject) injections++

            val target = if (inject) anchorMean else recentMean
            s = (1 - pull) * s + pull * target - tax
            scores += s.coerceAtLeast(floor.toDouble()).toInt()
        }
        return Run(scores, injections)
    }

    @Test
    fun `不接锚点时一路下滑到底`() {
        val dry = simulate(rounds = 200, gated = false)
        assertTrue("起点该在 90 以上：${dry.scores.first()}", dry.scores.first() >= 90)
        assertTrue("200 轮后该落到地板：${dry.scores.last()}", dry.scores.last() <= 30)
        assertEquals("没有接锚点就不该有注入", 0, dry.injections)

        val report = StyleDrift.measure(dry.scores)
        assertTrue(
            "真实的漂移分析器也要能看出下滑：${report.logLine()}",
            report.trend == StyleDrift.Trend.DECLINING || report.trend == StyleDrift.Trend.COLLAPSING,
        )
        assertTrue("要能定位到开始掉的那一段：${report.logLine()}", report.worstTurn != null)
    }

    @Test
    fun `接了锚点就稳在高平台不会崩`() {
        val dry = simulate(rounds = 200, gated = false)
        val wet = simulate(rounds = 200, gated = true)

        assertTrue("全程不该跌破 60：最低 ${wet.scores.min()}", wet.scores.min() >= 60)
        assertTrue(
            "后半程均值要明显高于裸跑：${wet.scores.takeLast(100).average()} vs ${dry.scores.takeLast(100).average()}",
            wet.scores.takeLast(100).average() >= dry.scores.takeLast(100).average() + 20,
        )

        val report = StyleDrift.measure(wet.scores)
        assertEquals("接了锚点之后整体应判为平稳：${report.logLine()}", StyleDrift.Trend.STABLE, report.trend)
    }

    @Test
    fun `再锚定不是每轮都插`() {
        // ⚠️ 每轮都塞一段"照这个手感"会让它变成新的模板 —— 这个项目已经吃过
        //   "规则本身成模板"的亏（VoiceSamples 里那句"用三次是模板"）。
        val wet = simulate(rounds = 200, gated = true)
        assertTrue("该触发过：${wet.injections}", wet.injections >= 1)
        assertTrue(
            "不该每轮都插（200 轮里 ${wet.injections} 次）",
            wet.injections <= 60,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  真实文本上的一条硬断言
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `锚点的平均人味分高于历史平均`() {
        // 这是整个改造的**唯一一条实质主张** —— 换参照物，把参照线抬上去。
        // 前半段是干净样本，后半段是被自己带偏的样本（分更低）。
        val good = listOf(
            "嗯。那你先去睡吧。",
            "「随你。」（把杯子往你那边推了推）",
            "行吧，那我去了。",
            "……你听谁说的",
            "随你。",
        )
        val drifted = listOf(
            "好的，我已经帮你记下了。还有什么需要吗？",
            "需要注意的是，这件事得先确认一下再说。",
            "我之所以这么说，是因为我觉得应该先确认一下。",
            "**第一点**：早点睡\n**第二点**：多喝水",
            "总的来说，这件事我们明天再讨论会比较合适一些。",
        )
        val history = good + drifted

        val historyMean = history.map { HumanScore.of(it).total }.average()
        val picks = StyleDrift.pickAnchors(history, query = "在吗")
        val anchorMean = picks.map { it.score }.average()

        assertTrue("该挑出样本：${picks.map { it.text }}", picks.isNotEmpty())
        assertTrue(
            "锚点均值 $anchorMean 必须高于历史均值 $historyMean（否则白插一段）",
            anchorMean > historyMean,
        )
    }
}
