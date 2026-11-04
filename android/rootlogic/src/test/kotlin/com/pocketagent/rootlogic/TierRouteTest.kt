package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两档路由的测试。
 *
 * ★ 这里钉的是三件产品承诺，不是实现细节：
 *   1. **普通用户永远不需要 root**：选普通模式时，代码连 root 路径都不该看
 *   2. **root 的收益是"不占屏"**，不是"多几个按钮" —— 每条能力的
 *      root 路径打扰程度不得高于普通路径
 *   3. **降级必须说出来**：root 用不了时任务要继续跑，但必须标出
 *      `degraded` 与"这一趟要占住屏幕"，不能静默降级
 */
class TierRouteTest {

    private val rootReady = DeviceState(
        accessibilityOn = true,
        overlayAllowed = true,
        mediaProjectionGranted = true,
        suAvailable = true,
        rootDaemonAlive = true,
    )

    private val noPermission = DeviceState()

    // ── 1. 普通模式：不许碰 root ────────────────────────────────

    @Test
    fun `普通模式只走无障碍，即使 root 已经就绪`() {
        // ★ 这是"两档"的定义：档位由用户选，不由探测结果决定。
        //   探测到 root 就偷偷走 root 路径，等于替用户做了安全决定。
        val v = TierRouter.resolve("screen.tap", PrivilegeMode.NON_ROOT, rootReady)
        assertTrue(v is Verdict.Ready)
        val ready = v as Verdict.Ready
        assertEquals(ExecPath.A11Y_GESTURE, ready.path)
        assertFalse("普通模式不该被判成降级", ready.degraded)
    }

    @Test
    fun `普通模式读屏与点按都要占住屏幕`() {
        // ★ 这条是产品形态：没有 root 就得把目标应用推到前台，
        //   这段时间用户的手机被这次任务占着。界面必须提前说。
        for (id in listOf("screen.read", "screen.tap")) {
            val v = TierRouter.resolve(id, PrivilegeMode.NON_ROOT, rootReady) as Verdict.Ready
            assertEquals("$id 在普通模式必须占屏", Occupancy.FOREGROUND, v.occupancy)
            assertTrue("$id 的屏幕占用要被识别出来", TierRouter.occupiesScreen(v))
        }
    }

    // ── 2. root：满血且不占屏 ───────────────────────────────────

    @Test
    fun `root 就绪时点按走注入且不占屏`() {
        val v = TierRouter.resolve("screen.tap", PrivilegeMode.ROOT, rootReady) as Verdict.Ready
        assertEquals(ExecPath.ROOT_INJECT, v.path)
        assertEquals(Occupancy.NONE, v.occupancy)
        assertFalse(v.degraded)
        assertFalse("满血时不该占屏", TierRouter.occupiesScreen(v))
    }

    @Test
    fun `root 路径要么是 root 通道，要么与普通路径完全一致`() {
        // ★ 例外是刻意留的：`file.saf` 两档都走 SAF —— 用户已经用系统
        //   选择器授权过的目录，绕过去只会让审计记录消失。
        //   所以这里允许"root 路径 = 普通路径"，但不允许"root 路径是
        //   第三条既不是 root 也不是普通路径的通道"。
        for (r in RouteTable.routes) {
            val root = r.root ?: continue
            val sameAsPlain = r.plain != null && r.plain.path == root.path
            assertTrue(
                "${r.id} 的 root 路径 ${root.path} 既不是 root 通道，也不等于普通路径",
                root.path.isRoot || sameAsPlain,
            )
            r.plain?.let {
                if (!sameAsPlain) assertFalse("${r.id} 的普通路径不能是 root 通道", it.path.isRoot)
            }
        }
    }

    @Test
    fun `root 不会比普通模式更打扰用户`() {
        // ★ 承诺：升级 root 之后，屏幕占用只能变少不能变多。
        for (r in RouteTable.routes) {
            val plain = r.plain ?: continue
            val root = r.root ?: continue
            assertTrue(
                "${r.id}: root 的占用(${root.occupancy})比普通模式(${plain.occupancy})更高",
                root.occupancy.weight <= plain.occupancy.weight,
            )
        }
    }

