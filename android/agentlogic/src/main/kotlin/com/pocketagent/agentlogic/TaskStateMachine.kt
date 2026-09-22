package com.pocketagent.agentlogic

/**
 * 动作类型 —— **归一化后的运行时枚举**。
 *
 * ## 为什么这里有一份，而 `:action` 里也有一份
 *
 * `:action` 的 `ActionType`（15 个值）是**执行通道**的视图：它关心
 * "这个动作该派给哪个 Executor"。本模块的枚举是**决策与安全**的视图：
 * 它关心"这个动作会不会改变屏幕""失败后能不能重试""要不要用户确认"。
 *
 * 两者的共同点是名字，不同点是**附加语义**。合并的后果是
 * `:action` 被拖出纯 Kotlin（它带 `ElementRef` → `Rect` → Android），
 * 而本模块必须保持零 Android 依赖才能进离线验证器。
 *
 * ⚠️ **转换由 Android 层的一个 `when` 完成，且必须是穷尽式**（Kotlin 的
 * 枚举 `when` 不写 `else` 时缺分支会编译失败）—— 这样"新增了动作类型
 * 但忘了映射"会在编译期暴露，而不是运行期静默走 else。
 *
 * ## 显式携带 `changesScreen` 是本文件最重要的设计
 *
 * 卡死检测的判据是"同一动作重复 2 次且屏幕无变化"。若不区分动作类型，
 * `WAIT` / `SCROLL` 到底 这类**本就应该无变化**的动作会被判成卡死，
 * 表现为"任务在明明正常的时候突然中止"。
 *
 * 规划文档 §2.3.4 已点明这个陷阱。把它做成枚举的**属性**而不是
 * 散落在判据里的 `if (type == WAIT || type == SCROLL)` ——
 * 后者每新增一个"不改屏"的动作就要去改判据，忘改就是静默误判。
 */
