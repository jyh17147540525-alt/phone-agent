package com.pocketagent.filelogic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 办公工具 —— **智能体能调用的动作集合**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ [readOnly] 不是分类标签，是**权限边界的声明**
 * ═══════════════════════════════════════════════════════════════
 *
 * 任务下发是**分阶段开放工具**的（P1 只读 → P2 生成 → P3 修改），
 * 而分阶段的依据就是这一个布尔值。它的存在让"当前阶段允许哪些工具"
 * 变成一个**可计算**的问题，而不是散落在各处的 `if (stage >= 2)`。
 *
 * 为什么这件事值得一个字段：一旦"哪些工具能用"变成分散判断，
 * 迟早会有某个分支漏掉 —— 而漏掉的后果是**在只读阶段执行了写操作**。
 * 那种错误不会报错，只会让用户发现"我只让它读一下，它却改了文件"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么工具定义住在 `:filelogic` 而不是 `:agentlogic`
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为**参数校验是文件语义的一部分**："这个路径能不能写"取决于
 * [WorkspacePolicy]、取决于路径有没有穿越、取决于产出该落在哪 ——
 * 这些全是本模块已经建好的概念。把校验挪到 agent 层，等于
 * 让"路径安全"有两个真相来源。
 *
 * ⇒ 本模块负责"这个调用**合法吗**"，agent 层负责"要不要发起这个调用"。
 */
enum class OfficeTool(
    val id: String,

    /** 给模型看的说明。**要写清"能做什么"，而不是"怎么调"** —— 参数由 schema 表达。 */
    val description: String,

    /** 是否只读。见类注释。 */
    val readOnly: Boolean,
) {
    LIST_FILES(
        id = "list_files",
        description = "列出工作区里某个目录下的文件和子目录。路径用相对路径，根目录写 \".\"。",
        readOnly = true,
    ),

    READ_TEXT(
        id = "read_text",
        description = "读取工作区里一个文本文件的内容。非 UTF-8 的文件会被拒绝，不会'尽力解码'。",
        readOnly = true,
    ),

    WRITE_TEXT(
        id = "write_text",
        description = "在工作区的产出目录里新建或覆盖一个文本文件。路径必须是相对路径。",
        readOnly = false,
    ),

    MAKE_TABLE(
        id = "make_table",
        description = "把行列数据存成表格文件。format 取 csv 或 xlsx，默认 csv；xlsx 会带表头加粗与列宽。",
        readOnly = false,
    ),

    MAKE_SLIDES(
        id = "make_slides",
        description = "把大纲做成一页页幻灯片（pptx）。slides 是 JSON 数组：" +
            "[{\"title\":\"季度回顾\",\"subtitle\":\"可选\",\"bullets\":[\"要点一\",\"要点二\"]}]。" +
            "每页最多 8 条要点，整份最多 60 页。",
        readOnly = false,
    ),

    MAKE_DOC(
        id = "make_doc",
        description = "把标题与段落写成 Word 文档（docx）。blocks 是 JSON 数组：" +
            "[{\"heading\":1,\"text\":\"标题\"},{\"text\":\"正文段落\"}]；heading 取 1–4，省略就是普通段落。",
        readOnly = false,
    ),
    ;

    companion object {
        private val BY_ID = entries.associateBy { it.id }

        fun byId(id: String): OfficeTool? = BY_ID[id.trim()]
    }
}

/** 一次解析成功的工具调用。 */
data class OfficeToolCall(
    val tool: OfficeTool,
    /** 原始参数。**保留原样**，校验阶段按工具逐个取用 —— 不做"统一转成某种中间结构"， 那会让每个工具的特殊性都要挤进同一个形状。 */
    val args: Map<String, String>,
)

/** 解析结论。 */
sealed interface ToolParseOutcome {

    data class Ready(val call: OfficeToolCall) : ToolParseOutcome

    /** 解析不了（不是 JSON、缺 tool 字段、未知工具）。 */
    data class Invalid(val reason: String) : ToolParseOutcome
}

/**
 * 工具调用**解析器** —— 从模型输出到结构化调用。
 *
 * ⚠️ 只做"能不能读懂"，**不做安全判断**。安全判断在 [OfficeToolValidator]。
 *    分开的理由与 `FileAccessDecider` 的分工一致：
 *    "读不懂"与"不允许"给用户/模型的下一步完全不同
 *    （前者重新生成，后者放弃这个动作）。
 */
object OfficeToolParser {

