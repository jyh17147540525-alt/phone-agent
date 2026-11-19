package com.pocketagent.rootlogic

/**
 * root 容器的根文件系统（rootfs）—— **装它、查它、把它挂上**。
 *
 * ## 为什么这一步必须单独存在
 *
 * `chroot <rootfs> /bin/sh` 之前的所有能力都白搭：手机有 root、档位也切到了
 * Root 模式，只要 `<rootfs>/bin` 不存在，`linux_exec` 就只能降级到内置引擎
 * （只吃 `python3`）。真机上的原话是「内置引擎只认 python3」，用户听到的是
 * "它明明有 root 却说跑不了"。
 *
 * ## 三个决定
 *
 * 1. **下载在应用进程里做**（有网络权限），解包交给 `su`。
 *    让 `su` 去 `curl` 等于把联网能力塞进 root 通道 —— 那是我们自己禁掉的东西。
 * 2. **rootfs 放在应用自己的 files 下**。放 `/data/local` 的话，su 解出来的
 *    文件属主是 root，应用读 `/work` 时会 EACCES；放在自己目录里，
 *    解包后 chown 都不用做，应用照样能读回文件。
 * 3. **`/work` 用 bind mount 挂进容器**，而不是在容器里另建一个目录。
 *    否则「内置引擎写进去的文件」和「root 容器看到的文件」是两份，
 *    用户换个后端就发现文件不见了 —— 这种"文件会自己消失"的体验比没有更糟。
 *
 * 本文件是**纯逻辑**：只有路径与判据，不发一条命令。真正执行在
 * [RootCommands.payload] 的 `ProvisionRootfs` / `MountWork` 两支里。
 */
data class RootfsLayout(
    /** 解包目标，例如 `<files>/linux/rootfs`。 */
    val rootfsDir: String,
    /** 宿主侧的共享工作区，例如 `<files>/linux/work`。 */
    val workDir: String,
) {
    /** 容器内的 `/work` —— 宿主的 [workDir] 会被 bind mount 到这里。 */
    val innerWorkPath: String get() = "$rootfsDir/work"

    /**
     * 解包完成标记。**不靠"目录存在"判**：解到一半被杀同样会留下目录。
     *
     * ★ 标记放在 rootfs **外面**（`<rootfs>.ready`）：解包是 `su` 干的，解出来的
     *   目录属主是 root，应用（uid 999/10499）连在里面建个空文件都会 EACCES ——
     *   真机上就是这么翻的（"安装中断：… .pocketagent-rootfs: open failed: EACCES"）。
     *   标记只是个凭据，没有任何理由必须待在容器里。
     */
    val markerPath: String get() = "$rootfsDir.ready"

    /** chroot 进去至少要能执行它。 */
    val shPath: String get() = "$rootfsDir/bin/sh"

    /** 下载落点（应用私有目录，解包前不需要 root）。 */
    fun tarballOf(filesDir: String): String = "$filesDir/linux/rootfs.tar.gz"

    /** 写进标记文件的内容：给"这个 rootfs 是什么版本"留个凭据。 */
    fun markerText(source: String, bytes: Long): String = "$source\n$bytes\n"

}

/**
 * rootfs 的安装状态。**界面直接读它**，所以每一种都要有一句人话。
 */
sealed interface RootfsState {

    /** 还没装（或装到一半被杀）。 */
    data object Absent : RootfsState

    /** 正在下载。[total] 为 -1 表示服务器没给长度。 */
    data class Downloading(val received: Long, val total: Long) : RootfsState {
        /** 0f..1f；不知道总长时返回 null（界面该显示不确定进度条）。 */
        val fraction: Float? get() = if (total > 0L) (received.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** 下载完，正在解包（`su` 里跑 tar，可能几秒到十几秒）。 */
    data object Unpacking : RootfsState

    /** 装好了。 */
    data class Ready(val bytes: Long) : RootfsState

    /** 失败。**必须带原因** —— "安装失败"这四个字没有信息量。 */
    data class Failed(val reason: String) : RootfsState
}

/**
 * "这个 rootfs 里到底有没有能执行的 shell" —— **不能只看 `bin/sh` 是不是普通文件**。
 *
 * ★ 真机上翻过这个车：Alpine 的 `bin/sh` 是指向 `/bin/busybox` 的**符号链接**，
 *   Java 的 `File.isFile` 会顺着链接去看宿主的 `/bin/busybox`（不存在）⇒ 明明解包
 *   完整，界面却说"解包完成但里面没有 bin/sh"。判定必须三种情况都算数。
 */
object RootfsCheck {

