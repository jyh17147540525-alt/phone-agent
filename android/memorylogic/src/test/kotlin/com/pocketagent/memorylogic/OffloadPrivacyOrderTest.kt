package com.pocketagent.memorylogic

import com.pocketagent.agentlogic.FilterOutcome
import com.pocketagent.agentlogic.PageKind
import com.pocketagent.agentlogic.PrivacyFilter
import com.pocketagent.agentlogic.RedactRegion
import com.pocketagent.agentlogic.RedactionPlan
import com.pocketagent.agentlogic.SystemUiRegions
import com.pocketagent.agentlogic.UploadContext
import com.pocketagent.agentlogic.UploadPurpose
import com.pocketagent.agentlogic.UploadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 隐私关卡必须跑在卸载落盘之前的测试（P2 验收第 2 条）。
 *
 * ★ 一句话判据：**关卡没跑过、或者跑在写入之后，都属于红线失守。**
 *
 * 为什么单独立一个文件：这一条断言的其实是一个**顺序**，而顺序这种东西
 * 只在「两个动作都发生了」的时候才看得出来。所以这里不测内容对不对，
 * 只测**调用序列**与**否决后有没有留下字节**。真正的 [PrivacyFilter]
 * 也在本文件里走一遍四个分支，确认它接到卸载路径上时的行为与接线预期一致。
 *
 * ⚠️ 本文件刻意**不用 mockk** —— 离线验证器的 classpath 里没有它。
 * 记账用两个手写的极简 fake（见 [RecordingGate] / [RecordingStore]），
 * 这也正是把隐私关卡做成 [PrivacyGate] 端口而不是直接依赖 [PrivacyFilter]
 * 实例的理由：端口让"关卡跑没跑、跑在哪"可以被离线观测。
 */
class OffloadPrivacyOrderTest {

    /** 记账型关卡：每次被问都记一笔，然后给出事先约定的结论。 */
    private class RecordingGate(
        private val log: MutableList<String>,
        private val decision: FilterOutcome,
    ) : PrivacyGate {
        override fun review(request: UploadRequest): FilterOutcome {
            log += REVIEW
            return decision
        }
    }

    /** 记账型落盘出口：每次写入都记一笔，内容留在内存里以便复查。 */
    private class RecordingStore(private val log: MutableList<String>) : OffloadBlobStore {
        val payloads = LinkedHashMap<String, OffloadPayload>()

        override fun write(payload: OffloadPayload) {
            log += WRITE
            payloads[payload.ref.relativePath] = payload
        }

        override fun read(ref: OffloadRef): String? = payloads[ref.relativePath]?.encoded
    }

    /** 造一份待卸载内容。参数齐全，避免每个测试各自拼 UploadRequest。 */
    private fun draft(
        body: String = "正文",
        requiresScreenshot: Boolean = false,
        pageKind: PageKind = PageKind.NORMAL,
        purpose: UploadPurpose = UploadPurpose.TASK_REASONING,
        inputFieldRegions: List<RedactRegion> = emptyList(),
        alreadyFiltered: Boolean = false,
        priorPlan: RedactionPlan? = null,
        taskId: String = "task-1",
    ) = OffloadDraft(
        kind = OffloadKind.TOOL_RESULT,
        taskId = taskId,
        body = body,
        request = UploadRequest(
            context = UploadContext(
                providerId = "p1",
                purpose = purpose,
                alreadyFiltered = alreadyFiltered,
            ),
            requiresScreenshot = requiresScreenshot,
            pageKind = pageKind,
            inputFieldRegions = inputFieldRegions,
            priorPlan = priorPlan,
        ),
    )

    private fun newLog() = mutableListOf<String>()

    // ── ★ 顺序本身 ─────────────────────────────────────────────

    @Test
    fun `关卡先于落盘执行`() {
        val log = newLog()
        val store = RecordingStore(log)
        val gate = RecordingGate(log, FilterOutcome.Allowed(RedactionPlan()))

        ContextOffloader(store).offload(draft(), gate, now = 1L)

        // 只断言这一条：顺序是 review → write。反过来就是隐私已经落盘。
        assertEquals(listOf(REVIEW, WRITE), log)
        assertTrue(store.payloads.isNotEmpty())
    }

