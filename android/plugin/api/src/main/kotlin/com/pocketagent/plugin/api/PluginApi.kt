package com.pocketagent.plugin.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 插件契约。
 *
 * ⚠️ 本模块**必须保持零 Android 依赖**。原因：
 *    1. 插件作者要能在纯 JVM 环境开发、编译、跑单元测试，不必装 Android SDK
 *    2. 契约的稳定性高于实现，本文件一旦发布就是兼容性负债
 *
 * 核心原则：**本体提供「能力」，插件提供「意图」。**
 * 插件永远拿不到 API Key 明文、拿不到 AccessibilityNodeInfo、绕不过 SafetyGuard。
 */

// ═══════════════════════════════════════════════════════════════
//  插件清单
// ═══════════════════════════════════════════════════════════════

@Serializable
data class PluginManifest(
    /** 全局唯一 ID，建议用反向域名，如 "community.wechat.auto-reply" */
    val id: String,
    val name: String,
    val version: String,
    /** 插件 API 版本。本体据此判断兼容性 */
    @SerialName("apiVersion") val apiVersion: Int = CURRENT_API_VERSION,
    val author: String? = null,
    val license: String? = null,
    /** 插件级别：L1 规则包 / L2 脚本 / L3 原生 */
    val level: PluginLevel,
    /**
     * 声明需要的能力。本体在安装时逐项向用户申请。
     *
     * ⚠️ 用宽容序列化器：不认识的 id 丢弃而不是抛异常。理由见
     *    [PluginCapabilityListSerializer] —— 严格解析会把"本体太旧"
     *    伪装成"清单格式错误"，把用户引向错误的排查方向。
     */
    @Serializable(with = PluginCapabilityListSerializer::class)
    val capabilities: List<PluginCapability>,
    /** 生效的目标应用包名。空表示不限制（风险更高，需额外确认） */
    val targetApps: List<String> = emptyList(),
    val description: String? = null,
    val homepage: String? = null,
    /** 入口文件路径（L1 指向规则 JSON，L2 指向 JS 文件） */
    val entry: String? = null,
    /** 网络请求的域名白名单，仅在声明 network.request 能力时有效 */
    val allowedHosts: List<String> = emptyList(),
    /** 插件自定义配置界面的声明（由本体渲染，插件不能自带 UI 代码） */
    val settingsSchema: List<SettingField> = emptyList(),
    /** 内容哈希（SHA-256），用于完整性校验 */
    val sha256: String? = null,
    /** 作者签名，本体用作者公钥验签 */
    val signature: String? = null,
) {
    companion object {
        /**
         * 当前 API 版本。
         * 兼容策略：本体支持 [CURRENT_API_VERSION] 及以下所有版本；
         * 高于本版本 → 拒绝加载并提示用户升级本体。
         */
        const val CURRENT_API_VERSION = 1
    }
}

enum class PluginLevel {
    /** 声明式规则包，无代码执行。生态主力，占预期 80% */
    L1_RULES,

    /** JavaScript 沙箱脚本。需会编程，占预期 18% */
    L2_SCRIPT,

    /** 独立 APK，通过 AIDL 通信。能力最强、风险最高，M1-M3 暂不开放 */
    L3_NATIVE,
}

/**
 * 能力白名单。
 *
 * 设计要点：
 * 1. **默认全部拒绝**。插件必须显式声明，用户必须显式批准
 * 2. **`payment.*` 类能力永不开放** —— 这不是技术限制，是产品底线
 * 3. 每项能力都有对应的 [userFacingDescription]，用于向用户解释
 *    （禁止把技术名词直接甩给用户）
 */
