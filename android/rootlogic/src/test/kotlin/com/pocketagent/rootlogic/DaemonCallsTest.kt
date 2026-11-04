package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `RootAction → 常驻通道请求` 的映射。
 *
 * 这里钉的是**形状**：参数是数组、不经 shell、动词分得开。形状对了，
 * 注入那一整类问题就不存在；形状错了（比如把整条命令塞进一个参数再交给
 * `sh -c`），前面 `RootCommands` 的校验就白做了。
 */
class DaemonCallsTest {

    @Test
    fun `点按走 INJECT 且坐标是两个独立参数`() {
        val call = DaemonCalls.of(RootAction.Tap(100, 200))
        assertEquals(RootVerb.INJECT, call.verb)
        assertEquals(listOf("tap", "100", "200"), call.argv)
    }

    @Test
    fun `负坐标合法 多屏会出现`() {
        assertEquals(listOf("tap", "-1", "0"), DaemonCalls.of(RootAction.Tap(-1, 0)).argv)
    }

    @Test
    fun `滑动时长被夹在 1 到 60000`() {
        val slow = DaemonCalls.of(RootAction.Swipe(0, 0, 10, 10, millis = 999_999))
        assertEquals("60000", slow.argv.last())

        val instant = DaemonCalls.of(RootAction.Swipe(0, 0, 10, 10, millis = 0))
        assertEquals("1", instant.argv.last())
    }

    @Test
    fun `按键走白名单`() {
        assertEquals(listOf("key", "KEYCODE_BACK"), DaemonCalls.of(RootAction.Key("KEYCODE_BACK")).argv)
    }

    @Test
    fun `不在白名单的按键被拒`() {
        val rejected = try {
            DaemonCalls.of(RootAction.Key("KEYCODE_HOME; reboot"))
            false
        } catch (e: UnsafeRootArgument) {
            true
        }
        assertTrue("按键名必须过白名单", rejected)
    }

    @Test
    fun `文字里的分号与空格原样过去 不经 shell 就无害`() {
        val text = "你好 world; rm -rf /"
        val call = DaemonCalls.of(RootAction.Text(text))
        assertEquals(RootVerb.INJECT, call.verb)
        assertEquals(listOf("text", text), call.argv)
    }

    @Test
    fun `文字里的换行被拒 否则守护进程日志会被劈成两行`() {
        val rejected = try {
            DaemonCalls.of(RootAction.Text("第一行\n第二行"))
            false
        } catch (e: UnsafeRootArgument) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun `空文字被拒`() {
        val rejected = try {
            DaemonCalls.of(RootAction.Text(""))
            false
        } catch (e: UnsafeRootArgument) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun `截图与 dump 都归 CAPTURE 但子命令不同`() {
        val capture = DaemonCalls.of(RootAction.Capture("/sdcard/a.png"))
        assertEquals(RootVerb.CAPTURE, capture.verb)
        assertEquals("screencap", capture.argv[0])

        val dump = DaemonCalls.of(RootAction.DumpTree("/data/local/tmp/ui.xml"))
        assertEquals(RootVerb.CAPTURE, dump.verb)
        assertEquals("dump", dump.argv[0])
    }

    @Test
    fun `读文件走 READ 且路径越界直接拒绝`() {
        assertEquals(RootVerb.READ, DaemonCalls.of(RootAction.ReadFile("/data/local/tmp/x")).verb)

        val rejected = try {
            DaemonCalls.of(RootAction.ReadFile("/data/local/tmp/x; reboot"))
            false
        } catch (e: UnsafeRootArgument) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun `改设置把命名空间放在第一个参数`() {
        val call = DaemonCalls.of(
            RootAction.PutSetting(SettingNamespace.SECURE, "enabled_accessibility_services", "a/b"),
        )
        assertEquals(RootVerb.SETTINGS, call.verb)
        assertEquals(listOf("secure", "enabled_accessibility_services", "a/b"), call.argv)
    }

    @Test
    fun `改属性用 prop 前缀与读属性区分开`() {
        val write = DaemonCalls.of(RootAction.SetProp("debug.pa", "1"))
        assertEquals(RootVerb.SETTINGS, write.verb)
        assertEquals(listOf("prop", "debug.pa", "1"), write.argv)

        val read = DaemonCalls.of(RootAction.GetProp("ro.build.version.sdk"))
        assertEquals(RootVerb.QUERY, read.verb)
        assertEquals(listOf("getprop", "ro.build.version.sdk"), read.argv)
    }

    @Test
    fun `停用包走 EXEC 且是固定前缀`() {
        val call = DaemonCalls.of(RootAction.DisablePackage("com.example.junk"))
        assertEquals(RootVerb.EXEC, call.verb)
        assertEquals(listOf("pm", "disable-user", "--user", "0", "com.example.junk"), call.argv)
    }

    @Test
    fun `包名带分号被拒`() {
        val rejected = try {
            DaemonCalls.of(RootAction.DisablePackage("com.x; rm -rf /"))
            false
        } catch (e: UnsafeRootArgument) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun `探活没有参数`() {
        val call = DaemonCalls.of(RootAction.Probe)
        assertEquals(RootVerb.PROBE, call.verb)
        assertTrue(call.argv.isEmpty())
    }

    @Test
    fun `查询保留首词与参数的分界`() {
        val call = DaemonCalls.of(RootAction.Query(listOf("dumpsys", "activity", "activities")))
        assertEquals(RootVerb.QUERY, call.verb)
        assertEquals(listOf("dumpsys", "activity", "activities"), call.argv)
    }

    @Test
    fun `查询参数里的管道符被拒`() {
        val rejected = try {
            DaemonCalls.of(RootAction.Query(listOf("ls", "|", "sh")))
            false
        } catch (e: UnsafeRootArgument) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun `describe 不含原始文本 只给动词与子命令`() {
        val call = DaemonCalls.of(RootAction.Text("我的银行卡密码是 123456"))
        val described = call.describe()
        assertTrue(described.startsWith("inject"))
        assertTrue("describe 不该带出用户文本：$described", !described.contains("123456"))
    }
}
