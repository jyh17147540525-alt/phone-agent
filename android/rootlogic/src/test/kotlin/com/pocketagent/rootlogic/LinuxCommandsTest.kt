package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令构造与参数校验 —— 这一层决定"什么能进容器"。
 *
 * 全部离线：一个字节都不发给系统。真机上"越界能不能成"靠内核兜底，
 * 这里钉的是"我们连试都不该试"。
 */
class LinuxCommandsTest {

    private fun run(vararg argv: String, timeoutSec: Int = 30) =
        LinuxCall.Run(argv.toList(), timeoutSec)

    // ── 能过的命令 ──────────────────────────────────────────────────────────

    @Test
    fun `普通命令原样通过`() {
        assertEquals(
            listOf("python3", "-c", "print(2**100)"),
            LinuxCommands.argv(run("python3", "-c", "print(2**100)")),
        )
        assertEquals(listOf("jq", ".items", "/work/a.json"), LinuxCommands.argv(run("jq", ".items", "/work/a.json")))
    }

    @Test
    fun `代码里的斜杠不会被当成路径`() {
        // `print(1/2)` 里有 `/`，但它是代码不是路径 —— 路径规则不该误伤
        assertEquals(
            listOf("python3", "-c", "print(1/2)"),
            LinuxCommands.argv(run("python3", "-c", "print(1/2)")),
        )
    }

    @Test
    fun `相对路径与 tmp 都允许`() {
        LinuxCommands.argv(run("cp", "a/b.txt", "c.txt"))
        LinuxCommands.argv(run("tar", "czf", "/work/out.tar.gz", "."))
        LinuxCommands.argv(run("ffmpeg", "-i", "/tmp/in.mp3", "/work/out.mp3"))
    }

    // ── 形状 ────────────────────────────────────────────────────────────────

