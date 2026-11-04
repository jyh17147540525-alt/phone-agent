package com.pocketagent.rootlogic

/**
 * **按档位装配工具清单** —— 把 [TierRouter] 的判定变成模型真正看到的那套工具。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"少挂几个工具"不够，还要给出理由
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具从清单里消失时，模型不会认为"这条路被封了"，它会**换个姿势试**：
 * 拿不到 `root_exec`，它就去 `android_tap` 一通乱点，或者直接编一个
 * 不存在的参数。所以被挡住的工具必须以 [Blocked] 的形式**留在清单里**，
 * 带一句"为什么拿不到、要怎么才能拿到"。
 *
 * 这和 `CapabilityMatrix` 对用户做的事是同一件：看不见的边界会被当成 bug，
 * 说清楚的边界才是设计。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 占屏是**计划级**属性，不是工具级
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户关心的是"这一趟跑起来我能不能用手机"，而不是"tap 这条占不占屏"。
 * 所以 [TierPlan.requiresScreen] 是整份计划的结论：
 * 只要有一条要前台的活在里，整趟就得提前打招呼。
 */
data class ToolBinding(
    val tool: String,
    val capabilityId: String,
)

object ToolBindings {

    /**
     * 设备工具 → 能力 id。
     *
     * ⚠️ 这里只收**真正动到手机**的工具。`persona_brief` / `context_offload`
     *    这类纯逻辑工具不绑能力（见 [DefaultFreeTools]）—— 把它们也算进来，
     *    会让"普通模式缺什么"这张表被无意义的条目淹没。
     */
    val all: List<ToolBinding> = listOf(
        ToolBinding("screen_read", "screen.read"),
        ToolBinding("screen_shot", "screen.read"),
        ToolBinding("android_tap", "screen.tap"),
        ToolBinding("android_swipe", "screen.tap"),
        ToolBinding("android_type", "screen.tap"),
        ToolBinding("android_key", "screen.tap"),
        // ★ 批量动作挂在 screen.tap 上：它做的就是点按/滑动/输入/导航键，
        //   一档的资源占用与单步完全一样。绑上来之后"普通档缺无障碍"时
        //   它也会被网关拦下并给出同一句引导 —— 否则它会先读屏失败，
        //   报一句"读屏不可用"，用户看不出该去开什么。
        ToolBinding("android_batch", "screen.tap"),
        ToolBinding("root_exec", "shell.root"),
        // ── 2026-11-04：系统级四件事 ────────────────────────────────
        // ★ 它们只有 root 一条路（普通档没有对应实现），绑上去之后
        //   运行模式页才能把"这几条要 root"如实显示出来；
        //   不绑的话它们在页面上显示成"没有工具入口"——那是另一件事。
        ToolBinding("file_read", "file.any"),
        ToolBinding("setting_write", "settings.write"),
        ToolBinding("prop_write", "prop.set"),
        ToolBinding("app_disable", "pm.disable"),
    )

    fun capabilityOf(tool: String): String? = all.firstOrNull { it.tool == tool }?.capabilityId

    fun toolsOf(capabilityId: String): List<String> = all.filter { it.capabilityId == capabilityId }.map { it.tool }
}

/**
 * 永远可用的工具：不碰设备，与档位无关。
 *
 * ★ 显式列出来而不是"其它都算自由"：新加一个碰设备的工具时，
 *   忘了在这里排除会被立刻发现（它会既不在 mounted 也不在 blocked）。
 */
object DefaultFreeTools {
    val names: Set<String> = setOf(
        "app_host",
        "persona_brief",
        "context_offload",
        "context_recall",
        "office_tools",
        "office_run",
    )
}

/** 已挂上的一条。 */
data class Mounted(
    val tool: String,
    val capabilityId: String,
    val path: ExecPath,
    val occupancy: Occupancy,
    val degraded: Boolean,
    val note: String,
)

/** 被挡住的一条 —— 带原因与出路。 */
data class Blocked(
    val tool: String,
    val capabilityId: String,
    val verdict: Verdict,
) {
    /** 给模型看的一句话。 */
    fun explain(): String = when (val v = verdict) {
        is Verdict.NeedPermission -> "$tool 现在用不了：缺「${v.permission.label}」。${v.permission.guide}"
        is Verdict.NeedRoot -> "$tool 用不了：${v.why}"
        Verdict.NotImplemented -> "$tool 还没实现（不是权限问题）"
        is Verdict.Ready -> "$tool 其实可用（不该出现在被挡清单里）"
    }
}

