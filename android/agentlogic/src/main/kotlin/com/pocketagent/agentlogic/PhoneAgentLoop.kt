package com.pocketagent.agentlogic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 手机操作的 **agent 循环** —— 感知 → 决策 → 执行 → 观察 → 再决策。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 它与 [OfficeAgentLoop] 是**两条线**，不要合并
 * ═══════════════════════════════════════════════════════════════
 *
 * | | [OfficeAgentLoop] | 本类 |
 * |---|---|---|
 * | 干什么 | 读**文件**、写**文件** | 读**屏幕**、点**控件** |
 * | 工具协议 | 让模型输出 JSON 文本，再解析 | **原生 function calling**（`tools` / `tool_calls`） |
 * | 代价 | 解析失败可重试 | **点错了就是点错了，撤不回来** |
 * | 依赖 | 零 Android | 零 Android |
 *
 * 两者共用同一份 [BudgetGuard] 与同一套保护思想，但**契约不同**：
 * 办公那条线要"模型写错路径也能改"，手机这条线要"**宁可不动，也不要动错**"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么用原生 function calling，而不是让模型输出 JSON 文本
 * ═══════════════════════════════════════════════════════════════
 *
 * [OfficeAgentLoop] 走的是"模型输出一段 JSON，我们解析"——那条路对文件操作够用
 * （解析失败大不了重试，最坏情况是"这次没读成"）。
 *
 * 手机操作**不能这样**：解析出错的后果是**点到一个我们没打算点的地方**。
 * 而 `tools` / `tool_calls` 是协议层保证的结构化调用：工具名与参数由
 * provider 侧组装，不存在"模型把 JSON 写歪了"这一类失败。
 *
 * ⇒ 我们**不解析、不猜测**。参数是 JSON 字符串就直接交给执行方去校验，
 *   本层不替它做「大概是想点这个吧」的推断。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 五条保护，缺一条都可能跑飞
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **预算守卫**（[BudgetGuard]，四维）。移动端 agent 的瓶颈不是算力是**轮次**。
 * 2. **步数上限**（[PhoneTask.maxSteps]）。预算说"资源允许多少"，步数说
 *    "这件事本身允许多长"。查一次屏幕就够的任务不该跑满预算。
 * 3. **重复调用检测**。模型卡住时的典型表现是**发出完全相同的调用** ——
 *    它以为上次没成功。没有这条，循环会转到预算耗尽，而用户看到的是
 *    "助理转了 8 轮什么也没做"。
 * 4. **安全拒绝不是反馈，是终止方向**。见下。
 * 5. **同一批调用里的重复也要拦**。模型有时在一轮里把同一个调用发两遍。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 第 4 条是本类与办公循环**最重要**的差别
 * ═══════════════════════════════════════════════════════════════
 *
 * [OfficeAgentLoop] 的立场是"拒绝是反馈，不是终止"——因为文件工具的拒绝
 * （路径穿越、产出目录不对）**模型自己能改**。
 *
 * 但手机工具里的 [AgentToolResult.Blocked] 是**安全策略的最终结论**
 * （支付页、密码框、频率闸）。把它当反馈喂回去的后果是：
 * 模型看到"被拒绝了"，于是**换个说法再试一次** ——
 * 改个坐标、改个描述，同一件被禁止的事会反复撞击安全边界。
 *
 * ⇒ 三条铁律：
 *  1. [AgentToolResult.Blocked] 回给模型时**必须带"不要重试"**；
 *  2. 同一工具被 Blocked 两次 ⇒ **立即中止整个任务**（不是继续转）；
 *  3. [AgentToolResult.Failed]（技术故障）才可以重试 —— 它与 Blocked 是两回事。
 *
 * 第 2 条尤其重要：它把"用户的钱和注意力"从"模型的自我纠正能力"里赎回来。
 */