    @Test
    fun `空命令被拒`() {
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(LinuxCall.Run(emptyList())) }
    }

    @Test
    fun `参数太多被拒`() {
        val argv = List(LinuxCommands.MAX_ARGV + 1) { "x" }
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(LinuxCall.Run(argv)) }
    }

    @Test
    fun `单个参数过长被拒`() {
        val argv = listOf("echo", "a".repeat(LinuxCommands.MAX_TOKEN_CHARS + 1))
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(LinuxCall.Run(argv)) }
    }

    @Test
    fun `参数总量过大被拒`() {
        // 每个都在上限内，但总量超了
        val piece = "a".repeat(3_000)
        val argv = listOf("echo") + List(6) { piece }
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(LinuxCall.Run(argv)) }
    }

    @Test
    fun `含 NUL 的参数被拒`() {
        assertThrows(LinuxRejected::class.java) {
            LinuxCommands.argv(LinuxCall.Run(listOf("echo", "a\u0000b")))
        }
    }

    @Test
    fun `空串参数被拒`() {
        assertThrows(LinuxRejected::class.java) {
            LinuxCommands.argv(LinuxCall.Run(listOf("echo", "")))
        }
    }

    // ── 首词 ────────────────────────────────────────────────────────────────

    @Test
    fun `联网工具被拒且理由说清是没网`() {
        for (head in listOf("curl", "wget", "pip", "npm")) {
            val error = assertThrows(LinuxRejected::class.java) {
                LinuxCommands.argv(run(head, "--version"))
            }
            assertTrue("${error.message} 应说明不联网", error.message!!.contains("不联网"))
        }
    }

    @Test
    fun `提权与跳容器的命令被拒`() {
        for (head in listOf("su", "sudo", "chroot", "proot", "mount")) {
            assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(run(head, "-v")) }
        }
    }

    @Test
    fun `不认识的程序被拒`() {
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(run("rmm", "-rf", "/work")) }
    }

    @Test
    fun `白名单里没有大小写变体`() {
        // 不是"看起来像"就行：Python3 / CURL 都不是我们认识的那个程序
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(run("Python3", "-c", "print(1)")) }
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(run("CURL", "http://x")) }
    }

    // ── 路径 ────────────────────────────────────────────────────────────────

    @Test
    fun `点号点号跳目录被拒`() {
        val error = assertThrows(LinuxRejected::class.java) {
            LinuxCommands.argv(run("cat", "/work/../../etc/passwd"))
        }
        assertTrue(error.message!!.contains(".."))
    }

    @Test
    fun `工作区之外的绝对路径被拒`() {
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(run("cat", "/etc/passwd")) }
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(run("rm", "/system/bin/sh")) }
    }

    @Test
    fun `删根会被路径规则拦住`() {
        // `rm -rf /` 的 `/` 不在允许范围内
        assertThrows(LinuxRejected::class.java) { LinuxCommands.argv(run("rm", "-rf", "/")) }
    }

    @Test
    fun `只是字符串里的点号点号不误伤`() {
        // 不是路径的 token（不含分隔符）不该触发路径规则
        assertEquals(
            listOf("python3", "-c", "print('..')"),
            LinuxCommands.argv(run("python3", "-c", "print('..')")),
        )
    }

    // ── 读文件 ──────────────────────────────────────────────────────────────

    @Test
    fun `只能读工作区里的文件`() {
        assertEquals("/work/a.txt", LinuxCommands.readPath("/work/a.txt"))
        assertThrows(LinuxRejected::class.java) { LinuxCommands.readPath("/etc/hosts") }
        assertThrows(LinuxRejected::class.java) { LinuxCommands.readPath("/workx/a.txt") }
        assertThrows(LinuxRejected::class.java) { LinuxCommands.readPath("/work/../x") }
        assertThrows(LinuxRejected::class.java) { LinuxCommands.readPath("") }
    }

    @Test
    fun `读文件大小被夹到上限`() {
        assertEquals(64 * 1024, LinuxCall.Read("/work/a").maxBytes)
        assertEquals(1, LinuxCommands.readBytes(0))
        assertEquals(LinuxCall.MAX_READ_BYTES, LinuxCommands.readBytes(Int.MAX_VALUE))
    }

    // ── 超时 ────────────────────────────────────────────────────────────────

    @Test
    fun `超时被夹在 1 到 60 秒`() {
        assertEquals(1, LinuxCommands.timeoutOf(0))
        assertEquals(1, LinuxCommands.timeoutOf(-5))
        assertEquals(60, LinuxCommands.timeoutOf(999))
        assertEquals(30, LinuxCommands.timeoutOf(30))
    }

    // ── 启动命令 ────────────────────────────────────────────────────────────

    @Test
    fun `proot 的启动命令带挂载与工作目录`() {
        val argv = LinuxCommands.launch(
            kind = LinuxKind.EXTERNAL_PROOT,
            rootfs = "/rootfs",
            workDir = "/data/user/0/p/files/linux/work",
            inner = listOf("python3", "-c", "print(1)"),
        )
        assertEquals(
            listOf(
                "proot", "--rootfs", "/rootfs",
                "-b", "/data/user/0/p/files/linux/work:/work",
                "-w", "/work",
                "python3", "-c", "print(1)",
            ),
            argv,
        )
    }

    @Test
    fun `chroot 的启动命令不需要挂载`() {
        // ★ 必须经容器里的 /bin/sh 转一手：`chroot <root> id` 是拿**宿主**的 PATH 去 exec，
        //   容器里的 busybox applet 一个都不在宿主 PATH 上 ⇒ 退出码 127（真机原样翻过）。
        val argv = LinuxCommands.launch(
            LinuxKind.ROOT_CHROOT, "/rootfs", "/work", listOf("python3", "-c", "print(1)"),
        )
        assertEquals(listOf("chroot", "/rootfs", "/bin/sh", "-c"), argv.dropLast(1))
        val script = argv.last()
        // PATH 必须在容器里重设：chroot 不换环境，宿主的 /system/bin 带进来只会得到 127
        assertTrue(script, script.startsWith("PATH=${LinuxCommands.CONTAINER_PATH}; export PATH; exec "))
        assertTrue(script, script.contains("'python3' '-c' 'print(1)'"))
    }

    @Test
    fun `只有 root 的 chroot 能真断网`() {
        val off = LinuxCommands.launch(
            LinuxKind.ROOT_CHROOT, "/rootfs", "/work", listOf("python3", "-V"), networkOff = true,
        )
        assertEquals("unshare", off.first())

        // proot 没有 network namespace：传了也不装样子
        val prootOff = LinuxCommands.launch(
            LinuxKind.EXTERNAL_PROOT, "/rootfs", "/work", listOf("python3", "-V"), networkOff = true,
        )
        assertTrue(prootOff.none { it == "unshare" })
    }

    @Test
    fun `没有 rootfs 时启动命令当场被拒`() {
        assertThrows(LinuxRejected::class.java) {
            LinuxCommands.launch(LinuxKind.EXTERNAL_PROOT, null, "/work", listOf("echo", "1"))
        }
        assertThrows(LinuxRejected::class.java) {
            LinuxCommands.launch(LinuxKind.ROOT_CHROOT, null, "/work", listOf("echo", "1"))
        }
    }

    @Test
    fun `启动命令不接受空的内层命令`() {
        assertThrows(LinuxRejected::class.java) {
            LinuxCommands.launch(LinuxKind.ROOT_CHROOT, "/rootfs", "/work", emptyList())
        }
    }
}
