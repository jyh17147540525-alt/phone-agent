package com.pocketagent.rootlogic

/**
 * 守护进程侧的**处理器表**：`RootVerb` → 真正要不要跑一条本地命令。
 *
 * ★ 关键立场：**不信任客户端**。
 *
 * 客户端（`DaemonCalls`）已经校验过一遍参数，但守护进程跑在 root 下，
 * 一旦客户端被改写/冒充（token 泄漏、调试版 APK、被 hook 的进程），
 * 这一侧就是最后一道闸。所以这里的每个处理器都**重新校验**，不复用
 * "客户端已经检查过了"这个假设。
 *
 * ★ 第二条立场：**处理器不调 `su`**。守护进程本身已经是 root，
 * 再套一层 `su -c` 只会把参数数组重新拼成 shell 字符串 —— 那正是整条
 * 链路唯一会引入转义与注入问题的地方。这里一律用参数数组直起进程。
 */
fun interface LocalRunner {
    fun run(argv: List<String>, timeoutMs: Int): ShellResult
}

object DaemonHandlers {

    /** 处理器默认超时。`uiautomator dump` 偶尔要几秒，其余都很快。 */
    private const val DEFAULT_TIMEOUT_MS = 15_000

    /** 只读查询的合法首词 —— 与客户端 `RootCommands.QUERY_HEADS` 同一份规则。 */
    private val QUERY_HEADS = setOf("dumpsys", "getprop", "ls", "cat", "ps", "wm", "df", "id", "stat")

    /** 任何情况下都不许通过通道读的路径（块设备/内核内存）。 */
    private val FORBIDDEN_PREFIXES = listOf("/dev/block", "/dev/mem", "/dev/kmem", "/proc/self/mem", "/sys/kernel/debug")

    fun standard(runner: LocalRunner): Map<RootVerb, (ByteArray) -> DaemonLoop.WireReply> = mapOf(
        RootVerb.PING to { _ -> DaemonLoop.WireReply.Ok(0, "pong", "") },
        RootVerb.PROBE to { _ -> guarded { probe(runner) } },
        RootVerb.INJECT to { body -> guarded { inject(runner, body) } },
        RootVerb.CAPTURE to { body -> guarded { capture(runner, body) } },
        RootVerb.READ to { body -> guarded { read(runner, body) } },
        RootVerb.SETTINGS to { body -> guarded { settings(runner, body) } },
        RootVerb.QUERY to { body -> guarded { query(runner, body) } },
        RootVerb.EXEC to { body -> guarded { exec(runner, body) } },
        RootVerb.DISPLAY to { body -> guarded { display(runner, body) } },
    )

    /**
     * 参数不合法 → `Refused`，而不是让异常冒到 `DaemonLoop` 变成 `HANDLER_FAILED`。
     *
     * 两者对客户端的含义完全不同：`Refused` 是"这条路走不通（参数/权限）"，
     * 上层该换做法；`HANDLER_FAILED` 是"处理器自己炸了"，上层该当故障报出来。
     * 参数错被报成故障，日志里会永远混着"看起来像 bug 的用法错误"。
     */
    private inline fun guarded(block: () -> DaemonLoop.WireReply): DaemonLoop.WireReply = try {
        block()
    } catch (e: HandlerRejection) {
        DaemonLoop.WireReply.Refused(e.message ?: "参数不被接受")
    } catch (e: UnsafeRootArgument) {
        DaemonLoop.WireReply.Refused("参数越界：${e.message}")
    }

    // ── PROBE ────────────────────────────────────────────────────────────

    private fun probe(runner: LocalRunner): DaemonLoop.WireReply {
        val result = runner.run(listOf("id"), DEFAULT_TIMEOUT_MS)
        if (result.code != 0) return refused("探活失败（退出码 ${result.code}）")
        val identity = result.stdout.trim()
        // 不是 root 就别装作通道可用：调用方据此退回 su 那条路。
        return if (identity.contains("uid=0")) {
            DaemonLoop.WireReply.Ok(0, identity, "")
        } else {
            refused("通道进程不是 root（$identity）")
        }
    }

    // ── INJECT ───────────────────────────────────────────────────────────

