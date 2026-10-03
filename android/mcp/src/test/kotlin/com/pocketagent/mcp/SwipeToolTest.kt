package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `android_swipe`：几何校验、安全关卡、顺序。
 */
class SwipeToolTest {

    private val events = mutableListOf<String>()
    private var now = 1000L

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
        var outcome: DispatchOutcome = DispatchOutcome.Dispatched("ACCESSIBILITY", 20)
        val actions = mutableListOf<AgentAction>()
        override suspend fun dispatch(action: AgentAction): DispatchOutcome {
            events += "dispatch"
            actions += action
            return outcome
        }
    }

    private fun capture(): ScreenCaptureOutcome = ScreenCaptureOutcome.Ok(
        ScreenCaptureDto(
            packageName = "com.example.app", activityName = null,
            width = 1440, height = 3200, source = CaptureSource.ACCESSIBILITY,
            confidence = 0.7f, capturedAtMs = 1L, nodes = emptyList(),
        ),
    )

    private fun args(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int? = null): JsonObject =
        buildJsonObject {
            putJsonObject("from") { put("x", fromX); put("y", fromY) }
            putJsonObject("to") { put("x", toX); put("y", toY) }
            durationMs?.let { put("durationMs", it) }
        }

    private fun tool(
        reader: FakeReader,
        safety: FakeSafety,
        dispatch: FakeDispatch,
    ) = SwipeTool(reader, safety, dispatch, ActionRateLimiter(clock = { now }))

    @Test
    fun `成功路径：默认时长与顺序`() = runTest {
        val reader = FakeReader(capture(), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        val outcome = tool.call(args(700, 2000, 700, 800)) as ToolOutcome.Success

        assertTrue(outcome.text.contains("已派发"))
        assertTrue("成功文案要提示确认", outcome.text.contains("screen_read"))
        assertEquals(listOf("capture", "safety", "dispatch"), events)

        val action = dispatch.actions.single() as AgentAction.Swipe
        assertEquals(ScreenPoint(700, 2000), action.from)
        assertEquals(ScreenPoint(700, 800), action.to)
        assertEquals("默认时长 300ms", 300L, action.durationMs)
        assertEquals("swipe", safety.queries.single().actionType)
    }

    @Test
    fun `滑动距离太短被拒`() = runTest {
        val reader = FakeReader(capture(), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val outcome = tool.call(args(100, 100, 110, 105)) as ToolOutcome.Failed

        assertTrue(outcome.reason.contains("太短"))
        assertTrue("要给出替代方案", outcome.reason.contains("android_tap"))
        assertEquals(listOf("capture"), events)
    }

    @Test
    fun `坐标越界被拒`() = runTest {
        val reader = FakeReader(capture(), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val outcome = tool.call(args(100, 100, 100, 99999)) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("超出屏幕"))
    }

    @Test
    fun `durationMs 越界在参数阶段被拒且不采集`() = runTest {
        val reader = FakeReader(capture(), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val outcome = tool.call(args(100, 100, 100, 800, durationMs = 50)) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("100–2000"))
        assertEquals("参数失败不该碰设备", 0, reader.count)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `敏感页命中直接拒绝且不派发`() = runTest {
        val reader = FakeReader(capture(), events)
        val safety = FakeSafety(events).apply {
            outcome = ActionSafetyOutcome.Blocked("当前页面出现了支付相关的内容，我不会继续操作。", canFallbackToManual = true)
        }
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        val outcome = tool.call(args(700, 2000, 700, 800)) as ToolOutcome.Refused

        assertTrue(outcome.reason.contains("不要重试"))
        assertTrue(dispatch.actions.isEmpty())
        assertEquals(listOf("capture", "safety"), events)
    }

    @Test
    fun `工具声明符合命名约定`() {
        val tool = tool(FakeReader(capture(), events), FakeSafety(events), FakeDispatch(events))
        assertEquals("android_swipe", tool.name)
        assertTrue(tool.name.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals("object", tool.inputSchema()["type"]!!.jsonPrimitive.content)
    }
}
