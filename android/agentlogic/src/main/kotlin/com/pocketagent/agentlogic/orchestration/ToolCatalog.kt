package com.pocketagent.agentlogic.orchestration

/**
 * 手脚本目录 —— 「这台手机上，它到底能做什么、每件事的代价是什么」的唯一答案。
 *
 * ## 为什么需要一份目录，而不是直接遍历 MCP 工具表
 *
 * 直接遍历工具表能得到**名字和描述**，得不到三样东西：
 *
 * 1. **覆盖是否完整**。工具表里有一条、目录里漏一条 → 规划器看不见它，
 *    于是模型会绕远路（用 root 命令去做 `file_read` 本来就能做的事）。
 *    反向的漏更糟：目录里有、工具里没有 → 规划出一个永远失败的计划。
 *    两个方向由 [coverage] 双向对账。
 * 2. **占屏与可逆**。见 [ToolSpec] 的说明。
 * 3. **排序**。规划器要按"代价从小到大"试着做，而不是让模型自由发挥 ——
 *    自由发挥的典型结果是"为了读一行设置，先把应用停用了"。
 *
 * ## 这是**唯一的真值来源**吗
 *
 * 不是。真值是运行时：`TierRouter` 决定这一步走哪条路、`ToolRegistry` 决定这个工具
 * 现在能不能用。本目录回答的是**静态事实**（这条路代价多大），
 * 两者冲突时以运行时为准，并且 [screenDrift] 会把冲突找出来 ——
 * 静态说"不占屏"、运行时却占屏，这种不一致必须在测试里炸，而不是在用户手上炸。
 *
 * ## 插件怎么进来
 *
 * 插件工具按 `p_<插件名>_<工具名>` 命名（[ToolSource.PLUGIN]），实现
 * `:mcp` 的 `McpTool` 后注册进同一条流水线。目录这边只要求一条：
 * **插件必须声明自己的 [ToolSpec]**，不允许"默认按只读处理" ——
 * 默认值选错的那个方向，用户会丢数据。
 */
class ToolCatalog(specs: List<ToolSpec> = BuiltinTools.all) {

    val all: List<ToolSpec> = specs.toList()

    private val index: Map<String, ToolSpec>