    private fun inject(runner: LocalRunner, body: ByteArray): DaemonLoop.WireReply {
        val argv = decode(body) ?: return refused(BAD_ARGV)
        if (argv.isEmpty()) return refused(BAD_ARGV)

        val targets = when (argv[0]) {
            "tap" -> {
                if (argv.size != 3) return refused("tap 需要 x y 两个参数")
                argv.drop(1).map { checkedCoord("坐标", it) }
            }

            "swipe" -> {
                if (argv.size != 6) return refused("swipe 需要 5 个参数")
                argv.drop(1).take(4).map { checkedCoord("坐标", it) } + checkedDuration(argv[5])
            }

            "key" -> {
                if (argv.size != 2) return refused("key 需要 1 个参数")
                listOf(RootCommands.checkedKeyCode(argv[1]))
            }

            "text" -> {
                if (argv.size != 2) return refused("text 需要 1 个参数")
                listOf(checkedText(argv[1]))
            }

            else -> return refused("不认识的注入动作：${argv[0]}")
        }

        val result = runner.run(listOf("input") + argv[0] + targets, DEFAULT_TIMEOUT_MS)
        return reply(result)
    }

    // ── CAPTURE ──────────────────────────────────────────────────────────

    private fun capture(runner: LocalRunner, body: ByteArray): DaemonLoop.WireReply {
        val argv = decode(body) ?: return refused(BAD_ARGV)
        if (argv.size != 2) return refused("截图需要 子命令 + 路径")
        val path = checkedPath("路径", argv[1])

        val command = when (argv[0]) {
            "screencap" -> listOf("screencap", "-p", path)
            "dump" -> listOf("uiautomator", "dump", path)
            else -> return refused("不认识的抓取动作：${argv[0]}")
        }
        return reply(runner.run(command, DUMP_TIMEOUT_MS))
    }

    // ── READ ─────────────────────────────────────────────────────────────

    private fun read(runner: LocalRunner, body: ByteArray): DaemonLoop.WireReply {
        val argv = decode(body) ?: return refused(BAD_ARGV)
        if (argv.size != 1) return refused("读文件需要 1 个路径")
        val path = checkedPath("路径", argv[0])

        // 块设备与内核内存：读了要么无意义，要么把整机状态改坏（/dev/kmem）。
        if (FORBIDDEN_PREFIXES.any { path.startsWith(it) }) {
            return refused("这个路径不通过通道读（$path）")
        }
        return reply(runner.run(listOf("cat", path), DEFAULT_TIMEOUT_MS))
    }

    // ── SETTINGS ─────────────────────────────────────────────────────────

    private fun settings(runner: LocalRunner, body: ByteArray): DaemonLoop.WireReply {
        val argv = decode(body) ?: return refused(BAD_ARGV)
        if (argv.isEmpty()) return refused(BAD_ARGV)

        return if (argv[0] == "prop") {
            if (argv.size != 3) return refused("改属性需要 名字 + 值")
            val name = checkedToken("属性名", argv[1])
            val value = checkedText(argv[2])
            reply(runner.run(listOf("setprop", name, value), DEFAULT_TIMEOUT_MS))
        } else {
            if (argv.size != 3) return refused("改设置需要 命名空间 + 键 + 值")
            if (argv[0] !in SettingNamespace.entries.map { it.cliName }) {
                return refused("命名空间只能是 system/secure/global")
            }
            val key = checkedToken("键名", argv[1])
            val value = checkedToken("设置值", argv[2])
            reply(runner.run(listOf("settings", "put", argv[0], key, value), DEFAULT_TIMEOUT_MS))
        }
    }

    // ── QUERY ────────────────────────────────────────────────────────────

    private fun query(runner: LocalRunner, body: ByteArray): DaemonLoop.WireReply {
        val argv = decode(body) ?: return refused(BAD_ARGV)
        if (argv.isEmpty()) return refused(BAD_ARGV)
        if (argv[0] !in QUERY_HEADS) return refused("查询首词不在白名单：${argv[0]}")
        if (argv.size > 9) return refused("查询参数太多")

        val safe = listOf(argv[0]) + argv.drop(1).map { checkedToken("查询参数", it) }
        return reply(runner.run(safe, DEFAULT_TIMEOUT_MS))
    }

