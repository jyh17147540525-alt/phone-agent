package com.pocketagent.assistant

import com.pocketagent.mcp.LinuxAvailability
import com.pocketagent.mcp.LinuxOpsPort
import com.pocketagent.mcp.LinuxReadResult
import com.pocketagent.mcp.LinuxRunResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后端阶梯：**能借 Termux 就借，借不到用内置引擎**。
 *
 * 这里钉的是"用户中途装了 Termux"这类变化：阶梯必须当场重新判断，
 * 而不是开机选一次就定终身 —— 否则会出现"我明明装了它却说没装"。
 */
class LadderLinuxOpsTest {

    private class Fake(
        private val status: () -> LinuxAvailability,
        private val name: String,
    ) : LinuxOpsPort {
        val runs = mutableListOf<List<String>>()

        // ⚠️ 参数名不要叫 availability —— 与接口方法同名会变成自己调自己（StackOverflowError）
        override suspend fun availability(): LinuxAvailability = status()

        override suspend fun run(argv: List<String>, timeoutSec: Int): LinuxRunResult {
            runs += argv
            return LinuxRunResult(0, "$name:${argv.joinToString(" ")}", "", 1)
        }

        override suspend fun read(path: String, maxBytes: Int): LinuxReadResult =
            LinuxReadResult("$name:$path".toByteArray(), false)
    }

    private fun ready(explain: String) = { LinuxAvailability(true, explain) }
    private fun notReady(explain: String) = { LinuxAvailability(false, explain) }

    @Test
    fun `Termux 可用时优先走 Termux`() = runBlocking {
        val termux = Fake(ready("Termux 通道可用"), "termux")
        val builtin = Fake(ready("内置引擎可用"), "builtin")

        val result = LadderLinuxOps(termux, builtin).run(listOf("git", "--version"), 10)

        assertEquals("termux:git --version", result.stdout)
        assertTrue(builtin.runs.isEmpty())
    }

    @Test
    fun `Termux 不可用时降级到内置引擎`() = runBlocking {
        val termux = Fake(notReady("没装 Termux"), "termux")
        val builtin = Fake(ready("内置引擎可用"), "builtin")

        val result = LadderLinuxOps(termux, builtin).run(listOf("python3", "-c", "print(1)"), 10)

        assertEquals("builtin:python3 -c print(1)", result.stdout)
        assertTrue(termux.runs.isEmpty())
    }

    @Test
    fun `降级时把两边的差别写在文案里`() = runBlocking {
        val ladder = LadderLinuxOps(
            termux = Fake(notReady("没装 Termux"), "termux"),
            builtin = Fake(ready("内置引擎可用"), "builtin"),
        )
        val availability = ladder.availability()

        assertTrue(availability.ready)
        assertTrue("要说清这是内置引擎", availability.explain.contains("内置引擎"))
        assertTrue("要说清装不了包", availability.explain.contains("装不了"))
        assertTrue("要把 Termux 的原因带上", availability.explain.contains("没装 Termux"))
    }

    @Test
    fun `两边都不可用时如实说不可用`() = runBlocking {
        val ladder = LadderLinuxOps(
            termux = Fake(notReady("没装 Termux"), "termux"),
            builtin = Fake(notReady("引擎关着"), "builtin"),
        )
        val availability = ladder.availability()

        assertFalse(availability.ready)
        assertTrue(availability.explain.contains("引擎关着"))
        assertTrue(availability.explain.contains("没装 Termux"))
    }

    @Test
    fun `root 容器可用时它排在最前面`() = runBlocking {
        val root = Fake(ready("root 容器可用"), "root")
        val termux = Fake(ready("Termux 通道可用"), "termux")
        val builtin = Fake(ready("内置引擎可用"), "builtin")

        val result = LadderLinuxOps(termux, builtin, root).run(listOf("id"), 5)

        assertEquals("root:id", result.stdout)
        assertTrue(termux.runs.isEmpty())
        assertTrue(builtin.runs.isEmpty())
    }

    @Test
    fun `root 没有根文件系统时降级到 Termux`() = runBlocking {
        val root = Fake(notReady("root 有了，但还没有根文件系统"), "root")
        val termux = Fake(ready("Termux 通道可用"), "termux")
        val builtin = Fake(ready("内置引擎可用"), "builtin")

        val result = LadderLinuxOps(termux, builtin, root).run(listOf("id"), 5)

        assertEquals("termux:id", result.stdout)
        assertTrue(root.runs.isEmpty())
    }

    @Test
    fun `三档都不可用时文案里三边都有`() = runBlocking {
        val ladder = LadderLinuxOps(
            termux = Fake(notReady("没装 Termux"), "termux"),
            builtin = Fake(notReady("引擎关着"), "builtin"),
            root = Fake(notReady("没有 root"), "root"),
        )
        val availability = ladder.availability()

        assertFalse(availability.ready)
        assertTrue(availability.explain.contains("引擎关着"))
        assertTrue(availability.explain.contains("没装 Termux"))
        assertTrue(availability.explain.contains("没有 root"))
    }

    @Test
    fun `中途装上 Termux 后下一次就会改走 Termux`() = runBlocking {
        var termuxReady = false
        val termux = Fake({ LinuxAvailability(termuxReady, if (termuxReady) "可用" else "没装") }, "termux")
        val builtin = Fake(ready("内置引擎可用"), "builtin")
        val ladder = LadderLinuxOps(termux, builtin)

        ladder.run(listOf("python3", "--version"), 5)
        assertTrue("第一次应走内置", builtin.runs.isNotEmpty())

        termuxReady = true
        ladder.run(listOf("python3", "--version"), 5)
        assertEquals("装上之后就应该改走 Termux", 1, termux.runs.size)
    }
}
