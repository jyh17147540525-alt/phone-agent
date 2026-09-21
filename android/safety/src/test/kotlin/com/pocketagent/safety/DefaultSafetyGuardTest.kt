package com.pocketagent.safety

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DefaultSafetyGuard] 单元测试。
 *
 * 这个类是产品的**合规底线**，测试重点不在"功能对不对"，而在：
 *  1. 用户**无法**通过任何配置绕过内置黑名单（这是产品红线，不是技术细节）
 *  2. 检查顺序正确 —— 顺序错了会给出误导性的拦截理由
 *  3. 审计日志本身不会变成泄漏点
 */
class DefaultSafetyGuardTest {

    private val normalApp = "com.taobao.taobao"
    private val payApp = "com.eg.android.AlipayGphone"

    private fun guard(
        rules: SensitiveRules = SensitiveRules.Builtin,
        policies: Map<String, AutomationPolicy> = emptyMap(),
        maxActionsPerMinute: Int = DefaultSafetyGuard.DEFAULT_MAX_ACTIONS_PER_MINUTE,
        sink: ((SafetyBlockEvent) -> Unit)? = null,
    ) = DefaultSafetyGuard(
        rules = rules,
        policyRegistry = InMemoryAutomationPolicyRegistry(policies),
        maxActionsPerMinute = maxActionsPerMinute,
        auditSink = sink,
    )

    private fun ctx(
        packageName: String = normalApp,
        actionType: String = "CLICK",
        targetDescription: String? = null,
        visibleTexts: List<String> = emptyList(),
        inputFields: List<InputFieldSignature> = emptyList(),
        actionsInLastMinute: Int = 0,
        executedSteps: Int = 1,
    ) = ActionContext(
        packageName = packageName,
        activityName = null,
        actionType = actionType,
        targetDescription = targetDescription,
        visibleTexts = visibleTexts,
        inputFields = inputFields,
        actionsInLastMinute = actionsInLastMinute,
        executedSteps = executedSteps,
    )

    private fun check(g: DefaultSafetyGuard, context: ActionContext): SafetyVerdict =
        runBlocking { g.checkBeforeAction(context) }

