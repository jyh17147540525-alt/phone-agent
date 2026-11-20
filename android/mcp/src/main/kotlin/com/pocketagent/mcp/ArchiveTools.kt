package com.pocketagent.mcp

import com.pocketagent.filelogic.ArchiveLimits
import com.pocketagent.filelogic.ZipReader
import com.pocketagent.filelogic.ZipSafety
import com.pocketagent.filelogic.ZipWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 压缩包：`zip_list` / `zip_create` / `zip_extract`。
 *
 * ⚠️ 这三个工具都在**工作区**（应用自己的 `linux/work`，也是 root 容器的 `/work`）
 *    里干活，不碰 SAF 选中的那个目录 —— 那边一次只能拿一个 document，
 *    塞几十个文件进去会慢到没法用。
 *
 * ⚠️ 解包是**最容易出安全事故**的一个：压缩包里写 `../../databases/app.db`
 *    是完全合法的 zip。安全边界全在 `:filelogic` 的 [ZipSafety] 与
 *    [ArchiveLimits] 里（离线可测），这里只负责"读出来、落下去、说清楚"。
 */
interface ArchivePort {

    /** 读一个工作区文件。不存在时抛异常（由工具转成人话）。 */
    suspend fun read(relativePath: String): ByteArray

    /** 写一个工作区文件（父目录不存在就建）。 */
    suspend fun write(relativePath: String, bytes: ByteArray)

    /**
     * 收集要打包的内容：传文件就取它本身，传目录就递归取里面所有文件。
     * 返回的 key 是**相对工作区**的路径。
     */
    suspend fun collect(relativePaths: List<String>): List<Pair<String, ByteArray>>

    /** 工作区里已有哪些文件（只用于"打完包回一句有几个"）。 */
    suspend fun countUnder(relativeDir: String): Int
}

class ZipListTool(private val port: ArchivePort) : McpTool {

    override val name: String = "zip_list"

    override val description: String =
        "看一眼压缩包里有什么（名字、大小、是不是目录），不解压、不落盘。适合先看清再决定解哪个。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("zip") {
                put("type", "string")
                put("description", "工作区里的压缩包路径，例如 输出/资料.zip")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("zip")) }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val zip = args["zip"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (zip.isEmpty()) return ToolOutcome.Failed("要给我压缩包的路径（工作区里的相对路径，例如 输出/资料.zip）。")
        val bytes = runCatching { port.read(zip) }.getOrElse {
            return ToolOutcome.Failed("工作区里没有「$zip」，先看看路径对不对。")
        }
        if (bytes.isEmpty()) return ToolOutcome.Failed("「$zip」是个空文件，不是有效的压缩包。")
        val entries = runCatching { ZipReader.list(bytes, limit = 300) }.getOrElse {
            return ToolOutcome.Failed("这个文件不是 zip 格式（或者已经损坏），解不开。")
        }
        if (entries.isEmpty()) return ToolOutcome.Success("「$zip」里什么都没有。")
        val lines = entries.joinToString("\n") { e ->
            val kind = if (e.isDirectory) "目录" else "${e.size} 字节"
            "· ${e.name}（$kind）"
        }
        return ToolOutcome.Success("「$zip」里有 ${entries.size} 项：\n$lines")
    }
}

class ZipCreateTool(private val port: ArchivePort) : McpTool {

    override val name: String = "zip_create"