    // ── EXEC（受控） ──────────────────────────────────────────────────────

    /**
     * 通道里**唯一**能起"非固定命令"的地方，而且只认一种形状：
     * `pm disable-user --user 0 <包名>`。
     *
     * 为什么不做成通用 exec：通用 exec 等于把通道变成 root shell，
     * 那前面所有白名单都只是装饰。要加新命令，就在这里加一条具名分支 ——
     * 加的时候必须同时想清楚"它能不能被滥用"。
     */
    private fun exec(runner: LocalRunner, body: ByteArray): DaemonLoop.WireReply {
        val argv = decode(body) ?: return refused(BAD_ARGV)
        if (argv.size != 5 || argv[0] != "pm" || argv[1] != "disable-user" ||
            argv[2] != "--user" || argv[3] != "0"
        ) {
            return refused("通道只接受 pm disable-user --user 0 <包名>")
        }
        val pkg = checkedToken("包名", argv[4])
        if (pkg in PROTECTED) {
            return refused("不允许停用系统关键包：$pkg")
        }
        return reply(runner.run(listOf("pm", "disable-user", "--user", "0", pkg), DEFAULT_TIMEOUT_MS))
    }

    private val PROTECTED = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
    )

    // ── DISPLAY（虚拟屏）────────────────────────────────────────────────

    /**
     * 把目标 App 拉到副屏、在副屏上点、抓副屏画面。
     *
     * ⚠️ 这三条命令**不是 root 专属**：shell（uid 2000，例如经无线调试配对后
     *    的 `app_process`）同样能跑。所以通道的 PROBE 结果要能被上层区分
     *    "root / shell / 都不是"，别把 shell 直接判成不可用。
     *
     * ⚠️ display id 0 是**用户主屏**，一律拒绝 —— 往那儿拉 App 等于把用户的手机抢走。
     */
    private fun display(runner: LocalRunner, body: ByteArray): DaemonLoop.WireReply {
        val argv = decode(body) ?: return refused(BAD_ARGV)
        val command = DisplayCommands.fromProtocol(argv)
        return reply(runner.run(command, DUMP_TIMEOUT_MS))
    }

    // ── 共用校验 ─────────────────────────────────────────────────────────

    private fun reply(result: ShellResult): DaemonLoop.WireReply =
        DaemonLoop.WireReply.Ok(result.code, result.stdout, result.stderr)

    private fun refused(reason: String): DaemonLoop.WireReply = DaemonLoop.WireReply.Refused(reason)

    private fun decode(body: ByteArray): List<String>? = runCatching { PrivilegeWire.decodeArgv(body) }.getOrNull()

    private fun checkedToken(what: String, value: String): String = runCatching {
        RootCommands.safeToken(what, value)
    }.getOrElse { throw HandlerRejection("$what 不合法：$value") }

    private fun checkedPath(what: String, value: String): String = checkedToken(what, value)

    private fun checkedCoord(what: String, raw: String): String {
        val v = raw.toIntOrNull() ?: throw HandlerRejection("$what 不是整数：$raw")
        if (v < -100_000 || v > 100_000) throw HandlerRejection("$what 越界：$raw")
        return v.toString()
    }

    private fun checkedDuration(raw: String): String {
        val v = raw.toIntOrNull() ?: throw HandlerRejection("时长不是整数：$raw")
        return v.coerceIn(1, 60_000).toString()
    }

    private fun checkedText(text: String): String {
        if (text.isEmpty()) throw HandlerRejection("文本为空")
        if (text.length > 2_000) throw HandlerRejection("文本过长")
        if (text.any { it == '\n' || it == '\r' }) throw HandlerRejection("文本含换行")
        return text
    }

    /**
     * 校验失败时抛这个，由 `DaemonLoop` 统一翻成 `ERROR` 帧（带原因）。
     *
     * 不用 `IllegalArgumentException` 是为了在日志里一眼分清：
     * "客户端送的参数不合法" vs "处理器自己写错了"。
     */
    class HandlerRejection(reason: String) : Exception(reason)

    private const val BAD_ARGV = "请求参数无法解析"
    private const val DUMP_TIMEOUT_MS = 30_000
}