class PhoneAgentLoop(
    private val llm: AgentLlmPort,
    private val runner: AgentToolRunner,
    private val budgetGuard: BudgetGuard,
    /** 注入时钟 —— 便于离线测试"时长"这一维预算而不真的等 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 跑一个任务。
     *
     * ⚠️ **本方法不抛异常**。内部失败一律转成 [PhoneRunResult] 的一个分支 ——
     *    调用链上没人能处理异常，抛出去会把"任务没做成"这个**可解释的业务状态**
     *    变成崩溃（同 [OfficeAgentLoop] 的既定纪律）。
     */
    suspend fun run(task: PhoneTask): PhoneRunResult {
        val started = clock()
        val trace = mutableListOf<String>()
        var usage = BudgetUsage()
        var history: List<AgentMessage> = listOf(AgentMessage.System(task.systemPrompt)) +
            task.priorMessages +
            listOf(AgentMessage.User(task.userInput))

        var lastCallKey: String? = null
        var identicalRepeats = 0
        // 每个工具被"安全拒绝"过几次 —— 见类注释铁律 2
        val blockedCount = mutableMapOf<String, Int>()

        while (true) {
            // ── 保护 1：预算（四维任一超限即中止）────────────────
            val usageWithTime = usage.at(elapsedMs = clock() - started, energyMicroAh = usage.energyMicroAh)
            when (val verdict = budgetGuard.check(usageWithTime)) {
                is BudgetVerdict.Exceeded -> return PhoneRunResult.Stopped(
                    PauseReason.BUDGET,
                    "预算超限（${verdict.dimension}）：${verdict.detail}",
                    trace,
                )
                // Warn / NotMeasurable / Ok 都继续 —— 警戒线不是中止线
                else -> Unit
            }

            // ── 保护 2：步数 ──────────────────────────────────────
            if (usage.turns >= task.maxSteps) {
                return PhoneRunResult.StepLimitReached(usage.turns, trace)
            }

            // ── 调模型 ───────────────────────────────────────────
            val reply = try {
                llm.complete(AgentTurnRequest(messages = history, tools = task.tools))
            } catch (e: Exception) {
                return PhoneRunResult.Stopped(
                    PauseReason.MODEL_ERROR,
                    "模型调用失败：${e.message ?: e::class.simpleName}",
                    trace,
                )
            }
            usage = usage.nextTurn()

            when (reply) {
                // ── 模型给出最终答复 ⇒ 任务结束 ───────────────────
                is AgentTurnReply.Final -> {
                    // ⚠️ 空答复也算"结束"，但**必须标注**。
                    //    不标注的表现是用户看到一个空卡片，以为界面坏了。
                    val text = reply.text.ifBlank { "（模型没有给出答复内容）" }
                    return PhoneRunResult.Answered(text, usage.turns, trace)
                }

                is AgentTurnReply.Calls -> {
                    val calls = reply.calls
                    // 模型既说了话又调了工具：把话记进 trace，别丢
                    reply.text?.takeIf { it.isNotBlank() }?.let { trace += it }

                    if (calls.isEmpty()) {
                        // 既没有 calls 也没有 final —— 协议上不该发生。
                        // 当成"看不懂"反馈回去，而不是当成完成（后者会让任务静默失败）。
                        history = history + AgentMessage.Assistant("") + AgentMessage.ToolResult(
                            text = "你这一轮没有返回任何工具调用，也没有给出答复。请二选一：调用工具，或直接回答。",
                            callId = "",
                            name = "",
                        )
                        continue
                    }

                    // ── 保护 5：同一批里的重复 ────────────────────
                    val keys = calls.map { callKey(it) }
                    if (keys.size != keys.toSet().size) {
                        return PhoneRunResult.Stopped(
                            PauseReason.LOOP_DETECTED,
                            "模型在一轮里发出了重复的工具调用（${calls.size} 个里有重复），判定为异常输出",
                            trace,
                        )
                    }

                    // ── 保护 3：跨轮次的重复 ──────────────────────
                    val batchKey = keys.sorted().joinToString("&")
                    if (batchKey == lastCallKey) {
                        identicalRepeats++
                        if (identicalRepeats >= MAX_IDENTICAL_REPEATS) {
                            return PhoneRunResult.Stopped(
                                PauseReason.LOOP_DETECTED,
                                "连续 ${identicalRepeats + 1} 次发出完全相同的调用，判定为卡死",
                                trace,
                            )
                        }
                    } else {
                        identicalRepeats = 0
                        lastCallKey = batchKey
                    }

                    // ── 逐个执行 ──────────────────────────────────
                    val results = mutableListOf<AgentMessage>()
                    for (call in calls) {
                        val outcome = try {
                            runner.run(call)
                        } catch (e: Exception) {
                            // 执行方抛异常 = 接线问题，但对循环而言等价于"这次没做成"。
                            // 不能让它把整个任务炸掉。
                            AgentToolResult.Failed("执行器异常：${e.message ?: e::class.simpleName}")
                        }

                        when (outcome) {
                            is AgentToolResult.Ok -> {
                                trace += "${call.name} → ${outcome.text.oneLine()}"
                                results += AgentMessage.ToolResult(
                                    text = outcome.text,
                                    callId = call.id,
                                    name = call.name,
                                )
                            }

                            is AgentToolResult.Failed -> {
                                // ★ 技术故障**可以**重试 —— 这与 Blocked 是两回事。
                                //   "无障碍没开"用户能修，"支付页不许点"模型改不了。
                                val text = "执行失败：${outcome.reason}。可以换个方式重试一次。"
                                trace += "${call.name} 失败：${outcome.reason.oneLine()}"
                                results += AgentMessage.ToolResult(
                                    text = text,
                                    callId = call.id,
                                    name = call.name,
                                )
                            }

                            is AgentToolResult.Blocked -> {
                                // ★★ 铁律 1：回给模型时必须带"不要重试"
                                val text = "这一步被安全策略拦下，**这是最终结论，不要重试、不要换个说法再试**。" +
                                    "原因：${outcome.reason}。" +
                                    "请把这个情况如实告诉用户，并问他接下来想怎么办。"
                                trace += "${call.name} 被拦下：${outcome.reason.oneLine()}"
                                results += AgentMessage.ToolResult(
                                    text = text,
                                    callId = call.id,
                                    name = call.name,
                                )

                                // ★★ 铁律 2：同一工具被拦两次 ⇒ 立即中止
                                val n = (blockedCount[call.name] ?: 0) + 1
                                blockedCount[call.name] = n
                                if (n >= MAX_BLOCKS_PER_TOOL) {
                                    return PhoneRunResult.Stopped(
                                        PauseReason.SAFETY_BLOCKED,
                                        "${call.name} 已被安全策略拦下 $n 次，已停止本次任务。" +
                                            "原因：${outcome.reason}",
                                        trace,
                                    )
                                }
                            }
                        }
                    }

                    // 把模型这一轮的调用与我们的结果一起追加进历史
                    history = history +
                        AgentMessage.Assistant(reply.text.orEmpty(), calls) +
                        results
                }
            }
        }
    }

    /** 用于重复检测的键：工具 + **排序后**的参数。参数顺序不同不算重复。 */
    private fun callKey(call: AgentToolCall): String =
        call.name + "|" + canonicalArgs(call.argumentsJson)

    /**
     * 把参数 JSON 归一化成"可比较"的形式。
     *
     * ⚠️ 不能直接拿原串比：`{"a":1,"b":2}` 与 `{"b":2,"a":1}` 是同一个调用，
     *    直接比字符串会把它们当成两次不同的调用 —— 于是重复检测**失效**，
     *    而失效的表现是"卡死检测没触发，任务转到预算耗尽"。
     *
     * ⚠️ 解析失败时**退回原串**（而不是返回空）：空字符串会让所有畸形参数
     *    看起来一模一样，于是两个不同的坏调用被误判成"重复"，任务被提前中止。
     */
    private fun canonicalArgs(raw: String): String {
        val parsed = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return raw
        return parsed.entries
            .sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v.toString()}" }
    }

    companion object {
        /**
         * 连续多少次**完全相同**的调用判定为卡死。
         *
         * 取 3：第 1 次是正常尝试，第 2 次可能是模型没看到反馈，
         * 第 3 次基本可以确定它在原地打转。
         */
        const val MAX_IDENTICAL_REPEATS: Int = 3

        /**
         * 同一个工具被**安全拒绝**几次就中止整个任务。
         *
         * 取 2，比 [MAX_IDENTICAL_REPEATS] 更严 —— 理由见类注释铁律 2：
         * 安全拒绝不是"没做成"，是"不许做"。允许它试第二次已经是宽容，
         * 再往后就是在**帮用户撞安全墙**。
         */
        const val MAX_BLOCKS_PER_TOOL: Int = 2
    }
}

