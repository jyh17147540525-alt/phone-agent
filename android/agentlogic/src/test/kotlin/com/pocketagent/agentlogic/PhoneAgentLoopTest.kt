package com.pocketagent.agentlogic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机操作循环的测试。
 *
 * ⚠️ 这些断言针对的**全是静默失败**：本层出错时不抛异常、真机上也不报错，
 * 只会让助理多点一下、或者反复去撞安全墙。
 */
class PhoneAgentLoopTest {

    // ── 脚手架 ─────────────────────────────────────────────────────

    private val toolSpec = AgentToolSpec(
        name = "screen_read",
        description = "读屏",
        parametersJsonSchema = """{"type":"object"}""",
    )

    private fun task(maxSteps: Int = 12) = PhoneTask(
        id = "t-1",
        userInput = "给妈妈发一条消息",
        systemPrompt = "你是手机助理",
        tools = listOf(toolSpec),
        maxSteps = maxSteps,
    )

    /** 按剧本逐轮返回的假模型。剧本用完就返回 Final。 */
    private class ScriptedLlm(private val replies: List<AgentTurnReply>) : AgentLlmPort {
        var calls = 0
            private set

        override suspend fun complete(request: AgentTurnRequest): AgentTurnReply {
            val r = replies.getOrElse(calls) { AgentTurnReply.Final("剧本用完") }
            calls++
            return r
        }
    }

    /** 记录所有调用并按剧本返回结果的假执行器。 */
    private class RecordingRunner(private val outcomes: List<AgentToolResult>) : AgentToolRunner {
        val seen = mutableListOf<AgentToolCall>()

        override suspend fun run(call: AgentToolCall): AgentToolResult {
            seen += call
            return outcomes.getOrElse(seen.size - 1) { AgentToolResult.Ok("默认成功") }
        }
    }

    private fun guard(turns: Int = 12) =
        BudgetGuard(AgentBudget(maxTurns = turns, maxDurationMs = 300_000L))

    private fun call(name: String = "screen_read", args: String = "{}", id: String = "c1") =
        AgentToolCall(id = id, name = name, argumentsJson = args)

    // ── 基本流程 ───────────────────────────────────────────────────

