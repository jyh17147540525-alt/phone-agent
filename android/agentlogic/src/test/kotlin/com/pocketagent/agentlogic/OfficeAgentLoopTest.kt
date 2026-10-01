package com.pocketagent.agentlogic

import com.pocketagent.filelogic.OfficeRequest
import com.pocketagent.filelogic.WorkspacePolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 办公 agent 循环的离线测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 本文件要钉住的四件事
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **拒绝是反馈，不是终止**（`工具被拒绝时把原因回给模型而不是中止`）。
 *    拒绝的理由绝大多数是模型能自己修的 —— 把可修复的错误当成
 *    终止条件，是循环设计里最贵的错法。
 * 2. **重复调用要判卡死**（`连续相同调用判定卡死`）。
 *    没有这条，循环会一直转到预算耗尽，用户看到的是
 *    "助理转了 12 轮什么也没做"。
 * 3. **解析失败也要回给模型**（`解析失败会把提示写进 trace`）。
 *    不告诉它"看不懂"，它下一轮只会重复同样的错误输出。
 * 4. **`answer` 的提取必须宽松**（`答复里带换行与引号也能取出`）。
 *    严格 JSON 解析会把**已经完成**的任务判成"看不懂"，然后继续跑。
 */
class OfficeAgentLoopTest {

    // ── 假件 ────────────────────────────────────────────────────

    private class FakeLlm(private val responses: List<String>) : OfficeLlmPort {
        private var index = 0
        val prompts = mutableListOf<String>()

        override suspend fun complete(prompt: String): String {
            prompts += prompt
            return responses.getOrElse(index++) { """{"answer":"（预设响应已用完）"}""" }
        }

        val callCount: Int get() = index
    }

    private class FakeExecutor(
        private val override: ((OfficeRequest) -> OfficeToolOutcome)? = null,
    ) : OfficeToolExecutor {
        val executed = mutableListOf<OfficeRequest>()

        override suspend fun execute(request: OfficeRequest): OfficeToolOutcome {
            executed += request
            return override?.invoke(request) ?: OfficeToolOutcome.Ok("已执行 ${request::class.simpleName}")
        }
    }

    private fun task(
        input: String = "把数据整理成表格",
        policy: WorkspacePolicy = WorkspacePolicy(),
        maxSteps: Int = OfficeTask.DEFAULT_MAX_STEPS,
    ) = OfficeTask(
        id = "t1",
        userInput = input,
        workspaceName = "我的文档",
        policy = policy,
        maxSteps = maxSteps,
    )

    private fun loop(
        llm: OfficeLlmPort,
        executor: OfficeToolExecutor = FakeExecutor(),
        budget: AgentBudget = AgentBudget(),
    ) = OfficeAgentLoop(llm, executor, BudgetGuard(budget), clock = { 1_000L })

    // ── 正常流程 ────────────────────────────────────────────────