    /**
     * 解析模型给出的工具调用。
     *
     * 接受的形态：
     * ```json
     * {"tool": "read_text", "args": {"path": "报告.md"}}
     * ```
     * 也接受 `args` 直接平铺在顶层的写法（模型经常这么干）：
     * ```json
     * {"tool": "read_text", "path": "报告.md"}
     * ```
     */
    fun parse(raw: String): ToolParseOutcome {
        val obj = try {
            JSON.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            return ToolParseOutcome.Invalid("工具调用不是合法的 JSON 对象：${e.message ?: "解析失败"}")
        }

        val name = obj["tool"]?.jsonPrimitive?.textOrNull()
            ?: obj["name"]?.jsonPrimitive?.textOrNull()
            ?: return ToolParseOutcome.Invalid("工具调用缺少 tool 字段")

        val tool = OfficeTool.byId(name)
            ?: return ToolParseOutcome.Invalid(
                "未知工具「$name」。可用工具：${OfficeTool.entries.joinToString(" / ") { it.id }}",
            )

        // 参数来源：优先 args 子对象；没有就把顶层除 tool/name 之外的标量当成参数。
        // ⚠️ 两种都接受是刻意的 —— 模型两种写法都会用，而只认一种会让
        //    一半的调用被判成"缺参数"，然后模型重试、再失败，最后放弃任务。
        val args: Map<String, String> = obj["args"]?.let { element ->
            runCatching {
                element.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
            }.getOrNull() ?: emptyMap()
        } ?: obj.filterKeys { it != "tool" && it != "name" }.mapNotNull { (k, v) ->
            runCatching { k to v.jsonPrimitive.content }.getOrNull()
        }.toMap()

        return ToolParseOutcome.Ready(OfficeToolCall(tool = tool, args = args))
    }

    private fun JsonPrimitive.textOrNull(): String? = content.takeIf { it.isNotBlank() }

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
}

/**
 * 工具调用要执行的动作。**由校验器产出**，不是解析器 ——
 * 因为它只有在"路径合法 + 策略允许"之后才成立。
 */
sealed interface OfficeRequest {

    data class ListFiles(val relativePath: String) : OfficeRequest

    data class ReadText(val relativePath: String, val maxBytes: Int) : OfficeRequest

    data class WriteText(val relativePath: String, val content: String) : OfficeRequest

    data class MakeTable(
        val relativePath: String,
        val header: List<String>,
        val rows: List<List<String>>,
        val format: DocumentFormat,
    ) : OfficeRequest

    data class MakeSlides(
        val relativePath: String,
        val slides: List<PptxWriter.Slide>,
    ) : OfficeRequest

    data class MakeDoc(
        val relativePath: String,
        val blocks: List<DocxWriter.Block>,
    ) : OfficeRequest
}

/** 校验结论。 */
sealed interface ToolValidation {

    data class Allowed(val request: OfficeRequest) : ToolValidation

    /**
     * 拒绝。
     *
     * ⚠️ [reason] 是**要回给模型看的** —— 它会据此改一次再试。
     *    所以它必须说清"哪里不对"，而不是笼统的"参数错误"。
     *    但也**不要**把策略细节写进去（比如"产出目录是 输出/"已经够了，
     *    不需要解释策略是怎么来的）—— 那些是给用户看的。
     */
    data class Denied(val reason: String) : ToolValidation
}

/**
 * 工具调用**校验器** —— 本文件里安全关键的部分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 三条硬规则
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 路径必须是相对的，且不能穿越。**
 *    `..`、绝对路径、`~` 一律拒绝。这不是"防手滑" ——
 *    模型完全可能在一次"看起来合理"的调用里写 `../../DCIM`，
 *    而它没有任何恶意，只是**想帮用户找文件**。
 *
 * **2. 写操作的路径必须落在 `policy.outputSubdir` 之下。**
 *    这条是**结构性的**：即使模型写了 `报告.md`（工作区根），
 *    也会被改写成 `输出/报告.md` 或直接拒绝。
 *    ⇒ 智能体**在结构上不可能覆盖用户的原文件**，
 *      而不是"靠每次弹确认框提醒用户"。
 *
 * **3. 内容大小必须在上限内。**
 *    防的是 OOM：一个 100MB 的字符串塞进内存会崩，
 *    而崩溃现场指不到"写了个太大的文件"。
 */
object OfficeToolValidator {

    /** 读的默认上限，与 `FileChannel.DEFAULT_MAX_READ_BYTES` 同口径。 */
    const val DEFAULT_READ_MAX_BYTES: Int = 256 * 1024

