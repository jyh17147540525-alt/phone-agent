package com.pocketagent.rootlogic

/**
 * root 档的**具体动作** —— 一条动作一个受限构造，不是"随便给个命令跑跑"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不是 `fun exec(cmd: String)`
 * ═══════════════════════════════════════════════════════════════
 *
 * 只要留一个"任意字符串"的口子，前面所有按能力做的门就白装了：
 * 模型能用它做任何事，`CapabilityMatrix` 也就成了一张装饰画。
 *
 * ⇒ 每个 [RootAction] 都是**封闭的**：参数是什么、合法范围是什么，
 *   由构造函数校验；`su -c` 的载荷由 [RootCommands.payload] 拼，
 *   而每个 token 都过 [RootCommands.safeToken] —— 带 `;`、空格、`$`、
 *   引号的字符串**根本构造不出来**。
 *
 * ⚠️ 这不是"防注入的最佳实践"，是这个模块的**存在理由**：
 *    root 权限下一条命令拼错，后果是不可撤销的。
 */
sealed interface RootAction {

    /** 点一下。坐标是屏幕像素。 */
    data class Tap(val x: Int, val y: Int) : RootAction

    /** 划一下。 */
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val millis: Int) : RootAction

    /** 按一次物理/系统键（home / back / recents / power …）。 */
    data class Key(val code: String) : RootAction

    /** 打一段字。 */
    data class Text(val text: String) : RootAction

    /** 抓一帧到 [path]（`screencap -p`）。 */
    data class Capture(val path: String) : RootAction

    /** `settings put <namespace> <key> <value>`。 */
    data class PutSetting(val namespace: SettingNamespace, val key: String, val value: String) : RootAction

    /** 停用一个预装应用（当前用户）。 */
    data class DisablePackage(val pkg: String) : RootAction

    /** 读系统属性。 */
    data class GetProp(val name: String) : RootAction

    /** 改系统属性（`setprop`）—— 与 [GetProp] 分开，读写是两种后果。 */
    data class SetProp(val name: String, val value: String) : RootAction

    /**
     * 把当前界面的 UI 树 dump 到 [path]（`uiautomator dump`）。
     *
     * ★ 这是 root 档**不依赖无障碍**读屏的第一步：dump 出一份 XML，
     *   再由 [UiautomatorDump] 解析成节点树。普通档做不到这件事
     *   （没有无障碍服务就拿不到树），这正是两档的分水岭之一。
     */
    data class DumpTree(val path: String) : RootAction

    /** 读任意路径的文件（`cat`）—— 普通档只到 SAF 授权目录为止。 */
    data class ReadFile(val path: String) : RootAction

    /** 探活：`id`。 */
    data object Probe : RootAction

    /** 白名单里的只读查询（`dumpsys` / `pm list` / `ls` …）。 */
    data class Query(val argv: List<String>) : RootAction
}

/** `settings` 的三个命名空间 —— 写错一个就是"改了但没生效"。 */
enum class SettingNamespace(val cliName: String) {
    SYSTEM("system"),
    SECURE("secure"),
    GLOBAL("global"),
}

/** 一次 `su -c` 的执行结果。 */
data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = code == 0

    /** 给模型看的一句话 —— 成功时只回显 stdout，失败时把 stderr 带上。 */
    fun describe(): String =
        if (ok) stdout.trim().ifEmpty { "已执行" } else "退出码 $code：${stderr.trim().ifEmpty { stdout.trim() }}"
}

/**
 * 真正跑命令的端口。
 *
 * ★ 是 `List<String>` 而不是 `String`：调用方（Android 侧）应当用
 *   `ProcessBuilder("su", "-c", payload)` 这种**数组形态**，不要自己再拼一次字符串。
 */
fun interface RootShellPort {
    fun run(argv: List<String>): ShellResult
}

/** 参数不合法 —— 在构造动作时就该被拦，绝不留到 `su` 里再出错。 */
class UnsafeRootArgument(val what: String, val value: String) : IllegalArgumentException("$what 不合法：$value")

/**
 * 把 [RootAction] 翻译成 `su -c` 的载荷。
 *
 * ⚠️ **每一条都自己校验参数**，不依赖调用方"传对"。
 */
object RootCommands {

    /** 允许出现在载荷里的字符集。故意窄：够用，且拼不出第二个命令。 */
    private val SAFE = Regex("""^[A-Za-z0-9_.:/@+-]*$""")