    override val description: String =
        "把工作区里的文件或整个目录打成一个 zip。只打包已有文件，不会凭空造内容。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("zip") {
                put("type", "string")
                put("description", "打出来的压缩包路径，例如 输出/资料.zip"
                )
            }
            putJsonObject("paths") {
                put("type", "array")
                put("items", buildJsonObject { put("type", "string") })
                put("description", "要打包的工作区路径，可以给文件和目录；目录会递归收进去")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("zip"))
            add(JsonPrimitive("paths"))
        }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val zip = args["zip"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (zip.isEmpty()) return ToolOutcome.Failed("要给我压缩包的存放路径，例如 输出/资料.zip。")
        val paths = (args["paths"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (paths.isEmpty()) return ToolOutcome.Failed("要给我至少一个要打包的路径。")
        val entries = runCatching { port.collect(paths) }.getOrElse {
            return ToolOutcome.Failed("这些路径里有的不存在：「${paths.joinToString("、")}」。先列一下目录再挑。")
        }
        if (entries.isEmpty()) return ToolOutcome.Failed("这些路径里没有可打包的文件（空目录打不出东西）。")
        val bytes = runCatching { ZipWriter.create(entries) }.getOrElse { error ->
            return ToolOutcome.Failed("打包失败：${error.message ?: "路径不合规"}")
        }
        runCatching { port.write(zip, bytes) }.getOrElse {
            return ToolOutcome.Failed("写不进「$zip」，检查一下这个位置能不能写。")
        }
        val total = entries.sumOf { it.second.size }
        return ToolOutcome.Success("打包好了：$zip（${entries.size} 个文件，原始 $total 字节，压缩后 ${bytes.size} 字节）。")
    }
}

class ZipExtractTool(
    private val port: ArchivePort,
    private val limits: ArchiveLimits = ArchiveLimits(),
) : McpTool {

    override val name: String = "zip_extract"

    override val description: String =
        "把 zip 解到工作区的一个目录里。路径穿越、超大文件、条目过多都会被拦下并说明原因。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("zip") {
                put("type", "string")
                put("description", "工作区里的压缩包路径")
            }
            putJsonObject("to") {
                put("type", "string")
                put("description", "解到哪个目录（相对工作区），不给就用压缩包同名的目录"
                )
            }
        }
        putJsonArray("required") { add(JsonPrimitive("zip")) }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val zip = args["zip"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (zip.isEmpty()) return ToolOutcome.Failed("要给我压缩包的路径。")
        val bytes = runCatching { port.read(zip) }.getOrElse {
            return ToolOutcome.Failed("工作区里没有「$zip」。")
        }
        val target = args["to"]?.jsonPrimitive?.contentOrNullSafe()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: zip.substringBeforeLast('.', zip).substringAfterLast('/')
        if (ZipSafety.normalize(target) == null) {
            return ToolOutcome.Failed("解压目录「$target」不合规（不能是绝对路径或带 ..）。")
        }
        // ★ 先看魔数：任意字节喂给 ZipInputStream 都只是"读不到条目"，
        //   会退化成"这个压缩包是空的"，用户以为文件是空的而不是坏的。
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) {
            return ToolOutcome.Failed("这个文件不是 zip 格式（或者已经损坏），解不开。")
        }
        val result = runCatching { ZipReader.extract(bytes, limits) }.getOrElse {
            return ToolOutcome.Failed("这个文件不是 zip 格式（或者已经损坏），解不开。")
        }
        if (result.files.isEmpty()) {
            return ToolOutcome.Failed(result.summary())
        }
        var written = 0
        val failed = ArrayList<String>()
        for ((name, content) in result.files) {
            val relative = "$target/$name"
            if (!ZipSafety.isInside(target, relative)) {
                failed += "$name（会写到目标目录外面）"
                continue
            }
            runCatching { port.write(relative, content) }.onSuccess { written++ }
                .onFailure { failed += "$name（写不进去）" }
        }
        val head = "解到 $target/：写了 $written 个文件，共 ${result.totalBytes} 字节。"
        val skip = if (result.skipped.isEmpty() && failed.isEmpty()) {
            ""
        } else {
            val all = result.skipped + failed
            "\n有 ${all.size} 项没写：" + all.take(5).joinToString("；")
        }
        return if (written == 0) ToolOutcome.Failed(head + skip) else ToolOutcome.Success(head + skip)
    }
}

/** `JsonPrimitive.contentOrNull` 在 `JsonNull` 上会给出字面量 "null"，必须自己判类型。 */
private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is kotlinx.serialization.json.JsonNull) null else content