    init {
        val duplicated = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "手脚本目录里有重复的工具名：${duplicated.joinToString("、")}"
        }
        index = all.associateBy { it.id }
    }

    fun byId(id: String): ToolSpec? = index[id]

    fun ofSource(source: ToolSource): List<ToolSpec> = all.filter { it.source == source }

    /** 会占用用户屏幕的工具。普通档离线时，这一组就是"任务期间用户不能用手机"的范围。 */
    fun requiringScreen(): List<ToolSpec> = all.filter { it.needsScreen }

    /** 会改动东西的工具。 */
    fun mutating(): List<ToolSpec> = all.filter { it.mutating }

    /**
     * 做了不容易撤回的工具。
     *
     * ⚠️ 这个集合应当是**短的**。它一旦变长，说明目录把"懒得写撤销路径"当成了
     * "不可逆"，于是所有动作都要问用户 —— 那等于没有自主度。
     */
    fun irreversible(): List<ToolSpec> = all.filter { it.mutating && !it.reversible }

    /**
     * 规划顺序：先看、再改、最后才碰不可逆的。
     *
     * 同组内按预期耗时升序 —— 短动作先做，失败得更早，代价更小。
     */
    fun planningOrder(): List<ToolSpec> = all.sortedWith(
        compareBy<ToolSpec> { if (it.readOnly) 0 else if (it.reversible) 1 else 2 }
            .thenBy { it.expectedMs }
            .thenBy { it.id },
    )

    /**
     * 与真实工具表对账。
     *
     * - `unregistered`：工具表里有、目录里没有 —— 规划器看不见它（会绕远路）
     * - `phantom`：目录里有、工具表里没有 —— 规划得出一个注定失败的计划
     */
    fun coverage(toolNames: Collection<String>): ToolCoverage {
        val names = toolNames.toSet()
        val catalogIds = index.keys
        return ToolCoverage(
            registered = catalogIds.intersect(names).sorted(),
            unregistered = (names - catalogIds).sorted(),
            phantom = (catalogIds - names).sorted(),
        )
    }

    /**
     * 与运行时路由对账：`occupies(id)` 返回"这一步在真机上会不会占用户屏幕"。
     *
     * 只比较真的会碰屏幕的通道（无障碍 / root）。纯进程内、授权目录、回环这几条
     * 没有"占屏"这回事，拿它们去比只会制造噪声。
     */
    fun screenDrift(occupies: (String) -> Boolean): List<String> = all
        .filter { it.channel in SCREEN_CHANNELS }
        .filter { it.needsScreen != occupies(it.id) }
        .map { "${it.id}（目录说${if (it.needsScreen) "占屏" else "不占屏"}）" }

    /**
     * 进提示词的那一段。
     *
     * 只写"什么时候用"，不写参数表 —— 参数表由 MCP 的 `inputSchema` 提供，
     * 两处都写会漂移。条数也封顶：一次列 18 条已经是预算上限，
     * 再多就开始挤掉真正重要的指令（依据：指令堆叠越多，遵循率越低）。
     */
    fun describeForPrompt(limit: Int = DEFAULT_PROMPT_LIMIT): String {
        require(limit > 0) { "limit 必须为正" }
        val picked = all.take(limit)
        val omitted = all.size - picked.size
        val body = picked.joinToString("\n") { spec ->
            val mark = when {
                spec.needsScreen -> "（会占屏）"
                spec.mutating && !spec.reversible -> "（不易撤回）"
                else -> ""
            }
            "- ${spec.id}：${spec.whenToUse}$mark"
        }
        val tail = if (omitted > 0) "\n…另有 $omitted 条没列出（用 office_tools 或工具清单查）" else ""
        val text = "可用工具（${all.size} 条）：\n$body$tail"
        return if (text.length <= MAX_PROMPT_CHARS) text else text.take(MAX_PROMPT_CHARS) + "…"
    }

    companion object {
        const val DEFAULT_PROMPT_LIMIT = 18
        const val MAX_PROMPT_CHARS = 1_500

        private val SCREEN_CHANNELS = setOf(
            ToolChannel.A11Y,
            ToolChannel.ROOT_SU,
            ToolChannel.ROOT_DAEMON,
        )
    }
}

/** 对账结果。 */
data class ToolCoverage(
    val registered: List<String>,
    val unregistered: List<String>,
    val phantom: List<String>,
) {
    val complete: Boolean get() = unregistered.isEmpty() && phantom.isEmpty()

    /** 一句话结论，失败时直接进日志/界面。 */
    fun explain(): String = when {
        complete -> "手脚本目录与工具表一致（${registered.size} 条）"
        else -> buildString {
            append("手脚本目录与工具表不一致：已登记 ${registered.size} 条")
            if (unregistered.isNotEmpty()) {
                append("；有工具没登记：").append(unregistered.joinToString("、"))
            }
            if (phantom.isNotEmpty()) {
                append("；登记了但工具不存在：").append(phantom.joinToString("、"))
            }
        }
    }
}

/**
 * 内建工具目录 —— 与 `:mcp` 里真实的 18 个 `McpTool.name` 逐字对应。
 *
 * ⚠️ 改这里之前先看 `ToolCatalogTest.coverage 与真实工具表一致`：
 * 那条测试里钉着工具名清单。新增 MCP 工具而忘了登记，测试会红 ——
 * 这是刻意留的"忘记成本"，因为漏登记的后果是规划器看不见这条能力。
 */
object BuiltinTools {

