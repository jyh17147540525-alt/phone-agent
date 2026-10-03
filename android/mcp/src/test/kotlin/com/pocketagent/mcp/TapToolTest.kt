package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `android_tap`：关卡顺序、拒绝文案、参数校验、盲点、限流。
 *
 * ⚠️ 顺序在这里是**安全断言**：安全判定必须发生在派发之前 ——
 *    "测试看不到顺序"的借口不成立，用记录事件的 stub 直接断言调用序列
 *    （capture → safety → dispatch），并且断言被拒的路径上 dispatch 没被碰过。
 */
class TapToolTest {

    private val events = mutableListOf<String>()
    private var now = 1000L

    // ── fakes ──────────────────────────────────────────────────

    private class FakeReader(
        private val outcome: ScreenCaptureOutcome,
        private val events: MutableList<String>,
    ) : ScreenReaderPort {
        var count = 0
        override suspend fun capture(): ScreenCaptureOutcome {
            count += 1
            events += "capture"
            return outcome
        }
    }

    private class FakeSafety(private val events: MutableList<String>) : ActionSafetyPort {
        var outcome: ActionSafetyOutcome = ActionSafetyOutcome.Allowed
        val queries = mutableListOf<ActionSafetyQuery>()
        override suspend fun review(query: ActionSafetyQuery): ActionSafetyOutcome {
            events += "safety"
            queries += query
            return outcome
        }
    }

    private class FakeDispatch(private val events: MutableList<String>) : ActionDispatchPort {
        var outcome: DispatchOutcome = DispatchOutcome.Dispatched("ACCESSIBILITY", 12)
        val actions = mutableListOf<AgentAction>()
        override suspend fun dispatch(action: AgentAction): DispatchOutcome {
            events += "dispatch"
            actions += action
            return outcome
        }
    }

    private fun newLimiter() = ActionRateLimiter(clock = { now })

    private fun node(
        nodeId: String = "n1",
        text: String? = null,
        desc: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        bounds: ScreenRect = ScreenRect(10, 10, 300, 100),
        depth: Int = 1,
    ) = UiNodeDto(
        nodeId = nodeId, className = "android.widget.View", text = text,
        contentDescription = desc, hintText = null, bounds = bounds,
        clickable = clickable, longClickable = false, editable = false,
        scrollable = false, checkable = false, checked = false, enabled = enabled,
        focused = false, visible = visible, selected = false,
        viewIdResourceName = null, depth = depth, password = false,
    )

    private fun capture(vararg nodes: UiNodeDto): ScreenCaptureOutcome =
        ScreenCaptureOutcome.Ok(
            ScreenCaptureDto(
                packageName = "com.example.app", activityName = null,
                width = 1440, height = 3200, source = CaptureSource.ACCESSIBILITY,
                confidence = 0.7f, capturedAtMs = 1L, nodes = nodes.toList(),
            ),
        )

    private fun textTarget(text: String): JsonObject = buildJsonObject {
        putJsonObject("target") { put("kind", "text"); put("text", text) }
    }

    private fun coordTarget(x: Int, y: Int): JsonObject = buildJsonObject {
        putJsonObject("target") { put("kind", "coordinates"); put("x", x); put("y", y) }
    }

    private fun tool(
        reader: FakeReader,
        safety: FakeSafety,
        dispatch: FakeDispatch,
        limiter: ActionRateLimiter = newLimiter(),
    ) = TapTool(reader, safety, dispatch, limiter)

    // ── 成功路径 + 关卡顺序 ────────────────────────────────────

