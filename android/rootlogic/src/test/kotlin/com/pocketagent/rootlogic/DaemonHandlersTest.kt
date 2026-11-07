package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护进程侧的处理器表。
 *
 * 这些用例的价值在于：它们用**假的 runner** 精确断言"到底会起哪条命令"。
 * 真机上跑错一条命令的代价是"改了用户的系统设置"或"点到了不该点的按钮"，
 * 而这类错误在离线很难靠 review 发现 —— 所以把它钉成断言。
 */
class DaemonHandlersTest {

    private class Recorder(
        private val result: ShellResult = ShellResult(0, "ok", ""),
    ) : LocalRunner {
        val calls = ArrayList<List<String>>()
        var lastTimeout = 0

        override fun run(argv: List<String>, timeoutMs: Int): ShellResult {
            calls += argv
            lastTimeout = timeoutMs
            return result
        }
    }

    private fun invoke(runner: LocalRunner, verb: RootVerb, argv: List<String>): DaemonLoop.WireReply {
        val handlers = DaemonHandlers.standard(runner)
        val handler = handlers.getValue(verb)
        return handler(PrivilegeWire.encodeArgv(argv))
    }

    // ── INJECT ───────────────────────────────────────────────────────────

    @Test
    fun `点按只起一条 input tap`() {
        val rec = Recorder()
        val reply = invoke(rec, RootVerb.INJECT, listOf("tap", "100", "200"))

        assertEquals(listOf(listOf("input", "tap", "100", "200")), rec.calls)
        assertTrue(reply is DaemonLoop.WireReply.Ok)
    }

    @Test
    fun `滑动补齐到 input swipe 五个参数`() {
        val rec = Recorder()
        invoke(rec, RootVerb.INJECT, listOf("swipe", "0", "0", "10", "10", "300"))

        assertEquals(listOf("input", "swipe", "0", "0", "10", "10", "300"), rec.calls.single())
    }

    @Test
    fun `滑动时长越界被夹住 而不是原样传下去`() {
        val rec = Recorder()
        invoke(rec, RootVerb.INJECT, listOf("swipe", "0", "0", "10", "10", "999999"))

        assertEquals("60000", rec.calls.single().last())
    }

    @Test
    fun `文字原样进参数数组 不经 shell`() {
        val rec = Recorder()
        invoke(rec, RootVerb.INJECT, listOf("text", "你好 world; rm -rf /"))

        assertEquals(listOf("input", "text", "你好 world; rm -rf /"), rec.calls.single())
    }

    @Test
    fun `不认识的注入动作直接拒绝 一条命令都不起`() {
        val rec = Recorder()
        val reply = invoke(rec, RootVerb.INJECT, listOf("swipe_from_memory", "1", "2"))

        assertTrue(rec.calls.isEmpty())
        assertTrue(reply is DaemonLoop.WireReply.Refused)
    }

    @Test
    fun `按键不在白名单里拒绝`() {
        val rec = Recorder()
        val reply = invoke(rec, RootVerb.INJECT, listOf("key", "KEYCODE_HOME; reboot"))

        // 参数不合法 → Refused（"这条路走不通"），不是 HANDLER_FAILED（"处理器炸了"）。
        assertTrue("按键名必须过白名单", reply is DaemonLoop.WireReply.Refused)
        assertTrue(rec.calls.isEmpty())
    }

    @Test
    fun `坐标不是整数拒绝`() {
        val rec = Recorder()
        invoke(rec, RootVerb.INJECT, listOf("tap", "abc", "200"))
        assertTrue(rec.calls.isEmpty())
    }

    // ── CAPTURE ──────────────────────────────────────────────────────────

    @Test
    fun `截图命令是 screencap -p 路径`() {
        val rec = Recorder()
        invoke(rec, RootVerb.CAPTURE, listOf("screencap", "/sdcard/a.png"))

        assertEquals(listOf("screencap", "-p", "/sdcard/a.png"), rec.calls.single())
    }

    @Test
    fun `dump 命令是 uiautomator dump 路径 且超时更长`() {
        val rec = Recorder()
        invoke(rec, RootVerb.CAPTURE, listOf("dump", "/data/local/tmp/ui.xml"))

        assertEquals(listOf("uiautomator", "dump", "/data/local/tmp/ui.xml"), rec.calls.single())
        assertEquals(30_000, rec.lastTimeout)
    }

    @Test
    fun `路径带分号拒绝`() {
        val rec = Recorder()
        invoke(rec, RootVerb.CAPTURE, listOf("screencap", "/sdcard/a.png; reboot"))
        assertTrue(rec.calls.isEmpty())
    }

    // ── READ ─────────────────────────────────────────────────────────────

    @Test
    fun `读文件是 cat 路径`() {
        val rec = Recorder()
        invoke(rec, RootVerb.READ, listOf("/data/local/tmp/x.xml"))
        assertEquals(listOf("cat", "/data/local/tmp/x.xml"), rec.calls.single())
    }

    @Test
    fun `块设备不通过通道读`() {
        val rec = Recorder()
        val reply = invoke(rec, RootVerb.READ, listOf("/dev/block/by-name/boot"))
        assertTrue(rec.calls.isEmpty())
        assertTrue(reply is DaemonLoop.WireReply.Refused)
    }

    // ── SETTINGS ─────────────────────────────────────────────────────────

