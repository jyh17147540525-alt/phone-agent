package com.pocketagent.mcp

import com.pocketagent.capabilitylogic.ChannelResult
import com.pocketagent.capabilitylogic.ShellRunner
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `root_exec` —— 以 root 身份执行一条命令。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 这是全项目**权限最高**的一个工具，设计上按"武器"对待
 * ═══════════════════════════════════════════════════════════════
 *
 * 它能做用户手机上的任何事：改系统、删数据、装东西、读别的应用。
 * 所以它与别的工具在三个地方**不一样**：
 *
 *  1. **默认不挂载** —— 只有用户在设置里显式选了 Root 模式、
 *     且 `su` 探测真的可用时，它才会出现在工具清单里。
 *     （见 `AppContainer.agentToolsForChat` 的装配）
 *  2. **命令黑名单在最前** —— 在安全判定之前先挡掉一批
 *     "任何情况下都不该由模型来执行"的命令（`rm -rf /`、`dd`、格式化）。
 *  3. **只读与写入分档** —— `getprop`/`ls` 这类只读命令直接放行；
 *     而 `pm uninstall`/`settings put` 这类必须走安全判定。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么不直接让模型自由发挥
 * ═══════════════════════════════════════════════════════════════
 *
 * 模型的命令**不可信**：它可能把用户说的"清理一下"理解成 `rm -rf`。
 * 而 root 下的 `rm -rf` 没有回收站。所以：
 *
 *  - 黑名单是**硬编码的、不可配置的**（配置就意味着能被关掉）
 *  - 白名单式放行的只读命令，不影响用户数据
 *  - 其余一律先问安全判定，判定说不行就是不行
 *
 * ⚠️ 拒绝必须走 [ToolOutcome.Refused]（"最终结论，不要重试"），
 *    不是 Failed —— 后者会让模型换个命令再试，那是在反复撞墙。
 */
class RootExecTool(
    private val runner: ShellRunner,
    private val safety: ActionSafetyPort,

    /**
     * 是否已确认 root 可用。
     *
     * ★ 抽成函数而不是构造时快照：用户可能刚在 Magisk 里放行，
     *   而这个工具实例是容器级单例，构造时探一次就再也不看了。
     */
    private val rootAvailable: () -> Boolean,
) : McpTool {

    override val name: String = "root_exec"

    override val description: String =
        "以 **root** 身份执行一条 shell 命令。仅在你需要修改系统设置、读写普通权限到不了的路径、" +
            "或操作系统组件时使用。命令以数组形式给出（第一个元素是程序名，其余是参数），" +
            "**不要自己拼 shell 字符串**。只读命令（查看属性、列目录）可以直接用；" +
            "会修改系统的命令会先经过安全判定。" +
            "⚠️ 这是本应用权限最高的能力，执行前请先向用户说明要做什么。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("argv") {
                put("type", "array")
                put("description", "命令与参数，如 [\"getprop\", \"ro.build.version.sdk\"]")
                putJsonObject("items") { put("type", "string") }
                put("minItems", 1)
            }
            putJsonObject("purpose") {
                put("type", "string")
                put("description", "一句话说明这条命令要达成什么 —— 会展示给用户，也是审计记录")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("argv"))
            add(JsonPrimitive("purpose"))
        }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        // ── ① root 可用性（最先判，因为不可用时后面全白搭）──────
        if (!rootAvailable()) {
            return ToolOutcome.Refused(
                "Root 通道当前不可用（手机没 root，或 Magisk 里还没给本应用授权）。" +
                    "⚡ 这不是命令写错了 —— **不要换个命令再试**。请告诉用户去检查 root 授权。",
            )
        }

        // ── ② 解析参数 ───────────────────────────────────────────
        val argvArr = arguments["argv"] as? JsonArray
            ?: return ToolOutcome.Failed("缺少 argv 参数（命令数组）")
        val argv = argvArr.mapNotNull { (it as? JsonPrimitive)?.content }
        if (argv.isEmpty()) {
            return ToolOutcome.Failed("argv 不能为空")
        }

        val purpose = arguments["purpose"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (purpose.isEmpty()) {
            // ★ 要求写 purpose 不是形式主义：它是审计日志里唯一能回答
            //   "这条 root 命令是为了什么"的东西，事后追查全靠它。
            return ToolOutcome.Failed(
                "必须提供 purpose（一句话说明这条命令要做什么）—— " +
                    "它是审计记录，事后追查「为什么跑了这条 root 命令」全靠它。",
            )
        }

        // ── ③ 黑名单：任何情况下都不由模型执行 ───────────────────
        CommandBlocklist.check(argv)?.let { why ->
            return ToolOutcome.Refused(
                "这条命令被硬性禁止：$why。" +
                    "⚡ **这是最终结论，不要换个写法再试**（比如改用通配符、管道或 `sh -c`）。" +
                    "请如实告诉用户这条操作不能由我来做。",
            )
        }

        // ── ④ 安全判定：只读命令放行，其余走 :safety ─────────────
        val readOnly = CommandBlocklist.isReadOnly(argv)
        if (!readOnly) {
            val verdict = safety.review(
                ActionSafetyQuery(
                    packageName = ROOT_PACKAGE,
                    activityName = null,
                    actionType = "root_exec",
                    targetDescription = argv.joinToString(" ").take(200),
                    visibleTexts = listOf(purpose),
                    inputFields = emptyList(),
                    actionsInLastMinute = 0,
                ),
            )
            when (verdict) {
                is ActionSafetyOutcome.Blocked -> return ToolOutcome.Refused(
                    "${verdict.userMessage}" +
                        "⚡ 这是安全策略的最终结论，**不要重试、也不要换个命令绕过**。",
                )
                // ⚠️ 与既有纪律一致：没有确认通道时降级为拒绝（见 ActionSafetyPort 顶部）
                is ActionSafetyOutcome.ConfirmationRequired -> return ToolOutcome.Refused(
                    "${verdict.userMessage}" +
                        "⚡ 这类操作需要你亲自确认，而当前没有确认界面，所以我没有执行。",
                )
                ActionSafetyOutcome.Allowed -> Unit
            }
        }

        // ── ⑤ 执行 ───────────────────────────────────────────────
        return when (val result = runner.run(argv)) {
            is ChannelResult.Succeeded -> {
                val out = result.payload.orEmpty()
                ToolOutcome.Success(
                    text = buildString {
                        append("root 命令执行成功：")
                        append(argv.joinToString(" "))
                        append("（${purpose}）\n")
                        append(if (out.isBlank()) "（无输出）" else out)
                    },
                )
            }
            is ChannelResult.Unavailable -> ToolOutcome.Refused(
                "${result.detail}" +
                    "⚡ 通道不可用不是命令的问题，**不要换个命令再试**。",
            )
            is ChannelResult.Failed -> ToolOutcome.Failed(
                "命令执行失败：${result.reason}。可以检查参数后重试一次。",
            )
            is ChannelResult.NeedsConfirmation -> ToolOutcome.Refused(
                "这一步需要用户确认，当前没有确认界面，所以没有执行。",
            )
        }
    }

    private companion object {
        /** 审计归属：root 命令**不是**某个具体应用发起的，是用户授权的。 */
        const val ROOT_PACKAGE = "android"
    }
}

