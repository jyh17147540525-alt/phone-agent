package com.pocketagent.agentlogic.orchestration

/**
 * 工具分组 —— 让"能力全给"和"提示词不爆"不再冲突。
 *
 * ## 为什么需要这一层
 *
 * 18 条工具的说明一次性塞进提示词，代价不是 token 数字，而是两件更贵的事：
 * 1. **挤掉真正重要的指令**。一次请求里指令越多，遵循率越低；掉的那部分往往
 *    不是"少用某个工具"，而是"只输出 JSON"这类格式要求 —— 也就是最容易让
 *    整轮解析失败的那条。
 * 2. **每轮让前缀失效**。工具说明是提示词前缀的一部分，前缀一变，缓存全废。
 *
 * ⇒ 开局只给"基础组"和每组一个**入口工具**；模型真的要用某一组时，调用入口（或
 * 凭记忆直接调组里的某个工具），这一组才展开。机制来自 Mika 的 ToolGroups 设计
 * （见 `docs/工程/Mika-技术借鉴-2026-11-05.md`），实现是本项目自己的：
 * 只开不合、展开判据只看"调了哪个工具"，不做提示词分段拼接。
 *
 * ## 一条硬纪律：只开不合
 *
 * 展开过的组在**同一个任务内永不收起**。收起会让提示词前缀反复变化（缓存失效），
 * 更会让模型在下一次调用时突然看不到刚用过的工具 —— 它会以为那个能力消失了。
 */
enum class ToolGroup(
    val label: String,
    /**
     * 这一组的入口工具：组收着时唯一露出来的那一个。
     *
     * `null` 表示这一组没有入口（基础组永远全给，不需要入口）。
     */
    val entry: String?,
) {
    /** 基础：读屏、截图、人格、上下文搬运。每轮都给。 */
    BASE("基础", null),

    /** 界面操作：点按、滑动、输入、按键、批量、虚拟屏托管。 */
    UI("界面操作", "android_tap"),

    /** 系统与文件：root 命令、读文件、改设置、改属性、停用应用。 */
    SYSTEM("系统与文件", "root_exec"),

    /** 授权目录：列工具清单、在授权目录里读写。 */
    FILES("授权目录", "office_tools"),
}

/** 工具 → 组的静态映射。未登记的工具按 [ToolGroup.BASE] 处理（永远可见，宁多给不漏给）。 */
object ToolGrouping {

    val of: Map<String, ToolGroup> = mapOf(
        // 基础
        "screen_read" to ToolGroup.BASE,
        "screen_shot" to ToolGroup.BASE,
        "persona_brief" to ToolGroup.BASE,
        "context_offload" to ToolGroup.BASE,
        "context_recall" to ToolGroup.BASE,
        // 界面
        "android_tap" to ToolGroup.UI,
        "android_swipe" to ToolGroup.UI,
        "android_type" to ToolGroup.UI,
        "android_key" to ToolGroup.UI,
        "android_batch" to ToolGroup.UI,
        "app_host" to ToolGroup.UI,
        // 系统
        "root_exec" to ToolGroup.SYSTEM,
        "file_read" to ToolGroup.SYSTEM,
        "setting_write" to ToolGroup.SYSTEM,
        "prop_write" to ToolGroup.SYSTEM,
        "app_disable" to ToolGroup.SYSTEM,
        // 授权目录
        "office_tools" to ToolGroup.FILES,
        "office_run" to ToolGroup.FILES,
    )

    fun groupOf(tool: String): ToolGroup = of[tool] ?: ToolGroup.BASE

    fun toolsIn(group: ToolGroup): Set<String> = of.filterValues { it == group }.keys