    @Test
    fun `调工具后给出答复`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                """{"tool":"list_files","args":{"path":"."}}""",
                """{"answer":"工作区里有 3 个文件。"}""",
            ),
        )
        val executor = FakeExecutor()

        val result = loop(llm, executor).run(task())

        assertTrue("应当是 Answered，实际 $result", result is OfficeRunResult.Answered)
        result as OfficeRunResult.Answered
        assertEquals("工作区里有 3 个文件。", result.text)
        assertEquals(2, result.steps)
        assertEquals(1, executor.executed.size)
    }

    @Test
    fun `第一次就答复时不调任何工具`() = runBlocking {
        val llm = FakeLlm(listOf("""{"answer":"这个工作区是空的。"}"""))
        val executor = FakeExecutor()

        val result = loop(llm, executor).run(task())

        assertTrue(result is OfficeRunResult.Answered)
        assertEquals("不该执行任何工具", 0, executor.executed.size)
        assertEquals(1, (result as OfficeRunResult.Answered).steps)
    }

    @Test
    fun `每一步的结果都进 trace`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                """{"tool":"list_files","args":{"path":"."}}""",
                """{"tool":"read_text","args":{"path":"a.md"}}""",
                """{"answer":"读完了。"}""",
            ),
        )

        val result = loop(llm).run(task()) as OfficeRunResult.Answered

        assertEquals(2, result.trace.size)
        assertTrue("trace 要含工具名，实际 ${result.trace}", result.trace[0].contains("list_files"))
        assertTrue(result.trace[1].contains("read_text"))
    }

    // ── ★★ 拒绝是反馈，不是终止 ─────────────────────────────────

    @Test
    fun `工具被拒绝时把原因回给模型而不是中止`() = runBlocking {
        // 路径穿越 —— 模型可以自己改对
        val llm = FakeLlm(
            listOf(
                """{"tool":"read_text","args":{"path":"../../secret"}}""",
                """{"answer":"路径不对，我改用 a.md。"}""",
            ),
        )

        val result = loop(llm).run(task()) as OfficeRunResult.Answered

        assertEquals("拒绝不该中止任务", 2, result.steps)
        assertTrue("trace 要含拒绝原因，实际 ${result.trace}", result.trace[0].contains("被拒绝"))
    }

    @Test
    fun `拒绝原因会出现在下一轮的提示词里`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                """{"tool":"write_text","args":{"path":"a.md","content":"x"}}""",
                """{"answer":"好的。"}""",
            ),
        )

        loop(llm).run(task())

        assertEquals("应当调了两次模型", 2, llm.callCount)
        val secondPrompt = llm.prompts[1]
        assertTrue(
            "第二轮提示词里要能看到拒绝原因，否则模型无从改正",
            secondPrompt.contains("被拒绝"),
        )
    }

    @Test
    fun `产出目录约束被违反时模型能看到理由`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                """{"tool":"write_text","args":{"path":"报告.md","content":"x"}}""",
                """{"answer":"我改到输出目录。"}""",
            ),
        )

        val result = loop(llm).run(task()) as OfficeRunResult.Answered

        assertTrue(
            "理由要提到产出目录，实际 ${result.trace}",
            result.trace[0].contains("输出"),
        )
    }

    // ── ★★ 卡死检测 ─────────────────────────────────────────────

    @Test
    fun `连续相同调用判定卡死`() = runBlocking {
        // 模型卡住时的典型表现：发出完全相同的调用（它以为上次没成功）
        val same = """{"tool":"list_files","args":{"path":"."}}"""
        val llm = FakeLlm(List(10) { same })

        val result = loop(llm).run(task())

        assertTrue("应当判卡死，实际 $result", result is OfficeRunResult.Stopped)
        result as OfficeRunResult.Stopped
        assertTrue("理由要提到卡死，实际「${result.reason}」", result.reason.contains("卡死"))
        assertTrue("不该跑到步数上限，实际 ${llm.callCount} 次", llm.callCount <= 4)
    }

    @Test
    fun `参数顺序不同不算重复`() = runBlocking {
        // 防止把"模型换了参数写法"误判成卡死
        val llm = FakeLlm(
            listOf(
                """{"tool":"write_text","args":{"path":"输出/a.md","content":"1"}}""",
                """{"tool":"write_text","args":{"content":"1","path":"输出/a.md"}}""",
                """{"tool":"write_text","args":{"path":"输出/a.md","content":"2"}}""",
                """{"answer":"好了。"}""",
            ),
        )

        val result = loop(llm).run(task())

        assertTrue("不该被判卡死，实际 $result", result is OfficeRunResult.Answered)
    }

    @Test
    fun `交替的两次调用不算重复`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                """{"tool":"list_files","args":{"path":"."}}""",
                """{"tool":"read_text","args":{"path":"a.md"}}""",
                """{"tool":"list_files","args":{"path":"."}}""",
                """{"answer":"完成。"}""",
            ),
        )

        val result = loop(llm).run(task())

        assertTrue(result is OfficeRunResult.Answered)
    }

    // ── ★★ 保护机制 ─────────────────────────────────────────────

    @Test
    fun `步数上限会停下`() = runBlocking {
        // 每次换一个路径，避开卡死检测，纯测步数上限
        val llm = FakeLlm(List(20) { i -> """{"tool":"read_text","args":{"path":"f$i.md"}}""" })

        val result = loop(llm).run(task(maxSteps = 4))

        assertTrue("应当是 StepLimitReached，实际 $result", result is OfficeRunResult.StepLimitReached)
        assertEquals(4, (result as OfficeRunResult.StepLimitReached).steps)
    }

    @Test
    fun `步数上限不是失败`() = runBlocking {
        // 它只是没做完 —— 用户可以选择继续。语义上必须与 Stopped 分开
        val llm = FakeLlm(List(20) { i -> """{"tool":"read_text","args":{"path":"f$i.md"}}""" })

        val result = loop(llm).run(task(maxSteps = 2))

        assertTrue(result !is OfficeRunResult.Stopped)
    }

    @Test
    fun `预算超限会中止`() = runBlocking {
        val llm = FakeLlm(List(20) { i -> """{"tool":"read_text","args":{"path":"f$i.md"}}""" })
        // 预算只给 2 轮
        val budget = AgentBudget(maxTurns = 2)

        val result = loop(llm, budget = budget).run(task(maxSteps = 20))

        assertTrue("应当被预算拦住，实际 $result", result is OfficeRunResult.Stopped)
        assertTrue((result as OfficeRunResult.Stopped).reason.contains("预算"))
    }

    @Test
    fun `模型调用失败时如实报告而不是崩溃`() = runBlocking {
        val llm = object : OfficeLlmPort {
            override suspend fun complete(prompt: String): String = throw RuntimeException("网络断了")
        }

        val result = loop(llm).run(task())

        assertTrue(result is OfficeRunResult.Stopped)
        assertTrue((result as OfficeRunResult.Stopped).reason.contains("网络断了"))
    }

    @Test
    fun `工具执行失败不中止任务`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                """{"tool":"read_text","args":{"path":"a.md"}}""",
                """{"answer":"读不了，我换个文件。"}""",
            ),
        )
        val executor = FakeExecutor { OfficeToolOutcome.Failed("文件不存在") }

        val result = loop(llm, executor).run(task())

        assertTrue("工具失败不该中止", result is OfficeRunResult.Answered)
        assertTrue((result as OfficeRunResult.Answered).trace[0].contains("文件不存在"))
    }

    // ── ★ 解析 ──────────────────────────────────────────────────

    @Test
    fun `解析失败会把提示写进 trace`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                "我觉得应该先列个目录", // 不是 JSON
                """{"answer":"好的。"}""",
            ),
        )

        val result = loop(llm).run(task()) as OfficeRunResult.Answered

        assertTrue("要告诉模型输出格式不对", result.trace[0].contains("无法解析"))
    }

    @Test
    fun `解析失败的提示会出现在下一轮提示词里`() = runBlocking {
        val llm = FakeLlm(listOf("随便说说", """{"answer":"好。"}"""))

        loop(llm).run(task())

        assertTrue(llm.prompts[1].contains("无法解析"))
    }

    @Test
    fun `代码围栏会被剥掉`() = runBlocking {
        val llm = FakeLlm(
            listOf(
                "```json\n{\"tool\":\"list_files\",\"args\":{\"path\":\".\"}}\n```",
                """{"answer":"完成。"}""",
            ),
        )
        val executor = FakeExecutor()

        loop(llm, executor).run(task())

        assertEquals("围栏里的调用应当被执行", 1, executor.executed.size)
    }

    @Test
    fun `答复里带换行与引号也能取出`() = runBlocking {
        // 严格 JSON 解析会把这种"已经完成"的任务判成看不懂
        val llm = FakeLlm(listOf("""{"answer":"第一行\n他说\"好\"\n第三行"}"""))

        val result = loop(llm).run(task()) as OfficeRunResult.Answered

        assertTrue("换行要还原，实际「${result.text}」", result.text.contains("\n"))
        assertTrue("引号要还原，实际「${result.text}」", result.text.contains("\"好\""))
    }

    @Test
    fun `答复带代码围栏也能取出`() = runBlocking {
        val llm = FakeLlm(listOf("```json\n{\"answer\":\"完成了。\"}\n```"))

        val result = loop(llm).run(task())

        assertEquals("完成了。", (result as OfficeRunResult.Answered).text)
    }

    // ── 提示词 ──────────────────────────────────────────────────

    @Test
    fun `提示词包含全部工具说明`() = runBlocking {
        val llm = FakeLlm(listOf("""{"answer":"好。"}"""))

        loop(llm).run(task())

        val prompt = llm.prompts[0]
        // ★ 工具说明从枚举生成 —— 手写的那份会在加工具时漂移，
        //   而漂移的表现是"模型永远不用那个新工具"，没人会发现
        com.pocketagent.filelogic.OfficeTool.entries.forEach {
            assertTrue("提示词要含工具 ${it.id}", prompt.contains(it.id))
        }
    }

    @Test
    fun `提示词写明产出目录约束`() = runBlocking {
        val llm = FakeLlm(listOf("""{"answer":"好。"}"""))

        loop(llm).run(task(policy = WorkspacePolicy(outputSubdir = "产物")))

        val prompt = llm.prompts[0]
        assertTrue("提示词要说明产出目录", prompt.contains("产物"))
    }

    @Test
    fun `提示词包含用户任务原文`() = runBlocking {
        val llm = FakeLlm(listOf("""{"answer":"好。"}"""))

        loop(llm).run(task(input = "把这堆数据整理成 Excel"))

        assertTrue(llm.prompts[0].contains("把这堆数据整理成 Excel"))
    }

    @Test
    fun `第一轮提示词不含已执行操作`() = runBlocking {
        val llm = FakeLlm(listOf("""{"answer":"好。"}"""))

        loop(llm).run(task())

        assertTrue("第一轮还没执行过任何东西", !llm.prompts[0].contains("已执行的操作"))
    }
}
