package com.pocketagent.agentlogic.orchestration

/**
 * 「这一次它能动到什么程度」—— 工作台输入框旁边那个**权限**条。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 命名照搬电脑端 harness，语义也照搬
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的原话："在工作区，模型选择的旁边加上工作区选择，还有权限选择，
 * 这两个照搬电脑上的 harness 命名就好了"。
 *
 * 电脑上那三个档位是 `read-only` / `workspace-write` / `danger-full-access`
 * —— 名字本身就在说"允许你改多少东西"，所以这里逐字对应：
 *
 * | 电脑上 | 这里 | 一句话 |
 * |---|---|---|
 * | read-only | [READ_ONLY] 只读 | 能看能查 |
 * | workspace-write | [WORKSPACE_WRITE] 工作区可写 | 在工作区里能建能改 |
 * | danger-full-access | [FULL_ACCESS] 完全访问 | 系统设置、发消息都允许 |
 *
 * ⚠️ 显示用 [label]（短，进 chip），解释用 [blurb]（一行，进抽屉）。
 *    两个字段都在枚举里而不是散在界面文件里 —— 界面只负责排版，
 *    文案改动不会漏掉其中一个页面。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 判定按**效果**，不按工具名
 * ═══════════════════════════════════════════════════════════════
 *
 * [allows] 的输入是 [ToolSpec.effects]，不是 `spec.id`。
 * 按名字判是手工白名单：新加一个工具，白名单没同步，它就会被
 * **静默**归到某一档 —— 而"静默归类"在这里等于"用户以为自己只开了只读"。
 * 效果是工具**自己声明的**（[ToolSpec] 的构造里就有 `require`），
 * 所以新工具自动获得正确档位，不需要有人记得来改这个文件。
 *
 * ⚠️ 手机上的取舍：`MOVES_SCREEN`（点屏幕）算进 [WORKSPACE_WRITE]。
 *    理由：屏幕是用户自己的那块地方，点错了能退回去；
 *    而 [ToolEffect.SENDS_TEXT]（把内容发出去）与
 *    [ToolEffect.WRITES_SYSTEM]（改系统设置/装应用）**不可撤销**，
 *    才需要 [FULL_ACCESS]。这与电脑上"改工作区里的文件是安全的、
 *    动系统才危险"是同一个判据，不是另起一套。
 */
enum class AgentPermission(
    /** 输入框旁边那颗 chip 上的字。必须短 —— 它和一串 chip 并排。 */
    val label: String,
    /** 抽屉里那一行解释。说清"允许什么、不允许什么"。 */
    val blurb: String,
) {
    /** 只读 —— 只能看和查。 */
    READ_ONLY("只读", "能看、能查，但不改任何东西"),

    /** 工作区可写 —— 读 + 点屏幕 + 在工作区里建/改文件。 */
    WORKSPACE_WRITE("工作区可写", "能在工作区里建文件和改文件，也能点屏幕"),

    /** 完全访问 —— 还有改系统设置、把内容发出去。 */
    FULL_ACCESS("完全访问", "系统设置、发消息、装应用都允许"),

    ;

    /**
     * 这一档允不允许一个**带这些效果**的工具。
     *
     * ⚠️ 空 `effects` 一律放行 —— [ToolSpec] 的构造已经 `require` 过非空，
     *    走到这里还是空只可能是"没登记的工具"，而调用方（
     *    `AppContainer.toolsForPermission`）对没登记的**直接不给**，
     *    不会把空集合送进来。这里返回 true 是为了让纯函数语义完整。
     */
    fun allows(effects: Set<ToolEffect>): Boolean = when (this) {
        FULL_ACCESS -> true
        READ_ONLY -> effects.all { it == ToolEffect.READ_ONLY }
        WORKSPACE_WRITE -> effects.all {
            it == ToolEffect.READ_ONLY ||
                it == ToolEffect.MOVES_SCREEN ||
                it == ToolEffect.WRITES_DATA
        }
    }

    companion object {
        /** 默认档。见 [of] 的注释。 */
        val DEFAULT: AgentPermission = FULL_ACCESS

        /**
         * 从存档里读回来。
         *
         * ⚠️ 不用 `valueOf`：枚举改名 / 存档被改坏时它会抛
         *    `IllegalArgumentException`，而这条路在**启动**上 ——
         *    抛在这里等于黑屏。读不出来就退回 [DEFAULT]，
         *    与 `ChatMode.of` 同一条纪律。
         */
        fun of(raw: String?): AgentPermission =
            entries.firstOrNull { it.name == raw } ?: DEFAULT

        /**
         * 进提示词的那一段。
         *
         * ⚠️ 位置在**工作纪律之后**：先立"怎么办事"，再说"能动多少"。
         *    反过来的话模型会把权限当成任务描述的一部分。
         *
         * ⚠️ [permission] 为 null ⇒ 返回空串，**什么都不加** ——
         *    聊天页（角色扮演）不该看见"权限"这种系统话。
         *
         * @param workspaceName 当前工作区的名字。空 = 还没选。
         */
        fun promptSection(permission: AgentPermission?, workspaceName: String? = null): String {
            val p = permission ?: return ""
            val head = when (p) {
                READ_ONLY ->
                    "只读。你能看、能查、能点开，但**不改任何东西**。\n" +
                        "要动手的事，先说清楚需要他做什么，等他来做。"
                WORKSPACE_WRITE ->
                    "工作区可写。读、查、点屏幕、在工作区里建文件和改文件都行。\n" +
                        "工作区以外的文件、系统设置、把内容发出去 —— 这些不行，遇到就说出来。"
                FULL_ACCESS ->
                    "没有额外限制。但「能不能」和「该不该」是两件事：\n" +
                        "不可撤销的动作（发消息、花钱、删东西、改系统设置）先说一句再动手。"
            }
            val ws = workspaceName?.trim().orEmpty()
            val wsLine = if (ws.isEmpty()) {
                "还没有选工作区 —— 要落文件时先问他把东西放哪。"
            } else {
                "工作区是他授权的「$ws」，产出放这里，不要往别处写。"
            }
            return "## 这一次的权限\n$head\n$wsLine"
        }
    }
}