    @Test
    fun `关卡否决时一个字节都不写`() {
        val log = newLog()
        val store = RecordingStore(log)
        val gate = RecordingGate(log, FilterOutcome.Dropped("按安全策略中止"))

        val outcome = ContextOffloader(store).offload(draft(), gate, now = 1L)

        // 理由要原样带出来（"执行过程可见"原则：用户得知道为什么停在这里）。
        assertEquals("按安全策略中止", (outcome as OffloadOutcome.Dropped).reason)
        // 关卡跑过一次，之后**连写都没尝试**。
        assertEquals(listOf(REVIEW), log)
        assertTrue("关卡否决后存储里必须什么都没有", store.payloads.isEmpty())
    }

    @Test
    fun `关卡抛出的异常不被吞掉`() {
        val log = newLog()
        val store = RecordingStore(log)
        val gate = PrivacyGate { throw IllegalStateException("关卡内部炸了") }

        val thrown = assertThrows(IllegalStateException::class.java) {
            ContextOffloader(store).offload(draft(), gate, now = 1L)
        }

        // ⚠️ 一旦在这里 catch 住并"当作没卸载"继续，故障就变成静默的了。
        assertEquals("关卡内部炸了", thrown.message)
        assertTrue(store.payloads.isEmpty())
        assertTrue(log.isEmpty())
    }

    // ── 真 PrivacyFilter 接上这一段时的四个分支 ────────────────

    @Test
    fun `真实过滤器遇到系统 UI 未知时拒绝截图类卸载`() {
        val store = RecordingStore(newLog())
        val gate = PrivacyFilter(SystemUiRegions.unknown).asPrivacyGate()

        val outcome = ContextOffloader(store).offload(
            draft(requiresScreenshot = true),
            gate,
            now = 1L,
        )

        // 保守取舍：量不到系统 UI 区域 → 宁可整条丢弃，也不把通知栏顺路带上去。
        assertTrue(outcome is OffloadOutcome.Dropped)
        assertTrue(store.payloads.isEmpty())
    }

    @Test
    fun `真实过滤器放行时把遮蔽指令带进 payload`() {
        val store = RecordingStore(newLog())
        val gate = PrivacyFilter(SystemUiRegions.unknown).asPrivacyGate()
        val inputBox = RedactRegion(left = 0, top = 100, right = 1080, bottom = 200)

        val outcome = ContextOffloader(store).offload(
            draft(requiresScreenshot = false, inputFieldRegions = listOf(inputBox)),
            gate,
            now = 1L,
        )

        val ref = (outcome as OffloadOutcome.Stored).ref
        // 只传文本的上传不受系统 UI 未知影响，但输入框仍要被遮 ——
        // 用户正在打的那半句话常常就是密码/验证码。
        assertEquals(listOf(inputBox), store.payloads[ref.relativePath]!!.plan.mask)
    }

    @Test
    fun `敏感页面一票否决`() {
        val store = RecordingStore(newLog())
        val gate = PrivacyFilter(SystemUiRegions.unknown).asPrivacyGate()

        val outcome = ContextOffloader(store).offload(
            draft(pageKind = PageKind.SENSITIVE),
            gate,
            now = 1L,
        )

        assertTrue(outcome is OffloadOutcome.Dropped)
        assertTrue(store.payloads.isEmpty())
    }

    @Test
    fun `插件分析的截图上传被拒绝`() {
        val store = RecordingStore(newLog())
        val gate = PrivacyFilter(SystemUiRegions.unknown).asPrivacyGate()

        val outcome = ContextOffloader(store).offload(
            draft(requiresScreenshot = true, purpose = UploadPurpose.PLUGIN_ANALYSIS),
            gate,
            now = 1L,
        )

        assertTrue(outcome is OffloadOutcome.Dropped)
        assertTrue(store.payloads.isEmpty())
    }

    // ── 伪造标记：硬失败，不静默修正 ────────────────────────────

    @Test
    fun `伪造已过滤标记时硬失败且不落盘`() {
        val store = RecordingStore(newLog())
        val gate = PrivacyFilter(SystemUiRegions.unknown).asPrivacyGate()

        // 声称"已过滤"却拿不出过滤计划 —— 这就是绕过尝试。
        // 刻意让它炸，而不是"退回重新过滤"：静默修正会让这个 bug 一直存在。
        assertThrows(IllegalArgumentException::class.java) {
            ContextOffloader(store).offload(
                draft(alreadyFiltered = true, priorPlan = null),
                gate,
                now = 1L,
            )
        }
        assertTrue(store.payloads.isEmpty())
    }

    private companion object {
        const val REVIEW = "review"
        const val WRITE = "write"
    }
}