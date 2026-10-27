package com.pocketagent.hosting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 托管命令拼装的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这些命令以 **root** 身份执行 —— 拼错一个参数的后果不是"不好用"
 * ═══════════════════════════════════════════════════════════════
 *
 * 所以这组测试的重点不是"功能对不对"，而是**"有没有可能被注入"**：
 * 包名若不做白名单校验，`am task resize` 之后的参数就能被换成任意命令。
 */
class HostingCommandsTest {

    // ═══════════════════════════════════════════════════════════
    //  包名白名单
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `合法包名通过`() {
        for (p in listOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.ugc.aweme",
            "com.pocketagent.debug",
        )) {
            assertTrue("$p 应当合法", HostingCommands.isValidPackage(p))
        }
    }

    @Test
    fun `注入尝试必须被拒绝`() {
        // ⚠️ 这些如果能通过，它们会被拼进 root shell
        val evil = listOf(
            "com.tencent.mm; rm -rf /",
            "com.tencent.mm && reboot",
            "com.tencent.mm | cat /etc/passwd",
            "com.tencent.mm`id`",
            "com.tencent.mm\$(id)",
            "com.tencent.mm\nreboot",
            "../../data",
            "",
            "com",
        )
        for (p in evil) {
            assertFalse("「$p」必须被拒绝", HostingCommands.isValidPackage(p))
        }
    }

    @Test
    fun `拼命令时非法包名直接抛`() {
        // ★ 抛异常而不是"返回 null"：调用方必须显式处理，
        //   而不是拿到一个空命令继续跑。
        for (p in listOf("com.x; rm -rf /", "")) {
            try {
                HostingCommands.findTaskId(p)
                throw AssertionError("「$p」应当抛异常")
            } catch (e: IllegalArgumentException) {
                assertTrue("要说清原因：${e.message}", e.message!!.contains("包名"))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  命令形态
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `resize 命令形态正确`() {
        val cmd = HostingCommands.resizeTask(24735, HostBounds(0, 0, 600, 900))
        assertEquals("am task resize 24735 0 0 600 900", cmd)
    }

    @Test
    fun `resize 拒绝非法 taskId`() {
        for (bad in listOf(0, -1)) {
            try {
                HostingCommands.resizeTask(bad, HostBounds(0, 0, 10, 10))
                throw AssertionError("$bad 应当被拒绝")
            } catch (e: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `自由窗口启动必须带 windowingMode 5`() {
        // ══════════════════════════════════════════════════════════
        //  ★★ 2026-10-27 真机把这个测试的**结论推翻了**
        // ══════════════════════════════════════════════════════════
        //
        // 上一版这里断言的是 "用 monkey 而不是 am start"，理由是
        // "am start 需要知道具体 Activity 名"。那个理由本身没错，但结论错了：
        //
        // 真机实测（Redmi K60 / Android 15，root）：
        //   monkey 启动    → Task{... mode=fullscreen}
        //   am task resize → **什么都不做**（它只在多窗口/自由窗口下有效）
        //   ⇒ 整条托管链路静默失效，而日志看起来每一步都"成功"了
        //
        //   am start --windowingMode 5 -n <component>
        //                  → Task{... mode=freeform}
        //   am task resize → mBounds=Rect(720, 120 - 1420, 1020)   ✓
        //
        // ⇒ 正确做法是**先问系统要组件名**，再带 `--windowingMode 5` 启动。
        //   组件名从 `cmd package resolve-activity --brief` 拿，
        //   于是也不需要硬编码任何 Activity 名。
        val cmd = HostingCommands.startFreeform("com.tencent.mm/.ui.LauncherUI")
        assertEquals("am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI", cmd)
        assertTrue("5 必须是 FREEFORM：$cmd", cmd.contains("--windowingMode 5"))
    }

    @Test
    fun `windowingMode 的自由窗口常量就是 5`() {
        // ⚠️ 钉住这个数字：`am` 只认数字，系统改了值只会表现成"又变全屏了"，
        //    不会报错。这条断言是唯一的报警器。
        assertEquals(5, HostingCommands.WINDOWING_MODE_FREEFORM)
    }

    @Test
    fun `非法组件名不能拼进 root 命令`() {
        for (bad in listOf(
            "com.tencent.mm",                 // 没有斜杠
            "com.tencent.mm/.ui;reboot",      // 命令注入
            "com.tencent.mm/.ui Launcher",    // 空格
            "-n com.evil",
        )) {
            try {
                HostingCommands.startFreeform(bad)
                throw AssertionError("$bad 应当被拒绝")
            } catch (e: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `能从 resolve-activity 输出里取到组件名`() {
        val out = """
            priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
            com.tencent.mobileqq/.activity.SplashActivity
        """.trimIndent()
        assertEquals(
            "com.tencent.mobileqq/.activity.SplashActivity",
            HostingCommands.parseLauncherComponent(out),
        )
    }

    @Test
    fun `取不到组件名时返回 null 而不是瞎猜`() {
        // ⚠️ 返回 null 让调用方**如实报错**。退回全屏启动会把"没托管成功"
        //    变成"挡在用户屏幕上" —— 那比失败更糟。
        assertEquals(null, HostingCommands.parseLauncherComponent(""))
        assertEquals(null, HostingCommands.parseLauncherComponent("No activity found"))
        assertEquals(null, HostingCommands.parseLauncherComponent("com.foo/.Bar;reboot"))
    }

    @Test
    fun `resolve 命令要带 brief 与 LAUNCHER`() {
        val cmd = HostingCommands.resolveLauncher("com.tencent.mm")
        assertTrue("要用 --brief 只输出结论：$cmd", cmd.contains("--brief"))
        assertTrue("要指定 MAIN：$cmd", cmd.contains("android.intent.action.MAIN"))
        assertTrue("要指定 LAUNCHER：$cmd", cmd.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `恢复全屏传全屏 bounds`() {
        val cmd = HostingCommands.restoreFullscreen(24735, 1440, 3200)
        assertEquals("am task resize 24735 0 0 1440 3200", cmd)
    }

    // ═══════════════════════════════════════════════════════════
    //  几何约束
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `负坐标被拒绝`() {
        // ⚠️ 真机实测：`am task resize 24735 -2000 0 -1400 900`
        //    返回 `Error: bad left arg: -2000`。
        //    所以"把窗口藏到屏幕外"这条路是死的 —— 类型层面就该拦。
        try {
            HostBounds(-1, 0, 100, 100)
            throw AssertionError("负坐标应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue("要说清系统不允许：${e.message}", e.message!!.contains("负"))
        }
    }

    @Test
    fun `右边界必须大于左边界`() {
        for ((l, r) in listOf(100 to 100, 100 to 50)) {
            try {
                HostBounds(l, 0, r, 100)
                throw AssertionError("($l,$r) 应当被拒绝")
            } catch (e: IllegalArgumentException) {
                // ok
            }
        }
    }

    @Test
    fun `默认托管区域在右上角`() {
        val b = HostBounds.cornerOf(1440, 3200)

        assertEquals("右边界应当贴屏幕右边", 1440, b.right)
        assertTrue("左边界应当在右半屏：${b.left}", b.left > 720)
        assertTrue("顶部应当有偏移（避开状态栏）：${b.top}", b.top > 0)
        assertTrue("宽度应当是屏幕的 4 成左右：${b.width}", b.width in 500..700)
        assertTrue("高度应当是屏幕的 3 成左右：${b.height}", b.height in 850..1050)
    }

    @Test
    fun `小屏也不会算出负坐标`() {
        // ⚠️ 边界情况：如果屏幕很窄，"屏幕宽 - 42%" 仍然为正；
        //    但这一条要显式验证，因为负值会让构造直接抛。
        for ((w, h) in listOf(720 to 1280, 1080 to 1920, 1440 to 3200)) {
            val b = HostBounds.cornerOf(w, h)
            assertTrue("${w}x${h} 算出了负的 left", b.left >= 0)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  dumpsys 解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `从 dumpsys 输出解析 taskId`() {
        // 真机上的真实格式
        val real = """  * Task{2799da9 #24735 type=standard A=10304:com.tencent.mm U=0 visible=true"""
        assertEquals(24735, HostingCommands.parseTaskId(real))
    }

    @Test
    fun `解析不到时返回 null 而不是瞎猜`() {
        // ★ `am task resize` 对不存在的 taskId **也返回 0**（静默失败）。
        //   所以解析失败必须显式返回 null，让调用方去报错。
        assertNull(HostingCommands.parseTaskId(""))
        assertNull(HostingCommands.parseTaskId("no task here"))
        assertNull(HostingCommands.parseTaskId("Task{abc}"))
    }

    @Test
    fun `解析多个 task 时取第一个`() {
        val multi = """
  * Task{aaa #111 type=standard A=10001:com.a U=0
  * Task{bbb #222 type=standard A=10002:com.b U=0
        """.trimIndent()
        assertEquals(111, HostingCommands.parseTaskId(multi))
    }
}

/**
 * 能力边界文案的测试。
 *
 * ★ 这不是"文档测试"，是**产品承诺测试**：
 *   用户点托管之前看到的话，必须与真机实测一致。
 */
class HostingLimitationTest {

    @Test
    fun `必须明确说窗口看得见`() {
        val all = HostingLimitation.items.joinToString(" ") { it.title + it.detail }
        assertTrue(
            "必须诚实说明小窗可见（真机实测负坐标被拒）：$all",
            "看得见" in all || "不是完全隐藏" in all,
        )
    }

    @Test
    fun `必须说明需要 root`() {
        val all = HostingLimitation.items.joinToString(" ") { it.title + it.detail }
        assertTrue("必须说明需要 root：$all", "root" in all)
    }

    @Test
    fun `必须说明真正的虚拟屏这一版没有`() {
        // ⚠️ 用户明确要求"虚拟屏"。做不到就必须说 ——
        //    藏着会让用户以为功能坏了。
        val all = HostingLimitation.items.joinToString(" ") { it.title + it.detail }
        assertTrue("必须说明虚拟屏缺席：$all", "虚拟屏" in all)
        assertTrue("要说明原因（Shizuku）：$all", "Shizuku" in all || "系统签名" in all)
    }

    @Test
    fun `每条限制都要有标题和说明`() {
        for (item in HostingLimitation.items) {
            assertTrue("标题不能为空", item.title.isNotBlank())
            assertTrue("说明不能太短：${item.title}", item.detail.length >= 20)
        }
    }
}
