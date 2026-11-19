package com.pocketagent.data

import android.content.Context
import com.pocketagent.rootlogic.RootAction
import com.pocketagent.rootlogic.RootActions
import com.pocketagent.rootlogic.RootfsLayout
import com.pocketagent.rootlogic.RootfsProvision
import com.pocketagent.rootlogic.RootfsState
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * root 容器根文件系统的安装器：**下载在应用进程，解包在 su**。
 *
 * ## 为什么下载不放给 su
 *
 * `linux_exec` 的白名单里刻意没有 `curl/wget`（决策 D3）。如果我们自己为了装
 * rootfs 让 `su` 跑一条 `curl`，那"root 通道不能联网"这条规矩就从后门破了。
 * 应用自己有 INTERNET 权限，用 `HttpURLConnection` 下到私有目录，再让 `su`
 * 只做一件纯本地的事（解包），职责就分清了。
 *
 * ## 为什么状态要往外抛而不返回值
 *
 * 下载是几十秒的事，界面要能显示进度。返回一个 `RootfsState` 只有最后一帧，
 * 中间那 40 秒用户看到的是"点了没反应" —— 那是最容易被当成卡死的形态。
 */
class RootfsInstaller(
    private val context: Context,
    private val actions: RootActions,
    private val layout: RootfsLayout,
    private val onState: (RootfsState) -> Unit,
) {

    /** 下载落点：`<files>/linux/rootfs.tar.gz`。下完就解包，留着下次重装能省一次下载。 */
    private val tarball: File get() = File(layout.tarballOf(context.filesDir.absolutePath))

    /**
     * 当前状态 —— **问的是磁盘，不是内存**。
     *
     * 标记文件存在即视为装好：解包被中途杀掉时目录也会存在，靠"目录在不在"判
     * 会把半成品当成可用，chroot 进去就是 `no such file or directory`。
     */
    /**
     * rootfs 里有没有能跑的 shell。
     *
     * `bin/sh` 在 Alpine 里是**符号链接**（→ /bin/busybox），`File.isFile` 会顺着链接
     * 去宿主找 `/bin/busybox` —— 于是完整的 rootfs 被判成"缺 bin/sh"。三种情况都算数。
     */
    private fun hasShell(): Boolean {
        val sh = File(layout.shPath)
        val busybox = File(layout.rootfsDir, "bin/busybox")
        val isLink = runCatching {
            java.nio.file.Files.isSymbolicLink(sh.toPath())
        }.getOrDefault(false)
        return com.pocketagent.rootlogic.RootfsCheck.shellLikely(
            shIsRegularFile = sh.isFile && !isLink,
            shIsSymlink = isLink,
            busyboxExists = busybox.isFile,
        )
    }

    fun probe(): RootfsState {
        val marker = File(layout.markerPath)
        if (!marker.isFile) return RootfsState.Absent
        if (!hasShell()) {
            return RootfsState.Failed(com.pocketagent.rootlogic.RootfsCheck.missingShellNote())
        }
        return RootfsState.Ready(
            com.pocketagent.rootlogic.RootfsCheck.bytesOfMarker(
                runCatching { marker.readText() }.getOrDefault(""),
            ),
        )
    }

    /**
     * 装一遍：下载 → 解包 → 挂 /work → 打标记。
     *
     * 每一步失败都**带原因**返回 [RootfsState.Failed]，绝不返回一个含糊的"失败"。
     */
    suspend fun install(url: String = RootfsProvision.ALPINE_URL) = withContext(Dispatchers.IO) {
        try {
            val dir = File(layout.rootfsDir).parentFile
            if (dir != null && !dir.isDirectory && !dir.mkdirs()) {
                return@withContext fail("建不了目录 ${dir.absolutePath}（存储满了或被拦了）。")
            }

            // 上次下好的包还在（且体积看着正常）就直接用：3.8 MB 在国内 CDN 上要两分钟，
            // 重装时再下一遍纯属折磨用户。tar 坏了会解包失败，那时才值得重下。
            val cached = tarball.isFile && RootfsProvision.checkDownload(tarball.length()) == null
            if (!cached) {
                onState(RootfsState.Downloading(0L, -1L))
                val error = download(url) { received, total ->
                    onState(RootfsState.Downloading(received, total))
                }
                if (error != null) return@withContext fail(error)
            }

            val bytes = tarball.length()
            val tooSmall = RootfsProvision.checkDownload(bytes)
            if (tooSmall != null) return@withContext fail(tooSmall)

            onState(RootfsState.Unpacking)
            val result = actions.run(
                RootAction.ProvisionRootfs(
                    tarPath = tarball.absolutePath,
                    rootfsDir = layout.rootfsDir,
                    workDir = layout.workDir,
                ),
            )
            if (!result.ok || !result.stdout.contains(PROVISION_MARK)) {
                return@withContext fail("解包失败（退出码 ${result.code}）：${result.stderr.trim().take(200)}")
            }
            if (!hasShell()) {
                return@withContext fail(com.pocketagent.rootlogic.RootfsCheck.missingShellNote())
            }

            mountWork()
            File(layout.markerPath).writeText(
                layout.markerText(url, bytes.coerceAtLeast(0L)),
            )
            Timber.i("rootfs 装好了：%s（%d 字节）", layout.rootfsDir, bytes)
            onState(RootfsState.Ready(bytes))
        } catch (e: Exception) {
            fail("安装中断：${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * 把 `<rootfs>/work` 挂到共享工作区。
     *
     * 重启后挂载会消失，所以 [RootLinuxOps.run] 每次跑命令前也会调一次；
     * 这里装了立刻挂，是为了让"装完就能用"。
     */
    suspend fun mountWork() = withContext(Dispatchers.IO) {
        if (isWorkMounted()) return@withContext true
        val result = actions.run(
            RootAction.MountWork(rootfsDir = layout.rootfsDir, workDir = layout.workDir),
        )
        val ok = result.ok && result.stdout.contains(MOUNT_MARK)
        if (!ok) Timber.w("挂 /work 失败：%s", result.stderr.trim().take(200))
        ok
    }

    /** 读 `/proc/mounts` 判断 —— 比再跑一次 `su mountpoint` 便宜得多。 */
    fun isWorkMounted(): Boolean = runCatching {
        val point = layout.innerWorkPath
        File("/proc/mounts").readLines().any { line ->
            line.split(' ').getOrNull(1) == point
        }
    }.getOrDefault(false)

    /**
     * 下载。
     *
     * @return null 表示成功；否则是给用户看的原因
     */
    private fun download(url: String, progress: (Long, Long) -> Unit): String? {
        var connection: HttpURLConnection? = null
        return try {
            // 手动跟随重定向：CDN 那边一层 302 很常见，而 Android 的
            // HttpURLConnection 默认跟随，但**跨协议**（http→https）会掉。
            var current = url
            var redirects = 0
            while (true) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", USER_AGENT)
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location")
                    c.disconnect()
                    if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                        return "下载被重定向太多次（停在 $current）。"
                    }
                    current = if (location.startsWith("http")) location else URL(URL(current), location).toString()
                    redirects++
                    continue
                }
                if (code != 200) {
                    c.disconnect()
                    return "下载失败：HTTP $code（$current）"
                }
                connection = c
                break
            }

            val c = connection ?: return "下载失败：没有拿到连接。"
            val total = c.contentLengthLong
            tarball.parentFile?.mkdirs()
            var received = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                tarball.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        received += n
                        // 每 256 KB 报一次：太密会让 Compose 每帧都重组
                        if (received - lastReport >= 256 * 1024) {
                            lastReport = received
                            progress(received, total)
                        }
                    }
                }
            }
            progress(received, total)
            null
        } catch (e: Exception) {
            Timber.w(e, "rootfs 下载失败")
            "下载失败：${e.message ?: e::class.simpleName}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun fail(reason: String): Unit {
        Timber.w("rootfs 安装失败：%s", reason)
        onState(RootfsState.Failed(reason))
    }

    private companion object {
        /** `su` 那边回显的哨兵：没有它就不能相信"退出码 0"。 */
        const val PROVISION_MARK = "PA_ROOTFS_OK"
        const val MOUNT_MARK = "PA_MOUNT_OK"
        const val MAX_REDIRECTS = 5
        const val USER_AGENT = "PocketAgent/rootfs"
    }
}