// ═══════════════════════════════════════════════════════════════
//  端口与数据（全是纯形状，由 `:app` 做适配）
// ═══════════════════════════════════════════════════════════════

/**
 * 循环需要的模型端口。
 *
 * ⚠️ **只依赖接口，不依赖任何厂商实现**（与 `OfficeLlmPort` 同一条纪律）。
 *    `:app` 用它去适配 `LlmProvider`。
 */
fun interface AgentLlmPort {
    /**
     * 一轮补全。
     *
     * @throws Exception 网络/协议错误。**循环会接住并转成
     *   [PhoneRunResult.Stopped]，实现方可以放心抛**。
     */
    suspend fun complete(request: AgentTurnRequest): AgentTurnReply
}

/** 发给模型的一轮请求。 */
data class AgentTurnRequest(
    val messages: List<AgentMessage>,
    val tools: List<AgentToolSpec>,
)

/**
 * 一个工具的**声明**。
 *
 * ⚠️ [parametersJsonSchema] 是**字符串**而不是结构化对象：
 *    工具 schema 由 `:mcp` 侧生成（`McpTool.inputSchema()`），本模块
 *    原样透传即可 —— 一旦在这里解析，两个模块就有了同一个 schema 的两份理解，
 *    而漂移的方向是"模型看到的参数与工具真正接受的参数不一致"。
 */
