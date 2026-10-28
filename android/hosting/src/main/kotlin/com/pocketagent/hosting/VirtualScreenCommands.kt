package com.pocketagent.hosting

/**
 * **虚拟屏命令拼装** —— 纯逻辑，可离线测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-10-27 真机：虚拟屏这条路走通了
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的要求是硬的：**角色的一切执行操作必须在虚拟屏里，不能占用、
 * 也不能打扰用户的主屏幕。** 之前的 freeform 小窗做不到（窗口看得见）。
 *
 * 真机（Redmi K60 / Android 15 / root）实测的四步，全部成立：
 *
 * ```
 * ① App 建屏（不需要 root）：
 *    createVirtualDisplay(PUBLIC | OWN_CONTENT_ONLY, ImageReader.surface)
 *    → Display{ id=5, 720x1280, owner=com.pocketagent.debug }
 * ② root 把目标 App 拉进这块屏：
 *    am start --display 5 -n com.tencent.mobileqq/.activity.SplashActivity
 *    → dumpsys: "Display: mDisplayId=5" 下出现了 QQ 的 Task；
 *      主屏 display 0 的焦点**没变**
 * ③ 读画面：ImageReader 取到 720x1280 的帧，非零像素采样 99 → 真的有内容
 * ④ 点它：input -d 5 tap 360 640 → 无报错
 * ```
 *
 * ⚠️ 第 ② 步是**唯一**需要 root 的一步。之前 `su -c am start --display`
 *    失败过一次，原因不是权限，而是**当时那块屏还不存在** ——
 *    系统把"往不存在的 display 启动"报成了 SecurityException，
 *    排查方向被彻底带偏。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一条必须实现的顺序：**先清任务，再释放屏**
 * ═══════════════════════════════════════════════════════════════
 *
 * 虚拟屏被 release 之后，上面的 Task 会被系统**挪回默认屏幕**。
 * 真机目击：探针进程一结束，QQ 就整个铺到用户主屏上全屏显示 ——
 * 那正是用户最不能接受的事。
 *
 * ⇒ [stop] 的语义是"先 `am force-stop`，再 release 屏"，
 *    而且**这两步的顺序不能换**。见 [releaseSequence]。
 */
object VirtualScreenCommands {

    /** 包名白名单（同 [HostingCommands]）。 */
    private val PACKAGE_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")

    /** 组件名白名单：`包名/类名`。 */
    private val COMPONENT_RE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+$")

    fun isValidPackage(name: String): Boolean = PACKAGE_RE.matches(name)

    fun isValidComponent(name: String): Boolean = COMPONENT_RE.matches(name)

    /**
     * 把目标 App 拉到指定虚拟屏上启动。
     *
     * ⚠️ 必须有 `-n <component>`：`--display` + `-p <pkg>` 解析不出 Activity，
     *    `am` 会回 "unable to resolve Intent"。
     */
    fun startOnDisplay(displayId: Int, component: String): String {
        require(displayId > 0) { "displayId 必须为正：$displayId（0 是用户主屏，永远不要往那上面拉）" }
        require(isValidComponent(component)) { "非法组件名：$component" }
        return "am start --display $displayId --windowingMode $WINDOWING_MODE_FULLSCREEN -n $component"
    }

    /**
     * 关掉老方案留下的 freeform 全局开关。
     *
     * ★ 必须做：`enable_freeform_support=1` 会让**所有**新任务倾向 freeform，
     *   包括用户自己手动打开的应用 —— 那不是我们该留在人家手机上的状态。
     *
     * ⚠️ 用 `settings delete` 而不是 `put 0`：这两个开关在系统里的语义是
     *   "有没有设置过"，写 0 与"设置过但为假"在部分 ROM 上并不等价。
     */
    fun disableFreeform(): List<String> = listOf(
        "settings delete global enable_freeform_support",
        "settings delete global force_resizable_activities",
    )

    /** WINDOWING_MODE_FULLSCREEN。**这个数字有测试钉住。** */
    const val WINDOWING_MODE_FULLSCREEN: Int = 1

    /**
     * 在虚拟屏上点一下。
     *
     * ⚠️ 坐标是**那块屏自己的坐标系**（720×1280 这种），不是主屏的。
     *    混用的后果是"点到了完全不相干的地方"，而界面不会报错。
     */
    fun tapOnDisplay(displayId: Int, x: Int, y: Int): String {
        requireDisplay(displayId)
        require(x >= 0 && y >= 0) { "坐标不能为负：($x,$y)" }
        return "input -d $displayId tap $x $y"
    }

    /** 在虚拟屏上滑动。 */
    fun swipeOnDisplay(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): String {
        requireDisplay(displayId)
        require(listOf(x1, y1, x2, y2).all { it >= 0 }) { "坐标不能为负" }
        require(durationMs in 1..10_000) { "时长越界：$durationMs" }
        return "input -d $displayId swipe $x1 $y1 $x2 $y2 $durationMs"
    }

    /**
     * 在虚拟屏上输入文字。
     *
     * ⚠️ `input text` 用空格分隔参数，所以空格必须写成 `%s`；
     *    单引号会打断 shell 引号，直接剔除（这是 `input` 的既定行为，
     *    不是我们的选择）。
     */
    fun typeOnDisplay(displayId: Int, text: String): String {
        requireDisplay(displayId)
        val escaped = text.replace("'", "").replace(" ", "%s")
        return "input -d $displayId text '$escaped'"
    }

    /** 在虚拟屏上按一个键（如 4 = BACK）。 */
    fun keyOnDisplay(displayId: Int, keyCode: Int): String {
        requireDisplay(displayId)
        require(keyCode > 0) { "keyCode 必须为正：$keyCode" }
        return "input -d $displayId keyevent $keyCode"
    }

    /** 关掉某个包的所有进程。 */
    fun forceStop(pkg: String): String {
        require(isValidPackage(pkg)) { "非法包名：$pkg" }
        return "am force-stop $pkg"
    }

    fun isValidDisplay(displayId: Int): Boolean = displayId > 0

    private fun requireDisplay(displayId: Int) {
        require(isValidDisplay(displayId)) {
            "displayId 必须为正：$displayId —— 0 是用户主屏，" +
                "往那上面注入等于直接动用户正在用的界面"
        }
    }
}
