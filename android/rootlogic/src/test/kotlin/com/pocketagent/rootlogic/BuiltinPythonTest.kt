package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 Python 后端的"能做什么"边界。
 *
 * 这里钉的是**诚实**：做不到的（脚本文件、-m、交互）要当场说清，
 * 而不是等执行时抛一个 Python 的报错让用户以为"内置引擎坏了"。
 */
class BuiltinPythonTest {

    @Test
    fun `python3 -c 被接受`() {
        val plan = BuiltinPython.plan(listOf("python3", "-c", "print(2**100)"))
        assertEquals(BuiltinInvocation.Code("print(2**100)"), plan)
    }

    @Test
    fun `python 也认`() {
        assertEquals(
            BuiltinInvocation.Code("print(1)"),
            BuiltinPython.plan(listOf("python", "-c", "print(1)")),
        )
    }

    @Test
    fun `问版本是单独的形态`() {
        assertEquals(BuiltinInvocation.Version, BuiltinPython.plan(listOf("python3", "--version")))
        assertEquals(BuiltinInvocation.Version, BuiltinPython.plan(listOf("python3", "-V")))
    }

    @Test
    fun `其它程序被拒且指出替代路径`() {
        val reject = BuiltinPython.plan(listOf("ls", "-la")) as BuiltinInvocation.Reject
        assertTrue(reject.reason.contains("只支持 python3"))
        assertTrue("要说清别的程序需要什么", reject.reason.contains("Termux") || reject.reason.contains("root"))
    }

    @Test
    fun `脚本文件与模块模式被拒且说明不支持`() {
        for (argv in listOf(
            listOf("python3", "script.py"),
            listOf("python3", "-m", "json.tool"),
            listOf("python3", "-i"),
            listOf("python3"),
        )) {
            val plan = BuiltinPython.plan(argv)
            assertTrue("$argv 应被拒，实际 $plan", plan is BuiltinInvocation.Reject)
        }
    }

    @Test
    fun `空代码与空命令被拒`() {
        assertTrue(BuiltinPython.plan(emptyList()) is BuiltinInvocation.Reject)
        assertTrue(BuiltinPython.plan(listOf("python3", "-c", "  ")) is BuiltinInvocation.Reject)
    }

    @Test
    fun `代码过长被拒`() {
        val long = "x".repeat(BuiltinPython.MAX_CODE_CHARS + 1)
        val reject = BuiltinPython.plan(listOf("python3", "-c", long)) as BuiltinInvocation.Reject
        assertTrue(reject.reason.contains("太长"))
    }

    @Test
    fun `work 映射到真实目录，其余路径原样`() {
        val real = "/data/user/0/com.pocketagent/files/linux/work"
        assertEquals(real, BuiltinPython.realPath("/work", real))
        assertEquals("$real/a.txt", BuiltinPython.realPath("/work/a.txt", real))
        assertEquals("/tmp/x", BuiltinPython.realPath("/tmp/x", real))
    }

    @Test
    fun `真实路径能翻回容器路径`() {
        val real = "/data/user/0/com.pocketagent/files/linux/work"
        assertEquals("/work", BuiltinPython.containerPath(real, real))
        assertEquals("/work/a.txt", BuiltinPython.containerPath("$real/a.txt", real))
        // 工作区之外的不假装能映射
        assertEquals("/data/local/tmp/x", BuiltinPython.containerPath("/data/local/tmp/x", real))
    }

    @Test
    fun `映射带尾斜杠也不出错`() {
        val real = "/work-root/"
        assertEquals("/work-root/a", BuiltinPython.realPath("/work/a", real))
        assertEquals("/work/a", BuiltinPython.containerPath("/work-root/a", real))
    }
}