data class AgentToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

/**
 * 一条历史消息。
 *
 * ★ 形状刻意与 `provider.api.ChatMessage` 对齐但**不共用类型**：
 *   `:agentlogic` 必须保持零 Android 依赖与零模块依赖才能进离线验证器。
 *   适配（一次 `when`）在 `:app`，那是唯一知道两边的地方。
 */
sealed interface AgentMessage {

    val text: String

    data class System(override val text: String) : AgentMessage

    data class User(override val text: String) : AgentMessage

    /** 模型的发言。[calls] 非空时表示它这一轮要调工具。 */
    data class Assistant(
        override val text: String,
        val calls: List<AgentToolCall> = emptyList(),
    ) : AgentMessage

    /** 工具返回。⚠️ [callId] 必须与 [Assistant.calls] 里的 id 对上，否则协议方会报错。 */
    data class ToolResult(
        override val text: String,
        val callId: String,
        val name: String,
    ) : AgentMessage
}

/** 模型要调的一次工具。 */
data class AgentToolCall(
    val id: String,
    val name: String,
    /** 原始 JSON 字符串 —— **本层不解析**（见 [AgentToolSpec] 的注释）。 */
    val argumentsJson: String,
)

/** 模型一轮输出的归宿。两态 —— 有调用，或已终结。 */
sealed interface AgentTurnReply {

    /** 要调工具（可能同时带一段话）。 */
    data class Calls(val calls: List<AgentToolCall>, val text: String? = null) : AgentTurnReply

    /** 任务结束，这是给用户的答复。 */
    data class Final(val text: String) : AgentTurnReply
}

/** 工具执行端口 —— 由 `:app` 实现（真正碰无障碍 / Shizuku）。 */
fun interface AgentToolRunner {
    /** ⚠️ 实现方**不要抛异常**作为常规控制流；循环会兜住，但那条路上没有面向用户的文案。 */
    suspend fun run(call: AgentToolCall): AgentToolResult
}

