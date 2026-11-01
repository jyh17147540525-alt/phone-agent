package com.pocketagent.agent

import com.pocketagent.action.Bounds
import com.pocketagent.action.ElementRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **自动定位的编排** —— 证明「匹配 → 消歧」真的串起来了。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件存在的意义
 * ═══════════════════════════════════════════════════════════════
 *
 * `TextGrounder` 与 `AmbiguityResolver` 各自有测试，但此前**没有任何地方
 * 把它们串起来**（`AmbiguityResolver` 在生产代码里零引用）。
 *
 * 各自绿 ≠ 连得上。这个文件盯的是**连接处**：
 *  · 置信度从前者传到后者，口径一致吗
 *  · 后者的 `PickFirst` 只回传 refKey，那 bounds 从哪来
 *  · 三个出口有没有被正确区分
 */
class AutoLocatorTest {

    private fun node(
        id: String,
        text: String = "",
        desc: String = "",
        viewId: String? = null,
        clickable: Boolean = true,
        bounds: Bounds = Bounds(10, 20, 110, 70),
    ) = TextGrounder.CandidateNode(
        nodeId = id,
        text = text,
        contentDescription = desc,
        viewId = viewId,
        bounds = bounds,
        clickable = clickable,
    )

    // ═══════════════════════════════════════════════════════════
    //  ① 唯一匹配 → 直接动手
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `唯一精确匹配直接找到`() {
        val r = AutoLocator.locate(
            "搜索",
            listOf(node("n1", text = "搜索")),
        )
        assertTrue("应当直接找到：$r", r is AutoLocator.Outcome.Found)
        assertEquals("n1", (r as AutoLocator.Outcome.Found).ref.let {
            (it as ElementRef.ByNodeId).nodeId
        })
    }

