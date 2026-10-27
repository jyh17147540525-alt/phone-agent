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
    fun `自由窗口启动用 monkey 而不是 am start`() {
        // ★ 用 monkey 的理由：`am start` 需要知道具体 Activity 名，
        //   而每个应用的 launcher Activity 都不一样。
        val cmd = HostingCommands.startFreeform("com.tencent.mm")
        assertTrue("应当用 monkey：$cmd", cmd.startsWith("monkey"))
        assertTrue("要指定包名：$cmd", cmd.contains("com.tencent.mm"))
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