    @Test
    fun `成功路径走完捕获-判定-派发且 reason 转发`() = runTest {
        val reader = FakeReader(capture(node(text = "发送", clickable = true)), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        val outcome = tool.call(
            buildJsonObject {
                putJsonObject("target") { put("kind", "text"); put("text", "发送") }
                put("reason", "测试发送")
            },
        ) as ToolOutcome.Success

        assertTrue(outcome.text.contains("已派发"))
        assertTrue("成功文案必须提示用 screen_read 确认结果", outcome.text.contains("screen_read"))
        assertEquals("关卡顺序：先判定后派发", listOf("capture", "safety", "dispatch"), events)

        val action = dispatch.actions.single() as AgentAction.Tap
        assertEquals("reason 要转发", "测试发送", action.reason)
        assertEquals("n1", action.ref!!.nodeId)
        assertNull(action.point)

        val query = safety.queries.single()
        assertEquals("tap", query.actionType)
        assertTrue("目标描述要带上目标文本（危险动作匹配的输入）", query.targetDescription!!.contains("发送"))
        assertEquals("进入判定时尚未占用额度", 0, query.actionsInLastMinute)
    }

    // ── 敏感页 / 危险动作（一票否决）────────────────────────────

    @Test
    fun `敏感页命中直接拒绝且不派发`() = runTest {
        val reader = FakeReader(capture(node(text = "确认支付", clickable = true)), events)
        val safety = FakeSafety(events).apply {
            outcome = ActionSafetyOutcome.Blocked(
                "当前页面出现了支付、密码或验证码相关的内容，我不会继续操作。",
                canFallbackToManual = true,
            )
        }
        val dispatch = FakeDispatch(events)
        val limiter = newLimiter()
        val tool = tool(reader, safety, dispatch, limiter)

        val outcome = tool.call(textTarget("确认支付")) as ToolOutcome.Refused

        assertTrue(outcome.reason.contains("支付"))
        assertTrue("要告诉模型别重试", outcome.reason.contains("不要重试"))
        assertTrue("被拒路径不允许派发", dispatch.actions.isEmpty())
        assertEquals(listOf("capture", "safety"), events)
        assertEquals("被安全拒掉的调用不占频率额度", 0, limiter.countInLastMinute())
    }

    @Test
    fun `危险动作的确认类降级为拒绝且不回显目标文本`() = runTest {
        val reader = FakeReader(capture(node(text = "确认支付", clickable = true)), events)
        val safety = FakeSafety(events).apply {
            outcome = ActionSafetyOutcome.ConfirmationRequired("我准备点击「确认支付」，这个操作可能无法撤销。要继续吗？")
        }
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        val outcome = tool.call(textTarget("确认支付")) as ToolOutcome.Refused

        assertTrue("要引导用户手动完成", outcome.reason.contains("手动完成"))
        assertFalse("拒绝路径不允许带出屏幕文本", outcome.reason.contains("确认支付"))
        assertTrue(dispatch.actions.isEmpty())
    }

    // ── 目标解析失败先于安全判定 ────────────────────────────────

    @Test
    fun `目标解析失败时安全判定根本不会被调用`() = runTest {
        val reader = FakeReader(capture(), events)
        val safety = FakeSafety(events)
        val tool = tool(reader, safety, FakeDispatch(events))

        val outcome = tool.call(textTarget("不存在")) as ToolOutcome.Failed

        assertTrue(outcome.reason.contains("找不到"))
        assertEquals("解析失败就终止，不该再多走一步", listOf("capture"), events)
        assertTrue(safety.queries.isEmpty())
    }

    // ── 参数校验（全部要给"下一步"）─────────────────────────────

    @Test
    fun `参数非法一律Failed且不采集`() = runTest {
        val reader = FakeReader(capture(), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val missing = tool.call(buildJsonObject {}) as ToolOutcome.Failed
        assertTrue(missing.reason.contains("target"))

        val badKind = tool.call(buildJsonObject { putJsonObject("target") { put("kind", "swipe") } }) as ToolOutcome.Failed
        assertTrue(badKind.reason.contains("node_id"))

        val noBounds = tool.call(
            buildJsonObject { putJsonObject("target") { put("kind", "node_id"); put("nodeId", "n1") } },
        ) as ToolOutcome.Failed
        assertTrue("node_id 缺 bounds 要拒绝并解释为什么", noBounds.reason.contains("位置校验"))

        val badBounds = tool.call(
            buildJsonObject {
                putJsonObject("target") {
                    put("kind", "node_id"); put("nodeId", "n1")
                    putJsonArray("bounds") { add(100); add(0); add(10); add(10) }
                }
            },
        ) as ToolOutcome.Failed
        assertTrue(badBounds.reason.contains("left"))

        val badX = tool.call(
            buildJsonObject { putJsonObject("target") { put("kind", "coordinates"); put("x", "abc"); put("y", 1) } },
        ) as ToolOutcome.Failed
        assertTrue(badX.reason.contains("整数"))

        assertEquals("参数级失败不产生任何设备访问", 0, reader.count)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `坐标越界在解析阶段失败`() = runTest {
        val reader = FakeReader(capture(), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val outcome = tool.call(coordTarget(5000, 10)) as ToolOutcome.Failed

        assertTrue(outcome.reason.contains("超出屏幕"))
        assertEquals(listOf("capture"), events)
    }

    // ── 盲点 ────────────────────────────────────────────────────

    @Test
    fun `坐标空白处按盲点派发且没有目标描述`() = runTest {
        val reader = FakeReader(capture(), events) // 空树
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        val outcome = tool.call(coordTarget(700, 1200)) as ToolOutcome.Success

        assertTrue("成功文案要如实标注盲点", outcome.text.contains("盲点"))
        assertTrue("必须点明「不确定点到了什么」", outcome.text.contains("无法确认点到了什么"))
        assertTrue("必须要求模型对用户如实说明", outcome.text.contains("如实告诉用户"))
        val action = dispatch.actions.single() as AgentAction.Tap
        assertNull(action.ref)
        assertEquals(ScreenPoint(700, 1200), action.point)
        assertNull("盲点没有目标可描述（危险动作匹配按无目标处理）", safety.queries.single().targetDescription)
    }

    // ── 频率闸 ──────────────────────────────────────────────────

    @Test
    fun `频率闸拒绝且不派发`() = runTest {
        val reader = FakeReader(capture(node(text = "下一步", clickable = true)), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val limiter = ActionRateLimiter(maxActionsPerMinute = 2, minGapMs = 0, clock = { now })
        val tool = tool(reader, safety, dispatch, limiter)

        now += 1000
        assertTrue(tool.call(textTarget("下一步")) is ToolOutcome.Success)
        now += 1000
        assertTrue(tool.call(textTarget("下一步")) is ToolOutcome.Success)

        now += 1000
        val third = tool.call(textTarget("下一步")) as ToolOutcome.Refused
        assertTrue(third.reason.contains("上限"))
        assertTrue("要给出等待时间", third.reason.contains("秒后可继续"))
        assertEquals("第三次没有派发", 2, dispatch.actions.size)

        now += ActionRateLimiter.WINDOW_MS
        assertTrue("窗口滑出后恢复", tool.call(textTarget("下一步")) is ToolOutcome.Success)
    }

    // ── 通道结果映射 ────────────────────────────────────────────

    @Test
    fun `通道失败原样转达`() = runTest {
        val reader = FakeReader(capture(node(text = "发送", clickable = true)), events)
        val dispatch = FakeDispatch(events).apply {
            outcome = DispatchOutcome.Failed("执行通道不可用（无障碍服务未连接）。请开启无障碍后重试。")
        }
        val tool = tool(reader, FakeSafety(events), dispatch)

        val outcome = tool.call(textTarget("发送")) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("无障碍"))
    }

    @Test
    fun `通道要求人工介入时走拒绝`() = runTest {
        val reader = FakeReader(capture(node(text = "发送", clickable = true)), events)
        val dispatch = FakeDispatch(events).apply {
            outcome = DispatchOutcome.Refused("执行通道要求人工介入：请把操作步骤交给用户完成。")
        }
        val tool = tool(reader, FakeSafety(events), dispatch)

        val outcome = tool.call(textTarget("发送"))
        assertTrue(outcome is ToolOutcome.Refused)
    }

    // ── 工具声明 ────────────────────────────────────────────────

    @Test
    fun `工具声明符合命名约定`() {
        val tool = tool(FakeReader(capture(), events), FakeSafety(events), FakeDispatch(events))
        assertEquals("android_tap", tool.name)
        assertTrue(tool.name.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("object", tool.inputSchema()["type"]!!.jsonPrimitive.content)
    }
}
