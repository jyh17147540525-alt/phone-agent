package com.pocketagent.safety

import java.util.concurrent.ConcurrentHashMap

/**
 * [SafetyGuard] 的默认实现。
 *
 * ═══════════════════════════════════════════════════════════════
 *  检查顺序不是随便排的
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * 1. 内置包名黑名单     ← 最先。任何东西都不能越过它
 * 2. App 自动化声明      ← 第三方 App 的意愿
 * 3. 页面敏感关键词
 * 4. 敏感输入控件
 * 5. 操作频率            ← 到这里才轮到"工程性"限制
 * 6. 危险动作二次确认    ← 最后才是"问一下用户"
 * ```
 *
 * **为什么黑名单必须排第一，而不是"和用户设置合并后再判断"？**
 *
 * 因为原则 4 写的是「规则只允许加严，不允许用户配置绕过」。
 * 如果实现成"用户设置覆盖内置规则"，那么一个被诱导的用户（或一个恶意插件）
 * 只要把 `com.eg.android.AlipayGphone` 设成 `Allowed`，整个护栏就没了。
 * 所以内置黑名单是一条**单向阀**：只能往上加，不能往下减，也不参与任何覆盖逻辑。
 *
 * 同理，第 5 步（频率）排在第 3、4 步之后：如果当前页面是支付页，
 * 用户应该看到"这是支付页面"，而不是"你操作太快了" —— 后者会误导用户
 * 以为"慢一点就能继续"。
 */
