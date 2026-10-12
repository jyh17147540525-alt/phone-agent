package com.pocketagent.mcp

import com.pocketagent.agentlogic.OfficeToolExecutor
import com.pocketagent.agentlogic.OfficeToolOutcome
import com.pocketagent.filelogic.OfficeRequest
import com.pocketagent.filelogic.OfficeTool
import com.pocketagent.filelogic.OfficeToolCall
import com.pocketagent.filelogic.OfficeToolValidator
import com.pocketagent.filelogic.ToolValidation
import com.pocketagent.filelogic.WorkspacePolicy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `office_tools` / `office_run` —— 把「在用户授权的工作区里读写文件」交给 dsh。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么 dsh 需要这个（不是"为了给死代码找个消费方"）
 * ═══════════════════════════════════════════════════════════════
 *
 * dsh 跑在 Termux 的容器里，**它的 UID 与我们不同** ——
 * 用户通过 SAF 授权给本应用的那个目录，dsh 自己**够不到**。
 * 而本应用已经握着那份授权（第 0 档能力），却只能靠用户手点按钮来用。
 *
 * 于是这里补上的是**能力**：dsh 说"把 `笔记.md` 读出来"，
 * 本应用用**用户已经授权过的那个目录**去读，再把结果回给 dsh。
 * 权限没有被放大 —— 它一直在用户授的那棵树里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它同时给四块死代码接上了第一个生产消费方
 * ═══════════════════════════════════════════════════════════════
 *
 * 接之前（实测）：
 * - `OfficeToolValidator` / `OfficeToolParser` 只被 `OfficeAgentLoop` 引用，
 *   而那个循环自己零引用；
 * - `OfficeToolExecutor` 是**一个没有实现的端口**；
 * - `XlsxWriter` / `DocxWriter` / `OfficeText` 在**生产代码里零引用**
 *   （只有模块内的测试用）—— 也就是说**这个应用当时根本产不出表格**。
 *
 * ⇒ 这不是"清死代码"，是**补上一条断掉的能力链**：
 *   工具定义（`:filelogic`）→ 校验（`:filelogic`）→ 执行（`:app`）→ 出口（本文件）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 校验**必须**走 `:filelogic` 的 `OfficeToolValidator`，不许在这里另写一份
 * ═══════════════════════════════════════════════════════════════
 *
 * 路径穿越（`..` / 绝对路径）、写入必须落在产出目录、单文件大小上限 ——
 * 这三条已经在 `OfficeToolValidator` 里实现并有测试。在这里重写一遍，
 * 等于让"什么允许"有两个真相来源，而漂移的方向永远是**松的那一份生效**
 * （调用方会走到它先通过的那条路上）。这与 `:safety` 的既有裁决是同一条理由。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 被校验拒绝时用 [ToolOutcome.Failed]，**刻意不用 Refused**
 * ═══════════════════════════════════════════════════════════════
 *
 * 本项目的 `Refused` 语义是「安全策略的**最终结论**，请不要重试」（见 `screen_read`
 * 的敏感页拒绝）。而工作区校验的拒绝**绝大多数是可修正的** ——
 * 路径写成绝对的了、忘了产出目录前缀。对这类错误说"别重试"是错的：
 * 模型下一轮明明能改对。这与 `OfficeAgentLoop` 里「**拒绝是反馈，不是终止**」
 * 是同一条判断。
 */