    val all: List<ToolSpec> = listOf(
        ToolSpec(
            id = "screen_read",
            title = "读屏",
            channel = ToolChannel.A11Y,
            effects = setOf(ToolEffect.READ_ONLY),
            needsScreen = true,
            idempotent = true,
            expectedMs = 400,
            timeoutMs = 15_000,
            whenToUse = "做完动作后确认界面对不对，或要看当前页面有哪些可点的东西",
            failureHint = "读不到通常是无障碍没开或被系统关掉；先确认 PocketAgent 的无障碍服务是开启状态",
        ),
        ToolSpec(
            id = "screen_shot",
            title = "截图",
            channel = ToolChannel.A11Y,
            effects = setOf(ToolEffect.READ_ONLY),
            needsScreen = true,
            idempotent = true,
            expectedMs = 500,
            timeoutMs = 20_000,
            whenToUse = "需要看颜色、图标、图片这类节点树里没有的东西时",
            failureHint = "截图失败多半是录屏授权被回收；重新点一次「开始录屏」再试",
        ),
        ToolSpec(
            id = "android_tap",
            title = "点按",
            channel = ToolChannel.A11Y,
            effects = setOf(ToolEffect.MOVES_SCREEN),
            needsScreen = true,
            reversible = false,
            batchable = true,
            expectedMs = 300,
            timeoutMs = 10_000,
            whenToUse = "目标控件有明确文字或资源 id，点一下就能推进时",
            failureHint = "点不中先 screen_read 看坐标是不是变了；别连续重点同一个位置",
        ),
        ToolSpec(
            id = "android_swipe",
            title = "滑动",
            channel = ToolChannel.A11Y,
            effects = setOf(ToolEffect.MOVES_SCREEN),
            needsScreen = true,
            batchable = true,
            expectedMs = 400,
            timeoutMs = 10_000,
            whenToUse = "列表要翻页、或要拖一个进度条与滑块时",
            failureHint = "滑完没变化就换距离或时长；连续两次无效说明这条路走不通",
        ),
        ToolSpec(
            id = "android_type",
            title = "输入文字",
            channel = ToolChannel.A11Y,
            effects = setOf(ToolEffect.MOVES_SCREEN),
            needsScreen = true,
            reversible = false,
            expectedMs = 400,
            timeoutMs = 10_000,
            whenToUse = "焦点已经在输入框里，要把文字填进去时",
            failureHint = "先 tap 一下输入框拿到焦点再输入；中文输入不进去时改走 root 通道的 input text",
        ),
        ToolSpec(
            id = "android_key",
            title = "按系统键",
            channel = ToolChannel.A11Y,
            effects = setOf(ToolEffect.MOVES_SCREEN),
            needsScreen = true,
            expectedMs = 200,
            timeoutMs = 10_000,
            whenToUse = "要返回上一页、回桌面、切最近任务时",
            failureHint = "按键没反应说明当前界面吞掉了事件；改用界面上的返回按钮",
        ),
        ToolSpec(
            id = "android_batch",
            title = "批量动作",
            channel = ToolChannel.A11Y,
            effects = setOf(ToolEffect.MOVES_SCREEN),
            needsScreen = true,
            reversible = false,
            expectedMs = 1_500,
            timeoutMs = 30_000,
            whenToUse = "一串确定性操作、中间不需要看结果时，一批发出去省掉来回",
            failureHint = "第 N 步失败会告诉你停在哪；先用 screen_read 看一眼再决定继续还是重来",
        ),
        ToolSpec(
            id = "file_read",
            title = "读文件",
            channel = ToolChannel.ROOT_DAEMON,
            effects = setOf(ToolEffect.READ_ONLY),
            idempotent = true,
            expectedMs = 60,
            timeoutMs = 10_000,
            whenToUse = "要读某个具体路径的文本内容、而且你已经知道路径时",
            failureHint = "读不到先确认路径存在；应用私有目录要 root，授权目录请走 office_run",
        ),
        ToolSpec(
            id = "setting_write",
            title = "改系统设置",
            channel = ToolChannel.ROOT_DAEMON,
            effects = setOf(ToolEffect.WRITES_SYSTEM),
            idempotent = true,
            expectedMs = 80,
            timeoutMs = 10_000,
            whenToUse = "要改 system、secure、global 里的某一项，比如亮度、动画、默认输入法",
            failureHint = "改动没生效先读回同一项确认；普通档需要先用 adb 授予修改安全设置",
        ),
        ToolSpec(
            id = "prop_write",
            title = "改系统属性",
            channel = ToolChannel.ROOT_DAEMON,
            effects = setOf(ToolEffect.WRITES_SYSTEM),
            reversible = false,
            idempotent = true,
            expectedMs = 80,
            timeoutMs = 10_000,
            whenToUse = "只有 setprop 才能解决的那几项调试开关，而且你清楚它的含义",
            failureHint = "属性写错可能导致异常重启；不确定就不要写，先用 getprop 看现值",
        ),
        ToolSpec(
            id = "app_disable",
            title = "停用应用",
            channel = ToolChannel.ROOT_DAEMON,
            effects = setOf(ToolEffect.WRITES_SYSTEM),
            idempotent = true,
            expectedMs = 300,
            timeoutMs = 15_000,
            whenToUse = "要临时关掉一个应用时，停用比卸载可撤回",
            failureHint = "恢复用 pm enable 加包名；系统框架类应用一律拒绝，这是保护不是故障",
        ),
        ToolSpec(
            id = "root_exec",
            title = "root 命令",
            channel = ToolChannel.ROOT_SU,
            effects = setOf(ToolEffect.WRITES_SYSTEM),
            reversible = false,
            expectedMs = 300,
            timeoutMs = 20_000,
            whenToUse = "前面所有工具都做不到、而你能把要做的事写成一条确定命令时",
            failureHint = "被拒绝说明命令在禁止清单里；换个写法绕不过去，这是最终结论",
        ),
        ToolSpec(
            id = "app_host",
            title = "虚拟屏托管",
            channel = ToolChannel.LOOPBACK,
            effects = setOf(ToolEffect.MOVES_SCREEN),
            expectedMs = 2_000,
            timeoutMs = 30_000,
            whenToUse = "要在用户看不见的虚拟屏里操作某个应用，微信、QQ、抖音这类",
            failureHint = "托管起不来多半是这个应用不允许被复制进程；换回真屏操作并提前告知用户",
        ),
        ToolSpec(
            id = "persona_brief",
            title = "人格摘要",
            channel = ToolChannel.IN_PROCESS,
            effects = setOf(ToolEffect.READ_ONLY),
            idempotent = true,
            expectedMs = 10,
            timeoutMs = 3_000,
            whenToUse = "要代入正确的人称、称呼、语气与当前情绪时",
            failureHint = "读不到就按默认人称说话，不要自己编造人格设定",
        ),
        ToolSpec(
            id = "context_offload",
            title = "卸载上下文",
            channel = ToolChannel.IN_PROCESS,
            effects = setOf(ToolEffect.WRITES_DATA),
            expectedMs = 30,
            timeoutMs = 5_000,
            whenToUse = "一段结果太长、后面还要用，先搬出上下文只留索引时",
            failureHint = "卸载失败就直接截断，别把整段长文塞回上下文",
        ),
        ToolSpec(
            id = "context_recall",
            title = "取回上下文",
            channel = ToolChannel.IN_PROCESS,
            effects = setOf(ToolEffect.READ_ONLY),
            idempotent = true,
            expectedMs = 30,
            timeoutMs = 5_000,
            whenToUse = "要用之前卸载掉的那段内容时，按原样的 ref 取回",
            failureHint = "取不回说明 ref 抄错了或已过期；不要自己拼 ref",
        ),
        ToolSpec(
            id = "office_tools",
            title = "文件工具清单",
            channel = ToolChannel.IN_PROCESS,
            effects = setOf(ToolEffect.READ_ONLY),
            idempotent = true,
            expectedMs = 20,
            timeoutMs = 5_000,
            whenToUse = "第一次在授权目录里做事，先看能用哪些文件操作",
            failureHint = "列不出来说明用户还没授权工作区；先带用户选一次目录",
        ),
        ToolSpec(
            id = "office_run",
            title = "文件操作",
            channel = ToolChannel.IN_PROCESS,
            effects = setOf(ToolEffect.WRITES_DATA),
            reversible = false,
            expectedMs = 500,
            timeoutMs = 60_000,
            whenToUse = "要在授权目录里列目录、读文本、写文本或生成表格时",
            failureHint = "写操作只能落在产出目录；被拒说明路径越界了，不是权限问题",
        ),
    )
}