    /**
     * 目标文字里出现这些词，就**预先展开**对应的组。
     *
     * 只看"这次要做的事"，不看用户的身份或历史 —— 预展开错了的代价只是多一段提示词，
     * 而少展开一次的代价是模型去找入口、多一轮往返。
     */
    private val PRELOAD: Map<ToolGroup, List<String>> = mapOf(
        ToolGroup.FILES to listOf("文件", "表格", "文档", "目录", "导出", "保存", "xlsx", "docx", "pdf", "附件"),
        ToolGroup.SYSTEM to listOf("root", "su", "设置", "属性", "停用", "禁用", "卸载", "权限", "包名"),
    )

    fun groupsImpliedBy(goal: String): Set<ToolGroup> {
        val text = goal.lowercase()
        return PRELOAD.filterValues { words -> words.any { text.contains(it) } }.keys
    }
}

/** 本轮该给模型看的工具集合。 */
data class ToolOffer(
    val tools: List<ToolSpec>,
    val opened: Set<ToolGroup>,
) {
    val ids: List<String> get() = tools.map { it.id }
}

/**
 * 工具展开状态机。
 *
 * 用法（每轮）：`explorer.preload(goal)`（开局一次）→ `explorer.offer()` 取本轮清单 →
 * 模型调用后 `explorer.onCall(tool)` 让它展开 → 下一轮 `offer()` 自然变大。
 */
class ToolExplorer(
    private val catalog: ToolCatalog = ToolCatalog(),
) {
    private val opened = linkedSetOf<ToolGroup>()

    /** 已展开的组（只增不减）。 */
    fun opened(): Set<ToolGroup> = opened.toSet()

    /** 开局按目标预展开。重复调用无副作用。 */
    fun preload(goal: String) {
        opened += ToolGrouping.groupsImpliedBy(goal)
    }

    /**
     * 模型调了某个工具 —— 如果它属于还没展开的组，就展开那一组。
     *
     * ⚠️ 这里的判据是"调了什么"，不是"给了什么"：模型凭记忆调了一个没露出来的工具，
     * 也要照常展开，否则它下一次仍然看不到这个工具的参数表，只能一直瞎猜。
     *
     * @return 这一次新展开的组；没有新展开（或未知工具、基础组）返回 null。
     */
    fun onCall(tool: String): ToolGroup? {
        val group = ToolGrouping.groupOf(tool)
        if (group == ToolGroup.BASE) return null
        return if (opened.add(group)) group else null
    }

    /** 本轮给模型的工具：基础组全部 + 未展开组的入口 + 已展开组的全部。 */
    fun offer(): ToolOffer {
        val ordered = mutableListOf<ToolSpec>()
        ordered += catalog.all.filter { ToolGrouping.groupOf(it.id) == ToolGroup.BASE }

        for (group in ToolGroup.entries) {
            if (group == ToolGroup.BASE) continue
            if (group in opened) {
                ordered += catalog.all.filter { ToolGrouping.groupOf(it.id) == group }
            } else {
                group.entry?.let { entry -> catalog.byId(entry)?.let { ordered += it } }
            }
        }
        return ToolOffer(tools = ordered, opened = opened.toSet())
    }

    /**
     * 本轮工具说明的文本。口吻与 [ToolCatalog.describeForPrompt] 一致，
     * 但只列 [offer] 里的工具，并在末尾说清"还有哪些组收着、怎么展开"。
     */
    fun promptText(): String = buildString {
        val offer = offer()
        appendLine("可用工具（${offer.tools.size} 条）：")
        for (spec in offer.tools) {
            val mark = when {
                spec.needsScreen -> "（会占屏）"
                spec.mutating && !spec.reversible -> "（不易撤回）"
                else -> ""
            }
            append("- ").append(spec.id).append("：").append(spec.whenToUse).append(mark).appendLine()
        }
        val closed = ToolGroup.entries.filter { it != ToolGroup.BASE && it !in offer.opened }
        if (closed.isNotEmpty()) {
            append("另有 ")
            append(closed.joinToString("、") { group ->
                val count = ToolGrouping.toolsIn(group).size
                "「${group.label}」（$count 条，调 ${group.entry} 后展开）"
            })
            append(" 还收着。")
        }
    }.trimEnd()
}
