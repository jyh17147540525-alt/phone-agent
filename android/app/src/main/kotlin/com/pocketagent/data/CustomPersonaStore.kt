package com.pocketagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketagent.personalogic.CustomPersona
import com.pocketagent.personalogic.PersonaGender
import com.pocketagent.personalogic.PersonaInitiative
import com.pocketagent.personalogic.PersonaInput
import com.pocketagent.personalogic.PersonaVerbosity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/** DataStore 实例 —— 与 `plugin_sources` 同一套模式，进程内单例。 */
private val Context.personaStore: DataStore<Preferences> by preferencesDataStore("custom_persona")

/**
 * **自定义角色存储** —— 让用户填的角色**真的能生效**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 2026-10-25：这个类存在的直接原因是一次自我走查
 * ═══════════════════════════════════════════════════════════════
 *
 * 走查路径「设置 → 角色设定 → 填名字 → 保存」时发现：
 * `PersonaScreen` 的 `onSave` 默认值是 `{}`，而 `AppShell` 调用时
 * **没有传实现**。于是用户填完、点保存 —— **什么都不会发生**。
 *
 * 按钮看起来是能用的，点了也没报错。那正是本项目最忌讳的形态：
 * **不报错、不崩溃，只是安静地什么都不做。**
 *
 * ⚠️ 这也解释了为什么"自定义角色"作为一个功能一直"没开放"：
 *    不是没写 UI，是**UI 和生效路径之间没有线**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么用 DataStore + 手写 JSON，而不是 Room
 * ═══════════════════════════════════════════════════════════════
 *
 * 自定义角色是**单条配置**（用户同一时刻只有一个角色在生效），
 * 用 Room 要开表、写迁移、加 DAO —— 为一条记录付出全表的代价。
 *
 * 而手写 JSON 的代价是：字段增删要自己处理兼容。所以这里：
 *  - 缺字段 → 用默认值（向前兼容）
 *  - 多字段 → 忽略（向后兼容）
 * 两者都靠 `jsonObject[...]` 的 null 检查兜住，不依赖 `@Serializable` 的严格模式。
 */
class CustomPersonaStore(private val context: Context) {

    /**
     * 读当前角色。
     *
     * @return null = 用户还没设过（此时应该用内置默认人格）
     */
    suspend fun load(): CustomPersona? = context.personaStore.data
        .map { prefs -> prefs[KEY]?.let { decode(it) } }
        .first()

    /**
     * 保存（覆盖）。
     *
     * ⚠️ **拒绝写入占位角色**。
     *
     * 这不是洁癖，是一次真实事故的封条：旧版本在构造失败的兜底路径上
     * 会把 `CustomPersona(id="draft", name="（未命名）")` 写进盘，
     * 而用户那边看到的是「刚填的设定全没了」——因为它存的确实是空壳。
     *
     * ⇒ 现在只要 `name` 是空/占位，这里**抛异常**。
     *   `AppShell` 的 `runCatching` 会把它变成界面上的一句失败提示 ——
     *   **宁可报错，也不许安静地存一份假数据**。
     */
    suspend fun save(persona: CustomPersona) {
        require(!PersonaInput.isPlaceholder(persona.name)) {
            "拒绝保存没有名字的角色（占位「${persona.name}」不是真数据）"
        }
        context.personaStore.edit { prefs ->
            prefs[KEY] = encode(persona)
        }
    }

    /** 清除 —— 回到内置默认人格。 */
    suspend fun clear() {
        context.personaStore.edit { prefs -> prefs.remove(KEY) }
    }

    // ── 序列化 ────────────────────────────────────────────────

    private fun encode(p: CustomPersona): String = buildJsonObject {
        put("id", p.id)
        put("name", p.name)
        put("identity", p.identity)
        put("addressUser", p.addressUser)
        put("addressSelf", p.addressSelf)
        put("relationTo", p.relationTo)
        put("dialect", p.dialect)
        put("gender", p.gender.name)
        put("avatarRes", p.avatarRes)
        put("catchphrases", buildJsonArray { p.catchphrases.forEach { add(JsonPrimitive(it)) } })
        put("memories", buildJsonArray { p.memories.forEach { add(JsonPrimitive(it)) } })
        // ── 立体度的第二组（2026-10-28）────────────────────────
        put("flawsRaw", buildJsonArray { p.flawsRaw.forEach { add(JsonPrimitive(it)) } })
        put("verbosity", p.verbosity.name)
        put("dailyLife", p.dailyLife)
        put("interests", buildJsonArray { p.interests.forEach { add(JsonPrimitive(it)) } })
        put("taboos", buildJsonArray { p.taboos.forEach { add(JsonPrimitive(it)) } })
        put("sharedReferences", buildJsonArray { p.sharedReferences.forEach { add(JsonPrimitive(it)) } })
        put("initiative", p.initiative.name)
        put("sleepHour", p.sleepHour)
        put("wakeHour", p.wakeHour)
    }.toString()

