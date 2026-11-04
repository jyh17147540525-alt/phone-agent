package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具装配的测试。
 *
 * ★ 钉的是"模型看到的世界"：
 *   1. 被挡住的工具**不能悄悄消失** —— 必须带理由留在清单里
 *   2. 每个设备工具都必须有归属（装上 / 挡住 / 永远可用），漏一个就是 bug
 *   3. 普通模式跑起来必须提前说"要占屏"
 */
class TierPlanTest {

    private val plainReady = DeviceState(accessibilityOn = true, overlayAllowed = true)
    private val plainMissing = DeviceState()
    private val rootReady = DeviceState(
        accessibilityOn = true,
        overlayAllowed = true,
        mediaProjectionGranted = true,
        secureSettingsGranted = true,
        suAvailable = true,
        rootDaemonAlive = true,
    )

    @Test
    fun `普通模式有点按能力，但整趟要占屏`() {
        val plan = TierPlanner.plan(PrivilegeMode.NON_ROOT, plainReady)
        val tap = plan.mounted.first { it.tool == "android_tap" }
        assertEquals(ExecPath.A11Y_GESTURE, tap.path)
        assertEquals(Occupancy.FOREGROUND, tap.occupancy)
        assertTrue(plan.requiresScreen)
        assertTrue(plan.screenNotice().contains("占住屏幕"))
    }

    @Test
    fun `root 满血时整趟不占屏`() {
        val plan = TierPlanner.plan(PrivilegeMode.ROOT, rootReady)
        val tap = plan.mounted.first { it.tool == "android_tap" }
        assertEquals(ExecPath.ROOT_INJECT, tap.path)
        assertFalse(plan.requiresScreen)
        assertTrue(plan.screenNotice().contains("后台"))
        assertTrue("满血不该有降级项", plan.degraded.isEmpty())
    }

    @Test
    fun `被挡住的工具留在清单里并说明原因`() {
        // ★ 工具消失 → 模型换姿势乱试。留着 + 说原因，它才会换方案或报告。
        val plan = TierPlanner.plan(PrivilegeMode.NON_ROOT, plainMissing)
        val tap = plan.blocked.first { it.tool == "android_tap" }
        val text = tap.explain()
        assertTrue("要说清缺什么：$text", text.contains("无障碍"))
        assertTrue("要给出去哪开：$text", text.contains("设置"))
        assertFalse("被挡的工具不能同时又挂在可用清单里", "android_tap" in plan.toolNames)
    }

    @Test
    fun `只有 root 能做的那条在普通模式下讲的是系统限制`() {
        val plan = TierPlanner.plan(PrivilegeMode.NON_ROOT, plainReady)
        val root = plan.blocked.first { it.tool == "root_exec" }
        val text = root.explain()
        assertTrue("要说清是系统不给：$text", text.contains("系统"))
        assertTrue(root.verdict is Verdict.NeedRoot)
    }

    @Test
    fun `su 被拒会降级并保留点按能力`() {
        // 2026-11-04：前置条件从"常驻通道"改成 su 授权后，**要造的是 su 被拒**
        // 这件事（Magisk 里点了拒绝/授权超时），而不是通道没起。
        val suDenied = rootReady.copy(suAvailable = false)
        val plan = TierPlanner.plan(PrivilegeMode.ROOT, suDenied)
        val tap = plan.mounted.first { it.tool == "android_tap" }
        assertTrue("必须标降级", tap.degraded)
        assertEquals(ExecPath.A11Y_GESTURE, tap.path)
        assertTrue("降级就得承认占屏", plan.requiresScreen)
        assertEquals(listOf("android_tap"), plan.degraded.map { it.tool }.filter { it == "android_tap" })
    }

    @Test
    fun `纯逻辑工具与档位无关`() {
        val plan = TierPlanner.plan(PrivilegeMode.NON_ROOT, plainMissing)
        // 一条权限都没有，纯逻辑工具照样在
        for (name in DefaultFreeTools.names) {
            assertTrue("$name 不该受档位影响", name in plan.toolNames)
        }
    }

    @Test
    fun `每个设备工具都有归属`() {
        // ★ 漏网的工具会既不在 mounted 也不在 blocked —— 那就是"装了但没人知道"。
        for (state in listOf(plainReady, plainMissing, rootReady)) {
            for (mode in PrivilegeMode.entries) {
                val plan = TierPlanner.plan(mode, state)
                val all = plan.mounted.map { it.tool } + plan.blocked.map { it.tool }
                assertEquals(
                    "$mode / $state 下工具对不上：$all",
                    ToolBindings.all.map { it.tool }.sorted(),
                    all.sorted(),
                )
            }
        }
    }

