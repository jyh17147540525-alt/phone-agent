package com.pocketagent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标解析：三种定位、可点击祖先上浮、跨快照位置校验、畸形数据不炸。
 *
 * 这些全是纯函数判定 —— "解析错了会点到别的东西上"是静默事故，
 * 只有确定性测试能钉住。
 */
class TargetResolverTest {

    private fun node(
        nodeId: String,
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        depth: Int = 0,
        bounds: ScreenRect,
    ) = UiNodeDto(
        nodeId = nodeId, className = "android.widget.View", text = text,
        contentDescription = desc, hintText = null, bounds = bounds,
        clickable = clickable, longClickable = false, editable = false,
        scrollable = false, checkable = false, checked = false, enabled = enabled,
        focused = false, visible = visible, selected = false,
        viewIdResourceName = null, depth = depth, password = false,
    )

    private fun capture(vararg nodes: UiNodeDto) = ScreenCaptureDto(
        packageName = "com.example.app", activityName = null,
        width = 1000, height = 2000, source = CaptureSource.ACCESSIBILITY,
        confidence = 0.7f, capturedAtMs = 1L, nodes = nodes.toList(),
    )

    private fun ok(outcome: ResolveOutcome): ResolvedTarget =
        (outcome as ResolveOutcome.Ok).target

    private fun failed(outcome: ResolveOutcome): ResolveOutcome.Failed =
        outcome as ResolveOutcome.Failed

    // ── 坐标 ────────────────────────────────────────────────────

    @Test
    fun `坐标命中最小面积节点并上浮到可点击祖先`() {
        val outer = node("outer", clickable = true, depth = 0, bounds = ScreenRect(0, 0, 900, 1200))
        val inner = node("inner", text = "确认", depth = 1, bounds = ScreenRect(100, 100, 300, 200))

        val target = ok(TargetResolver.resolve(TapTargetSpec.ByCoord(150, 150), capture(outer, inner)))

        assertEquals("语义节点是最小的那个", "inner", target.candidates.first().nodeId)
        assertEquals("点击节点上浮到可点击祖先", "outer", target.clickNode!!.nodeId)
    }

    @Test
    fun `坐标越界直接失败且给出屏幕尺寸`() {
        val outcome = failed(TargetResolver.resolve(TapTargetSpec.ByCoord(2000, 10), capture()))
        assertTrue(outcome.reason.contains("超出屏幕范围"))
        assertTrue(outcome.reason.contains("1000"))
    }

    @Test
    fun `坐标空白处是盲点`() {
        val target = ok(TargetResolver.resolve(TapTargetSpec.ByCoord(500, 500), capture()))
        assertNull(target.clickNode)
        assertEquals(ScreenPoint(500, 500), target.blindPoint)
        assertTrue(target.candidates.isEmpty())
    }

    @Test
    fun `畸形矩形被忽略且不炸`() {
        val weird = node("w", text = "x", bounds = ScreenRect(300, 300, 100, 100))
        val target = ok(TargetResolver.resolve(TapTargetSpec.ByCoord(150, 150), capture(weird)))
        assertNull("right < left 的矩形不参与包含判定", target.clickNode)
        assertNotNull("结果退化为盲点而不是异常", target.blindPoint)
    }

    // ── 文本 ────────────────────────────────────────────────────

    @Test
    fun `文本模糊匹配找到唯一可点击节点`() {
        val button = node("btn", text = "发送消息", clickable = true, bounds = ScreenRect(10, 10, 200, 80))
        val target = ok(TargetResolver.resolve(TapTargetSpec.ByText("发送", exact = false), capture(button)))
        assertEquals("btn", target.clickNode!!.nodeId)
    }

    @Test
    fun `文本精确匹配与包含匹配的差别`() {
        val button = node("btn", text = "发送按钮", clickable = true, bounds = ScreenRect(10, 10, 200, 80))
        val cap = capture(button)
        assertTrue("精确匹配「发送」不该命中「发送按钮」", TargetResolver.resolve(TapTargetSpec.ByText("发送", exact = true), cap) is ResolveOutcome.Failed)
        assertTrue("包含匹配应该命中", TargetResolver.resolve(TapTargetSpec.ByText("发送", exact = false), cap) is ResolveOutcome.Ok)
    }

