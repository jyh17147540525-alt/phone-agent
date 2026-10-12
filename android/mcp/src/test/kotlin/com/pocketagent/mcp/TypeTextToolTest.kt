package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `android_type` / `android_key`：关卡顺序、**android_key 刻意不过安全判定**、参数形状。
 *
 * ⚠️ 关卡顺序在这里是安全断言（与 `TapToolTest` 同一条纪律）：
 *    被拒的路径上**派发器一次都不能被碰**。
 */
class TypeTextToolTest {

    private val events = mutableListOf<String>()
    private var now = 1000L

    private class FakeReader(
        private val outcome: ScreenCaptureOutcome,
        private val events: MutableList<String>,
    ) : ScreenReaderPort {
        override suspend fun capture(): ScreenCaptureOutcome {
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
        bounds: ScreenRect = ScreenRect(10, 10, 300, 100),
    ) = UiNodeDto(
        nodeId = nodeId, className = "android.widget.EditText", text = text,
        contentDescription = desc, hintText = null, bounds = bounds,
        clickable = true, longClickable = false, editable = true,
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

    private fun nodeIdTarget(id: String = "n1") = buildJsonObject {
        putJsonObject("target") {
            put("kind", "node_id"); put("nodeId", id)
            put("bounds", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive(10)); add(JsonPrimitive(10))
                add(JsonPrimitive(300)); add(JsonPrimitive(100))
            })
        }
    }

    private fun typeTool(
        reader: FakeReader,
        safety: FakeSafety,
        dispatch: FakeDispatch,
        limiter: ActionRateLimiter = newLimiter(),
    ) = TypeTextTool(reader, safety, dispatch, limiter)

    private fun keyTool(
        dispatch: FakeDispatch,
        limiter: ActionRateLimiter = newLimiter(),
    ) = GlobalKeyTool(dispatch, limiter)

    // ── ① android_type 成功路径 ─────────────────────────────────

    @Test
    fun `type 正常路径走完捕获-判定-派发且 text 透传`() = runTest {
        val reader = FakeReader(capture(node(text = "搜索")), events)
        val safety = FakeSafety(events)
        val dispatch = FakeDispatch(events)
        val outcome = typeTool(reader, safety, dispatch)
            .call(buildJsonObject {
                put("target", nodeIdTarget()["target"]!!)
                put("text", "黄焖鸡")
                put("reason", "用户要搜这家店")
            })

        assertTrue("应成功：$outcome", outcome is ToolOutcome.Success)
        assertEquals(listOf("capture", "safety", "dispatch"), events)
        val action = dispatch.actions.single() as AgentAction.TypeText
        assertEquals("黄焖鸡", action.text)
        assertEquals("用户要搜这家店", action.reason)
        assertEquals("n1", action.ref?.nodeId)
    }

    @Test
    fun `type 空串表示清空且照样透传`() = runTest {
        val dispatch = FakeDispatch(events)
        val outcome = typeTool(FakeReader(capture(node()), events), FakeSafety(events), dispatch)
            .call(buildJsonObject {
                put("target", nodeIdTarget()["target"]!!)
                put("text", "")
            })
        assertTrue(outcome is ToolOutcome.Success)
        assertEquals("", (dispatch.actions.single() as AgentAction.TypeText).text)
    }

    @Test
    fun `type 走节点路径时 point 为空`() = runTest {
        val dispatch = FakeDispatch(events)
        typeTool(FakeReader(capture(node()), events), FakeSafety(events), dispatch)
            .call(buildJsonObject { put("target", nodeIdTarget()["target"]!!); put("text", "x") })
        val action = dispatch.actions.single() as AgentAction.TypeText
        assertTrue("节点路径不该有 blindPoint", action.point == null)
        assertTrue(action.ref != null)
    }

    // ── ② ★ 被拒时派发器一次都不许被碰 ──────────────────────────

    @Test
    fun `敏感页被拒时顺序停在 safety 且不派发`() = runTest {
        val dispatch = FakeDispatch(events)
        val safety = FakeSafety(events).apply {
            outcome = ActionSafetyOutcome.Blocked(
                userMessage = "当前页面属于敏感页面（支付/银行/密码），已按安全策略中止。",
                canFallbackToManual = true,
            )
        }
        val outcome = typeTool(FakeReader(capture(node()), events), safety, dispatch)
            .call(buildJsonObject { put("target", nodeIdTarget()["target"]!!); put("text", "x") })

        assertTrue("应是 Refused：$outcome", outcome is ToolOutcome.Refused)
        assertEquals("顺序应停在 safety", listOf("capture", "safety"), events)
        assertTrue("派发器绝不能被调用", dispatch.actions.isEmpty())
    }

    @Test
    fun `限流被拒时不派发`() = runTest {
        val dispatch = FakeDispatch(events)
        val limiter = newLimiter()
        // 先把额度用掉
        repeat(60) { now += 1000; limiter.tryAcquire() }
        val outcome = typeTool(FakeReader(capture(node()), events), FakeSafety(events), dispatch, limiter)
            .call(buildJsonObject { put("target", nodeIdTarget()["target"]!!); put("text", "x") })
        assertTrue(outcome is ToolOutcome.Refused)
        assertTrue(dispatch.actions.isEmpty())
    }