enum class PluginCapability(
    val id: String,
    /** 给用户看的说明，用大白话 */
    val userFacingDescription: String,
    /** 风险等级，决定是否需要二次确认 */
    val risk: RiskLevel,
) {
    SCREEN_READ(
        "screen.read",
        "读取当前屏幕上的文字和按钮",
        RiskLevel.HIGH,
    ),
    SCREEN_CAPTURE(
        "screen.capture",
        "截取你的屏幕画面",
        RiskLevel.CRITICAL,
    ),
    ACTION_CLICK(
        "action.click",
        "替你点击屏幕上的按钮",
        RiskLevel.HIGH,
    ),
    ACTION_INPUT(
        "action.input",
        "替你输入文字",
        RiskLevel.HIGH,
    ),
    ACTION_GESTURE(
        "action.gesture",
        "替你滑动、长按屏幕",
        RiskLevel.MEDIUM,
    ),
    APP_LAUNCH(
        "app.launch",
        "打开指定的应用",
        RiskLevel.LOW,
    ),
    NOTIFICATION_POST(
        "notification.post",
        "给你发送通知",
        RiskLevel.LOW,
    ),
    NOTIFICATION_READ(
        "notification.read",
        "读取你的通知消息",
        RiskLevel.HIGH,
    ),
    CLIPBOARD_READ(
        "clipboard.read",
        "读取你的剪贴板内容",
        RiskLevel.HIGH,
    ),
    CLIPBOARD_WRITE(
        "clipboard.write",
        "写入内容到你的剪贴板",
        RiskLevel.MEDIUM,
    ),
    STORAGE(
        "storage",
        "保存自己的配置和数据（独立空间，不影响其他插件）",
        RiskLevel.LOW,
    ),
    LLM_CALL(
        "llm.call",
        "调用你配置的 AI 模型（⚠️ 会消耗你的 API 额度）",
        RiskLevel.MEDIUM,
    ),
    NETWORK_REQUEST(
        "network.request",
        "向指定网站发送请求",
        RiskLevel.HIGH,
    ),
    ;

    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    /** 是否需要用户二次确认 */
    val requiresExplicitConsent: Boolean
        get() = risk != RiskLevel.LOW

    companion object {
        fun fromId(id: String): PluginCapability? = entries.firstOrNull { it.id == id }

        /**
         * 永不开放的能力前缀。
         * 任何试图申请这些能力的插件都应被**拒绝安装**，并记入审计日志。
         */
        val FORBIDDEN_PREFIXES = listOf("payment.", "key.", "crypto.", "system.")
    }
}

/** 插件自定义配置字段 —— 本体负责渲染，插件不能自带 UI 代码 */
@Serializable
sealed interface SettingField {
    val key: String
    val label: String
    val description: String?

    @Serializable
    @SerialName("text")
    data class Text(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        val default: String = "",
        val multiline: Boolean = false,
        val placeholder: String? = null,
    ) : SettingField

    @Serializable
    @SerialName("switch")
    data class Switch(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        val default: Boolean = false,
    ) : SettingField

    @Serializable
    @SerialName("number")
    data class Number(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        val default: Int = 0,
        val min: Int? = null,
        val max: Int? = null,
    ) : SettingField

    @Serializable
    @SerialName("select")
    data class Select(
        override val key: String,
        override val label: String,
        override val description: String? = null,
        val options: List<Option>,
        val defaultIndex: Int = 0,
    ) : SettingField {
        @Serializable
        data class Option(val value: String, val label: String)
    }
}

// ═══════════════════════════════════════════════════════════════
//  插件运行时接口
// ═══════════════════════════════════════════════════════════════

/**
 * 插件实例。
 *
 * 生命周期由本体管理：加载 → 启用 → （按需触发）→ 禁用 → 卸载。
 * **插件不能常驻后台**（L1/L2），只能被本体按需调用。
 */
interface Plugin {
    val manifest: PluginManifest

    /**
     * 加载插件。此时应完成规则解析 / 脚本编译等准备工作。
     * @param host 能力代理。插件通过它访问本体能力，**无法直接触达平台层**
     */
    suspend fun onLoad(host: PluginHost)

    /** 启用。可在此注册触发器、恢复状态 */
    suspend fun onEnable() {}

    /** 禁用。应释放资源，停止一切活动 */
    suspend fun onDisable() {}