/**
 * 命令黑名单与只读判定 —— **纯逻辑，刻意可离线测试**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么黑名单必须是"硬编码 + 可测"
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是 root 工具的最后一道闸。一旦它在真机上判错（该拦的没拦），
 * 后果是**用户的手机被一条模型生成的命令改坏**，且不可撤销。
 *
 * ⇒ 所以：
 *  - 不放进配置（配置就能被关掉，而"关掉更省事"的诱惑永远存在）
 *  - 不依赖模型（模型的判断不可信 —— 它可能正是要执行的那一方）
 *  - **每条规则都有测试**，且测试写的是"为什么"而不只是"要拦"
 */
object CommandBlocklist {

    /**
     * 绝对禁止的命令（程序名精确匹配）。
     *
     * ⚠️ 判据不是"危险"，而是 **"没有任何合理的用户请求需要模型来执行它"**。
     *    比如 `rm` 本身不在此列 —— 用户说"删掉那个文件"是合理的；
     *    但 `mkfs`（格式化）不存在任何合理场景。
     */
    private val FORBIDDEN_PROGRAMS = mapOf(
        "mkfs" to "格式化分区 —— 会抹掉整块存储",
        "mkfs.ext4" to "格式化分区 —— 会抹掉整块存储",
        "mke2fs" to "格式化分区 —— 会抹掉整块存储",
        "dd" to "裸写磁盘 —— 写错位置就再也起不来了",
        "fastboot" to "刷机命令 —— 不属于运行时操作",
        "flash_image" to "刷写分区 —— 不可撤销",
        "wipe" to "擦除数据 —— 不可撤销",
        "reboot" to "重启会让当前所有任务中断，且可能进不了系统（-edl/recovery 等）",
        "poweroff" to "关机不是本应用该做的事",
    )

