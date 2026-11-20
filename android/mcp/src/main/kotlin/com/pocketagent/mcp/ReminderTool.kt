package com.pocketagent.mcp

import com.pocketagent.filelogic.Reminder
import com.pocketagent.filelogic.ReminderTime
import com.pocketagent.filelogic.Reminders
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 提醒与朗读。
 *
 * 提醒的时间换算在 `:filelogic` 的 [Reminders]（纯函数、可测），
 * 真正让手机响的是 `:app` 的 AlarmManager —— 这里夹住三条：
 * 文本不能为空、一次最多 [Reminders.MAX_PENDING] 条、取消要能报"没这条"。
 */
interface ReminderPort {
    suspend fun schedule(text: String, atMillis: Long): String
    suspend fun pending(): List<Reminder>
    suspend fun cancel(id: String): Boolean
}

interface TtsPort {
    /** 念一段话。返回引擎名或空串（表示用系统默认引擎）。 */
    suspend fun speak(text: String): String
}

class RemindTool(private val port: ReminderPort) : McpTool {

    override val name: String = "remind"

    override val description: String =
        "设一个到点会弹通知的提醒，也可以列出或取消已有的提醒。时间写法：09:00（下一次）、" +
            "2026-11-21 09:00、+30m。一次最多 ${Reminders.MAX_PENDING} 条。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("enum", JsonArray(listOf(JsonPrimitive("set"), JsonPrimitive("list"), JsonPrimitive("cancel"))))
                put("description", "set 新增 / list 列出 / cancel 取消，默认 set")
            }
            putJsonObject("text") { put("type", "string"); put("description", "提醒内容（action=set 必填）") }
            putJsonObject("at") { put("type", "string"); put("description", "什么时候，例如 09:00 或 +30m") }
            putJsonObject("id") { put("type", "string"); put("description", "要取消的提醒 id（action=cancel 必填）") }
        }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val action = args["action"]?.jsonPrimitive?.contentOrNullSafe()?.trim()?.lowercase().orEmpty()
        return when (action) {
            "", "set", "add", "新增" -> set(args)
            "list", "列出" -> list()
            "cancel", "取消", "delete" -> cancel(args)
            else -> ToolOutcome.Failed("action 只认 set / list / cancel，收到的是「$action」。")
        }
    }

    private suspend fun set(args: JsonObject): ToolOutcome {
        val text = args["text"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (text.isEmpty()) return ToolOutcome.Failed("要提醒什么？给我一句话。")
        if (text.length > MAX_TEXT) return ToolOutcome.Failed("提醒内容太长了（最多 $MAX_TEXT 字）。")
        val raw = args["at"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (raw.isEmpty()) return ToolOutcome.Failed("要在什么时候提醒？例如 09:00 或 +30m。")

        val existing = port.pending()
        if (existing.size >= Reminders.MAX_PENDING) {
            return ToolOutcome.Failed("已经有 ${existing.size} 条提醒了（上限 ${Reminders.MAX_PENDING}），先取消几条再设。")
        }

        val now = System.currentTimeMillis()
        val parsed = Reminders.parse(raw, now)
        if (parsed is ReminderTime.Bad) return ToolOutcome.Failed(parsed.reason)
        val atMillis = (parsed as ReminderTime.Ok).atMillis
        val id = runCatching { port.schedule(text, atMillis) }.getOrElse { error ->
            return ToolOutcome.Failed("排提醒失败：${error.message ?: "系统拒绝了这条定时"}")
        }
        return ToolOutcome.Success(
            "设好了（id=$id）：${Reminders.describe(atMillis, now)} —— $text",
        )
    }

    private suspend fun list(): ToolOutcome {
        val pending = port.pending().sortedBy { it.atMillis }
        if (pending.isEmpty()) return ToolOutcome.Success("现在没有待响的提醒。")
        val now = System.currentTimeMillis()
        val lines = pending.joinToString("\n") { r ->
            "· ${Reminders.describe(r.atMillis, now)}  ${r.text}  (id=${r.id})"
        }
        return ToolOutcome.Success("有 ${pending.size} 条提醒：\n$lines")
    }

    private suspend fun cancel(args: JsonObject): ToolOutcome {
        val id = args["id"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (id.isEmpty()) return ToolOutcome.Failed("要取消哪一条？给我它的 id（先 action=list 看一眼）。")
        val ok = runCatching { port.cancel(id) }.getOrElse { false }
        return if (ok) {
            ToolOutcome.Success("取消了（id=$id）。")
        } else {
            ToolOutcome.Failed("没有 id=$id 这条提醒，可能已经响过了。")
        }
    }

    private companion object {
        const val MAX_TEXT = 200
    }
}

class SpeakTool(private val tts: TtsPort) : McpTool {

    override val name: String = "speak"

    override val description: String =
        "把一段话用手机自带的语音引擎念出来（离线，不上传）。适合用户说\"念给我听\"或要做语音提示时。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("text") { put("type", "string"); put("description", "要念的内容，最多 $MAX_TEXT 字") }
        }
        putJsonArray("required") { add(JsonPrimitive("text")) }
    }

    override suspend fun call(args: JsonObject): ToolOutcome {
        val text = args["text"]?.jsonPrimitive?.contentOrNullSafe()?.trim().orEmpty()
        if (text.isEmpty()) return ToolOutcome.Failed("要念什么？给我一句话。")
        if (text.length > MAX_TEXT) {
            return ToolOutcome.Failed("一次最多念 $MAX_TEXT 字，长了会被系统截断；先念重点。")
        }
        val engine = runCatching { tts.speak(text) }.getOrElse { error ->
            return ToolOutcome.Failed("念不出来：${error.message ?: "这台手机可能没装语音引擎"}")
        }
        val engineNote = if (engine.isBlank()) "系统默认引擎" else engine
        return ToolOutcome.Success("开始念了（$engineNote，${text.length} 字）。")
    }

    companion object {
        const val MAX_TEXT = 500
    }
}
