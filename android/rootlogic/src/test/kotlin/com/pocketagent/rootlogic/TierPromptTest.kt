package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位提示词段的测试。
 *
 * ★ 钉的是**它的边界**而不是措辞：短、只说三件事、不通的路给的是"怎么开"。
 */
class TierPromptTest {

    private val plainReady = DeviceState(
        accessibilityOn = true,
        overlayAllowed = true,
        secureSettingsGranted = true,
        suAvailable = false,
        rootDaemonAlive = false,
    )

    private val rootReady = DeviceState(
        accessibilityOn = true,
        overlayAllowed = true,
        secureSettingsGranted = true,
        suAvailable = true,
        rootDaemonAlive = false,
    )

    private fun section(mode: PrivilegeMode, state: DeviceState): String =
        TierPrompt.section(mode, TierPlanner.plan(mode, state))

    @Test
    fun `普通档说清会占屏`() {
        val s = section(PrivilegeMode.NON_ROOT, plainReady)
        assertTrue(s.contains("普通档"))
        assertTrue(s.contains("占用"))
        assertTrue(s.contains("屏幕"))
    }

    @Test
    fun `root 档说清不占屏`() {
        val s = section(PrivilegeMode.ROOT, rootReady)
        assertTrue(s.contains("root 档"))
        assertTrue(s.contains("不占用"))
    }

    @Test
    fun `不通的路带出怎么开`() {
        val s = section(PrivilegeMode.NON_ROOT, plainReady)
        // 普通档没有 root，file_read / app_disable 都不通
        assertTrue(s.contains("file_read"))
        assertTrue("应带设置路径：$s", s.contains("root") || s.contains("Magisk") || s.contains("系统"))
    }

    @Test
    fun `不通的路最多列四条`() {
        val s = section(PrivilegeMode.NON_ROOT, plainReady)
        val listed = s.split("；").filter { it.contains("（") }
        assertTrue("列太多会变成背诵表：$s", listed.size <= 4)
    }

    @Test
    fun `整段保持短`() {
        val s = section(PrivilegeMode.ROOT, rootReady)
        assertTrue("这一段每轮都追加，不该超过 400 字：${s.length}", s.length < 400)
    }

    @Test
    fun `没有任何能力时返回空串`() {
        val empty = TierPlan(PrivilegeMode.NON_ROOT, emptyList(), emptyList(), emptyList())
        assertEquals("", TierPrompt.section(PrivilegeMode.NON_ROOT, empty))
    }

    @Test
    fun `不说用户看不懂的内部词`() {
        val s = section(PrivilegeMode.NON_ROOT, plainReady)
        listOf("Verdict", "CapabilityRoute", "TierPlan", "ExecPath").forEach {
            assertFalse("提示词里不该出现内部类型名 $it：$s", s.contains(it))
        }
    }
}
