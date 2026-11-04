package com.pocketagent.agentlogic.orchestration

/**
 * 一条能力的「机械说明」—— 手脚本目录的最小单位。
 *
 * ## 为什么 MCP 的 description 不够
 *
 * MCP 工具自带的 `description` 是写给**模型**看的：一段人话，说明"这个工具干什么"。
 * 但编排器（[com.pocketagent.agentlogic.orchestration.ToolCatalog] 的读者）要的是
 * **机器可判定的字段**，而且这些问题用自然语言回答不了：
 *
 * | 编排器要问的 | 只看 description 会怎样 |
 * |---|---|
 * | 这一步会不会占住用户的屏幕？ | 不知道 → 用户正在打字时被抢屏 |
 * | 失败了能不能重试？ | 不知道 → 把"付过一次款"再付一次 |
 * | 要不要先确认？ | 不知道 → 用户睡醒发现被停用了三个应用 |
 * | 超时该给多久？ | 不知道 → 长任务被 3 秒的默认超时砍断 |
 *
 * ## 这些字段**不是**标签，是判据
 *
 * - [needsScreen] 必须与 `:rootlogic` 的 `TierRouter` 结论一致 —— 不一致就是
 *   一处说"不占屏"、另一处说"占屏"，最终由谁先跑谁说了算。对账函数见
 *   [ToolCatalog.screenDrift]。
 * - [reversible] 决定失败后要不要**回滚**。写反的后果不对称：把可逆的标成不可逆
 *   → 只是多问一句；把不可逆的标成可逆 → 用户的数据没了。
 * - [idempotent] 决定**重试**。只有"重跑一次结果一样"的才能自动重试。
 *
 * ## 校验放在构造里
 *
 * 这些字段错了不会报错，只会让任务在真机上走错路。所以宁可在这里抛
 * [IllegalArgumentException]：写错的目录在启动时就炸，而不是半夜在用户手机上炸。
 */
data class ToolSpec(
    /** 工具名。必须与 `:mcp` 里 `McpTool.name` 逐字一致（[ToolCatalog.coverage] 会核对）。 */
    val id: String,
    /** 给用户看的短名。出现在"正在做：…"这类进度文案里。 */
    val title: String,
    /** 实际走哪条通道。见 [ToolChannel]。 */
    val channel: ToolChannel,
    /** 这一步会改变什么。见 [ToolEffect]。 */
    val effects: Set<ToolEffect>,
    /**
     * 是否占用**用户正在用的那块屏**。
     *
     * ⚠️ 与"改不改屏幕"是两件事：[ToolEffect.MOVES_SCREEN] 回答"会动界面吗"，
     *    本字段回答"动了之后用户还能不能正常用手机"。虚拟屏里操作应用是
     *    [ToolEffect.MOVES_SCREEN] 但 `needsScreen = false`。
     */
    val needsScreen: Boolean = false,
    /**
     * 失败后能不能回到做之前的状态。
     *
     * 判据是「撤销路径存在且**用户自己**能走通」：`pm enable` 能把停用的应用装回来
     * 算可逆；`setprop` 写的值重启才清、中途可能把系统卡在启动循环，不算可逆。
     */
    val reversible: Boolean = true,
    /**
     * 同样的参数再跑一次，结果是否一样。
     *
     * 只有 true 的才允许自动重试。点一下按钮通常**不**幂等（第二次是双击）。
     */
    val idempotent: Boolean = false,
    /** 能否与其他动作打包成一批（一次读屏、多次动作）。见 `android_batch`。 */
    val batchable: Boolean = false,
    /** 正常情况下的预期耗时（毫秒）。用于排程与"是不是卡住了"的判断。 */
    val expectedMs: Long = 300L,
    /** 硬超时（毫秒）。超过就判失败，不无限等。 */
    val timeoutMs: Long = 15_000L,
    /** 什么时候该用它。一句话，**进提示词**，所以必须短。 */
    val whenToUse: String,
    /** 失败时给用户/模型的下一步。不能只写"失败了"。 */
    val failureHint: String,
    /** 谁提供的。见 [ToolSource]。 */
    val source: ToolSource = ToolSource.BUILTIN,
) {
    init {
        require(ID_PATTERN.matches(id)) {
            "工具名不合法：$id（只允许小写字母、数字、下划线，3–48 字符，且以字母开头）"
        }
        require(title.isNotBlank() && title.length <= 24) {
            "工具 $id 的标题必须是 1–24 字的短名，实际：$title"
        }
        require(effects.isNotEmpty()) { "工具 $id 没有声明任何效果（effects 为空）" }
        require(!(ToolEffect.READ_ONLY in effects && effects.size > 1)) {
            "工具 $id 同时声明了 READ_ONLY 与其它效果 —— 只读的工具不该顺带改东西"
        }
        require(expectedMs in 1..MAX_EXPECTED_MS) {
            "工具 $id 的 expectedMs 必须在 1..$MAX_EXPECTED_MS，实际：$expectedMs"
        }
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) {
            "工具 $id 的 timeoutMs 必须在 $MIN_TIMEOUT_MS..$MAX_TIMEOUT_MS，实际：$timeoutMs"
        }
        require(expectedMs <= timeoutMs) {
            "工具 $id 的预期耗时（$expectedMs）比超时（$timeoutMs）还长 —— 那它永远在超时前跑不完"
        }
        require(whenToUse.length in 4..MAX_WHEN_LENGTH) {
            "工具 $id 的 whenToUse 必须是 4–$MAX_WHEN_LENGTH 字，实际 ${whenToUse.length} 字"
        }
        require(failureHint.length in 4..MAX_HINT_LENGTH) {
            "工具 $id 的 failureHint 必须是 4–$MAX_HINT_LENGTH 字，实际 ${failureHint.length} 字"
        }
        // 这些文案会出现在提示词与界面里；markdown 粗体在本项目里一律不落地
        for ((field, value) in listOf("title" to title, "whenToUse" to whenToUse, "failureHint" to failureHint)) {
            require(!value.contains("**")) { "工具 $id 的 $field 里出现了 markdown 粗体，改用直角引号" }
        }
    }

    /** 只读：看一眼、不改任何东西。 */
    val readOnly: Boolean get() = effects == setOf(ToolEffect.READ_ONLY)

    /** 会改变用户看得见的状态（界面 / 系统 / 数据）。 */
    val mutating: Boolean get() = !readOnly

    /**
     * 要不要用户先点一下。
     *
     * 只拦**有后果的**不可逆动作：改系统、把内容发出去、写数据。
     *
     * 点屏幕本身不拦。理由不是"点屏幕不可怕"，而是"每一步都要确认的 agent 等于没有自主度"：
     * 用户会先去设置里把确认关掉，那时候真正危险的动作也没人看了。
     * 闸门只有稀缺才有人认真读 —— 这条和 `AutonomyPolicy` 里"MONEY/IDENTITY 之外不设硬边界"
     * 是同一个取舍。
     */
    val requiresConfirmation: Boolean
        get() = mutating && !reversible && effects.any {
            it == ToolEffect.WRITES_SYSTEM || it == ToolEffect.SENDS_TEXT || it == ToolEffect.WRITES_DATA
        }

    /** 给用户看的一句"这一步的代价"。 */
    fun cost(): String = when {
        needsScreen -> "会占住你的屏幕"
        mutating && !reversible -> "做了不容易撤回"
        mutating -> "会改动东西"
        else -> "只看不动"
    }

    companion object {
        private val ID_PATTERN = Regex("""^[a-z][a-z0-9_]{2,47}$""")
        const val MIN_TIMEOUT_MS = 100L
        const val MAX_TIMEOUT_MS = 600_000L
        const val MAX_EXPECTED_MS = 120_000L
        const val MAX_WHEN_LENGTH = 60
        const val MAX_HINT_LENGTH = 120
    }
}

