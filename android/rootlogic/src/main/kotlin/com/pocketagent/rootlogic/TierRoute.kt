package com.pocketagent.rootlogic

/**
 * **两档能力的执行路由** —— `CapabilityMatrix` 说"能不能"，这里说"**走哪条路**"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 两档真正的差别不是"能不能点"，而是"要不要占屏"
 * ═══════════════════════════════════════════════════════════════
 *
 * 普通模式也能点、也能读屏 —— 但它只有**一条腿**：无障碍服务。
 * 而无障碍天生是"看当前窗口"的：要读别的应用、要在别的应用里点，
 * 就得先把它**推到前台**，屏幕被这次任务占住，用户什么都干不了。
 *
 * root 模式换的是另一条腿：`InputManager.injectInputEvent` 能往任意窗口
 * 注入，`SurfaceControl` 能抓任意层 —— 后台就能干活，屏幕留给用户。
 *
 * ⇒ 所以每条路都必须标出 [Occupancy]。它是这个产品里**用户最能感知到的
 *   差别**（"用起来这台手机还是不是我的"），比"多几个功能"重要得多。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 降级必须是显式的
 * ═══════════════════════════════════════════════════════════════
 *
 * root 用户也可能突然用不了 root：Magisk 更新后授权被重置、常驻进程
 * 被系统杀掉、SELinux 换了策略。这时**不能**直接判"不可用"——
 * 普通模式那条腿还在，任务应该继续跑，只是**代价变大**（要占屏）。
 *
 * ⇒ [Verdict.Ready] 带 `degraded` 与 `occupancy`：降级不是静默的，
 *   界面与日志都要能说出"这一趟是退到普通模式跑的，所以它占了你的屏幕"。
 *   悄悄降级比直接失败更坏 —— 用户会以为 root 生效了，下次就不看提示了。
 */
/** 一条具体的执行通道。 */
enum class ExecPath(
    /** 稳定标识（日志与审计用）。 */
    val id: String,
    /** 给用户看的一句话。 */
    val label: String,
) {
    /** 无障碍树（只能看当前窗口）。 */
    A11Y_TREE("a11y.tree", "无障碍读屏"),

    /** 无障碍手势派发（`dispatchGesture`，当前窗口）。 */
    A11Y_GESTURE("a11y.gesture", "无障碍模拟点按"),

    /** API 30+ 的 `takeScreenshot`（当前窗口，不需要录屏授权）。 */
    A11Y_SCREENSHOT("a11y.screenshot", "无障碍截屏"),

    /** 录屏授权取帧（全屏，但常驻一条通知）。 */
    MEDIA_PROJECTION("mp.capture", "录屏取帧"),

    /** 悬浮窗（覆盖在别人界面上，这就是"占屏"最轻的形态）。 */
    OVERLAY("overlay.window", "悬浮窗"),

    /** 显式意图 / deep link。 */
    INTENT("sys.intent", "系统意图"),

    /** SAF 授权的目录。 */
    SAF("file.saf", "授权目录"),

    /** root 直接读写任意路径。 */
    ROOT_FS("root.fs", "root 直读文件"),

    /**
     * root 注入输入事件。
     *
     * 当前实现走 `su -c input tap`（底层同样是 InputManager.injectInputEvent），
     * 任意窗口都收得到，包括游戏与自绘 Canvas —— 这一点与无障碍的
     * `dispatchGesture` 有实质差别：后者只对当前活动窗口生效。
     */
    ROOT_INJECT("root.inject", "root 注入事件"),

    /**
     * root 抓帧。
     *
     * 当前实现走 `su -c screencap -p`（SurfaceFlinger），**不是** `SurfaceControl` 的
     * 逐层抓取 —— 后者要常驻守护进程。两者能力上都是"整屏、任意 App、不占屏"，
     * 差别在延迟与能不能只抓单层；标签按现状写，等守护进程上线再改。
     */
    ROOT_CAPTURE("root.capture", "root 直接截屏"),

    /** root 结构化查询（`IPackageManager` 等）。 */
    ROOT_QUERY("root.query", "root 结构化查询"),

    /** root 白名单命令（argv，不经 shell）。 */
    ROOT_EXEC("root.exec", "root 白名单命令"),

    /** root 受范围约束的网络探测。 */
    ROOT_PROBE("root.probe", "网络探测"),
    ;

    val isRoot: Boolean get() = id.startsWith("root.")
}