    /** 卸载前的清理。清理后插件数据应被删除 */
    suspend fun onUnload() {}

    /**
     * 执行一次插件逻辑。
     *
     * L1：根据规则匹配当前快照并执行动作
     * L2：执行脚本入口函数
     *
     * @return 执行结果。返回 [PluginResult.NoMatch] 表示当前页面不适用，
     *         本体不会记录为失败
     */
    suspend fun execute(context: PluginContext): PluginResult
}

/** 执行上下文 */
data class PluginContext(
    /** 当前屏幕快照（归一化后的，不是原始 AccessibilityNodeInfo） */
    val snapshot: ScreenSnapshotView,
    /** 插件配置值（用户在设置界面填的） */
    val settings: Map<String, String>,
    /** 触发来源 */
    val trigger: TriggerSource,
    /** 是否处于用户手动测试模式（此时不应产生副作用） */
    val dryRun: Boolean = false,
)

enum class TriggerSource {
    /** 屏幕内容变化触发 */
    SCREEN_CHANGED,
    /** 用户手动执行 */
    MANUAL,
    /** 定时任务 */
    SCHEDULED,
    /** 其他插件调用 */
    PLUGIN_CALL,
}

sealed interface PluginResult {
    /** 已执行了动作 */
    data class Executed(val actionsCount: Int, val note: String? = null) : PluginResult

    /** 当前页面不匹配任何规则 —— 正常情况，不算失败 */
    data object NoMatch : PluginResult

    /** 执行失败 */
    data class Failed(val reason: String) : PluginResult

    /** 需要用户介入 */
    data class NeedUser(val message: String) : PluginResult
}

/**
 * 能力代理 —— 插件与本体之间的**唯一通道**。
 *
 * ⚠️ 所有方法都经过 SafetyGuard 与权限校验。插件无法绕过。
 *
 * 实现要求：
 * 1. 每次调用前校验插件是否已获得对应能力，未获得则抛 [CapabilityDeniedException]
 * 2. 每次调用都记入审计日志
 * 3. 敏感页面拦截在**代理层**执行，插件感知不到
 * 4. LLM 调用不得暴露 API Key，本体代为发起
 */
interface PluginHost {

    // ── 感知（需 screen.read / screen.capture）──────────────

    /** 获取当前屏幕的归一化视图。**不返回原始节点对象** */
    suspend fun getSnapshot(): ScreenSnapshotView

    /** 在快照中按选择器查找节点 */
    fun findNodes(selector: String): List<NodeView>

    /** 截取屏幕（需 screen.capture）。返回的图片由本体管理生命周期 */
    suspend fun captureScreen(): CaptureHandle?

    // ── 动作（需 action.*）──────────────────────────────────

    suspend fun click(target: Target): ActionOutcome
    suspend fun longPress(target: Target, durationMs: Long = 600): ActionOutcome
    suspend fun inputText(text: String): ActionOutcome
    suspend fun scroll(direction: ScrollDirection, distancePx: Int? = null): ActionOutcome
    suspend fun swipe(from: Point, to: Point, durationMs: Long = 300): ActionOutcome
    suspend fun pressBack(): ActionOutcome
    suspend fun pressHome(): ActionOutcome

    /** 打开应用或深层链接（需 app.launch） */
    suspend fun launchApp(packageName: String? = null, deepLink: String? = null): ActionOutcome

    // ── 模型（需 llm.call）─────────────────────────────────

    /**
     * 调用用户配置的模型。
     *
     * ⚠️ 本体代为发起请求，插件**永远拿不到 API Key**。
     * ⚠️ 会消耗用户的 API 额度，UI 上必须让用户知情。
     *
     * @param model 指定模型，null 表示用路由默认值
     */
    suspend fun callLlm(prompt: String, model: String? = null, jsonMode: Boolean = false): LlmResponse

    // ── 存储（需 storage）──────────────────────────────────

    /** 插件私有存储，与其他插件隔离 */
    val storage: PluginStorage

    // ── 通知与剪贴板 ───────────────────────────────────────