    // ═══════════════════════════════════════════════════════════
    //  一、内置黑名单不可绕过（产品红线）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `黑名单应用在采集前就被拦下`() {
        val verdict = guard().checkBeforeCapture(payApp, "MainActivity")

        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockReason.SENSITIVE_PAGE, (verdict as SafetyVerdict.Blocked).reason)
        assertTrue("应允许降级为手动引导", verdict.canFallbackToManual)
    }

    @Test
    fun `黑名单应用在动作前被拦下`() {
        val verdict = check(guard(), ctx(packageName = payApp, targetDescription = "首页搜索框"))

        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockReason.SENSITIVE_PAGE, (verdict as SafetyVerdict.Blocked).reason)
    }

    /**
     * ★ 核心红线测试。
     *
     * 用户（或被诱导的用户、或恶意插件）把黑名单 App 设成 `Allowed`，
     * **必须依然被拦**。如果这条测试失败，说明"规则只允许加严，不允许用户
     * 配置绕过"这条产品原则在代码里已经不成立了。
     */
    @Test
    fun `用户把黑名单应用设为允许，依然被拦`() {
        val g = guard(policies = mapOf(payApp to AutomationPolicy.Allowed))

        val verdict = check(g, ctx(packageName = payApp))

        assertTrue("用户设置不能越过内置黑名单", verdict is SafetyVerdict.Blocked)
        assertEquals(BlockReason.SENSITIVE_PAGE, (verdict as SafetyVerdict.Blocked).reason)
    }

    @Test
    fun `用户把黑名单应用设为允许，采集前也依然被拦`() {
        val g = guard(policies = mapOf(payApp to AutomationPolicy.Allowed))

        assertTrue(g.checkBeforeCapture(payApp, null) is SafetyVerdict.Blocked)
    }

    @Test
    fun `黑名单应用在用户追加规则后依然被拦`() {
        val g = guard(rules = SensitiveRules.Builtin.withUserRules(
            extraPackages = setOf("com.example.shop"),
            extraKeywords = listOf("确认下单"),
        ))

        assertTrue(check(g, ctx(packageName = payApp)) is SafetyVerdict.Blocked)
    }

    // ═══════════════════════════════════════════════════════════
    //  二、App 自动化声明
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `App 声明拒绝自动化时降级为引导`() {
        val g = guard(policies = mapOf(normalApp to AutomationPolicy.Denied("企业合规要求")))

        val verdict = check(g, ctx())

        assertTrue(verdict is SafetyVerdict.Blocked)
        val blocked = verdict as SafetyVerdict.Blocked
        assertEquals(BlockReason.APP_OPT_OUT, blocked.reason)
        assertTrue(blocked.canFallbackToManual)
        assertTrue("应把 App 给出的理由转达用户：${blocked.userMessage}", blocked.userMessage.contains("企业合规要求"))
    }

    @Test
    fun `用户手动禁止的应用被拦并给出改回路径`() {
        val g = guard(policies = mapOf(normalApp to AutomationPolicy.UserBlocked))

        val verdict = check(g, ctx()) as SafetyVerdict.Blocked

        assertEquals(BlockReason.USER_BLACKLIST, verdict.reason)
        assertTrue("要告诉用户去哪儿改：${verdict.userMessage}", verdict.userMessage.contains("设置"))
    }

    @Test
    fun `未声明策略的应用默认放行`() {
        assertEquals(SafetyVerdict.Allowed, check(guard(), ctx()))
    }

    @Test
    fun `显式允许的应用放行`() {
        val g = guard(policies = mapOf(normalApp to AutomationPolicy.Allowed))

        assertEquals(SafetyVerdict.Allowed, check(g, ctx()))
    }

    // ═══════════════════════════════════════════════════════════
    //  三、页面与控件级拦截
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `普通应用里出现支付关键词也要拦`() {
        // 关键点：不能只看包名。微信、淘宝里都有支付流程
        val verdict = check(guard(), ctx(visibleTexts = listOf("订单详情", "确认支付")))

        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockReason.SENSITIVE_PAGE, (verdict as SafetyVerdict.Blocked).reason)
    }

    @Test
    fun `密码框触发拦截`() {
        val verdict = check(
            guard(),
            ctx(inputFields = listOf(InputFieldSignature(hint = "请输入登录密码"))),
        )

        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockReason.SENSITIVE_PAGE, (verdict as SafetyVerdict.Blocked).reason)
    }

    @Test
    fun `验证码框使用独立的拦截原因`() {
        // 验证码要用 CAPTCHA_DETECTED 而不是笼统的 SENSITIVE_PAGE，
        // 因为 UI 上要给不同的引导（"请你自己填验证码" vs "这里不能自动化"）
        val verdict = check(
            guard(),
            ctx(inputFields = listOf(InputFieldSignature(hint = "短信验证码"))),
        )

        assertTrue(verdict is SafetyVerdict.Blocked)
        assertEquals(BlockReason.CAPTCHA_DETECTED, (verdict as SafetyVerdict.Blocked).reason)
    }

    @Test
    fun `inputType 标记的密码框即使没有 hint 也能识别`() {
        val verdict = check(
            guard(),
            ctx(inputFields = listOf(InputFieldSignature(hint = "请输入", isPassword = true))),
        )

        assertEquals(BlockReason.SENSITIVE_PAGE, (verdict as SafetyVerdict.Blocked).reason)
    }

    @Test
    fun `用户追加的关键词同样生效`() {
        val g = guard(rules = SensitiveRules.Builtin.withUserRules(extraKeywords = listOf("确认下单")))

        assertTrue(check(g, ctx(visibleTexts = listOf("确认下单"))) is SafetyVerdict.Blocked)
    }

    @Test
    fun `用户追加的包名同样生效`() {
        val g = guard(rules = SensitiveRules.Builtin.withUserRules(extraPackages = setOf("com.example.bank")))

        assertTrue(check(g, ctx(packageName = "com.example.bank")) is SafetyVerdict.Blocked)
    }

    // ═══════════════════════════════════════════════════════════
    //  四、频率限制
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `频率超限被拦且不给引导退路`() {
        val g = guard(maxActionsPerMinute = 20)

        val verdict = check(g, ctx(actionsInLastMinute = 20)) as SafetyVerdict.Blocked

        assertEquals(BlockReason.RATE_LIMITED, verdict.reason)
        assertFalse(
            "频率超限意味着任务可能卡住了，交还用户手动也没用，不该给引导退路",
            verdict.canFallbackToManual,
        )
    }

    @Test
    fun `未达频率上限时放行`() {
        val g = guard(maxActionsPerMinute = 20)

        assertEquals(SafetyVerdict.Allowed, check(g, ctx(actionsInLastMinute = 19)))
    }

    @Test
    fun `频率上限设为 0 表示不限制`() {
        val g = guard(maxActionsPerMinute = 0)

        assertEquals(SafetyVerdict.Allowed, check(g, ctx(actionsInLastMinute = 99_999)))
    }

    // ═══════════════════════════════════════════════════════════
    //  五、危险动作二次确认
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `点击支付按钮要求二次确认而不是直接拦`() {
        // 这是"用户明确要求付款"的正常路径，不该一刀切拒绝
        val verdict = check(guard(), ctx(targetDescription = "底部的「确认支付」按钮"))

        assertTrue(verdict is SafetyVerdict.RequireConfirmation)
        val confirm = verdict as SafetyVerdict.RequireConfirmation
        assertEquals(BlockReason.DANGEROUS_ACTION, confirm.reason)
        assertTrue("确认超时必须为正", confirm.timeoutMs > 0)
    }

    @Test
    fun `确认文案里要带上具体目标，而不是干巴巴一句危险`() {
        val verdict = check(guard(), ctx(targetDescription = "「立即购买」按钮")) as SafetyVerdict.RequireConfirmation

        assertTrue("应引用具体目标：${verdict.userMessage}", verdict.userMessage.contains("立即购买"))
    }

    @Test
    fun `点击普通按钮不需要确认`() {
        assertEquals(SafetyVerdict.Allowed, check(guard(), ctx(targetDescription = "顶部的搜索框")))
    }

    @Test
    fun `没有目标描述时不触发确认`() {
        assertEquals(SafetyVerdict.Allowed, check(guard(), ctx(targetDescription = null)))
    }

    // ═══════════════════════════════════════════════════════════
    //  六、检查顺序
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `敏感页面优先于频率超限报告`() {
        // 顺序错了会误导用户："操作太快了"会让用户以为慢一点就能继续
        val verdict = check(
            guard(maxActionsPerMinute = 10),
            ctx(visibleTexts = listOf("确认支付"), actionsInLastMinute = 999),
        ) as SafetyVerdict.Blocked

        assertEquals(BlockReason.SENSITIVE_PAGE, verdict.reason)
    }

    @Test
    fun `黑名单优先于危险动作确认`() {
        // 在支付宝里点「确认支付」不该弹"要继续吗"，应该直接拒绝
        val verdict = check(
            guard(),
            ctx(packageName = payApp, targetDescription = "确认支付"),
        ) as SafetyVerdict.Blocked

        assertEquals(BlockReason.SENSITIVE_PAGE, verdict.reason)
    }

    @Test
    fun `App 声明优先于页面关键词`() {
        // 两者都拦，但"该 App 不接受自动化"比"这个页面敏感"更准确
        val g = guard(policies = mapOf(normalApp to AutomationPolicy.Denied(null)))

        val verdict = check(g, ctx(visibleTexts = listOf("确认支付"))) as SafetyVerdict.Blocked

        assertEquals(BlockReason.APP_OPT_OUT, verdict.reason)
    }

    @Test
    fun `危险动作确认优先于放行`() {
        // 确认是最后一关，前面全过才轮到它
        val verdict = check(
            guard(),
            ctx(visibleTexts = listOf("商品详情"), targetDescription = "立即购买"),
        )

        assertTrue(verdict is SafetyVerdict.RequireConfirmation)
    }

    // ═══════════════════════════════════════════════════════════
    //  七、审计
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每次拦截都留下审计记录`() {
        val g = guard()

        g.checkBeforeCapture(payApp, null)
        check(g, ctx(visibleTexts = listOf("确认支付")))
        check(g, ctx(inputFields = listOf(InputFieldSignature(hint = "支付密码"))))

        assertEquals(3, g.auditEvents().size)
    }

    @Test
    fun `放行不产生审计记录`() {
        val g = guard()

        check(g, ctx())

        assertTrue(g.auditEvents().isEmpty())
    }

    @Test
    fun `审计记录里不能出现页面内容`() {
        // ★ 审计日志本身不能变成泄漏点。
        // 只允许记"命中了哪条规则"，不允许记页面上的原始文本。
        val g = guard()
        val secret = "订单号XYZZY9999"

        check(g, ctx(visibleTexts = listOf(secret, "确认支付")))

        val event = g.auditEvents().single()
        assertFalse("审计描述泄漏了页面内容：${event.description}", event.description.contains(secret))
    }

    @Test
    fun `用户可见的拦截说明里也不能出现页面内容`() {
        val g = guard()
        val secret = "订单号XYZZY9999"

        val verdict = check(g, ctx(visibleTexts = listOf(secret, "确认支付"))) as SafetyVerdict.Blocked

        assertFalse("拦截说明泄漏了页面内容：${verdict.userMessage}", verdict.userMessage.contains(secret))
    }

    @Test
    fun `审计记录携带包名与原因`() {
        val g = guard()

        g.checkBeforeCapture(payApp, null)

        val event = g.auditEvents().single()
        assertEquals(payApp, event.packageName)
        assertEquals(BlockReason.SENSITIVE_PAGE, event.reason)
        assertTrue(event.timestamp > 0)
    }

    @Test
    fun `审计出口会被回调`() {
        val collected = mutableListOf<SafetyBlockEvent>()
        val g = guard(sink = { collected += it })

        check(g, ctx(visibleTexts = listOf("确认支付")))

        assertEquals(1, collected.size)
        assertEquals(BlockReason.SENSITIVE_PAGE, collected.single().reason)
    }

    @Test
    fun `外部可以直接补记一条拦截事件`() {
        // 执行层在别处发现的拦截（如 Shizuku 通道报错）也要能进审计
        val g = guard()

        g.recordBlock(
            SafetyBlockEvent(
                timestamp = System.currentTimeMillis(),
                packageName = normalApp,
                reason = BlockReason.UNKNOWN_OR_EXTERNAL,
                description = "执行层补记",
            )
        )

        assertEquals(1, g.auditEvents().size)
    }

    @Test
    fun `审计记录数量有上限，超出后丢弃最旧的`() {
        val g = guard()
        val limit = DefaultSafetyGuard.MAX_AUDIT_EVENTS

        repeat(limit + 100) { g.checkBeforeCapture(payApp, null) }

        assertEquals("审计日志必须有上限，否则护栏自己会变成内存泄漏源", limit, g.auditEvents().size)
    }

    // ═══════════════════════════════════════════════════════════
    //  八、策略注册表
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `策略注册表默认返回未声明`() {
        val registry = InMemoryAutomationPolicyRegistry()

        assertEquals(AutomationPolicy.Undeclared, registry.isAutomationAllowed("com.whatever"))
    }

    @Test
    fun `策略注册表可以写入与读取`() {
        val registry = InMemoryAutomationPolicyRegistry()

        registry.setUserPolicy(normalApp, AutomationPolicy.UserBlocked)

        assertEquals(AutomationPolicy.UserBlocked, registry.isAutomationAllowed(normalApp))
    }

    @Test
    fun `内存策略注册表不联网`() {
        assertFalse(InMemoryAutomationPolicyRegistry().syncFromRemote())
    }
}