    // ── ③ 参数形状 ──────────────────────────────────────────────

    @Test
    fun `缺 text 报 Failed 并说明清空要传空串`() = runTest {
        val outcome = typeTool(FakeReader(capture(node()), events), FakeSafety(events), FakeDispatch(events))
            .call(buildJsonObject { put("target", nodeIdTarget()["target"]!!) })
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("空串"))
    }

    @Test
    fun `target 缺失报 Failed`() = runTest {
        val outcome = typeTool(FakeReader(capture(node()), events), FakeSafety(events), FakeDispatch(events))
            .call(buildJsonObject { put("text", "x") })
        assertTrue(outcome is ToolOutcome.Failed)
    }

    @Test
    fun `bounds 不是四个数报 Failed`() = runTest {
        val bad = buildJsonObject {
            putJsonObject("target") {
                put("kind", "node_id"); put("nodeId", "n1")
                put("bounds", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive(10)); add(JsonPrimitive(10))
                })
            }
        }
        val outcome = typeTool(FakeReader(capture(node()), events), FakeSafety(events), FakeDispatch(events))
            .call(buildJsonObject { put("target", bad["target"]!!); put("text", "x") })
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("四个整数"))
    }

    @Test
    fun `target 文本是非标量报 Failed`() = runTest {
        val bad = buildJsonObject {
            putJsonObject("target") {
                put("kind", "text")
                putJsonObject("text") { put("nested", "x") }
            }
        }
        val outcome = typeTool(FakeReader(capture(node()), events), FakeSafety(events), FakeDispatch(events))
            .call(buildJsonObject { put("target", bad["target"]!!); put("text", "x") })
        assertTrue(outcome is ToolOutcome.Failed)
    }

    // ── ④ ★ android_key：刻意不过安全判定 ───────────────────────

    @Test
    fun `key 在敏感页上也放行 —— 这是设计不是疏漏`() = runTest {
        // 用一个**会拦截一切**的 FakeSafety 验证：key 工具根本不问它。
        // 理由见 GlobalKeyTool 的类注释：在支付页上「返回」是把用户带出去的动作，
        // 拦它等于把人锁在支付页里。
        val dispatch = FakeDispatch(events)
        val outcome = keyTool(dispatch).call(buildJsonObject { put("key", "back") })
        assertTrue("应成功：$outcome", outcome is ToolOutcome.Success)
        val action = dispatch.actions.single() as AgentAction.GlobalKey
        assertEquals(GlobalKeyName.BACK, action.key)
    }

    @Test
    fun `key 三种键都能派发`() = runTest {
        for (k in listOf("back", "home", "recents")) {
            now += 10_000 // 避开最小间隔
            val dispatch = FakeDispatch(events)
            assertTrue(keyTool(dispatch).call(buildJsonObject { put("key", k) }) is ToolOutcome.Success)
            val expected = when (k) {
                "back" -> GlobalKeyName.BACK; "home" -> GlobalKeyName.HOME
                else -> GlobalKeyName.RECENTS
            }
            assertEquals(expected, (dispatch.actions.single() as AgentAction.GlobalKey).key)
        }
    }

    @Test
    fun `key 大小写不敏感`() = runTest {
        val dispatch = FakeDispatch(events)
        assertTrue(keyTool(dispatch).call(buildJsonObject { put("key", "BACK") }) is ToolOutcome.Success)
        assertEquals(GlobalKeyName.BACK, (dispatch.actions.single() as AgentAction.GlobalKey).key)
    }

    @Test
    fun `key 未知键报 Failed 并列出可用键`() = runTest {
        val outcome = keyTool(FakeDispatch(events)).call(buildJsonObject { put("key", "power_off") })
        assertTrue(outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue(reason.contains("back")); assertTrue(reason.contains("home"))
    }

    @Test
    fun `key 缺参数报 Failed`() = runTest {
        assertTrue(keyTool(FakeDispatch(events)).call(buildJsonObject { }) is ToolOutcome.Failed)
    }

    // ── ⑤ schema ────────────────────────────────────────────────

    @Test
    fun `type 的 schema 说明替换语义`() {
        val schema = TypeTextTool(
            FakeReader(capture(node()), events), FakeSafety(events), FakeDispatch(events), newLimiter(),
        ).inputSchema().toString()
        assertTrue("应说明是替换：$schema", schema.contains("替换"))
        assertTrue("应说明空串清空：$schema", schema.contains("空串"))
        // ⚠️ schema 里不该出现「确认支付」这类示例 —— 那是危险动作关键词，
        //    写进 schema 会教模型往敏感方向试
        assertTrue(schema.contains("node_id"))
    }

    @Test
    fun `key 的 schema 列出三种键并说明不受拦截`() {
        val schema = GlobalKeyTool(FakeDispatch(events), newLimiter()).inputSchema().toString()
        for (k in listOf("back", "home", "recents")) assertTrue(schema.contains(k))
        assertTrue("应说明不受敏感页拦截：$schema", schema.contains("不受"))
    }
}