/** 这次动作要占用户多少屏幕。 */
enum class Occupancy(
    /** 界面上的词。 */
    val label: String,
    /** 排序用：数值越大越打扰。 */
    val weight: Int,
) {
    /** 后台完成，用户可以正常用手机。 */
    NONE("不占屏", 0),

    /** 屏幕上有个小提示条，可以同时做别的事。 */
    BADGE("小提示条", 1),

    /** 必须把目标应用推到前台，这段时间手机被这次任务占着。 */
    FOREGROUND("占住屏幕", 2),
}

/** 一条路需要的系统授权。 */
enum class Permission(
    val label: String,
    /** 去哪开 —— **必须是可执行的一步**，不能只写"请授权"。 */
    val guide: String,
) {
    ACCESSIBILITY(
        "无障碍服务",
        "设置 → 无障碍 → 已下载的服务（或「已安装的应用」）→ PocketAgent → 打开",
    ),
    OVERLAY(
        "悬浮窗",
        "设置 → 应用 → PocketAgent → 显示在其他应用上层 → 允许",
    ),
    MEDIA_PROJECTION(
        "录屏",
        "任务开始时系统会弹一次「开始录制或投放」，选「整个屏幕」并确认",
    ),
    ROOT_SU(
        "root 授权",
        "打开 Magisk（或你的 root 管理器）→ 超级用户 → 找到 PocketAgent → 允许",
    ),
    WRITE_SECURE_SETTINGS(
        "修改系统设置",
        "电脑连上手机后执行一次 `adb shell pm grant com.pocketagent " +
            "android.permission.WRITE_SECURE_SETTINGS`（设置页会显示是否已授权）",
    ),
    ROOT_DAEMON(
        "常驻通道",
        "root 已授权，但常驻进程没起来 —— 在设置里点一次「重启 root 通道」，" +
            "若反复失败，多半是 SELinux 拦了 socket，见诊断页",
    ),
}

/** 这台手机**此刻**的真实状态（由各个运行时探测填进来）。 */
data class DeviceState(
    val accessibilityOn: Boolean = false,
    val overlayAllowed: Boolean = false,
    val mediaProjectionGranted: Boolean = false,
    val secureSettingsGranted: Boolean = false,
    val suAvailable: Boolean = false,
    val rootDaemonAlive: Boolean = false,
    /** 探测结论；null 表示还没探过。 */
    val probe: RootProbeResult? = null,
) {
    /** 只有 su 能跑、常驻通道也在，才算满血。 */
    val rootReady: Boolean get() = suAvailable && rootDaemonAlive
}

/** 一条路。 */
data class Route(
    val path: ExecPath,
    val occupancy: Occupancy,
    val needs: Permission? = null,
    /**
     * 这条路在**当前窗口之外**能不能用。
     *
     * ★ 无障碍那三条全是 false：它只能看/点当前窗口，
     *   所以"在其他应用里干活"必然要求把那个应用推到前台。
     */
    val worksOffForeground: Boolean,
)

/**
 * 一条能力在两档下分别走哪条路。
 *
 * `plain = null` 表示普通模式**没有实现**（不是"做不到"，是"我们还没写"）——
 * 这两种要分开，否则用户会以为该去 root，而其实只差一次发版。
 */
data class CapabilityRoute(
    val id: String,
    val plain: Route?,
    val root: Route?,
) {
    fun routeIn(mode: PrivilegeMode): Route? = when (mode) {
        PrivilegeMode.NON_ROOT -> plain
        PrivilegeMode.ROOT -> root
    }
}

/** 判定结论。 */
sealed interface Verdict {

    /**
     * 可以跑。
     *
     * @param degraded 这一趟**退到了更低一档**（root 用户回落普通模式）
     * @param note 为什么降级 —— 界面要显示，日志要留
     */
    data class Ready(
        val path: ExecPath,
        val occupancy: Occupancy,
        val degraded: Boolean = false,
        val note: String = "",
    ) : Verdict

    /** 缺一个系统授权；[permission] 里带可执行的引导。 */
    data class NeedPermission(val permission: Permission) : Verdict

    /** 这一档真的做不到；[why] 要说清是系统限制。 */
    data class NeedRoot(val why: String) : Verdict