    @Test
    fun `只有 root 那条路能离开前台干活`() {
        // ★ 无障碍只能看/点当前窗口 —— 所以 plain 路径必须在任何情况都能
        //   在前台跑；而 root 注入/抓帧本来就与前台无关。
        for (r in RouteTable.routes) {
            r.root?.let {
                assertTrue("${r.id}: root 路径应当不依赖前台", it.worksOffForeground)
            }
        }
        for (id in listOf("screen.read", "screen.tap")) {
            val plain = RouteTable.byId(id)!!.plain!!
            assertFalse("$id 的无障碍路径必须依赖前台", plain.worksOffForeground)
        }
    }

    // ── 3. 降级：继续跑，但要说明代价 ───────────────────────────

    @Test
    fun `su 被拒就退回无障碍，并标明要占屏`() {
        // ★ Magisk 更新后授权重置是**高频**事件。这时候判"不可用"，
        //   等于用户手机还能用却被告知坏了。
        // （2026-11-04：root 路径的前置条件已从"常驻通道"改成 su 授权 ——
        //   `input tap` / `screencap` 走的是 `su -c`，今天就能跑。）
        val suDenied = rootReady.copy(suAvailable = false)
        val v = TierRouter.resolve("screen.tap", PrivilegeMode.ROOT, suDenied) as Verdict.Ready
        assertEquals(ExecPath.A11Y_GESTURE, v.path)
        assertTrue("必须标成降级", v.degraded)
        assertEquals(Occupancy.FOREGROUND, v.occupancy)
        assertTrue("说明里要点明占屏", v.note.contains("占住屏幕"))
        assertTrue("说明里要点明原因：${v.note}", v.note.contains("root 授权"))
    }

    @Test
    fun `关掉回落就不再偷偷退到普通模式`() {
        val suDenied = rootReady.copy(suAvailable = false)
        val v = TierRouter.resolve("screen.tap", PrivilegeMode.ROOT, suDenied, allowFallback = false)
        assertTrue(v is Verdict.NeedPermission)
        assertEquals(Permission.ROOT_SU, (v as Verdict.NeedPermission).permission)
    }

    @Test
    fun `两条路都不通时报的是 root 缺的那一条`() {
        // 用户既然选了 root 档，他真正缺的是 root 侧的东西，
        // 报"无障碍没开"会把他引去错误的方向。
        val v = TierRouter.resolve("screen.read", PrivilegeMode.ROOT, noPermission)
        assertTrue(v is Verdict.NeedPermission)
        assertEquals(Permission.ROOT_SU, (v as Verdict.NeedPermission).permission)
    }

    @Test
    fun `普通模式下缺无障碍就直说缺无障碍`() {
        val v = TierRouter.resolve("screen.tap", PrivilegeMode.NON_ROOT, noPermission)
        assertEquals(Permission.ACCESSIBILITY, (v as Verdict.NeedPermission).permission)
    }

    // ── 4. 边界：只有 root 能做的事，要说清是系统不给 ────────────

    @Test
    fun `只有 root 的能力在普通模式下报的是系统限制`() {
        for (id in listOf("file.any", "prop.set", "pm.disable", "shell.root")) {
            val v = TierRouter.resolve(id, PrivilegeMode.NON_ROOT, rootReady)
            assertTrue("$id 在普通模式应判 NeedRoot，实际 $v", v is Verdict.NeedRoot)
            val why = (v as Verdict.NeedRoot).why
            assertTrue("$id 必须说清是系统限制", why.contains("系统"))
            assertFalse("$id 不该说'不支持'了事", why.isBlank())
        }
    }

