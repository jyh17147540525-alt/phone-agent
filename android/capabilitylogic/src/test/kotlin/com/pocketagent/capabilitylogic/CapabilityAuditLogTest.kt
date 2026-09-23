package com.pocketagent.capabilitylogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计日志 —— 两组重点：**消毒**（防伪造）与 **targetDigest 的取值规则**（防泄露）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 为什么这两组值得专门写测试
 * ═══════════════════════════════════════════════════════════════
 *
 * 第 0 档的能力**用户看不见**（那正是它的定义）。所以这份日志是
 * "昨晚到底发生了什么"的**唯一**答案来源。它有两个相反的失效方向：
 *
 * **方向一：记多了 → 泄露。**
 * `notification.post` 的正文是模型生成的、可能含用户的私人内容。
 * 如果它落进日志，"审计"就变成了一个持续收集用户内容的东西 ——
 * 而用户以为自己只是打开了"透明度报告"。
 * 这一侧的守卫是 [CapabilityAuditEvents.digestOf] 的单条规则：
 * **只有长得像包名的值才被记下**。
 *
 * **方向二：消毒漏了 → 伪造。**
 * 日志的展示形态是一行一条。参数值里一个 `\n` 就能伪造出
 * "已执行 停用应用 com.xxx" 这样一条**根本没发生过**的记录，
 * 同时把真实记录挤掉。这一侧的守卫是 [sanitizeForDisplay]。
 *
 * 两侧的测试都必须"断言的是形状"（有没有换行、含不含正文），
 * 而不是"内容对不对" —— 后者在实现改坏时照样能过。
 */
class CapabilityAuditLogTest {

    private val catalog = CapabilityCatalog()

    private fun plan(capabilityId: String, args: Map<String, String>): PlannedExecution =
        CommandPlanner.plan(catalog.byId(capabilityId)!!, args)

    // ══════════════════════════════════════════════════════════
    //  消毒：防伪造
    // ══════════════════════════════════════════════════════════

    @Test
    fun `换行被转义，伪造不出第二条记录`() {
        val log = CapabilityAuditLog()
        log.record(
            CapabilityAuditEvent(
                timestamp = 1L,
                capabilityId = "notification.post",
                origin = CallOrigin.AGENT,
                outcome = CapabilityAuditOutcome.DENIED,
                detail = "拒绝：正文不合法\n2026-09-23 03:12:05 已执行 停用应用",
            ),
        )

        val detail = log.events().single().detail

        assertFalse("记录里出现了真实换行，会伪造出第二条记录", detail.contains('\n'))
        assertTrue("换行应当被转义成可见形式", detail.contains("\\n"))
    }

    @Test
    fun `回车与制表符也被转义`() {
        val log = CapabilityAuditLog()
        log.record(
            CapabilityAuditEvent(1L, "x", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED, detail = "a\rb\tc"),
        )

        val detail = log.events().single().detail
        assertTrue(detail.contains("\\r"))
        assertTrue(detail.contains("\\t"))
    }

    @Test
    fun `不可见的控制字符被转义`() {
        val log = CapabilityAuditLog()
        log.record(
            CapabilityAuditEvent(1L, "x", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED, detail = "a\u0007b"),
        )

        assertTrue(log.events().single().detail.contains("\\u0007"))
    }

    @Test
    fun `正常的中文内容不被改动`() {
        val log = CapabilityAuditLog()
        log.record(
            CapabilityAuditEvent(
                1L, "display.brightness", CallOrigin.USER,
                CapabilityAuditOutcome.EXECUTED, targetDigest = "system/screen_brightness",
                detail = "直接执行",
            ),
        )

        val event = log.events().single()
        assertEquals("system/screen_brightness", event.targetDigest)
        assertEquals("直接执行", event.detail)
    }

    @Test
    fun `超长字段被截断`() {
        val log = CapabilityAuditLog()
        log.record(
            CapabilityAuditEvent(
                1L, "x", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED,
                detail = "a".repeat(CapabilityAuditLog.MAX_FIELD_LENGTH + 100),
            ),
        )

        assertEquals(CapabilityAuditLog.MAX_FIELD_LENGTH, log.events().single().detail.length)
    }

    @Test
    fun `capabilityId 与 targetDigest 也被消毒`() {
        // ⚠️ 三个字段都要过消毒。只消毒 detail 是一个很自然的疏漏 ——
        //    因为"用户内容"看起来只会在 detail 里，而 capabilityId
        //    在实现者的心智里是"我们自己生成的、安全的"。
        //    实际上它来自调用方（插件/模型），完全可以是任意字符串。
        val log = CapabilityAuditLog()
        log.record(
            CapabilityAuditEvent(
                1L,
                capabilityId = "bad\nid",
                origin = CallOrigin.PLUGIN,
                outcome = CapabilityAuditOutcome.DENIED,
                targetDigest = "digest\nforged",
            ),
        )

        val event = log.events().single()
        assertFalse(event.capabilityId.contains('\n'))
        assertFalse(event.targetDigest.contains('\n'))
    }