    /**
     * 解码。
     *
     * ⚠️ 每个字段都走 `?: 默认值`，**任何一个字段缺失都不该让整条记录失效**。
     *    否则将来加字段时，老用户的角色会在升级后凭空消失 ——
     *    而那对一个"用逝者名字命名"的角色来说是很重的打击。
     */
    private fun decode(raw: String): CustomPersona? = runCatching {
        val o = Json.parseToJsonElement(raw).jsonObject
        val name = o["name"]?.jsonPrimitive?.content ?: return null
        // ══════════════════════════════════════════════════════════
        //  ★ 自愈：盘上的"占位空壳"当作没设过
        // ══════════════════════════════════════════════════════════
        // 旧版本曾把 `{"id":"draft","name":"（未命名）",…}` 写进盘
        // （构造失败 → 兜底壳 → 被保存）。那条记录**不是用户的设定**，
        // 但它非空，所以以前会被当成一个正常的空角色读回来 ——
        // 于是每次启动都"有一个什么都没填的角色"，
        // 用户以为自己的设定被清空了。
        //
        // ⇒ 认出来就返回 null，回落到内置人格 + 空表单。
        if (PersonaInput.isPlaceholder(name)) return null
        CustomPersona(
            id = o["id"]?.jsonPrimitive?.content ?: "custom",
            name = name,
            identity = o["identity"]?.jsonPrimitive?.content.orEmpty(),
            addressUser = o["addressUser"]?.jsonPrimitive?.content.orEmpty(),
            addressSelf = o["addressSelf"]?.jsonPrimitive?.content.orEmpty(),
            relationTo = o["relationTo"]?.jsonPrimitive?.content.orEmpty(),
            dialect = o["dialect"]?.jsonPrimitive?.content.orEmpty(),
            gender = o["gender"]?.jsonPrimitive?.content
                ?.let { n -> PersonaGender.entries.firstOrNull { it.name == n } }
                ?: PersonaGender.UNSPECIFIED,
            avatarRes = o["avatarRes"]?.jsonPrimitive?.content,
            catchphrases = o["catchphrases"]?.stringList().orEmpty(),
            memories = o["memories"]?.stringList().orEmpty(),
            // ── 立体度的第二组（2026-10-28）────────────────────
            // ⚠️ 每个都带默认值：老用户存的是没有这些字段的 JSON，
            //    缺字段**必须**退回默认而不是整条失效。
            flawsRaw = o["flawsRaw"]?.stringList().orEmpty(),
            verbosity = o["verbosity"]?.jsonPrimitive?.content
                ?.let { n -> PersonaVerbosity.entries.firstOrNull { it.name == n } }
                ?: PersonaVerbosity.NORMAL,
            dailyLife = o["dailyLife"]?.jsonPrimitive?.content.orEmpty(),
            interests = o["interests"]?.stringList().orEmpty(),
            taboos = o["taboos"]?.stringList().orEmpty(),
            sharedReferences = o["sharedReferences"]?.stringList().orEmpty(),
            initiative = o["initiative"]?.jsonPrimitive?.content
                ?.let { n -> PersonaInitiative.entries.firstOrNull { it.name == n } }
                ?: PersonaInitiative.NORMAL,
            sleepHour = o["sleepHour"]?.jsonPrimitive?.content?.toIntOrNull() ?: 23,
            wakeHour = o["wakeHour"]?.jsonPrimitive?.content?.toIntOrNull() ?: 7,
        )
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.stringList(): List<String> =
        runCatching { jsonArray.mapNotNull { it.jsonPrimitive.content } }.getOrDefault(emptyList())

    private companion object {
        val KEY = stringPreferencesKey("custom_persona_json")
    }
}
