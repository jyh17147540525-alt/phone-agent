package com.pocketagent.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令黑名单的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这块测试比其他任何一块都重要
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是 root 工具的最后一道闸。判错的后果是**用户的手机被一条
 * 模型生成的命令改坏**，而且 root 下没有回收站。
 *
 * 所以测试的重点是**放过检查**（该拦的没拦），而不是误拦：
 * 误拦只是"这条做不了"，用户能感知、能换做法；
 * 放过是**静默的、不可逆的**。
 */
class CommandBlocklistTest {

    private fun check(vararg argv: String): String? = CommandBlocklist.check(argv.toList())

    // ═══════════════════════════════════════════════════════════
    //  必须拦下的
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `格式化与刷写工具一律拦下`() {
        for (prog in listOf("mkfs", "mkfs.ext4", "mke2fs", "dd", "fastboot", "flash_image")) {
            assertNotNull("`$prog` 必须被拦 —— 它不可撤销", check(prog, "/dev/block/sda1"))
        }
    }

    @Test
    fun `递归删除根目录或系统目录必须拦下`() {
        // ★ 最关键的一组。`rm -rf /` 在 root 下等于毁机。
        val deadly = listOf(
            listOf("rm", "-rf", "/"),
            listOf("rm", "-rf", "/system"),
            listOf("rm", "-rf", "/data"),
            listOf("rm", "-rf", "/vendor"),
            listOf("rm", "-rf", "/sdcard"),
            listOf("rm", "-fr", "/"),
            listOf("rm", "-r", "/data/adb"),
            listOf("rm", "-Rf", "/system/"),
        )
        for (argv in deadly) {
            val why = check(*argv.toTypedArray())
            assertNotNull("`${argv.joinToString(" ")}` 必须被拦", why)
        }
    }

    @Test
    fun `用 sh 或 su 包装命令必须拦下`() {
        // ★ 黑名单是按程序名判的，套一层 shell 就能绕过。
        //   所以包装器本身必须拦 —— 我们提供的就是 argv 形式，不需要 sh -c。
        for (prog in listOf("sh", "bash", "su", "ash")) {
            assertNotNull("`$prog -c ...` 是绕过手段，必须拦", check(prog, "-c", "rm -rf /"))
        }
    }

    @Test
    fun `重启与关机必须拦下`() {
        // 不属于"运行时操作"：重启会中断所有任务，-edl 还可能进不了系统。
        assertNotNull(check("reboot"))
        assertNotNull(check("reboot", "-edl"))
        assertNotNull(check("poweroff"))
    }

    // ═══════════════════════════════════════════════════════════
    //  必须放过的（误拦会让功能不可用）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `删除具体文件必须放过`() {
        // ★ 与"删根目录"的差别就在这里：目标是具体文件时是合理操作。
        assertNull("删一个具体文件是合理的", check("rm", "/sdcard/Download/tmp.log"))
        assertNull(check("rm", "-f", "/data/local/tmp/x"))
    }

    @Test
    fun `普通的系统查询必须放过`() {
        assertNull(check("getprop", "ro.build.version.sdk"))
        assertNull(check("dumpsys", "battery"))
        assertNull(check("ls", "/system/app"))
        assertNull(check("pm", "list", "packages"))
    }

    // ═══════════════════════════════════════════════════════════
    //  只读判定
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `查询类命令被判为只读`() {
        for (argv in listOf(
            listOf("getprop", "ro.product.model"),
            listOf("ls", "/system"),
            listOf("dumpsys", "battery"),
            listOf("pm", "list", "packages"),
            listOf("settings", "get", "global", "adb_enabled"),
        )) {
            assertTrue("`${argv.joinToString(" ")}` 应当是只读", CommandBlocklist.isReadOnly(argv))
        }
    }

    @Test
    fun `同一个程序名下的写子命令不能被判为只读`() {
        // ★★ 这是最容易出错的地方：`pm` 既能 `list`（只读）也能 `uninstall`（写）。
        //    如果只按程序名判只读，`pm uninstall` 就会被当成只读而跳过安全判定。
        assertFalse("pm uninstall 是写操作", CommandBlocklist.isReadOnly(listOf("pm", "uninstall", "com.x")))
        assertFalse("pm disable 是写操作", CommandBlocklist.isReadOnly(listOf("pm", "disable", "com.x")))
        assertFalse("settings put 是写操作", CommandBlocklist.isReadOnly(listOf("settings", "put", "global", "x", "1")))
        assertFalse("settings delete 是写操作", CommandBlocklist.isReadOnly(listOf("settings", "delete", "global", "x")))
        assertFalse("svc ... enable 是写操作", CommandBlocklist.isReadOnly(listOf("svc", "wifi", "enable")))
    }

    @Test
    fun `未知程序默认不是只读`() {
        // ★ 白名单的意义就在这里：不认识的一律当写操作，走安全判定。
        //   黑名单漏一个就会静默放行一个写命令。
        assertFalse("未知程序不该被当只读", CommandBlocklist.isReadOnly(listOf("mycustomtool", "--do-something")))
    }

    @Test
    fun `空命令既不通过黑名单也不被判只读`() {
        assertNotNull("空命令必须被拦", check())
        assertFalse("空命令不是只读", CommandBlocklist.isReadOnly(emptyList()))
    }

    @Test
    fun `带路径的程序名也能正确识别`() {
        // ★ 绕过手段之一：写 `/system/bin/rm -rf /` 而不是 `rm -rf /`。
        //   所以判据必须取 basename。
        assertNotNull(
            "绝对路径形式的 rm 也必须被拦",
            check("/system/bin/rm", "-rf", "/"),
        )
        assertTrue(
            "绝对路径形式的 getprop 应当仍是只读",
            CommandBlocklist.isReadOnly(listOf("/system/bin/getprop", "ro.x")),
        )
    }
}
