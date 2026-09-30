package com.pocketagent.memorylogic

import com.pocketagent.agentlogic.FilterOutcome
import com.pocketagent.agentlogic.RedactionPlan
import com.pocketagent.agentlogic.UploadContext
import com.pocketagent.agentlogic.UploadPurpose
import com.pocketagent.agentlogic.UploadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卸载 → 恢复的往返测试（P2 验收第 1 条）。
 *
 * ★ 一句话判据：**卸载出去的内容，恢复回来必须逐字节一致。**
 *
 * 为什么要把这条钉得这么死：这个链路里每一层都可能"成功但不等价" ——
 * JSON 转义漏一个字符、平台换行被规范化、外壳版本演进时少读一个字段。
 * 而它们的表现全部是**恢复成功、内容不同**，用户只会觉得助理记错了。
 */
class OffloadRoundTripTest {

    /** 内存版落盘出口：文件名 → 落盘文本。 */
    private class MemoryBlobStore : OffloadBlobStore {
        val files = LinkedHashMap<String, String>()

        override fun write(payload: OffloadPayload) {
            files[payload.ref.relativePath] = payload.encoded
        }

        override fun read(ref: OffloadRef): String? = files[ref.relativePath]
    }

    private val store = MemoryBlobStore()
    private val offloader = ContextOffloader(store)

    /** 永远放行的关卡 —— 本文件只测往返，关卡的行为在 OffloadPrivacyOrderTest 里钉。 */
    private val allowGate = PrivacyGate { FilterOutcome.Allowed(RedactionPlan()) }

    private fun draft(
        body: String,
        kind: OffloadKind = OffloadKind.TOOL_RESULT,
        taskId: String = "task-1",
    ) = OffloadDraft(
        kind = kind,
        taskId = taskId,
        body = body,
        request = UploadRequest(
            context = UploadContext(providerId = "p1", purpose = UploadPurpose.TASK_REASONING),
            requiresScreenshot = false,
        ),
    )

    /** 一段"什么都有"的正文：中文、emoji、换行、引号、制表符、CRLF。 */
    private val trickyBody = "第一行\n第二行\r\n带\"引号\"与「中文引号」\n\ttab 结尾 🙂"

    // ── 端到端往返 ─────────────────────────────────────────────

    @Test
    fun `卸载后恢复内容逐字节一致`() {
        val outcome = offloader.offload(draft(trickyBody), allowGate, now = 1_000L)
        val ref = (outcome as OffloadOutcome.Stored).ref

        val recalled = offloader.recall(ref)

        assertEquals(trickyBody, (recalled as RecallOutcome.Restored).body)
    }

    @Test
    fun `往返闭合 重编码结果与落盘文本完全相同`() {
        val ref = (offloader.offload(draft(trickyBody), allowGate, now = 1L) as OffloadOutcome.Stored).ref
        val onDisk = store.files[ref.relativePath]!!

        val decoded = OffloadCodec.decode(onDisk) as DecodeResult.Ok
        val reencoded = OffloadCodec.encode(decoded.kind, decoded.taskId, decoded.body, decoded.createdAt)

        assertEquals(onDisk, reencoded)
    }

    @Test
    fun `外壳里保留了类别任务与时间`() {
        val ref = (offloader.offload(draft("正文", kind = OffloadKind.SCREENSHOT, taskId = "t9"), allowGate, now = 42L) as OffloadOutcome.Stored).ref

        val decoded = OffloadCodec.decode(store.files[ref.relativePath]!!) as DecodeResult.Ok

        assertEquals(OffloadKind.SCREENSHOT, decoded.kind)
        assertEquals("t9", decoded.taskId)
        assertEquals(42L, decoded.createdAt)
        assertEquals("正文", decoded.body)
    }

    // ── 幂等与路径 ─────────────────────────────────────────────

    @Test
    fun `相同内容重复卸载落到同一个文件且引用 id 相同`() {
        val first = (offloader.offload(draft(trickyBody), allowGate, now = 1L) as OffloadOutcome.Stored).ref
        val second = (offloader.offload(draft(trickyBody), allowGate, now = 2L) as OffloadOutcome.Stored).ref

        // ⚠️ 引用里的 createdAt 会不同（那是"什么时候卸的"），但 id 与路径
        //    必须相同 —— 否则重试会在用户存储里堆出一串一模一样的大文件。
        assertEquals(first.id, second.id)
        assertEquals(first.relativePath, second.relativePath)
        assertEquals(1, store.files.size)
    }

    @Test
    fun `不同内容落到不同文件`() {
        val a = (offloader.offload(draft("甲"), allowGate, now = 1L) as OffloadOutcome.Stored).ref
        val b = (offloader.offload(draft("乙"), allowGate, now = 1L) as OffloadOutcome.Stored).ref

        assertNotEquals(a.id, b.id)
        assertEquals(2, store.files.size)
    }