class OfficeToolsTool(
    private val workspaces: OfficeWorkspacePort,
) : McpTool {

    override val name: String = "office_tools"

    override val description: String =
        "列出当前工作区可用的文件工具及其参数。先用它了解能做什么，再用 office_run 执行。" +
            "工作区是**用户自己授权**的目录 —— 你能看到的只有那棵树里的内容。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val workspace = workspaces.current()
            ?: return ToolOutcome.Failed(NO_WORKSPACE_MESSAGE)

        return ToolOutcome.Success(
            // ⚠️ 工具清单**从 OfficeTool 枚举生成**，不手写 —— 手写的那份会在加工具时
            //    漂移，而漂移的表现是"模型不知道有这个工具"，于是它永远不用那个工具，
            //    而没有任何人会发现问题。（与 OfficeAgentLoop.buildPrompt 同一条纪律。）
            text = buildString {
                appendLine("# 工作区文件工具")
                appendLine()
                appendLine("工作区：「${workspace.name}」")
                appendLine("产出目录：`${workspace.policy.outputSubdir}/`" +
                    "（**写操作只能落在这里**，路径必须带这个前缀）")
                appendLine("单文件上限：${workspace.policy.maxFileBytes} 字节")
                appendLine("允许写入：${if (workspace.policy.allowWrite) "是" else "否"}")
                appendLine()
                for (tool in OfficeTool.entries) {
                    appendLine("## `${tool.id}`${if (tool.readOnly) "（只读）" else ""}")
                    appendLine(tool.description)
                    appendLine("参数：${argHintFor(tool)}")
                    appendLine()
                }
                append("调用方式：`office_run`，参数形如 " +
                    "`{\"tool\":\"read_text\",\"args\":{\"path\":\"笔记.md\"}}`。")
            },
            structured = buildJsonObject {
                put("workspace", workspace.name)
                put("outputSubdir", workspace.policy.outputSubdir)
                put("maxFileBytes", workspace.policy.maxFileBytes)
                put("allowWrite", workspace.policy.allowWrite)
                putJsonArray("tools") {
                    for (tool in OfficeTool.entries) {
                        add(buildJsonObject {
                            put("id", tool.id)
                            put("description", tool.description)
                            put("readOnly", tool.readOnly)
                            put("args", argHintFor(tool))
                        })
                    }
                }
            },
        )
    }

    /**
     * 每个工具的参数提示。
     *
     * ⚠️ 这份提示**必须与 `OfficeToolValidator` 实际读的键名一致** ——
     *    参数名对不上时，校验器会报"缺少 path 参数"，而模型看着 schema 里
     *    明明白白写着的 `path` 只会一头雾水。校验器读的键：
     *    `path` / `max_bytes` / `content` / `format` / `header` / `rows`。
     */
    private fun argHintFor(tool: OfficeTool): String = when (tool) {
        OfficeTool.LIST_FILES -> "path（相对路径，根目录写 \".\"）"
        OfficeTool.READ_TEXT -> "path；可选 max_bytes"
        OfficeTool.WRITE_TEXT ->
            "path（**必须带产出目录前缀**，形如 「${outputHint()}文件名」）；content"
        OfficeTool.MAKE_TABLE -> "path；header（逗号分隔）；rows（每行用 ; 分隔，字段用 , 分隔）；" +
            "可选 format（csv 或 xlsx，默认 csv）"
    }

    private fun outputHint(): String = "${workspaces.current()?.policy?.outputSubdir ?: "输出"}/"

    private companion object {
        const val NO_WORKSPACE_MESSAGE =
            "用户还没有授权任何目录，所以现在没有可操作的工作区。" +
                "请引导用户打开本应用的「设置 → 第 0 档能力」，授权一个目录后再试。"
    }
}

/**
 * `office_run` —— 执行一次工作区工具调用。
 *
 * 输入形状刻意与 `OfficeToolCall` 对齐（`tool` + `args` 都是字符串键值），
 * 因为校验器就是按这个形状读的 —— 在这里做任何"统一转成中间结构"，
 * 都会让每个工具的特殊性挤进同一个形状。
 */