    /** 这一档我们还没实现（不是做不到）。 */
    data object NotImplemented : Verdict
}

/**
 * 能力 id → 两档的实现路径。
 *
 * ⚠️ key 必须与 [CapabilityMatrix] 的 id 一一对应：
 *    矩阵负责"说给用户听"，这张表负责"怎么跑"。两处对不上时
 *    [RouteTable.missingMatrixEntries] 会指出来 —— 别让它悄悄分叉。
 */
object RouteTable {

    val routes: List<CapabilityRoute> = listOf(

        // 读屏：普通模式看当前窗口；root 抓任意层
        //
        // ★ 2026-11-04：root 路径的前置条件从 ROOT_DAEMON 改成 **ROOT_SU**。
        //   原因很实际：`su -c screencap -p <file>` 今天就跑得通（见 `RootActions`），
        //   而常驻通道还没有守护进程。写成 ROOT_DAEMON 等于把一条**现在可用**的路
        //   标成"去启动一个还不存在的功能" —— 指向未来的开关，就是本模块最忌讳的
        //   "错误信息指向无关的地方"。等知识通道上线，换的是 `RootShellPort` 的实现，
        //   路由表不用动。
        CapabilityRoute(
            id = "screen.read",
            plain = Route(ExecPath.A11Y_TREE, Occupancy.FOREGROUND, Permission.ACCESSIBILITY, worksOffForeground = false),
            root = Route(ExecPath.ROOT_CAPTURE, Occupancy.NONE, Permission.ROOT_SU, worksOffForeground = true),
        ),

        // 点按：无障碍手势 vs `input tap`
        CapabilityRoute(
            id = "screen.tap",
            plain = Route(ExecPath.A11Y_GESTURE, Occupancy.FOREGROUND, Permission.ACCESSIBILITY, worksOffForeground = false),
            root = Route(ExecPath.ROOT_INJECT, Occupancy.NONE, Permission.ROOT_SU, worksOffForeground = true),
        ),

        // 授权目录：两档同一条路（root 也没必要绕过 SAF 去做用户已经授权的事）
        CapabilityRoute(
            id = "file.saf",
            plain = Route(ExecPath.SAF, Occupancy.NONE, null, worksOffForeground = true),
            root = Route(ExecPath.SAF, Occupancy.NONE, null, worksOffForeground = true),
        ),

        // 任意路径：只有 root 有路；而且这条**不占屏**
        CapabilityRoute(
            id = "file.any",
            plain = null,
            root = Route(ExecPath.ROOT_FS, Occupancy.NONE, Permission.ROOT_SU, worksOffForeground = true),
        ),

        CapabilityRoute(
            id = "settings.write",
            // 普通模式靠一次 adb 授权（多数 ROM 上改不了受保护项）
            plain = Route(ExecPath.INTENT, Occupancy.NONE, Permission.WRITE_SECURE_SETTINGS, worksOffForeground = true),
            root = Route(ExecPath.ROOT_EXEC, Occupancy.NONE, Permission.ROOT_SU, worksOffForeground = true),
        ),

        CapabilityRoute(
            id = "prop.set",
            plain = null,
            root = Route(ExecPath.ROOT_EXEC, Occupancy.NONE, Permission.ROOT_SU, worksOffForeground = true),
        ),

        CapabilityRoute(
            id = "pm.disable",
            plain = null,
            // ★ 前置条件是 **su 授权**，不是常驻通道：这一条今天就是 `su -c pm disable-user`。
            //   写成 ROOT_DAEMON 会让装了 root 的用户看到一个"去启动一个还不存在的功能"的
            //   指引 —— 指向未来的开关，等于把这个工具永久标成不可用。
            root = Route(ExecPath.ROOT_EXEC, Occupancy.NONE, Permission.ROOT_SU, worksOffForeground = true),
        ),

        CapabilityRoute(
            id = "shell.root",
            plain = null,
            root = Route(ExecPath.ROOT_EXEC, Occupancy.NONE, Permission.ROOT_SU, worksOffForeground = true),
        ),
    )

    fun byId(id: String): CapabilityRoute? = routes.firstOrNull { it.id == id }

