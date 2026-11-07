package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 虚拟屏动作在主通道里的三条出口：`su -c` 的字符串、常驻通道的 argv、协议入口。
 *
 * 这三条必须**指向同一条命令**。它们分叉的后果不是报错，而是"root 用户能点、shell 用户点不动"
 * 或者"两个身份点的不是同一块屏" —— 那种 bug 在真机上表现为"有时管用"。
 */
class DisplayActionsTest {

    // ── su 那条路（字符串） ─────────────────────────────────────────────────

    @Test
    fun `拉起 App 的 su 命令带 display 与窗口模式`() {
        val payload = RootCommands.payload(RootAction.DisplayLaunch(5, "com.a/.Main"))
        assertEquals("am start --display 5 --windowingMode 1 -n com.a/.Main", payload)
    }

    @Test
    fun `副屏点按的 su 命令带 -d`() {
        assertEquals(
            "input -d 5 tap 360 640",
            RootCommands.payload(RootAction.DisplayTap(5, 360, 640)),
        )
    }

    @Test
    fun `抓副屏与停 App 的 su 命令`() {
        assertEquals(
            "screencap -d 5 -p /data/local/tmp/s.png",
            RootCommands.payload(RootAction.DisplayCapture(5, "/data/local/tmp/s.png")),
        )
        assertEquals(
            "am force-stop com.a",
            RootCommands.payload(RootAction.DisplayStop("com.a")),
        )
    }

    @Test
    fun `非法组件名在拼命令之前就被拒`() {
        // 抛在构造命令这一步，意味着一个字节都不会发出去
        assertThrows(UnsafeRootArgument::class.java) {
            RootCommands.payload(RootAction.DisplayLaunch(5, "com.a"))
        }
    }

    @Test
    fun `副屏 id 为零时 su 那条路当场拒绝，通道那条路由服务端拒绝`() {
        // su 这条路在拼字符串时就校验（一个字节都不发出去）
        assertThrows(UnsafeRootArgument::class.java) {
            RootCommands.payload(RootAction.DisplayTap(0, 1, 1))
        }
        // 通道这条路客户端只负责把意图编码过去，**校验在服务端**：
        // 客户端能发什么，不该成为"能不能执行"的唯一一道闸。
        val argv = DaemonCalls.of(RootAction.DisplayTap(0, 1, 1)).argv
        assertThrows(UnsafeRootArgument::class.java) {
            DisplayCommands.fromProtocol(argv)
        }
    }

    // ── 常驻通道那条路（argv） ──────────────────────────────────────────────

    @Test
    fun `通道里四条动作都走 DISPLAY 动词`() {
        val launch = DaemonCalls.of(RootAction.DisplayLaunch(5, "com.a/.Main"))
        assertEquals(RootVerb.DISPLAY, launch.verb)
        assertEquals(listOf("launch", "com.a/.Main", "5"), launch.argv)

        val tap = DaemonCalls.of(RootAction.DisplayTap(5, 1, 2))
        assertEquals(listOf("input", "5", "tap", "1", "2"), tap.argv)

        val capture = DaemonCalls.of(RootAction.DisplayCapture(5, "/data/local/tmp/s.png"))
        assertEquals(listOf("capture", "5", "/data/local/tmp/s.png"), capture.argv)

        val stop = DaemonCalls.of(RootAction.DisplayStop("com.a"))
        assertEquals(listOf("stop", "com.a"), stop.argv)
    }

    @Test
    fun `通道那边把这四条翻回同样的命令`() {
        assertEquals(
            listOf("am", "start", "--display", "5", "--windowingMode", "1", "-n", "com.a/.Main"),
            DisplayCommands.fromProtocol(DaemonCalls.of(RootAction.DisplayLaunch(5, "com.a/.Main")).argv),
        )
        assertEquals(
            listOf("input", "-d", "5", "tap", "1", "2"),
            DisplayCommands.fromProtocol(DaemonCalls.of(RootAction.DisplayTap(5, 1, 2)).argv),
        )
        assertEquals(
            listOf("screencap", "-d", "5", "-p", "/data/local/tmp/s.png"),
            DisplayCommands.fromProtocol(DaemonCalls.of(RootAction.DisplayCapture(5, "/data/local/tmp/s.png")).argv),
        )
        assertEquals(
            listOf("am", "force-stop", "com.a"),
            DisplayCommands.fromProtocol(DaemonCalls.of(RootAction.DisplayStop("com.a")).argv),
        )
    }

    // ── 能力归属 ────────────────────────────────────────────────────────────

    @Test
    fun `虚拟屏动作归到能力矩阵里已有的条目`() {
        assertEquals("screen.tap", RootCommands.capabilityOf(RootAction.DisplayLaunch(5, "com.a/.Main")))
        assertEquals("screen.tap", RootCommands.capabilityOf(RootAction.DisplayTap(5, 1, 1)))
        assertEquals("screen.read", RootCommands.capabilityOf(RootAction.DisplayCapture(5, "/data/local/tmp/s.png")))
        // 停 App 是最重的一类：它把别人的进程干掉
        assertEquals("shell.root", RootCommands.capabilityOf(RootAction.DisplayStop("com.a")))
    }

    @Test
    fun `能力归属都在矩阵里存在`() {
        val ids = CapabilityMatrix.all.map { it.id }.toSet()
        for (action in listOf(
            RootAction.DisplayLaunch(5, "com.a/.Main"),
            RootAction.DisplayTap(5, 1, 1),
            RootAction.DisplayCapture(5, "/data/local/tmp/s.png"),
            RootAction.DisplayStop("com.a"),
        )) {
            val capability = RootCommands.capabilityOf(action)
            assert(ids.contains(capability)) { "矩阵里没有 $capability" }
        }
    }

    // ── 顺序约束 ────────────────────────────────────────────────────────────

    @Test
    fun `收屏之前必须先停 App`() {
        val sessions = DisplaySessions()
        val id = (sessions.claim("com.a") as DisplaySessions.Claim.Granted).displayId
        sessions.launch(id, "com.a", "com.a/.Main")

        val plan = sessions.close(id)
        assertEquals(listOf("am", "force-stop", "com.a"), plan.forceStop)
        assertEquals("执行：am force-stop com.a", plan.steps.first())
    }
}
