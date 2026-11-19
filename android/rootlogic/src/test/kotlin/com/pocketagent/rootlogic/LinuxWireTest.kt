package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LINUX 动词的三条出口必须**指向同一条命令**：`su -c` 的字符串、常驻通道的数组、
 * 以及协议入口。这里同时钉住"服务端会重新校验"——客户端能发什么，不该是最后一道闸。
 */
class LinuxWireTest {

    private val chroot = LinuxWire.Request(
        kind = LinuxKind.ROOT_CHROOT,
        rootfs = "/data/local/tmp/pa-root",
        workDir = "/data/local/tmp/pa-work",
        networkOff = false,
        inner = listOf("python3", "-c", "print(1)"),
    )

    @Test
    fun `编码形状固定`() {
        assertEquals(
            listOf(
                "run", "chroot", "/data/local/tmp/pa-root", "/data/local/tmp/pa-work", "on",
                "python3", "-c", "print(1)",
            ),
            LinuxWire.encode(chroot),
        )
    }

    @Test
    fun `proot 两种都编码成 proot`() {
        assertEquals("proot", LinuxWire.encode(chroot.copy(kind = LinuxKind.BUNDLED_PROOT))[1])
        assertEquals("proot", LinuxWire.encode(chroot.copy(kind = LinuxKind.EXTERNAL_PROOT))[1])
    }

    @Test
    fun `解码能还原原请求`() {
        val decoded = LinuxWire.decode(LinuxWire.encode(chroot))
        assertEquals(chroot.rootfs, decoded.rootfs)
        assertEquals(chroot.workDir, decoded.workDir)
        assertEquals(chroot.inner, decoded.inner)
        assertEquals(false, decoded.networkOff)
        // proot 编回来会落成 BUNDLED_PROOT —— 线上形状不区分"自带/外部"，那是本地事实
        assertEquals(LinuxKind.ROOT_CHROOT, decoded.kind)
    }

    @Test
    fun `网络开关往返`() {
        val off = chroot.copy(networkOff = true)
        assertTrue(LinuxWire.decode(LinuxWire.encode(off)).networkOff)
    }

    @Test
    fun `形状不对一律拒绝`() {
        assertThrows(LinuxRejected::class.java) { LinuxWire.decode(listOf("run", "chroot")) }
        assertThrows(LinuxRejected::class.java) { LinuxWire.decode(listOf("exec", "chroot", "/r", "/w", "on", "id")) }
        assertThrows(LinuxRejected::class.java) { LinuxWire.decode(listOf("run", "docker", "/r", "/w", "on", "id")) }
        assertThrows(LinuxRejected::class.java) { LinuxWire.decode(listOf("run", "chroot", "/r", "/w", "maybe", "id")) }
    }

    @Test
    fun `路径校验挡住相对路径与 shell 元字符`() {
        // 相对路径
        assertThrows(LinuxRejected::class.java) { LinuxWire.safePath("rootfs", "rootfs") }
        // 上跳
        assertThrows(LinuxRejected::class.java) { LinuxWire.safePath("rootfs", "/a/../b") }
        // 空白（su 那条路要拼 shell 字符串）
        assertThrows(LinuxRejected::class.java) { LinuxWire.safePath("rootfs", "/a b") }
        // 分号与引号
        assertThrows(LinuxRejected::class.java) { LinuxWire.safePath("rootfs", "/a;rm") }
        assertThrows(LinuxRejected::class.java) { LinuxWire.safePath("rootfs", "/a'b") }
        assertThrows(LinuxRejected::class.java) { LinuxWire.safePath("rootfs", "/a\"b") }
        assertEquals("/data/x", LinuxWire.safePath("rootfs", "/data/x"))
    }

    // ── 三条出口 ────────────────────────────────────────────────────────────

    @Test
    fun `通道那条路直接发编码后的数组`() {
        val call = DaemonCalls.of(
            RootAction.LinuxShell(
                kind = LinuxKind.ROOT_CHROOT,
                rootfs = "/data/local/tmp/pa-root",
                workDir = "/data/local/tmp/pa-work",
                inner = listOf("id"),
            ),
        )
        assertEquals(RootVerb.LINUX, call.verb)
        assertEquals(LinuxWire.encode(chroot.copy(inner = listOf("id"))), call.argv)
    }

    @Test
    fun `su 那条路拼成一条完整命令`() {
        val payload = RootCommands.payload(
            RootAction.LinuxShell(
                kind = LinuxKind.ROOT_CHROOT,
                rootfs = "/data/local/tmp/pa-root",
                workDir = "/data/local/tmp/pa-work",
                inner = listOf("id"),
            ),
        )
        // 经 /bin/sh 转一手再 exec —— 见 LinuxCommandsTest 里那条同因的改动
        // 每个 token 都单引号包起来：payload 是交给 `su -c "…"` 的**一条**字符串，
        // 不引的话 `-c exec 'id'` 会被外层 shell 拆成 `sh -c exec` + `$0=id` —— 退出码 0、输出空。
        // 这里只钉结构，不钉转义后的完整字形（那种断言改一处转义就全红，信息量还低）。
        assertTrue(payload, payload.startsWith("'chroot' '/data/local/tmp/pa-root' '/bin/sh' '-c'"))
        assertTrue(payload, payload.contains("exec"))
        assertTrue(payload, payload.contains("'id'"))
    }

    @Test
    fun `root 档可以真断网`() {
        val payload = RootCommands.payload(
            RootAction.LinuxShell(
                kind = LinuxKind.ROOT_CHROOT,
                rootfs = "/data/local/tmp/pa-root",
                workDir = "/data/local/tmp/pa-work",
                inner = listOf("id"),
                networkOff = true,
            ),
        )
        assertTrue("root 档的断网是 unshare -n：$payload", payload.startsWith("'unshare' '-n' 'chroot'"))
    }

    @Test
    fun `proot 那条路不会假装断网`() {
        // proot 没有 network namespace —— 文案与实现都不该声称能断
        val payload = RootCommands.payload(
            RootAction.LinuxShell(
                kind = LinuxKind.EXTERNAL_PROOT,
                rootfs = "/data/local/tmp/pa-root",
                workDir = "/data/local/tmp/pa-work",
                inner = listOf("id"),
                networkOff = true,
            ),
        )
        assertTrue("proot 不该出现 unshare：$payload", !payload.startsWith("unshare"))
    }

    @Test
    fun `能力归属是最重的那一条`() {
        val action = RootAction.LinuxShell(
            kind = LinuxKind.ROOT_CHROOT,
            rootfs = "/data/local/tmp/pa-root",
            workDir = "/data/local/tmp/pa-work",
            inner = listOf("id"),
        )
        assertEquals("shell.root", RootCommands.capabilityOf(action))
        assertTrue(CapabilityMatrix.all.any { it.id == "shell.root" })
    }
}