    @Test
    fun `落库出口收到的是消毒后的副本`() {
        val seen = mutableListOf<CapabilityAuditEvent>()
        val log = CapabilityAuditLog(sink = { seen += it })

        log.record(
            CapabilityAuditEvent(1L, "x", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED, detail = "a\nb"),
        )

        assertEquals(1, seen.size)
        assertFalse("出口拿到的是原始值，消毒白做了", seen.single().detail.contains('\n'))
    }

    @Test
    fun `没有出口时也能工作`() {
        val log = CapabilityAuditLog()
        log.record(CapabilityAuditEvent(1L, "x", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED))

        assertEquals(1, log.events().size)
    }

    // ══════════════════════════════════════════════════════════
    //  ★★★ targetDigest：只有包名才被记下
    // ══════════════════════════════════════════════════════════

    @Test
    fun `通知正文不会出现在审计记录里`() {
        // ⚠️ 这是本文件最重要的一条测试。
        //    断言的是**整个事件的字符串形式**都不含正文 ——
        //    这样将来给事件加字段（比如 target / extra）时，
        //    如果那个字段装的是 execution.summary，这条测试会立刻变红。
        val execution = plan(
            "notification.post",
            mapOf("tag" to "提醒", "text" to "给妈妈打个电话"),
        )

        val event = CapabilityAuditEvents.executed(
            timestamp = 1L,
            capabilityId = "notification.post",
            origin = CallOrigin.SCHEDULE,
            execution = execution,
            wasConfirmed = false,
        )

        assertFalse("通知正文泄露进审计记录了：$event", event.toString().contains("给妈妈打个电话"))
        assertFalse("通知标签泄露进审计记录了：$event", event.toString().contains("提醒"))
        assertEquals("这条命令没有目标可言，digest 应当为空", "", event.targetDigest)
    }

    @Test
    fun `包名被记入 targetDigest`() {
        // ⚠️ 与上一条相反的方向：这是**应该**被记下的。
        //    "昨晚停用的是哪个 App"是用户最需要知道的一件事，
        //    而包名是公开标识、不是个人内容。
        val execution = plan(
            "app.set_enabled",
            mapOf("action" to "disable", "packageName" to "com.example.noisy"),
        )

        val event = CapabilityAuditEvents.executed(
            1L, "app.set_enabled", CallOrigin.SCHEDULE, execution, wasConfirmed = false,
        )

        assertEquals("com.example.noisy", event.targetDigest)
    }

    @Test
    fun `设置写入的 targetDigest 是命名空间与键`() {
        // ⚠️ 键来自配方**字面量**，不含任何用户输入，所以可以直接记。
        val execution = plan("display.brightness", mapOf("level" to "120"))

        val event = CapabilityAuditEvents.executed(
            1L, "display.brightness", CallOrigin.AGENT, execution, wasConfirmed = false,
        )

        assertEquals("system/screen_brightness", event.targetDigest)
    }

    @Test
    fun `没有目标的命令 digest 为空串而不是「未知」`() {
        // ⚠️ 空串是**正常情况**（切换深色模式没有"目标"可言）。
        //    界面不该因为它是空的就显示"未知" —— 那会让用户以为出了问题。
        val execution = plan("ui.night_mode", mapOf("mode" to "auto"))

        val event = CapabilityAuditEvents.executed(
            1L, "ui.night_mode", CallOrigin.AGENT, execution, wasConfirmed = false,
        )

        assertEquals("", event.targetDigest)
    }

    @Test
    fun `参数里不是包名的值不会进 digest`() {
        // ⚠️ 这条钉住的是"单条规则"本身：digest 的取值集合是
        //    "argv 里匹配包名规则的项"，而不是"某个参数的取值"。
        val execution = plan("media.volume_step", mapOf("stream" to "3", "direction" to "raise"))

        val event = CapabilityAuditEvents.executed(
            1L, "media.volume_step", CallOrigin.AGENT, execution, wasConfirmed = false,
        )

        assertEquals("", event.targetDigest)
    }

    // ══════════════════════════════════════════════════════════
    //  结论归类
    // ══════════════════════════════════════════════════════════

    @Test
    fun `未放行与拒绝被分成两种结论`() {
        // ⚠️ 合并成一条的后果：用户看到一堆"被拒绝"会以为系统坏了，
        //    而实际上他只需要去点一下授权。
        val notGranted = CapabilityAuditEvents.denied(
            1L, "wifi.set_enabled", CallOrigin.AGENT, BlockReason.NOT_GRANTED,
        )
        val denied = CapabilityAuditEvents.denied(
            1L, "app.set_enabled", CallOrigin.AGENT, BlockReason.DENIED_TARGET_PACKAGE,
        )

        assertEquals(CapabilityAuditOutcome.NOT_GRANTED, notGranted.outcome)
        assertEquals(CapabilityAuditOutcome.DENIED, denied.outcome)
    }