    @Test
    fun `调一次工具再回答`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call())),
                AgentTurnReply.Final("已经看到屏幕了"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Ok("微信主界面，42 个节点")))

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue(result is PhoneRunResult.Answered)
        assertEquals("已经看到屏幕了", (result as PhoneRunResult.Answered).text)
        assertEquals(1, runner.seen.size)
    }

    @Test
    fun `没有调用直接回答也算成功`() = runTest {
        val llm = ScriptedLlm(listOf(AgentTurnReply.Final("这个我做不到")))
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue(result is PhoneRunResult.Answered)
        assertTrue(runner.seen.isEmpty())
    }

    @Test
    fun `多轮工具调用按顺序执行`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call(args = """{"a":1}""", id = "c1"))),
                AgentTurnReply.Calls(listOf(call(args = """{"a":2}""", id = "c2"))),
                AgentTurnReply.Final("好了"),
            ),
        )
        val runner = RecordingRunner(emptyList())

        PhoneAgentLoop(llm, runner, guard()).run(task())

        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), runner.seen.map { it.argumentsJson })
    }

    // ── 预算与步数 ─────────────────────────────────────────────────

    @Test
    fun `步数用完不算失败`() = runTest {
        // 模型永远在调工具，永不回答 —— 必须被步数拦住
        val llm = ScriptedLlm(List(5) { AgentTurnReply.Calls(listOf(call(args = """{"n":$it}"""))) })
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard(turns = 99)).run(task(maxSteps = 3))

        assertTrue("步数用完应当是 StepLimitReached，而不是 Stopped", result is PhoneRunResult.StepLimitReached)
        assertEquals(3, (result as PhoneRunResult.StepLimitReached).steps)
    }

    @Test
    fun `预算轮次超限时中止`() = runTest {
        val llm = ScriptedLlm(List(10) { AgentTurnReply.Calls(listOf(call(args = """{"n":$it}"""))) })
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard(turns = 2)).run(task(maxSteps = 99))

        assertTrue(result is PhoneRunResult.Stopped)
        assertEquals(PauseReason.BUDGET, (result as PhoneRunResult.Stopped).pause)
    }

    // ── 保护 3：重复调用 ───────────────────────────────────────────

    /**
     * ★ 模型卡住时的典型表现是**发出完全相同的调用** —— 它以为上次没成功。
     *   没有这条，循环会转到预算耗尽，而用户看到的是"助理转了 8 轮什么也没做"。
     */
    @Test
    fun `连续相同调用判定卡死`() = runTest {
        val same = AgentTurnReply.Calls(listOf(call(args = """{"x":1}""")))
        val llm = ScriptedLlm(List(10) { same })
        val runner = RecordingRunner(listOf(AgentToolResult.Failed("没找到")))

        val result = PhoneAgentLoop(llm, runner, guard(turns = 99)).run(task(maxSteps = 99))

        assertTrue(result is PhoneRunResult.Stopped)
        assertEquals(PauseReason.LOOP_DETECTED, (result as PhoneRunResult.Stopped).pause)
        assertTrue(result.reason.contains("卡死"))
    }

    @Test
    fun `参数不同不算重复`() = runTest {
        val llm = ScriptedLlm(List(6) { i -> AgentTurnReply.Calls(listOf(call(args = """{"n":$i}"""))) })
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard(turns = 99)).run(task(maxSteps = 3))

        // 步数用完，而不是被判卡死
        assertTrue(result is PhoneRunResult.StepLimitReached)
    }

    /**
     * ★ 参数**顺序不同**是同一个调用。
     *
     * 直接比原串会把 `{"a":1,"b":2}` 与 `{"b":2,"a":1}` 当成两次不同调用 ——
     * 于是重复检测**失效**，而失效的表现是"卡死没检出，转到预算耗尽"。
     */
    @Test
    fun `参数顺序不同仍算重复`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call(args = """{"a":1,"b":2}"""))),
                AgentTurnReply.Calls(listOf(call(args = """{"b":2,"a":1}"""))),
                AgentTurnReply.Calls(listOf(call(args = """{"a":1,"b":2}"""))),
                AgentTurnReply.Calls(listOf(call(args = """{"b":2,"a":1}"""))),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Failed("没找到")))

        val result = PhoneAgentLoop(llm, runner, guard(turns = 99)).run(task(maxSteps = 99))

        assertTrue("参数顺序不同应当仍被判为重复", result is PhoneRunResult.Stopped)
        assertEquals(PauseReason.LOOP_DETECTED, (result as PhoneRunResult.Stopped).pause)
    }

    /** 畸形参数退回原串比较 —— 不能因为"解析失败"就让所有坏参数看起来一样。 */
    @Test
    fun `畸形参数不会互相误判为重复`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call(args = "坏参数甲"))),
                AgentTurnReply.Calls(listOf(call(args = "坏参数乙"))),
                AgentTurnReply.Calls(listOf(call(args = "坏参数丙"))),
            ),
        )
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard(turns = 99)).run(task(maxSteps = 3))

        assertTrue("三个不同参数不该被判卡死", result is PhoneRunResult.StepLimitReached)
    }

    // ── 保护 5：同一批里的重复 ─────────────────────────────────────

    @Test
    fun `同一轮里的重复调用判异常`() = runTest {
        val llm = ScriptedLlm(
            listOf(AgentTurnReply.Calls(listOf(call(args = """{"x":1}"""), call(args = """{"x":1}""", id = "c2")))),
        )
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue(result is PhoneRunResult.Stopped)
        assertEquals(PauseReason.LOOP_DETECTED, (result as PhoneRunResult.Stopped).pause)
    }

    // ── 三分支：Ok / Failed / Blocked ──────────────────────────────

    @Test
    fun `失败结果告诉模型可以重试`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call())),
                AgentTurnReply.Final("好的"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Failed("无障碍服务没开")))

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        val trace = (result as PhoneRunResult.Answered).trace
        assertTrue("失败要记进 trace", trace.any { it.contains("无障碍服务没开") })
    }

    /**
     * ═══════════════════════════════════════════════════════════════
     * ★★ 本文件最重要的断言
     * ═══════════════════════════════════════════════════════════════
     *
     * 安全拒绝**不是**"这次没成功"，是"不许做"。把它当反馈喂回去的后果是：
     * 模型看到"被拒绝了"，于是换个坐标、改个描述再试一次 ——
     * 而那就是在反复撞击安全边界。
     */
    @Test
    fun `安全拒绝必须明确要求不要重试`() = runTest {
        val llm = CapturingLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call())),
                AgentTurnReply.Final("我停下"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Blocked("当前页面是支付页")))

        PhoneAgentLoop(llm, runner, guard()).run(task())

        val fedBack = llm.messages.last().filterIsInstance<AgentMessage.ToolResult>().single()
        assertTrue("必须告诉模型不要重试：${fedBack.text}", fedBack.text.contains("不要重试"))
        assertTrue("必须带上原因", fedBack.text.contains("支付页"))
        assertTrue("必须要求它转达用户", fedBack.text.contains("告诉用户"))
    }

    @Test
    fun `技术失败不要求模型停止重试`() = runTest {
        val llm = CapturingLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call())),
                AgentTurnReply.Final("好的"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Failed("读屏超时")))

        PhoneAgentLoop(llm, runner, guard()).run(task())

        val fedBack = llm.messages.last().filterIsInstance<AgentMessage.ToolResult>().single()
        assertFalse("技术故障不该禁止重试", fedBack.text.contains("不要重试"))
        assertTrue(fedBack.text.contains("可以换个方式重试"))
    }

    /**
     * ★★ 同一工具被安全拦下两次 ⇒ **立即中止整个任务**。
     *
     * 这一条把"用户的钱和注意力"从"模型的自我纠正能力"里赎回来：
     * 允许它试第二次已经是宽容，再往后就是在帮用户撞墙。
     */
    @Test
    fun `同一工具被拦两次就中止整个任务`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call(id = "c1"))),
                AgentTurnReply.Calls(listOf(call(id = "c2"))),
                AgentTurnReply.Final("我不停"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Blocked("支付页"), AgentToolResult.Blocked("支付页")))

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue(result is PhoneRunResult.Stopped)
        assertEquals(PauseReason.SAFETY_BLOCKED, (result as PhoneRunResult.Stopped).pause)
        assertEquals("不该给它第三次机会", 2, runner.seen.size)
    }

    @Test
    fun `被拦一次后改做别的事仍可继续`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call(name = "android_tap", id = "c1"))),
                AgentTurnReply.Calls(listOf(call(name = "screen_read", id = "c2"))),
                AgentTurnReply.Final("我换个做法"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Blocked("敏感页"), AgentToolResult.Ok("看到了")))

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue("换个工具应当能继续", result is PhoneRunResult.Answered)
    }

    @Test
    fun `不同工具各自计数`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call(name = "android_tap", id = "c1"))),
                AgentTurnReply.Calls(listOf(call(name = "android_swipe", id = "c2"))),
                AgentTurnReply.Final("好"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Blocked("敏感"), AgentToolResult.Blocked("敏感")))

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue("两个不同工具各被拦一次，不该中止", result is PhoneRunResult.Answered)
    }

    // ── 异常与边界 ─────────────────────────────────────────────────

    @Test
    fun `模型抛异常转成可解释状态而不是崩溃`() = runTest {
        val llm = AgentLlmPort { throw RuntimeException("网络断了") }
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue(result is PhoneRunResult.Stopped)
        assertEquals(PauseReason.MODEL_ERROR, (result as PhoneRunResult.Stopped).pause)
        assertTrue(result.reason.contains("网络断了"))
    }

    @Test
    fun `执行器抛异常不会炸掉整个任务`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call())),
                AgentTurnReply.Final("我记下了"),
            ),
        )
        val runner = AgentToolRunner { throw IllegalStateException("接线错了") }

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue("执行器异常应当被兜住", result is PhoneRunResult.Answered)
    }

    @Test
    fun `既无调用也无答复时反馈给模型而不是当成完成`() = runTest {
        val llm = CapturingLlm(
            listOf(
                AgentTurnReply.Calls(emptyList()),
                AgentTurnReply.Final("这下有了"),
            ),
        )
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        assertTrue(result is PhoneRunResult.Answered)
        val fedBack = llm.messages.last().filterIsInstance<AgentMessage.ToolResult>().single()
        assertTrue("要说清它没给东西", fedBack.text.contains("没有返回任何工具调用"))
    }

    /**
     * ★ 空答复**不能**静默变成空卡片 —— 那会让用户以为界面坏了。
     */
    @Test
    fun `空答复被标注而不是留下空白`() = runTest {
        val llm = ScriptedLlm(listOf(AgentTurnReply.Final("")))
        val runner = RecordingRunner(emptyList())

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        val text = (result as PhoneRunResult.Answered).text
        assertTrue("空答复要有兜底文案", text.isNotBlank())
    }

    @Test
    fun `历史里带上了工具调用与结果`() = runTest {
        val llm = CapturingLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call(id = "c1"))),
                AgentTurnReply.Final("好了"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Ok("看到微信")))

        PhoneAgentLoop(llm, runner, guard()).run(task())

        val msgs = llm.messages.last()
        // 首条是 System，末条是最后的 User/结果 —— 中间应当有 Assistant(calls) 与 ToolResult
        assertTrue("历史里要有助手那一轮的调用", msgs.any { it is AgentMessage.Assistant && it.calls.isNotEmpty() })
        val tr = msgs.filterIsInstance<AgentMessage.ToolResult>().single()
        assertEquals("c1", tr.callId)
        assertEquals("screen_read", tr.name)
        assertEquals("看到微信", tr.text)
    }
    @Test
    fun `每一轮都带上了工具声明`() = runTest {
        val llm = CapturingLlm(listOf(AgentTurnReply.Final("好")))
        val runner = RecordingRunner(emptyList())

        PhoneAgentLoop(llm, runner, guard()).run(task())

        assertEquals(listOf(toolSpec), llm.requests.single().tools)
    }

    @Test
    fun `模型同时说话与调工具时话不丢`() = runTest {
        val llm = ScriptedLlm(
            listOf(
                AgentTurnReply.Calls(listOf(call()), text = "我先看一眼屏幕"),
                AgentTurnReply.Final("好了"),
            ),
        )
        val runner = RecordingRunner(listOf(AgentToolResult.Ok("看到了")))

        val result = PhoneAgentLoop(llm, runner, guard()).run(task())

        val trace = (result as PhoneRunResult.Answered).trace
        assertTrue("模型的话要进 trace", trace.any { it.contains("我先看一眼屏幕") })
    }

    @Test
    fun `循环不读时钟（时间可由外部注入）`() = runTest {
        var now = 0L
        val llm = ScriptedLlm(listOf(AgentTurnReply.Final("好")))
        val runner = RecordingRunner(emptyList())

        // 注入的时钟直接跳过时长预算
        val loop = PhoneAgentLoop(llm, runner, guard(), clock = { now })

        val result = loop.run(task())
        assertTrue(result is PhoneRunResult.Answered)
    }

    // ── 捕获请求的假模型 ───────────────────────────────────────────

    private class CapturingLlm(private val replies: List<AgentTurnReply>) : AgentLlmPort {
        val requests = mutableListOf<AgentTurnRequest>()
        val messages: List<List<AgentMessage>> get() = requests.map { it.messages }

        override suspend fun complete(request: AgentTurnRequest): AgentTurnReply {
            requests += request
            return replies.getOrElse(requests.size - 1) { AgentTurnReply.Final("剧本用完") }
        }
    }
}