enum class StepAction(
    /** 执行后是否**预期**屏幕发生变化。false 的动作不参与卡死判定 */
    val changesScreen: Boolean,
    /** 是否需要定位元素（决定至少第 1 档感知） */
    val needsElement: Boolean,
    /** 是否是纯导航（不需要了解屏幕内容 → 第 0 档） */
    val isPureNavigation: Boolean = false,
    /** 失败后重试是否有意义（比如"点一下没反应"重试有意义） */
    val retryable: Boolean = true,
) {
    CLICK(changesScreen = true, needsElement = true),
    TAP_COORD(changesScreen = true, needsElement = false),
    LONG_PRESS(changesScreen = true, needsElement = true),
    INPUT_TEXT(changesScreen = true, needsElement = true),
    CLEAR_TEXT(changesScreen = true, needsElement = true),
    /** 滚动会变屏，但"滚到底"这一个案例本就不变 —— 由 [isTerminalScroll] 另行处理 */
    SCROLL(changesScreen = true, needsElement = false),

    /**
     * 滑动 —— 与滚动同构，但**跨屏拖拽**（如拖动进度条）会变屏。
     * 这里保守地标为变屏；若将来发现拖拽到底的情形误判，再细化。
     */
    SWIPE(changesScreen = true, needsElement = false),

    // ── 以下四个是"本就应该不变屏"的，卡死检测必须排除 ──────────
    BACK(changesScreen = true, needsElement = false, isPureNavigation = true),
    HOME(changesScreen = true, needsElement = false, isPureNavigation = true),
    RECENTS(changesScreen = true, needsElement = false, isPureNavigation = true),
    OPEN_APP(changesScreen = true, needsElement = false, isPureNavigation = true),

    /**
     * ★ 等待 —— 本质就是"什么都不做"，不变屏是正常的。
     *
     * ⚠️ `retryable = false` 是**必须显式写的**：不写它会取默认值 `true`，
     * 而"等待失败了再等一次"没有任何意义 —— 它不会产生新的信息，
     * 只会白烧一轮预算。
     *
     * 这正是本项目反复出现的那类问题：**默认值恰好是错的那一边**，
     * 而漏写参数不会报错。
     */
    WAIT(changesScreen = false, needsElement = false, retryable = false),

    /** 询问用户 —— 用户没回应前屏幕当然不变 */
    ASK_USER(changesScreen = false, needsElement = false, retryable = false),

    /** 任务完成 —— 不是真正的动作 */
    FINISH(changesScreen = false, needsElement = false, retryable = false),

    /** 中止 —— 同上 */
    ABORT(changesScreen = false, needsElement = false, retryable = false),
    ;

    /**
     * 是否是"滚到底/拖到头"这类可能自然到达终点的动作。
     *
     * SCROLL 与 SWIPE 在到达边界时**后续重复不会产生变化**，这是正常的。
     * 卡死检测对它们用**更高的阈值**（见 [TaskStateMachine.terminalScrollTolerance]），
     * 而不是完全排除 —— 完全排除的话"在原地反复滚"这种真卡死就抓不到了。
     */
    val isTerminalScroll: Boolean
        get() = this == SCROLL || this == SWIPE

    /**
     * 从字符串解析 —— **容错解析，且失败可诊断**。
     *
     * ## 为什么需要它（D-AS 的落地）
     *
     * 规划文档 §2.1 第 ③ 条：`PlanStep.actionType` 原本是 `String`，
     * 而模型输出 `"CLICK "`（带空格）或 `"Tap"` 时，
     * **字符串版本不会报错，会在派发时静默走进 else 分支**。
     *
     * 改成枚举后，解析这一步就成了唯一的可能出错的接缝 ——
     * 所以它必须**显式返回失败**，而不是回退到一个默认值。
     *
     * ⚠️ **绝不能有"解析不出来就当 CLICK"的兜底** ——
     * 那等于把静默走错分支从"派发时"提前到了"解析时"，
     * 问题一点没解决，还更难查。
     */
    companion object {
        /** 别名表：模型常见的口语化输出 → 正式枚举 */
        private val ALIASES: Map<String, StepAction> = mapOf(
            "TAP" to CLICK,
            "PRESS" to CLICK,
            "CLICK_ELEMENT" to CLICK,
            "TYPE" to INPUT_TEXT,
            "INPUT" to INPUT_TEXT,
            "TEXT" to INPUT_TEXT,
            "ENTER_TEXT" to INPUT_TEXT,
            "FILL" to INPUT_TEXT,
            "LONGCLICK" to LONG_PRESS,
            "LONG_CLICK" to LONG_PRESS,
            "PRESS_LONG" to LONG_PRESS,
            "CLEAR" to CLEAR_TEXT,
            "SCROLL_UP" to SCROLL,
            "SCROLL_DOWN" to SCROLL,
            "SWIPE_UP" to SWIPE,
            "SWIPE_DOWN" to SWIPE,
            "GO_BACK" to BACK,
            "LAUNCH_APP" to OPEN_APP,
            "OPEN" to OPEN_APP,
            "START_APP" to OPEN_APP,
            "SLEEP" to WAIT,
            "DELAY" to WAIT,
            "WAIT_FOR" to WAIT,
            "ASK" to ASK_USER,
            "ASK_USER_FOR_HELP" to ASK_USER,
            "DONE" to FINISH,
            "COMPLETE" to FINISH,
            "STOP" to ABORT,
            "CANCEL" to ABORT,
            "SET_COORDINATE" to TAP_COORD,
            "TAP_AT" to TAP_COORD,
        )

        /**
         * 解析模型输出的动作名。
         *
         * 容错范围（都是模型**真的会**输出的形态）：
         * - 前后空白：`"CLICK "`
         * - 大小写：`"click"` / `"Click"`
         * - 连字符 / 空格：`"long-press"` / `"long press"`
         * - **CamelCase：`"LaunchApp"` / `"inputText"`**
         * - 常见别名：`"Tap"` / `"Open"`
         *
         * ## ★ CamelCase 拆分是必须的（实测踩过）
         *
         * 最初的实现只做了 `uppercase()` + 替换 `-` 与空格，
         * 于是 `"LaunchApp"` → `"LAUNCHAPP"`，而别名表的键是 `"LAUNCH_APP"`
         * —— **永远匹配不上**，`parseOrNull` 返回 null，然后整条规划被判失败。
         *
         * 讽刺的是：这个 bug 本身就属于本函数要解决的那一类
         * ——"不报错、只是安静地走不到正确的分支"。
         * 只不过这次它出现在"修它的代码"自己身上。
         *
         * @return 解析成功返回枚举；失败返回 null —— **调用方必须显式处理 null**
         *   （转成"规划失败→手动引导"，而不是猜一个默认动作）
         */
        fun parseOrNull(raw: String): StepAction? {
            val normalized = normalize(raw)
            if (normalized.isEmpty()) return null

            // 先精确匹配正式名
            entries.firstOrNull { it.name == normalized }?.let { return it }
            // 再查别名
            return ALIASES[normalized]
        }

        /**
         * 把各种书写形态归一化成 `SNAKE_CASE`。
         *
         * 顺序有讲究：**先拆 CamelCase，再统一大写**。
         * 反过来的话 `"LaunchApp"` 已经被压成 `"LAUNCHAPP"`，
         * 大写字母之间的边界就找不回来了。
         *
         * 用正则一次完成拆词、换分隔符、统一大小写，
         * 避免"多次 replace 互相干扰"（比如先替换 `-` 成 `_`，
         * 再拆 CamelCase 时把刚生成的 `_` 又当成词边界处理）。
         */
        fun normalize(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            // ① 分隔符统一成空格：连字符、下划线、连续空白
            val spaced = trimmed.replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ")
            // ② 在"小写/数字 → 大写"以及"连续大写 → 大写+小写"处插入空格，
            //    覆盖 LaunchApp / launchApp / HTTPServer 三种形态
            val split = spaced
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            // ③ 最后统一大写并用下划线连接
            return split.trim().uppercase().replace(' ', '_')
        }
    }
}