    @Test
    fun `拒绝事件记的是原因码而不是用户文案`() {
        val denied = CapabilityAuditEvents.denied(
            1L, "x", CallOrigin.AGENT, BlockReason.DENIED_SETTING_KEY,
        )

        assertEquals("原因 DENIED_SETTING_KEY", denied.detail)
    }

    @Test
    fun `超时与用户主动拒绝被分成两种结论`() {
        // ⚠️ 用户主动拒绝说明他不同意；超时说明他可能根本没看到。
        //    合并成一条会让"我明明没点过同意，怎么执行了"无从查起。
        val execution = plan("wifi.set_enabled", mapOf("state" to "disabled"))

        val declined = CapabilityAuditEvents.confirmationResolved(
            1L, "wifi.set_enabled", CallOrigin.AGENT, execution,
            ConfirmReason.GUARDED_CAPABILITY, accepted = false,
        )
        val timedOut = CapabilityAuditEvents.confirmationResolved(
            1L, "wifi.set_enabled", CallOrigin.AGENT, execution,
            ConfirmReason.GUARDED_CAPABILITY, accepted = false, timedOut = true,
        )

        assertEquals(CapabilityAuditOutcome.CONFIRMATION_DECLINED, declined.outcome)
        assertEquals(CapabilityAuditOutcome.CONFIRMATION_TIMEOUT, timedOut.outcome)
        assertTrue(timedOut.detail.contains("视为拒绝"))
    }

    @Test
    fun `确认后执行与直接执行被区分`() {
        val execution = plan("media.dispatch", mapOf("action" to "pause"))

        val direct = CapabilityAuditEvents.executed(
            1L, "media.dispatch", CallOrigin.AGENT, execution, wasConfirmed = false,
        )
        val confirmed = CapabilityAuditEvents.executed(
            1L, "media.dispatch", CallOrigin.AGENT, execution, wasConfirmed = true,
        )

        assertEquals(CapabilityAuditOutcome.EXECUTED, direct.outcome)
        assertEquals(CapabilityAuditOutcome.EXECUTED_AFTER_CONFIRM, confirmed.outcome)
    }

    @Test
    fun `失败事件带上目标与原因`() {
        val execution = plan(
            "app.set_enabled",
            mapOf("action" to "disable", "packageName" to "com.example.noisy"),
        )

        val failed = CapabilityAuditEvents.failed(
            1L, "app.set_enabled", CallOrigin.SCHEDULE, execution, "Shizuku 未激活",
        )

        assertEquals(CapabilityAuditOutcome.FAILED, failed.outcome)
        assertEquals("com.example.noisy", failed.targetDigest)
        assertEquals("Shizuku 未激活", failed.detail)
    }

    // ══════════════════════════════════════════════════════════
    //  缓冲与筛选
    // ══════════════════════════════════════════════════════════

    @Test
    fun `超出容量时丢弃最旧的`() {
        val log = CapabilityAuditLog(capacity = 3)
        for (i in 1..5) {
            log.record(CapabilityAuditEvent(i.toLong(), "x$i", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED))
        }

        assertEquals(listOf(3L, 4L, 5L), log.events().map { it.timestamp })
    }

    @Test
    fun `记录按时间正序`() {
        val log = CapabilityAuditLog()
        log.record(CapabilityAuditEvent(1L, "a", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED))
        log.record(CapabilityAuditEvent(2L, "b", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED))

        assertEquals(listOf("a", "b"), log.events().map { it.capabilityId })
    }

    @Test
    fun `按结论筛选`() {
        val log = CapabilityAuditLog()
        log.record(CapabilityAuditEvent(1L, "a", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED))
        log.record(CapabilityAuditEvent(2L, "b", CallOrigin.AGENT, CapabilityAuditOutcome.DENIED))

        assertEquals(listOf("b"), log.eventsWithOutcome(CapabilityAuditOutcome.DENIED).map { it.capabilityId })
    }

    @Test
    fun `按来源筛选能挑出无人值守的调用`() {
        // ⚠️ 这是 CallOrigin.SCHEDULE 存在的全部理由：回答
        //    "我没看着的时候，它都干了什么"。
        //    定时任务的记录与 agent 的记录在 outcome 上完全一样，
        //    只有这个字段能区分。
        val log = CapabilityAuditLog()
        log.record(CapabilityAuditEvent(1L, "a", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED))
        log.record(CapabilityAuditEvent(2L, "b", CallOrigin.SCHEDULE, CapabilityAuditOutcome.EXECUTED))
        log.record(CapabilityAuditEvent(3L, "c", CallOrigin.SCHEDULE, CapabilityAuditOutcome.DENIED))

        val unattended = log.eventsFrom(CallOrigin.SCHEDULE)

        assertEquals(listOf("b", "c"), unattended.map { it.capabilityId })
    }

    @Test
    fun `clear 之后为空`() {
        val log = CapabilityAuditLog()
        log.record(CapabilityAuditEvent(1L, "a", CallOrigin.AGENT, CapabilityAuditOutcome.EXECUTED))
        log.clear()

        assertEquals(emptyList<CapabilityAuditEvent>(), log.events())
    }
}
