package com.pocketagent.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Linux 工作区：**能不能用** + **跑一条命令** + **读一个文件**。
 *
 * ## 为什么端口在 `:mcp`，实现放 `:app`
 *
 * 与 `SystemOpsPort` 同一套理由：`:mcp` 只描述"要什么"，不关心底下是 proot、chroot
 * 还是将来的别的沙箱。换实现只换一个类，工具与提示词都不用动。
 *
 * ## 三种失败必须分开
 *
 * - [LinuxOutcome.Rejected]：**参数问题**（路径越界、命令不在白名单）—— 改写法有用。
 * - [LinuxOutcome.NotReady]：**环境问题**（没装、要下载、proot 被拦）—— 改写法没用，
 *   要说清"缺什么、怎么补"。
 * - [LinuxOutcome.Failed]：**执行问题**（退出码非零、超时）—— 原样带回。
 *
 * 合并成一句"失败"的后果：用户对着一个装不上的环境反复改命令。
 */
interface LinuxOpsPort {

    /** 这台机器现在的状态。工具用它决定"先拒还是先跑"。 */
    suspend fun availability(): LinuxAvailability

    /** 跑一条命令。参数校验由实现方负责（它才知道 `/work` 的真实路径）。 */
    suspend fun run(argv: List<String>, timeoutSec: Int): LinuxRunResult

    /** 读回一个文件（实现方负责确认它真在 `/work` 里）。 */
    suspend fun read(path: String, maxBytes: Int): LinuxReadResult
}

/** 给工具看的可用性。`explain` 是给用户的整句话，不是堆栈。 */
data class LinuxAvailability(val ready: Boolean, val explain: String)

data class LinuxRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
)

data class LinuxReadResult(val bytes: ByteArray, val truncated: Boolean) {
    // ⚠️ data class 带 ByteArray 必须手写 equals/hashCode（同 ToolOutcome.Success）
    override fun equals(other: Any?): Boolean =
        this === other || (other is LinuxReadResult && bytes.contentEquals(other.bytes) && truncated == other.truncated)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + truncated.hashCode()
}

sealed interface LinuxOutcome {
    /** 参数问题。 */
    data class Rejected(val reason: String) : LinuxOutcome

    /** 环境不可用。 */
    data class NotReady(val explain: String) : LinuxOutcome

    /** 执行问题。 */
    data class Failed(val reason: String) : LinuxOutcome
}

