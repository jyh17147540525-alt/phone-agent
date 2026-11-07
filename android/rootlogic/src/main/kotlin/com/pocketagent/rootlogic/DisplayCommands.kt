package com.pocketagent.rootlogic

/**
 * 虚拟屏命令的**参数构造**（纯逻辑，不执行）。
 *
 * ## 为什么单独一层
 *
 * 虚拟屏这条路有四个动作，每个都由一条 shell 命令完成：
 *
 * | 动作 | 命令 | 需要什么身份 |
 * |---|---|---|
 * | 把目标 App 拉到副屏 | `am start --display N -n 包/类` | shell 或 root |
 * | 在副屏上点/滑/打字 | `input -d N …` | shell 或 root |
 * | 抓副屏画面 | `screencap -d N -p 路径` | shell 或 root |
 * | 建屏 / 收屏 | 应用侧 `createVirtualDisplay` / `release` | **不需要特权** |
 *
 * 第三列是这条能力真正的成本所在：**建屏不用 root，把别的 App 拉进去才要**。
 * 2026-10-27 真机（K60 / Android 15）实测过这四步，见 `:hosting` 的
 * `VirtualScreenCommands` 头注释。
 *
 * ## 为什么参数校验写在这里
 *
 * 这些字符串最终会交给 `ProcessBuilder` 的**数组**形态（不拼 shell 字符串），
 * 但 `am` / `input` 自己会解析它后面的参数，所以仍然存在"传进去一个能改变行为的参数"
 * 的风险：
 *
 * - `--display 0` 是**用户主屏** —— 往那儿拉 App 就是把用户的手机抢走；
 * - `am start` 的参数里带 `--activity-clear-top` 这类开关会改变任务栈；
 * - `input` 的子命令不止 tap/滑动，还有 `draganddrop`、`sleep` 等，白名单外一律不放。
 *
 * 所以这里做的是**白名单**，不是"过滤危险字符"。
 */
object DisplayCommands {

    /** 副屏 id 的下限：0 是主屏，永远不碰。 */
    const val MIN_DISPLAY_ID = 1

    /** 上限：真实设备上副屏 id 不会超过这个量级，超了说明参数错了。 */
    const val MAX_DISPLAY_ID = 99

