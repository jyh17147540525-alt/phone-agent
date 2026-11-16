package com.pocketagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketagent.personalogic.ChatStyle
import com.pocketagent.personalogic.LivingWith
import com.pocketagent.personalogic.UserAgeBand
import com.pocketagent.personalogic.UserGender
import com.pocketagent.personalogic.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 独立的 DataStore：与角色设定（`custom_persona`）分开，各存各的。 */
private val Context.userProfileStore: DataStore<Preferences> by preferencesDataStore("user_profile")

/**
 * **「关于你」那一页的存储。**
 *
 * 与 [CustomPersonaStore] 同一套做法，理由也一样：这是**单条配置**
 * （一个用户只有一份自我介绍），用 Room 要开表、写迁移、加 DAO，
 * 为一条记录付出全表的代价。
 *
 * ⚠️ 手写 JSON 的代价是字段增删要自己兜住，所以：
 *  - 缺字段 → 默认值（老版本升级上来不会丢整条档案）
 *  - 多字段 → 忽略（降级安装不会崩）
 *
 * ⚠️ 这里**没有** `require` 拦占位值。角色的名字必须拦（占位壳等于
 * "设定全没了"），档案不一样：用户可能只想填一句雷区就保存，
 * 那一份就是**有效**的。空档案在读回时自然被 [UserProfile.promptSection]
 * 拼成空串，不会污染提示词。
 */
class UserProfileStore(private val context: Context) {

    /** @return null = 用户还没填过（此时提示词里一个字都不加） */
    suspend fun load(): UserProfile? = context.userProfileStore.data
        .map { prefs -> prefs[KEY]?.let { decode(it) } }
        .first()

    suspend fun save(profile: UserProfile) {
        context.userProfileStore.edit { prefs ->
            prefs[KEY] = encode(profile)
        }
    }

    /** 清空 —— 回到"他还不了解你"。 */
    suspend fun clear() {
        context.userProfileStore.edit { prefs -> prefs.remove(KEY) }
    }

    // ── 序列化 ────────────────────────────────────────────────

    private fun encode(p: UserProfile): String = buildJsonObject {
        put("callName", p.callName)
        put("gender", p.gender.name)
        put("ageBand", p.ageBand.name)
        put("livingWith", p.livingWith.name)
        put("work", p.work)
        put("nowBusy", p.nowBusy)
        put("chatStyle", p.chatStyle.name)
        put("likes", buildJsonArray { p.likes.forEach { add(JsonPrimitive(it)) } })
        put("dislikes", buildJsonArray { p.dislikes.forEach { add(JsonPrimitive(it)) } })
        put("taboos", buildJsonArray { p.taboos.forEach { add(JsonPrimitive(it)) } })
        put("bodyNotes", p.bodyNotes)
        // 没填就是空串，不是 0 —— 0 点是有效时间，不能拿它当"未填"。
        put("sleepHour", p.sleepHour?.toString().orEmpty())
        put("wakeHour", p.wakeHour?.toString().orEmpty())
        put("knownFacts", buildJsonArray { p.knownFacts.forEach { add(JsonPrimitive(it)) } })
        put("extra", p.extra)
    }.toString()

    private fun decode(raw: String): UserProfile? = runCatching {
        val o = Json.parseToJsonElement(raw).jsonObject
        UserProfile(
            callName = o.str("callName"),
            gender = o.enumOf("gender", UserGender.entries, UserGender.UNSPECIFIED),
            ageBand = o.enumOf("ageBand", UserAgeBand.entries, UserAgeBand.UNSPECIFIED),
            livingWith = o.enumOf("livingWith", LivingWith.entries, LivingWith.UNSPECIFIED),
            work = o.str("work"),
            nowBusy = o.str("nowBusy"),
            chatStyle = o.enumOf("chatStyle", ChatStyle.entries, ChatStyle.UNSPECIFIED),
            likes = o["likes"]?.stringList().orEmpty(),
            dislikes = o["dislikes"]?.stringList().orEmpty(),
            taboos = o["taboos"]?.stringList().orEmpty(),
            bodyNotes = o.str("bodyNotes"),
            sleepHour = o.str("sleepHour").toIntOrNull()?.coerceIn(0, 23),
            wakeHour = o.str("wakeHour").toIntOrNull()?.coerceIn(0, 23),
            knownFacts = o["knownFacts"]?.stringList().orEmpty(),
            extra = o.str("extra"),
        )
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content.orEmpty()

    /** 枚举按**名字**存，不按序号 —— 序号在增删成员后会悄悄指错。 */
    private fun <E : Enum<E>> kotlinx.serialization.json.JsonObject.enumOf(
        key: String,
        entries: List<E>,
        fallback: E,
    ): E {
        val raw = this[key]?.jsonPrimitive?.content ?: return fallback
        return entries.firstOrNull { it.name == raw } ?: fallback
    }

    private fun JsonElement.stringList(): List<String> =
        runCatching { jsonArray.mapNotNull { it.jsonPrimitive.content } }.getOrDefault(emptyList())

    private companion object {
        val KEY = stringPreferencesKey("user_profile_json")
    }
}
