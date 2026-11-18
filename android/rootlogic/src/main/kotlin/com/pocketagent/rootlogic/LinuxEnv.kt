package com.pocketagent.rootlogic

/**
 * Linux 工作区 —— **能不能用、用哪一套、根在哪、能写哪**。
 *
 * ## 为什么要有这一层，而不是直接去跑 `proot`
 *
 * "能不能跑"在三种机器上完全不同：root 机可以 `chroot`，装了 Termux 的机器已经有容器，
 * 普通用户一台干净的手机什么都没有。这三种情况下**命令长得不一样、失败原因也不一样**：
 *
 * | 机器 | 直接跑 proot 的结果 | 用户看到的话 |
 * |---|---|---|
 * | 干净手机 | `proot: not found` | 「命令失败」← 他会以为我们坏了 |
 * | 装了 Termux | 能用，但 rootfs 路径要探测 | 其实可以直接用 |
 * | root 机 | 能用且更快 | 但还在用 proot，白白慢 3 倍 |
 *
 * ⇒ 先探测、再决定，**探测结果要能一句话说给用户听**（缺什么、怎么补）。
 *
 * ## 纯函数
 *
 * [LinuxEnvProbe.decide] 只吃"探测到的事实"，不碰文件系统 —— 这样三档状态机可以离线测，
 * 而真正的文件探测（`File.exists()`）留在 `:app` 里做。判错状态的代价是
 * "明明能用却说不能用"或反过来，两者都只能靠真机发现，所以逻辑必须在这里被钉住。
 */
enum class LinuxKind(val label: String) {
    /** 机器上已有的容器（Termux / dsh 那套）。零额外体积。 */
    EXTERNAL_PROOT("已有容器"),

    /** app 自带 proot + 按需下载的 rootfs。普通用户的默认路线。 */
    BUNDLED_PROOT("自带容器"),

    /** root 机上的 chroot。最快，且能用 network namespace 真断网。 */
    ROOT_CHROOT("root 容器"),
}

/** 环境状态。四态分开：`NEEDS_DOWNLOAD` 与 `BROKEN` 的下一步动作完全不同。 */
enum class LinuxState {
    /**
     * 系统不允许执行（不是"没装"）。
     *
     * ★ 2026-11-18 补：targetSdk ≥ 29 的 app 执行不了自己可写目录里的文件（W^X）。
     *   这种情况落在 [ABSENT] 会让用户去装 Termux —— 白折腾一次，因为真正原因是本应用
     *   被系统策略挡住。两者的下一步完全不同，所以必须分开。
     */
    BLOCKED,

    /** 能用。 */
    READY,

    /** 自带 proot 在，但 rootfs 还没下载。 */
    NEEDS_DOWNLOAD,

    /** 这台机器上没有可用的 Linux。 */
    ABSENT,

    /** 探测到了，但用不了（proot 被 ROM 拦、rootfs 损坏、没有执行权限）。 */
    BROKEN,
}

/**
 * 一台机器上的 Linux 工作区。
 *
 * @param workDir **唯一可写目录**。容器内固定挂成 `/work`。
 * @param reason 不可用时给用户的一句话（不是堆栈）。
 */
data class LinuxEnv(
    val state: LinuxState,
    val kind: LinuxKind?,
    val rootfsPath: String?,
    val workDir: String,
    val reason: String = "",
) {
    val usable: Boolean get() = state == LinuxState.READY

    /** 给界面与提示词看的一句结论。 */
    fun explain(): String = when (state) {
        LinuxState.READY -> "Linux 工作区可用（${kind?.label ?: "未知"}），可写目录只有 $workDir"
        LinuxState.NEEDS_DOWNLOAD -> "还差一个 Linux 根文件系统（约 200 MB）。$reason"
        LinuxState.ABSENT -> "这台机器上还没有 Linux 工作区。$reason"
        LinuxState.BROKEN -> "Linux 工作区在，但用不了：$reason"
        LinuxState.BLOCKED -> "系统不让应用执行自己目录里的文件，所以真 Linux 这条路走不通。$reason"
    }

    companion object {
        /** 容器内的挂载点。对外只说这个名字，不暴露真实路径。 */
        const val INNER_WORK_DIR = "/work"
    }
}