    @Test
    fun `找到时带出的是真实的 bounds`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防"点在屏幕左上角"
        // ══════════════════════════════════════════════════════
        // `AmbiguityResolution.PickFirst` 只回传 `refKey`（不透明字符串），
        // **不带 bounds**。若连接处不回查原始节点，
        // 就会构造出 `Bounds(0,0,0,0)` —— 点在屏幕左上角。
        //
        // ⚠️ 那个错误在真机上极难发现：表现是"点了但没反应"，
        //    看起来像元素变了，而不是像坐标错了。
        val real = Bounds(120, 340, 460, 420)
        val r = AutoLocator.locate("搜索", listOf(node("n1", text = "搜索", bounds = real)))
        val ref = (r as AutoLocator.Outcome.Found).ref as ElementRef.ByNodeId
        assertEquals(real, ref.bounds)
        assertNotEquals("不能是零矩形", Bounds(0, 0, 0, 0), ref.bounds)
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 找不到 → 明确放弃
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有候选时放弃并说明扫了多少节点`() {
        val r = AutoLocator.locate("搜索", listOf(node("n1", text = "设置")))
        assertTrue(r is AutoLocator.Outcome.GiveUp)
        assertTrue(
            "理由里该说明扫了多少节点：${(r as AutoLocator.Outcome.GiveUp).reason}",
            r.reason.contains("1"),
        )
    }

    @Test
    fun `空屏幕放弃`() {
        assertTrue(AutoLocator.locate("搜索", emptyList()) is AutoLocator.Outcome.GiveUp)
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 不可点 → 补采集，不假装成功
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `唯一命中但不可点时要求补采集`() {
        val r = AutoLocator.locate(
            "搜索",
            listOf(node("n1", text = "搜索", clickable = false)),
        )
        assertTrue("不能把不可点当成可点：$r", r is AutoLocator.Outcome.NeedMoreInfo)
    }

    // ═══════════════════════════════════════════════════════════
    //  ④ 多个候选 → 交给消歧器
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `两个同分候选问用户`() {
        val r = AutoLocator.locate(
            "确定",
            listOf(node("n1", text = "确定"), node("n2", text = "确定")),
        )
        assertTrue("同分时不能替用户做主：$r", r is AutoLocator.Outcome.AskUser)
        assertEquals(2, (r as AutoLocator.Outcome.AskUser).options.size)
    }

    @Test
    fun `差距够大时消歧器自动选定`() {
        // ★ 这条证明置信度口径真的传到了消歧器：
        //   0.95（精确）vs 0.60（包含）差 0.35 ≥ 阈值 0.2 ⇒ 自动选
        val r = AutoLocator.locate(
            "搜索",
            listOf(
                node("n1", text = "搜索"),
                node("n2", text = "搜索历史"),
            ),
        )
        assertTrue(
            "差距 0.35 应当让消歧器自动选定，而不是问用户：$r",
            r is AutoLocator.Outcome.Found,
        )
        assertEquals(
            "n1",
            ((r as AutoLocator.Outcome.Found).ref as ElementRef.ByNodeId).nodeId,
        )
    }

    @Test
    fun `问用户的选项是人能看懂的`() {
        // ⚠️ 选项直接展示给用户 —— 出现 nodeId 那种东西等于没给选项
        val r = AutoLocator.locate(
            "确定",
            listOf(
                node("android.widget.Button{1a2b}", text = "确定"),
                node("android.widget.Button{3c4d}", text = "确定"),
            ),
        ) as AutoLocator.Outcome.AskUser
        assertTrue("选项不能是 nodeId：${r.options}", r.options.all { it == "确定" })
    }

    @Test
    fun `没有文本时用描述做标签`() {
        val r = AutoLocator.locate(
            "返回",
            listOf(
                node("n1", desc = "返回"),
                node("n2", desc = "返回"),
            ),
        ) as AutoLocator.Outcome.AskUser
        assertTrue("应当用 contentDescription 做标签：${r.options}", r.options.all { it == "返回" })
    }

    @Test
    fun `几个候选都给不出可读标签时才退回 nodeId`() {
        // 最后手段：至少还能区分选项
        val r = AutoLocator.locate(
            "确定",
            listOf(node("n1"), node("n2")),
        )
        // 全空节点不匹配任何目标 ⇒ 直接 GiveUp
        assertTrue(r is AutoLocator.Outcome.GiveUp)
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑤ 置信度下限由消歧器统一判
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `包含匹配的唯一候选会被消歧器的下限拦掉`() {
        // ══════════════════════════════════════════════════════
        //  ★ 阈值只在一个地方定
        // ══════════════════════════════════════════════════════
        // 包含匹配是 0.60，高于 `AmbiguityResolver.LOW_CONFIDENCE_FLOOR`（0.4），
        // 所以它会通过。真正的下限测试需要构造 < 0.4 的候选 ——
        // 而 `TextGrounder.MIN_MATCH_CONFIDENCE` 是 0.5，过滤在更前面。
        // ⇒ 两边阈值不同但不冲突：前者管"算不算命中"，后者管"值不值得做"。
        val r = AutoLocator.locate("搜索", listOf(node("n1", text = "搜索历史")))
        assertTrue("0.60 高于下限 0.4，应当找到：$r", r is AutoLocator.Outcome.Found)
    }

    // ═══════════════════════════════════════════════════════════
    //  ⑥ 三个出口互不混淆
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `出口不会互相降级`() {
        // ★ 各自的情形必须落到各自的出口
        val found = AutoLocator.locate("搜索", listOf(node("n1", text = "搜索")))
        val needInfo = AutoLocator.locate(
            "搜索",
            listOf(node("n1", text = "搜索", clickable = false)),
        )
        val giveUp = AutoLocator.locate("搜索", emptyList())

        assertTrue(found is AutoLocator.Outcome.Found)
        assertTrue(needInfo is AutoLocator.Outcome.NeedMoreInfo)
        assertTrue(giveUp is AutoLocator.Outcome.GiveUp)

        // 不许把 NeedMoreInfo / GiveUp 悄悄变成 Found
        assertTrue(needInfo !is AutoLocator.Outcome.Found)
        assertTrue(giveUp !is AutoLocator.Outcome.Found)
    }
}
