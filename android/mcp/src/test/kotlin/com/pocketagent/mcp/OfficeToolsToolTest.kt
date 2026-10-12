package com.pocketagent.mcp

import com.pocketagent.agentlogic.OfficeToolExecutor
import com.pocketagent.agentlogic.OfficeToolOutcome
import com.pocketagent.filelogic.OfficeRequest
import com.pocketagent.filelogic.WorkspacePolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `office_tools` / `office_run`：工作区缺失的引导、**校验器真的是唯一一份**、
 * 被拒时执行器没被碰过、args 形状。
 *
 * ⚠️ 这里刻意**不 stub `OfficeToolValidator`** —— 用真实现。
 *    本组工具存在的意义之一就是"校验不许有第二份"，
 *    用一个假校验器测它，等于把这条纪律测掉了。
 */
class OfficeToolsToolTest {

    private val executed = mutableListOf<OfficeRequest>()

    /** 记录被执行的请求。任何一次调用都会留下痕迹 —— 这是"被拒时没碰执行器"的依据。 */
    private inner class FakeExecutor(
        private val outcome: (OfficeRequest) -> OfficeToolOutcome = { OfficeToolOutcome.Ok("done") },
    ) : OfficeToolExecutor {
        override suspend fun execute(request: OfficeRequest): OfficeToolOutcome {
            executed += request
            return outcome(request)
        }
    }

    private fun port(workspace: OfficeWorkspace?): OfficeWorkspacePort =
        OfficeWorkspacePort { workspace }

    private val defaultWorkspace = OfficeWorkspace(name = "测试目录", policy = WorkspacePolicy())

    private fun runArgs(tool: String, vararg args: Pair<String, String>) = buildJsonObject {
        put("tool", tool)
        putJsonObject("args") { args.forEach { (k, v) -> put(k, v) } }
    }

    // ── ① office_tools ──────────────────────────────────────────

    @Test
    fun `没有工作区时给出授权引导而不是干巴巴的失败`() = runTest {
        val outcome = OfficeToolsTool(port(null)).call(buildJsonObject { })
        assertTrue(outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue("应说明为什么：$reason", reason.contains("没有授权"))
        assertTrue("应给出下一步：$reason", reason.contains("第 0 档能力"))
    }

    @Test
    fun `有工作区时列出全部四个工具与产出目录`() = runTest {
        val outcome = OfficeToolsTool(port(defaultWorkspace)).call(buildJsonObject { })
        assertTrue(outcome is ToolOutcome.Success)
        val text = (outcome as ToolOutcome.Success).text
        for (id in listOf("list_files", "read_text", "write_text", "make_table")) {
            assertTrue("应列出 $id：$text", text.contains(id))
        }
        assertTrue("应说明产出目录：$text", text.contains("输出/"))
        // 工具清单从枚举生成 —— 加工具时这里会跟着变，不需要手改
        val tools = outcome.structured!!.jsonObject["tools"]!!.toString()
        assertEquals("应恰好四个工具", 4, Regex("\"id\"").findAll(tools).count())
    }

    @Test
    fun `office_tools 的参数提示与校验器读的键名一致`() = runTest {
        val outcome = OfficeToolsTool(port(defaultWorkspace)).call(buildJsonObject { })
        val text = (outcome as ToolOutcome.Success).text
        // 校验器读的键：path / max_bytes / content / format / header / rows
        for (key in listOf("path", "content", "header", "rows", "format")) {
            assertTrue("提示里应出现键名 $key：$text", text.contains(key))
        }
    }

    // ── ② ★ 校验是唯一一份（路径穿越被真校验器拦下）──────────────

    @Test
    fun `路径穿越被拒 —— 且执行器一次都没被碰过`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val outcome = tool.call(runArgs("read_text", "path" to "../../etc/passwd"))
        assertTrue("应失败：$outcome", outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue("应说明原因：$reason", reason.contains("路径不合法"))
        assertTrue("应提示可修正：$reason", reason.contains("修正"))
        assertTrue("执行器绝不能被调用", executed.isEmpty())
    }

    @Test
    fun `绝对路径被拒`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        assertTrue(tool.call(runArgs("read_text", "path" to "/data/local/tmp/x")).let {
            it is ToolOutcome.Failed && it.reason.contains("不合法")
        })
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `写操作路径不带产出目录前缀被拒`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val outcome = tool.call(runArgs("write_text", "path" to "报告.md", "content" to "x"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue("应指出产出目录要求", (outcome as ToolOutcome.Failed).reason.contains("输出/"))
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `allowWrite 为 false 时写操作被拒`() = runTest {
        val ro = OfficeWorkspace(name = "只读", policy = WorkspacePolicy(allowWrite = false))
        val tool = OfficeRunTool(port(ro), FakeExecutor())
        val outcome = tool.call(runArgs("write_text", "path" to "输出/a.md", "content" to "x"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue(executed.isEmpty())
    }

    // ── ③ 正常路径 ──────────────────────────────────────────────

    @Test
    fun `读文本正常路径 —— 请求带上了 max_bytes`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val outcome = tool.call(runArgs("read_text", "path" to "笔记.md", "max_bytes" to "1024"))
        assertTrue(outcome is ToolOutcome.Success)
        assertEquals(1, executed.size)
        val req = executed.single() as OfficeRequest.ReadText
        assertEquals("笔记.md", req.relativePath)
        assertEquals(1024, req.maxBytes)
    }

    @Test
    fun `写文本正常路径 —— 落在产出目录下`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val outcome = tool.call(runArgs("write_text", "path" to "输出/报告.md", "content" to "内容"))
        assertTrue(outcome is ToolOutcome.Success)
        val req = executed.single() as OfficeRequest.WriteText
        assertEquals("输出/报告.md", req.relativePath)
        assertEquals("内容", req.content)
    }

    @Test
    fun `make_table 解析 header 与 rows`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val outcome = tool.call(
            runArgs(
                "make_table",
                "path" to "输出/表.csv",
                "header" to "名称,数量",
                "rows" to "苹果,3;香蕉,5",
            ),
        )
        assertTrue(outcome is ToolOutcome.Success)
        val req = executed.single() as OfficeRequest.MakeTable
        assertEquals(listOf("名称", "数量"), req.header)
        assertEquals(listOf(listOf("苹果", "3"), listOf("香蕉", "5")), req.rows)
    }

    @Test
    fun `数字型参数按字面量接受`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val payload = buildJsonObject {
            put("tool", "read_text")
            putJsonObject("args") {
                put("path", "a.txt")
                put("max_bytes", JsonPrimitive(2048)) // 数字，不是字符串
            }
        }
        val outcome = tool.call(payload)
        assertTrue("数字应当被接受：$outcome", outcome is ToolOutcome.Success)
        assertEquals(2048, (executed.single() as OfficeRequest.ReadText).maxBytes)
    }

    // ── ④ 参数形状 ──────────────────────────────────────────────

    @Test
    fun `未知工具被拒并列出可用工具`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val outcome = tool.call(runArgs("rm_rf", "path" to "."))
        assertTrue(outcome is ToolOutcome.Failed)
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue("应列出可用工具：$reason", reason.contains("read_text"))
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `缺少 tool 参数被拒`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        assertTrue(tool.call(buildJsonObject { }) is ToolOutcome.Failed)
        assertTrue(executed.isEmpty())
    }