/**
 * 三档探测的**纯逻辑**部分。
 *
 * ⚠️ 顺序是有讲究的：root 的 `chroot` 优先（快、能真断网），其次已有容器（零体积），
 * 最后才是自带容器。反过来的后果是：root 用户也在跑 proot，白白慢 3 倍，
 * 而用户完全不知道为什么"我明明有 root 还这么慢"。
 */
object LinuxEnvProbe {

    /**
     * 探测到的事实。全部由调用方（`:app`）去文件系统上查，这里只做判定。
     *
     * @param rootChroot 有 root 且 rootfs 目录可 chroot
     * @param externalProot 机器上已有 proot 容器（Termux / dsh）且能访问
     * @param bundledProot app 自带的 proot 二进制可执行（`nativeLibraryDir` 里那个）
     * @param rootfsReady 自带 rootfs 已下载并解压完成
     * @param workDirExists 工作区目录已创建且可写
     */
    data class Facts(
        val rootChroot: Boolean = false,
        val externalProot: Boolean = false,
        val bundledProot: Boolean = false,
        val rootfsReady: Boolean = false,
        val workDirExists: Boolean = false,
        /** 探测到但不可用时的原因（例如"proot 被 ROM 拦了"）。 */
        val brokenReason: String = "",
        /**
         * 系统层面禁止执行（W^X / SELinux）。
         *
         * 由调用方在"能建文件、能读 rootfs，但 exec 被拒"时置 true。
         */
        val execBlocked: Boolean = false,
    )

    fun decide(
        facts: Facts,
        workDir: String,
        externalRootfs: String? = null,
        bundledRootfs: String? = null,
    ): LinuxEnv = when {
        // 最先判：系统不让执行的话，下面所有"有 rootfs / 有容器"的结论都没意义
        facts.execBlocked -> LinuxEnv(
            state = LinuxState.BLOCKED,
            kind = null,
            rootfsPath = null,
            workDir = workDir,
            reason = "这台机器不允许应用执行自己目录里的文件（Android 10 起的限制）。" +
                "换 root 或 Termux 才能拿到真 Linux；内置引擎不受这条限制。",
        )

        !facts.workDirExists -> LinuxEnv(
            state = LinuxState.BROKEN,
            kind = null,
            rootfsPath = null,
            workDir = workDir,
            reason = "工作区目录建不起来（$workDir）。可能是存储已满或没有写权限。",
        )

        facts.rootChroot -> LinuxEnv(LinuxState.READY, LinuxKind.ROOT_CHROOT, null, workDir)

        facts.externalProot && externalRootfs != null -> LinuxEnv(
            state = LinuxState.READY,
            kind = LinuxKind.EXTERNAL_PROOT,
            rootfsPath = externalRootfs,
            workDir = workDir,
        )

        facts.bundledProot && facts.rootfsReady -> LinuxEnv(
            state = LinuxState.READY,
            kind = LinuxKind.BUNDLED_PROOT,
            rootfsPath = bundledRootfs,
            workDir = workDir,
        )

        facts.bundledProot -> LinuxEnv(
            state = LinuxState.NEEDS_DOWNLOAD,
            kind = LinuxKind.BUNDLED_PROOT,
            rootfsPath = bundledRootfs,
            workDir = workDir,
            reason = "下载之后就能跑 python、jq、ffmpeg 这类命令。",
        )

        facts.brokenReason.isNotBlank() -> LinuxEnv(
            state = LinuxState.BROKEN,
            kind = null,
            rootfsPath = null,
            workDir = workDir,
            reason = facts.brokenReason,
        )

        else -> LinuxEnv(
            state = LinuxState.ABSENT,
            kind = null,
            rootfsPath = null,
            workDir = workDir,
            reason = "需要 root、装一个 Termux，或者下载一份自带的根文件系统。",
        )
    }
}