    @Test
    fun `落盘路径按任务与类别分目录`() {
        val ref = (offloader.offload(draft("正文", kind = OffloadKind.TOOL_RESULT, taskId = "task-7"), allowGate, now = 1L) as OffloadOutcome.Stored).ref

        assertEquals("task-7/tool-result/${ref.id}.json", ref.relativePath)
    }

    // ── 引用里的完整性信息 ─────────────────────────────────────

    @Test
    fun `引用的字节数与指纹和落盘内容一致`() {
        val ref = (offloader.offload(draft(trickyBody), allowGate, now = 1L) as OffloadOutcome.Stored).ref
        val onDisk = store.files[ref.relativePath]!!

        assertEquals(onDisk.toByteArray(Charsets.UTF_8).size, ref.bytes)
        assertEquals(OffloadCodec.sha256(onDisk), ref.sha256)
        assertEquals(64, ref.sha256.length)
    }

    // ── 恢复失败的三条路径，一条都不能少 ────────────────────────

    @Test
    fun `存储里没有时报 Missing`() {
        val ref = (offloader.offload(draft("正文"), allowGate, now = 1L) as OffloadOutcome.Stored).ref
        store.files.clear()

        assertTrue(offloader.recall(ref) is RecallOutcome.Missing)
    }

    @Test
    fun `存储内容被改写时报 Corrupted`() {
        val ref = (offloader.offload(draft("用户住在杭州"), allowGate, now = 1L) as OffloadOutcome.Stored).ref
        store.files[ref.relativePath] = store.files[ref.relativePath]!!.replace("杭州", "上海")

        val recalled = offloader.recall(ref)

        assertTrue("改写后的内容不能当成功返回", recalled is RecallOutcome.Corrupted)
    }

    @Test
    fun `存储内容被截断时报 Corrupted`() {
        val ref = (offloader.offload(draft(trickyBody), allowGate, now = 1L) as OffloadOutcome.Stored).ref
        store.files[ref.relativePath] = store.files[ref.relativePath]!!.dropLast(10)

        assertTrue(offloader.recall(ref) is RecallOutcome.Corrupted)
    }

    // ── 外壳演进 ───────────────────────────────────────────────

    @Test
    fun `版本不认识时报损坏而不是尽力解析`() {
        val text = """{"v":2,"kind":"tool-result","taskId":"t","body":"正文","at":1}"""

        val decoded = OffloadCodec.decode(text)

        assertTrue(decoded is DecodeResult.Failed)
        assertTrue((decoded as DecodeResult.Failed).reason.contains("版本"))
    }

    @Test
    fun `不是 JSON 时报损坏`() {
        assertTrue(OffloadCodec.decode("这不是 JSON") is DecodeResult.Failed)
    }

    @Test
    fun `类别段名不认识时报损坏`() {
        val text = """{"v":1,"kind":"mystery","taskId":"t","body":"正文","at":1}"""

        assertTrue(OffloadCodec.decode(text) is DecodeResult.Failed)
    }

    @Test
    fun `缺少任务号时报损坏`() {
        val text = """{"v":1,"kind":"tool-result","taskId":"","body":"正文","at":1}"""

        assertTrue(OffloadCodec.decode(text) is DecodeResult.Failed)
    }

    // ── 降级：进上下文的是索引，不是正文 ────────────────────────

    @Test
    fun `占位索引不含正文`() {
        val ref = (offloader.offload(draft("这是绝不能出现在上下文里的正文"), allowGate, now = 1L) as OffloadOutcome.Stored).ref

        val placeholder = offloader.placeholder(ref)

        assertTrue("占位索引里出现了正文 —— 卸载等于没做", !placeholder.contains("绝不能出现在上下文里"))
        assertTrue(placeholder.contains(ref.id))
        assertTrue(placeholder.contains("工具原始结果"))
    }

    @Test
    fun `阈值按 UTF-8 字节判定`() {
        // 100 个汉字 = 300 字节，越过 200 的阈值；100 个 ASCII = 100 字节，不越。
        assertTrue(OffloadPolicy.needsOffload("中".repeat(100), thresholdBytes = 200))
        assertFalse(OffloadPolicy.needsOffload("a".repeat(100), thresholdBytes = 200))
    }

    @Test
    fun `恰好等于阈值时不卸载`() {
        // 边界必须是确定的一侧：`>` 而不是 `>=`。差一个字节就搬走一个大文件，
        // 会让"同一个任务两次运行结果不同"，而两次都"成功"。
        assertFalse(OffloadPolicy.needsOffload("a".repeat(200), thresholdBytes = 200))
        assertTrue(OffloadPolicy.needsOffload("a".repeat(201), thresholdBytes = 200))
    }

    // ── 构造校验 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `空正文的卸载请求被拒绝`() {
        draft("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空任务号的卸载请求被拒绝`() {
        draft("正文", taskId = "")
    }
}