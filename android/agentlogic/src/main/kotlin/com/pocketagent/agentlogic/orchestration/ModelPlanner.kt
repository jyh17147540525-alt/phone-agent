package com.pocketagent.agentlogic.orchestration

import com.pocketagent.agentlogic.AgentLlmPort
import com.pocketagent.agentlogic.AgentMessage
import com.pocketagent.agentlogic.AgentTurnReply
import com.pocketagent.agentlogic.AgentTurnRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * 让模型出计划 —— [PlannerPort] 的真实实现。
 *
 * ## 为什么计划要模型出，而不是写规则
 *
 * "把昨天那个会议纪要整理成表格发我"这种句子，规则表写不出来。但**验收不能交给模型**：
 * 模型说"做完了"，执行器只认屏幕摘要。这条分工是整条链路的关键 ——
 * 模型负责"想"，[RunOrchestrator] 负责"查"。
 *
 * ## 提示词为什么这么短
 *
 * 一次计划只问这一件事：给 JSON。工具清单里每条只留「什么时候用」一句话，
 * 参数形状交给 `:mcp` 的 `inputSchema`。原因不是省 token，而是
 * **指令越多、遵循率越低**：把 24 条要求堆进一次请求，实测遵循率会从九成掉到两成，
 * 而掉的那部分通常是"只输出 JSON"这类格式要求 —— 也就是最容易让我解析失败的那条。
 *
 * 所以这里只写五条，且每条都能被判真假：只用给定工具名、最多 N 步、每步给验收、
 * 做不到就说 refuse、缺信息就问 need。
 *
 * ## 解析失败怎么处理
 *
 * 不猜。模型输出不是 JSON 时返回 [PlanResult.NeedInfo] —— 让用户决定要不要再试一次，
 * 而不是替它"猜一个大概的计划"然后去点用户的手机。
 */
class ModelPlanner(
    private val llm: AgentLlmPort,
    /** 一次计划最多几步。默认 8：再多说明目标本身没拆清楚。 */
    private val maxSteps: Int = 8,
) : PlannerPort {

    override suspend fun plan(request: PlanRequest): PlanResult {
        val prompt = buildPrompt(request)
        val reply = runCatching {
            llm.complete(AgentTurnRequest(messages = listOf(AgentMessage.User(prompt)), tools = emptyList()))
        }.getOrElse { error ->
            return PlanResult.NeedInfo("这一轮没连上模型（${error.javaClass.simpleName}），要我稍后再试一次吗？")
        }

        val text = when (reply) {
            is AgentTurnReply.Final -> reply.text
            is AgentTurnReply.Calls -> reply.text.orEmpty()
        }

        val json = extractJsonObject(text)
            ?: return PlanResult.NeedInfo("模型这轮没给出能执行的计划，要我换个说法再试一次吗？")

        val raw = runCatching { JSON.decodeFromString<RawPlan>(json) }.getOrNull()
            ?: return PlanResult.NeedInfo("模型这轮的输出格式不对，要我换个说法再试一次吗？")

        raw.refuse?.takeIf { it.isNotBlank() }?.let { return PlanResult.Refuse(it.trim()) }
        raw.need?.takeIf { it.isNotBlank() }?.let { return PlanResult.NeedInfo(it.trim()) }

        val rawSteps = raw.plan.orEmpty()
        if (rawSteps.isEmpty()) {
            return PlanResult.NeedInfo("我还没想清楚第一步做什么，你再说细一点？")
        }
        if (rawSteps.size > maxSteps) {
            return PlanResult.Refuse("这件事可以拆成更多步，但一次只做 ${maxSteps} 步以内更稳；先给我最要紧的那一段。")
        }

        val steps = mutableListOf<PlannedStep>()
        for ((index, item) in rawSteps.withIndex()) {
            val spec = request.catalog.byId(item.tool)
                ?: return PlanResult.Refuse("计划里用了不存在的工具「${item.tool}」，这条计划本身是错的，我不执行。")
            steps += PlannedStep(
                id = "step_${index + 1}",
                tool = spec.id,
                args = item.args.mapValues { (_, value) -> value.asText() },
                expect = item.expect?.toExpectation() ?: Expectation.None,
                note = item.note.ifBlank { spec.title },
            )
        }
        return PlanResult.Ok(RunPlan(goal = request.goal, steps = steps))
    }

    /** 拼请求。短、可判定、只问这一件事。 */
    internal fun buildPrompt(request: PlanRequest): String = buildString {
        appendLine("你是手机操作的计划器。只输出一个 JSON 对象，不要解释，不要输出代码块标记。")
        appendLine()
        appendLine("目标：${request.goal}")
        appendLine()
        appendLine("可用工具（只能用这些名字）：")
        append(request.catalog.describeForPrompt())
        appendLine()
        appendLine()
        appendLine("约束：")
        appendLine("- 最多 ${maxSteps} 步，每步一个工具。")
        appendLine("- 每步都要给 expect（做完看什么），只有纯记录类步骤可以省略。")
        appendLine("- args 的键用工具参数表里的名字，值写成字符串。")
        appendLine("- 做不到就输出 {\"refuse\":\"原因\"}；缺信息输出 {\"need\":\"要问用户什么\"}。")
        if (request.budgetLeft in 1..maxSteps) {
            appendLine("- 这一趟只剩 ${request.budgetLeft} 步的预算。")
        }
        request.previousFailure?.takeIf { it.isNotBlank() }?.let {
            appendLine("- 上一次没成：$it 。换一条不同的路，不要重复同样的步骤。")
        }
        appendLine()
        appendLine("JSON 形状：")
        appendLine("{\"plan\":[{\"tool\":\"android_tap\",\"args\":{\"text\":\"发送\"},\"expect\":{\"text\":\"已发送\"},\"note\":\"点发送\"}]}")
        appendLine("expect 可用字段：text / packageName / atLeastNodes / key 加 value（四选一，或省略）。")
    }

    /**
     * 从模型输出里抠出 JSON 对象。
     *
     * 不要求它"一定干净"：真实模型经常在 JSON 前后加一句解释，或者裹一层代码块。
     * 但也不做"猜"：没有成对的大括号就当解析失败。
     */
    internal fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    // ── 线上形状（只在解析时用；不是公开 API）────────────────────────────────

    @Serializable
    private data class RawPlan(
        val plan: List<RawStep>? = null,
        val refuse: String? = null,
        val need: String? = null,
    )

    @Serializable
    private data class RawStep(
        val tool: String,
        val args: Map<String, JsonElement> = emptyMap(),
        val expect: RawExpect? = null,
        val note: String = "",
    )

    @Serializable
    private data class RawExpect(
        val text: String? = null,
        val packageName: String? = null,
        val atLeastNodes: Int? = null,
        val key: String? = null,
        val value: String? = null,
    ) {
        fun toExpectation(): Expectation = when {
            text != null -> Expectation.TextVisible(text)
            packageName != null -> Expectation.PackageIs(packageName)
            atLeastNodes != null -> Expectation.AtLeastNodes(atLeastNodes)
            key != null && value != null -> Expectation.ValueIs(key, value)
            else -> Expectation.None
        }
    }

    private fun JsonElement.asText(): String = when (this) {
        is JsonPrimitive -> content
        else -> toString()
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}