/** `linux_exec`：跑一条命令。 */
class LinuxExecTool(
    private val ops: LinuxOpsPort,
) : McpTool {

    override val name: String = "linux_exec"

    override val description: String =
        "在一个隔离的 Linux 工作区里跑一条命令（python3 / jq / ffmpeg / git / 文本工具）。" +
            "只有 /work 能写，默认不联网，不提供 shell 语法（传数组，不是一整串）。" +
            "第一次用会先问用户一次。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("argv") {
                put("type", "array")
                put("description", "命令与参数，例如 [\"python3\",\"-c\",\"print(1)\"]。不是一整串字符串。")
                putJsonArray("items") { add(JsonPrimitive("string")) }
            }
            putJsonObject("timeoutSec") {
                put("type", "integer")
                put("description", "超时秒数，1–60，默认 30")
            }
            putJsonObject("purpose") {
                put("type", "string")
                put("description", "这条命令是干什么的（必填，进审计）")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("argv"))
            add(JsonPrimitive("purpose"))
        }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        if (arguments["script"] != null) {
            // M1 刻意不提供 script：`bash -lc` 会绕过首词白名单，等于把"不联网"变成摆设
            return ToolOutcome.Failed("这个版本不支持 script（整段脚本）。请拆成 argv 形式的单条命令。")
        }

        val argv = (arguments["argv"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }
            .orEmpty()
        if (argv.isEmpty()) {
            return ToolOutcome.Failed("argv 不能为空：要传数组，例如 [\"python3\",\"-c\",\"print(1)\"]。")
        }
        if (argv.size > MAX_ARGV) {
            return ToolOutcome.Failed("参数太多（${argv.size} 个），一次最多 $MAX_ARGV 个。")
        }

        val readiness = ops.availability()
        if (!readiness.ready) return ToolOutcome.Refused(readiness.explain)

        val timeout = clamp(arguments["timeoutSec"].intOrNull(), default = DEFAULT_TIMEOUT_SEC, min = 1, max = 60)
        val result = runCatching { ops.run(argv, timeout) }.getOrElse { error ->
            return ToolOutcome.Failed("命令没有跑起来（${error.javaClass.simpleName}）。")
        }

        val text = buildString {
            append("exitCode=").append(result.exitCode)
            append("（").append(result.durationMs).append("ms）")
            if (result.stdout.isNotBlank()) {
                append("\n--- stdout ---\n").append(truncate(result.stdout, MAX_STDOUT))
            }
            if (result.stderr.isNotBlank()) {
                append("\n--- stderr ---\n").append(truncate(result.stderr, MAX_STDERR))
            }
            if (result.stdout.length > MAX_STDOUT || result.stderr.length > MAX_STDERR) {
                append("\n（输出已截断：要看细节请把结果写进 /work，再用 linux_read 读）")
            }
        }
        return ToolOutcome.Success(
            text = text,
            structured = buildJsonObject {
                put("exitCode", result.exitCode)
                put("durationMs", result.durationMs)
            },
        )
    }

    private companion object {
        const val MAX_ARGV = 32
        const val DEFAULT_TIMEOUT_SEC = 30
        const val MAX_STDOUT = 4_000
        const val MAX_STDERR = 2_000
    }
}

/** `linux_read`：只读 `/work` 里的文件。 */
class LinuxReadTool(
    private val ops: LinuxOpsPort,
) : McpTool {

    override val name: String = "linux_read"

    override val description: String =
        "读取 Linux 工作区（/work）里的一个文件。只能读 /work 下的路径；二进制只回大小与类型。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") {
                put("type", "string")
                put("description", "必须是 /work 下的绝对路径，例如 /work/report.csv")
            }
            putJsonObject("maxBytes") {
                put("type", "integer")
                put("description", "最多读多少字节，默认 65536")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("path")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val path = (arguments["path"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
        if (path.isBlank()) return ToolOutcome.Failed("path 不能为空，例如 /work/report.csv。")
        if (!path.startsWith("/work/")) {
            return ToolOutcome.Failed("只能读 /work 下的文件，收到的是「$path」。")
        }

        val readiness = ops.availability()
        if (!readiness.ready) return ToolOutcome.Refused(readiness.explain)

        val maxBytes = clamp(arguments["maxBytes"].intOrNull(), default = DEFAULT_MAX_BYTES, min = 1, max = MAX_BYTES)
        val result = runCatching { ops.read(path, maxBytes) }.getOrElse { error ->
            return ToolOutcome.Failed("读不到「$path」（${error.javaClass.simpleName}）。")
        }

        val looksBinary = result.bytes.any { it == 0.toByte() }
        return if (looksBinary) {
            ToolOutcome.Success(
                "「$path」是二进制（${result.bytes.size} 字节${if (result.truncated) "，已截断" else ""}）。" +
                    "这里不回灌字节：要处理它就用 linux_exec 跑一条命令（例如 file 或 ffprobe）。",
            )
        } else {
            ToolOutcome.Success(
                text = String(result.bytes, Charsets.UTF_8) + if (result.truncated) "\n（已截断）" else "",
            )
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 64 * 1024
        const val MAX_BYTES = 512 * 1024
    }
}

/** 夹取参数（工具与测试共用，避免两处各写一遍）。 */
internal fun clamp(value: Int?, default: Int, min: Int, max: Int): Int =
    (value ?: default).coerceIn(min, max)

private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun truncate(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "\n…"
