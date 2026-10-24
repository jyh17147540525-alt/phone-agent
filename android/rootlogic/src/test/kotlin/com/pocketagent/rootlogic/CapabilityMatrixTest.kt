package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力梯度与边界判定的测试。
 *
 * ★ 这些测试钉的不是"功能有没有"，而是**边界说得对不对**。
 *   用户明确要求"非 root 的上限就到那里，没有 root 办不到的就办不到，
 *   要划清边界" —— 所以最重要的是：
 *   1. 每条"做不到"都必须**说清为什么**（不能只说"不支持"）
 *   2. 每种探测结论都必须给出**不同的、可操作的**下一步
 *   3. root 之后解锁的必须**真的是普通模式做不到的**
 */
class CapabilityMatrixTest {

    @Test
    fun `读屏与点按在两种模式下都必须可用`() {
        // ★ 这是产品的立身之本：如果读屏需要 root，整个应用就只能服务
        //   root 用户，那和用户的"非 root 也能用"要求直接冲突。
        for (id in listOf("screen.read", "screen.tap")) {
            val cap = CapabilityMatrix.byId(id)
            assertNotNull("能力表里必须有 $id", cap)
            assertTrue("$id 在普通模式下必须可用", cap!!.isUsableIn(PrivilegeMode.NON_ROOT))
            assertTrue("$id 在 root 模式下必须可用", cap.isUsableIn(PrivilegeMode.ROOT))
        }
    }

    @Test
    fun `每条做不到的能力都必须说清为什么`() {
        // ⚠️ 只写"不支持"等于没说。用户会以为是应用偷懒。
        val unavailable = CapabilityMatrix.all.filter {
            it.nonRoot is CapabilityTier.Unavailable || it.root is CapabilityTier.Unavailable
        }
        assertTrue("应当存在一些「普通模式做不到」的能力，否则这张表没有意义", unavailable.isNotEmpty())

        for (cap in unavailable) {
            listOf(cap.nonRoot, cap.root).filterIsInstance<CapabilityTier.Unavailable>().forEach {
                assertTrue(
                    "能力 ${cap.id} 的「做不到」理由太短，说不清原因：${it.why}",
                    it.why.length >= 20,
                )
            }
        }
    }

    @Test
    fun `部分可用的能力必须同时说明怎么补和还差什么`() {
        // ⚠️ 只说"部分可用"是含糊的。用户要知道两件事：
        //   ① 我现在怎么把它开起来 ② root 之后还能多得到什么
        val degraded = CapabilityMatrix.all.filter {
            it.nonRoot is CapabilityTier.Degraded || it.root is CapabilityTier.Degraded
        }
        assertTrue("系统设置这条应当是「部分可用」，否则梯度没体现出来", degraded.isNotEmpty())

        for (cap in degraded) {
            listOf(cap.nonRoot, cap.root).filterIsInstance<CapabilityTier.Degraded>().forEach {
                assertTrue("${cap.id} 的 how 不能为空", it.how.isNotBlank())
                assertTrue("${cap.id} 的 missing 不能为空", it.missing.isNotBlank())
            }
        }
    }

    @Test
    fun `root 解锁的必须是普通模式真的做不到的`() {
        // ★ 这条防止一种误导：把"普通模式也能做、只是麻烦点"的能力
        //   说成"root 解锁"，等于骗用户去 root。
        val unlocked = CapabilityMatrix.unlockedByRoot()

        assertTrue("应当有 root 才能解锁的能力", unlocked.isNotEmpty())
        for (cap in unlocked) {
            assertFalse(
                "能力 ${cap.id} 在普通模式下其实可用，不该算作「root 解锁」",
                cap.isUsableIn(PrivilegeMode.NON_ROOT),
            )
            assertTrue(
                "能力 ${cap.id} 被算作解锁了，但 root 模式下也不可用",
                cap.isUsableIn(PrivilegeMode.ROOT),
            )
        }
    }

    @Test
    fun `root 命令执行本身必须是普通模式做不到的`() {
        // 这是整张表最核心的一条：没有 root 就没有这条通道，没有任何替代方案。
        val shell = CapabilityMatrix.byId("shell.root")
        assertNotNull("必须有 shell.root 这条", shell)
        assertFalse("root 命令执行不能出现在普通模式里", shell!!.isUsableIn(PrivilegeMode.NON_ROOT))
        assertTrue("root 模式下必须可用", shell.isUsableIn(PrivilegeMode.ROOT))
    }

