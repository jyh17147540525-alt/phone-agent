package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 借 Termux 那条路：请求构造 + 三个前提的预检。
 *
 * 这里钉两件事：①**只传程序名**，不传路径、不传整串脚本（否则白名单形同虚设）；
 * ②三种失败分开报 —— 用户要做的动作完全不同。
 */
class TermuxCommandsTest {

    @Test
    fun `普通命令被翻译成 Termux 里的绝对路径与参数`() {
        val request = TermuxCommands.build(listOf("python3", "-c", "print(2**100)"))

        assertEquals("/data/data/com.termux/files/usr/bin/python3", request.path)
        assertEquals(listOf("-c", "print(2**100)"), request.arguments)
        assertEquals(TermuxCommands.HOME, request.workdir)
        assertTrue(request.background)
    }

    @Test
    fun `只接受程序名，不接受路径`() {
        // 传 `/data/data/com.termux/files/usr/bin/python3` 会让"白名单"变成"随便什么都能跑"
        assertThrows(LinuxRejected::class.java) {
            TermuxCommands.build(listOf("/data/data/com.termux/files/usr/bin/sh", "-c", "id"))
        }
    }

    @Test
    fun `白名单之外的程序被拒`() {
        assertThrows(LinuxRejected::class.java) { TermuxCommands.build(listOf("bash", "-lc", "id")) }
        assertThrows(LinuxRejected::class.java) { TermuxCommands.build(listOf("curl", "http://x")) }
    }

    @Test
    fun `参数沿用本地那条路的校验`() {
        // 路径越界在本地会被拒，这里必须同样拒 —— 否则"本地拦了、Termux 放过去"
        assertThrows(LinuxRejected::class.java) {
            TermuxCommands.build(listOf("cat", "/etc/passwd"))
        }
        assertThrows(LinuxRejected::class.java) {
            TermuxCommands.build(listOf("python3", "-c", "print(1)\u0000"))
        }
    }

    @Test
    fun `空命令被拒`() {
        assertThrows(LinuxRejected::class.java) { TermuxCommands.build(emptyList()) }
    }

    @Test
    fun `工作目录与后台可以指定`() {
        val request = TermuxCommands.build(
            listOf("jq", ".items", "/work/a.json"),
            workdir = "/work",
            background = false,
        )
        assertEquals("/work", request.workdir)
        assertTrue(!request.background)
    }

    @Test
    fun `三个前提分开判`() {
        assertEquals(TermuxPreflight.NotInstalled, TermuxCommands.preflight(false, false, false))
        assertEquals(
            TermuxPreflight.ExternalAppsDisabled,
            TermuxCommands.preflight(true, externalAppsAllowed = false, permissionGranted = false),
        )
        assertEquals(
            TermuxPreflight.PermissionMissing,
            TermuxCommands.preflight(true, externalAppsAllowed = true, permissionGranted = false),
        )
        assertEquals(TermuxPreflight.Ready, TermuxCommands.preflight(true, true, true))
    }

    @Test
    fun `没开外部调用时要给出可以直接抄的那一行`() {
        val advice = TermuxCommands.advice(TermuxPreflight.ExternalAppsDisabled)
        assertTrue(advice.contains("allow-external-apps=true"))
        assertTrue(advice.contains("termux.properties"))
    }

    @Test
    fun `专有名词原样保留`() {
        val advice = TermuxCommands.advice(TermuxPreflight.PermissionMissing)
        assertTrue(advice.contains("com.termux.permission.RUN_COMMAND"))
        assertEquals("com.termux", TermuxCommands.PACKAGE)
        assertEquals("com.termux.RUN_COMMAND", TermuxCommands.ACTION)
    }
}