/**
 * 一次工具执行的结果。**三分支必须分开** —— 见 [PhoneAgentLoop] 类注释铁律 2。
 */
sealed interface AgentToolResult {

    /** 成功。[text] 会**原样进模型上下文**，所以要含关键信息（看到了什么、点到了什么）。 */
    data class Ok(val text: String) : AgentToolResult

    /**
     * 技术故障（无障碍没开、读屏超时、目标找不到）。
     *
     * ★ 这类**可以重试** —— 用户能修，模型也可能换个定位方式。
     */
    data class Failed(val reason: String) : AgentToolResult

    /**
     * 被安全策略拦下。**这是最终结论，不是"这次没成功"。**
     *
     * ★ 与 [Failed] 分开是刻意的：合并的后果是模型把"不许做"理解成"没做成"，
     *   于是换个坐标再试一次 —— 而那就是在反复撞安全墙。
     */
    data class Blocked(val reason: String) : AgentToolResult
}

/** 一个手机任务。 */
data class PhoneTask(
    val id: String,
    val userInput: String,
    val systemPrompt: String,
    val tools: List<AgentToolSpec>,
    /**
     * 这一句**之前**的对话历史。
     *
     * ⚠️ 必须有：聊天框里用户会连着说好几轮（"打开微信" → "给妈妈发消息"），
     *    第二轮里的"妈妈"依赖第一轮的上下文。只传 [userInput] 会让每轮
     *    都从零开始 —— 表现是"助理突然不记得刚才说过什么"。
     *
     * ★ 不含系统提示词（它单独传），也不含本轮输入。
     */
    val priorMessages: List<AgentMessage> = emptyList(),
    /**
     * 最大步数。
     *
     * ⚠️ 与预算的轮次是**两回事**（同 `OfficeTask.maxSteps` 的注释）：
     *    预算说"资源允许多少"，步数说"这件事本身允许多长"。
     */
    val maxSteps: Int = DEFAULT_MAX_STEPS,
) {
    companion object {
        /**
         * 默认 12 步。
         *
         * 比办公的 12 步**刻意不同**：手机操作一步 = 一次真实的点击，
         * 代价不可逆。宁可让用户说"接着来"，也不要让它自己多点几下。
         */
        const val DEFAULT_MAX_STEPS: Int = 12
    }
}

/** 循环为什么停下。 */
enum class PauseReason {
    /** 预算超限（轮次 / 时长 / 能耗 / 流量）。 */
    BUDGET,

    /** 模型调用失败。 */
    MODEL_ERROR,

    /** 判定卡死（重复调用）。 */
    LOOP_DETECTED,

    /** 安全策略反复拦下同一个工具。★ 这一条要**显著区别于**上面三条。 */
    SAFETY_BLOCKED,
}

/**
 * 循环的结局。**四个分支都带 [trace]** —— 用户要能看到"它到底做了什么"。
 */
sealed interface PhoneRunResult {

    /** 模型给出了最终答复。 */
    data class Answered(val text: String, val steps: Int, val trace: List<String>) : PhoneRunResult

    /** 步数用完。**这不是失败** —— 它只是没做完，用户可以要求继续。 */
    data class StepLimitReached(val steps: Int, val trace: List<String>) : PhoneRunResult

    /**
     * 被保护机制中止。
     *
     * ⚠️ [reason] 必须能**直接展示给用户**：[SAFETY_BLOCKED] 时界面要说清
     * "是安全边界拦下的"而不是"出错了" —— 两者的下一步完全不同
     * （前者请用户自己来，后者可以重试）。
     */
    data class Stopped(
        val pause: PauseReason,
        val reason: String,
        val trace: List<String>,
    ) : PhoneRunResult
}

/** 把一段可能含换行的文本压成一行 —— trace 是给人看的列表，不是日志。 */
private fun String.oneLine(): String = replace('\n', ' ').replace('\r', ' ').take(160)