    @Test
    fun `普通模式下做不到的能力清单非空且每条都带理由`() {
        val blocked = CapabilityMatrix.unavailableIn(PrivilegeMode.NON_ROOT)

        assertTrue("普通模式应当确实有一些做不到的事（这是要划清的边界）", blocked.isNotEmpty())
        // 界面要能逐条列出理由 —— 没有理由的条目没法显示。
        for (cap in blocked) {
            val tier = cap.tierIn(PrivilegeMode.NON_ROOT)
            assertTrue("$cap.id 在普通模式下被列为不可用，但 tier 不是 Unavailable", tier is CapabilityTier.Unavailable)
        }
    }

    @Test
    fun `能力的 id 必须唯一`() {
        // id 是日志与审计的稳定标识，重复会让"是哪条能力"变得无法定位。
        val ids = CapabilityMatrix.all.map { it.id }
        assertEquals("能力 id 有重复：${ids.groupBy { it }.filter { it.value.size > 1 }.keys}", ids.size, ids.toSet().size)
    }
}

/**
 * root 探测结论 → 用户下一步的测试。
 *
 * ★ 这块的核心不是技术，是**别让用户走错方向**。
 *   [RootProbeResult.Denied] 被当成"没 root"是本项目最怕的那种误导：
 *   用户明明刚 root 完，却被告知没有 root。
 */
class RootProbeExplainTest {

    @Test
    fun `被拒绝时必须点名 root 管理器，且明确不是没 root`() {
        val text = RootProbeResult.Denied.explain()

        assertTrue("必须提到 Magisk 或 root 管理器，否则用户不知道该去哪放行：$text",
            text.contains("Magisk") || text.contains("root 管理器"))
        assertTrue("必须明确「不是手机没 root」，否则用户会去重新 root：$text",
            text.contains("不是") && text.contains("没 root"))
    }

    @Test
    fun `没 root 时必须说明这是正常状态而不是故障`() {
        val text = RootProbeResult.NotRooted.explain()

        // ★ 关键在于措辞：写成"未检测到 root，部分功能不可用"会让用户以为坏了。
        assertTrue("必须说明普通模式已经可用，不是坏掉了：$text",
            text.contains("全部可用") || text.contains("已经全部"))
        assertTrue("必须指出那是系统的限制，不是本应用的限制：$text",
            text.contains("系统") || text.contains("不是本应用"))
    }

    @Test
    fun `四种结论的解释必须互不相同`() {
        // ⚠️ 如果两种结论给出同一句话，那"分开四种"就白分了。
        val texts = listOf(
            RootProbeResult.Available("uid=0(root) context=u:r:magisk:s0").explain(),
            RootProbeResult.NotRooted.explain(),
            RootProbeResult.Denied.explain(),
            RootProbeResult.Failed("su 超时").explain(),
        )
        assertEquals("四种结论的解释不能有重复", texts.size, texts.toSet().size)
    }

    @Test
    fun `执行失败时必须带上原因`() {
        val text = RootProbeResult.Failed("授权超时").explain()
        assertTrue("要把失败原因带给用户：$text", text.contains("授权超时"))
    }
}

/**
 * 模式本身的测试。
 */
class PrivilegeModeTest {

    @Test
    fun `两种模式的文案必须能让人一眼看出差别`() {
        val non = PrivilegeMode.NON_ROOT
        val root = PrivilegeMode.ROOT

        assertTrue("普通模式必须点明「不需要 root」", non.summary.contains("不需要 root"))
        assertTrue("root 模式必须点明风险", root.summary.contains("不可撤销") || root.summary.contains("风险"))
        assertTrue("两种模式的说明不能相同", non.summary != root.summary)
    }

    @Test
    fun `普通模式的说明必须包含边界而不只是功能列表`() {
        // ★ 用户明确要求"要划清边界"。所以普通模式的文案必须承认它做不到什么，
        //   而不是只罗列能做什么。
        val text = PrivilegeMode.NON_ROOT.summary
        assertTrue("普通模式必须承认存在边界：$text",
            text.contains("做不到") || text.contains("边界") || text.contains("不给"))
    }
}
