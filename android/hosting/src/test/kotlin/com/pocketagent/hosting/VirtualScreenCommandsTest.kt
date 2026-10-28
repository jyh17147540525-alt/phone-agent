package com.pocketagent.hosting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VirtualScreenCommands] 的离线钉子。
 *
 * ★ 这一类被测对象有个共同点：**拼错不会报错**。
 *   `am`/`input` 的参数错一个字母，退出码照样是 0，界面照样"看起来在动"，
 *   只有结果不对 —— 而结果不对在虚拟屏上很难被看见（用户本来就不看那块屏）。
 */
class VirtualScreenCommandsTest {

    @Test
    fun `启动必须显式指定全屏窗口模式`() {
        // ★ 真机踩过：虚拟屏里的应用渲染成了**带圆角阴影的浮窗**，
        //   因为机器上曾打开过 enable_freeform_support。
        //   ⇒ 必须显式 --windowingMode 1，不依赖任何全局开关"碰巧"给对。
        val cmd = VirtualScreenCommands.startOnDisplay(9, "com.tencent.mm/.ui.LauncherUI")
        assertEquals(
            "am start --display 9 --windowingMode 1 -n com.tencent.mm/.ui.LauncherUI",
            cmd,
        )
        assertTrue("必须是全屏模式：$cmd", cmd.contains("--windowingMode 1"))
    }

    @Test
    fun `全屏窗口常量就是 1`() {
        // ⚠️ `am` 只认数字。系统改了值只会表现成"又变浮窗了"，不会报错。
        assertEquals(1, VirtualScreenCommands.WINDOWING_MODE_FULLSCREEN)
    }

    @Test
    fun `永远不允许往主屏拉应用`() {
        try {
            VirtualScreenCommands.startOnDisplay(0, "com.tencent.mm/.ui.LauncherUI")
            throw AssertionError("displayId=0 是用户主屏，必须被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("主屏"))
        }
    }

    @Test
    fun `坐标注入也要挡住主屏`() {
        try {
            VirtualScreenCommands.tapOnDisplay(0, 10, 10)
            throw AssertionError("往主屏注入触摸等于直接动用户正在用的界面")
        } catch (e: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `点击带上 display 参数且坐标原样保留`() {
        assertEquals(
            "input -d 7 tap 360 640",
            VirtualScreenCommands.tapOnDisplay(7, 360, 640),
        )
    }

    @Test
    fun `非法组件名不能拼进 root 命令`() {
        for (bad in listOf(
            "com.tencent.mm",
            "com.tencent.mm/.ui;reboot",
            "com.tencent.mm/.ui Launcher",
            "-n com.evil",
        )) {
            try {
                VirtualScreenCommands.startOnDisplay(3, bad)
                throw AssertionError("$bad 应当被拒绝")
            } catch (e: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `输入文字时空格转义且去掉单引号`() {
        // "hello it's me" → 去单引号 → "hello its me" → 空格转 %s
        val cmd = VirtualScreenCommands.typeOnDisplay(5, "hello it's me")
        assertEquals("input -d 5 text 'hello%sits%sme'", cmd)
        assertFalse("单引号会打断 shell 引号：$cmd", cmd.contains("'s"))
    }

    @Test
    fun `清理老方案的 freeform 开关`() {
        val cmds = VirtualScreenCommands.disableFreeform()
        assertEquals(2, cmds.size)
        // ⚠️ 用 delete 而不是 put 0：这两个开关的语义是"有没有设置过"。
        assertTrue(cmds.all { it.contains("settings delete global") })
        assertTrue(cmds.any { it.contains("enable_freeform_support") })
        assertTrue(cmds.any { it.contains("force_resizable_activities") })
    }

    @Test
    fun `滑动和按键都带 display 参数`() {
        assertEquals(
            "input -d 8 swipe 10 20 30 40 300",
            VirtualScreenCommands.swipeOnDisplay(8, 10, 20, 30, 40, 300),
        )
        assertEquals("input -d 8 keyevent 4", VirtualScreenCommands.keyOnDisplay(8, 4))
    }
}
