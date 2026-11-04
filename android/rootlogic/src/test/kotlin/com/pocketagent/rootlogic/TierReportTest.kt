package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档位页内容的单元测试。
 *
 * ⚠️ 这里测的是**文案与状态的一致性**，不是排版。
 *    「明明缺无障碍却画成失败」「明明要占屏却说不用」这类问题
 *    在真机上一眼看不出来，只有断言能钉住。
 */
class TierReportTest {

    private val rootReady = DeviceState(
        accessibilityOn = true,
        overlayAllowed = true,
        mediaProjectionGranted = true,
        secureSettingsGranted = true,
        suAvailable = true,
        rootDaemonAlive = false,
    )

    private val plainReady = DeviceState(
        accessibilityOn = true,
        overlayAllowed = true,
        mediaProjectionGranted = true,
        secureSettingsGranted = true,
        suAvailable = false,
        rootDaemonAlive = false,
    )

    private fun report(mode: PrivilegeMode, state: DeviceState, probe: RootProbeResult?) =
        TierReports.build(mode, TierPlanner.plan(mode, state), probe)

    private fun TierReport.row(id: String): TierRow =
        rows.first { it.capabilityId == id }

    // ── 覆盖完整性 ──────────────────────────────────────────────

    @Test
    fun `矩阵里每条能力都在页面上出现且只出现一次`() {
        val r = report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted)
        assertEquals(CapabilityMatrix.all.size, r.rows.size)
        assertEquals(r.rows.size, r.rows.map { it.capabilityId }.toSet().size)
        assertEquals(CapabilityMatrix.all.map { it.id }.toSet(), r.rows.map { it.capabilityId }.toSet())
    }

    @Test
    fun `每行的标题都来自矩阵，不在这里另起名字`() {
        val r = report(PrivilegeMode.ROOT, rootReady, RootProbeResult.Available("uid=0(root)"))
        CapabilityMatrix.all.forEach { capability ->
            assertEquals(capability.title, r.row(capability.id).title)
        }
    }

    // ── 状态判定 ────────────────────────────────────────────────

    @Test
    fun `root 满血时读屏与点按都可用且不占屏`() {
        val r = report(PrivilegeMode.ROOT, rootReady, RootProbeResult.Available("uid=0(root)"))
        val read = r.row("screen.read")
        val tap = r.row("screen.tap")
        assertEquals(TierRowState.READY, read.state)
        assertEquals(TierRowState.READY, tap.state)
        assertFalse(read.occupiesScreen)
        assertFalse(tap.occupiesScreen)
        assertNull(read.hint)
    }

    @Test
    fun `普通档下读屏点按要占屏，而且行里必须说出来`() {
        val r = report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted)
        assertTrue(r.row("screen.read").occupiesScreen)
        assertTrue(r.row("screen.tap").occupiesScreen)
    }

    @Test
    fun `关掉无障碍时点按是缺授权，不是失败也不是需要 root`() {
        val r = report(
            PrivilegeMode.NON_ROOT,
            plainReady.copy(accessibilityOn = false),
            RootProbeResult.NotRooted,
        )
        val tap = r.row("screen.tap")
        assertEquals(TierRowState.NEED_PERMISSION, tap.state)
        assertEquals("缺「无障碍服务」", tap.hint)
        assertNotNull(tap.guide)
        assertTrue(tap.guide!!.contains("设置"))
    }

    @Test
    fun `只有 root 能给、且已有工具入口的能力，在普通档标成需要 root`() {
        val r = report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted)
        // shell.root 是唯一已经绑了工具（root_exec）的 root-only 能力
        val row = r.row("shell.root")
        assertEquals(TierRowState.NEED_ROOT, row.state)
        assertEquals("需要 root", row.hint)
        assertNotNull(row.guide)
    }

    @Test
    fun `能力层就绪但没绑工具的能力，说清楚是缺入口而不是缺实现`() {
        val r = report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted)
        // ★ 2026-11-04：这四条此前没有工具入口（页面写"缺入口"）。
        //   现在 file_read / setting_write / prop_write / app_disable 已挂上，
        //   于是它们在普通档的正确状态是"需要 root"，而不是"缺实现"。
        //   这个测试改成钉住**新的真相**：绑了工具 → 普通档说需要 root。
        listOf("file.any", "prop.set", "pm.disable").forEach { id ->
            val row = r.row(id)
            assertEquals("$id 应标成需要 root", TierRowState.NEED_ROOT, row.state)
            assertEquals("需要 root", row.hint)
            assertTrue(
                "$id 的引导应说清是系统权限：${row.guide}",
                row.guide!!.contains("系统") || row.guide.contains("root"),
            )
        }

        // ★ settings.write 是例外：它在普通档有路 —— adb 授予 WRITE_SECURE_SETTINGS
        //   后走 Intent。`plainReady` 里这个授权是 true，所以它**现在就该是 READY**。
        //   把它也断言成 NEED_ROOT 会掩盖"其实不用 root 也能改一部分设置"这件事。
        assertEquals(TierRowState.READY, r.row("settings.write").state)
    }

    @Test
    fun `确实没有工具入口的能力仍然如实说缺入口`() {
        // `file.saf` 至今没有 MCP 工具（它走 office_tools 的 SAF 通道，不是独立工具）。
        val r = report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted)
        val row = r.row("file.saf")
        assertEquals(TierRowState.NOT_IMPLEMENTED, row.state)
        assertTrue("应指向工具入口：${row.hint}", row.hint!!.contains("工具入口"))
    }

    @Test
    fun `root 档但 su 被拒时点按降级到无障碍并保留占屏提示`() {
        val r = report(
            PrivilegeMode.ROOT,
            rootReady.copy(suAvailable = false),
            RootProbeResult.Denied,
        )
        val tap = r.row("screen.tap")
        assertEquals(TierRowState.READY, tap.state)
        assertTrue(tap.occupyMsg(), tap.occupiesScreen)
        assertNotNull("降级必须带 note", tap.hint)
    }

    @Test
    fun `还没探测过 root 时不留空——按普通档结论走`() {
        val r = report(PrivilegeMode.NON_ROOT, plainReady, probe = null)
        // 没探测过不该把"缺 root"说成"没实现"：有绑工具的 root-only 能力必须是 NEED_ROOT
        assertEquals(TierRowState.NEED_ROOT, r.row("shell.root").state)
    }

    // ── headline / subline ─────────────────────────────────────

    @Test
    fun `三种处境的标题各不相同`() {
        val full = report(PrivilegeMode.ROOT, rootReady, RootProbeResult.Available("uid=0(root)")).headline
        val denied = report(PrivilegeMode.ROOT, rootReady, RootProbeResult.Denied).headline
        val plain = report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted).headline
        assertEquals("root 档：满血", full)
        assertEquals("root 档：root 没拿到", denied)
        assertEquals("普通档", plain)
        assertEquals(3, setOf(full, denied, plain).size)
    }

    @Test
    fun `普通档的副标题要点明有几条是档位差异而不是出错`() {
        val r = report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted)
        assertTrue(r.subline, r.subline.contains("要 root 才解锁"))
        assertTrue(r.subline, r.subline.contains("不是出错"))
    }

    @Test
    fun `缺授权时的副标题说的是点一下就好`() {
        val r = report(
            PrivilegeMode.NON_ROOT,
            plainReady.copy(accessibilityOn = false),
            RootProbeResult.NotRooted,
        )
        assertTrue(r.subline, r.subline.contains("点一下"))
    }

    @Test
    fun `root 满血下已绑工具的每一条都真的可用`() {
        val r = report(PrivilegeMode.ROOT, rootReady, RootProbeResult.Available("uid=0(root)"))
        val bound = ToolBindings.all.map { it.capabilityId }.toSet()
        val brokenReady = r.rows.filter { it.capabilityId in bound && it.state != TierRowState.READY }
        assertEquals("这些绑了工具却没就绪：${brokenReady.map { it.capabilityId }}", 0, brokenReady.size)
        assertTrue("实际：${r.subline}", r.subline.contains("条能用"))
    }

    // ── 动作 ────────────────────────────────────────────────────

    @Test
    fun `缺无障碍时给出打开无障碍设置的动作`() {
        val r = report(
            PrivilegeMode.NON_ROOT,
            plainReady.copy(accessibilityOn = false),
            RootProbeResult.NotRooted,
        )
        assertTrue(r.actions.any { it.target == TierActionTarget.OPEN_ACCESSIBILITY })
    }

    @Test
    fun `选了 root 但没通时给出 root 管理器放行的动作`() {
        val r = report(PrivilegeMode.ROOT, rootReady.copy(suAvailable = false), RootProbeResult.Denied)
        assertTrue(r.actions.any { it.target == TierActionTarget.OPEN_ROOT_MANAGER })
    }

    @Test
    fun `重新检测永远在最后，且不会重复`() {
        val r = report(
            PrivilegeMode.NON_ROOT,
            plainReady.copy(accessibilityOn = false, overlayAllowed = false),
            RootProbeResult.NotRooted,
        )
        assertEquals(TierActionTarget.RE_PROBE, r.actions.last().target)
        assertEquals(1, r.actions.count { it.target == TierActionTarget.RE_PROBE })
    }

    @Test
    fun `root 满血时不给多余的跳转，只剩重新检测`() {
        val r = report(PrivilegeMode.ROOT, rootReady, RootProbeResult.Available("uid=0(root)"))
        assertEquals(listOf(TierActionTarget.RE_PROBE), r.actions.map { it.target })
    }

    // ── 边界 ────────────────────────────────────────────────────

    @Test
    fun `readyCount 与 blockedCount 加起来等于总行数`() {
        listOf(
            report(PrivilegeMode.ROOT, rootReady, RootProbeResult.Available("uid=0(root)")),
            report(PrivilegeMode.NON_ROOT, plainReady, RootProbeResult.NotRooted),
            report(PrivilegeMode.NON_ROOT, DeviceState(), RootProbeResult.NotRooted),
        ).forEach { r ->
            assertEquals(r.rows.size, r.readyCount + r.blockedCount)
        }
    }

    @Test
    fun `什么都没有时每一行都给了下一步，不出现只有状态没有出路的行`() {
        val r = report(PrivilegeMode.NON_ROOT, DeviceState(), RootProbeResult.NotRooted)
        val stranded = r.rows.filter {
            it.state != TierRowState.READY && it.guide.isNullOrBlank() && it.hint.isNullOrBlank()
        }
        assertEquals("这些行只有状态没有出路：${stranded.map { it.capabilityId }}", 0, stranded.size)
    }

    private fun TierRow.occupyMsg(): String = "path=${pathLabel} screen=$occupiesScreen"
}