    @Test
    fun `args 里出现对象被拒（不聪明地转换）`() = runTest {
        val tool = OfficeRunTool(port(defaultWorkspace), FakeExecutor())
        val payload = buildJsonObject {
            put("tool", "read_text")
            putJsonObject("args") {
                putJsonObject("path") { put("nested", "x") }
            }
        }
        val outcome = tool.call(payload)
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("不是标量"))
        assertTrue(executed.isEmpty())
    }

    // ── ⑤ 执行器异常不许外抛 ────────────────────────────────────

    @Test
    fun `执行器抛异常时转成可读的失败而不是崩溃`() = runTest {
        val tool = OfficeRunTool(
            port(defaultWorkspace),
            FakeExecutor { error("底层炸了") },
        )
        val outcome = tool.call(runArgs("read_text", "path" to "a.txt"))
        assertTrue("必须兜住异常：$outcome", outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("异常"))
    }

    @Test
    fun `执行器报失败时原样透出原因`() = runTest {
        val tool = OfficeRunTool(
            port(defaultWorkspace),
            FakeExecutor { OfficeToolOutcome.Failed("文件不是 UTF-8 文本") },
        )
        val outcome = tool.call(runArgs("read_text", "path" to "a.bin"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("UTF-8"))
    }

    // ── ⑥ schema ────────────────────────────────────────────────

    @Test
    fun `office_run 的 schema 把工具 id 列成枚举`() = runTest {
        val schema = OfficeRunTool(port(defaultWorkspace), FakeExecutor()).inputSchema().toString()
        assertTrue(schema.contains("list_files"))
        assertTrue(schema.contains("make_table"))
        assertTrue("应说明 args 值写成字符串", schema.contains("字符串"))
    }

    @Test
    fun `office_tools 的 schema 无参数`() = runTest {
        val schema = OfficeToolsTool(port(defaultWorkspace)).inputSchema()
        assertFalse(schema.toString().contains("required"))
    }
}
