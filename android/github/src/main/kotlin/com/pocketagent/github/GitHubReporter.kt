package com.pocketagent.github

/**
 * GitHub 上报与贡献通道。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 安全红线（新手最容易犯的致命错误）
 * ═══════════════════════════════════════════════════════════════
 *
 * **绝对不要把 GitHub token 硬编码进 APK。**
 *
 * APK 可以被任何人反编译。一旦 token 内置：
 *  - 任何人都能提取它，往仓库里灌垃圾、开几百个 issue、甚至删除内容
 *  - 从 GitHub 的视角看，这些操作都"来自你的应用"，你无法追责
 *  - GitHub 会检测到泄露并自动吊销，但仓库可能已经被搞了
 *
 * 正确做法：**OAuth Device Flow**，让 token 属于用户而不是你。
 * 这既解决了安全问题，又和 BYOK 的产品理念一脉相承。
 *
 * ═══════════════════════════════════════════════════════════════
 *  设计原则
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **未配置时完全静默** —— 不弹窗、不提示、不报错，功能就是"不存在"
 * 2. **凭据运行时注入** —— 复用 `:core:crypto` 的 Keystore 加密存储，不自建加密逻辑
 * 3. **仓库地址可配置** —— 不硬编码，便于将来从个人仓库迁到组织仓库
 * 4. **发送前必须预览** —— 用户看到要发什么，点确认才发
 * 5. **内容必须脱敏** —— 经 `LogSanitizer` 过滤，防止把 Key、手机号、截图内容带出去
 * 6. **失败静默降级** —— 上报失败不影响主功能，最多记本地日志
 * 7. **频次限制** —— 同类报告每天上限，防止刷屏与触发 GitHub 限流
 */
interface GitHubReporter {

    /** 当前连接状态。UI 据此决定是否展示"连接到 GitHub"入口 */
    fun connectionState(): ConnectionState

    /**
     * 通过 OAuth Device Flow 发起连接（推荐方式）。
     *
     * 流程：
     * ```
     * 1. App 请求 device_code + user_code
     * 2. UI 展示 8 位 user_code，引导用户打开 github.com/login/device 输入
     * 3. App 按 interval 轮询换取 access_token
     * 4. 成功后加密存储 token，返回已连接
     * ```
     *
     * ✅ token 属于用户，用户可随时在 GitHub 设置中撤销
     * ✅ 不需要 client secret（public client 模式），没有可泄露的长期凭据
     * ✅ Client ID 是公开的，硬编码也没关系
     */
    suspend fun startDeviceFlow(): DeviceFlowSession

    /**
     * 通过用户自备的 fine-grained token 连接（备选方式）。
     *
     * 与 BYOK 理念一致：引导用户去 GitHub 生成一个只对指定仓库
     * 有 `issues:write`（或 `contents:write`）权限的 token，粘贴进来。
     */
    suspend fun connectWithToken(token: String, repo: String): ConnectionResult

    /**
     * 断开连接。
     *
     * ⚠️ 必须同时引导用户去 GitHub 设置里撤销授权 —— 仅删除本地 token 是不够的。
     */
    suspend fun disconnect()

    /**
     * 提交一份报告（崩溃、误报、适配反馈等）。
     *
     * ⚠️ [ReportPayload.body] 必须已由调用方经 `LogSanitizer` 处理，
     *    且必须经用户预览确认。本方法不负责脱敏。
     */
    suspend fun submitReport(report: ReportPayload): SubmitResult

    /**
     * 提交规则或插件贡献（生成 PR）。
     *
     * 这是最有价值的场景 —— 社区贡献的规则直接喂养插件生态。
     */
    suspend fun submitContribution(contribution: ContributionPayload): SubmitResult

    /** 待发送队列（离线时暂存，联网后重试） */
    fun pendingQueue(): List<PendingReport>
}

// ═══════════════════════════════════════════════════════════════
//  连接状态
// ═══════════════════════════════════════════════════════════════

sealed interface ConnectionState {
    /**
     * 未配置 —— 所有上报功能静默关闭。
     * ⚠️ 这是默认状态，且在没有 token 的阶段会长期保持这个状态。
     */
    data object NotConfigured : ConnectionState

    data class Connected(
        val login: String,
        val repo: String,
        /** token 是否有过期时间（fine-grained token 会过期） */
        val expiresAt: String? = null,
    ) : ConnectionState

    /** token 已过期或被用户撤销 */
    data class Expired(val reason: String) : ConnectionState

