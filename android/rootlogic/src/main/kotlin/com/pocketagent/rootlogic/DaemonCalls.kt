package com.pocketagent.rootlogic

/**
 * `RootAction` → **常驻通道请求** 的映射（不走 `su`，走 [PrivilegeWire]）。
 *
 * 为什么要有这一层：`RootCommands.argv()` 产出的是 `su -c "…"` 的**一整串 shell**，
 * 而常驻通道要的是**参数数组**（不经过 shell，也就没有引号/转义/注入这一整类问题）。
 * 两者形状不同，不能复用同一个拼装函数 —— 硬要复用，等于把 shell 字符串再
 * 拆回数组，那是把已经做过一遍的转义工作倒着做一遍。
 *
 * 但**校验必须复用**：`RootCommands.safeToken` / 坐标范围 / keycode 白名单
 * 是同一套规则。两套白名单迟早漂移，而漂移的方向通常是"其中一套变松"。
 *
 * ⚠️ 参数数组形态下，`RootAction.Text` 里的空格、分号、引号**全部无害**
 * （它们只是数组里的字符）。这里仍然校验长度与换行，是为了让守护进程侧的
 * 日志不会因为一个换行被劈成两行。
 */
data class DaemonCall(val verb: RootVerb, val argv: List<String>) {
    /** 审计/排障用的一行描述。**不含**原始文本（可能含用户隐私）。 */
    fun describe(): String = "${verb.name.lowercase()} ${argv.firstOrNull() ?: ""}".trim()
}

object DaemonCalls {

    /**
     * 把一次动作翻成通道请求。
     *
     * @throws UnsafeRootArgument 参数越界时抛出（与 `su` 那条路**同一个异常类型**，
     *   这样上层 `RootActions.run` 的 catch 不用分两套）。
     */
    fun of(action: RootAction): DaemonCall = when (action) {
        is RootAction.Tap -> DaemonCall(
            RootVerb.INJECT,
            listOf("tap", coord("x", action.x), coord("y", action.y)),
        )

        is RootAction.Swipe -> DaemonCall(
            RootVerb.INJECT,
            listOf(
                "swipe",
                coord("fromX", action.fromX), coord("fromY", action.fromY),
                coord("toX", action.toX), coord("toY", action.toY),
                action.millis.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS).toString(),
            ),
        )

        is RootAction.Key -> {
            val code = RootCommands.checkedKeyCode(action.code)
            DaemonCall(RootVerb.INJECT, listOf("key", code))
        }

        is RootAction.Text -> DaemonCall(
            RootVerb.INJECT,
            listOf("text", checkText(action.text)),
        )

        is RootAction.Capture -> DaemonCall(
            RootVerb.CAPTURE,
            listOf("screencap", RootCommands.safeToken("截图路径", action.path)),
        )

        is RootAction.DumpTree -> DaemonCall(
            RootVerb.CAPTURE,
            listOf("dump", RootCommands.safeToken("dump 路径", action.path)),
        )

        is RootAction.ReadFile -> DaemonCall(
            RootVerb.READ,
            listOf(RootCommands.safeToken("文件路径", action.path)),
        )

        is RootAction.PutSetting -> DaemonCall(
            RootVerb.SETTINGS,
            listOf(
                action.namespace.cliName,
                RootCommands.safeToken("键名", action.key),
                RootCommands.safeToken("设置值", action.value),
            ),
        )

        is RootAction.SetProp -> DaemonCall(
            RootVerb.SETTINGS,
            listOf("prop", RootCommands.safeToken("属性名", action.name), checkText(action.value)),
        )

        is RootAction.GetProp -> DaemonCall(
            RootVerb.QUERY,
            listOf("getprop", RootCommands.safeToken("属性名", action.name)),
        )

        is RootAction.DisablePackage -> DaemonCall(
            // `pm disable-user` 在守护进程侧是**专用处理**，不是白名单 EXEC ——
            // 停用包会改系统状态，不该和"跑一条只读命令"共用一个口子。
            RootVerb.EXEC,
            listOf("pm", "disable-user", "--user", "0", RootCommands.safeToken("包名", action.pkg)),
        )

        is RootAction.Probe -> DaemonCall(RootVerb.PROBE, emptyList())

    // ★ 虚拟屏走独立的 DISPLAY 动词，不复用 INJECT/CAPTURE：
    //   它们的参数形状不同（多一个 display id），混成一个动词会让服务端难以分辨
    //   "点主屏"和"点副屏"——而这两者的后果差一整块屏幕。
    is RootAction.DisplayLaunch ->
        DaemonCall(RootVerb.DISPLAY, listOf("launch", action.component, action.displayId.toString()))
    is RootAction.DisplayTap ->
        DaemonCall(
            RootVerb.DISPLAY,
            listOf("input", action.displayId.toString(), "tap", action.x.toString(), action.y.toString()),
        )
    is RootAction.DisplayCapture ->
        DaemonCall(RootVerb.DISPLAY, listOf("capture", action.displayId.toString(), action.path))
    is RootAction.DisplayStop ->
        DaemonCall(RootVerb.DISPLAY, listOf("stop", action.packageName))

        is RootAction.Query -> DaemonCall(
            RootVerb.QUERY,
            listOf(RootCommands.safeToken("查询首词", action.argv.firstOrNull() ?: "")) +
                action.argv.drop(1).map { RootCommands.safeToken("查询参数", it) },
        )
    }

    private fun coord(name: String, v: Int): String {
        // 复用同一条范围规则（±100_000，允许负值表示多屏偏移）。
        if (v < -MAX_COORD || v > MAX_COORD) {
            throw UnsafeRootArgument(name, v.toString())
        }
        return v.toString()
    }

    private fun checkText(text: String): String {
        if (text.isEmpty()) throw UnsafeRootArgument("文本", "空")
        if (text.length > MAX_TEXT) throw UnsafeRootArgument("文本", "超过 $MAX_TEXT 字")
        if (text.any { it == '\n' || it == '\r' }) throw UnsafeRootArgument("文本", "含换行")
        return text
    }

    private const val MAX_COORD = 100_000
    private const val MIN_SWIPE_MS = 1
    private const val MAX_SWIPE_MS = 60_000
    private const val MAX_TEXT = 2_000
}
