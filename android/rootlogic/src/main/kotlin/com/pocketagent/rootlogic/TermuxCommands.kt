package com.pocketagent.rootlogic

/**
 * 借 Termux 的 Linux 干活 —— 构造 `com.termux.RUN_COMMAND` 请求。
 *
 * ## 为什么这条路对"所有用户"重要
 *
 * Android 10 起，`targetSdk ≥ 29` 的 app 执行不了自己目录里的文件（见 [ExecBackend] 的说明）。
 * 而 Termux 自己钉在 targetSdk 28，**在它那边 exec 是合法的**。
 * `RUN_COMMAND` 是 Termux 官方给外部 app 的接口：跨 app 由 intent 显式授权，
 * 不是绕沙箱，也没有 root 依赖。
 *
 * ## 为什么只传 `路径 + 参数数组`，不传一整串命令
 *
 * 与本地那条路保持同一套语义：`argv[0]` 的 basename 必须在白名单里。
 * 一旦允许传 `bash -lc "<任意脚本>"`，白名单就形同虚设（一行 `curl | sh` 就出去了）。
 *
 * ## 用户要做的两件事（必须写进界面）
 *
 * 1. 装 Termux；
 * 2. 在 Termux 里执行 `echo "allow-external-apps=true" >> ~/.termux/termux.properties`，
 *    并给本应用授予 `com.termux.permission.RUN_COMMAND`。
 *
 * 少一件都会静默失败（intent 发出去，Termux 不收），所以 [build] 之前要先 [preflight]。
 */
data class TermuxRequest(
    /** Termux 内某个可执行文件的绝对路径，例如 `/data/data/com.termux/files/usr/bin/python3`。 */
    val path: String,
    /** 参数（不含可执行文件本身）。 */
    val arguments: List<String>,
    /** 在 Termux 里的工作目录。默认是 Termux 的 home。 */
    val workdir: String,
    /** 后台跑：不占用户的 Termux 会话。 */
    val background: Boolean = true,
)

/** 预检结果：能不能发这条请求。 */
sealed interface TermuxPreflight {
    data object Ready : TermuxPreflight

    /** 没装 Termux（或装的是别的包名）。 */
    data object NotInstalled : TermuxPreflight

    /** 装了，但没开 `allow-external-apps`。 */
    data object ExternalAppsDisabled : TermuxPreflight

    /** 没给 `RUN_COMMAND` 权限。 */
    data object PermissionMissing : TermuxPreflight
}

object TermuxCommands {

    /** Termux 的包名与权限。 */
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val ACTION = "com.termux.RUN_COMMAND"

    /** Termux 的私有前缀。**不要**拼宿主机路径 —— 那是另一个 uid 的空间。 */
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"

    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

    /**
     * 构造一条请求。
     *
     * @throws LinuxRejected 首词不在白名单、或参数形状不合法（与本地那条路同一份规则）。
     */
    fun build(
        argv: List<String>,
        workdir: String = HOME,
        background: Boolean = true,
        allowed: Set<String> = LinuxCommands.ALLOWED_HEADS,
    ): TermuxRequest {
        if (argv.isEmpty()) throw LinuxRejected("命令", "空")
        val head = argv.first()
        if (head.contains('/')) throw LinuxRejected("命令", "$head（这里只传程序名，不传路径）")
        if (head !in allowed) throw LinuxRejected("命令", "$head（不在允许清单里）")

        // 参数侧复用本地那条路的校验：路径、长度、NUL 全都一致，
        // 免得"本地拒了、Termux 放过去"。
        LinuxCommands.argv(LinuxCall.Run(listOf(head) + argv.drop(1)))

        return TermuxRequest(
            path = "$PREFIX/bin/$head",
            arguments = argv.drop(1),
            workdir = workdir,
            background = background,
        )
    }

    /**
     * 预检：三个前提逐条判。
     *
     * ★ 分开判而不是"装没装"一句话：三种失败的用户动作完全不同
     *   （去装 Termux / 去改 termux.properties / 去给权限），
     *   合并成"Termux 不可用"等于让用户自己猜。
     */
    fun preflight(installed: Boolean, externalAppsAllowed: Boolean, permissionGranted: Boolean): TermuxPreflight =
        when {
            !installed -> TermuxPreflight.NotInstalled
            !externalAppsAllowed -> TermuxPreflight.ExternalAppsDisabled
            !permissionGranted -> TermuxPreflight.PermissionMissing
            else -> TermuxPreflight.Ready
        }

    /** 预检失败时给用户的那句话（可直接进界面/提示词）。 */
    fun advice(result: TermuxPreflight): String = when (result) {
        TermuxPreflight.Ready -> "Termux 可以用"
        TermuxPreflight.NotInstalled -> "还没装 Termux。装好之后我在它那边的 Linux 里干活，不占你的手机。"
        TermuxPreflight.ExternalAppsDisabled ->
            "Termux 装好了，但没允许外部应用调用：在 Termux 里执行 " +
                "`echo allow-external-apps=true >> ~/.termux/termux.properties`，重启 Termux 一次。"
        TermuxPreflight.PermissionMissing -> "还差一个权限：给本应用授予「运行 Termux 命令」（com.termux.permission.RUN_COMMAND）。"
    }
}
