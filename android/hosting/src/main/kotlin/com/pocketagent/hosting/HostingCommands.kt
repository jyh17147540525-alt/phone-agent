package com.pocketagent.hosting

/**
 * **root shell 命令拼装** —— 纯逻辑，可离线测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么命令要在这里拼好，而不是在执行器里
 * ═══════════════════════════════════════════════════════════════
 *
 * 这些命令是**以 root 身份**跑的。拼错一个参数，后果不是"功能不好用"，
 * 而是"动了不该动的东西"。所以：
 *
 *  - 拼装是**纯函数**，每条命令都有测试断言它长什么样
 *  - 执行器只负责"把已经拼好的 argv 交给 su"，不做任何字符串处理
 *  - **绝不接受用户输入直接拼进命令**（包名走白名单校验）
 *
 * ⚠️ 包名校验是硬要求：`am task resize` 的第一个参数是 taskId（数字），
 *    如果传进去一个 `5; rm -rf /` 之类的字符串，在 root shell 里就是灾难。
 *    这里用**正则白名单**（`[a-zA-Z0-9._]+`），不是黑名单过滤 ——
 *    黑名单永远漏一个。
 */
object HostingCommands {

    /** Android 包名的合法形式。**白名单**，不是"过滤危险字符"。 */
    private val PACKAGE_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /**
     * Activity 组件名的合法形式：`包名/类名`，类名可以以 `.` 开头。
     *
     * ★ 同样必须是**白名单**：它会拼进 `am start -n <component>` 这条 root 命令。
     */
    private val COMPONENT_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+$")

    fun isValidPackage(name: String): Boolean = PACKAGE_RE.matches(name)

    /**
     * 找某个包名的 taskId。
     *
     * ★ 用 `dumpsys activity activities` + `grep`，而不是 `am stack list`：
     *   后者的输出格式在 Android 12/13/14 之间改过，而 dumpsys 的
     *   `Task{... #12345 ...}` 形态近几个版本都稳定。
     *
     * @return 一条完整的 shell 命令（不含 su 前缀）
     */
    fun findTaskId(pkg: String): String {
        require(isValidPackage(pkg)) { "非法包名：$pkg" }
        // 取第一个匹配的 taskId（最近的）
        return "dumpsys activity activities | grep -oE 'Task\\{[0-9a-f]+ #([0-9]+) type=standard A=[0-9]+:${Regex.escape(pkg)}' " +
            "| grep -oE '#[0-9]+' | head -1 | tr -d '#'"
    }

    /**
     * 把某个包名的 task 缩到指定区域。
     *
     * ⚠️ 这条命令**必须先有 taskId**。所以实际执行是两步：
     *    ① `findTaskId` 拿到 id
     *    ② `am task resize <id> l t r b`
     *
     * ⇒ 本函数返回的是第二步的**模板**，调用方填入 id。
     */
    fun resizeTask(taskId: Int, bounds: HostBounds): String {
        require(taskId > 0) { "taskId 必须为正：$taskId" }
        // ⚠️ bounds 已在 HostBounds 的 init 里校验过（非负、右>左、下>上），
        //    这里不再重复 —— 但**仍然逐项转成 Int 再拼**，杜绝字符串注入。
        val l = bounds.left.toString()
        val t = bounds.top.toString()
        val r = bounds.right.toString()
        val b = bounds.bottom.toString()
        return "am task resize $taskId $l $t $r $b"
    }

    /**
     * 解析某个包的**启动 Activity 组件名**（形如 `com.foo/.MainActivity`）。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★★ 2026-10-27 真机：为什么必须走这一步
     * ═══════════════════════════════════════════════════════════════
     *
     * 原来这里用的是 `monkey -p <pkg> -c LAUNCHER 1`。它能启动，但
     * **忽略了 `--windowingMode`** —— 应用照样全屏起来，
     * 随后 `am task resize` 对全屏任务**什么都不做**（它只在多窗口/
     * 自由窗口模式下有效），于是整条托管链路静默失效。
     *
     * 真机实测（Redmi K60 / Android 15）：
     * ```
     * am start --windowingMode 5 -n com.tencent.mobileqq/.activity.SplashActivity
     *   → Task{... mode=freeform}          ← 成了
     * am task resize 24784 720 120 1420 1020
     *   → mBounds=Rect(720, 120 - 1420, 1020)  ← 也成了
     * ```
     *
     * ⚠️ 而 `am start --windowingMode 5 -p <pkg>` **解析不出来**
     *    （`unable to resolve Intent`）。`-p` 只是给 action/category
     *    加一个包过滤，不负责选 Activity。所以必须先问系统要组件名。
     */
    fun resolveLauncher(pkg: String): String {
        require(isValidPackage(pkg)) { "非法包名：$pkg" }
        return "cmd package resolve-activity --brief " +
            "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER $pkg"
    }

    /**
     * 从 `resolve-activity --brief` 的输出里取组件名。
     *
     * 输出形如两行：
     * ```
     * priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
     * com.tencent.mobileqq/.activity.SplashActivity
     * ```
     * 取**最后一行含 `/` 的**。取不到就返回 null（由调用方如实报错，
     * 不要退回全屏启动 —— 那会把"没托管成功"变成"挡在用户屏幕上"）。
     */
    fun parseLauncherComponent(out: String): String? {
        val line = out.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.contains('/') && !it.contains(' ') && it.contains('.') }
            ?: return null
        // 组件名同样走**白名单**：它会被拼进 root 命令
        return line.takeIf { COMPONENT_RE.matches(it) }
    }

    /**
     * 以**自由窗口**模式启动应用。
     *
     * ⚠️ `--windowingMode 5` 是 WINDOWING_MODE_FREEFORM 的常量值。
     *    这个数字写死是有意的：`am` 只认数字，不认枚举名。
     *    如果哪天系统改了值，症状会是"启动成全屏了"而不是报错 ——
     *    所以下面有一条测试钉住这个 5。
     *
     * @param component 来自 [parseLauncherComponent] 的组件名
     */
    fun startFreeform(component: String): String {
        require(COMPONENT_RE.matches(component)) { "非法组件名：$component" }
        return "$AM_START_FREEFORM -n $component"
    }

    /**
     * 兜底的启动方式：只知道包名、解析不出组件时用它。
     *
     * ⚠️ 它**不会**让应用变成自由窗口 —— 调用方必须知道这一点，
     *    并据此决定要不要继续走下去（见 [startFreeform] 的注释）。
     */
    fun startLauncherOnly(pkg: String): String {
        require(isValidPackage(pkg)) { "非法包名：$pkg" }
        return "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
    }

    /** `--windowingMode 5` = WINDOWING_MODE_FREEFORM。**这个 5 有测试钉住。** */
    const val WINDOWING_MODE_FREEFORM: Int = 5

    private const val AM_START_FREEFORM: String = "am start --windowingMode 5"

    /** 恢复全屏。freeform 的 `am task resize` 传全屏 bounds 即可。 */
    fun restoreFullscreen(taskId: Int, screenW: Int, screenH: Int): String =
        resizeTask(taskId, HostBounds(0, 0, screenW, screenH))

    /**
     * 判断一段 `dumpsys` 输出里有没有目标包名。
     *
     * ★ 这是给"执行结果校验"用的：`am task resize` 对不存在的 taskId
     *    **也会返回 0**（静默失败）。所以必须回读确认。
     */
    fun parseTaskId(dumpsysOutput: String): Int? {
        if (dumpsysOutput.isBlank()) return null
        val m = Regex("""#(\d+)\s+type=standard""").find(dumpsysOutput)
        return m?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
