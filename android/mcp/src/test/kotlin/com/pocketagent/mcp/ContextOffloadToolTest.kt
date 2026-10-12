package com.pocketagent.mcp

import com.pocketagent.agentlogic.FilterOutcome
import com.pocketagent.agentlogic.PageKind
import com.pocketagent.agentlogic.RedactionPlan
import com.pocketagent.agentlogic.UploadContext
import com.pocketagent.agentlogic.UploadPurpose
import com.pocketagent.agentlogic.UploadRequest
import com.pocketagent.memorylogic.ContextOffloader
import com.pocketagent.memorylogic.OffloadBlobStore
import com.pocketagent.memorylogic.OffloadDraft
import com.pocketagent.memorylogic.OffloadKind
import com.pocketagent.memorylogic.OffloadPayload
import com.pocketagent.memorylogic.OffloadPolicy
import com.pocketagent.memorylogic.OffloadRef
import com.pocketagent.memorylogic.PrivacyGate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `context_offload` / `context_recall`：关卡顺序、拒绝文案、参数校验、幂等、往返。
 *
 * ⚠️ 顺序在这里是**安全断言**（红线 §8.2-2）：隐私关卡必须发生在**任何写入之前**。
 *    "测试看不到顺序"的借口不成立 —— 用会记账的 fake 直接断言调用序列，
 *    并且断言**被拒的路径上存储一次都没被碰过**。
 *
 * ⚠️ 本文件也是 `:mcp → :memorylogic` 这条依赖边的**活体验证**：
 *    能编译过就说明两个模块真的接上了（在此之前 `:memorylogic` 的
 *    2000+ 行在 `classes.dex` 里一个字节都没有）。
 */
class ContextOffloadToolTest {

    /** 记录所有对存储的触碰。任何一次 write 都会留下痕迹 —— 这是顺序断言的依据。 */
    private val events = mutableListOf<String>()

    private val files = mutableMapOf<String, String>()

    private inner class FakeStore : OffloadBlobStore {
        override fun write(payload: OffloadPayload) {
            events += "write:${payload.ref.relativePath}"
            files[payload.ref.relativePath] = payload.encoded
        }

        override fun read(ref: OffloadRef): String? {
            events += "read:${ref.relativePath}"
            return files[ref.relativePath]
        }
    }

    /** 默认放行的关卡；`plan` 刻意给一个非 no-op，好断言它确实被传下去了。 */
    private class FakeGate(
        private val outcome: FilterOutcome = FilterOutcome.Allowed(
            plan = RedactionPlan(mask = emptyList(), reduceQuality = false),
        ),
        private val events: MutableList<String>,
    ) : PrivacyGate {
        val requests = mutableListOf<UploadRequest>()
        override fun review(request: UploadRequest): FilterOutcome {
            events += "gate"
            requests += request
            return outcome
        }
    }

    /** draft 端口：默认产出一个 NORMAL 页面的 draft，并记账。 */
    private inner class FakeDrafts(
        private val outcome: (OffloadKind) -> OffloadDraftOutcome,
    ) : OffloadDraftPort {
        override suspend fun draft(
            kind: OffloadKind,
            taskId: String,
            body: String,
        ): OffloadDraftOutcome {
            events += "draft"
            return outcome(kind)
        }
    }

    private fun normalDraft(kind: OffloadKind, taskId: String, body: String) = OffloadDraft(
        kind = kind,
        taskId = taskId,
        body = body,
        request = UploadRequest(
            context = UploadContext(providerId = "dsh", purpose = UploadPurpose.TASK_REASONING),
            requiresScreenshot = false,
            pageKind = PageKind.NORMAL,
        ),
    )

    private fun offloadTool(
        drafts: OffloadDraftPort,
        gate: PrivacyGate,
        store: OffloadBlobStore = FakeStore(),
        now: Long = 1_000L,
    ): ContextOffloadTool = ContextOffloadTool(
        offloader = ContextOffloader(store),
        drafts = drafts,
        gate = gate,
        clock = { now },
    )

    private fun args(kind: String, taskId: String, body: String) = buildJsonObject {
        put("kind", kind); put("taskId", taskId); put("body", body)
    }

