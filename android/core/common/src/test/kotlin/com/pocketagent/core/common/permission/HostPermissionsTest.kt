package com.pocketagent.core.common.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HostPermissions] 的测试。
 *
 * ⚠️ 这一组测试盯的不是"能不能跑通"，而是**界面会不会说假话**。
 *    每条错误在这里都是静默的：没有异常、没有崩溃，只有一句让用户
 *    白跑一趟系统设置的文案。
 */
class HostPermissionsTest {

    /** 默认信号：全未授予、未声明、API 35（通知权限存在的版本）。 */
    private fun signals(
        sdkInt: Int = 35,
        accessibility: Boolean = false,
        overlay: Boolean = false,
        notifications: Boolean = false,
        screenshotDeclared: Boolean = false,
    ) = HostSignals(
        sdkInt = sdkInt,
        accessibilityServiceEnabled = accessibility,
        canDrawOverlays = overlay,
        notificationsEnabled = notifications,
        screenshotDeclared = screenshotDeclared,
    )

    private fun List<PermissionVerdict>.byId(id: String) = first { it.id == id }

    // ── 无障碍 ────────────────────────────────────────────────────

    @Test
    fun `无障碍未启用时是「未授予」并且给出可行动作`() {
        val v = HostPermissions.verdicts(signals()).byId(HostPermissions.ID_ACCESSIBILITY)
        assertEquals(GrantState.Denied, v.state)
        assertEquals(PermissionAction.OPEN_ACCESSIBILITY_SETTINGS, v.action)
    }

    @Test
    fun `无障碍已启用时是「已授予」且不给按钮`() {
        val v = HostPermissions.verdicts(signals(accessibility = true))
            .byId(HostPermissions.ID_ACCESSIBILITY)
        assertEquals(GrantState.Granted, v.state)
        assertNull("已授予还给「去设置」按钮，用户会以为还有没开的东西", v.action)
    }

    // ── 通知：★ 动作随版本变（静默失败重灾区）────────────────────

    @Test
    fun `API 32 上通知未开时按钮必须指向应用通知设置`() {
        // ⚠️ 这是本文件最重要的一条。`POST_NOTIFICATIONS` 从 API 33 才存在，
        //    在 Android 12 上给一个"请求权限"按钮，点了不会弹任何东西
        //    —— 用户会以为应用坏了。那时开关在"应用通知"设置页里。
        val v = HostPermissions.verdicts(signals(sdkInt = 32))
            .byId(HostPermissions.ID_NOTIFICATIONS)
        assertEquals(GrantState.Denied, v.state)
        assertEquals(PermissionAction.OPEN_NOTIFICATION_SETTINGS, v.action)
    }

    @Test
    fun `API 33 上通知未开时按钮是运行时请求`() {
        val v = HostPermissions.verdicts(signals(sdkInt = 33))
            .byId(HostPermissions.ID_NOTIFICATIONS)
        assertEquals(GrantState.Denied, v.state)
        assertEquals(PermissionAction.REQUEST_NOTIFICATIONS, v.action)
    }

    @Test
    fun `通知已开时是「已授予」且不给按钮`() {
        val v = HostPermissions.verdicts(signals(notifications = true))
            .byId(HostPermissions.ID_NOTIFICATIONS)
        assertEquals(GrantState.Granted, v.state)
        assertNull(v.action)
    }

    @Test
    fun `通知动作的版本分界恰好落在 API 33`() {
        // 防止有人把常量改成 31/34 而没人发现。上下各测一格。
        assertEquals(33, HostPermissions.NOTIFICATION_PERMISSION_MIN_API)
        assertEquals(
            PermissionAction.OPEN_NOTIFICATION_SETTINGS,
            HostPermissions.verdicts(signals(sdkInt = 32))
                .byId(HostPermissions.ID_NOTIFICATIONS).action,
        )
        assertEquals(
            PermissionAction.REQUEST_NOTIFICATIONS,
            HostPermissions.verdicts(signals(sdkInt = 33))
                .byId(HostPermissions.ID_NOTIFICATIONS).action,
        )
    }

    // ── 截图：声明 vs 授权（★ 另一种静默失败）────────────────────

    @Test
    fun `截图未声明时判「应用还没做」而不是「未授予」`() {
        // ⚠️ 截图**不是**用户在系统设置里能开的权限 —— 它来自无障碍服务配置里的
        //    `android:canTakeScreenshot`。显示成"未授权"，用户会一直去找那个开关。
        val v = HostPermissions.verdicts(signals(accessibility = true))
            .byId(HostPermissions.ID_SCREENSHOT)
        assertEquals(GrantState.Undeclared, v.state)
        assertNull("用户做什么都没用的事，不能给按钮", v.action)
    }