    suspend fun postNotification(title: String, content: String)
    suspend fun readClipboard(): String?
    suspend fun writeClipboard(text: String)

    // ── 网络（需 network.request，且受 allowedHosts 限制）────

    suspend fun httpGet(url: String): HttpResult
    suspend fun httpPost(url: String, body: String, contentType: String = "application/json"): HttpResult

    // ── 日志 ───────────────────────────────────────────────

    /** 写入插件运行日志。会经过脱敏，且长度受限 */
    fun log(message: String)
}

/** 屏幕视图 —— 送给插件的归一化表示（只读） */
data class ScreenSnapshotView(
    val packageName: String,
    val activityName: String?,
    val screenWidth: Int,
    val screenHeight: Int,
    val nodes: List<NodeView>,
    /** 屏幕上的文本摘要，便于 L2 脚本快速判断 */
    val textSummary: String,
) {
    fun findText(text: String, exact: Boolean = false): List<NodeView> =
        nodes.filter { node ->
            val t = node.text ?: node.contentDescription ?: return@filter false
            if (exact) t == text else t.contains(text)
        }
}

/** 节点视图（只读） */
data class NodeView(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val className: String,
    val bounds: RectView,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val depth: Int,
)

data class RectView(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class Point(val x: Int, val y: Int)

enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

/** 动作目标 —— 优先用选择器，坐标是兜底 */
sealed interface Target {
    data class BySelector(val selector: String) : Target
    data class ByCoord(val x: Int, val y: Int) : Target
    data class ByNodeId(val nodeId: String) : Target
}

sealed interface ActionOutcome {
    data object Dispatched : ActionOutcome
    data class NotFound(val target: String) : ActionOutcome
    data class Blocked(val reason: String) : ActionOutcome
    data class Failed(val reason: String) : ActionOutcome
}

data class LlmResponse(val text: String, val tokensUsed: Int)

/** 截图句柄 —— 图片数据由本体管理，插件只能拿到引用 */
data class CaptureHandle(val id: String, val width: Int, val height: Int)

interface PluginStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

data class HttpResult(val code: Int, val body: String)

/** 能力未授权 */
class CapabilityDeniedException(val capability: PluginCapability) :
    Exception("插件未获得 ${capability.id} 能力授权")

// ═══════════════════════════════════════════════════════════════
//  L1 规则包
// ═══════════════════════════════════════════════════════════════

@Serializable
data class RulePack(
    val manifest: PluginManifest,
    val rules: List<Rule>,
)

@Serializable
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    /** 数值越大越先执行 */
    val priority: Int = 0,
    val match: MatchSpec,
    val actions: List<RuleAction>,
    val constraints: Constraints = Constraints(),
)

@Serializable
data class MatchSpec(
    /** 目标包名 */
    val packageName: String? = null,
    /** 目标 Activity（支持前缀匹配） */
    val activity: String? = null,
    /** 必须全部满足 */
    val conditions: List<Condition> = emptyList(),
    /**
     * 排除条件 —— 任一满足则不匹配。
     * ⚠️ 这是安全设计的关键：用于排除支付、转账等页面。
     */
    val excludeConditions: List<Condition> = emptyList(),
)

@Serializable
sealed interface Condition {
    @Serializable
    @SerialName("nodeExists")
    data class NodeExists(val selector: String, val timeoutMs: Int = 2000) : Condition

    @Serializable
    @SerialName("textContains")
    data class TextContains(val value: String) : Condition

    @Serializable
    @SerialName("textEquals")
    data class TextEquals(val value: String) : Condition

    @Serializable
    @SerialName("activityIs")
    data class ActivityIs(val value: String) : Condition

    @Serializable
    @SerialName("packageIs")
    data class PackageIs(val value: String) : Condition

    @Serializable
    @SerialName("timeBetween")
    data class TimeBetween(val startHour: Int, val endHour: Int) : Condition
}

@Serializable
sealed interface RuleAction {
    @Serializable
    @SerialName("click")
    data class Click(val selector: String? = null, val x: Int? = null, val y: Int? = null) : RuleAction