    // ── ① 正常路径 ───────────────────────────────────────────────

    @Test
    fun `正常路径返回引用与占位索引`() = runTest {
        val body = "x".repeat(3000)
        val tool = offloadTool(
            drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", body)) },
            gate = FakeGate(events = events),
        )
        val outcome = tool.call(args("tool-result", "t1", body))
        assertTrue(outcome is ToolOutcome.Success)
        val sc = (outcome as ToolOutcome.Success).structured!!.jsonObject
        val ref = sc["ref"]!!.jsonObject
        assertTrue("id 应以 of- 开头", ref["id"]!!.jsonPrimitive.content.startsWith("of-"))
        assertEquals("tool-result", ref["kind"]!!.jsonPrimitive.content)
        assertEquals("t1", ref["taskId"]!!.jsonPrimitive.content)
        // 占位索引必须存在，且**不含正文**（否则卸载就白做了）
        val placeholder = sc["placeholder"]!!.jsonPrimitive.content
        assertTrue("占位索引应含 id", placeholder.contains(ref["id"]!!.jsonPrimitive.content))
        assertFalse("占位索引绝不能含正文", placeholder.contains("xxxx"))
    }

    // ── ② ★ 顺序断言（红线）──────────────────────────────────────

    @Test
    fun `关卡在写入之前 —— 调用顺序是 draft → gate → write`() = runTest {
        val body = "y".repeat(3000)
        val tool = offloadTool(
            drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", body)) },
            gate = FakeGate(events = events),
        )
        tool.call(args("tool-result", "t1", body))
        // ⚠️ 只断言**顺序**，不断言落盘路径的格式 ——
        //    路径格式由 OffloadRef.relativePath 负责（它有离线测试），
        //    在这里复述一遍只会让"改了目录结构就红一片"，而那与安全顺序无关。
        val order = events.map { e ->
            when {
                e.startsWith("write:") -> "write"
                else -> e
            }
        }
        assertEquals("顺序必须是 draft → gate → write", listOf("draft", "gate", "write"), order)
    }

    @Test
    fun `敏感页被拒时 —— 存储一次都没被碰过`() = runTest {
        val tool = offloadTool(
            drafts = FakeDrafts {
                OffloadDraftOutcome.Refused("当前页面属于敏感页面（支付/银行/密码），已按安全策略中止。")
            },
            gate = FakeGate(events = events),
        )
        val outcome = tool.call(args("tool-result", "t1", "z".repeat(3000)))
        assertTrue("应是 Refused 而非 Failed/Success", outcome is ToolOutcome.Refused)
        assertTrue("文案应能直接展示", (outcome as ToolOutcome.Refused).reason.contains("敏感页面"))
        assertEquals("顺序应停在 draft，不许走到 gate/write", listOf("draft"), events)
        assertTrue("磁盘上不该有任何文件", files.isEmpty())
    }

    @Test
    fun `关卡 Dropped 时 —— 存储一次都没被碰过`() = runTest {
        val tool = offloadTool(
            drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", "w".repeat(3000))) },
            gate = FakeGate(
                outcome = FilterOutcome.Dropped(reason = "当前页面属于敏感页面。"),
                events = events,
            ),
        )
        val outcome = tool.call(args("tool-result", "t1", "w".repeat(3000)))
        assertTrue(outcome is ToolOutcome.Refused)
        assertEquals("顺序应停在 gate，不许走到 write", listOf("draft", "gate"), events)
        assertTrue(files.isEmpty())
    }

    // ── ③ 参数校验 ───────────────────────────────────────────────