    @Test
    fun `工具名不重复`() {
        val plan = TierPlanner.plan(PrivilegeMode.ROOT, rootReady)
        assertEquals(plan.toolNames.size, plan.toolNames.toSet().size)
    }

    @Test
    fun `单条判定与整份计划一致`() {
        // 两处不一致会让"任务跑到一半"和"装配时"看到两个世界。
        val plan = TierPlanner.plan(PrivilegeMode.NON_ROOT, plainReady)
        for (m in plan.mounted) {
            val v = TierPlanner.resolveTool(m.tool, PrivilegeMode.NON_ROOT, plainReady)
            assertTrue("${m.tool} 单条判定应为 Ready，实际 $v", v is Verdict.Ready)
        }
        for (b in plan.blocked) {
            val v = TierPlanner.resolveTool(b.tool, PrivilegeMode.NON_ROOT, plainReady)
            assertNotEquals("${b.tool} 单条判定不该是 Ready", true, v is Verdict.Ready)
        }
    }

    @Test
    fun `纯逻辑工具单条判定永远可用`() {
        val v = TierPlanner.resolveTool("persona_brief", PrivilegeMode.NON_ROOT, plainMissing)
        assertTrue(v is Verdict.Ready)
        assertEquals(Occupancy.NONE, (v as Verdict.Ready).occupancy)
    }

    @Test
    fun `不认识的工具不假装能用`() {
        assertEquals(
            Verdict.NotImplemented,
            TierPlanner.resolveTool("no_such_tool", PrivilegeMode.ROOT, rootReady),
        )
    }

    @Test
    fun `什么都不缺时不提示占屏`() {
        // 没有任何设备工具被挂上（例如纯对话）→ 不该吓唬用户。
        val plan = TierPlanner.plan(PrivilegeMode.NON_ROOT, plainMissing, free = DefaultFreeTools.names)
        assertEquals(0, plan.mounted.size)
        assertFalse(plan.requiresScreen)
        assertTrue(plan.screenNotice().contains("不需要"))
    }

    @Test
    fun `root 模式下可选不回落`() {
        // ★ 2026-11-04 改了两轮，现在是 **su 语义**：
        //   root 路径的前置条件是 su 授权（不是常驻通道），所以
        //   · su 通了 → 点按/读屏都挂上，且不占屏
        //   · su 没通 → 一条都不该挂（allowFallback=false 时不许硬跑无障碍）
        val mountedPlan = TierPlanner.plan(PrivilegeMode.ROOT, rootReady, allowFallback = false)
        assertTrue("su 通了，点按该挂上", mountedPlan.mounted.any { it.tool == "android_tap" })
        assertTrue("su 通了，读屏该挂上", mountedPlan.mounted.any { it.tool == "screen_read" })
        assertTrue("root 路径不该占屏", mountedPlan.mounted.none { it.occupancy == Occupancy.FOREGROUND })

        val suDead = rootReady.copy(suAvailable = false)
        val plan = TierPlanner.plan(PrivilegeMode.ROOT, suDead, allowFallback = false)
        assertTrue(
            "su 没通、又不许回落时，一条设备工具都不该挂着：${plan.mounted.map { it.tool }}",
            plan.mounted.none { it.tool == "android_tap" || it.tool == "screen_read" },
        )
        val tapBlocked = plan.blocked.first { it.tool == "android_tap" }
        assertTrue("被挡的原因要说去 Magisk 放行：${tapBlocked.explain()}", tapBlocked.explain().contains("Magisk"))
    }

    @Test
    fun `网关问到的原因能直接给用户看`() {
        // ★ 这句话会原样进 tools/list 的描述尾部与 isError 结果。
        //   空串、英文占位符、以及"未实现"混进"缺权限"都会误导模型。
        val why = TierPlanner.blockReason("android_tap", PrivilegeMode.NON_ROOT, plainMissing)
        assertTrue("缺无障碍要说明白：$why", why!!.contains("无障碍"))
        assertTrue("要给出去哪开：$why", why.contains("设置"))
        assertTrue("要写出工具名，模型才知道在说哪一条：$why", why.contains("android_tap"))
    }

    @Test
    fun `可用时网关返回空`() {
        assertNull(TierPlanner.blockReason("android_tap", PrivilegeMode.NON_ROOT, plainReady))
        assertNull(TierPlanner.blockReason("android_tap", PrivilegeMode.ROOT, rootReady))
        assertNull("纯逻辑工具永远不该被拦", TierPlanner.blockReason("persona_brief", PrivilegeMode.NON_ROOT, plainMissing))
    }

    @Test
    fun `root 满血时 root_exec 不再被拦`() {
        assertNull(TierPlanner.blockReason("root_exec", PrivilegeMode.ROOT, rootReady))
        assertNotNull(TierPlanner.blockReason("root_exec", PrivilegeMode.NON_ROOT, rootReady))
    }
}
