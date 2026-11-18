package com.pocketagent.rootlogic

/**
 * LINUX 动词在通道里的**线上形状**。
 *
 * ## 为什么单独一层
 *
 * 同一个"跑 Linux 命令"要能从三条路发出：`su -c` 的字符串、常驻通道的数组、以及单元测试里
 * 直接拼的数组。三处各写一遍解析，迟早在某一条上少校验一项（历史上已经踩过 `CAPTURE` 那种）。
 * 所以线上形状只定义一次，`DaemonHandlers` 与 `RootActions` 都调用这里。
 *
 * ## 形状
 *
 * ```
 * run <kind> <rootfs> <workDir> <net: on|off> <程序> [参数…]
 * ```
 *
 * `kind` = `chroot` | `proot`。判断"能不能用"由上层做（root 才有 chroot），这一层只管形状与校验。
 */
object LinuxWire {

    const val HEAD = "run"

    /** 解析出来的请求。 */
    data class Request(
        val kind: LinuxKind,
        val rootfs: String,
        val workDir: String,
        val networkOff: Boolean,
        val inner: List<String>,
    )

    fun encode(request: Request): List<String> {
        val kind = when (request.kind) {
            LinuxKind.ROOT_CHROOT -> KIND_CHROOT
            LinuxKind.EXTERNAL_PROOT, LinuxKind.BUNDLED_PROOT -> KIND_PROOT
        }
        return listOf(
            HEAD,
            kind,
            safePath("rootfs", request.rootfs),
            safePath("workDir", request.workDir),
            if (request.networkOff) NET_OFF else NET_ON,
        ) + request.inner
    }

    /**
     * 解析并校验。
     *
     * ⚠️ 服务端也走这个函数 —— 客户端能发什么，不该成为"能不能执行"的唯一一道闸。
     */
    fun decode(argv: List<String>): Request {
        if (argv.size < 6) throw LinuxRejected("linux 参数（run 类型 rootfs workDir 开关 程序…）", argv.joinToString(" "))
        if (argv[0] != HEAD) throw LinuxRejected("linux 子命令", argv[0])
        val kind = when (argv[1]) {
            KIND_CHROOT -> LinuxKind.ROOT_CHROOT
            KIND_PROOT -> LinuxKind.BUNDLED_PROOT
            else -> throw LinuxRejected("linux 类型（chroot/proot）", argv[1])
        }
        val rootfs = safePath("rootfs", argv[2])
        val workDir = safePath("workDir", argv[3])
        val networkOff = when (argv[4]) {
            NET_OFF -> true
            NET_ON -> false
            else -> throw LinuxRejected("网络开关（on/off）", argv[4])
        }
        return Request(kind, rootfs, workDir, networkOff, argv.drop(5))
    }

    /**
     * 路径校验：绝对、无 `..`、无空白、无引号。
     *
     * 空白与引号必须在这里挡掉 —— 因为 `su -c` 那条路会把它拼成一条 shell 字符串。
     */
    internal fun safePath(what: String, path: String): String {
        if (path.isEmpty() || path.length > 512) throw LinuxRejected(what, path)
        if (!path.startsWith("/")) throw LinuxRejected("$what（必须绝对路径）", path)
        if (path.split('/').any { it == ".." }) throw LinuxRejected("$what（含 ..）", path)
        if (path.any { it.isWhitespace() || it == '\'' || it == '"' || it == ';' || it == '|' || it == '&' || it == '$' }) {
            throw LinuxRejected("$what（含空白或 shell 元字符）", path)
        }
        return path
    }

    private const val KIND_CHROOT = "chroot"
    private const val KIND_PROOT = "proot"
    private const val NET_ON = "on"
    private const val NET_OFF = "off"
}