    @Test
    fun `kind 非法时报 Failed 并列出合法值`() = runTest {
        val tool = offloadTool(FakeDrafts { OffloadDraftOutcome.Refused("不该走到这") }, FakeGate(events = events))
        val outcome = tool.call(args("bogus", "t1", "a"))
        assertTrue(outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue("应列出合法值：$reason", reason.contains("tool-result"))
        assertTrue("参数错误不该触碰端口", events.isEmpty())
    }

    @Test
    fun `taskId 为空时报 Failed`() = runTest {
        val tool = offloadTool(FakeDrafts { OffloadDraftOutcome.Refused("不该走到这") }, FakeGate(events = events))
        assertTrue(tool.call(args("tool-result", "  ", "a")) is ToolOutcome.Failed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `body 为空时报 Failed`() = runTest {
        val tool = offloadTool(FakeDrafts { OffloadDraftOutcome.Refused("不该走到这") }, FakeGate(events = events))
        assertTrue(tool.call(args("tool-result", "t1", "")) is ToolOutcome.Failed)
        assertTrue(events.isEmpty())
    }

    // ── ④ ★ 幂等（重试不该在用户存储里堆出一串一样的大文件）────────

    @Test
    fun `同一内容重复卸载落到同一个文件`() = runTest {
        val body = "q".repeat(3000)
        val store = FakeStore()
        val tool = offloadTool(
            drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", body)) },
            gate = FakeGate(events = events),
            store = store,
            now = 1_000L,
        )
        val first = (tool.call(args("tool-result", "t1", body)) as ToolOutcome.Success)
            .structured!!.jsonObject["ref"]!!.jsonObject["id"]!!.jsonPrimitive.content
        // 第二次用**不同的时刻** —— 时间属于引用的元数据，不属于它的身份。
        val tool2 = offloadTool(
            drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", body)) },
            gate = FakeGate(events = events),
            store = store,
            now = 9_999_999L,
        )
        val second = (tool2.call(args("tool-result", "t1", body)) as ToolOutcome.Success)
            .structured!!.jsonObject["ref"]!!.jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("同一内容两次卸载必须得到同一个 id", first, second)
        assertEquals("只应有一个文件", 1, files.size)
    }

    @Test
    fun `不同任务里同一内容各留一份`() = runTest {
        val body = "r".repeat(3000)
        val store = FakeStore()
        val tool = offloadTool(
            drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", body)) },
            gate = FakeGate(events = events), store = store,
        )
        tool.call(args("tool-result", "t1", body))
        val tool2 = offloadTool(
            drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t2", body)) },
            gate = FakeGate(events = events), store = store,
        )
        tool2.call(args("tool-result", "t2", body))
        assertEquals("两个任务应各留一份", 2, files.size)
    }

    // ── ⑤ recall ────────────────────────────────────────────────

    @Test
    fun `卸载后能原样取回（逐字节一致）`() = runTest {
        val body = "正文内容".repeat(800)
        val store = FakeStore()
        val drafts = FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", body)) }
        val offload = offloadTool(drafts, FakeGate(events = events), store = store)
        val refJson = (offload.call(args("tool-result", "t1", body)) as ToolOutcome.Success)
            .structured!!.jsonObject["ref"]!!

        val recall = ContextRecallTool(ContextOffloader(store))
        val outcome = recall.call(buildJsonObject { put("ref", refJson) })
        assertTrue(outcome is ToolOutcome.Success)
        assertEquals("必须逐字节一致", body, (outcome as ToolOutcome.Success).text)
        assertTrue(outcome.structured!!.jsonObject["verified"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `引用指向不存在的文件时报 Missing（不是 Success）`() = runTest {
        val recall = ContextRecallTool(ContextOffloader(FakeStore()))
        val outcome = recall.call(buildJsonObject { put("ref", refJsonFor("t9", "a".repeat(3000))) })
        assertTrue("绝不能把取不到当成功", outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("找不到"))
    }

    @Test
    fun `内容被改写时报 Corrupted 且明确要求不要使用`() = runTest {
        val body = "s".repeat(3000)
        val store = FakeStore()
        val refJson = refJsonFor("t1", body)
        // 先正常写入，再篡改落盘内容（模拟截断/被改写）
        val offload = offloadTool(
            FakeDrafts { k -> OffloadDraftOutcome.Ready(normalDraft(k, "t1", body)) },
            FakeGate(events = events), store = store,
        )
        offload.call(args("tool-result", "t1", body))
        val key = files.keys.first()
        files[key] = files[key]!!.dropLast(10) // 截断

        val outcome = ContextRecallTool(ContextOffloader(store))
            .call(buildJsonObject { put("ref", refJson) })
        assertTrue(outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue("应说明完整性校验未过：$reason", reason.contains("完整性"))
        assertTrue("应明确要求不要使用：$reason", reason.contains("请勿使用"))
    }

    @Test
    fun `ref 字段不全时报 Failed 并说明要原样抄`() = runTest {
        val recall = ContextRecallTool(ContextOffloader(FakeStore()))
        // 少了 sha256 与 bytes —— 只给 id/kind/taskId
        val partial = buildJsonObject {
            putJsonObject("ref") {
                put("id", "of-0123456789abcdef0123")
                put("kind", "tool-result")
                put("taskId", "t1")
            }
        }
        val outcome = recall.call(partial)
        assertTrue(outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue("应提示原样抄：$reason", reason.contains("原样"))
        assertTrue("应点名缺哪个字段：$reason", reason.contains("sha256"))
    }

    @Test
    fun `ref 的 kind 不认识时报 Failed`() = runTest {
        val recall = ContextRecallTool(ContextOffloader(FakeStore()))
        val bad = buildJsonObject {
            putJsonObject("ref") {
                put("id", "of-0123456789abcdef0123")
                put("kind", "不认识的类别")
                put("taskId", "t1")
                put("bytes", 100)
                put("sha256", "a".repeat(64))
                put("createdAt", 1)
            }
        }
        assertTrue(recall.call(bad) is ToolOutcome.Failed)
    }

    // ── ⑥ schema 与描述（模型要读的那份）─────────────────────────

    @Test
    fun `offload 的 schema 声明三个必填参数`() = runTest {
        val tool = offloadTool(FakeDrafts { OffloadDraftOutcome.Refused("x") }, FakeGate(events = events))
        val schema = tool.inputSchema()
        val required = schema["required"]!!.toString()
        assertTrue(required.contains("kind"))
        assertTrue(required.contains("taskId"))
        assertTrue(required.contains("body"))
        // kind 的 enum 必须把三个类别都列出来（模型靠它选值）
        assertTrue(schema.toString().contains("tool-result"))
        assertTrue(schema.toString().contains("turn-body"))
    }

    @Test
    fun `recall 的 schema 说明 bytes 与 sha256 的用途`() = runTest {
        val schema = ContextRecallTool(ContextOffloader(FakeStore())).inputSchema()
        val s = schema.toString()
        assertTrue("应说明完整性判据", s.contains("完整性"))
        assertTrue("应提示原样抄", s.contains("原样"))
    }

    // ── helpers ─────────────────────────────────────────────────

    /** 按内容算出合法的 ref JSON（供 recall 测试用）。 */
    private fun refJsonFor(taskId: String, body: String): JsonObject {
        val encoded = com.pocketagent.memorylogic.OffloadCodec.encode(
            OffloadKind.TOOL_RESULT, taskId, body, 1_000L,
        )
        return buildJsonObject {
            put("id", OffloadRef.idOf(OffloadKind.TOOL_RESULT, taskId, body))
            put("kind", OffloadKind.TOOL_RESULT.segment)
            put("taskId", taskId)
            put("bytes", com.pocketagent.memorylogic.OffloadCodec.byteSize(encoded))
            put("sha256", com.pocketagent.memorylogic.OffloadCodec.sha256(encoded))
            put("createdAt", 1_000L)
        }
    }

    @Test
    fun `占位索引的文案里含类别标签与字节数`() = runTest {
        val body = "p".repeat(3000)
        val ref = OffloadRef(
            id = OffloadRef.idOf(OffloadKind.TOOL_RESULT, "t1", body),
            kind = OffloadKind.TOOL_RESULT,
            taskId = "t1",
            bytes = body.toByteArray(Charsets.UTF_8).size,
            sha256 = "a".repeat(64),
            createdAt = 1L,
        )
        val placeholder = OffloadPolicy.placeholderFor(ref)
        assertTrue(placeholder.contains(OffloadKind.TOOL_RESULT.label))
        assertTrue(placeholder.contains(ref.bytes.toString()))
        assertNotNull(placeholder)
    }
}