    /** 包名：至少两段，和 `am`/`pm` 的接受范围一致。 */
    private val PACKAGE = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$""")

    /** `包名/类名`。类名允许 `$`（内部类）与前导点（`.MainActivity`）。 */
    private val COMPONENT = Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+/[a-zA-Z0-9_.$]+$""")

    /** `input` 允许的子命令。刻意不含 `sleep` / `draganddrop` / `motionevent`。 */
    private val INPUT_HEADS = setOf("tap", "swipe", "keyevent", "text", "roll")

    /** 坐标与时长只允许十进制数字（含负号：多屏坐标可能为负）。 */
    private val NUMBER = Regex("""^-?\d{1,6}$""")

    /** 文字参数：允许中文、空格、标点，但拒绝换行与单引号（后者会破坏 `input text` 的引号）。 */
    private val TEXT_SAFE = Regex("""^[^\n\r']{1,200}$""")

    /**
     * 解析 display id。
     *
     * @throws UnsafeRootArgument 非法或为 0（主屏）
     */
    fun displayId(raw: String): Int {
        val value = raw.toIntOrNull()
            ?: throw UnsafeRootArgument("副屏 id", raw)
        if (value < MIN_DISPLAY_ID || value > MAX_DISPLAY_ID) {
            throw UnsafeRootArgument("副屏 id（$MIN_DISPLAY_ID–$MAX_DISPLAY_ID，0 是用户主屏）", raw)
        }
        return value
    }

    /**
     * 把副屏上的 App 停掉。
     *
     * ⚠️ 这一步**必须在收屏之前**：直接 release 虚拟屏，系统会把上面的 Task
     *    **挪回主屏全屏**，用户会看到应用自己蹦到脸前（真机 2026-10-27 实测）。
     */
    fun forceStop(packageName: String): List<String> {
        if (!PACKAGE.matches(packageName)) {
            throw UnsafeRootArgument("包名", packageName)
        }
        return listOf("am", "force-stop", packageName)
    }

    /** 把目标 App 拉到副屏。 */
    fun launch(displayId: Int, component: String): List<String> {
        requireDisplay(displayId)
        if (!COMPONENT.matches(component)) {
            throw UnsafeRootArgument("组件名（包名/类名）", component)
        }
        return listOf(
            "am", "start",
            "--display", displayId.toString(),
            // freeform 会让副屏上的 App 变成小窗（用户在主屏也能看见影子），固定全屏
            "--windowingMode", "1",
            "-n", component,
        )
    }

    /** 在副屏上执行一次 `input`。 */
    fun input(displayId: Int, args: List<String>): List<String> {
        requireDisplay(displayId)
        if (args.isEmpty()) throw UnsafeRootArgument("input 参数", "")
        val head = args[0]
        if (head !in INPUT_HEADS) throw UnsafeRootArgument("input 子命令", head)

        val rest = when (head) {
            "tap" -> {
                if (args.size != 3) throw UnsafeRootArgument("tap 参数（x y）", args.drop(1).joinToString(" "))
                args.drop(1).map { requireNumber("坐标", it) }
            }
            "swipe" -> {
                // args 含子命令本身：swipe + 四个坐标 + 时长 = 6
                if (args.size != 6) throw UnsafeRootArgument("swipe 参数（x1 y1 x2 y2 时长）", args.drop(1).joinToString(" "))
                args.drop(1).map { requireNumber("坐标或时长", it) }
            }
            "keyevent" -> {
                if (args.size != 2) throw UnsafeRootArgument("keyevent 参数（键码）", args.drop(1).joinToString(" "))
                listOf(requireKeyName(args[1]))
            }
            "text" -> {
                if (args.size != 2) throw UnsafeRootArgument("text 参数（一段文字）", args.drop(1).joinToString(" "))
                val text = args[1]
                if (!TEXT_SAFE.matches(text)) throw UnsafeRootArgument("输入文字", text)
                listOf(text)
            }
            else -> {
                if (args.size != 2) throw UnsafeRootArgument("roll 参数（dx dy）", args.drop(1).joinToString(" "))
                args.drop(1).map { requireNumber("滚动量", it) }
            }
        }
        return listOf("input", "-d", displayId.toString()) + head + rest
    }

    /** 抓副屏画面。 */
    fun capture(displayId: Int, path: String): List<String> {
        requireDisplay(displayId)
        if (!path.startsWith("/") || path.contains("..") || path.contains('\n')) {
            throw UnsafeRootArgument("截图路径", path)
        }
        return listOf("screencap", "-d", displayId.toString(), "-p", path)
    }

    /**
     * 协议入口：`argv` → 实际要跑的命令。
     *
     * 通道里只认三种子命令。第四个动作（建屏/收屏）在应用侧做，不走通道 ——
     * 理由：`VirtualDisplay` 是应用自己的对象，交给 shell 反而要额外找它、
     * 还会在应用被回收后留下没人负责的屏。
     */
    fun fromProtocol(argv: List<String>): List<String> {
        if (argv.isEmpty()) throw UnsafeRootArgument("display 子命令", "")
        return when (argv[0]) {
            "launch" -> {
                if (argv.size != 3) throw UnsafeRootArgument("launch 参数（组件 副屏 id）", argv.drop(1).joinToString(" "))
                launch(displayId(argv[2]), argv[1])
            }
            "input" -> {
                if (argv.size < 3) throw UnsafeRootArgument("input 参数（副屏 id + 参数）", argv.drop(1).joinToString(" "))
                input(displayId(argv[1]), argv.drop(2))
            }
            "capture" -> {
                if (argv.size != 3) throw UnsafeRootArgument("capture 参数（副屏 id 路径）", argv.drop(1).joinToString(" "))
                capture(displayId(argv[1]), argv[2])
            }
            // 收屏之前必须先停 App：直接 release 虚拟屏，系统会把 Task 挪回主屏
            "stop" -> {
                if (argv.size != 2) throw UnsafeRootArgument("stop 参数（包名）", argv.drop(1).joinToString(" "))
                forceStop(argv[1])
            }
            else -> throw UnsafeRootArgument("display 子命令（launch/input/capture/stop）", argv[0])
        }
    }

    private fun requireDisplay(displayId: Int) {
        if (displayId < MIN_DISPLAY_ID || displayId > MAX_DISPLAY_ID) {
            throw UnsafeRootArgument("副屏 id（$MIN_DISPLAY_ID–$MAX_DISPLAY_ID，0 是用户主屏）", displayId.toString())
        }
    }

    private fun requireNumber(what: String, raw: String): String {
        if (!NUMBER.matches(raw)) throw UnsafeRootArgument(what, raw)
        return raw
    }

    /** 键名只允许 `KEYCODE_*` 或纯数字键码 —— 其它写法会变成 `input` 的自由参数。 */
    private fun requireKeyName(raw: String): String {
        val ok = raw.matches(Regex("""^KEYCODE_[A-Z0-9_]+$""")) || raw.matches(Regex("""^\d{1,3}$"""))
        if (!ok) throw UnsafeRootArgument("键名", raw)
        return raw
    }
}
