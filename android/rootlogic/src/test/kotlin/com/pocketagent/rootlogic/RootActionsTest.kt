package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * root 档动作的**参数闸门**。
 *
 * ★ 这组测试钉的不是"命令拼得对不对"，而是"**能不能拼出第二条命令**"。
 *   root 权限下一条拼错的命令不可撤销 —— 所以每条越界输入都必须
 *   在**发包之前**被拒，而不是靠 su 那边报错。
 */
class RootActionsTest {

    /** 记录调用次数与最后一次 argv，用来验证"被拒时根本没发包"。 */
    private class Recorder(private val result: ShellResult = ShellResult(0, "ok", "")) : RootShellPort {
        var calls = 0
        var last: List<String>? = null
        override fun run(argv: List<String>): ShellResult {
            calls++
            last = argv
            return result
        }
    }

    // ── 1. 正常路径 ──────────────────────────────────────────────

    @Test
    fun `点按拼成 input tap 并交给 su -c`() {
        val port = Recorder()
        RootActions(port).run(RootAction.Tap(100, 200))
        assertEquals(listOf("su", "-c", "input tap 100 200"), port.last)
    }

    @Test
    fun `负坐标是合法的`() {
        // 多屏 / 虚拟屏里会出现负坐标；直接当"非法"会让副屏上的点按永远失败。
        val port = Recorder()
        RootActions(port).run(RootAction.Tap(-1, 0))
        assertEquals("input tap -1 0", port.last!!.last())
    }

    @Test
    fun `滑动时长被夹在 1 毫秒到 60 秒之间`() {
        val port = Recorder()
        val actions = RootActions(port)
        actions.run(RootAction.Swipe(0, 0, 10, 10, millis = 0))
        assertTrue(port.last!!.last().endsWith(" 1"))
        actions.run(RootAction.Swipe(0, 0, 10, 10, millis = 999_999))
        assertTrue(port.last!!.last().endsWith(" 60000"))
    }

    @Test
    fun `中文和空格可以进 input text，靠单引号挡住语法`() {
        val port = Recorder()
        RootActions(port).run(RootAction.Text("你好 world; rm -rf /"))
        // `;` 在单引号里不是语法，整段被当成一段文字
        assertEquals("input text '你好 world; rm -rf /'", port.last!!.last())
    }

    @Test
    fun `抓帧与改设置拼得对`() {
        val port = Recorder()
        val actions = RootActions(port)
        actions.run(RootAction.Capture("/sdcard/pa/shot.png"))
        assertEquals("screencap -p /sdcard/pa/shot.png", port.last!!.last())
        actions.run(RootAction.PutSetting(SettingNamespace.SECURE, "enabled_accessibility_services", "a/b"))
        assertEquals("settings put secure enabled_accessibility_services a/b", port.last!!.last())
    }

    @Test
    fun `白名单查询拼得对`() {
        val port = Recorder()
        RootActions(port).run(RootAction.Query(listOf("getprop", "ro.build.version.sdk")))
        assertEquals("getprop ro.build.version.sdk", port.last!!.last())
    }

    // ── 2. 越界输入：**一个字节都不该发出去** ─────────────────────

    @Test
    fun `包名里带分号直接拒绝，且不发包`() {
        val port = Recorder()
        val r = RootActions(port).run(RootAction.DisablePackage("com.x; rm -rf /"))
        assertEquals(RootActions.REJECTED, r.code)
        assertEquals("越界输入不该走到 su", 0, port.calls)
    }

