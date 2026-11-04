package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `android_batch`：一次调用连做几步。
 *
 * ⚠️ 这里钉的四件事，都是"做错了会安静地害人"的那类：
 *  1. **一次采集**（不是每步一次）—— 否则它就退化成"把 N 次往返挪到手机侧"，没省到该省的；
 *  2. **每步都过安全判定**（不是整批一次）—— 否则批中间塞一次危险点击就是绕过手段；
 *  3. **任一步失败立即停**，且文案必须说清"前面做过的不会回滚"；
 *  4. **参数错误一个字节都不发**。
 */
class BatchToolTest {

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
        var outcome: DispatchOutcome = DispatchOutcome.Dispatched("ACCESSIBILITY", 8)
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
        clickable: Boolean = true,
        bounds: ScreenRect = ScreenRect(10, 10, 300, 100),
    ) = UiNodeDto(
        nodeId = nodeId, className = "android.widget.View", text = text,
        contentDescription = null, hintText = null, bounds = bounds,
        clickable = clickable, longClickable = false, editable = false,
        scrollable = false, checkable = false, checked = false, enabled = true,
        focused = false, visible = true, selected = false,
        viewIdResourceName = null, depth = 1, password = false,
    )

    private fun capture(vararg nodes: UiNodeDto): ScreenCaptureOutcome =
        ScreenCaptureOutcome.Ok(
            ScreenCaptureDto(
                packageName = "com.example.app", activityName = null,
                width = 1440, height = 3200, source = CaptureSource.ACCESSIBILITY,
                confidence = 0.7f, capturedAtMs = 1L, nodes = nodes.toList(),
            ),
        )

    private fun tool(
        reader: FakeReader,
        safety: FakeSafety,
        dispatch: FakeDispatch,
        limiter: ActionRateLimiter = newLimiter(),
    ) = BatchTool(reader, safety, dispatch, limiter)

    private fun steps(vararg step: JsonObject): JsonObject = buildJsonObject {
        putJsonArray("steps") { step.forEach { add(it) } }
    }

    private fun tapAt(x: Int, y: Int): JsonObject = buildJsonObject {
        put("op", "tap"); put("x", x); put("y", y)
    }

    private fun tapText(text: String): JsonObject = buildJsonObject {
        put("op", "tap"); put("text", text)
    }

    private fun key(name: String): JsonObject = buildJsonObject {
        put("op", "key"); put("key", name)
    }

    // ── 成功路径 ───────────────────────────────────────────────

    @Test
    fun `三步按顺序派发且只采集一次`() = runTest {
        val reader = FakeReader(capture(node(text = "发送")), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        val outcome = tool.call(
            steps(tapAt(100, 200), tapText("发送"), key("back")),
        ) as ToolOutcome.Success

        assertEquals("整批只读一次屏", 1, reader.count)
        assertEquals(3, dispatch.actions.size)
        assertTrue(outcome.text.contains("已按顺序派发 3 步"))
        assertTrue("必须提示用 screen_read 核对", outcome.text.contains("screen_read"))

        assertTrue(dispatch.actions[0] is AgentAction.Tap)
        assertEquals(ScreenPoint(100, 200), (dispatch.actions[0] as AgentAction.Tap).point)
        assertEquals("按文字解析要落到那个节点上", "n1", (dispatch.actions[1] as AgentAction.Tap).ref!!.nodeId)
        assertEquals(GlobalKeyName.BACK, (dispatch.actions[2] as AgentAction.GlobalKey).key)
    }

    @Test
    fun `每步都过安全判定而不是整批一次`() = runTest {
        val reader = FakeReader(capture(node(text = "发送")), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        tool.call(steps(tapAt(1, 1), tapAt(2, 2), tapAt(3, 3)))

        assertEquals("三步要过三次判定", 3, safety.queries.size)
        assertEquals("动作类型要如实带上", listOf("tap", "tap", "tap"), safety.queries.map { it.actionType })
    }

    @Test
    fun `滑动与输入都能进批`() = runTest {
        val reader = FakeReader(capture(node(text = "搜索框")), events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, FakeSafety(events), dispatch)

        val swipe = buildJsonObject {
            put("op", "swipe"); put("fromX", 500); put("fromY", 1500)
            put("toX", 500); put("toY", 300); put("durationMs", 250)
        }
        val type = buildJsonObject {
            put("op", "type"); put("text", "你好"); put("text", "你好")
        }
        tool.call(steps(swipe, type))

        val s = dispatch.actions[0] as AgentAction.Swipe
        assertEquals(ScreenPoint(500, 1500), s.from)
        assertEquals(ScreenPoint(500, 300), s.to)
        assertEquals(250L, s.durationMs)
        assertEquals("你好", (dispatch.actions[1] as AgentAction.TypeText).text)
    }

    // ── 停下来 ─────────────────────────────────────────────────

    @Test
    fun `安全判定拦下就停且不派发后续步骤`() = runTest {
        val reader = FakeReader(capture(node(text = "确认支付")), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        safety.outcome = ActionSafetyOutcome.Blocked("这是支付页面", canFallbackToManual = true)

        val outcome = tool.call(steps(tapAt(1, 1), tapAt(2, 2))) as ToolOutcome.Refused

        assertTrue(outcome.reason.contains("支付"))
        assertTrue("要说明前面没做过任何一步", outcome.reason.contains("没有任何一步") || outcome.reason.contains("没有执行任何一步"))
        assertTrue("一个动作都不该派发", dispatch.actions.isEmpty())
    }

    @Test
    fun `中途失败时文案必须说清前面的不回滚`() = runTest {
        val reader = FakeReader(capture(node(text = "发送")), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, safety, dispatch)

        // 第 1 步成功，第 2 步失败
        var calls = 0
        val flaky = object : ActionDispatchPort {
            override suspend fun dispatch(action: AgentAction): DispatchOutcome {
                calls += 1
                return if (calls == 1) DispatchOutcome.Dispatched("ACCESSIBILITY", 5)
                else DispatchOutcome.Failed("目标失效")
            }
        }
        val outcome = BatchTool(reader, safety, flaky, newLimiter())
            .call(steps(tapAt(1, 1), tapAt(2, 2), tapAt(3, 3)))

        val failed = outcome as ToolOutcome.Failed
        assertTrue("要说清停在第几步：${failed.reason}", failed.reason.contains("第 2 步"))
        assertTrue("要说清已经做过的不回滚：${failed.reason}", failed.reason.contains("不会回滚"))
        assertTrue("要请模型先看屏幕：${failed.reason}", failed.reason.contains("screen_read"))
        assertEquals("停止后不该有第三次派发", 2, calls)
    }

    @Test
    fun `第一步就失败时说界面没被改动`() = runTest {
        val reader = FakeReader(capture(node(text = "发送")), events)
        val dispatch = FakeDispatch(events)
        dispatch.outcome = DispatchOutcome.Failed("通道坏了")
        val tool = tool(reader, FakeSafety(events), dispatch)

        val outcome = tool.call(steps(tapAt(1, 1))) as ToolOutcome.Failed

        assertTrue("要说清没有动过界面：${outcome.reason}", outcome.reason.contains("没有"))
        assertTrue(outcome.reason.contains("通道坏了"))
    }

    @Test
    fun `频率闸与单步工具共用同一份额度`() = runTest {
        val reader = FakeReader(capture(node(text = "发送")), events)
        val limiter = ActionRateLimiter(clock = { now })
        // 把额度用满：每次只推进 minGap（250ms），保证 21 次仍落在同一个 60s 窗口里。
        // ⚠️ 若推进 5s，滚动窗口会把旧记录挤掉 → 永远 Allowed → 死循环。
        var acquired = 0
        while (limiter.tryAcquire() is ActionRateLimiter.Decision.Allowed) {
            acquired += 1
            now += 250L
            check(acquired < 40) { "限流没有生效，预热循环没有收敛" }
        }
        // 额度耗尽后再推进一点，确保撞的是"次数上限"而不是"防连点"
        now += 250L

        val dispatch = FakeDispatch(events)
        val tool = tool(reader, FakeSafety(events), dispatch, limiter)
        val outcome = tool.call(steps(tapAt(1, 1))) as ToolOutcome.Refused

        assertTrue(outcome.reason.contains("暂停"))
        assertTrue("限流后一个动作都不该派发", dispatch.actions.isEmpty())
        assertTrue("确实已经用掉过额度", acquired > 0)
    }

    // ── 参数校验：一个字节都不发 ───────────────────────────────

    @Test
    fun `缺 steps 直接失败`() = runTest {
        val reader = FakeReader(capture(node(text = "x")), events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, FakeSafety(events), dispatch)

        val outcome = tool.call(buildJsonObject { }) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("steps"))
        assertEquals("参数错误不该读屏", 0, reader.count)
        assertTrue(dispatch.actions.isEmpty())
    }

    @Test
    fun `超过上限要求拆分`() = runTest {
        val reader = FakeReader(capture(node(text = "x")), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))
        val thirteen = (1..13).map { tapAt(it, it) }.toTypedArray()

        val outcome = tool.call(steps(*thirteen)) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("12"))
        assertTrue("要建议拆成两次", outcome.reason.contains("两次"))
        assertEquals(0, reader.count)
    }

    @Test
    fun `未知 op 与未知 key 都拒绝`() = runTest {
        val reader = FakeReader(capture(node(text = "x")), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val badOp = tool.call(steps(buildJsonObject { put("op", "click") })) as ToolOutcome.Failed
        assertTrue(badOp.reason.contains("click"))

        val badKey = tool.call(steps(buildJsonObject {
            put("op", "key"); put("key", "power")
        })) as ToolOutcome.Failed
        assertTrue(badKey.reason.contains("power"))
        assertEquals(0, reader.count)
    }

    @Test
    fun `nodeId 缺 bounds 时不发`() = runTest {
        val reader = FakeReader(capture(node(text = "x")), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val outcome = tool.call(
            steps(buildJsonObject { put("op", "tap"); put("nodeId", "n1") }),
        ) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("bounds"))
        assertEquals(0, reader.count)
    }

    @Test
    fun `坐标只给一个时报错`() = runTest {
        val reader = FakeReader(capture(node(text = "x")), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val outcome = tool.call(
            steps(buildJsonObject { put("op", "tap"); put("x", 10) }),
        ) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("x") || outcome.reason.contains("坐标"))
        assertEquals(0, reader.count)
    }

    // ── 读屏不可用 ─────────────────────────────────────────────

    @Test
    fun `读屏不可用时给出开权限的下一步`() = runTest {
        val reader = FakeReader(ScreenCaptureOutcome.Unavailable("无障碍没连上"), events)
        val dispatch = FakeDispatch(events)
        val tool = tool(reader, FakeSafety(events), dispatch)

        val outcome = tool.call(steps(tapAt(1, 1))) as ToolOutcome.Failed
        assertTrue(outcome.reason.contains("无障碍"))
        assertTrue("要指向系统设置", outcome.reason.contains("设置"))
        assertTrue(dispatch.actions.isEmpty())
    }

    @Test
    fun `成功回执里不含屏幕内容`() = runTest {
        val secret = "余额 12,345 元"
        val reader = FakeReader(capture(node(text = secret)), events)
        val tool = tool(reader, FakeSafety(events), FakeDispatch(events))

        val outcome = tool.call(steps(tapAt(1, 1))) as ToolOutcome.Success
        assertFalse("回执不该回显屏幕文本：${outcome.text}", outcome.text.contains(secret))
    }
}
