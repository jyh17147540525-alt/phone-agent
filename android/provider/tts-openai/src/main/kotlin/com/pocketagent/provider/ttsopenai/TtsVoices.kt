package com.pocketagent.provider.ttsopenai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 账号里能用的一个音色。
 *
 * @param id 真正的 voice_id —— 只有它才能放进 `/v1/text-to-speech/{voice_id}`。
 *   ⚠️ 界面上的「名字」**不能**当 id 用：Breeze 会回 404 `Voice not found.`
 *   （2026-11-20 真机撞过，用户配的是"月夜低语者"这种显示名）。
 */
data class TtsVoice(
    val id: String,
    val name: String,
    val note: String = "",
)

/**
 * `GET /v1/voices` 的响应解析。
 *
 * ★ 解析失败返回空列表，由界面说"没取到" —— 绝不编一个假音色出来：
 *   用户点了一个不存在的音色，比列表为空更糟。
 */
object TtsVoices {

    /** 一次最多列这么多：手机屏幕 + 手指，再多就是受罪。 */
    const val MAX_SHOWN = 60

    fun parse(json: String, limit: Int = MAX_SHOWN): List<TtsVoice> {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return emptyList()
        val array = root["voices"] as? JsonArray ?: return emptyList()
        val out = ArrayList<TtsVoice>(array.size)
        for (element in array) {
            val obj = element as? JsonObject ?: continue
            val id = (obj["voice_id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty()) continue
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty().ifEmpty { id }
            out += TtsVoice(id = id, name = name, note = noteOf(obj))
            if (out.size >= limit) break
        }
        return out
    }

    private fun noteOf(obj: JsonObject): String {
        val bits = ArrayList<String>(3)
        (obj["language_code"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { bits += it }
        (obj["gender"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { bits += it }
        (obj["origin"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { bits += it }
        return bits.joinToString(" · ")
    }

    /**
     * 这个字符串能不能当 voice_id 用。
     *
     * ★ 只认 ASCII 的紧凑 id（Breeze 的 UUID / `voc_…`、OpenAI 的 `alloy`）。
     *   带空格、中文、标点的都是"给人看的名字"，发过去必然 404。
     */
    fun looksLikeId(raw: String?): Boolean {
        val v = raw?.trim().orEmpty()
        if (v.length !in 2..128) return false
        return v.all { it.code in 0x21..0x7E }
    }
}