    /** 矩阵里有、路由表里没有的 id —— 说明两处已经分叉。 */
    fun missingMatrixEntries(): List<String> =
        CapabilityMatrix.all.map { it.id }.filter { byId(it) == null }

    /** 路由表里有、矩阵里没有的 id —— 说明界面会漏掉一条能力。 */
    fun orphanRoutes(): List<String> =
        routes.map { it.id }.filter { CapabilityMatrix.byId(it) == null }
}

/**
 * 按**当前设备状态**决定每条能力这次怎么跑。
 *
 * 纯函数，无状态：状态由调用方探测后传进来（和 `PermissionPolicy` 同一个思路）。
 */
object TierRouter {

    /**
     * 判一条能力。
     *
     * @param mode 用户选的模式（[PrivilegeMode.NON_ROOT] 时**永不**碰 root 路径）
     * @param allowFallback root 路径不可用时，是否回落到普通路径
     */
    fun resolve(
        capabilityId: String,
        mode: PrivilegeMode,
        state: DeviceState,
        allowFallback: Boolean = true,
    ): Verdict {
        val route = RouteTable.byId(capabilityId)
            ?: return Verdict.NotImplemented

        // 用户明确选了普通模式：连尝试都不该尝试 root 路径
        if (mode == PrivilegeMode.NON_ROOT) return judge(route.plain, state) { route.root != null }

        when (val root = judge(route.root, state) { route.plain != null }) {
            is Verdict.Ready -> return root
            else -> {
                if (!allowFallback) return root
                return when (val plain = judge(route.plain, state) { false }) {
                    is Verdict.Ready -> {
                        val why = when (root) {
                            is Verdict.NeedPermission -> root.permission.label + " 未就绪"
                            is Verdict.NeedRoot -> "root 不可用"
                            else -> "root 路径不可用"
                        }
                        plain.copy(
                            degraded = true,
                            note = "退到普通模式（$why）：这一趟要占住屏幕",
                        )
                    }
                    // 两条路都不通时，报 root 那条 —— 它才是用户真正缺的东西
                    else -> root
                }
            }
        }
    }

    /** 全部能力在**当前状态**下的结论，供工具挂载与界面共用。 */
    fun resolveAll(mode: PrivilegeMode, state: DeviceState, allowFallback: Boolean = true): Map<String, Verdict> =
        CapabilityMatrix.all.associate { it.id to resolve(it.id, mode, state, allowFallback) }

    /**
     * 这条路上现在到底缺什么。
     *
     * @param anyOther 另一档是否有实现 —— 用来区分 [Verdict.NotImplemented]
     *                 与 [Verdict.NeedRoot]：前者是我们还没写，后者是系统不给。
     */
    private inline fun judge(route: Route?, state: DeviceState, anyOther: () -> Boolean): Verdict {
        if (route == null) {
            return if (anyOther()) Verdict.NeedRoot(NEED_ROOT_TEXT) else Verdict.NotImplemented
        }
        val missing = route.needs?.let { unmet(it, state) } ?: false
        if (missing) return Verdict.NeedPermission(route.needs!!)
        return Verdict.Ready(route.path, route.occupancy)
    }

    private fun unmet(p: Permission, state: DeviceState): Boolean = when (p) {
        Permission.ACCESSIBILITY -> !state.accessibilityOn
        Permission.OVERLAY -> !state.overlayAllowed
        Permission.MEDIA_PROJECTION -> !state.mediaProjectionGranted
        Permission.WRITE_SECURE_SETTINGS -> !state.secureSettingsGranted
        Permission.ROOT_SU -> !state.suAvailable
        Permission.ROOT_DAEMON -> !state.rootDaemonAlive
    }

    /**
     * ★ 这句话必须点明"是系统不给"，否则用户会以为我们偷懒。
     *   与 `CapabilityMatrix` 里的 `why` 同一条口吻。
     */
    const val NEED_ROOT_TEXT: String =
        "这条能力只能以 root 身份做 —— 普通应用连调用它需要的系统接口都拿不到。" +
            "这不是本应用的限制，是 Android 的权限模型。"

    /** 这一趟要不要占用户的屏幕（界面据此决定要不要提示"接下来请别动手机"）。 */
    fun occupiesScreen(verdict: Verdict): Boolean = when (verdict) {
        is Verdict.Ready -> verdict.occupancy != Occupancy.NONE
        else -> false
    }
}