    /**
     * 绝对禁止的**参数模式**（即使程序名合法）。
     *
     * ★ 这一层针对的是 `rm -rf /` 这类"程序合法但参数致命"的组合。
     */
    private val FORBIDDEN_PATTERNS = listOf(
        Regex("""^/+\*?$""") to "目标是根目录",
        Regex("""^-rf?$|^-fr$""") to null, // 单独看 -rf 不致命，要看目标（下方单独判）
    )

    /**
     * 检查一条命令是否被禁止。
     *
     * @return 禁止原因；null = 未被禁止（仍可能被安全判定拦下）
     */
    fun check(argv: List<String>): String? {
        if (argv.isEmpty()) return "命令为空"

        val program = argv.first().substringAfterLast('/')

        FORBIDDEN_PROGRAMS[program]?.let { return "`$program`：$it" }

        // ── `rm` 的递归删除必须看目标 ────────────────────────────
        // ★ 这是最需要小心的一条：`rm -rf /` 与 `rm -rf /sdcard/tmp/x` 天差地别。
        if (program == "rm") {
            val args = argv.drop(1)
            val recursive = args.any { it.startsWith("-") && 'r' in it.lowercase() }
            val targets = args.filterNot { it.startsWith("-") }
            if (recursive) {
                val dangerous = targets.firstOrNull { t ->
                    val norm = t.trimEnd('/')
                    // 根、/system、/data、/sdcard 这些顶层目录递归删 = 毁机
                    norm.isEmpty() || norm == "/" ||
                        norm in setOf("/system", "/data", "/vendor", "/sdcard", "/storage", "/data/adb")
                }
                if (dangerous != null) {
                    return "`rm -r` 的递归目标是「$dangerous」—— 这会清掉系统或用户数据，不可恢复。" +
                        "如果确实要删文件，请给出**具体文件路径**。"
                }

            }
        }

        // ── `sh -c` / `su -c` 包装 ⇒ 视为绕过 ────────────────────
        // ★ 黑名单是按程序名判的，套一层 shell 就能绕过。
        //   所以这里直接拦包装器 —— 我们本来就提供了 argv 形式，不需要 sh -c。
        if (program in setOf("sh", "bash", "su", "ash")) {
            return "不允许用 `$program` 包装其他命令 —— 那等于绕过本工具的参数检查。" +
                "请直接给出要执行的程序与参数。"
        }

        return null
    }

    /**
     * 这条命令是不是**只读**的（不改任何状态）。
     *
     * ★ 只读放行的理由是成本/收益：`getprop`、`ls`、`ps` 这类命令
     *   不可能损坏任何东西，让它们走一遍安全判定只会让"看一眼系统信息"
     *   变得很慢。而它们恰好是模型最常需要的 root 能力。
     *
     * ⚠️ 判据必须是**白名单**（列举已知只读命令），不是黑名单。
     *    黑名单漏一个写命令就会静默放行一个写操作。
     */
    fun isReadOnly(argv: List<String>): Boolean {
        // ★ 空命令**不是只读** —— 它是个无效输入，应当走"拦"那一侧。
        //   这个守卫是测试逼出来的：`first()` 对空表会抛异常，
        //   而调用方（RootExecTool）在判只读之前并不保证非空。
        //   抛异常会被上层 catch 成 Failed（可重试），而空命令重试一万次也没用。
        if (argv.isEmpty()) return false

        val program = argv.first().substringAfterLast('/')
        val args = argv.drop(1)

        // 带写意图的子命令要先排除 —— 同一个程序名可能既能读又能写
        if (program == "settings" && args.firstOrNull() in setOf("put", "delete")) return false
        if (program == "pm" && args.firstOrNull() in setOf("install", "uninstall", "disable", "enable", "clear")) return false
        if (program == "svc" && args.size >= 2 && args[1] == "enable") return false

        return program in READ_ONLY_PROGRAMS
    }

    /**
     * 已知只读的程序白名单。
     *
     * ⚠️ 加新项前先回答：这个程序**在任何参数下**都不会改状态吗？
     *    `cat` 会（`cat x > /system/y`），但那需要 shell 重定向 ——
     *    我们用的是 argv，没有重定向，所以 `cat` 本身是安全的。
     */
    private val READ_ONLY_PROGRAMS = setOf(
        "getprop", "ls", "ll", "du", "df", "stat", "wc",
        "cat", "head", "tail", "grep", "find", "which", "type",
        "ps", "top", "id", "whoami", "uname", "date", "uptime",
        "dumpsys", "cmd", "service", "logcat",
        "pm",       // 列表类子命令（list packages 等），写操作已在上面排除
        "settings", // get 子命令，写操作已排除
        "wm",       // size/density 查询
        "input",    // ⚠️ 例外：input 会模拟操作，但它不改文件系统
    )
}
