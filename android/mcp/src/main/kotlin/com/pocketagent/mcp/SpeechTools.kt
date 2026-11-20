package com.pocketagent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 听写：把用户说的一句话转成文字（`listen`）。
 *
 * 与 `speak` 对称：朗读是"说给用户听"，听写是"听用户说"。两条都不联网 ——
 * 用的是手机自带的识别引擎，音频不出本机（Google 的识别器可能走云端，
 * 但那是系统行为，应用本身不上传，这一点在失败文案里说清楚，别让人误会）。
 *
 * 为什么不像别的工具那样一调就返回：
 * - 识别要**人说话**，天然是几秒钟的事，所以 `maxMillis` 是硬上限（默认 8 秒）。
 * - 用户可能什么都没说 —— 那不是错误，要如实回"没听到"而不是编一句话。
 */
interface AsrPort {
    /**
     * 听一次，返回识别到的文字。
     *
     * 返回空串表示**没听到**（安静、太快结束、或者用户取消了）。
     * 抛异常表示**听不了**（没权限、没有识别引擎、引擎报错）。
     */
    suspend fun listen(maxMillis: Long): String
}

class ListenTool(private val port: AsrPort) : McpTool {

    override val name: String = "listen"

    override val description: String =
        "打开麦克风听用户说一句，把语音转成文字。最多等 ${MAX_LISTEN_MS / 1000} 秒。" +
            "只在用户明确说「你听我说」「语音输入」时才用 —— 别自己主动开麦克风。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("seconds") {
                put("type", "number")
                put("description", "最多听几秒，默认 ${DEFAULT_SECONDS}，上限 ${MAX_LISTEN_MS / 1000}")
            }
        }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val seconds = args["seconds"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.toDoubleOrNull()
        val maxMillis = when {
            seconds == null -> DEFAULT_SECONDS * 1000L
            seconds <= 0 -> return ToolOutcome.Failed("听几秒得是正数，收到的是 $seconds。")
            else -> (seconds * 1000).toLong().coerceAtMost(MAX_LISTEN_MS)
        }
        val text = runCatching { port.listen(maxMillis) }.getOrElse { error ->
            return ToolOutcome.Failed("听不成：${error.message ?: "麦克风或识别引擎没准备好"}")
        }.trim()
        if (text.isEmpty()) {
            return ToolOutcome.Failed("没听到内容（可能太安静，或者你没说话）。可以再试一次，或者直接打字给我。")
        }
        return ToolOutcome.Success("听到的是：「$text」")
    }

    private companion object {
        const val DEFAULT_SECONDS = 8L
        const val MAX_LISTEN_MS = 20_000L
    }
}
