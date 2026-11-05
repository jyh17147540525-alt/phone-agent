package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 虚拟屏的三条通道命令（`launch` / `input` / `capture`）。
 *
 * 这里钉的是**参数形状**，因为这三条命令的破坏力不对称：
 * - `--display 0` 会把目标 App 拉到**用户主屏**，等于把手机抢走；
 * - `am start` 多一个开关就改变任务栈；
 * - `input` 的子命令不止 tap/滑动，还有 `draganddrop`、`sleep`。
 *
 * 所以全是白名单式断言：能起什么、精确到哪一个参数。
 */
class DisplayCommandsTest {

    // ── display id ──────────────────────────────────────────────────────────

    @Test
    fun `副屏 id 合法值被接受`() {
        assertEquals(1, DisplayCommands.displayId("1"))
        assertEquals(99, DisplayCommands.displayId("99"))
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `主屏 id 0 一律拒绝`() {
        // 0 是用户正在用的那块屏；往那儿拉 App 就是把用户的手机抢走
        DisplayCommands.displayId("0")
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `负数与超大 id 拒绝`() {
        DisplayCommands.displayId("-3")
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `非数字拒绝`() {
        DisplayCommands.displayId("5;reboot")
    }

    // ── launch ──────────────────────────────────────────────────────────────

    @Test
    fun `拉起到副屏的命令固定为全屏窗口模式`() {
        val argv = DisplayCommands.launch(5, "com.tencent.mobileqq/.activity.SplashActivity")
        assertEquals(
            listOf(
                "am", "start",
                "--display", "5",
                "--windowingMode", "1",
                "-n", "com.tencent.mobileqq/.activity.SplashActivity",
            ),
            argv,
        )
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `组件名缺类名时拒绝`() {
        DisplayCommands.launch(5, "com.tencent.mobileqq")
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `组件名带空格或引号时拒绝`() {
        DisplayCommands.launch(5, "com.x/.Main --activity-clear-top")
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `launch 时 id 为 0 也拒绝`() {
        DisplayCommands.launch(0, "com.x/.Main")
    }

    // ── input ───────────────────────────────────────────────────────────────

    @Test
    fun `副屏点按带 -d 前缀`() {
        assertEquals(
            listOf("input", "-d", "5", "tap", "360", "640"),
            DisplayCommands.input(5, listOf("tap", "360", "640")),
        )
    }

    @Test
    fun `副屏滑动带四个坐标与时长`() {
        assertEquals(
            listOf("input", "-d", "5", "swipe", "10", "20", "30", "40", "300"),
            DisplayCommands.input(5, listOf("swipe", "10", "20", "30", "40", "300")),
        )
    }

    @Test
    fun `副屏按键只认 KEYCODE 或纯数字`() {
        assertEquals(
            listOf("input", "-d", "5", "keyevent", "KEYCODE_BACK"),
            DisplayCommands.input(5, listOf("keyevent", "KEYCODE_BACK")),
        )
        assertEquals(
            listOf("input", "-d", "5", "keyevent", "4"),
            DisplayCommands.input(5, listOf("keyevent", "4")),
        )
    }

    @Test
    fun `副屏输入文字允许中文与空格`() {
        assertEquals(
            listOf("input", "-d", "5", "text", "晚点 到"),
            DisplayCommands.input(5, listOf("text", "晚点 到")),
        )
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `文字里的换行与单引号拒绝`() {
        DisplayCommands.input(5, listOf("text", "a'b"))
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `白名单之外的 input 子命令拒绝`() {
        // draganddrop / sleep / motionevent 都能做别的事，一律不放
        DisplayCommands.input(5, listOf("draganddrop", "1", "2", "3", "4"))
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `坐标不是数字时拒绝`() {
        DisplayCommands.input(5, listOf("tap", "1e3", "640"))
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `tap 参数个数不对时拒绝`() {
        DisplayCommands.input(5, listOf("tap", "360"))
    }

    // ── capture ─────────────────────────────────────────────────────────────

    @Test
    fun `抓副屏是 screencap -d`() {
        assertEquals(
            listOf("screencap", "-d", "5", "-p", "/data/local/tmp/pa/shot.png"),
            DisplayCommands.capture(5, "/data/local/tmp/pa/shot.png"),
        )
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `截图路径必须是绝对路径且不含上级目录`() {
        DisplayCommands.capture(5, "relative/shot.png")
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `截图路径含点点斜杠时拒绝`() {
        DisplayCommands.capture(5, "/data/local/tmp/../../etc/hosts")
    }

    // ── 协议入口 ────────────────────────────────────────────────────────────

    @Test
    fun `协议入口把三种子命令翻成命令`() {
        assertEquals(
            listOf("am", "start", "--display", "7", "--windowingMode", "1", "-n", "com.x/.Main"),
            DisplayCommands.fromProtocol(listOf("launch", "com.x/.Main", "7")),
        )
        assertEquals(
            listOf("input", "-d", "7", "tap", "1", "2"),
            DisplayCommands.fromProtocol(listOf("input", "7", "tap", "1", "2")),
        )
        assertEquals(
            listOf("screencap", "-d", "7", "-p", "/data/local/tmp/a.png"),
            DisplayCommands.fromProtocol(listOf("capture", "7", "/data/local/tmp/a.png")),
        )
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `协议入口拒绝未知子命令`() {
        DisplayCommands.fromProtocol(listOf("remove", "5"))
    }

    @Test(expected = UnsafeRootArgument::class)
    fun `协议入口拒绝参数个数不对`() {
        DisplayCommands.fromProtocol(listOf("launch", "com.x/.Main"))
    }

    // ── 与通道处理器连起来 ──────────────────────────────────────────────────

    private class Recorder(private val result: ShellResult = ShellResult(0, "ok", "")) : LocalRunner {
        val calls = ArrayList<List<String>>()
        override fun run(argv: List<String>, timeoutMs: Int): ShellResult {
            calls += argv
            return result
        }
    }

    private fun invoke(runner: LocalRunner, argv: List<String>): DaemonLoop.WireReply =
        DaemonHandlers.standard(runner).getValue(RootVerb.DISPLAY)(PrivilegeWire.encodeArgv(argv))

    @Test
    fun `通道里 launch 起一条 am start`() {
        val rec = Recorder()
        val reply = invoke(rec, listOf("launch", "com.x/.Main", "5"))

        assertTrue(reply is DaemonLoop.WireReply.Ok)
        assertEquals(1, rec.calls.size)
        assertEquals("am", rec.calls[0][0])
        assertEquals("--display", rec.calls[0][2])
        assertEquals("5", rec.calls[0][3])
    }

    @Test
    fun `通道里 id 为 0 时一条命令都不起`() {
        val rec = Recorder()
        val reply = invoke(rec, listOf("launch", "com.x/.Main", "0"))

        assertTrue("主屏 id 必须被拒：$reply", reply is DaemonLoop.WireReply.Refused)
        assertTrue(rec.calls.isEmpty())
    }

    @Test
    fun `通道里坏参数被拒且不起命令`() {
        val rec = Recorder()
        val reply = invoke(rec, listOf("input", "5", "draganddrop", "1", "2", "3", "4"))

        assertTrue(reply is DaemonLoop.WireReply.Refused)
        assertTrue(rec.calls.isEmpty())
    }
}