/**
 * 任务状态机 —— **防止长任务失控**。
 *
 * ## 它防的四种失控
 *
 * | 失控 | 判据 | 为什么必须在这里 |
 * |---|---|---|
 * | 步数失控 | 超过 `maxSteps` | 模型可能规划出 50 步的任务 |
 * | 重规划失控 | 重规划次数超限 | ★ 重规划**累加**步数（D-AR），不重置 |
 * | 原地卡死 | 同动作重复且屏幕无变化 | 不检测会一直点到超时 |
 * | 敏感页面 | 命中黑名单 | **不可配置绕过** |
 *
 * ## ★ D-AR 的落地：重规划后步数**累加，不重置**
 *
 * 规划文档建议"累加"，理由很硬：
 * 若重规划重置 `stepIndex`，则 `maxSteps` 形同虚设 ——
 * 每次失败重规划都从 0 开始计，于是"失败 3 次 × 各跑 20 步 = 60 步"。
 * 而移动端最怕的就是轮次失控（能耗差三个数量级）。
 *
 * ## 卡死检测的排除规则（规划文档 §2.3.4 的陷阱）
 *
 * `changesScreen = false` 的动作（`WAIT` / `ASK_USER` 等）**完全不参与**卡死判定。
 * `SCROLL` / `SWIPE` 这类"可能自然到达终点"的动作用**更高容忍度**。
 */