class DefaultSafetyGuard(
    private val rules: SensitiveRules = SensitiveRules.Builtin,
    private val policyRegistry: AutomationPolicyRegistry = InMemoryAutomationPolicyRegistry(),
    /**
     * 每分钟动作数硬上限。
     *
     * 注意与 `HumanizePolicy.MAX_ACTIONS_PER_MINUTE`（20/分钟）的区别：
     * 那个是**拟人化节奏**，用来让操作看起来自然；这个是**安全硬闸**，
     * 用来在 agent 陷入死循环时兜住用户的钱。两者职责不同，数值也不必相同，
     * 但这里必须 **≥** 那个，否则正常任务会被安全层打断。
     */
    private val maxActionsPerMinute: Int = DEFAULT_MAX_ACTIONS_PER_MINUTE,
    /** 审计事件出口。接数据库或日志时传入；不传则只留在内存里 */
    private val auditSink: ((SafetyBlockEvent) -> Unit)? = null,
) : SafetyGuard {

    private val auditLog = mutableListOf<SafetyBlockEvent>()

    /** 本地审计记录，供「透明度报告」页面展示 */
    @Synchronized
    fun auditEvents(): List<SafetyBlockEvent> = auditLog.toList()

    @Synchronized
    private fun record(event: SafetyBlockEvent) {
        // 上限保护：护栏自己不能变成内存泄漏源
        if (auditLog.size >= MAX_AUDIT_EVENTS) auditLog.removeAt(0)
        auditLog += event
        auditSink?.invoke(event)
    }

    // ═══════════════════════════════════════════════════════════
    //  采集前
    // ═══════════════════════════════════════════════════════════

    override fun checkBeforeCapture(packageName: String, activityName: String?): SafetyVerdict {
        // 1. 内置黑名单
        SensitiveDetector.matchPackage(packageName, rules)?.let { match ->
            return block(match, packageName, action = "capture")
        }

        // 2. 第三方 App 的自动化声明
        policyBlock(packageName)?.let { return it }

        return SafetyVerdict.Allowed
    }

    // ═══════════════════════════════════════════════════════════
    //  动作前
    // ═══════════════════════════════════════════════════════════

    override suspend fun checkBeforeAction(action: ActionContext): SafetyVerdict {
        val pkg = action.packageName

        // 1. 内置黑名单
        SensitiveDetector.matchPackage(pkg, rules)?.let { match ->
            return block(match, pkg, action.actionType, action.executedSteps)
        }

        // 2. App 声明
        policyBlock(pkg, action.executedSteps)?.let { return it }

        // 3. 页面文本
        SensitiveDetector.matchTexts(action.visibleTexts, rules)?.let { match ->
            return block(match, pkg, action.actionType, action.executedSteps)
        }

        // 4. 敏感控件
        SensitiveDetector.matchFields(action.inputFields, rules)?.let { match ->
            return block(match, pkg, action.actionType, action.executedSteps)
        }

        // 5. 频率
        if (maxActionsPerMinute > 0 && action.actionsInLastMinute >= maxActionsPerMinute) {
            val event = SafetyBlockEvent(
                timestamp = System.currentTimeMillis(),
                packageName = pkg,
                reason = BlockReason.RATE_LIMITED,
                description = "一分钟内已执行 ${action.actionsInLastMinute} 次动作，" +
                    "达到上限 $maxActionsPerMinute（动作：${action.actionType}）",
            )
            record(event)
            return SafetyVerdict.Blocked(
                reason = BlockReason.RATE_LIMITED,
                userMessage = "操作太频繁了，我先停下来。这通常意味着任务卡住了 —— " +
                    "请检查一下当前页面，然后重新告诉我你想做什么。",
                // 频率限制不是"这件事不能做"，交还用户手动也解决不了卡住的问题，
                // 所以不给"引导模式"这条退路，而是要求用户重新下达指令。
                canFallbackToManual = false,
            )
        }

        // 6. 危险动作 → 二次确认
        SensitiveDetector.matchDangerousAction(action.targetDescription, rules)?.let { keyword ->
            val event = SafetyBlockEvent(
                timestamp = System.currentTimeMillis(),
                packageName = pkg,
                reason = BlockReason.DANGEROUS_ACTION,
                description = "动作「${action.actionType}」的目标命中危险关键词「$keyword」",
            )
            record(event)
            return SafetyVerdict.RequireConfirmation(
                reason = BlockReason.DANGEROUS_ACTION,
                userMessage = "我准备点击「${action.targetDescription ?: keyword}」，" +
                    "这个操作可能无法撤销。要继续吗？",
            )
        }

        return SafetyVerdict.Allowed
    }

    // ═══════════════════════════════════════════════════════════
    //  审计
    // ═══════════════════════════════════════════════════════════

    override fun recordBlock(event: SafetyBlockEvent) = record(event)

    // ═══════════════════════════════════════════════════════════
    //  内部
    // ═══════════════════════════════════════════════════════════

    /** App 自动化策略判定。返回 null 表示放行 */
    private fun policyBlock(
        packageName: String,
        executedSteps: Int = 0,
    ): SafetyVerdict.Blocked? = when (val policy = policyRegistry.isAutomationAllowed(packageName)) {
        is AutomationPolicy.Denied -> {
            val reason = policy.reason?.takeIf { it.isNotBlank() }
            val message = if (reason != null) {
                "「$packageName」声明不接受自动化操作（$reason）。" +
                    "我可以把步骤列出来，你手动操作。"
            } else {
                "「$packageName」声明不接受自动化操作。我可以把步骤列出来，你手动操作。"
            }
            blocked(
                BlockReason.APP_OPT_OUT,
                packageName,
                message,
                description = "App 声明拒绝自动化${reason?.let { "：$it" } ?: ""}",
                executedSteps = executedSteps,
            )
        }

        AutomationPolicy.UserBlocked -> blocked(
            BlockReason.USER_BLACKLIST,
            packageName,
            "你之前把「$packageName」设成了不允许自动化。要改的话去「设置 → 应用权限」里调整。",
            description = "命中用户黑名单",
            executedSteps = executedSteps,
        )

        // Undeclared（未声明，默认允许）与 Allowed 都放行
        AutomationPolicy.Undeclared, AutomationPolicy.Allowed -> null
    }

    private fun block(
        match: SensitivityMatch,
        packageName: String,
        action: String,
        executedSteps: Int = 0,
    ): SafetyVerdict.Blocked {
        val reason = when (match.hit) {
            SensitivityHit.CAPTCHA_FIELD -> BlockReason.CAPTCHA_DETECTED
            else -> BlockReason.SENSITIVE_PAGE
        }
        return blocked(
            reason = reason,
            packageName = packageName,
            userMessage = match.hit.userMessage,
            // ⚠️ 审计里只记规则名，绝不记页面内容 —— 否则审计日志本身会变成泄漏点
            description = "${match.hit.name}（规则：${match.matchedRule ?: "-"}，动作：$action）",
            executedSteps = executedSteps,
        )
    }

    private fun blocked(
        reason: BlockReason,
        packageName: String,
        userMessage: String,
        description: String,
        executedSteps: Int = 0,
    ): SafetyVerdict.Blocked {
        record(
            SafetyBlockEvent(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                reason = reason,
                description = if (executedSteps > 0) "$description，已执行 $executedSteps 步" else description,
            )
        )
        return SafetyVerdict.Blocked(
            reason = reason,
            userMessage = userMessage,
            // 所有 Blocked 都可以降级为引导模式：用户自己动手总是合法的。
            // 唯一不给这条路的是频率超限（见 checkBeforeAction 第 5 步）。
            canFallbackToManual = true,
        )
    }

    companion object {
        /** 安全硬闸。高于拟人化节奏的 20/分钟，避免正常任务被误伤 */
        const val DEFAULT_MAX_ACTIONS_PER_MINUTE = 60

        /** 内存中保留的审计事件上限（超出后丢弃最旧的） */
        internal const val MAX_AUDIT_EVENTS = 500
    }
}

/**
 * 内存版策略注册表。
 *
 * 用途：单元测试、以及数据库尚未初始化时的兜底。
 * 持久化实现见 data 层（Room 表 `automation_policy`）。
 */
class InMemoryAutomationPolicyRegistry(
    initial: Map<String, AutomationPolicy> = emptyMap(),
) : AutomationPolicyRegistry {

    private val policies = ConcurrentHashMap<String, AutomationPolicy>(initial)

    override fun isAutomationAllowed(packageName: String): AutomationPolicy =
        policies[packageName] ?: AutomationPolicy.Undeclared

    override fun setUserPolicy(packageName: String, policy: AutomationPolicy) {
        policies[packageName] = policy
    }

    /**
     * 内存实现不联网，恒返回 false。
     *
     * ⚠️ 注意：远端同步回来的策略**也只能加严**。如果将来出现标准化接口，
     *    实现方必须确保远端数据不能把某个 App 从"拒绝"改回"允许" ——
     *    否则一个被劫持的同步源就能解除全部限制。
     */
    override fun syncFromRemote(): Boolean = false
}