    fun validate(call: OfficeToolCall, policy: WorkspacePolicy): ToolValidation =
        when (call.tool) {
            OfficeTool.LIST_FILES -> validateList(call)
            OfficeTool.READ_TEXT -> validateRead(call, policy)
            OfficeTool.WRITE_TEXT -> validateWrite(call, policy)
            OfficeTool.MAKE_TABLE -> validateMakeTable(call, policy)
            OfficeTool.MAKE_SLIDES -> validateMakeSlides(call, policy)
            OfficeTool.MAKE_DOC -> validateMakeDoc(call, policy)
        }

    // ── 只读 ────────────────────────────────────────────────────

    private fun validateList(call: OfficeToolCall): ToolValidation {
        val raw = call.args["path"]?.takeIf { it.isNotBlank() } ?: "."
        val path = relativePathOrNull(raw)
            ?: return ToolValidation.Denied("目录路径不合法：「$raw」。请用工作区内的相对路径，根目录写 \".\"。")
        return ToolValidation.Allowed(OfficeRequest.ListFiles(path))
    }

    private fun validateRead(call: OfficeToolCall, policy: WorkspacePolicy): ToolValidation {
        val raw = call.args["path"]?.takeIf { it.isNotBlank() }
            ?: return ToolValidation.Denied("read_text 缺少 path 参数")
        val path = relativePathOrNull(raw)
            ?: return ToolValidation.Denied("文件路径不合法：「$raw」。请用工作区内的相对路径。")

        val requested = call.args["max_bytes"]?.toIntOrNull()
        // ⚠️ 即使调用方要得更多，也不超过策略上限 —— 上限的意义就是"调用方说了不算"
        val maxBytes = (requested ?: DEFAULT_READ_MAX_BYTES)
            .coerceAtMost(policy.maxFileBytes.toInt().coerceAtLeast(1))

        return ToolValidation.Allowed(OfficeRequest.ReadText(path, maxBytes))
    }

    // ── 写 ──────────────────────────────────────────────────────

    private fun validateWrite(call: OfficeToolCall, policy: WorkspacePolicy): ToolValidation {
        if (!policy.allowWrite) {
            return ToolValidation.Denied("当前工作区不允许写入（allowWrite = false）")
        }
        val raw = call.args["path"]?.takeIf { it.isNotBlank() }
            ?: return ToolValidation.Denied("write_text 缺少 path 参数")
        val path = outputPathOrNull(raw, policy)
            ?: return ToolValidation.Denied(
                "写入路径不合法：「$raw」。只能写到产出目录「${policy.outputSubdir}/」下，用相对路径。",
            )

        val content = call.args["content"] ?: return ToolValidation.Denied("write_text 缺少 content 参数")

        // ★ 按**字节**算，不是字符数 —— 一个汉字 3 字节，
        //   按字符算会让中文的上限悄悄变成三倍
        val bytes = content.toByteArray(Charsets.UTF_8).size
        if (bytes > policy.maxFileBytes) {
            return ToolValidation.Denied(
                "内容太大：$bytes 字节，超过上限 ${policy.maxFileBytes} 字节。请拆成多个文件。",
            )
        }

        return ToolValidation.Allowed(OfficeRequest.WriteText(path, content))
    }

    private fun validateMakeTable(call: OfficeToolCall, policy: WorkspacePolicy): ToolValidation {
        if (!policy.allowWrite) {
            return ToolValidation.Denied("当前工作区不允许写入（allowWrite = false）")
        }
        val raw = call.args["path"]?.takeIf { it.isNotBlank() }
            ?: return ToolValidation.Denied("make_table 缺少 path 参数")
        val path = outputPathOrNull(raw, policy)
            ?: return ToolValidation.Denied(
                "写入路径不合法：「$raw」。只能写到产出目录「${policy.outputSubdir}/」下。",
            )

        val format = when (call.args["format"]?.trim()?.lowercase() ?: "csv") {
            "csv" -> DocumentFormat.CSV
            "xlsx", "excel" -> DocumentFormat.XLSX
            else -> return ToolValidation.Denied("make_table 只支持 csv 或 xlsx，收到「${call.args["format"]}」")
        }

        val header = call.args["header"]?.split(',')?.map { it.trim() } ?: emptyList()
        val rows = call.args["rows"]?.let { parseRows(it) } ?: emptyList()

        return ToolValidation.Allowed(OfficeRequest.MakeTable(path, header, rows, format))
    }

