package com.pocketagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pocketagent.plugin.api.MarketCatalog
import com.pocketagent.plugin.api.SubscriptionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber

/**
 * 插件订阅源管理。
 *
 * ═══════════════════════════════════════════════════════════════
 *  「市场」没有服务端
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户订阅的是**若干个静态 JSON 地址**。本类负责：拉取、合并、缓存订阅列表。
 * 合并规则（去重、版本取舍、被拒条目留痕）全在 [MarketCatalog.merge] 里，
 * 那个是纯函数、有单测；这里只做 IO。
 *
 * ⚠️ 一条硬规则：**只接受 https**。
 *    订阅地址是用户自己填的，等于让应用去请求任意 URL。允许 http 的话，
 *    一个中间人就能把整个插件目录换成他自己的 —— 而插件目录里的每个条目
 *    都会显示成"市场来源"。哈希校验防不住这个：哈希也是被篡改的目录给的。
 */
class SubscriptionRepository(
    private val dataStore: DataStore<Preferences>,
    private val http: OkHttpClient,
    private val json: Json,
) {

    /** 用户订阅的源地址，按添加顺序 */
    val sourceUrls: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[KEY_SOURCES]?.toList().orEmpty()
    }

    /**
     * 首次进市场页时补上内置源。
     *
     * ⚠️ 必须尊重 [KEY_BUILTIN_DISMISSED] 标记，**不能只看"列表是不是空的"**。
     *    否则用户删掉内置源之后，下次进市场页它又自己回来了 ——
     *    用户会得出「这应用删不掉东西」的结论，而一个删不掉的东西
     *    会让人怀疑它在后台做了什么。这是个信任问题，不是体验问题。
     */
    suspend fun ensureBuiltinSource() {
        if (dataStore.data.first()[KEY_BUILTIN_DISMISSED] == true) return
        if (sourceUrls.first().isEmpty()) {
            addSource(BUILTIN_SOURCE_URL)
        }
    }

    /**
     * 把内置源加回来。
     *
     * 存在的理由：删内置源必须是个**可逆**操作。用户删掉之后，界面上得有办法
     * 找回来 —— 他不可能记得住那串 URL，也不该被要求记住。
     */
    suspend fun restoreBuiltinSource(): AddResult {
        dataStore.edit { it[KEY_BUILTIN_DISMISSED] = false }
        return addSource(BUILTIN_SOURCE_URL)
    }

    suspend fun addSource(url: String): AddResult {
        val normalized = normalize(url)
            ?: return AddResult.Rejected("订阅地址必须以 https:// 开头。")

        if (sourceUrls.first().any { it.equals(normalized, ignoreCase = true) }) {
            return AddResult.Rejected("这个地址已经订阅过了。")
        }

        dataStore.edit { prefs ->
            prefs[KEY_SOURCES] = (prefs[KEY_SOURCES].orEmpty()) + normalized
            // 手动把内置源加回来时，顺手清掉"已删除"标记 ——
            // 否则下次进市场页它还是不会被自动补上，用户会以为加了没用
            if (normalized.equals(BUILTIN_SOURCE_URL, ignoreCase = true)) {
                prefs[KEY_BUILTIN_DISMISSED] = false
            }
        }
        return AddResult.Added
    }

    suspend fun removeSource(url: String) {
        dataStore.edit { prefs ->
            prefs[KEY_SOURCES] = prefs[KEY_SOURCES].orEmpty() - url
            if (url.equals(BUILTIN_SOURCE_URL, ignoreCase = true)) {
                prefs[KEY_BUILTIN_DISMISSED] = true
            }
        }
    }

    /**
     * 地址规范化。返回 null 表示这个地址不可接受。
     *
     * 去掉末尾斜杠，否则 `https://a.com/x` 和 `https://a.com/x/` 会被当成两个源 ——
     * 用户会看到列表里出现两条一模一样的地址，然后怀疑去重坏了。
     */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        if (trimmed.length <= "https://".length) return null
        return trimmed.trimEnd('/')
    }

    /**
     * 拉取全部订阅源并合并成目录。
     *
     * 单个源失败**不影响其他源** —— [MarketCatalog.merge] 会把失败源记进
     * `sources` 状态里展示给用户，而不是整体失败。这是"没有单点"的设计落到实处。
     */
    suspend fun loadCatalog(): MarketCatalog = withContext(Dispatchers.IO) {
        val urls = sourceUrls.first()
        val loaded: List<Pair<String, SubscriptionSource?>> = urls.map { url ->
            url to fetchSource(url)
        }
        MarketCatalog.merge(loaded)
    }

    private fun fetchSource(url: String): SubscriptionSource? = try {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("订阅源返回 %d：%s", response.code, url)
                null
            } else {
                val body = response.body?.string()
                if (body.isNullOrBlank()) null
                else json.decodeFromString(SubscriptionSource.serializer(), body)
            }
        }
    } catch (e: Exception) {
        // 用户填的地址，什么网络异常都可能。绝不能让它把整个刷新流程带崩
        Timber.w(e, "订阅源加载失败：%s", url)
        null
    }

    sealed interface AddResult {
        data object Added : AddResult
        data class Rejected(val reason: String) : AddResult
    }

    companion object {
        private val KEY_SOURCES = stringSetPreferencesKey("plugin_source_urls")

        /** 用户是否主动删除过内置源。删过就不再自动加回，直到他手动恢复 */
        private val KEY_BUILTIN_DISMISSED = booleanPreferencesKey("builtin_source_dismissed")

        /**
         * 内置官方源。
         *
         * 刻意放在 GitHub Pages 而不是自建服务端 —— 见项目方案第 1 章：
         * 不建自有后端是合规护身符，也是零成本的关键。官方源挂了，
         * 用户还能自己加源，功能不受影响。
         *
         * ⚠️ 这个地址目前**还是占位**，对应仓库尚未建立。用户首次打开市场页
         *    会看到「源加载失败」—— 那是**如实报告**，不是 bug。
         *
         *    正因为如此，订阅源管理界面是必需的而非可选的：没有它，用户面对
         *    一个加载不出来的市场将毫无办法，而"官方源挂了用户还能自己加源"
         *    这句设计承诺也就成了空话。
         */
        const val BUILTIN_SOURCE_URL =
            "https://pocketagent-community.github.io/plugins/index.json"
    }
}

/**
 * 极简手写依赖容器。
 *
 * ⚠️ 为什么不用 Hilt（项目技术栈里明明定了 Hilt）：
 *
 *    Hilt 的价值在于**自动注入已经存在的构造器**。但现在除了插件模块，
 *    其余模块（keymgmt / perception / agent）连接口都还没落，
 *    此时写 `@Module` 等于给不存在的依赖写绑定 —— 一堆空壳，还得跟着改。
 *
 *    M0 阶段先用手写容器把依赖关系**显式**摆出来（这也是一份可读的架构图），
 *    等各模块的构造器就位后再一次性切 Hilt。届时要把 ksp 与 hilt 两个 Gradle
 *    插件**一起**加回来（见 app/build.gradle.kts 的 plugins 块）——
 *    只加插件不加 hilt-compiler 依赖会直接构建失败。
 */
class AppContainer(context: Context) {

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val pluginSourceStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("plugin_sources")
        }

    val subscriptions = SubscriptionRepository(pluginSourceStore, http, json)

    val installer = PluginInstaller(context.applicationContext, http, json)
}