class TaskStateMachine(
    private val maxSteps: Int,
    private val maxReplans: Int,
    /** 普通动作"无变化"连续多少次判卡死。默认 2（规划文档 §2.3.4） */
    private val freezeThreshold: Int = DEFAULT_FREEZE_THRESHOLD,
    /**
     * 滚动类动作的容忍度。
     *
     * ★ 必须**大于** [freezeThreshold]：滚到页面底部后继续滚是真的没有变化，
     * 那不是卡死。但又不能完全排除 —— "在原地反复滚"是真卡死。
     */
    private val terminalScrollTolerance: Int = DEFAULT_TERMINAL_SCROLL_TOLERANCE,
) {

    init {
        require(maxSteps > 0) { "maxSteps 必须为正数，当前为 $maxSteps" }
        require(maxReplans >= 0) { "maxReplans 不能为负，当前为 $maxReplans" }
        require(freezeThreshold > 1) {
            "freezeThreshold 必须 > 1（为 1 会把任何一次正常的短暂无变化判成卡死），当前为 $freezeThreshold"
        }
        require(terminalScrollTolerance > freezeThreshold) {
            "terminalScrollTolerance 必须大于 freezeThreshold（滚动类动作本就更可能无变化），" +
                "当前为 $terminalScrollTolerance vs $freezeThreshold"
        }
    }

    /** 每个任务的状态。用 taskId 索引以支持多任务共存 */
    private val states = mutableMapOf<String, MachineState>()

    /**
     * 启动一个任务。
     *
     * ⚠️ 同一个 taskId 重复启动会**重置**状态 —— 这是刻意的：
     * 重试一个任务时应当从干净状态开始，而不是继承上一次的失控计数。
     */
    fun start(taskId: String) {
        states[taskId] = MachineState()
    }

    /** 当前状态 */
    fun currentState(taskId: String): AgentTaskState =
        states[taskId]?.toPublicState() ?: AgentTaskState.Idle

    /**
     * 是否可以继续。
     *
     * 这是**唯一**的继续判定入口 —— 循环每一步之前都要调它。
     * 分散成多个 `if` 会让"漏掉某一种中止条件"成为可能。
     */
    fun canContinue(taskId: String): ContinueDecision {
        val s = states[taskId] ?: return ContinueDecision.Continue

        if (s.finished) {
            return ContinueDecision.Stop("任务已结束：${s.finishReason ?: "未知原因"}")
        }
        if (s.stepIndex >= maxSteps) {
            return ContinueDecision.Stop("步数已达上限 $maxSteps")
        }
        if (s.replanCount > maxReplans) {
            return ContinueDecision.Stop("重规划次数已达上限 $maxReplans（累计步数 ${s.stepIndex}）")
        }
        if (s.waitingUser != null) {
            return ContinueDecision.Stop("正在等待用户：${s.waitingUser}")
        }
        return ContinueDecision.Continue
    }

    /**
     * 记录一步的开始。
     *
     * @param action 本步的动作类型 —— **决定它是否参与卡死判定**
     */
    fun onStepStarted(taskId: String, action: StepAction) {
        val s = states.getOrPut(taskId) { MachineState() }
        s.lastAction = action
        s.stepIndex += 1
    }

    /**
     * 记录一步的执行结果。
     *
     * @param screenChanged 执行前后屏幕是否有可观测变化
     */
    fun onStepFinished(taskId: String, screenChanged: Boolean) {
        val s = states[taskId] ?: return
        val action = s.lastAction ?: return

        // ★ 排除规则：不变屏是这类动作的**正常状态**，不参与卡死计数
        if (!action.changesScreen) {
            s.unchangedStreak = 0
            return
        }

        if (screenChanged) {
            s.unchangedStreak = 0
            return
        }

        s.unchangedStreak += 1

        // 记录是否在重复同一个动作（卡死检测的第二个条件）
        if (s.previousAction == action) {
            s.sameActionStreak += 1
        } else {
            s.sameActionStreak = 1
            s.previousAction = action
        }

        val limit = if (action.isTerminalScroll) terminalScrollTolerance else freezeThreshold
        if (s.unchangedStreak >= limit && s.sameActionStreak >= limit) {
            s.frozen = true
            s.finishReason = buildString {
                append("检测到卡死：")
                append(action.name).append(" 连续 ").append(s.sameActionStreak).append(" 次")
                append("且屏幕无变化（阈值 ").append(limit).append("）")
            }
        }
    }

    /**
     * 记录一次重规划。
     *
     * ★ **不重置 stepIndex**（D-AR）—— 见类注释。
     */
    fun onReplan(taskId: String) {
        val s = states.getOrPut(taskId) { MachineState() }
        s.replanCount += 1
        // ⚠️ 刻意不动 s.stepIndex —— 这就是 D-AR 的全部内容，
        //    也是"maxSteps 到底有没有意义"的分水岭。
        //    重规划后同样要清空卡死计数，否则"换了个思路但恰好还是同一种动作"
        //    会继承旧计数而被误判。
        s.unchangedStreak = 0
        s.sameActionStreak = 0
        s.previousAction = null
    }

    /** 进入等待用户状态 */
    fun awaitUser(taskId: String, reason: String) {
        states.getOrPut(taskId) { MachineState() }.waitingUser = reason
    }

    /** 用户已回应，恢复 */
    fun resumeFromUser(taskId: String) {
        states[taskId]?.waitingUser = null
    }

    /** 标记任务结束 */
    fun finish(taskId: String, success: Boolean, reason: String) {
        val s = states.getOrPut(taskId) { MachineState() }
        s.finished = true
        s.success = success
        s.finishReason = reason
    }

    /** 清掉某个任务的状态（任务彻底结束、确认不再需要恢复时调用） */
    fun forget(taskId: String) {
        states.remove(taskId)
    }

    /** 当前跟踪的任务数（诊断用） */
    val trackedTasks: Int get() = states.size

    // ── 内部可变状态 ──────────────────────────────────────────

    private class MachineState {
        var stepIndex: Int = 0
        var replanCount: Int = 0
        var lastAction: StepAction? = null
        var previousAction: StepAction? = null
        var unchangedStreak: Int = 0
        var sameActionStreak: Int = 0
        var frozen: Boolean = false
        var waitingUser: String? = null
        var finished: Boolean = false
        var success: Boolean = false
        var finishReason: String? = null

        fun toPublicState(): AgentTaskState = when {
            finished -> AgentTaskState.Finished(success, finishReason ?: "未知")
            frozen -> AgentTaskState.Finished(false, finishReason ?: "检测到卡死")
            waitingUser != null -> AgentTaskState.WaitingUser(waitingUser!!)
            stepIndex == 0 -> AgentTaskState.Idle
            else -> AgentTaskState.Running(stepIndex, replanCount)
        }
    }

    companion object {
        /** 规划文档 §2.3.4：同一动作重复 2 次且屏幕无变化 → 卡死 */
        const val DEFAULT_FREEZE_THRESHOLD = 2

        /**
         * 滚动类动作的容忍度。
         *
         * 取 3 而不是 2：滚到底需要"滚 2 次都没变化"才可能是真卡住，
         * 而第 1 次无变化极可能只是刚好到底了。
         */
        const val DEFAULT_TERMINAL_SCROLL_TOLERANCE = 3
    }
}

/**
 * 任务状态 —— 对外只读视图。
 *
 * ⚠️ `Idle` 刻意**不带 taskId**：状态是"某个任务的状态"，
 * 调用方已经知道自己在问哪个任务，再返回一次 taskId 只会产生
 * "两个 id 不一致"这种无意义的错误可能。
 */
sealed interface AgentTaskState {

    data object Idle : AgentTaskState

    data class Running(val stepIndex: Int, val replanCount: Int) : AgentTaskState

    data class WaitingUser(val reason: String) : AgentTaskState

    data class Finished(val success: Boolean, val reason: String) : AgentTaskState
}

/**
 * 继续/中止判定。
 *
 * `Stop` 带 reason 而不是只有一个布尔 —— UI 必须能说明**为什么停了**，
 * 否则用户看到任务无声停止，会以为程序坏了。
 */
sealed interface ContinueDecision {
    data object Continue : ContinueDecision
    data class Stop(val reason: String) : ContinueDecision
}