    @Test
    fun `root 模式下这些能力就是可用的`() {
        for (id in listOf("file.any", "prop.set", "pm.disable", "shell.root")) {
            val v = TierRouter.resolve(id, PrivilegeMode.ROOT, rootReady)
            assertTrue("$id 在 root 就绪时应可用，实际 $v", v is Verdict.Ready)
            assertFalse("$id 不该被误标降级", (v as Verdict.Ready).degraded)
        }
    }

    // ── 5. 引导必须可执行 ───────────────────────────────────────

    @Test
    fun `每条授权都有能照着走的一步`() {
        // ★ "请授权"不是引导。引导必须能落到某个开关上。
        for (p in Permission.entries) {
            assertTrue("${p.name} 缺引导", p.guide.length > 10)
            assertTrue(
                "${p.name} 的引导不像一个具体位置：${p.guide}",
                p.guide.contains("设置") || p.guide.contains("Magisk") || p.guide.contains("弹"),
            )
        }
    }

    @Test
    fun `缺权限时给的是引导而不是结论`() {
        val v = TierRouter.resolve("screen.read", PrivilegeMode.NON_ROOT, noPermission) as Verdict.NeedPermission
        assertTrue(v.permission.guide.contains("无障碍"))
    }

    // ── 6. 表与矩阵不许分叉 ─────────────────────────────────────

    @Test
    fun `路由表与能力矩阵一一对应`() {
        assertEquals("矩阵里有能力但路由表没写", emptyList<String>(), RouteTable.missingMatrixEntries())
        assertEquals("路由表里有能力但矩阵没写", emptyList<String>(), RouteTable.orphanRoutes())
    }

    @Test
    fun `每条矩阵里的能力都能给出结论`() {
        val plan = TierRouter.resolveAll(PrivilegeMode.ROOT, rootReady)
        assertEquals(CapabilityMatrix.all.size, plan.size)
        for ((id, verdict) in plan) {
            assertNotNull("$id 没有结论", verdict)
        }
    }

    @Test
    fun `面对没实现的能力不假装做不到`() {
        // ★ 区别很重要：NotImplemented 是"我们还没写"，
        //   NeedRoot 是"系统不给"。把前者说成后者，用户会去 root 一个不需要 root 的手机。
        assertEquals(Verdict.NotImplemented, TierRouter.resolve("no.such.thing", PrivilegeMode.ROOT, rootReady))
    }

    @Test
    fun `同一份状态两次判定结果一致`() {
        // 纯函数：路由不该依赖内部状态，否则界面与工具挂载会看到两份不同的世界。
        val a = TierRouter.resolveAll(PrivilegeMode.ROOT, rootReady)
        val b = TierRouter.resolveAll(PrivilegeMode.ROOT, rootReady)
        assertEquals(a, b)
    }

    @Test
    fun `普通模式的可执行计划里没有一条 root 通道`() {
        val plan = TierRouter.resolveAll(PrivilegeMode.NON_ROOT, rootReady)
        for ((id, v) in plan) {
            if (v is Verdict.Ready) {
                assertFalse("$id 在普通模式下选了 root 通道 ${v.path}", v.path.isRoot)
            }
        }
    }

    @Test
    fun `没授权时不该被判成占屏`() {
        // 占屏提示的前提是"真的开始跑了"。缺权限就提示占屏会让人白等。
        val v = TierRouter.resolve("screen.tap", PrivilegeMode.NON_ROOT, noPermission)
        assertFalse(TierRouter.occupiesScreen(v))
    }

    @Test
    fun `file_saf 两档走同一条路`() {
        // 用户已经用系统选择器授权过的目录，没有任何理由因为 root 就绕过 SAF ——
        // 绕过只会让审计记录消失。
        val plain = RouteTable.byId("file.saf")!!.plain!!
        val root = RouteTable.byId("file.saf")!!.root!!
        assertEquals(ExecPath.SAF, plain.path)
        assertEquals(ExecPath.SAF, root.path)
        assertNull("SAF 不需要额外授权", plain.needs)
        assertNull(root.needs)
    }
}
