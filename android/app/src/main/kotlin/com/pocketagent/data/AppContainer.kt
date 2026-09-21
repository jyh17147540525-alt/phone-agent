package com.pocketagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pocketagent.core.crypto.CryptoManager
import com.pocketagent.core.database.DatabaseKeyProvider
import com.pocketagent.core.database.PassphraseUnavailableException
import com.pocketagent.core.database.PocketAgentDatabase
import com.pocketagent.core.database.PocketAgentDatabaseFactory
import com.pocketagent.keymgmt.CredentialRepository
import com.pocketagent.plugin.api.MarketCatalog
import com.pocketagent.plugin.api.SubscriptionSource
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.openaicompat.OpenAiCompatProvider
import com.pocketagent.provider.openaicompat.ProviderProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File

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
 *    Hilt 的价值在于**自动注入已经存在的构造器**。但现在除了插件模块与
 *    keymgmt，其余模块（perception / action / agent / LlmGateway）连
 *    接口都还没落，此时写 `@Module` 等于给不存在的依赖写绑定 ——
 *    一堆空壳，还得跟着改。
 *
 *    M0 阶段先用手写容器把依赖关系**显式**摆出来（这也是一份可读的架构图），
 *    等各模块的构造器就位后再一次性切 Hilt。届时要把 ksp 与 hilt 两个 Gradle
 *    插件**一起**加回来（见 app/build.gradle.kts 的 plugins 块）——
 *    只加插件不加 hilt-compiler 依赖会直接构建失败。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

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

    val installer = PluginInstaller(appContext, http, json)

    // ═══════════════════════════════════════════════════════════════
    //  API Key 管理
    // ═══════════════════════════════════════════════════════════════

    private val crypto = CryptoManager()

    private val keyProvider = DatabaseKeyProvider(
        crypto = crypto,
        keyFile = File(appContext.filesDir, PASSPHRASE_FILE_NAME),
    )

    /**
     * 11 家内置服务商。
     *
     * 全部走 OpenAI 兼容协议，所以共用 [OpenAiCompatProvider] 一个实现 ——
     * 它们之间的差异（BaseUrl、鉴权方式、能力集）都在 [ProviderProfiles]
     * 的数据里，不在代码里。
     *
     * ⚠️ 这里复用容器里那一个 [http] 实例，而不是每个 Provider 各建一个。
     *    每个 OkHttpClient 都有自己的连接池与线程池；11 个实例等于 11 套，
     *    而且它们**不会共享连接** —— 用户只有一个 Key 时这是纯浪费。
     */
    private val providers: List<LlmProvider> =
        ProviderProfiles.all.map { OpenAiCompatProvider(it, http) }

    /** 串行化"打开存储"与"重建存储"，避免两个协程同时初始化 */
    private val storeLock = Mutex()

    @Volatile
    private var database: PocketAgentDatabase? = null

    @Volatile
    private var credentialStore: CredentialRepository? = null

    /**
     * 打开加密存储。
     *
     * ═══════════════════════════════════════════════════════════════
     *  为什么不在构造容器时就打开
     * ═══════════════════════════════════════════════════════════════
     *
     * 打开数据库要动 Keystore（部分机型上 StrongBox 很慢）、读文件、
     * 加载 SQLCipher 原生库、还要真的开一次库文件。把这些放在冷启动路径上，
     * 会让**每个**用户（哪怕他从没打算配 Key）都付这个代价。
     *
     * 更要紧的是：这条路径**会失败**（见 [CredentialStoreResult]）。
     * 在启动时失败只能崩，而在用户点进"API Key"时才失败，
     * 我们就能把原因说清楚 —— 对一个没有应用商店、推不了热修的侧载应用，
     * "能解释的失败"比"崩溃"重要得多。
     *
     * 结果会缓存；成功后再调用直接返回同一个仓储。
     */
    suspend fun openCredentialStore(): CredentialStoreResult = withContext(Dispatchers.IO) {
        storeLock.withLock {
            credentialStore?.let { return@withLock CredentialStoreResult.Ready(it) }

            val passphrase = try {
                keyProvider.loadOrCreate()
            } catch (e: PassphraseUnavailableException) {
                return@withLock CredentialStoreResult.Unrecoverable(
                    e.message ?: "加密存储的钥匙已经失效，已保存的 API Key 无法再读取。"
                )
            } catch (e: Exception) {
                return@withLock CredentialStoreResult.Retryable(
                    "读不出加密存储的钥匙：${e.message ?: e::class.simpleName}"
                )
            }

            // ⚠️ 这里**不能** passphrase.fill(0)。
            //
            //    SupportOpenHelperFactory 是 `putfield password:[B` —— 直接持有
            //    引用、不克隆（已用 javap 核实）。清零等于给 SQLCipher 一个
            //    全 0 的口令，数据库会以 "file is not a database" 失败，
            //    而那个报错会把人引向"文件损坏"，离真因很远。
            //
            //    代价说清楚：这 32 字节会在内存里活到进程结束。但它单独存在
            //    没有用 —— 密文要它，它要 Keystore 里的主密钥，而主密钥在芯片里。
            //    这是两级密钥设计里唯一必须常驻内存的一环，不是遗漏。
            val db = try {
                PocketAgentDatabaseFactory.create(appContext, passphrase)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 故意捕 Throwable 而不是 Exception：SQLCipher 原生库加载失败
                // 抛的是 UnsatisfiedLinkError，它是 Error 不是 Exception。
                // 只捕 Exception 会让一个"能用文字解释清楚"的失败变成崩溃。
                return@withLock CredentialStoreResult.Retryable(
                    "打不开加密存储：${e.message ?: e::class.simpleName}"
                )
            }

            // ★ 强制真正开一次库。
            //
            //   Room 的 `databaseBuilder().build()` 是**惰性的** —— 它只组装
            //   对象，不碰文件。不在这里探一次的话，"口令不对 / 文件损坏"
            //   会在界面第一次查列表时才冒出来，而那时用户已经在 Key 页面里了，
            //   报错的位置离原因隔了好几层。
            try {
                db.openHelper.writableDatabase
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                runCatching { db.close() }
                val detail = e.message ?: e::class.simpleName ?: "未知错误"
                // "file is not a database" 是 SQLite 对**错误密钥**的固定报错
                // （SQLITE_NOTADB）。只有它能证明"数据真的读不回来了"；
                // 其余（空间不足、文件被占用、权限）都可能是暂时的 ——
                // 那些情况下若劝用户重建，等于让他为一次临时故障丢掉全部 Key。
                // 默认落到 Retryable 是安全的那一侧。
                return@withLock if (detail.contains("not a database", ignoreCase = true)) {
                    CredentialStoreResult.Unrecoverable(
                        "数据库用当前钥匙解不开（$detail）。已保存的 API Key 无法再读取。"
                    )
                } else {
                    CredentialStoreResult.Retryable("数据库打不开：$detail")
                }
            }

            database = db
            val repository = CredentialRepository(
                db = db,
                crypto = crypto,
                providers = providers,
            )
            credentialStore = repository
            CredentialStoreResult.Ready(repository)
        }
    }

    /**
     * 重建加密存储。
     *
     * 存在的理由：当钥匙失效时，用户**没有别的路可走**。数据已经读不回来，
     * 不给他一个"从头开始"的按钮，这个应用就永久卡在打不开的状态里 ——
     * 而它是个侧载应用，没有客服、没有热修。
     *
     * ⚠️ 删除顺序是**先库文件、后口令**，不能反。
     *    若先删口令再删库，而删库那一步失败了，就会留下
     *    "新口令 + 旧库文件"的组合 —— 下次开库报 "file is not a database"，
     *    用户刚看完一个吓人的错误，又要再看一次。
     *    反过来（库没了、口令还在）则完全无害：旧口令配新库，照常工作。
     *
     * ⚠️ 刻意**不动 Keystore 主密钥**。那是「清除所有数据」的职责，
     *    而这里只想让 Key 存储重新可用。销毁主密钥会让这次重建写下的
     *    新口令文件立刻失效 —— 等于重建完还是打不开。
     */
    suspend fun resetCredentialStore() = withContext(Dispatchers.IO) {
        storeLock.withLock {
            credentialStore = null
            runCatching { database?.close() }
            database = null

            appContext.deleteDatabase(PocketAgentDatabaseFactory.DATABASE_NAME)
            keyProvider.delete()
        }
    }

    private companion object {
        /**
         * 数据库口令的密文文件。
         *
         * 放 `filesDir` 而不是 `cacheDir` —— 后者会被系统在存储紧张时清掉，
         * 而它是数据库唯一的钥匙：被清掉就等于数据全丢。
         */
        const val PASSPHRASE_FILE_NAME = "db.key"
    }
}

/**
 * 打开加密存储的结果。
 *
 * ⚠️ 刻意用 sealed 类型而不是"返回 null / 抛异常"，因为这三种情况
 *    对用户意味着**三件不同的事**，界面必须能分别说清楚：
 *
 *      · [Ready]        —— 正常
 *      · [Unrecoverable]—— 钥匙失效，数据已经没了，只能重建
 *      · [Retryable]    —— 可能只是暂时的，先重试
 *
 *    如果统一成"打不开"，用户就不知道该重试还是该重建 ——
 *    而选错的那一方代价很大（重试一个永远打不开的存储 / 为一次临时故障
 *    丢掉全部 Key）。
 */
sealed interface CredentialStoreResult {
    data class Ready(val repository: CredentialRepository) : CredentialStoreResult

    /** 钥匙或数据库已经失效，已保存的 Key 读不回来了。唯一出路是重建 */
    data class Unrecoverable(val reason: String) : CredentialStoreResult

    /** 打不开，但可能只是暂时的（空间不足、文件被占用、原生库没加载上等） */
    data class Retryable(val reason: String) : CredentialStoreResult
}