class OfficeRunTool(
    private val workspaces: OfficeWorkspacePort,
    private val executor: OfficeToolExecutor,
) : McpTool {

    override val name: String = "office_run"

    override val description: String =
        "在用户授权的工作区里执行一次文件操作（列目录 / 读文本 / 写文本 / 生成表格）。" +
            "参数形状见 office_tools 的输出。**写操作只能落到产出目录下** —— " +
            "路径不带产出目录前缀会被拒绝，改一次即可。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("tool") {
                put("type", "string")
                put("description", "工具 id，取值见 office_tools。")
                putJsonArray("enum") { OfficeTool.entries.forEach { add(JsonPrimitive(it.id)) } }
            }
            putJsonObject("args") {
                put("type", "object")
                put("description", "该工具的参数。**值一律写成字符串**（含数字，如 \"max_bytes\":\"1024\"）—— " +
                    "校验器按字符串读。键名见 office_tools 里对应工具的参数提示。")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("tool")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val toolId = arguments["tool"]?.jsonPrimitive?.contentOrNull
            ?: return ToolOutcome.Failed("缺少 tool 参数。请先调用 office_tools 看有哪些工具。")
        val tool = OfficeTool.byId(toolId)
            ?: return ToolOutcome.Failed(
                "未知工具「$toolId」。可用工具：" +
                    OfficeTool.entries.joinToString("、") { it.id } + "（见 office_tools）。",
            )

        val workspace = workspaces.current()
            ?: return ToolOutcome.Failed(
                "用户还没有授权任何目录，所以现在没有可操作的工作区。" +
                    "请引导用户打开本应用的「设置 → 第 0 档能力」，授权一个目录后再试。",
            )

        // ⚠️ 值一律按**字符串**取 —— 校验器的契约就是 `Map<String, String>`。
        //    数字/布尔按字面量收（`"max_bytes": 1024` 与 `"1024"` 等价）；
        //    对象/数组**如实拒绝**，不"聪明地"转一下 —— 那会让 schema
        //    与校验器的契约悄悄不一致。
        val argsEl = arguments["args"]
        val args: Map<String, String> = if (argsEl == null || argsEl is JsonNull) {
            emptyMap()
        } else {
            val obj = runCatching { argsEl.jsonObject }.getOrNull()
                ?: return ToolOutcome.Failed("args 必须是一个对象（形如 {\"path\":\"a.txt\"}）。")
            val out = LinkedHashMap<String, String>(obj.size)
            for ((key, value) in obj) {
                if (value !is JsonPrimitive || value is JsonNull) {
                    return ToolOutcome.Failed(
                        "args 里的「$key」不是标量（是对象或数组）。请把每个参数写成字符串或数字。",
                    )
                }
                out[key] = value.content
            }
            out
        }

        val call = OfficeToolCall(tool = tool, args = args)

        // ── 校验：走 :filelogic 的唯一一份实现 ──────────────────────
        return when (val validation = OfficeToolValidator.validate(call, workspace.policy)) {
            is ToolValidation.Denied -> ToolOutcome.Failed(
                "参数未通过工作区策略校验：${validation.reason}" +
                    "（这通常是**可以修正的** —— 请按上面的提示改一次再试。）",
            )

            is ToolValidation.Allowed -> execute(validation.request)
        }
    }

    private suspend fun execute(request: OfficeRequest): ToolOutcome {
        val outcome = try {
            executor.execute(request)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 协程取消必须原样上抛，不许吞
        } catch (e: Exception) {
            // 「异常不得外抛」是执行端口的纪律；这里再兜一层 ——
            // MCP 工具链上任何泄漏的异常都会变成没有面向用户文案的 -32603。
            return ToolOutcome.Failed(
                "执行时抛出异常（${e::class.simpleName ?: "未知"}）。可以重试一次；" +
                    "若持续出现，请把这条信息交给开发者。",
            )
        }
        return when (outcome) {
            is OfficeToolOutcome.Ok -> ToolOutcome.Success(
                text = outcome.summary,
                structured = buildJsonObject {
                    put("ok", true)
                    put("summary", outcome.summary)
                    put("request", describe(request))
                },
            )

            is OfficeToolOutcome.Failed -> ToolOutcome.Failed(outcome.reason)
        }
    }

    /** 把已执行的动作如实描述出来（审计与"用户要知道它做了什么"）。 */
    private fun describe(request: OfficeRequest): String = when (request) {
        is OfficeRequest.ListFiles -> "list_files ${request.relativePath.ifEmpty { "." }}"
        is OfficeRequest.ReadText -> "read_text ${request.relativePath}（上限 ${request.maxBytes} 字节）"
        is OfficeRequest.WriteText ->
            "write_text ${request.relativePath}（${request.content.toByteArray(Charsets.UTF_8).size} 字节）"
        is OfficeRequest.MakeTable ->
            "make_table ${request.relativePath}（${request.format.extension}，" +
                "${request.rows.size} 行 × ${request.header.size} 列）"
    }
}

/**
 * 当前工作区 —— **由 `:app` 解析**（它才知道用户授了哪个目录、策略是什么）。
 *
 * ⚠️ 返回 null 表示"用户还没授权任何目录"。那**不是**错误，是初始状态 ——
 *    工具应当给出"去授权一个目录"的引导，而不是报一句"失败了"。
 */
fun interface OfficeWorkspacePort {
    fun current(): OfficeWorkspace?
}

/**
 * 一个可用的工作区。
 *
 * [policy] 是 `:filelogic` 的类型 —— 校验器直接吃它。
 * 刻意**不在这里复制一份策略字段**：复制出来就要两边同步，
 * 而"写入目录到底是哪个"这种不一致在真机上表现为"文件写到别处去了"。
 */
data class OfficeWorkspace(
    /** 给用户/模型看的名字（通常是授权目录的显示名）。 */
    val name: String,
    val policy: WorkspacePolicy,
)
