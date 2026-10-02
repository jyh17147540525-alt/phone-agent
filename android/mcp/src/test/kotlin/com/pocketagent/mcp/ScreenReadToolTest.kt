package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `screen_read`：关卡顺序、输入派生、拒绝文案、遮蔽执行。
 *
 * ⚠️ 顺序在这里是**安全断言**：敏感判定必须先于序列化 ——
 *    测试看不到"顺序"，但对**结果**的断言能把顺序错位的形态钉死
 *    （比如"敏感命中时仍产生了文本"这种组合会被下面的用例抓住）。
 */
class ScreenReadToolTest {

    // ── fakes ──────────────────────────────────────────────────

    private class FakeReader(private val outcome: ScreenCaptureOutcome) : ScreenReaderPort {
        var captureCount = 0
        override suspend fun capture(): ScreenCaptureOutcome {
            captureCount += 1
            return outcome
        }
    }

    private class FakeSafety(private val outcome: ScreenSafetyOutcome) : ScreenSafetyPort {
        var lastQuery: ScreenSafetyQuery? = null
        override fun review(query: ScreenSafetyQuery): ScreenSafetyOutcome {
            lastQuery = query
            return outcome
        }
    }

    private fun node(
        text: String? = null,
        editable: Boolean = false,
        visible: Boolean = true,
        password: Boolean = false,
        bounds: ScreenRect = ScreenRect(100, 50, 340, 90),
    ) = UiNodeDto(
        nodeId = "n", className = "android.widget.EditText", text = text,
        contentDescription = null, hintText = null, bounds = bounds,
        clickable = false, longClickable = false, editable = editable,
        scrollable = false, checkable = false, checked = false, enabled = true,
        focused = false, visible = visible, selected = false,
        viewIdResourceName = null, depth = 1, password = password,
    )

    private fun capture(vararg nodes: UiNodeDto): ScreenCaptureOutcome =
        ScreenCaptureOutcome.Ok(
            ScreenCaptureDto(
                packageName = "com.example.app", activityName = null,
                width = 1440, height = 3200,
                source = CaptureSource.ACCESSIBILITY, confidence = 0.7f,
                capturedAtMs = 1L, nodes = nodes.toList(),
            ),
        )

    // ── 正常路径 ───────────────────────────────────────────────

    @Test
    fun `正常读取返回文本与结构化输出且输入框被遮蔽`() = runTest {
        val reader = FakeReader(capture(node(text = "发送"), node(text = "草稿", editable = true)))
        val tool = ScreenReadTool(reader, FakeSafety(ScreenSafetyOutcome.Pass))

        val outcome = tool.call(buildJsonObject { }) as ToolOutcome.Success

        assertTrue("普通文本照常进入输出", outcome.text.contains("发送"))
        assertTrue("输入框内容必须被遮蔽（隐私关卡的计划被执行）", outcome.text.contains("[已遮蔽]"))
        assertFalse("原始输入内容不许出现", outcome.text.contains("草稿"))
        assertNotNull(outcome.structured)
        assertEquals(1, reader.captureCount)
    }

    // ── 敏感判定：输入派生 ─────────────────────────────────────

    @Test
    fun `判定输入只包含可见文本与控件形状且带回 password 标记`() = runTest {
        val reader = FakeReader(
            capture(
                node(text = "发送", visible = true),
                node(text = "支付密码", visible = false), // 不可见 → 不参与判定
                node(text = "草稿一", editable = true, password = false),
                node(text = "草稿二", editable = true, password = true),
            ),
        )
        val safety = FakeSafety(ScreenSafetyOutcome.Pass)
        val tool = ScreenReadTool(reader, safety)

        tool.call(buildJsonObject { })

        val query = safety.lastQuery!!
        assertTrue("可见文本要进判定", query.visibleTexts.contains("发送"))
        assertFalse("不可见节点的文本不参与判定", query.visibleTexts.contains("支付密码"))
        assertEquals(2, query.fields.size)
        assertEquals(1, query.fields.count { it.isPassword })
    }

    // ── 敏感判定：一票否决 ─────────────────────────────────────

    @Test
    fun `敏感命中直接拒绝且不再产生任何文本`() = runTest {
        val reader = FakeReader(capture(node(text = "确认支付")))
        val safety = FakeSafety(ScreenSafetyOutcome.Block("当前页面出现了支付相关的内容，我不会继续操作。"))
        val tool = ScreenReadTool(reader, safety)

        val outcome = tool.call(buildJsonObject { })

        assertTrue(outcome is ToolOutcome.Refused)
        val refused = outcome as ToolOutcome.Refused
        assertTrue(refused.reason.contains("支付相关"))
        assertTrue("要告诉模型别重试", refused.reason.contains("不要重试"))
        assertFalse("拒绝路径里不允许带出屏幕文本", refused.reason.contains("确认支付"))
    }

    // ── 不可用 / 失败：如实转达 ────────────────────────────────

    @Test
    fun `无障碍不可用时给出可执行的下一步`() = runTest {
        val tool = ScreenReadTool(
            FakeReader(ScreenCaptureOutcome.Unavailable("无障碍服务未连接")),
            FakeSafety(ScreenSafetyOutcome.Pass),
        )
        val outcome = tool.call(buildJsonObject { }) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("无障碍服务未连接"))
        assertTrue("必须告诉用户去哪儿开", outcome.reason.contains("辅助功能"))
    }

    @Test
    fun `读取失败的原因原样转达`() = runTest {
        val tool = ScreenReadTool(
            FakeReader(ScreenCaptureOutcome.Failed("该界面不提供无障碍信息")),
            FakeSafety(ScreenSafetyOutcome.Pass),
        )
        val outcome = tool.call(buildJsonObject { }) as ToolOutcome.Failed
        assertEquals("该界面不提供无障碍信息", outcome.reason)
    }

    // ── 畸形数据不炸 ───────────────────────────────────────────

    @Test
    fun `几何畸形的输入框不炸整次调用`() = runTest {
        // 无障碍树坐标是不可信数据：right < left 的矩形进不了遮蔽计划（被派生层过滤），
        // 但**不能**让它把整次工具调用炸掉。
        val weird = node(text = "x", editable = true, bounds = ScreenRect(340, 50, 100, 90))
        val tool = ScreenReadTool(FakeReader(capture(weird)), FakeSafety(ScreenSafetyOutcome.Pass))
        val outcome = tool.call(buildJsonObject { })
        assertTrue(outcome is ToolOutcome.Success)
    }

    // ── 工具声明 ───────────────────────────────────────────────

    @Test
    fun `工具声明符合命名约定`() {
        val tool = ScreenReadTool(FakeReader(capture()), FakeSafety(ScreenSafetyOutcome.Pass))
        assertEquals("screen_read", tool.name)
        assertTrue(tool.name.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("object", tool.inputSchema()["type"]!!.jsonPrimitive.content)
    }
}