    private val KEY_CODES = setOf(
        "KEYCODE_HOME", "KEYCODE_BACK", "KEYCODE_APP_SWITCH", "KEYCODE_POWER",
        "KEYCODE_ENTER", "KEYCODE_DEL", "KEYCODE_TAB", "KEYCODE_ESCAPE",
        "KEYCODE_VOLUME_UP", "KEYCODE_VOLUME_DOWN", "KEYCODE_MENU",
        "KEYCODE_DPAD_UP", "KEYCODE_DPAD_DOWN", "KEYCODE_DPAD_LEFT", "KEYCODE_DPAD_RIGHT",
    )

    /** 白名单查询的**第一段**允许什么 —— 只读工具，不含 `rm`/`sh`/`pm uninstall`。 */
    private val QUERY_HEADS = setOf(
        "dumpsys", "getprop", "ls", "cat", "ps", "wm", "df", "id", "stat",
    )

    fun safeToken(what: String, value: String): String {
        // 空串/超长/换行/分号/引号/反引号/`$`/`|`/`&` 全部落在这里。
        if (value.isEmpty() || value.length > 256 || !SAFE.matches(value)) {
            throw UnsafeRootArgument(what, value)
        }
        return value
    }

    /**
     * 按键名必须落在白名单里。
     *
     * 抽成独立函数是因为它有两个调用方：[payload] 拼 `su -c` 字符串、
     * [DaemonCalls] 拼通道参数数组。规则只有一份，两处共用。
     */
    fun checkedKeyCode(code: String): String {
        val safe = safeToken("按键", code)
        if (safe !in KEY_CODES) throw UnsafeRootArgument("按键", code)
        return safe
    }

    /** 坐标：允许负数（多屏/虚拟屏会出现），但要挡住溢出级的值。 */
    private fun coord(name: String, v: Int): String {
        if (v < -100_000 || v > 100_000) throw UnsafeRootArgument(name, v.toString())
        return v.toString()
    }

    /** 组装 `su -c` 的完整 argv（Android 侧直接交给 ProcessBuilder）。 */
    fun argv(action: RootAction): List<String> = listOf("su", "-c", payload(action))

    /** 只要那一条命令本身，便于日志与测试。 */
    fun payload(action: RootAction): String = when (action) {
        is RootAction.Tap -> "input tap ${coord("x", action.x)} ${coord("y", action.y)}"

        is RootAction.Swipe -> buildString {
            append("input swipe ")
            append(coord("fromX", action.fromX)).append(' ')
            append(coord("fromY", action.fromY)).append(' ')
            append(coord("toX", action.toX)).append(' ')
            append(coord("toY", action.toY)).append(' ')
            // 时长下限 1ms：0 在部分 ROM 上会被当成"瞬移"，事件直接丢
            append(action.millis.coerceIn(1, 60_000))
        }

        is RootAction.Key -> {
            "input keyevent ${checkedKeyCode(action.code)}"
        }

        is RootAction.Text -> {
            // ★ 文字是唯一"人写的内容"，不能过 SAFE 白名单（中文、标点都会被拒）。
            //   做法是：只允许不含引号/反斜杠/换行的文本，再用单引号包起来 ——
            //   这样 `$`、`;`、空格都不再是 shell 语法。
            val t = action.text
            if (t.isEmpty() || t.length > 2_000 || t.any { it == '\'' || it == '\\' || it == '\n' || it == '\r' }) {
                throw UnsafeRootArgument("文字", t)
            }
            "input text '$t'"
        }

        is RootAction.Capture -> "screencap -p ${safeToken("路径", action.path)}"

        is RootAction.PutSetting ->
            "settings put ${action.namespace.cliName} " +
                "${safeToken("键", action.key)} ${safeToken("值", action.value)}"

        is RootAction.DisablePackage -> "pm disable-user --user 0 ${safeToken("包名", action.pkg)}"

        is RootAction.GetProp -> "getprop ${safeToken("属性名", action.name)}"

        is RootAction.SetProp ->
            "setprop ${safeToken("属性名", action.name)} ${safeToken("属性值", action.value)}"

        // ⚠️ `uiautomator dump` 只接受**一个**文件路径，且不接受 `-` 走 stdout
        //   （部分 ROM 上 `-` 会写出一个名为 `-` 的文件），所以路径必须显式给。
        is RootAction.DumpTree -> "uiautomator dump ${safeToken("dump 路径", action.path)}"

        is RootAction.ReadFile -> "cat ${safeToken("路径", action.path)}"

        RootAction.Probe -> "id"

        is RootAction.Query -> {
            if (action.argv.isEmpty()) throw UnsafeRootArgument("查询", "空")
            if (action.argv.size > 8) throw UnsafeRootArgument("查询", "参数过多")
            val head = safeToken("查询命令", action.argv.first())
            if (head !in QUERY_HEADS) throw UnsafeRootArgument("查询命令", head)
            action.argv.joinToString(" ") { safeToken("查询参数", it) }
        }
    }