    @Test
    fun `改设置是 settings put 命名空间 键 值`() {
        val rec = Recorder()
        invoke(rec, RootVerb.SETTINGS, listOf("secure", "enabled_accessibility_services", "a/b"))

        assertEquals(
            listOf("settings", "put", "secure", "enabled_accessibility_services", "a/b"),
            rec.calls.single(),
        )
    }

    @Test
    fun `非法命名空间拒绝`() {
        val rec = Recorder()
        val reply = invoke(rec, RootVerb.SETTINGS, listOf("kernel", "a", "b"))
        assertTrue(rec.calls.isEmpty())
        assertTrue(reply is DaemonLoop.WireReply.Refused)
    }

    @Test
    fun `改属性是 setprop`() {
        val rec = Recorder()
        invoke(rec, RootVerb.SETTINGS, listOf("prop", "debug.pa", "1"))
        assertEquals(listOf("setprop", "debug.pa", "1"), rec.calls.single())
    }

    // ── QUERY ────────────────────────────────────────────────────────────

    @Test
    fun `白名单查询原样执行`() {
        val rec = Recorder()
        invoke(rec, RootVerb.QUERY, listOf("dumpsys", "activity", "activities"))
        assertEquals(listOf("dumpsys", "activity", "activities"), rec.calls.single())
    }

    @Test
    fun `查询首词不在白名单拒绝 sh 和 rm`() {
        val rec = Recorder()
        invoke(rec, RootVerb.QUERY, listOf("sh", "-c", "reboot"))
        invoke(rec, RootVerb.QUERY, listOf("rm", "-rf", "/data"))
        assertTrue(rec.calls.isEmpty())
    }

    // ── EXEC（受控） ──────────────────────────────────────────────────────

    @Test
    fun `只接受 pm disable-user --user 0 包名`() {
        val rec = Recorder()
        invoke(rec, RootVerb.EXEC, listOf("pm", "disable-user", "--user", "0", "com.example.junk"))
        assertEquals(
            listOf("pm", "disable-user", "--user", "0", "com.example.junk"),
            rec.calls.single(),
        )
    }

    @Test
    fun `其它 exec 形状一律拒绝`() {
        val rec = Recorder()
        invoke(rec, RootVerb.EXEC, listOf("sh", "-c", "id"))
        invoke(rec, RootVerb.EXEC, listOf("pm", "uninstall", "--user", "0", "com.example"))
        invoke(rec, RootVerb.EXEC, listOf("pm", "disable-user", "--user", "0"))
        assertTrue(rec.calls.isEmpty())
    }

    @Test
    fun `系统关键包不许停用`() {
        val rec = Recorder()
        val reply = invoke(rec, RootVerb.EXEC, listOf("pm", "disable-user", "--user", "0", "com.android.systemui"))

        assertTrue(rec.calls.isEmpty())
        assertTrue(reply is DaemonLoop.WireReply.Refused)
    }

    // ── PROBE / PING ─────────────────────────────────────────────────────

    @Test
    fun `探活报告身份，root 与 shell 都算可用`() {
        val root = Recorder(ShellResult(0, "uid=0(root) gid=0(root)", ""))
        assertTrue(invoke(root, RootVerb.PROBE, emptyList()) is DaemonLoop.WireReply.Ok)

        // shell 是虚拟屏那条路唯一的身份；在这里挡掉它，"免 root 也能不占屏干活"就不成立
        val shell = Recorder(ShellResult(0, "uid=2000(shell) gid=2000(shell)", ""))
        assertTrue(invoke(shell, RootVerb.PROBE, emptyList()) is DaemonLoop.WireReply.Ok)

        val neither = Recorder(ShellResult(0, "uid=10123(u0_a123)", ""))
        assertTrue(
            "既不是 root 也不是 shell 才算不可用",
            invoke(neither, RootVerb.PROBE, emptyList()) is DaemonLoop.WireReply.Refused,
        )
    }

    @Test
    fun `ping 不跑任何命令`() {
        val rec = Recorder()
        invoke(rec, RootVerb.PING, emptyList())
        assertTrue(rec.calls.isEmpty())
    }

    // ── 与 DaemonLoop 的合体 ──────────────────────────────────────────────

    @Test
    fun `经过 DaemonLoop 之后点按仍然只起一条命令`() {
        val rec = Recorder()
        val loop = DaemonLoop(DaemonHandlers.standard(rec))
        val reply = loop.handle(
            PrivilegeWire.Frame(
                kind = PrivilegeWire.Kind.REQUEST,
                id = 5,
                code = RootVerb.INJECT.wire,
                body = PrivilegeWire.encodeArgv(listOf("tap", "1", "2")),
            ),
        )

        assertEquals(PrivilegeWire.Kind.REPLY, reply.kind)
        assertEquals(5, reply.id)
        assertEquals(listOf(listOf("input", "tap", "1", "2")), rec.calls)
    }

    @Test
    fun `校验失败在 DaemonLoop 里变成 ERROR 帧 且带原因`() {
        val rec = Recorder()
        val loop = DaemonLoop(DaemonHandlers.standard(rec))
        val reply = loop.handle(
            PrivilegeWire.Frame(
                kind = PrivilegeWire.Kind.REQUEST,
                id = 6,
                code = RootVerb.QUERY.wire,
                body = PrivilegeWire.encodeArgv(listOf("sh", "-c", "reboot")),
            ),
        )

        assertEquals(PrivilegeWire.Kind.ERROR, reply.kind)
        assertFalse(reply.body.isEmpty())
        assertTrue(rec.calls.isEmpty())
    }
}
