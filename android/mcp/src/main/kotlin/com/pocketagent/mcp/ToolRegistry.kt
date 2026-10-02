package com.pocketagent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 一次工具调用的结局。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三个分支必须分开，尤其 [Refused] 与 [Failed]
 * ═══════════════════════════════════════════════════════════════
 *
 * 形态上它们都进 `isError: true` 的结果，但**语义完全不同**：
 *
 * | 分支 | 是什么 | 模型该做什么 |
 * |---|---|---|
 * | [Refused] | 按安全设计**主动不做**（敏感页面、隐私策略） | 停止该方向，把原因转达用户。**不要重试** |
 * | [Failed] | 能力不可用 / 执行失败（无障碍没开、读取超时） | 可以如实转达，用户有明确的下一步 |
 *
 * 合并成一个 `Error(text)` 的后果：协议层与将来的审计日志都分不清
 * "一次被正确拦截的攻击"和"一次普通的技术故障" —— 而这两者的排查方向、
 * 用户安抚话术、以及是否要告警，全部相反。
 */
sealed interface ToolOutcome {

    /** 成功。`text` 进模型上下文；`structured` 透传给程序化调用方（可空）。 */
    data class Success(
        val text: String,
        val structured: JsonObject? = null,
    ) : ToolOutcome

    /** 被安全策略拒绝。`reason` 必须能**直接展示给用户**。 */
    data class Refused(val reason: String) : ToolOutcome

    /** 能力不可用或执行失败。`reason` 必须给出"用户下一步做什么"。 */
    data class Failed(val reason: String) : ToolOutcome
}

/**
 * 一个 MCP 工具。
 *
 * ⚠️ [call] 是 `suspend` 的：真实工具要读屏、要等异步回调（无障碍 API 全在主线程回调）。
 *    分派器会给它套超时，实现方**不必**自己处理超时。
 */
interface McpTool {

    /** 工具名（发给客户端的原始名）。只允许 `[A-Za-z0-9_-]`，短且稳定。 */
    val name: String

    /** 给模型看的说明。写清楚"什么时候用、返回什么、什么情况下会被拒绝"。 */
    val description: String

    /** 输入 JSON Schema（MCP 的 `inputSchema` 字段原样返回给客户端）。 */
    fun inputSchema(): JsonObject

    /**
     * 执行。
     *
     * ⚠️ 实现方**不要抛异常**作为常规控制流 —— 失败请返回 [ToolOutcome.Failed]。
     *    抛出的异常会被分派器兜底成 `-32603`，那条路径上**没有任何面向用户的文案**。
     */
    suspend fun call(arguments: JsonObject): ToolOutcome
}

/**
 * 工具注册表。
 *
 * 本期只有一个 `screen_read`，但表本身按"会有第二个"设计 ——
 * P3 的执行端工具（tap / swipe / launch_app）会直接往里加，
 * 不需要动服务器与分派器。
 */
class ToolRegistry(tools: List<McpTool>) {

    init {
        // ⚠️ 重名在"注册表"这种结构里必须是**加载期错误**，不能是"后者覆盖前者"或
        //    "随插入顺序二选一" —— 后者会让 `tools/call` 落到不可预测的那个实现上，
        //    而且是静默的（两个实现都能返回"成功"）。
        val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "工具名重复注册：$duplicates" }
    }

    /** `LinkedHashMap` 保插入顺序 —— 工具列表的顺序会影响客户端提示词的前缀稳定性。 */
    private val byName: Map<String, McpTool> = tools.associateBy { it.name }

    /** `tools/list` 的结果体。 */
    fun listResult(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            for (tool in byName.values) {
                addJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema())
                }
            }
        }
    }

    /** 按名取工具；null = 未知工具（分派器回 `-32602`）。 */
    fun find(name: String): McpTool? = byName[name]
}