    /**
     * 幻灯片。
     *
     * ⚠️ 空 `slides` **拒绝**，而不是生成一份零页文件：模型空着手来，
     *    说明它没想好要讲什么；给它一个"成功"只会让它以为自己讲完了。
     */
    private fun validateMakeSlides(call: OfficeToolCall, policy: WorkspacePolicy): ToolValidation {
        val raw = call.args["path"]?.takeIf { it.isNotBlank() }
            ?: return ToolValidation.Denied("make_slides 缺少 path 参数")
        val path = outputPathOrNull(raw, policy)
            ?: return ToolValidation.Denied(
                "写入路径不合法：「$raw」。只能写到产出目录「${policy.outputSubdir}/」下。",
            )

        val json = call.args["slides"]?.takeIf { it.isNotBlank() }
            ?: return ToolValidation.Denied(
                "make_slides 缺少 slides 参数。给 JSON 数组：" +
                    """[{"title":"标题","bullets":["要点"]}]""",
            )

        val slides = when (val parsed = parseSlides(json)) {
            is SlideParse.Ok -> parsed.slides
            is SlideParse.Bad -> return ToolValidation.Denied(parsed.reason)
        }

        if (slides.isEmpty()) {
            return ToolValidation.Denied("slides 是空数组。请至少给一页，不然生成出来的文件是空的。")
        }
        if (slides.size > PptxWriter.MAX_SLIDES) {
            return ToolValidation.Denied(
                "一页页讲不完：${slides.size} 页超过上限 ${PptxWriter.MAX_SLIDES} 页。请合并或删减。",
            )
        }
        slides.forEachIndexed { index, slide ->
            if (slide.bullets.size > PptxWriter.MAX_BULLETS) {
                return ToolValidation.Denied(
                    "第 ${index + 1} 页有 ${slide.bullets.size} 条要点，超过上限 ${PptxWriter.MAX_BULLETS} 条：" +
                        "一页放不下，观众也读不完。请拆成两页。",
                )
            }
        }

        // 扩展名跟着工具走：模型把 path 写成 a.docx 会让用户双击打不开，这里直接纠正
        return ToolValidation.Allowed(OfficeRequest.MakeSlides(withExtension(path, "pptx"), slides))
    }

    /** Word 文档。判定与 [validateMakeSlides] 同口径。 */
    private fun validateMakeDoc(call: OfficeToolCall, policy: WorkspacePolicy): ToolValidation {
        val raw = call.args["path"]?.takeIf { it.isNotBlank() }
            ?: return ToolValidation.Denied("make_doc 缺少 path 参数")
        val path = outputPathOrNull(raw, policy)
            ?: return ToolValidation.Denied(
                "写入路径不合法：「$raw」。只能写到产出目录「${policy.outputSubdir}/」下。",
            )

        val json = call.args["blocks"]?.takeIf { it.isNotBlank() }
            ?: return ToolValidation.Denied(
                "make_doc 缺少 blocks 参数。给 JSON 数组：" +
                    """[{"heading":1,"text":"标题"},{"text":"正文"}]""",
            )

        val blocks = when (val parsed = parseBlocks(json)) {
            is BlockParse.Ok -> parsed.blocks
            is BlockParse.Bad -> return ToolValidation.Denied(parsed.reason)
        }

        if (blocks.isEmpty()) {
            return ToolValidation.Denied("blocks 是空数组。请至少给一段，不然生成出来的文档是空的。")
        }
        return ToolValidation.Allowed(OfficeRequest.MakeDoc(withExtension(path, "docx"), blocks))
    }

    /** 幻灯片 JSON 的解析结论。 */
    private sealed interface SlideParse {
        data class Ok(val slides: List<PptxWriter.Slide>) : SlideParse
        data class Bad(val reason: String) : SlideParse
    }