    @Test
    fun `文本命中多个可点击节点时拒绝并提示换定位方式`() {
        val a = node("a", text = "保存", clickable = true, bounds = ScreenRect(0, 0, 100, 100))
        val b = node("b", text = "保存", clickable = true, bounds = ScreenRect(200, 0, 300, 100))
        val outcome = failed(TargetResolver.resolve(TapTargetSpec.ByText("保存", exact = false), capture(a, b)))
        assertTrue(outcome.reason.contains("无法确定"))
        assertTrue("要给出下一步", outcome.reason.contains("node_id"))
    }

    @Test
    fun `文本命中一个可点击按钮和它内部文本节点时选中按钮`() {
        val button = node("btn", text = "发送", clickable = true, depth = 1, bounds = ScreenRect(0, 0, 200, 100))
        val label = node("lbl", text = "发送", clickable = false, depth = 2, bounds = ScreenRect(10, 10, 100, 60))
        val target = ok(TargetResolver.resolve(TapTargetSpec.ByText("发送", exact = false), capture(button, label)))
        assertEquals("btn", target.clickNode!!.nodeId)
    }

    @Test
    fun `文本目标唯一但不可点击时照常解析并上浮`() {
        val button = node("btn", clickable = true, depth = 1, bounds = ScreenRect(0, 0, 400, 200))
        val label = node("lbl", text = "确认", clickable = false, depth = 2, bounds = ScreenRect(50, 50, 150, 120))
        val target = ok(TargetResolver.resolve(TapTargetSpec.ByText("确认", exact = false), capture(button, label)))
        assertEquals("lbl", target.candidates.first().nodeId)
        assertEquals("btn", target.clickNode!!.nodeId)
    }

    @Test
    fun `文本找不到给出重新读屏的下一步`() {
        val outcome = failed(TargetResolver.resolve(TapTargetSpec.ByText("不存在的东西", exact = false), capture()))
        assertTrue(outcome.reason.contains("找不到"))
        assertTrue(outcome.reason.contains("screen_read"))
    }

    // ── node_id（跨快照位置校验）────────────────────────────────

    @Test
    fun `node_id 需要位置完全一致`() {
        val n = node("n7", text = "确定", clickable = true, bounds = ScreenRect(10, 20, 110, 60))
        val cap = capture(n)
        assertTrue(TargetResolver.resolve(TapTargetSpec.ByNodeId("n7", ScreenRect(10, 20, 110, 60)), cap) is ResolveOutcome.Ok)

        val stale = failed(TargetResolver.resolve(TapTargetSpec.ByNodeId("n7", ScreenRect(10, 21, 110, 60)), cap))
        assertTrue("位置不一致要报界面可能已变化", stale.reason.contains("位置"))
        assertTrue(stale.reason.contains("screen_read"))
    }

    @Test
    fun `node_id 不存在给出提示`() {
        val outcome = failed(TargetResolver.resolve(TapTargetSpec.ByNodeId("nope", ScreenRect(0, 0, 10, 10)), capture()))
        assertTrue(outcome.reason.contains("不存在"))
    }

    @Test
    fun `node_id 命中但不可见时失败`() {
        val n = node("n1", visible = false, bounds = ScreenRect(0, 0, 100, 100))
        val outcome = failed(TargetResolver.resolve(TapTargetSpec.ByNodeId("n1", ScreenRect(0, 0, 100, 100)), capture(n)))
        assertTrue(outcome.reason.contains("不可见"))
    }

    // ── 可用性 ──────────────────────────────────────────────────

    @Test
    fun `禁用目标被拒绝`() {
        val button = node("btn", text = "提交", clickable = true, enabled = false, bounds = ScreenRect(0, 0, 200, 100))
        val outcome = failed(TargetResolver.resolve(TapTargetSpec.ByText("提交", exact = false), capture(button)))
        assertTrue(outcome.reason.contains("不可用"))
    }
}