/**
 * 能力走哪条通道。
 *
 * 名称与 `:rootlogic` 的 `ExecPath` 不一一对应，因为**同一个工具**在不同档位下
 * 会走不同通道（`android_tap` 普通档走无障碍、root 档走 `input tap`）。这里写的是
 * "最常见的那条"，逐档的真值由 `TierRouter` 决定。
 */
enum class ToolChannel(val label: String) {
    /** 无障碍：`dispatchGesture` / `rootInActiveWindow`。普通档主力。 */
    A11Y("无障碍"),

    /** `su -c`：一次性拿 root 跑一条命令。80–300ms。 */
    ROOT_SU("root（su）"),

    /** 常驻通道：`app_process` 起的守护进程，一次往返 <5ms。root 档满血路径。 */
    ROOT_DAEMON("root 常驻通道"),

    /** 文件选择器 / 授权目录。不需要任何特权。 */
    SAF("授权目录"),

    /** 本机回环（虚拟屏托管、dsh 侧调用）。 */
    LOOPBACK("本机回环"),

    /** 进程内纯逻辑：人格、上下文、记忆。不碰系统。 */
    IN_PROCESS("进程内"),
}

/** 这一步会改变什么。 */
enum class ToolEffect {
    /** 只读。 */
    READ_ONLY,

    /** 会改变某块屏幕上的内容（包括用户看不见的虚拟屏）。 */
    MOVES_SCREEN,

    /** 会改系统状态：设置项、属性、包的启用状态。 */
    WRITES_SYSTEM,

    /** 会改文件/数据：写入、覆盖、删除。 */
    WRITES_DATA,

    /** 会把内容发出去：消息、邮件、HTTP。 */
    SENDS_TEXT,
}

/** 工具由谁提供。 */
enum class ToolSource(val label: String) {
    /** 应用内建。 */
    BUILTIN("内建"),

    /** 插件贡献。命名约定 `p_<插件名>_<工具名>`。 */
    PLUGIN("插件"),

    /** 只在 root 档可用（能力层有、普通档没有对应动作）。 */
    ROOT_ONLY("仅 root"),
}
