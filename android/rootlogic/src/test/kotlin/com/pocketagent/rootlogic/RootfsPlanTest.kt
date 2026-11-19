package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * rootfs 安装的**纯逻辑**部分：路径、判据、文案。
 *
 * 真机上翻过的车都在这几处：
 *  · 拿"目录存在"当"装好了" ⇒ 解包被杀也显示就绪，chroot 报 no such file；
 *  · 小到只有几 KB 的错误页被当成 tar.gz 解 ⇒ 报"压缩包损坏"，用户不知道是 404；
 *  · 装了却忘了把 `/work` 挂进去 ⇒ 换个后端文件就"消失"。
 */
class RootfsPlanTest {

    private val layout = RootfsLayout(
        rootfsDir = "/data/user/0/com.pocketagent/files/linux/rootfs",
        workDir = "/data/user/0/com.pocketagent/files/linux/work",
    )

    @Test
    fun `容器里的 work 在 rootfs 下面，宿主工作区要挂到它上面`() {
        assertEquals("${layout.rootfsDir}/work", layout.innerWorkPath)
        assertTrue(layout.innerWorkPath.startsWith(layout.rootfsDir))
        // 两个目录不能是同一个：同一个的话"挂上去"就是自挂，mount 会直接失败
        assertFalse(layout.innerWorkPath == layout.workDir)
    }

    @Test
    fun `标记文件与 sh 的路径都在 rootfs 里`() {
        // ★ 标记必须在 rootfs **外面**：解包是 root 干的，容器目录连空文件都写不进去
        assertFalse(layout.markerPath.startsWith("${layout.rootfsDir}/"))
        assertTrue(layout.markerPath.startsWith(layout.rootfsDir))
        assertTrue(layout.markerPath.endsWith(".ready"))
        assertEquals("${layout.rootfsDir}/bin/sh", layout.shPath)
    }

    @Test
    fun `bin sh 是符号链接时也算装好了`() {
        // Alpine 的 bin/sh -> /bin/busybox。只认普通文件的话，完整的 rootfs 会被判成坏的。
        assertTrue(RootfsCheck.shellLikely(shIsRegularFile = false, shIsSymlink = true, busyboxExists = true))
        assertTrue(RootfsCheck.shellLikely(shIsRegularFile = false, shIsSymlink = false, busyboxExists = true))
        assertTrue(RootfsCheck.shellLikely(shIsRegularFile = true, shIsSymlink = false, busyboxExists = false))
        // 什么都没有才是真坏
        assertFalse(RootfsCheck.shellLikely(shIsRegularFile = false, shIsSymlink = false, busyboxExists = false))
        assertTrue(RootfsCheck.missingShellNote().contains("bin/sh"))
    }

    @Test
    fun `下载落点在 files 下的 linux 目录`() {
        assertEquals(
            "/data/user/0/com.pocketagent/files/linux/rootfs.tar.gz",
            layout.tarballOf("/data/user/0/com.pocketagent/files"),
        )
    }

    @Test
    fun `空文件和几 KB 的错误页都要被拦下`() {
        assertEquals("下载下来是空文件，多半是断网或被拦了。", RootfsProvision.checkDownload(0))
        // 404/502 的 HTML 通常 1–3 KB：解包只会得到"压缩包损坏"，看不出是 404
        val tiny = RootfsProvision.checkDownload(2_048)!!
        assertTrue(tiny.contains("2048"))
        assertTrue(tiny.contains("错误页"))
    }

    @Test
    fun `正常的根文件系统体积要放行`() {
        assertNull(RootfsProvision.checkDownload(RootfsProvision.MIN_TAR_BYTES))
        assertNull(RootfsProvision.checkDownload(3_600_000))
        assertNull(RootfsProvision.checkDownload(RootfsProvision.MAX_TAR_BYTES))
    }

    @Test
    fun `大到不像最小根文件系统要拦下`() {
        val tooBig = RootfsProvision.checkDownload(RootfsProvision.MAX_TAR_BYTES + 1)!!
        assertTrue(tooBig.contains("下错了链接"))
    }

    @Test
    fun `下载进度不知道总长时不给百分比`() {
        assertEquals(0.5f, (RootfsState.Downloading(50, 100).fraction as Float), 0.001f)
        assertNull(RootfsState.Downloading(50, -1).fraction)
        // 服务端给的长度比实际小的时候不许超过 1
        assertEquals(1f, (RootfsState.Downloading(120, 100).fraction as Float), 0.001f)
    }

    @Test
    fun `每一种状态都要有一句人话，且失败必须带原因`() {
        assertEquals(
            "下载下来是空文件，多半是断网或被拦了。",
            RootfsProvision.explain(RootfsState.Failed("下载下来是空文件，多半是断网或被拦了。")),
        )
        assertTrue(RootfsProvision.explain(RootfsState.Absent).contains("3.6 MB"))
        assertTrue(RootfsProvision.explain(RootfsState.Unpacking).contains("解包"))
        assertTrue(RootfsProvision.explain(RootfsState.Ready(3_600_000)).contains("就绪"))
        // 进度文案要带上已收到多少，否则用户只知道"在转圈"
        // 1_000_000 / 3_600_000 = 27.7% ⇒ 夹成 27（向下取整，不四舍五入夸大进度）
        assertTrue(RootfsProvision.explain(RootfsState.Downloading(1_000_000, 3_600_000)).contains("27%"))
        assertTrue(RootfsProvision.explain(RootfsState.Downloading(1_000_000, -1)).contains("已收到"))
    }

    @Test
    fun `能力说明不许夸大成能装软件包`() {
        val line = RootfsProvision.capabilityLine()
        assertTrue(line.contains("/work"))
        // 容器内不联网 ⇒ 装不了新包。这句话必须在界面上就说清
        assertTrue(line.contains("不联网") || line.contains("装不了"))
    }

    @Test
    fun `解包装载的两条命令都能被安全令牌放过`() {
        val payload = RootCommands.payload(
            RootAction.ProvisionRootfs(
                tarPath = "${layout.rootfsDir}.tar.gz",
                rootfsDir = layout.rootfsDir,
                workDir = layout.workDir,
            ),
        )
        assertTrue(payload.contains("tar -xf"))
        assertTrue(payload.contains("PA_ROOTFS_OK"))
        // 重装必须先清空：旧文件混进新 rootfs 会让"看起来装好了但 sh 是旧的"
        assertTrue(payload.contains("rm -rf "))
    }

    @Test
    fun `挂载命令是幂等的`() {
        val payload = RootCommands.payload(
            RootAction.MountWork(rootfsDir = layout.rootfsDir, workDir = layout.workDir),
        )
        assertTrue(payload.contains("mountpoint -q"))
        assertTrue(payload.contains("mount -o bind"))
        assertTrue(payload.contains("PA_MOUNT_OK"))
    }

    @Test
    fun `路径里有空格或分号时直接拒绝拼接`() {
        var thrown = false
        try {
            RootCommands.payload(
                RootAction.ProvisionRootfs(
                    tarPath = "/data/local/tmp/a b.tar.gz",
                    rootfsDir = layout.rootfsDir,
                    workDir = layout.workDir,
                ),
            )
        } catch (e: UnsafeRootArgument) {
            thrown = true
        }
        assertTrue("带空格的路径必须被 UnsafeRootArgument 拦下", thrown)
    }
}