    @Test
    fun `截图已声明但无障碍没开时是「未授予」且按钮指向无障碍`() {
        val v = HostPermissions.verdicts(signals(screenshotDeclared = true))
            .byId(HostPermissions.ID_SCREENSHOT)
        assertEquals(GrantState.Denied, v.state)
        assertEquals(PermissionAction.OPEN_ACCESSIBILITY_SETTINGS, v.action)
    }

    @Test
    fun `截图已声明且无障碍已开时是「已授予」`() {
        val v = HostPermissions.verdicts(signals(accessibility = true, screenshotDeclared = true))
            .byId(HostPermissions.ID_SCREENSHOT)
        assertEquals(GrantState.Granted, v.state)
    }

    // ── 汇总：不能把"用户开不了"的算成"待开启" ────────────────────

    @Test
    fun `汇总只把「未授予」算成待处理`() {
        // API 32（通知未开，但那时没有权限可给）+ 无障碍已开 + 悬浮窗未开 + 截图未声明
        // → 用户真正要做的只有 2 件（悬浮窗、通知设置）
        val summary = HostPermissions.summarize(
            HostPermissions.verdicts(signals(sdkInt = 32, accessibility = true)),
        )
        assertEquals("悬浮窗 + 通知", 2, summary.actionable)
        assertEquals("截图", 1, summary.appSidePending)
        assertEquals("无障碍", 1, summary.granted)
    }

    @Test
    fun `徽章优先报用户能做的事`() {
        // 全未授予 + 截图未声明：用户能处理的有 3 件（无障碍 / 悬浮窗 / 通知），
        // 应用侧没做的有 1 件（截图）。徽章要报前者 —— 报"部分未实现"等于
        // 把用户**马上就能处理掉**的 3 件事藏起来。
        val summary = HostPermissions.summarize(HostPermissions.verdicts(signals()))
        assertEquals(3, summary.actionable)
        assertEquals(1, summary.appSidePending)
        assertEquals("3 项待开启", HostPermissions.badgeText(summary))
    }

    @Test
    fun `应用侧还没做时不能说「已就绪」`() {
        // 无障碍 + 悬浮窗 + 通知都给齐了，只差截图还没声明 → 用户该做的做完了，
        // 可应用确实还不能截图。说"已就绪"就是假话。
        val summary = HostPermissions.summarize(
            HostPermissions.verdicts(
                signals(
                    accessibility = true,
                    overlay = true,
                    notifications = true,
                    screenshotDeclared = false,
                ),
            ),
        )
        assertEquals(0, summary.actionable)
        assertEquals(1, summary.appSidePending)
        assertEquals("部分未实现", HostPermissions.badgeText(summary))
    }

    @Test
    fun `全部就绪时才是「已就绪」`() {
        val summary = HostPermissions.summarize(
            HostPermissions.verdicts(
                signals(
                    accessibility = true,
                    overlay = true,
                    notifications = true,
                    screenshotDeclared = true,
                ),
            ),
        )
        assertEquals(0, summary.actionable)
        assertEquals(0, summary.appSidePending)
        assertEquals("已就绪", HostPermissions.badgeText(summary))
    }

    // ── 类型不变量 ────────────────────────────────────────────────

    @Test
    fun `输入类型的形状被钉住 —— 加字段必须回来看一眼`() {
        // ⚠️ 为什么用 toString 而不是反射：本模块是纯 Kotlin，不引 kotlin-reflect。
        //    data class 的 toString 会列出全部属性名，所以它能当**形状探针**用。
        //
        //    这条测试存在的唯一理由是：**拦住有人"顺手"把进程内状态加进来**。
        //    `AgentAccessibilityService.connected` 会滞后，用它判权限会让界面说假话，
        //    所以它刻意不在 HostSignals 里（见其 KDoc）。
        //    一旦这条变红，先读那段 KDoc，**不要**直接改期望字符串。
        val actual = HostSignals(
            sdkInt = 35,
            accessibilityServiceEnabled = false,
            canDrawOverlays = false,
            notificationsEnabled = false,
            screenshotDeclared = false,
        ).toString()

        assertEquals(
            "HostSignals(sdkInt=35, accessibilityServiceEnabled=false, " +
                "canDrawOverlays=false, notificationsEnabled=false, screenshotDeclared=false)",
            actual,
        )
    }

    @Test
    fun `判定结果覆盖全部四项且 id 唯一`() {
        val verdicts = HostPermissions.verdicts(signals())
        assertEquals(4, verdicts.size)
        assertEquals(
            "id 重复会让界面上出现两行看起来一样的条目",
            verdicts.size,
            verdicts.map { it.id }.toSet().size,
        )
        assertTrue(
            "每一项都必须说明用途 —— 不说清用途，用户没法判断该不该给",
            verdicts.all { it.purpose.isNotBlank() },
        )
    }
}