    @Test
    fun `文字里有单引号或反斜杠会被拒`() {
        val port = Recorder()
        val actions = RootActions(port)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Text("it's")).code)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Text("a\\b")).code)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Text("a\nb")).code)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Text("")).code)
        assertEquals(0, port.calls)
    }

    @Test
    fun `按键只认白名单里的 keycode`() {
        val port = Recorder()
        val actions = RootActions(port)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Key("KEYCODE_HOME; reboot")).code)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Key("keyevent 26")).code)
        assertEquals(0, port.calls)
        actions.run(RootAction.Key("KEYCODE_BACK"))
        assertEquals("input keyevent KEYCODE_BACK", port.last!!.last())
    }

    @Test
    fun `查询命令只读白名单，写操作进不来`() {
        val port = Recorder()
        val actions = RootActions(port)
        // 这几个都是"能把手机搞坏"的命令，不该借 Query 的口子溜进来
        for (bad in listOf("sh", "rm", "reboot", "pm", "screencap")) {
            assertEquals("$bad 不该被放行", RootActions.REJECTED, actions.run(RootAction.Query(listOf(bad))).code)
        }
        assertEquals(0, port.calls)
    }

    @Test
    fun `查询参数里带管道符或重定向会被拒`() {
        val port = Recorder()
        val actions = RootActions(port)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Query(listOf("ls", "/sdcard|x"))).code)
        assertEquals(RootActions.REJECTED, actions.run(RootAction.Query(listOf("ls", "> /x"))).code)
        assertEquals(0, port.calls)
    }

    @Test
    fun `坐标越界会被拒`() {
        val port = Recorder()
        assertEquals(RootActions.REJECTED, RootActions(port).run(RootAction.Tap(1_000_000, 0)).code)
        assertEquals(0, port.calls)
    }

    // ── 3. 结果归类 ──────────────────────────────────────────────

    @Test
    fun `成功时只回显 stdout`() {
        val port = Recorder(ShellResult(0, "uid=0(root)\n", ""))
        assertEquals("uid=0(root)", RootActions(port).run(RootAction.Probe).describe())
    }

    @Test
    fun `失败时把退出码和 stderr 一起给出`() {
        val port = Recorder(ShellResult(1, "", "Permission denied\n"))
        val text = RootActions(port).run(RootAction.Probe).describe()
        assertTrue("要说清退出码：$text", text.contains("1"))
        assertTrue("要带上 stderr：$text", text.contains("Permission denied"))
    }

    @Test
    fun `成功但没有输出时不回空串`() {
        // 空串会让模型以为"什么也没发生"，进而重试。
        val port = Recorder(ShellResult(0, "  ", ""))
        assertEquals("已执行", RootActions(port).run(RootAction.Probe).describe())
    }

    // ── 4. 与能力矩阵的对应关系 ──────────────────────────────────

    @Test
    fun `每条动作都归属到矩阵里的某条能力`() {
        assertEquals("screen.tap", RootCommands.capabilityOf(RootAction.Tap(0, 0)))
        assertEquals("screen.tap", RootCommands.capabilityOf(RootAction.Text("hi")))
        assertEquals("screen.read", RootCommands.capabilityOf(RootAction.Capture("/x")))
        assertEquals("settings.write", RootCommands.capabilityOf(RootAction.PutSetting(SettingNamespace.GLOBAL, "a", "b")))
        assertEquals("pm.disable", RootCommands.capabilityOf(RootAction.DisablePackage("com.x")))
        // ★ 读写分开：`getprop` 是只读的，按最高危那条走；改属性才是 prop.set
        assertEquals("shell.root", RootCommands.capabilityOf(RootAction.GetProp("ro.debuggable")))
        assertEquals("prop.set", RootCommands.capabilityOf(RootAction.SetProp("ro.debuggable", "1")))
        assertEquals("screen.read", RootCommands.capabilityOf(RootAction.DumpTree("/data/local/tmp/a.xml")))
        assertEquals("file.any", RootCommands.capabilityOf(RootAction.ReadFile("/data/local/tmp/a.xml")))
        assertEquals("shell.root", RootCommands.capabilityOf(RootAction.Probe))

        // ★ 归属的 id 必须真的在矩阵里 —— 否则这条动作永远拿不到档位判定。
        val ids = CapabilityMatrix.all.map { it.id }.toSet()
        for (action in listOf(
            RootAction.Tap(0, 0), RootAction.Capture("/x"), RootAction.Probe,
            RootAction.PutSetting(SettingNamespace.SYSTEM, "a", "b"),
            RootAction.DisablePackage("com.x"), RootAction.GetProp("a"),
            RootAction.SetProp("a", "b"), RootAction.DumpTree("/x.xml"),
            RootAction.ReadFile("/x"),
        )) {
            assertTrue(
                "${action::class.simpleName} 归属的 ${RootCommands.capabilityOf(action)} 不在矩阵里",
                RootCommands.capabilityOf(action) in ids,
            )
        }
    }

    @Test
    fun `dump 与读文件拼成哪条命令`() {
        val port = Recorder()

        RootCommands.payload(RootAction.DumpTree("/data/local/tmp/pa.xml")).let {
            assertEquals("uiautomator dump /data/local/tmp/pa.xml", it)
        }
        RootCommands.payload(RootAction.ReadFile("/data/local/tmp/pa.xml")).let {
            assertEquals("cat /data/local/tmp/pa.xml", it)
        }
        RootCommands.payload(RootAction.SetProp("debug.pa", "1")).let {
            assertEquals("setprop debug.pa 1", it)
        }
        // 全程没发包 —— payload 是纯函数
        assertEquals(0, port.calls)
    }

    @Test
    fun `dump 路径带空格或分号一个字节都不发`() {
        for (path in listOf("/data/local/tmp/a b.xml", "/x;rm -rf /", "/x|sh", "/x\$(id)")) {
            val port = Recorder()
            val result = RootActions(port).run(RootAction.DumpTree(path))

            assertFalse("路径「$path」不该被接受", result.ok)
            assertEquals("路径「$path」不该发到 su", 0, port.calls)
        }
    }

    @Test
    fun `动作不合法时描述里写清楚是本地拒绝`() {
        val port = Recorder()
        val text = RootActions(port).run(RootAction.DisablePackage("bad pkg")).describe()
        assertTrue("要能区分本地拒绝与命令失败：$text", text.contains("包名"))
        assertFalse("不该假装命令跑过了", text.contains("已执行"))
    }
}
