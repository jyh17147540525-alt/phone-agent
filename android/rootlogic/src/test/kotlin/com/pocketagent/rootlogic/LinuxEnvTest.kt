package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三档探测的纯逻辑。
 *
 * 每条断言背后都是"判错会怎样"：把 `NEEDS_DOWNLOAD` 判成 `ABSENT`，用户会以为
 * 这台手机不支持，直接放弃；把 `BROKEN` 判成 `ABSENT`，他会去装第二遍 Termux，
 * 而真正的原因是 proot 被 ROM 拦了 —— 那是完全不同的下一步。
 */
class LinuxEnvTest {

    private val work = "/data/user/0/com.pocketagent/files/linux/work"

    @Test
    fun `什么都没有时是没装，而不是坏了`() {
        val env = LinuxEnvProbe.decide(LinuxEnvProbe.Facts(workDirExists = true), work)

        assertEquals(LinuxState.ABSENT, env.state)
        assertFalse(env.usable)
        assertTrue("要告诉用户可以怎么补", env.reason.contains("root") || env.reason.contains("Termux"))
    }

    @Test
    fun `root 的 chroot 优先于已经存在的容器`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(
                rootChroot = true,
                externalProot = true,
                workDirExists = true,
            ),
            work,
            externalRootfs = "/data/data/com.termux/files/usr/var/lib/proot-distro/debian",
        )

        assertEquals(LinuxKind.ROOT_CHROOT, env.kind)
        assertEquals(LinuxState.READY, env.state)
        assertNull("chroot 用的是宿主根，不需要额外 rootfs 字段", env.rootfsPath)
    }

    @Test
    fun `已有容器就能直接用，体积零`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(externalProot = true, workDirExists = true),
            work,
            externalRootfs = "/data/data/com.termux/files/usr/var/lib/proot-distro/debian",
        )

        assertEquals(LinuxState.READY, env.state)
        assertEquals(LinuxKind.EXTERNAL_PROOT, env.kind)
        assertTrue(env.rootfsPath!!.contains("termux"))
    }

    @Test
    fun `有容器但拿不到 rootfs 路径时不算就绪`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(externalProot = true, workDirExists = true),
            work,
            externalRootfs = null,
        )

        assertEquals(LinuxState.ABSENT, env.state)
    }

    @Test
    fun `自带 proot 且 rootfs 在就是就绪`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(bundledProot = true, rootfsReady = true, workDirExists = true),
            work,
            bundledRootfs = "/data/user/0/com.pocketagent/files/linux/rootfs",
        )

        assertEquals(LinuxState.READY, env.state)
        assertEquals(LinuxKind.BUNDLED_PROOT, env.kind)
    }

    @Test
    fun `自带 proot 但还没下载时是待下载，不是没装`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(bundledProot = true, workDirExists = true),
            work,
            bundledRootfs = "/data/user/0/com.pocketagent/files/linux/rootfs",
        )

        assertEquals(LinuxState.NEEDS_DOWNLOAD, env.state)
        assertTrue("要说清补什么", env.reason.contains("下载"))
    }

    @Test
    fun `工作区建不起来时一律算坏了`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(rootChroot = true, workDirExists = false),
            work,
        )

        assertEquals(LinuxState.BROKEN, env.state)
        assertTrue(env.reason.contains("工作区"))
    }

    @Test
    fun `探测到的明确原因要原样告知`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(workDirExists = true, brokenReason = "proot 被这台机器的系统拦了（ptrace 不可用）"),
            work,
        )

        assertEquals(LinuxState.BROKEN, env.state)
        assertTrue(env.explain().contains("ptrace"))
    }

    @Test
    fun `每种状态都有一句能读给用户听的话`() {
        val states = listOf(
            LinuxEnv(LinuxState.READY, LinuxKind.EXTERNAL_PROOT, "/r", work),
            LinuxEnv(LinuxState.NEEDS_DOWNLOAD, LinuxKind.BUNDLED_PROOT, "/r", work, "大约 200 MB"),
            LinuxEnv(LinuxState.ABSENT, null, null, work, "需要 root 或 Termux"),
            LinuxEnv(LinuxState.BROKEN, null, null, work, "proot 起不来"),
        )
        for (env in states) {
            assertTrue(env.explain().isNotBlank())
            assertFalse("不该把堆栈丢给用户", env.explain().contains("Exception"))
        }
    }

    @Test
    fun `就绪时的话里要写清唯一可写目录`() {
        val env = LinuxEnv(LinuxState.READY, LinuxKind.ROOT_CHROOT, null, work)
        assertTrue(env.explain().contains(LinuxEnv.INNER_WORK_DIR))
    }

    @Test
    fun `系统禁止执行时是 BLOCKED 而不是没装`() {
        // 这种情况落在 ABSENT 会让用户去装 Termux —— 白折腾，因为真正原因是系统策略
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(workDirExists = true, execBlocked = true),
            work,
        )

        assertEquals(LinuxState.BLOCKED, env.state)
        assertTrue(env.explain().contains("不允许"))
        assertTrue("要说清替代路径", env.explain().contains("root") || env.explain().contains("Termux"))
    }

    @Test
    fun `禁止执行优先于已探测到的容器`() {
        val env = LinuxEnvProbe.decide(
            LinuxEnvProbe.Facts(rootChroot = true, workDirExists = true, execBlocked = true),
            work,
        )
        assertEquals(LinuxState.BLOCKED, env.state)
    }

    @Test
    fun `容器内挂载点固定是斜杠 work`() {
        assertEquals("/work", LinuxEnv.INNER_WORK_DIR)
    }
}
