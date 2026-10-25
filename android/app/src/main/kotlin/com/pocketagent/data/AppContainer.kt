package com.pocketagent.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.pocketagent.assistant.AndroidOffloadDraftPort
import com.pocketagent.assistant.AppPersonaBriefPort
import com.pocketagent.assistant.ActionDispatchAdapter
import com.pocketagent.assistant.AccessibilityActionExecutor
import com.pocketagent.assistant.AgentA11ySource
import com.pocketagent.assistant.AgentForegroundService
import com.pocketagent.assistant.HostPermissionReader
import com.pocketagent.assistant.McpBridgeHost
import com.pocketagent.assistant.McpBridgeStatus
import com.pocketagent.assistant.PerceptionScreenReader
import com.pocketagent.assistant.SafetyDetectorGate
import com.pocketagent.assistant.SafetyGuardAdapter
import com.pocketagent.assistant.SingleChannelDispatcher
import com.pocketagent.assistant.AssistantPromptBuilder
import com.pocketagent.assistant.defaultAssistantPersona
import com.pocketagent.assistant.FileOffloadBlobStore
import com.pocketagent.assistant.SafOfficeToolExecutor
import com.pocketagent.assistant.SafOfficeWorkspacePort
import com.pocketagent.assistant.SafOfficeWorkspaceResolver
import com.pocketagent.capability.AndroidSettingsAccess
import com.pocketagent.capability.SafFileChannel
import com.pocketagent.core.common.AtomicTextFile
import com.pocketagent.core.crypto.CryptoManager
import com.pocketagent.core.database.DatabaseKeyProvider
import com.pocketagent.core.database.PassphraseUnavailableException
import com.pocketagent.core.database.PocketAgentDatabase
import com.pocketagent.core.database.PocketAgentDatabaseFactory
import com.pocketagent.core.network.LogSanitizer
import com.pocketagent.keymgmt.AppPrivateDshConfigSink
import com.pocketagent.keymgmt.CredentialRepository
import com.pocketagent.keymgmt.GatewayCredentialSource
import com.pocketagent.keymgmt.ModelConfigRepositoryImpl
import com.pocketagent.keymgmt.ModelDeclarationEntry
import com.pocketagent.keymgmt.RoomUsageRecorder
import com.pocketagent.keymgmt.modelDeclarationsOf
import com.pocketagent.mcp.ActionRateLimiter
import com.pocketagent.mcp.McpBridgeSession
import com.pocketagent.mcp.McpDispatcher
import com.pocketagent.mcp.McpHttpServer
import com.pocketagent.mcp.ContextOffloadTool
import com.pocketagent.mcp.ContextRecallTool
import com.pocketagent.mcp.GlobalKeyTool
import com.pocketagent.mcp.TypeTextTool
import com.pocketagent.mcp.OfficeRunTool
import com.pocketagent.mcp.OfficeToolsTool
import com.pocketagent.mcp.PersonaBriefTool
import com.pocketagent.mcp.ScreenReadTool
import com.pocketagent.mcp.McpTool
import com.pocketagent.rootlogic.PrivilegeMode
import com.pocketagent.rootlogic.RootProbeResult
import com.pocketagent.assistant.effectiveMode
import com.pocketagent.mcp.RootExecTool
import com.pocketagent.assistant.RootProbe
import com.pocketagent.assistant.RootShellRunner
import com.pocketagent.mcp.SwipeTool
import com.pocketagent.mcp.TapTool
import com.pocketagent.mcp.ToolRegistry
import com.pocketagent.memorylogic.ContextOffloader
import com.pocketagent.safety.DefaultSafetyGuard
import com.pocketagent.modelrouter.ModelRouteCoordinator
import com.pocketagent.perception.AccessibilityPerceptionManager
import com.pocketagent.plugin.api.MarketCatalog
import com.pocketagent.plugin.api.SubscriptionSource
import com.pocketagent.provider.api.LlmProvider
import com.pocketagent.provider.api.TtsProvider
import com.pocketagent.provider.gateway.GatewayCallException
import com.pocketagent.provider.gateway.GatewayCore
import com.pocketagent.provider.gateway.GatewayFailure
import com.pocketagent.provider.gateway.RoutingBridge
import com.pocketagent.provider.gateway.dsh.DshGatewaySession
import com.pocketagent.provider.gateway.dsh.DshGatewayStartResult
import com.pocketagent.provider.gateway.dsh.DshWriteResult
import com.pocketagent.provider.gateway.http.HttpGatewayServer
import com.pocketagent.provider.openaicompat.OpenAiCompatProvider
import com.pocketagent.provider.openaicompat.ProviderProfiles
import com.pocketagent.provider.ttsopenai.OpenAiCompatTtsProvider
import com.pocketagent.provider.ttsopenai.TtsProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /**
     * 写系统设置的 Android 侧实现（T0-A）。
     *
     * ⚠️ 它是**两个消费方共用的同一个对象**：
     *    · 执行层（`CapabilityRunner` 的 settings 端口）—— 真正去写；
     *    · 权限页（`HostPermissionReader`）—— 读"有没有授权"。
     *    两边用同一个 `isGranted`，才不会出现「界面说已开启、执行说没权限」。
     */
    val settingsAccess = AndroidSettingsAccess(appContext)

    /**
     * 读宿主权限的真实状态（无障碍 / 悬浮窗 / 通知 / 截图 / 写设置两项）。
     *
     * ⚠️ 它**不做判断** —— "该显示什么状态、该给什么按钮"全在 `:core:common`
     *    的 `HostPermissions` 里（纯 Kotlin、有测试）。判断一旦散到 Android 侧，
     *    离线测试就够不着了，而这一层的错（用错 API、读错字段）恰恰最静默。
     */
    val hostPermissionReader = HostPermissionReader(appContext, settingsAccess)

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

    /**
     * 内置语音合成服务商。
     *
     * ⚠️ 与 [providers] 是**两张独立的表**，见 `CredentialRepository` 的
     *    构造参数注释。这里同样复用容器里那一个 [http] ——
     *    没有理由为语音单开一个连接池。
     *
     * ⚠️ 与对话侧不同，TTS **不能只用一个实现类覆盖全部**：
     *    虽然当前两家（硅基流动 / OpenAI）恰好都走 `/audio/speech`，
     *    但厂商覆盖面小得多 —— OpenRouter 没有语音端点，各家的本地
     *    TTS 走的是完全不同的协议。所以这里用 `map` 而不是
     *    复制对话侧那句"全都走同一个协议"的乐观判断。
     *
     * ⚠️ **没有把 Android 原生 `TextToSpeech` 放进来。** 它是离线的、
     *    免费的、也是"尽可能减少联网"这一取向的天然选择，但它是
     *    **系统 API 而不是 HTTP 服务商**：不涉及 Key、不涉及网络、
     *    不涉及鉴权。硬塞进 [TtsProvider] 会让那个接口多出一堆
     *    "对本地引擎无意义"的字段（authScheme / baseUrl / validateKey）。
     *    它将来应当作为一个**独立的执行通道**接进语音层。
     */
    private val ttsProviders: List<TtsProvider> =
        TtsProfiles.all.map { OpenAiCompatTtsProvider(it, http) }

    /**
     * 静态模型清单：`modelId` → 「展示名 + 价格」。
     *
     * ═══════════════════════════════════════════════════════════════
     *  它是"离线快照"，不是"实时列表"
     * ═══════════════════════════════════════════════════════════════
     *
     * 数据源是 `ProviderProfiles.all` 里每家厂商的 `defaultModels` ——
     * 随应用版本更新，**不发网络请求**。
     *
     * 为什么不去调 `LlmProvider.listModels()`：那要求**已解密的凭据**
     * 并且**发网络请求**。而配置页打开时用户还没选 Key，也必须在
     * 飞行模式下能打开（它读的是"我配过什么"，不是"厂商有什么"）。
     *
     * ⚠️ **查不到不是错误。** 用户可能配了自建端点的模型名，或厂商下架了
     *    清单里的模型。此时展示名回落到裸 `modelId`、价格回落到 null
     *    （未知）。把"未知"当成"免费"会让预算熔断静默失效，见
     *    `ModelConfigMapping` 的长注释。
     *
     * ⚠️ **这里是一次性求值的 `val`，不是 `by lazy`。**
     *    `ProviderProfiles.all` 在 [providers] 那里已经被遍历过一次了
     *    （为了建 Provider 实例），所以这次遍历是搭便车，不额外引入
     *    冷启动成本。真正的开销在后端：`ModelConfigRepositoryImpl`
     *    内部对它做了 `by lazy` 缓存，配置页读时才展开。
     */
    private val modelDeclarations = modelDeclarationsOf(
        ProviderProfiles.all.flatMap { profile ->
            profile.defaultModels.map { model ->
                ModelDeclarationEntry(
                    modelId = model.id,
                    displayName = model.displayName,
                    inputPricePerMillion = model.inputPricePerMillion,
                    outputPricePerMillion = model.outputPricePerMillion,
                )
            }
        }
    )

    /** 串行化"打开存储"与"重建存储"，避免两个协程同时初始化 */
    private val storeLock = Mutex()

    @Volatile
    private var database: PocketAgentDatabase? = null

    @Volatile
    private var credentialStore: CredentialRepository? = null

    /**
     * 模型配置仓储。
     *
     * ⚠️ 与 [credentialStore] **同生命周期** —— 它们共用同一个数据库句柄，
     *    而那个句柄在 [resetCredentialStore] 里会被关掉。
     *    若这里不跟着置空，重建之后旧仓储还指着一个已关闭的库，
     *    下一次读模型列表会报"数据库已关闭"，而用户的动作只是"重建存储"。
     */
    @Volatile
    private var modelStore: ModelConfigRepositoryImpl? = null

    /**
     * 会话与轮次的仓储。
     *
     * ⚠️ 它**必须和 [modelStore] 同时建、同时清** —— 理由同那里写的：
     *    共用同一个 `db` 句柄的仓储分两次建，会让"凭据就绪但会话还没"
     *    成为一个可达状态，而那时界面会显示空历史，**用户以为聊天记录丢了**。
     */
    @Volatile
    private var conversationStore: ConversationStore? = null

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
                ttsProviders = ttsProviders,
            )
            credentialStore = repository

            // 模型仓储与凭据仓储共用同一个 db 句柄，必须一起建 ——
            // 分开建会让"凭据已就绪但模型还没"成为一个可达状态，
            // 而那时配置页会显示空列表，用户以为配置丢了
            modelStore = ModelConfigRepositoryImpl(
                db = db,
                declarations = modelDeclarations,
            )
            conversationStore = ConversationStore(
                sessions = db.sessionDao(),
                turns = db.turnDao(),
                tasks = db.taskDao(),
            )

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
            // ⚠️ **必须先拆掉 dsh 会话，再关库。**
            //
            //    会话持有的 `GatewayCredentialSource` 与模型仓储都指着这个 db，
            //    库一关它们就成了"指向已关闭库的引用" —— 与上面 [modelStore]
            //    那条注释是同一类错误，而这里的表现**更隐蔽**：
            //    网关还挂在 127.0.0.1 上监听，dsh 的请求照常进来，
            //    然后回一个"数据库已关闭"的错 —— 而用户的动作只是"重建存储"，
            //    他没有任何线索能把这两件事联系起来。
            //
            //    顺带把端点也停掉：端口该释放了。
            dshSession?.stop()
            dshSession = null
            dshStatus = DshIntegrationStatus.Off

            credentialStore = null
            modelStore = null
            // ⚠️ 一起清。库一关，它就成了"指向已关闭库的引用"——
            //    留着它比留着 null 更危险：调用方拿到它、用它，然后崩在一个
            //    与"会话"毫不相干的地方。
            conversationStore = null
            runCatching { database?.close() }
            database = null

            appContext.deleteDatabase(PocketAgentDatabaseFactory.DATABASE_NAME)
            keyProvider.delete()
        }
    }

    /**
     * 取模型配置仓储。
     *
     * ⚠️ 返回值是 `null` 表示**加密存储还没打开**，而不是"没有模型"。
     *    调用方必须分别处理 —— 否则配置页会把"存储没打开"显示成
     *    "你还没配任何模型"，用户就会去重新配一遍（而他配过的东西还在库里）。
     *
     * 常规用法是先进 [openCredentialStore]，拿到 `Ready` 之后再取这个。
     * 之所以不把两者合成一个返回类型：它们是**两个页面**的依赖
     * （Key 页 / 模型页），合成会让 Key 页也被迫持有模型仓储的概念。
     */
    fun modelRepository(): ModelConfigRepositoryImpl? = modelStore

    /**
     * 会话仓储。null = 库还没开（调用前先 `openCredentialStore()`）。
     *
     * ⚠️ 聊天路径上库**本来就是开的** —— `ChatPort` 为了取 Key 已经调过
     *    `openCredentialStore()`。所以这里复用，不额外付一次 Keystore 的代价。
     */
    fun conversationRepository(): ConversationStore? = conversationStore

    // ═══════════════════════════════════════════════════════════════
    //  dsh 集成：本机网关 + 配置投递
    // ═══════════════════════════════════════════════════════════════

    /**
     * 串行化会话的建立与开关。
     *
     * ⚠️ 两个协程同时进来会各建一个 [HttpGatewayServer] —— 于是**各占一个端口**，
     *    而后建的那个会把 [dshSession] 覆盖掉。表现是：用户点两次"开启"，
     *    配置里指向的端口和实际在听的端口不是同一个，dsh 报连接被拒。
     */
    private val dshLock = Any()

    @Volatile
    private var dshSession: DshGatewaySession? = null

    /**
     * 当前状态。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 状态存在**容器**里，不存在 ViewModel 里 —— 这是刻意的
     * ═══════════════════════════════════════════════════════════════
     *
     * 网关的生命周期**长于任何页面**：用户开启之后会离开设置页，而 dsh 还要
     * 继续用它。若状态放在页面级的 ViewModel 里，用户离开再回来会看到"未开启"，
     * 而网关其实还在 127.0.0.1 上听着 —— 那正是本项目最忌讳的**界面说假话**
     * （见 `SettingsViewModel` 的注释：一个说错话的界面比一个不说这话的界面更糟）。
     *
     * 所以唯一的事实来源放在这里，页面只**读**它。
     */
    @Volatile
    private var dshStatus: DshIntegrationStatus = DshIntegrationStatus.Off

    /** 当前状态。只读 —— 不要读了之后自己维护一份副本。 */
    fun dshIntegrationStatus(): DshIntegrationStatus = dshStatus

    /**
     * 开启 dsh 集成（**幂等**）。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 它**只能在 [openCredentialStore] 成功之后**成功
     * ═══════════════════════════════════════════════════════════════
     *
     * 网关的两件依赖来自加密存储：
     * - [GatewayCredentialSource] 要 `CredentialDao`（真 Key 在库里）
     * - [RoutingBridge] 要模型仓储（路由要读用户配了哪些模型）
     *
     * 所以**不在构造容器时就建好** —— 那时 `database` 还是 null。
     * 这也正好符合"冷启动路径上不该有 Keystore"这条既定纪律：
     * dsh 集成是用户主动开启的功能，不该让没打算用它的用户在冷启动付一次开库代价。
     *
     * ⚠️ **同步阻塞**：要读两个文件、写两个文件、建 `ServerSocket`。
     *    调用方**必须切到 IO 线程** —— 在主线程序调用会直接卡住那一帧。
     *    （`DshGatewaySession.start()` 不是 suspend 函数，这一点容易看漏。）
     *
     * ⚠️ 返回 [DshIntegrationStatus] 而不是 `Boolean`：失败要说清**为什么**、
     *    以及**用户该去做什么**（去 Key 页？还是去模型页？）。
     */
    fun startDshIntegration(): DshIntegrationStatus = synchronized(dshLock) {
        val db = database
            ?: return@synchronized blocked(
                "加密存储还没打开。请先打开一次「API Key」页，再回来开启 dsh 集成。"
            )
        val models = modelStore
            ?: return@synchronized blocked(
                "模型配置还没加载。请先打开一次「模型」页，再回来开启 dsh 集成。"
            )

        val session = dshSession ?: buildDshSession(db, models).also { dshSession = it }

        dshStatus = when (val result = session.start()) {
            is DshGatewayStartResult.NotStarted -> DshIntegrationStatus.Blocked(result.reason)
            is DshGatewayStartResult.Running ->
                DshIntegrationStatus.On(baseUrl = result.baseUrl, delivery = result.delivery)
        }
        // ⚠️ 只记**成功**的那一次（同能力桥的理由）：把 Blocked 记成"用户想开着"
        //    会让下次冷启动去重试一个当时就起不来的东西，而用户从没成功开过它。
        rememberIntegration(KEY_DSH_ON, dshStatus is DshIntegrationStatus.On)
        syncKeepAlive()
        dshStatus
    }

    /**
     * 关闭 dsh 集成（幂等）。
     *
     * ⚠️ **不清理已投递的配置** —— 理由见 `DshGatewaySession.stop()` 的长注释：
     *    清理需要对 dsh 真实目录的写权限，而那个权限正是本任务还没验证的东西；
     *    即使能清，删掉配置会让 dsh 进入"完全没配过"的状态，
     *    比"配了但连不上"更难向用户解释。
     */
    fun stopDshIntegration(): DshIntegrationStatus = synchronized(dshLock) {
        dshSession?.stop()
        dshStatus = DshIntegrationStatus.Off
        rememberIntegration(KEY_DSH_ON, false)
        syncKeepAlive()
        dshStatus
    }

    /** 记下失败状态并把它交回去 —— 失败原因必须能被界面读到，不能只进日志。 */
    private fun blocked(reason: String): DshIntegrationStatus =
        DshIntegrationStatus.Blocked(reason).also { dshStatus = it }

    /**
     * 已投递的草稿配置全文 —— 给用户抄到 dsh 那边去。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 界面**必须**把它展示出来，这是用户拿到这份配置的唯一途径
     * ═══════════════════════════════════════════════════════════════
     *
     * `AppPrivateDshConfigSink` 的注释里写着"不加前导点让它在文件管理器里可见" ——
     * **那句话只对"名字"成立，对"目录可达性"不成立**：草稿写在
     * `/data/data/<pkg>/files/dsh/`，非 root 设备上文件管理器与 MTP 都进不去。
     *
     * 所以不能指望用户"自己去找那个文件"。界面展示 + 长按选择复制，是唯一的路。
     */
    fun dshDraftSettings(): String? = runCatching {
        File(appContext.filesDir, AppPrivateDshConfigSink.SETTINGS_REL_PATH)
            .takeIf { it.isFile }
            ?.readText()
    }.getOrNull()

    /** 草稿文件的绝对路径，给界面显示（用户要照着找，或者贴给我们排查问题）。 */
    val dshDraftSettingsPath: String
        get() = File(appContext.filesDir, AppPrivateDshConfigSink.SETTINGS_REL_PATH).absolutePath

    /**
     * 把"起网关 → 渲染 → 合并 → 投递"这条链装起来。
     *
     * ⚠️ 这里**刻意没有任何逻辑** —— 全部在 `:provider:gateway` 与 `:keymgmt` 里，
     *    而那两处都有离线单测。本方法只是把构造参数摆出来（也顺便是一份可读的
     *    依赖图）。装配本身错了的后果是"编译不过"，那是最便宜的一类错误。
     */
    private fun buildDshSession(
        db: PocketAgentDatabase,
        models: ModelConfigRepositoryImpl,
    ): DshGatewaySession {
        val coordinator = ModelRouteCoordinator(models)

        val core = GatewayCore(
            bridge = RoutingBridge(
                coordinator = coordinator,
                // ⚠️ 收函数而不是再收一个仓储 —— 见 `RoutingBridge` 构造参数注释：
                //    同一个仓储注入两次，测试里很容易被换成不同的 fake，
                //    表现是"路由用这份配置、取值用另一份"，而测试照样过。
                modelById = { id -> models.byId(id) },
            ),
            credentials = GatewayCredentialSource(db.credentialDao(), crypto),
            providers = providers,
            usageRecorder = RoomUsageRecorder(db.usageDao()),
        )

        val endpoint = HttpGatewayServer(
            core = core,
            routingModeFor = {
                // ⚠️ `inferMode()` 返回 null 表示"一个可用的模型都没有"。
                //    这里**必须抛 [GatewayCallException]**，不能让它退化成
                //    裸的 IllegalStateException ——
                //    `HttpGatewayServer.describeFailure` 对非 GatewayCallException
                //    只会回一句「模型服务出错（IllegalStateException）」，
                //    用户看到那句会去查服务商，而真正的问题是"他还没配模型"。
                //    [GatewayFailure.NoModelConfigured] 的文案是现成且正确的。
                coordinator.inferMode()
                    ?: throw GatewayCallException(GatewayFailure.NoModelConfigured)
            },
            sanitize = LogSanitizer::sanitize,
        )

        return DshGatewaySession(
            endpoint = endpoint,
            sink = AppPrivateDshConfigSink(
                readFile = { relativePath ->
                    File(appContext.filesDir, relativePath).takeIf { it.isFile }?.readText()
                },
                writeFile = ::writeAppPrivateFile,
            ),
            log = { Timber.i(it) },
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  MCP 能力桥：把手机能力暴露给 dsh（P2）
    // ═══════════════════════════════════════════════════════════════

    /**
     * ⚠️ 与 dsh 集成（网关）**各用一把锁** —— 两者生命周期独立：
     *    网关要加密存储就绪（用户的 Key），能力桥只要无障碍权限。
     *    共用一把锁会让"网关因没配 Key 被 Blocked"顺带挡住桥的启停，
     *    而这两件事用户想分开做是完全合理的。
     */
    private val mcpBridgeLock = Any()

    @Volatile
    private var mcpBridgeHost: McpBridgeHost? = null

    @Volatile
    private var mcpBridgeStatus: McpBridgeStatus = McpBridgeStatus.Off

    /**
     * 人格运行时（DL-1…DL-4 的载体）—— **容器级单例**。
     *
     * ⚠️ **不能每次建桥时现造**：它持有"上次推进的时间基准"与当前情绪/精力，
     *    重建会把基准点重置到当下，于是「助理已经多久没被搭理」这段历史被抹掉。
     *
     * ⚠️ 取舍与 [com.pocketagent.mcp.ActionRateLimiter] **相反** ——
     *    那个必须每次重建才能"关桥即清空限流"，因为它的窗口是**短时**的
     *    （重置它可接受，甚至正确）；而状态的衰减是**长时**的（重置它等于失忆）。
     *    两处的判据同一条：**重置的代价是什么**。
     *
     * ⚠️ 当前只喂了默认 preset + 空账本（见 [defaultAssistantPersona] 的注释）——
     *    账本持久化归 `:memory`、微调入口是向导 UI、状态事件归 `:assistant`，
     *    三者都还没接。预支它们的后果是猜一个还没问出口的需求。
     */
    private val assistantPersona by lazy { defaultAssistantPersona() }

    /**
     * 助理的系统提示词构造器（`PersonaAssembler` 的消费方）。
     *
     * ★ 它让「助理有人格」第一次**真的生效**：在此之前聊天页用的是一个
     *   硬编码常量，用户改口癖、改称呼，模型那边一个字都不会变。
     */
    private val assistantPrompt by lazy { AssistantPromptBuilder(assistantPersona) }

    /**
     * 组装一份系统提示词。
     *
     * ⚠️ **每次调用都重新组装**（不缓存）：状态会随时间向中性回落，
     *    缓存会让「今天心情差」在几轮对话之后失效 —— 而那种失效
     *    在界面上完全看不出来（模型只是又变回了中性语气）。
     *
     * ⚠️ 记忆层还没接，所以提示词里**没有任何记忆** —— 助理目前不会记住任何事。
     *    这是如实的，不是省略。
     */
    /**
     * 组装系统提示词。
     *
     * @param canAct 这一轮挂不挂工具。**必须与实际情况一致** ——
     *   纯对话那一轮若还在说"你可以直接操作手机"，模型会许下一个
     *   这一轮做不到的承诺（2026-10-25 真机：用户说 hello，
     *   助理回"手机上的操作可以直接说"，而那一轮根本没挂工具）。
     */
    fun assistantSystemPrompt(canAct: Boolean = true): String = assistantPrompt.build(canAct)

    /** 当前状态。只读 —— 不要读了之后自己维护一份副本（同 dshIntegrationStatus）。 */
    fun mcpBridgeStatus(): McpBridgeStatus = mcpBridgeStatus

    /**
     * 开启能力桥（幂等）。
     *
     * ⚠️ **同步阻塞**（建 ServerSocket、写草稿文件）—— 调用方必须切到 IO 线程，
     *    与 `startDshIntegration` 同一条纪律。
     */
    fun startMcpBridge(): McpBridgeStatus = synchronized(mcpBridgeLock) {
        val host = mcpBridgeHost ?: buildMcpBridgeHost().also { mcpBridgeHost = it }
        mcpBridgeStatus = host.start()
        // ⚠️ 只记**成功**的那一次 —— 把失败记成"用户想开着"会让下次冷启动
        //    去重试一个当时就起不来的东西，而用户从没成功开过它。
        rememberIntegration(KEY_BRIDGE_ON, mcpBridgeStatus is McpBridgeStatus.On)
        syncKeepAlive()
        mcpBridgeStatus
    }

    /** 关闭能力桥（幂等）。不清理草稿与手机上的配置（同网关的取舍：清理需要不存在的写权限）。 */
    fun stopMcpBridge(): McpBridgeStatus = synchronized(mcpBridgeLock) {
        mcpBridgeHost?.stop()
        mcpBridgeStatus = McpBridgeStatus.Off
        rememberIntegration(KEY_BRIDGE_ON, false)
        syncKeepAlive()
        mcpBridgeStatus
    }

    /** 草稿文件的绝对路径（给界面显示；非 root 设备打不开它，所以全文也要显示）。 */
    val mcpDraftPath: String
        get() = File(appContext.filesDir, McpBridgeHost.DRAFT_REL_PATH).absolutePath

    /** 草稿全文 —— 用户抄到 dsh 那边去的唯一途径（同 [dshDraftSettings]）。 */
    fun mcpDraft(): String? = runCatching {
        File(appContext.filesDir, McpBridgeHost.DRAFT_REL_PATH)
            .takeIf { it.isFile }
            ?.readText()
    }.getOrNull()

    /**
     * 把「起服务 → 渲染草稿」这条链路装起来。
     *
     * ⚠️ 这里**刻意没有任何逻辑** —— 协议、关卡、序列化、配置渲染全部在
     *    `:mcp`（69 条离线测试）；本方法只是把构造参数摆出来
     *    （也顺便是一份可读的依赖图）。装配错了的后果是"编译不过"，
     *    那是最便宜的一类错误。
     */
    /**
     * 能力桥的全部工具 —— **容器级单例**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么必须单例，而不是"每次用的时候现造一份"
     * ═══════════════════════════════════════════════════════════════
     *
     * 因为 [ActionRateLimiter] 是**有状态的**（滑动窗口）。现在有两条链路
     * 都要这份工具清单：
     *
     *  - **dsh** 经 HTTP 调（[buildMcpBridgeHost]）
     *  - **应用内聊天**经 `PhoneAgentLoop` 直接调（`:agentlogic` 的循环）
     *
     * 若两边各造一份，就各自持有一个限流器 —— 于是**合计**可以跑到
     * `tap + swipe + type + key` 各自上限之和，也就是 4 倍的拟人节奏。
     * 而这件事**不会报错**：用户只会觉得"它点得有点快"，
     * 直到某个 App 把它当成机器人。
     *
     * ⇒ 一个实例，两条链路共用。这也是 `ActionRateLimiter` 注释里
     *    "关桥即清空限流"那个取舍的延伸：**重置它可接受，复制它不可接受**。
     *
     * ★ 还有一层更重要的收益：两条链路**走的是同一批工具的 `call()`**，
     *    于是 `:safety` 的六步判定、频率闸、盲点文案**全部自动一致**。
     *    若聊天路径另建一套判定，两份策略迟早漂移 —— 
     *    而漂移的方向是"其中一份比另一份松"，没有人会发现。
     */
    private val agentTools: List<McpTool> by lazy {
        val reader = PerceptionScreenReader(AccessibilityPerceptionManager(AgentA11ySource))
        val gate = SafetyDetectorGate()

        // ── P3：执行工具（android_tap / android_swipe）────────────────
        // 判定走 :safety 引擎（:mcp 只编排），执行走无障碍单通道；
        // 频率闸四个工具**共用同一个实例**（否则合计可跑到 40 次/分钟）。
        val rateLimiter = ActionRateLimiter()
        val safetyPort = SafetyGuardAdapter(DefaultSafetyGuard())
        val dispatchPort = ActionDispatchAdapter(
            SingleChannelDispatcher(AccessibilityActionExecutor()),
        )

        // ── DL：上下文卸载（context_offload / context_recall）──────────
        // ★ 它是 `:memorylogic` 的**第一个消费方**：在此之前它的 2000+ 行
        //   （含「卸载前必须过 PrivacyFilter」这条红线）没有任何模块引用，
        //   `classes.dex` 里一个字节都没有 —— 红线从未被执行过一次。
        //
        // ⚠️ 落盘根目录是**应用私有目录**（不是设计稿写的 SAF），取舍写在
        //    `FileOffloadBlobStore` 的类注释里：SAF 要先向用户要一个目录权限，
        //    而当前还没有任何界面能让用户理解"为什么要给助理一个目录"。
        //    等「记忆管理页」落地时一并重新评估。
        //
        // ⚠️ 隐私关卡 `PrivacyGate` 用 `:mcp` 里的默认值（`PrivacyFilter`
        //    + `systemUi = unknown`），与 `ScreenReadTool` 同一个构造口径 ——
        //    文本路径没有像素，unknown 不会误伤。
        val offloader = ContextOffloader(
            FileOffloadBlobStore(File(appContext.filesDir, OFFLOAD_DIR_NAME)),
        )
        val offloadDrafts = AndroidOffloadDraftPort(reader, gate)

        // ── DL：办公/文件工具（office_tools / office_run）──────────────
        // ★ 这一段补的是「最后一百米」：`:filelogic` 的工具定义与校验、
        //   `XlsxWriter`/`DocxWriter`/`OfficeText` 都已存在且离线测过，
        //   但 `OfficeToolExecutor` **是一个没有实现的端口** ——
        //   没有任何代码真的去碰文件系统（实测：那三个类在生产代码里零引用）。
        //
        // ⚠️ 它同时是 dsh 唯一一条「在用户授权目录里读写」的路径：
        //    dsh 跑在 Termux 容器里、UID 不同，够不到本应用持有的 SAF 授权。
        //    **权限没有被放大** —— 一直在用户授的那棵树里。
        //
        // ⚠️ 工作区解析器**只建一个**，端口与执行器共用 —— 两处各自解析
        //    会出现「端口说能写、执行器说没工作区」这类不一致。
        val officeWorkspaces = SafOfficeWorkspaceResolver.of(appContext)
        val officeChannel = SafFileChannel(appContext)
        val officeExecutor = SafOfficeToolExecutor(officeWorkspaces, officeChannel)
        val officeWorkspacePort = SafOfficeWorkspacePort(officeWorkspaces)

        listOf(
            ScreenReadTool(reader, gate),
            TapTool(reader, safetyPort, dispatchPort, rateLimiter),
            SwipeTool(reader, safetyPort, dispatchPort, rateLimiter),
            // ── DL：人格摘要（只读）──────────────────────────────
            // ★ 它是 `:personalogic` 的**第一个消费方**：在此之前
            //   DL-1…DL-4 的 5000+ 行、1852 条测试没有任何模块引用，
            //   编译得过、测试全绿，但 `classes.dex` 里一个字节都没有。
            //
            // ⚠️ 端口实现是 `:app` 侧的 `AppPersonaBriefPort` ——
            //    判定与映射在 `:mcp`（`personaBriefOf`），状态与时间在
            //    `:personalogic`（`PersonaRuntime`）；这里只把两者接起来。
            PersonaBriefTool(AppPersonaBriefPort(assistantPersona.runtime)),
            // ── DL：上下文卸载（`OffloadPorts.kt` 的两个适配器）──
            ContextOffloadTool(offloader, offloadDrafts),
            ContextRecallTool(offloader),
            // ── DL：办公/文件（见上面 officeExecutor 的注释）──
            OfficeToolsTool(officeWorkspacePort),
            OfficeRunTool(officeWorkspacePort, officeExecutor),
            // ── 2026-10-12：文字输入与导航键 ────────────────────
            // ★ 在此之前能力桥**只能点击与滑动** —— 而"帮我搜一下 X"
            //   这类任务的第一步就是打字。没有它，跨应用任务大部分走不通：
            //   能点开搜索框，却打不出一个字。
            //   （`:action` 里 `ActionType` 有 15 种，此前只暴露了 2 种。）
            //
            // ⚠️ `rateLimiter` **共用同一个实例** —— 否则合计可跑到
            //    `tap+swipe+type+key` 各自上限之和，那是 4 倍的节奏。
            TypeTextTool(reader, safetyPort, dispatchPort, rateLimiter),
            GlobalKeyTool(dispatchPort, rateLimiter),
        )
    }

    private fun buildMcpBridgeHost(): McpBridgeHost {
        val server = McpHttpServer(
            dispatcher = McpDispatcher(ToolRegistry(agentTools)),
            log = { Timber.i(it) },
        )
        return McpBridgeHost(
            session = McpBridgeSession(server),
            writeFile = ::writeAppPrivateFile,
            draftDisplayPath = mcpDraftPath,
            log = { Timber.i(it) },
        )
    }

    /**
     * 聊天路径要用的工具清单。
     *
     * ★ 与 dsh 那条链路拿到的是**同一份实例**（见 [agentTools] 的注释）——
     *   于是安全判定、频率闸、盲点文案全部自动一致。
     *
     * ⚠️ 刻意**不检查"能力桥是否已启动"**：
     *    `agentTools` 是纯 Kotlin 工具对象，直接调 `call()` 不需要 HTTP 服务。
     *    去要求用户"先去 DSH 集成页开启能力桥"，其实是把**传输层**的开关
     *    当成了**能力**的开关 —— 而聊天这条路径压根不走传输层。
     *
     * ⚠️ 但**无障碍权限是真的前置条件**：没开的话读屏会返回 Failed，
     *    而那个 Failed 会如实告诉模型"无障碍服务没开"（见 ScreenReadTool）。
     */
    fun agentToolsForChat(): List<McpTool> = agentTools

    // ═══════════════════════════════════════════════════════════════
    //  权限模式（root / 非 root）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 用户在设置里**选的**模式。
     *
     * ⚠️ 它不是"实际生效的模式" —— 见 [effectivePrivilegeMode]。
     *    选了 root 但 Magisk 没放行时，实际是普通模式，
     *    而**这个区别必须能被界面读到**，否则用户会以为开关坏了。
     */
    private var selectedPrivilegeMode: PrivilegeMode = PrivilegeMode.NON_ROOT

    /** 最近一次 root 探测结果。null = 还没探过。 */
    private var lastRootProbe: RootProbeResult? = null

    /** 当前选择（界面渲染开关用）。 */
    fun privilegeMode(): PrivilegeMode = selectedPrivilegeMode

    /** 最近一次探测结论（界面显示"为什么不可用"用）。 */
    fun rootProbe(): RootProbeResult? = lastRootProbe

    /**
     * **实际生效**的模式。
     *
     * ★ 这个函数是整个 root 模式的安全阀：
     *   用户选了 root ≠ 真的能用 root。手机可能换了、Magisk 可能更新后
     *   丢了授权。此时能力**必须降级**，而不是"按选择来"。
     *
     * ⚠️ 反向也成立：选了普通模式时**绝不自动升级** ——
     *   用户明确说不要 root，我们不该悄悄用 root 权限做事。
     */
    fun effectivePrivilegeMode(): PrivilegeMode =
        effectiveMode(selectedPrivilegeMode, lastRootProbe ?: RootProbeResult.NotRooted)

    /**
     * 切换模式，并**立刻探一次** root（切换时探，而不是每次调用都探）。
     *
     * @return 切换后的实际生效模式 + 探测结论，供界面显示
     */
    fun setPrivilegeMode(mode: PrivilegeMode): Pair<PrivilegeMode, RootProbeResult> {
        selectedPrivilegeMode = mode
        // 只在选 root 时才值得探 —— 选普通模式时探了也没用
        val probe = if (mode == PrivilegeMode.ROOT) RootProbe.probe() else RootProbeResult.NotRooted
        lastRootProbe = probe
        Timber.i("权限模式：选择=%s 探测=%s 实际=%s", mode, probe::class.simpleName, effectivePrivilegeMode())
        return effectivePrivilegeMode() to probe
    }

    /**
     * root 专用工具 —— **只在 root 真的可用时才挂载**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ★ 为什么要单独一个列表，而不是塞进 [agentTools]
     * ═══════════════════════════════════════════════════════════════
     *
     * [agentTools] 也会给 dsh 那条链路（MCP 桥）用。如果把 root_exec
     * 放进那个列表，dsh 那边**无论用户选什么模式都能看到它** ——
     * 那等于绕过了用户的选择。
     *
     * ⇒ root 工具只挂在聊天链路上，且必须同时满足：
     *   ① 用户选了 root ② 实际生效模式是 root
     */
    private val rootTools: List<McpTool> by lazy {
        val shellRunner = RootShellRunner()
        listOf(
            RootExecTool(
                runner = shellRunner,
                safety = SafetyGuardAdapter(DefaultSafetyGuard()),
                // ★ 传函数而不是布尔值：用户可能刚在 Magisk 里放行，
                //   而这个列表是 lazy 单例，构造时的快照会永远过时。
                rootAvailable = { effectivePrivilegeMode() == PrivilegeMode.ROOT },
            ),
        )
    }

    /**
     * 聊天链路本次该拿到的工具集。
     *
     * ★ 与原来的 [agentToolsForChat] 的差别：**按模式动态加减**。
     *   root 不可用时 root_exec 根本不出现在清单里 ——
     *   模型看不到它，也就不会去调它。
     */
    fun toolsForCurrentMode(): List<McpTool> =
        if (effectivePrivilegeMode() == PrivilegeMode.ROOT) {
            agentTools + rootTools
        } else {
            agentTools
        }

    // ═══════════════════════════════════════════════════════════════
    //  保活：两条链路共用一个前台服务（唯一锚点）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把"保活前台服务该不该在"同步到两条链路的真实状态。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 为什么必须在**四个**启停入口都调用（而不是只放进某一条链路）
     * ═══════════════════════════════════════════════════════════════
     *
     * 冷冻（cached app freezer）冻的是**整个进程**，不区分是谁的端口 ——
     * 网关（P1）同样是"应用在后台时被 dsh 调用"的形态。
     * 只在能力桥里加保活，等于让网关继续带着同一个病
     * （2026-10-03 真机实证：退后台 12 秒即超时）。
     *
     * 语义（单一事实来源，通知文本由它派生）：
     * - 任一条链路在跑 → 服务必须在（通知文本随组合变化）
     * - 两条都停了 → 服务必须走
     *
     * ⚠️ 设计上是**唯一保活锚点**（见 `docs/后台常驻与语音交互方案-v1.0.md`）：
     *    将来语音 / 悬浮球上线，在这里加它们的布尔值即可，不要再起第二个常驻服务。
     */
    /**
     * 启动期用的一次性协程作用域。
     *
     * ⚠️ 它**不属于任何界面** —— Application 级，进程活着它就活着。
     *    这里只跑"读偏好 / 恢复开关 / 写偏好"这类短任务。
     */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * ★ 把上次退出时「开着」的东西重新开起来。
     *
     * ═══════════════════════════════════════════════════════════════
     *  为什么需要它
     * ═══════════════════════════════════════════════════════════════
     *
     * `startMcpBridge()` / `startDshIntegration()` 是**内存态** —— 进程一死
     * 就回到 Off。于是用户每次打开 App 都要重新点两下开关，
     * 而"我上次明明开着"这件事**界面上一个字都没说**。
     *
     * ⚠️ 恢复的是**用户上次的选择**，不是"默认全开"：
     *    没开过的东西不会被偷偷开起来（那会让"我没开它却自己在跑"变成现实）。
     *
     * ⚠️ 恢复失败**不报错也不重试**：它发生在冷启动路径上，
     *    失败的原因几乎都是"无障碍没开""端口被占"这类**用户能看见的状态** ——
     *    界面上的开关会如实显示 Off，用户去点一下就知道为什么。
     *    在这里弹错误框只会让冷启动多一个莫名其妙的弹窗。
     */
    fun restoreEnabledIntegrations() {
        startupScope.launch {
            val prefs = runCatching { pluginSourceStore.data.first() }.getOrNull() ?: return@launch
            if (prefs[KEY_DSH_ON] == true) runCatching { startDshIntegration() }
            if (prefs[KEY_BRIDGE_ON] == true) runCatching { startMcpBridge() }
        }
    }

    /**
     * 记住用户的开关选择。
     *
     * ⚠️ **fire-and-forget**：写偏好在 IO 上异步做，不阻塞开关的返回。
     *    代价是"点了开关之后进程立刻被杀"会丢这次记录 —— 那是可接受的：
     *    丢了的表现只是"下次要再点一下"，而不是"开了没开说不清"。
     */
    private fun rememberIntegration(key: Preferences.Key<Boolean>, on: Boolean) {
        startupScope.launch { runCatching { pluginSourceStore.edit { it[key] = on } } }
    }

    /**
     * 首次启动引导是否已经看过。
     *
     * ⚠️ 判据是**一个显式标记**，不是"权限齐不齐" ——
     *    后者会让用户每次少开一个权限就被重新弹一次，那是骚扰。
     */
    val firstRunSeen: Flow<Boolean> = pluginSourceStore.data.map { it[KEY_FIRST_RUN_SEEN] == true }

    /** 用户看过了首次引导（点掉那个弹窗时调用）。 */
    suspend fun markFirstRunSeen() {
        runCatching { pluginSourceStore.edit { it[KEY_FIRST_RUN_SEEN] = true } }
    }

    private fun syncKeepAlive() {
        val gatewayOn = dshStatus is DshIntegrationStatus.On
        val bridgeOn = mcpBridgeStatus is McpBridgeStatus.On
        if (gatewayOn || bridgeOn) {
            AgentForegroundService.start(appContext, keepAliveText(gatewayOn, bridgeOn))
        } else {
            AgentForegroundService.stop(appContext)
        }
    }

    /** 通知正文 —— 用户看一眼通知就知道"现在开着什么"。 */
    private fun keepAliveText(gatewayOn: Boolean, bridgeOn: Boolean): String = when {
        gatewayOn && bridgeOn -> "模型网关与能力桥运行中 · 点按回到应用"
        gatewayOn -> "模型网关运行中 · 点按回到应用"
        else -> "能力桥运行中 · 点按回到应用"
    }

    /**
     * 写应用私有目录下的一个文件。返回是否成功。
     *
     * ⚠️ 实现**刻意只有一行** —— 真正的逻辑在 [AtomicTextFile] 里，
     *    因为本类在 `:app`（无测试源集、且依赖 `Context`），
     *    而那个逻辑的失败形态是静默的（半截 YAML 被 dsh 按缺省值读进去）。
     *    `:core:common` 是纯 Kotlin 模块，所以那份逻辑进得了离线单测。
     *
     * ⚠️ 这里**不 catch**：`AtomicTextFile.write` 已经约定不抛异常，
     *    它返回的 `false` 会一路变成 `DshWriteResult.Failure` ——
     *    那是一个**可解释的业务状态**，比异常好。
     *    在这里再包一层 try 只会把真实的编程错误（比如传错路径类型）也吞掉。
     */
    private fun writeAppPrivateFile(relativePath: String, content: String): Boolean =
        AtomicTextFile.write(File(appContext.filesDir, relativePath), content)

    private companion object {
        /**
         * 用户上次把「模型出口 / 能力桥」开着没有。
         *
         * ⚠️ 键名带 `_v1`：将来若语义变了（比如"开启"的含义从"进程内跑"变成
         *    "随系统启动"），**换键名**比"沿用旧键 + 猜旧值的意思"安全 ——
         *    后者会让老用户带着一个含义已变的 `true` 升级上来。
         */
        private val KEY_DSH_ON = booleanPreferencesKey("dsh_integration_on_v1")
        private val KEY_BRIDGE_ON = booleanPreferencesKey("mcp_bridge_on_v1")

        /** 首次启动引导是否已看过。**用户点掉弹窗才置位**，不随版本重置。 */
        private val KEY_FIRST_RUN_SEEN = booleanPreferencesKey("first_run_seen_v1")

        /**
         * 数据库口令的密文文件。
         *
         * 放 `filesDir` 而不是 `cacheDir` —— 后者会被系统在存储紧张时清掉，
         * 而它是数据库唯一的钥匙：被清掉就等于数据全丢。
         */
        const val PASSPHRASE_FILE_NAME = "db.key"

        /**
         * 上下文卸载内容的落盘目录（`filesDir/offload/`）。
         *
         * ⚠️ 与 `PASSPHRASE_FILE_NAME` 同一个理由用 `filesDir` 而不是 `cacheDir`：
         *    卸载的内容是**用户上下文的一部分**，被系统在存储紧张时清掉之后，
         *    `context_recall` 会报 Missing —— 而那时用户只会觉得"助理记性变差了"。
         *
         * ⚠️ 目录名是**相对路径**（`OffloadBlobStore` 的实现会再拼
         *    `<taskId>/<kind>/<id>.json`）。
         */
        const val OFFLOAD_DIR_NAME = "offload"
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

/**
 * dsh 集成对外的状态。
 *
 * ⚠️ 刻意用 sealed 而不是 `Boolean` / 返回 `null` —— 与 [CredentialStoreResult]
 *    同一个理由：三种情况对用户意味着**三件不同的事**，界面必须能分别说清楚。
 *
 *      · [Off]     —— 没开启（还没点过，或者用户刚关掉）
 *      · [On]      —— 网关在跑。**注意它不代表配置送达成功**，那要看 [On.delivery]
 *      · [Blocked] —— 没跑起来，且**这一次不会自己好**，用户需要先去做点什么
 *
 *    [Blocked.reason] 必须点名**用户要做的那个动作**（"先打开一次「API Key」页"），
 *    而不是"依赖未就绪"这种我们内部的说法 —— 后者用户读完不知道该干什么。
 *
 * ⚠️ 注意 [On] 里**没有** token。token 只活在容器与 `HttpGatewayServer` 之间，
 *    绝不进界面状态：界面状态会被重组、被截图、被记进日志，而 token 是
 *    "同机其它 App 能读到就完蛋"的东西。用户也不需要它 —— 他要抄的是配置里
 *    那一行 `apiKeyEnv` 指向的**变量名**，不是值。
 */
sealed interface DshIntegrationStatus {

    data object Off : DshIntegrationStatus

    /**
     * 网关在跑。
     *
     * @param baseUrl 形如 `http://127.0.0.1:12345/v1`。**每次开启都会变**
     *        （端口是随机分配的）—— 界面必须让用户看到它，
     *        否则 dsh 连不上时用户没有任何线索。
     * @param delivery 配置投递结果。可能是 `Partial` / `Failure`，见 `DshWriteResult`。
     */
    data class On(val baseUrl: String, val delivery: DshWriteResult) : DshIntegrationStatus

    data class Blocked(val reason: String) : DshIntegrationStatus
}
