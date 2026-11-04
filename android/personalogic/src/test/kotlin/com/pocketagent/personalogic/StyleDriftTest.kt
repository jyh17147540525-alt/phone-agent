package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StyleDrift] 的离线钉子。
 *
 * ★ 这些测试是整个"越聊越 AI 味"改造的**数学部分**：
 *   趋势、斜率、拐点、选材，全部是确定性算术，所以可以被钉死。
 *   钉不死的部分是"真机上她听起来有没有变"——那部分归 WorkBuddy。
 */
class StyleDriftTest {

    // ═══════════════════════════════════════════════════════════
    //  一、趋势判定
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空序列不出结论`() {
        val r = StyleDrift.measure(emptyList())
        assertEquals(StyleDrift.Trend.UNKNOWN, r.trend)
        assertEquals(0, r.samples)
    }

    @Test
    fun `样本不足四轮叫不出趋势但数字照给`() {
        val r = StyleDrift.measure(listOf(92, 92, 92))
        assertEquals(StyleDrift.Trend.UNKNOWN, r.trend)
        assertEquals(3, r.samples)
        assertEquals(92.0, r.head, 1e-9)
        assertEquals(92.0, r.tail, 1e-9)
    }

    @Test
    fun `一路下滑会判成在下滑`() {
        // 一轮掉一分左右：是"慢慢变味"，还没到"头尾差 15 分"的崩塌线。
        val r = StyleDrift.measure(listOf(92, 91, 90, 89, 88, 87, 86, 85, 84, 83))
        assertEquals(r.logLine(), StyleDrift.Trend.DECLINING, r.trend)
        assertTrue("斜率该是负的：${r.slope}", r.slope < 0)
        assertTrue("尾部该低于头部", r.drop < 0)
    }

    @Test
    fun `头尾差超过十五分直接判崩塌`() {
        // ⚠️ 这是"崩塌"与"下滑"的分界：掉一点点是风格漂，掉一大截是换了一个人。
        val r = StyleDrift.measure(listOf(92, 90, 89, 86, 84, 81, 78, 76, 73, 70))
        assertEquals(r.logLine(), StyleDrift.Trend.COLLAPSING, r.trend)
        assertTrue("头尾差该超过警戒线：${r.drop}", r.drop <= StyleDrift.STEEP_DROP)
    }

    @Test
    fun `噪声范围内的抖动算平稳`() {
        val r = StyleDrift.measure(listOf(88, 86, 89, 87, 88, 86, 88, 87))
        assertEquals(r.logLine(), StyleDrift.Trend.STABLE, r.trend)
    }

    @Test
    fun `尾部掉进 AI 档直接判崩塌`() {
        val r = StyleDrift.measure(listOf(90, 88, 60, 40, 35, 30))
        assertEquals(r.logLine(), StyleDrift.Trend.COLLAPSING, r.trend)
        assertTrue("尾部均值该低于崩线：${r.tail}", r.tail < HumanScore.BROKEN_AT)
    }

    @Test
    fun `陡降即使尾部还及格也判崩塌`() {
        // ★ 用户抱怨的是"前几条挺好、越聊越不对"。等尾部跌破 50 才报警就太晚了。
        val r = StyleDrift.measure(listOf(95, 95, 95, 80, 80, 78))
        assertEquals(r.logLine(), StyleDrift.Trend.COLLAPSING, r.trend)
    }

    @Test
    fun `回升会判成在回升`() {
        val r = StyleDrift.measure(listOf(70, 74, 78, 82, 86, 90))
        assertEquals(r.logLine(), StyleDrift.Trend.IMPROVING, r.trend)
        assertTrue(r.slope > 0)
    }

    @Test
    fun `拐点能定位到掉下去的那一轮`() {
        val r = StyleDrift.measure(listOf(95, 94, 93, 92, 80, 78, 77, 96, 95, 94))
        assertEquals("第 6 轮附近是谷底：${r.logLine()}", 6, r.worstTurn ?: -1)
    }

    @Test
    fun `一路上平稳时没有拐点`() {
        val r = StyleDrift.measure(listOf(90, 91, 90, 90, 91, 90))
        assertNull("平稳不是拐点：${r.logLine()}", r.worstTurn)
    }

    @Test
    fun `斜率按观测次数算而不是轮次号`() {
        // 中间剔掉了空回复（不计分），所以连续两个观测点之间可能隔了很多轮。
        // 拿轮次号当 x 会让斜率被空洞的间距扭曲。
        val r = StyleDrift.measure(listOf(90, 89, 88, 87))
        assertEquals(-1.0, r.slope, 1e-6)
    }

    // ═══════════════════════════════════════════════════════════
    //  二、什么时候才插再锚定段
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `连续两条跌破塑料线才需要再锚定`() {
        assertTrue(StyleDrift.needsReanchor(listOf(88, 69, 65)))
        assertFalse("只有一条不够，避免每轮都塞纸条", StyleDrift.needsReanchor(listOf(88, 88, 65)))
        assertFalse(StyleDrift.needsReanchor(listOf(88, 70)))
        assertFalse(StyleDrift.needsReanchor(listOf(40)))
        assertFalse(StyleDrift.needsReanchor(emptyList()))
    }

    // ═══════════════════════════════════════════════════════════
    //  三、锚点选材
    // ═══════════════════════════════════════════════════════════

    /** 一份"什么都有一点"的历史：好样本、客服尾巴、念设定、问句、分点、过短、过长。 */
    private val mixedHistory = listOf(
        "嗯。",                                                     // 太短
        "嗯。那你先去睡吧。",                                        // ✅ 短
        "「随你。」（把杯子往你那边推了推）",                          // ✅ 中
        "好的，已经帮你记下了。还有什么需要吗？",                      // 客服尾巴
        "按我的设定，我不该这么说话。",                                // 念设定
        "你吃饭了吗？",                                              // 问句
        "我今天下午去了那家店看了看情况。感觉东西不太行。下次应该不会再去了。",  // ✅ 中（略工整但仍是人话）
        "**第一点**：早点睡\n**第二点**：多喝水",                       // 分点，扣到 76
    )

    @Test
    fun `混着坏样本也能挑出好锚点`() {
        val picks = StyleDrift.pickAnchors(mixedHistory, query = "今晚加班到十点")
        assertEquals("挑三条：${picks.map { it.text }}", 3, picks.size)
        for (p in picks) {
            assertFalse("客服尾巴不能当范例：${p.text}", p.text.contains("还有什么需要"))
            assertFalse("念设定的不能当范例：${p.text}", p.text.contains("设定"))
            assertFalse("问句会教她每轮追问：${p.text}", p.text.endsWith("？"))
            assertFalse("分点的不能当范例：${p.text}", p.text.contains("**"))
            assertTrue("锚点必须够长：${p.text}", p.text.length >= 4)
            assertTrue("锚点必须不高分不该入选：${p.score}", p.score >= StyleDrift.ANCHOR_MIN_SCORE)
        }
    }

    @Test
    fun `锚点保持时间顺序`() {
        // 让她看到"她一直是这么说话的"，而不是"她被告知过几条例句"。
        val picks = StyleDrift.pickAnchors(mixedHistory, query = "")
        val idx = picks.map { mixedHistory.indexOf(it.text) }
        assertEquals("应按历史顺序输出：$idx", idx.sorted(), idx)
    }

    @Test
    fun `形状优先于分数`() {
        // 三条同形状的高分样本 vs 一条短的高分样本。
        // 只给 limit=1 时该拿短的 —— 因为它和另外两条形状不同。
        val history = listOf(
            "我今天下午去了那家店看了看情况感觉东西不太行下次应该不会再去了啊",
            "我今天上午去了那家铺子瞧了瞧行情感觉价格不太行下回应该不会再去了啊",
            "嗯。那你去吧。",
        )
        val picks = StyleDrift.pickAnchors(history, query = "", limit = 2)
        assertTrue(
            "两条里必须有一条是短句：${picks.map { it.text }}",
            picks.any { it.text.length < 15 },
        )
    }

    @Test
    fun `近似重复的样本只取一条`() {
        val history = listOf(
            "行吧，那我先去睡了，你也早点睡。",
            "行吧，那我先去睡了，你也早点睡呀。",
        )
        val picks = StyleDrift.pickAnchors(history, query = "", limit = 2)
        assertEquals("近似重复的只该留一条：${picks.map { it.text }}", 1, picks.size)
    }

    @Test
    fun `和用户刚说的话高度重合的样本不选`() {
        // 否则她开始复读用户的原话 —— 那是另一种露馅。
        val history = listOf("我今天真的特别困想早点睡")
        val picks = StyleDrift.pickAnchors(history, query = "我今天真的特别困想早点睡")
        assertTrue("不该选和用户重合的：${picks.map { it.text }}", picks.isEmpty())
    }

    @Test
    fun `没有合格样本时返回空表而不是硬凑`() {
        val picks = StyleDrift.pickAnchors(listOf("嗯。", "好的，还有什么需要吗？"), query = "在吗")
        assertTrue("宁缺勿滥：${picks.map { it.text }}", picks.isEmpty())
    }

    @Test
    fun `空历史不炸`() {
        assertTrue(StyleDrift.pickAnchors(emptyList(), query = "在吗").isEmpty())
        assertTrue(StyleDrift.pickAnchors(listOf("嗯。那你先去睡吧。"), query = "", limit = 0).isEmpty())
    }

    @Test
    fun `锚点带形状描述方便人看日志`() {
        val picks = StyleDrift.pickAnchors(listOf("行吧。那我去了。"), query = "")
        assertEquals(1, picks.size)
        assertTrue("要写清为什么选它：${picks[0].why}", picks[0].why.contains("字"))
    }
}