    /** 连接异常（网络等） */
    data class Error(val reason: String) : ConnectionState
}

/** Device Flow 会话 */
data class DeviceFlowSession(
    val userCode: String,
    /** 用户在浏览器中输入的地址，通常是 https://github.com/login/device */
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
    /** 轮询等待用户完成授权 */
    val awaitResult: suspend () -> ConnectionResult,
)

sealed interface ConnectionResult {
    data class Success(val login: String) : ConnectionResult

    /** 用户尚未完成授权（继续轮询） */
    data object AuthorizationPending : ConnectionResult

    /** 用户拒绝授权 */
    data object AccessDenied : ConnectionResult

    /** device code 已过期，需重新发起 */
    data object Expired : ConnectionResult

    data class Failed(val reason: String) : ConnectionResult
}

// ═══════════════════════════════════════════════════════════════
//  上报内容
// ═══════════════════════════════════════════════════════════════

/**
 * 报告类型。
 * ⚠️ 每种类型对应一个 Issue 模板，模板在仓库的 `.github/ISSUE_TEMPLATE/` 下维护。
 */
enum class ReportKind(val label: String, val template: String) {
    /** 崩溃/错误报告 */
    CRASH("程序崩溃", "bug_report.yml"),

    /** 规则误触反馈 */
    RULE_MISFIRE("规则误触", "rule_misfire.yml"),

    /** 规则失效反馈 */
    RULE_BROKEN("规则失效", "rule_broken.yml"),

    /**
     * 机型适配反馈 —— **这个最有价值**。
     * 没有预算买测试机，就让社区帮你测。
     */
    ROM_COMPAT("机型适配", "rom_compat.yml"),

    /** 权限引导问题 */
    PERMISSION_ISSUE("权限问题", "permission_issue.yml"),
}

data class ReportPayload(
    val kind: ReportKind,
    val title: String,
    /** ⚠️ 必须已脱敏 */
    val body: String,
    /** 附加标签 */
    val labels: List<String> = emptyList(),
)

/**
 * 贡献内容（规则或插件）。
 *
 * 流程：生成文件 → 展示 diff 给用户确认 → 创建分支 → 提交 → 开 PR
 */
data class ContributionPayload(
    val kind: ContributionKind,
    /** 目标仓库（通常是官方规则仓库，可能与上报仓库不同） */
    val targetRepo: String,
    /** 文件路径（相对于仓库根） */
    val filePath: String,
    /** 文件内容 */
    val content: String,
    val commitMessage: String,
    val prTitle: String,
    val prBody: String,
)

enum class ContributionKind(val label: String) {
    /** 单条规则 */
    RULE("规则"),

    /** 完整规则包 */
    RULE_PACK("规则包"),

    /** 插件 */
    PLUGIN("插件"),

    /** 机型适配信息 */
    ROM_PROFILE("机型适配档案"),
}

data class PendingReport(
    val id: String,
    val payload: ReportPayload,
    val createdAt: Long,
    val attempts: Int,
)

sealed interface SubmitResult {
    data class Success(val url: String) : SubmitResult

    /** 离线，已加入待发送队列 */
    data object QueuedOffline : SubmitResult

    /** 触发频次限制 */
    data class RateLimited(val retryAfterSeconds: Long) : SubmitResult

    /** 未配置，静默忽略 */
    data object NotConfigured : SubmitResult

    data class Failed(val reason: String) : SubmitResult
}

// ═══════════════════════════════════════════════════════════════
//  配置
// ═══════════════════════════════════════════════════════════════

/**
 * 上报通道配置。
 *
 * ⚠️ **仓库地址必须是可配置的，不能硬编码。**
 *    理由：将来可能从个人仓库迁到组织仓库，或更换规则仓库，
 *    硬编码意味着每次都要发新版。
 *
 * 配置来源优先级：
 *   1. 本地用户配置（最高）
 *   2. 订阅源下发的配置
 *   3. 内置默认值（可为空 —— 空则功能不启用）
 */
data class ReporterConfig(
    /** 上报目标仓库，格式 "owner/repo"；为 null 时功能不启用 */
    val reportRepo: String? = null,

    /** 规则贡献目标仓库 */
    val contributionRepo: String? = null,

    /** OAuth App 的 Client ID（公开信息，可硬编码） */
    val oauthClientId: String = "",

    /** 每类报告每天的上报上限 */
    val dailyLimitPerKind: Int = 3,

    /** 是否在崩溃后自动弹出上报确认（默认关，需用户授权后才开） */
    val promptOnCrash: Boolean = false,
)