/** 一份装配结果。 */
data class TierPlan(
    val mode: PrivilegeMode,
    val mounted: List<Mounted>,
    val blocked: List<Blocked>,
    val free: List<String>,
) {
    /** 这一趟整体要不要占住屏幕。 */
    val requiresScreen: Boolean get() = mounted.any { it.occupancy == Occupancy.FOREGROUND }

    /** 退到更低一档跑的那些工具 —— 界面要单独提醒。 */
    val degraded: List<Mounted> get() = mounted.filter { it.degraded }

    /** 模型可用的全部工具名（含永远可用的那批）。 */
    val toolNames: List<String> get() = (free + mounted.map { it.tool }).sorted()

    /** 给用户的一句话（界面顶部那条提示）。 */
    fun screenNotice(): String = when {
        mounted.isEmpty() -> "这一趟不需要动你的屏幕。"
        requiresScreen -> "接下来的任务会占住屏幕 —— 期间部分操作会被本应用接管。"
        else -> "任务在后台跑，你可以继续用手机。"
    }
}

object TierPlanner {

    /**
     * 按当前档位与设备状态装出这一趟的工具清单。
     *
     * @param allowFallback root 不可用时是否退到普通档（默认退 —— 任务优先）
     */
    fun plan(
        mode: PrivilegeMode,
        state: DeviceState,
        free: Set<String> = DefaultFreeTools.names,
        allowFallback: Boolean = true,
    ): TierPlan {
        val mounted = mutableListOf<Mounted>()
        val blocked = mutableListOf<Blocked>()

        for (binding in ToolBindings.all) {
            when (val v = TierRouter.resolve(binding.capabilityId, mode, state, allowFallback)) {
                is Verdict.Ready -> mounted += Mounted(
                    tool = binding.tool,
                    capabilityId = binding.capabilityId,
                    path = v.path,
                    occupancy = v.occupancy,
                    degraded = v.degraded,
                    note = v.note,
                )
                else -> blocked += Blocked(binding.tool, binding.capabilityId, v)
            }
        }
        return TierPlan(mode, mounted, blocked, free.sorted())
    }

    /** 只取可用的工具名（喂给 MCP `tools/list`）。 */
    fun mountable(mode: PrivilegeMode, state: DeviceState, allowFallback: Boolean = true): List<String> =
        plan(mode, state, allowFallback = allowFallback).toolNames

    /**
     * 给 **MCP 网关**用的一句话：`null` = 可用，非 null = 不可用的原因。
     *
     * ★ 这就是 `:mcp` 的 `ToolGate { TierPlanner.blockReason(it, mode, state) }`
     *   那一行胶水背后该做的事。放在这里而不是 app 层，是因为它是**纯逻辑**：
     *   放 app 层就进不了离线验证器，而"缺权限时说什么"恰恰是最该被钉住的文案。
     */
    fun blockReason(
        tool: String,
        mode: PrivilegeMode,
        state: DeviceState,
        allowFallback: Boolean = true,
    ): String? {
        val verdict = resolveTool(tool, mode, state, allowFallback)
        if (verdict is Verdict.Ready) return null
        val cap = ToolBindings.capabilityOf(tool) ?: return null
        return Blocked(tool, cap, verdict).explain()
    }

    /**
     * 把一条**具体**工具判一次。
     *
     * ★ 与 [plan] 分开是因为调用点不同：装配时看全局，单次调用前看这一条
     *   （用户可能在任务跑到一半时关掉了无障碍）。
     */
    fun resolveTool(
        tool: String,
        mode: PrivilegeMode,
        state: DeviceState,
        allowFallback: Boolean = true,
    ): Verdict {
        val cap = ToolBindings.capabilityOf(tool)
            ?: return if (tool in DefaultFreeTools.names) Verdict.Ready(ExecPath.INTENT, Occupancy.NONE)
            else Verdict.NotImplemented
        return TierRouter.resolve(cap, mode, state, allowFallback)
    }
}
