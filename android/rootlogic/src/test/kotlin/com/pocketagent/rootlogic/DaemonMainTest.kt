package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 薄壳：**整条栈真的接起来**——薄壳起服务、写发现文件、客户端读文件连上来、
 * 一条 `input tap` 落到（假的）runner 上。
 *
 * 这条用例的价值：它是"真机上第一步会做的事"的离线版本。真机上如果这一步不通，
 * 要么是 SELinux、要么是发现文件权限 —— 而**不是**协议或校验的问题，
 * 因为那些已经在这里被排除了。
 */
class DaemonMainTest {

    /** 通道通的时候**不该**被调到；调到了就说明选路错了。 */
    private object MustNotUseSu : RootShellPort {
        override fun run(argv: List<String>): ShellResult =
            throw AssertionError("通道可用时不该退回 su：$argv")
    }

    private class Recorder(private val resultMap: Map<String, ShellResult> = emptyMap()) : LocalRunner {
        val calls = ArrayList<List<String>>()

        override fun run(argv: List<String>, timeoutMs: Int): ShellResult {
            calls += argv
            return resultMap[argv.firstOrNull() ?: ""] ?: ShellResult(0, "ok", "")
        }
    }

    @Test
    fun `薄壳起服务后 客户端能按发现文件连上来并执行一条点按`() {
        val discovery = File.createTempFile("pa-discovery", ".txt").apply { delete() }
        val rec = Recorder()
        val started = DaemonMain.start(rec, discovery.absolutePath)

        try {
            assertTrue("发现文件必须写成功", started.discoveryWritten)
            assertTrue("端口必须是被系统分配的真实端口", started.port > 0)

            val endpoint = DaemonEndpoint.read(discovery.absolutePath)
            assertNotNull("客户端应当能从发现文件里读到端口与 token", endpoint)
            assertEquals(started.port, endpoint!!.port)
            assertEquals(started.token, endpoint.token)

            val client = PrivilegeSocketPort(port = endpoint.port, token = endpoint.token)
            val actions = RootActions(MustNotUseSu, daemon = client)
            val result = actions.run(RootAction.Tap(11, 22))

            assertEquals(0, result.code)
            assertEquals(listOf(listOf("input", "tap", "11", "22")), rec.calls)
        } finally {
            started.server.stop()
            discovery.delete()
        }
    }

    @Test
    fun `发现文件里的 token 与薄壳生成的一致`() {
        val discovery = File.createTempFile("pa-discovery2", ".txt").apply { delete() }
        val started = DaemonMain.start(Recorder(), discovery.absolutePath, token = "0123456789abcdef")
        try {
            assertEquals("0123456789abcdef", DaemonEndpoint.read(discovery.absolutePath)?.token)
            assertEquals("0123456789abcdef", started.token)
        } finally {
            started.server.stop()
            discovery.delete()
        }
    }

    @Test
    fun `薄壳生成的 token 是 32 位十六进制 且每次不同`() {
        val a = DaemonMain.newToken()
        val b = DaemonMain.newToken()

        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue("两次生成的 token 不该相同", a != b)
    }

    @Test
    fun `发现文件写不进去时如实报告 而不是假装起来了`() {
        // 往一个不存在目录里写：写不进去就必须说写不进去。
        val started = DaemonMain.start(Recorder(), "/绝对不存在的目录/pocketagent-daemon.txt")
        try {
            assertTrue("端口仍然绑上了", started.port > 0)
            assertTrue("但发现文件没写成，必须如实报", !started.discoveryWritten)
        } finally {
            started.server.stop()
        }
    }

    @Test
    fun `读属性的查询走 QUERY 动词`() {
        val discovery = File.createTempFile("pa-discovery3", ".txt").apply { delete() }
        val rec = Recorder(mapOf("getprop" to ShellResult(0, "34", "")))
        val started = DaemonMain.start(rec, discovery.absolutePath)
        try {
            val endpoint = DaemonEndpoint.read(discovery.absolutePath)!!
            val actions = RootActions(
                MustNotUseSu,
                daemon = PrivilegeSocketPort(port = endpoint.port, token = endpoint.token),
            )

            val result = actions.run(RootAction.GetProp("ro.build.version.sdk"))

            assertEquals("34", result.stdout.trim())
            assertEquals(listOf("getprop", "ro.build.version.sdk"), rec.calls.single())
        } finally {
            started.server.stop()
            discovery.delete()
        }
    }
}