    /** 这一条动作在能力矩阵里对应哪条能力 —— 用来复用同一套档位判定。 */
    fun capabilityOf(action: RootAction): String = when (action) {
        is RootAction.Tap, is RootAction.Swipe, is RootAction.Key, is RootAction.Text -> "screen.tap"
        is RootAction.Capture, is RootAction.DumpTree -> "screen.read"
        is RootAction.PutSetting -> "settings.write"
        is RootAction.DisablePackage -> "pm.disable"
        // ★ 读写分开：`getprop` 是只读的，归到 shell.root（最高危那条）；
        //   而 `prop.set` 在矩阵里的语义就是"改系统属性"。
        //   之前两者都被映射成 prop.set —— 一次只读查询就该按"改属性"去要授权，
        //   属于把闸门装反了方向。
        is RootAction.SetProp -> "prop.set"
        is RootAction.GetProp -> "shell.root"
        is RootAction.ReadFile -> "file.any"
        RootAction.Probe, is RootAction.Query -> "shell.root"
    }
}

/**
 * 执行器：校验 → 跑 → 归类结果。
 *
 * ★ 为什么不把校验放在 [RootShellPort] 实现里：那是 Android 侧代码，
 *   进不了离线验证器；而"参数能不能拼进命令"恰恰是这里最该被钉住的东西。
 */
class RootActions(
    private val port: RootShellPort,
    /**
     * 常驻通道（可选）。给了就**优先走它**，走不通再退回 [port]（`su -c`）。
     *
     * 为什么优先通道而不是优先 su：`su -c` 每次要 fork 一个 `su` 进程、
     * 拼一条 shell 字符串、再等 shell 解析 —— 单条 80–300ms，且字符串
     * 那条路上永远有转义问题。通道一次往返 <5ms，参数是数组。
     *
     * 为什么保留 su 兜底：通道要 root 侧起一个独立进程，装不上/被 SELinux
     * 拦/用户换机之后 Magisk 没重新放行，这三种情况都会让通道连不上。
     * 这时候"还能干活"比"架构干净"重要。
     */
    private val daemon: PrivilegePort? = null,
    private val ids: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(1),
) {

    /** 跑一条动作。参数不合法时**不发包**，直接返回失败。 */
    fun run(action: RootAction): ShellResult {
        // 先校验一次：两条路都不该收到非法参数，而校验失败时"一条命令都没起"
        // 这件事必须对两条路都成立。
        val argv = try {
            RootCommands.argv(action)
        } catch (e: UnsafeRootArgument) {
            return ShellResult(code = REJECTED, stdout = "", stderr = e.message ?: "参数不合法")
        }

        if (daemon != null) {
            val viaDaemon = runViaDaemon(action)
            if (viaDaemon != null) return viaDaemon
            // 通道不可用（连不上/断线/协议不对）→ 退回 su。**不重试通道**：
            // 重试会让"这条命令到底执行了没有"变成未知。
        }
        return port.run(argv)
    }

    /**
     * 走通道。[PrivilegeOutcome] 六类里，只有真正"通道层"的失败才返回 null
     * （表示"该退回 su"）；其余都已经是明确的结论。
     */
    private fun runViaDaemon(action: RootAction): ShellResult? {
        val call = try {
            DaemonCalls.of(action)
        } catch (e: UnsafeRootArgument) {
            return ShellResult(code = REJECTED, stdout = "", stderr = e.message ?: "参数不合法")
        }

        val client = PrivilegeClient(daemon ?: return null, ids)
        val outcomes = try {
            client.send(listOf(PrivilegeClient.Call(call.verb, call.argv)))
        } catch (e: PrivilegeClosed) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        return when (val outcome = outcomes.firstOrNull()) {
            is PrivilegeOutcome.Ok -> ShellResult(0, outcome.stdout, "")
            is PrivilegeOutcome.Exit -> ShellResult(outcome.code, outcome.stdout, outcome.stderr)
            is PrivilegeOutcome.DaemonError -> ShellResult(REJECTED, "", outcome.reason)
            is PrivilegeOutcome.Protocol -> null
            PrivilegeOutcome.NoReply -> null
            PrivilegeOutcome.DaemonDown -> null
            null -> null
        }
    }

    companion object {
        /** 本地拒绝的伪退出码 —— 与真实退出码区分开（真实命令不会返回负数）。 */
        const val REJECTED = -1
    }
}