    private fun parseSlides(raw: String): SlideParse {
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull()
            ?: return SlideParse.Bad(
                "slides 不是合法的 JSON 数组。示例：" +
                    """[{"title":"标题","bullets":["要点一"]}]""",
            )
        if (array.isEmpty()) return SlideParse.Bad("slides 是空数组。")

        val slides = ArrayList<PptxWriter.Slide>(array.size)
        array.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
                ?: return SlideParse.Bad("第 ${index + 1} 页不是一个对象（{…}）。")
            val title = obj["title"]?.jsonPrimitive?.textOrNull()?.trim().orEmpty()
            if (title.isEmpty()) {
                return SlideParse.Bad("第 ${index + 1} 页缺少 title。每页都要有标题，哪怕是「小结」。")
            }
            val subtitle = obj["subtitle"]?.jsonPrimitive?.textOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val bullets = obj["bullets"]?.let { b ->
                runCatching { b.jsonArray.map { it.jsonPrimitive.textOrNull() ?: "" } }.getOrNull()
                    ?: return SlideParse.Bad("第 ${index + 1} 页的 bullets 不是字符串数组。")
            }.orEmpty()
            slides += PptxWriter.Slide(title = title, bullets = bullets, subtitle = subtitle)
        }
        return SlideParse.Ok(slides)
    }

    /** Word 块的解析结论。 */
    private sealed interface BlockParse {
        data class Ok(val blocks: List<DocxWriter.Block>) : BlockParse
        data class Bad(val reason: String) : BlockParse
    }

    private fun parseBlocks(raw: String): BlockParse {
        val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull()
            ?: return BlockParse.Bad(
                "blocks 不是合法的 JSON 数组。示例：" +
                    """[{"heading":1,"text":"标题"},{"text":"正文"}]""",
            )
        if (array.isEmpty()) return BlockParse.Bad("blocks 是空数组。")

        val blocks = ArrayList<DocxWriter.Block>(array.size)
        array.forEachIndexed { index, element ->
            val obj = runCatching { element.jsonObject }.getOrNull()
                ?: return BlockParse.Bad("第 ${index + 1} 段不是一个对象（{…}）。")
            val text = obj["text"]?.jsonPrimitive?.textOrNull() ?: ""
            val level = obj["heading"]?.jsonPrimitive?.textOrNull()?.trim()?.toIntOrNull()
            if (level != null) {
                if (level < 1 || level > DocxWriter.MAX_HEADING_LEVEL) {
                    return BlockParse.Bad(
                        "第 ${index + 1} 段的 heading 是 $level，只支持 1–${DocxWriter.MAX_HEADING_LEVEL}。",
                    )
                }
                if (text.isBlank()) return BlockParse.Bad("第 ${index + 1} 段是标题但没有文字。")
                blocks += DocxWriter.Block.Heading(level, text)
            } else {
                // 空段落是合法的：用户就是想在文档里留一个空行
                blocks += DocxWriter.Block.Paragraph(text)
            }
        }
        return BlockParse.Ok(blocks)
    }

    /**
     * 取一个 JSON 标量的字符串内容。
     *
     * ⚠️ `JsonNull.content` 是**字面量 "null"**，直接取会把缺失字段变成四个字母，
     *    于是标题写成「null」出现在幻灯片上。所以先判类型。
     */
    private fun kotlinx.serialization.json.JsonPrimitive.textOrNull(): String? =
        if (this is JsonNull) null else content

    /** 把扩展名纠正成这个工具该产出的那一种。 */
    private fun withExtension(path: String, ext: String): String {
        val dot = path.lastIndexOf('/')
        val nameStart = if (dot >= 0) dot + 1 else 0
        val lastDot = path.lastIndexOf('.')
        return if (lastDot > nameStart) path.substring(0, lastDot) + "." + ext else "$path.$ext"
    }

    // ── 路径 ────────────────────────────────────────────────────

    /**
     * 把模型给的路径规整成一个**相对路径**；不合法时返回 null。
     *
     * 拒绝的情况：绝对路径、`..` 穿越、空、以及归一化器认不出的一切。
     */
    private fun relativePathOrNull(raw: String): String? {
        val trimmed = raw.trim().replace('\\', '/')
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("/")) return null
        if (trimmed.startsWith("~")) return null

        val segments = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return null
        if (segments.isEmpty()) return "" // 根目录

        // 归一化器再兜一道：它对非法字符有更完整的判断
        val joined = segments.joinToString("/")
        return when (PathNormalizer.normalize("/$joined")) {
            is PathNormalizer.Result.Valid -> joined
            is PathNormalizer.Result.Rejected -> null
        }
    }

    /**
     * 写路径：必须落在产出目录下。**不在的话直接拒绝，不"顺手改写"。**
     *
     * ⚠️ 为什么拒绝而不是改写成 `输出/<path>`：
     *    改写会让模型以为它写到了 `报告.md`，而实际上写到了
     *    `输出/报告.md` —— 它下一次 `read_text("报告.md")` 会读不到，
     *    然后困惑、重试、最后放弃。**一个能被理解的拒绝，
     *    比一个静默的善意修正更省事。**
     */
    private fun outputPathOrNull(raw: String, policy: WorkspacePolicy): String? {
        val path = relativePathOrNull(raw) ?: return null
        if (path.isEmpty()) return null // 不能把产出目录本身当文件

        val segments = path.split('/')
        if (segments.first() != policy.outputSubdir) return null
        if (segments.size < 2) return null // 只给了目录名，没有文件名
        return path
    }

    /** `rows` 参数：每行用 `;` 分隔，字段用 `,` 分隔 —— 刻意简单，复杂数据走 CSV 文件。 */
    private fun parseRows(raw: String): List<List<String>> =
        raw.split(';').mapNotNull { line ->
            line.trim().takeIf { it.isNotEmpty() }?.split(',')?.map { it.trim() }
        }
}
