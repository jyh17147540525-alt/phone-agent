package com.pocketagent.safety

/**
 * 安全护栏 —— 产品的**合规底线与信任来源**。
 *
 * 三条不可协商的原则：
 * 1. **敏感页面硬拦截**：命中即停止、丢弃截图、交还用户。规则只允许加严，不允许用户配置绕过。
 * 2. **危险动作二次确认**：涉及资金、删除、发送等不可逆操作，必须用户显式确认。
 * 3. **尊重第三方 App 的自动化声明**：兼容豆包 SAEP 类协议，对声明拒绝的 App 降级为引导模式。
 *
 * 这三条不是技术限制，是**产品选择**。一个「会拒绝执行危险操作的助手」比
 * 「什么都敢点的助手」活得更久，也更值得用户托付屏幕权限。
 */
interface SafetyGuard {

    /**
     * 采集前的检查。命中敏感页面时**必须返回 Blocked**，
     * 且调用方必须确保已采集的截图被丢弃（见 ScreenSnapshot.dispose）。
     */
    fun checkBeforeCapture(packageName: String, activityName: String?): SafetyVerdict

    /**
     * 动作执行前的检查。包括：
     * - 当前页面是否敏感
     * - 动作本身是否危险（点击「确认支付」）
     * - 目标 App 是否允许自动化
     * - 频率是否超限
     */
    suspend fun checkBeforeAction(action: ActionContext): SafetyVerdict

    /** 记录一次拦截，用于本地审计与用户可见的透明度报告 */
    fun recordBlock(event: SafetyBlockEvent)
}

/** 检查结论 */
sealed interface SafetyVerdict {
    /** 放行 */
    data object Allowed : SafetyVerdict

    /**
     * 拦截。附带对用户展示的说明 —— 措辞必须让用户理解「为什么」，
     * 而不是简单的「操作失败」。
     */
    data class Blocked(
        val reason: BlockReason,
        val userMessage: String,
        /** 是否可降级为「引导用户手动完成」 */
        val canFallbackToManual: Boolean,
    ) : SafetyVerdict

    /** 需要用户显式确认后才能继续 */
    data class RequireConfirmation(
        val reason: BlockReason,
        val userMessage: String,
        /** 确认超时（毫秒），超时视为拒绝 */
        val timeoutMs: Long = 30_000,
    ) : SafetyVerdict
}

enum class BlockReason {
    /** 敏感页面：支付、密码、验证码、银行 */
    SENSITIVE_PAGE,
    /** 命中目标 App 的自动化禁令（SAEP 类协议） */
    APP_OPT_OUT,
    /** 危险动作：转账、删除、发送 */
    DANGEROUS_ACTION,
    /** 操作频率超限 */
    RATE_LIMITED,
    /** 命中用户自定义的黑名单 */
    USER_BLACKLIST,
    /** 检测到验证码 */
    CAPTCHA_DETECTED,
    /**
     * 由护栏之外的代码补记的拦截（执行层发现异常、用户主动接管等）。
     *
     * 保留这一项是为了让审计日志**完整** —— 如果外部拦截无处记录，
     * 透明度报告就会只显示一半的事实，那比不显示更误导人。
     */
    UNKNOWN_OR_EXTERNAL,
}

/** 动作上下文 */
data class ActionContext(
    val packageName: String,
    val activityName: String?,
    val actionType: String,
    /** 动作指向元素的描述，如「底部的『确认支付』按钮」 */
    val targetDescription: String?,
    /** 当前页面的可见文本，用于关键词匹配 */
    val visibleTexts: List<String>,
    /**
     * 页面上的输入控件**形状**。
     *
     * ⚠️ 只带 hint / className / inputType，**不带用户已输入的内容**。
     *    判断"这是不是密码框"不需要看里面写了什么，多带一个字段就是多一条泄漏路径。
     */
    val inputFields: List<InputFieldSignature> = emptyList(),
    /** 本次任务已执行的步数 */
    val executedSteps: Int = 0,
    /** 最近一分钟内的动作数 */
    val actionsInLastMinute: Int = 0,
)

/** 拦截事件（写入本地审计日志，不含敏感内容） */
data class SafetyBlockEvent(
    val timestamp: Long,
    val packageName: String,
    val reason: BlockReason,
    /** 仅记录事件描述，绝不记录页面内容 */
    val description: String,
)

// 规则库与匹配引擎见 SensitiveRules.kt。
//
// 早先这里有个 `SensitivePageRules` 对象，用「把文本拼起来做子串匹配」判定敏感页面。
// 它已经删掉了，原因是三处漏判（漏判比误判危险得多）：
//   1. 空格会打断匹配 —— 无障碍树把「确认支付」拆成两个节点，拼起来是「确认 支付」，漏判
//   2. 全角/半角不统一 —— 「立即付款」（全角空格）匹配不到「立即付款」，漏判
//   3. 控件特征清单（密码框、验证码框）定义了却从未被使用
//
// 现在的实现见 [SensitiveDetector]，配套规则见 [SensitiveRules]。

/**
 * 第三方 App 自动化许可注册表（SAEP 兼容）。
 *
 * 背景：2026-09-14 豆包随「操作手机」Beta 发布 SAEP
 * （Screen Automation Execution Protocol），第三方 App 可声明
 * 是否接受 AI 助手在其内进行屏幕自动化操作。
 *
 * 本产品的立场：**主动兼容**。对声明拒绝的 App，功能降级为「展示步骤 + 引导用户手动执行」，
 * 并在 UI 上明确说明原因。把限制变成透明度，而不是偷偷绕过。
 */
interface AutomationPolicyRegistry {

    /** 该 App 是否允许自动化。未声明的默认允许（与行业现状一致） */
    fun isAutomationAllowed(packageName: String): AutomationPolicy

    /** 记录用户手动为本 App 设置的策略 */
    fun setUserPolicy(packageName: String, policy: AutomationPolicy)

    /** 从远端同步的声明列表（如果未来出现标准化接口） */
    fun syncFromRemote(): Boolean
}

sealed interface AutomationPolicy {
    /** 未声明，默认允许 */
    data object Undeclared : AutomationPolicy
    /** 明确允许 */
    data object Allowed : AutomationPolicy
    /** 明确拒绝 —— 必须降级为引导模式 */
    data class Denied(val reason: String?) : AutomationPolicy
    /** 用户手动禁止 */
    data object UserBlocked : AutomationPolicy
}