    @Serializable
    @SerialName("longPress")
    data class LongPress(val selector: String, val durationMs: Long = 600) : RuleAction

    @Serializable
    @SerialName("inputText")
    data class InputText(val text: String) : RuleAction

    @Serializable
    @SerialName("scroll")
    data class Scroll(val direction: ScrollDirection, val distancePx: Int? = null) : RuleAction

    @Serializable
    @SerialName("swipe")
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long = 300) : RuleAction

    @Serializable
    @SerialName("back")
    data object Back : RuleAction

    @Serializable
    @SerialName("home")
    data object Home : RuleAction

    @Serializable
    @SerialName("openApp")
    data class OpenApp(val packageName: String? = null, val deepLink: String? = null) : RuleAction

    @Serializable
    @SerialName("wait")
    data class Wait(val ms: Long) : RuleAction

    @Serializable
    @SerialName("notify")
    data class Notify(val title: String, val content: String) : RuleAction

    /** 调用模型。需 llm.call 能力，且会消耗用户额度 */
    @Serializable
    @SerialName("callLlm")
    data class CallLlm(val prompt: String, val model: String? = null) : RuleAction
}

/**
 * 约束 —— 防止规则失控。
 * 本体强制执行，插件无法覆盖。
 */
@Serializable
data class Constraints(
    /** 每分钟最多触发次数 */
    val maxTriggersPerMinute: Int = 6,
    /** 两次触发之间的最小间隔 */
    val cooldownMs: Long = 5_000,
    /** 是否要求屏幕点亮 */
    val requireScreenOn: Boolean = true,
    /** 单次执行的最大动作数 */
    val maxActions: Int = 20,
)

// ═══════════════════════════════════════════════════════════════
//  订阅源
// ═══════════════════════════════════════════════════════════════

/**
 * 订阅源 —— 一个静态 JSON 文件，托管在任意静态存储（推荐 GitHub Pages / raw）。
 *
 * ⚠️ 设计要点：**订阅源本身不能获得任何权限**，它只是插件清单的索引。
 *    每个插件的权限仍需逐个向用户申请。
 */
@Serializable
data class SubscriptionSource(
    val name: String,
    val author: String? = null,
    val updatedAt: String? = null,
    @SerialName("apiVersion") val apiVersion: Int = PluginManifest.CURRENT_API_VERSION,
    val plugins: List<SubscriptionEntry>,
)

@Serializable
data class SubscriptionEntry(
    val id: String,
    val name: String,
    val version: String,
    val level: PluginLevel,
    val capabilities: List<String>,
    val targetApps: List<String> = emptyList(),
    /** 插件文件的下载地址 */
    val downloadUrl: String,
    /** 必须校验，不匹配则拒绝加载 */
    val sha256: String,
    /** 作者签名 */
    val signature: String? = null,
    /**
     * 插件是干什么的。**必须能传到界面上。**
     *
     * ⚠️ 这三个展示字段是补上来的，原因值得记一笔：
     *
     *    市场界面一直在渲染 `description` 与 `author`，但本类**根本没有这两个字段**，
     *    于是 [toMarketEntry] 只能传 null —— 用户在市场上永远看不到任何插件说明，
     *    只能看到一个名字和几个能力标签。而与此同时 [PluginValidator] 还在警告
     *    作者「没有填写插件描述，用户在市场上无法判断这个插件做什么」。
     *
     *    一边要求作者写描述，一边市场没地方放描述。这种矛盾不会报错，
     *    只会让整个市场退化成"一串不敢点的名字"—— 而用户判断一个插件
     *    能不能装，靠的恰恰是这段描述。
     *
     *    这三项都给了默认值，所以旧格式的源 JSON 依然能解析（只是描述为空）。
     */
    val description: String? = null,
    val author: String? = null,
    /** 插件最后更新时间。市场按此排序，也让用户看出哪些是"上个月就没再动过"的 */
    val updatedAt: String? = null,
)