    fun shellLikely(shIsRegularFile: Boolean, shIsSymlink: Boolean, busyboxExists: Boolean): Boolean =
        shIsRegularFile || shIsSymlink || busyboxExists

    /** 从标记文件正文里读回体积（第二行）；读不出就当 0。 */
    fun bytesOfMarker(text: String): Long =
        text.lineSequence().drop(1).firstOrNull()?.trim()?.toLongOrNull() ?: 0L

    /** 装不上时给用户的一句话 —— 带上"要么重装、要么换 tar"两条路。 */
    fun missingShellNote(): String =
        "解包完成但找不到 bin/sh —— 要么 tar 不完整（重装一次），要么这个包不是最小根文件系统。"
}

object RootfsProvision {

    /**
     * Alpine 的最小根文件系统（arm64，约 3.6 MB）。
     *
     * 选它的理由：体积小到装得进去、`/bin/sh` 与 busybox 自带、许可证是 MIT。
     * **不打包进 APK**：那会让安装包多几 MB，而且换版本要发版。
     */
    const val ALPINE_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz"

    /** 小于这个体积的下载**一定是错误页**（HTML 400/502 也就几 KB）。 */
    const val MIN_TAR_BYTES = 200_000L

    /** 超过这个体积说明下错了东西（完整发行版镜像）。 */
    const val MAX_TAR_BYTES = 400L * 1024 * 1024

    /**
     * 下载回来的字节数是否像个 tar.gz。
     *
     * @return null 表示可以往下走；否则是给用户看的原因
     */
    fun checkDownload(bytes: Long): String? = when {
        bytes <= 0L -> "下载下来是空文件，多半是断网或被拦了。"
        bytes < MIN_TAR_BYTES ->
            "下载到的只有 ${bytes} 字节，太小了 —— 多半是服务器回了错误页（403/404/502）。"
        bytes > MAX_TAR_BYTES ->
            "下载到 ${bytes / 1024 / 1024} MB，比一个最小根文件系统大太多，可能下错了链接。"
        else -> null
    }

    /** 给界面的一句话。 */
    fun explain(state: RootfsState): String = when (state) {
        RootfsState.Absent ->
            "还没有根文件系统。装一个（约 3.6 MB，Alpine）之后，root 容器就能跑任意程序，" +
                "而不是只剩内置引擎的 python3。"
        is RootfsState.Downloading -> {
            val f = state.fraction
            if (f == null) {
                "正在下载…已收到 ${mb(state.received)}"
            } else {
                "正在下载…${(f * 100).toInt()}%（${mb(state.received)} / ${mb(state.total)}）"
            }
        }
        RootfsState.Unpacking -> "正在解包…（这一步要几秒，别退出应用）"
        is RootfsState.Ready -> "root 容器已就绪（根文件系统 ${mb(state.bytes)}）。"
        is RootfsState.Failed -> state.reason
    }

    /**
     * 装好之后给用户看的能力边界。
     *
     * ★ 不许说"能装软件包"：容器里没有网络（`linux_exec` 的白名单里没有
     *   `curl/wget/apk`），装了也拉不下来。这句话必须在这里就说清。
     */
    fun capabilityLine(): String =
        "能跑任意已装的程序（sh / busybox / python3 / jq…），`/work` 与别的后端共用；" +
            "容器内不联网，所以装不了新包。"

    private fun mb(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        else -> "${bytes / 1024} KB"
    }
}
