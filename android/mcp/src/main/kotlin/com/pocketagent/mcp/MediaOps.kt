package com.pocketagent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 媒体能力（抽音轨 / 转码 / 截帧 / 看信息）。
 *
 * ## 为什么不是 ffmpeg
 *
 * ffmpeg-kit 已停维、Android 二进制下架。Media3 能做的比 ffmpeg 少得多，所以这里
 * **只暴露三件真能做到的事**，并且不提供"随便写参数"的口子 ——
 * 提供一个像 ffmpeg 的壳、运行时却一半参数不支持，比不做更糟。
 *
 * ## 端口在这里，实现放 `:app`
 *
 * 与 `LinuxOpsPort` 同一套理由：`:mcp` 只描述要什么。换实现（Media3 → 将来的别的）只换一个类。
 */
interface MediaOpsPort {

    suspend fun availability(): MediaAvailability

    suspend fun probe(input: String): MediaResult

    suspend fun extractAudio(input: String, output: String): MediaResult

    suspend fun transcode(input: String, output: String): MediaResult

    suspend fun snapshot(input: String, atMs: Long, output: String): MediaResult
}

data class MediaAvailability(val ready: Boolean, val explain: String)

/** `info` 里的键是中性的（durationMs / width / height …），不暴露 Media3 的类型名。 */
data class MediaResult(val ok: Boolean, val detail: String, val info: Map<String, String> = emptyMap())

/** `media_ops`：一个工具做四件事，靠 `action` 区分。 */
class MediaOpsTool(
    private val ops: MediaOpsPort,
) : McpTool {

    override val name: String = "media_ops"

    override val description: String =
        "处理音视频：probe（看时长/分辨率）、extract_audio（抽音轨为 m4a）、" +
            "transcode（统一成 mp4/webm）、snapshot（截一帧为 png）。" +
            "输入输出都必须在 /work 下；不支持的参数会直接说明，不会静默忽略。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "probe / extract_audio / transcode / snapshot")
                putJsonArray("enum") {
                    add(JsonPrimitive("probe"))
                    add(JsonPrimitive("extract_audio"))
                    add(JsonPrimitive("transcode"))
                    add(JsonPrimitive("snapshot"))
                }
            }
            putJsonObject("input") {
                put("type", "string")
                put("description", "输入文件，必须是 /work 下的绝对路径")
            }
            putJsonObject("output") {
                put("type", "string")
                put("description", "输出文件（可省；省了按动作自动生成同名的 m4a/mp4/png）")
            }
            putJsonObject("atMs") {
                put("type", "integer")
                put("description", "snapshot 用：第几毫秒，默认 0")
            }
            putJsonObject("purpose") {
                put("type", "string")
                put("description", "这一步是干什么的（进审计）")
            }
        }
        putJsonArray("required") {
            add(JsonPrimitive("action"))
            add(JsonPrimitive("input"))
        }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val action = arguments.string("action")
        val input = arguments.string("input")
        if (action.isNullOrBlank()) return ToolOutcome.Failed("action 不能为空：probe / extract_audio / transcode / snapshot。")
        if (input.isNullOrBlank()) return ToolOutcome.Failed("input 不能为空，例如 /work/a.mp4。")
        if (!input.startsWith("/work/")) {
            return ToolOutcome.Failed("输入必须是 /work 下的绝对路径，收到的是「$input」。")
        }

        val readiness = ops.availability()
        if (!readiness.ready) return ToolOutcome.Refused(readiness.explain)

        val output = arguments.string("output")
        val atMs = (arguments["atMs"] as? JsonPrimitive)?.longOrNull ?: 0L

        val result = runCatching {
            when (action) {
                "probe" -> ops.probe(input)
                "extract_audio" -> ops.extractAudio(input, output.orEmpty())
                "transcode" -> ops.transcode(input, output.orEmpty())
                "snapshot" -> ops.snapshot(input, atMs, output.orEmpty())
                else -> MediaResult(false, "不认得这个动作「$action」：只有 probe / extract_audio / transcode / snapshot。")
            }
        }.getOrElse { error ->
            return ToolOutcome.Failed("媒体处理没有跑起来（${error.javaClass.simpleName}）。")
        }

        return if (result.ok) {
            ToolOutcome.Success(
                text = result.detail.ifBlank { "已处理：$input" },
                structured = buildJsonObject {
                    result.info.forEach { (k, v) -> put(k, v) }
                },
            )
        } else {
            // 参数/格式问题：换写法可能有用，所以是 Failed 而不是 Refused
            ToolOutcome.Failed(result.detail.ifBlank { "媒体处理失败。" })
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
