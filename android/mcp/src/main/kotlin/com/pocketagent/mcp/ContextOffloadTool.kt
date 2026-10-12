package com.pocketagent.mcp

import com.pocketagent.agentlogic.PrivacyFilter
import com.pocketagent.agentlogic.SystemUiRegions
import com.pocketagent.memorylogic.ContextOffloader
import com.pocketagent.memorylogic.OffloadDraft
import com.pocketagent.memorylogic.OffloadKind
import com.pocketagent.memorylogic.OffloadOutcome
import com.pocketagent.memorylogic.OffloadPolicy
import com.pocketagent.memorylogic.OffloadRef
import com.pocketagent.memorylogic.PrivacyGate
import com.pocketagent.memorylogic.RecallOutcome
import com.pocketagent.memorylogic.asPrivacyGate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `context_offload` / `context_recall` —— 把「上下文卸载」以 MCP 工具的形式交给 dsh。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 本组工具是 `:memorylogic` 的**第一个消费方**
 * ═══════════════════════════════════════════════════════════════
 *
 * 与 `:personalogic` 一样，`:memorylogic` 此前**没有任何模块引用** ——
 * 编译得过、测试全绿，但 `classes.dex` 里一个字节都没有。
 *
 * 而它比人格线更危险：它扛着一条红线 ——
 * **「卸载前必须过 `PrivacyFilter`，顺序不可颠倒」（架构 §8.2-2）**。
 * 那条红线此前由 `OffloadPayload` 的**私有构造函数**保证（"不过关卡直接写"编译不过），
 * 但**代码从没进过包**，所以它从未被执行过一次 ——
 * **「红线没跑过」与「红线没生效」在真机上长得一模一样。**
 *
 * ═══════════════════════════════════════════════════════════════
 *  它解决什么（不是"为了有个消费方"）
 * ═══════════════════════════════════════════════════════════════
 *
 * dsh 的上下文就是它的瓶颈。而它手上最容易膨胀的东西，恰恰是本能力桥自己产出的：
 * 一次 `screen_read` 就是几百行节点文本。腾讯那组实测里短期记忆 token 降 61%，
 * 靠的就是"把大块内容搬出上下文、只留一行占位"这一件事。
 *
 * ```
 *   screen_read        → 几百行节点文本（进上下文，线性推高 token）
 *   context_offload    → [已卸载「工具原始结果」· 5120 字节 · of-abc…]  ← 只留这一行
 *   context_recall     → 需要时原样取回
 * ```
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 关卡顺序（不可调换）—— 与 `screen_read` 同一条纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 *   ① 敏感判定（包名 / 页面文本 / 控件形状）—— 一票否决
 *   ② 构造 OffloadDraft（页面类型 / 输入框区域 —— 只有 Android 层量得出来）
 *   ③ ContextOffloader.offload：关卡 → 编码 → 落盘
 * ```
 *
 * 顺序错一步就是一次**静默的**泄漏：把 ③ 放到 ① 前面，支付页的内容**已经在磁盘上了**，
 * 而且没有任何一处会报错。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 一个我认为需要拍板的开放问题（已写进协作信）
 * ═══════════════════════════════════════════════════════════════
 *
 * `pageKind` 取自**当前屏幕**（因为只有它量得出来），而卸载的正文可能来自
 * **更早的一次读取**。两者在时间上可以不一致。
 *
 * 当前的处置是**保守方向**的，理由如下（我认为成立，但它值得被质疑）：
 * 本桥里"大正文"的唯一生产者就是 `screen_read`，而它在**产出时**已经拒过敏感页 ——
 * 所以正文本身不可能含支付页内容。剩下的不一致只可能来自模型自己编的正文，
 * 而那种正文没有"泄漏用户屏幕"的问题。
 *
 * 若将来出现第二个正文来源（比如截图 OCR），这条推理就不成立了 ——
 * 那时需要给 [OffloadDraft] 加**来源标记**。**现在不加**：在没有第二个来源时
 * 定义来源标记的格式，等于猜一个还没问出口的需求。
 */
class ContextOffloadTool(
    private val offloader: ContextOffloader,

    /**
     * 构造 `OffloadDraft` 的端口。实现在 `:app`（要读无障碍树、要量输入框区域）。
     *
     * ⚠️ 它是 `suspend` 的：实现要读屏（无障碍 API 全在主线程回调）。
     */
    private val drafts: OffloadDraftPort,

    /**
     * 隐私关卡。
     *
     * ⚠️ 与 `ScreenReadTool` 同一个构造方式与同一个理由：`systemUi = unknown`
     *    对**文本路径**是安全的（`PrivacyFilter.review` 在 `requiresScreenshot=false`
     *    时于系统 UI 检查**之前**就返回 —— 文本没有像素）。
     *    显式写出来，免得将来有人以为"unknown 会丢内容"而顺手改掉。
     */
    private val gate: PrivacyGate = PrivacyFilter(systemUi = SystemUiRegions.unknown).asPrivacyGate(),

    /** 卸载时刻。内核不读时钟 —— 这里注入，测试才能钉住幂等性。 */
    private val clock: () -> Long = System::currentTimeMillis,
) : McpTool {

    override val name: String = "context_offload"

    override val description: String =
        "把一大段内容从上下文里搬出去、存到本机，只留一行占位索引 —— 需要时用 " +
            "context_recall 原样取回。用于：一次 screen_read 的完整结果、超长正文、" +
            "你不再需要逐字阅读但之后可能要用到的内容。" +
            "返回的 ref 请原样保存（context_recall 要用它）。" +
            "注意：敏感页面（支付/密码/验证码）会被安全策略拒绝，一个字节都不会落盘。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("kind") {
                put("type", "string")
                put("description", "内容类别。tool-result=工具原始结果（如 screen_read 的输出）；" +
                    "screenshot=屏幕截图说明；turn-body=对话长正文。它决定落盘子目录，也决定占位索引里的说法。")
                putJsonArray("enum") { OffloadKind.entries.forEach { add(JsonPrimitive(it.segment)) } }
            }
            putJsonObject("taskId") {
                put("type", "string")
                put("description", "这条内容属于哪个任务。落盘按任务分目录；同一份内容属于两个任务时各留一份。")
            }
            putJsonObject("body") {
                put("type", "string")
                put("description", "要卸载的正文本身。**内容指纹由它派生** —— 同一份内容重复卸载会落到同一个文件，" +
                    "所以重试是幂等的，不会在你的存储里堆出一串一样的大文件。")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("kind")); add(JsonPrimitive("taskId")); add(JsonPrimitive("body"))
        }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        // ── 参数校验（工具内部做，见 inputSchema 的注释：不写 additionalProperties:false）──
        val kind = kindOf(arguments) ?: return ToolOutcome.Failed(
            "参数 kind 缺失或不是合法类别。合法值：" +
                OffloadKind.entries.joinToString("、") { it.segment } +
                "（${OffloadKind.entries.joinToString("、") { it.label }}）。",
        )
        val taskId = arguments["taskId"]?.jsonPrimitive?.contentOrNull
        if (taskId.isNullOrBlank()) {
            return ToolOutcome.Failed("参数 taskId 缺失或为空 —— 恢复时要按任务找回去，不能省。")
        }
        val body = arguments["body"]?.jsonPrimitive?.contentOrNull
        if (body.isNullOrEmpty()) {
            return ToolOutcome.Failed("参数 body 缺失或为空 —— 没有内容可卸载。")
        }

        // ── ① + ②：先过敏感判定，再由 Android 层构造 draft ──────────
        val draft = when (val outcome = drafts.draft(kind, taskId, body)) {
            is OffloadDraftOutcome.Refused -> return ToolOutcome.Refused(outcome.reason)
            is OffloadDraftOutcome.Ready -> outcome.draft
        }

        // ── ③：关卡 → 编码 → 落盘（顺序由 ContextOffloader 保证，且由类型钉住）──
        return when (val outcome = offloader.offload(draft, gate, clock())) {
            is OffloadOutcome.Dropped -> ToolOutcome.Refused(
                "${outcome.reason}（这是安全策略的最终结论，请不要重试；" +
                    "请把上面的原因如实告诉用户。）",
            )

            is OffloadOutcome.Stored -> {
                val ref = outcome.ref
                ToolOutcome.Success(
                    text = buildString {
                        appendLine("已卸载，内容不在上下文里了。")
                        appendLine()
                        appendLine("占位索引（放进上下文的就是这一行）：")
                        appendLine("  ${OffloadPolicy.placeholderFor(ref)}")
                        appendLine()
                        appendLine("引用（`context_recall` 要用它，**请原样保存**）：")
                        appendLine("  id=${ref.id}  kind=${ref.kind.segment}  taskId=${ref.taskId}")
                        appendLine("  bytes=${ref.bytes}  sha256=${ref.sha256}")
                        append(
                            "需要原文时，把上面的 ref 原样传给 `context_recall`（字段名照抄，" +
                                "不要自己拼）。",
                        )
                    },
                    structured = buildJsonObject {
                        putJsonObject("ref") {
                            put("id", ref.id)
                            put("kind", ref.kind.segment)
                            put("taskId", ref.taskId)
                            put("bytes", ref.bytes)
                            put("sha256", ref.sha256)
                            put("createdAt", ref.createdAt)
                        }
                        put("placeholder", OffloadPolicy.placeholderFor(ref))
                    },
                )
            }
        }
    }

    private fun kindOf(arguments: JsonObject): OffloadKind? {
        val raw = arguments["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        return OffloadKind.bySegment(raw)
    }
}

/**
 * `context_recall` —— 把卸载过的内容原样取回来。
 *
 * ⚠️ 与 [ContextOffloadTool] 分开成两个工具，而不是一个工具带 action 参数：
 *    **两者的输入形状完全不同**（一个吃正文，一个吃引用），
 *    合起来会让 schema 里一半字段永远用不上 —— 而模型会试着去填它们。
 *
 * ★ 三道检查都在 [ContextOffloader.recall] 里（文件在不在 / 指纹对不对 /
 *   往返闭不闭合）。这里**只做参数搬运**，不重复判定 ——
 * 重复判定会让"哪一份是真的"变成一个需要读两处才能回答的问题。
 */
class ContextRecallTool(
    private val offloader: ContextOffloader,
) : McpTool {

    override val name: String = "context_recall"

    override val description: String =
        "取回之前用 context_offload 卸载的内容（逐字节一致）。" +
            "需要传入 context_offload 返回的那个 ref —— 原样抄，不要自己拼。" +
            "若内容已丢失或被改写，会如实报错，不会把半截内容当成功返回。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("ref") {
                put("type", "object")
                put("description", "context_offload 返回的 ref 对象，**原样抄过来**。" +
                    "⚠️ 五个字段都不能少：bytes 与 sha256 是恢复时的完整性判据，" +
                    "少一个就无法分辨「内容对」与「文件被截断」。" +
                    "（kind 与 taskId 一起决定落盘路径；它们必须在路径与指纹两处都自洽。）")
                put("required", buildJsonArray {
                    add(JsonPrimitive("id")); add(JsonPrimitive("kind")); add(JsonPrimitive("taskId"))
                    add(JsonPrimitive("bytes")); add(JsonPrimitive("sha256")); add(JsonPrimitive("createdAt"))
                })
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "string") }
                    putJsonObject("kind") { put("type", "string") }
                    putJsonObject("taskId") { put("type", "string") }
                    putJsonObject("bytes") { put("type", "integer") }
                    putJsonObject("sha256") { put("type", "string") }
                    putJsonObject("createdAt") { put("type", "integer") }
                }
            }
        }
        putJsonArray("required") { add(JsonPrimitive("ref")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val ref = parseRef(arguments) ?: return ToolOutcome.Failed(
            "参数 ref 缺失或字段不全。请把 `context_offload` 返回的 structuredContent.ref " +
                "**原样**传过来（六个字段：id / kind / taskId / bytes / sha256 / createdAt），" +
                "不要自己拼路径或省略字段。",
        )

        return when (val outcome = offloader.recall(ref)) {
            is RecallOutcome.Restored -> ToolOutcome.Success(
                text = outcome.body,
                structured = buildJsonObject {
                    put("id", ref.id)
                    put("bytes", ref.bytes)
                    put("verified", true)
                },
            )

            // ⚠️ Missing 与 Corrupted 分开报：前者是"文件被删了"（用户动作：重新卸载），
            //    后者是"内容对不上"（用户动作：**不要用这份内容**）。
            //    合并成一句"取不回来"会让用户对着一个内容已被改写的文件继续干活。
            is RecallOutcome.Missing -> ToolOutcome.Failed(
                "这条引用在存储里找不到（${ref.id}）—— 文件可能被用户删了，或者当初就没写成功。" +
                    "请重新执行一次 context_offload。",
            )

            is RecallOutcome.Corrupted -> ToolOutcome.Failed(
                "内容完整性校验未通过：${outcome.reason}。" +
                    "**请勿使用这份内容**，也不要把它当作原文引用给用户。" +
                    "请重新执行一次 context_offload。",
            )
        }
    }

    private fun parseRef(arguments: JsonObject): OffloadRef? {
        val o = arguments["ref"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
        val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val kindSegment = o["kind"]?.jsonPrimitive?.contentOrNull ?: return null
        val taskId = o["taskId"]?.jsonPrimitive?.contentOrNull ?: return null
        val bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: return null
        val sha256 = o["sha256"]?.jsonPrimitive?.contentOrNull ?: return null
        val createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull ?: return null
        val kind = OffloadKind.bySegment(kindSegment) ?: return null
        // OffloadRef 的 init 块会做剩余校验（id 前缀 / bytes 为正 / sha256 长度）——
        // 让它抛，由 runCatching 兜住：校验规则只有一份，不在这里复制。
        return runCatching {
            OffloadRef(
                id = id, kind = kind, taskId = taskId,
                bytes = bytes.toInt(), sha256 = sha256, createdAt = createdAt,
            )
        }.getOrNull()
    }
}

/**
 * 构造 `OffloadDraft` 的端口 —— **实现在 `:app`**。
 *
 * ⚠️ 为什么不是让工具直接 new `OffloadDraft`：它的 `request` 里有两样东西
 *    **只有 Android 层量得出来**（`OffloadDraft` 的注释原话）：
 *    「页面类型、输入框区域」。记忆层与协议层都不该去猜它们。
 *    ——这正是设计稿说的「**识别在 Android 层、决策在纯层**」。
 *
 * ⚠️ 端口返回 [OffloadDraftOutcome.Refused] 而不是抛异常：敏感页面是**产品行为**
 *    不是故障。抛异常会被分派器兜底成 `-32603`，那条路径上**没有任何面向用户的文案**。
 */
fun interface OffloadDraftPort {
    suspend fun draft(kind: OffloadKind, taskId: String, body: String): OffloadDraftOutcome
}

/** 构造 draft 的结论。 */
sealed interface OffloadDraftOutcome {

    /** 可以卸载。[draft] 的 `request` 已经带上真实的页面类型与输入框区域。 */
    data class Ready(val draft: OffloadDraft) : OffloadDraftOutcome

    /** 拒绝卸载。[reason] 必须能**直接展示给用户**。 */
    data class Refused(val reason: String) : OffloadDraftOutcome
}
