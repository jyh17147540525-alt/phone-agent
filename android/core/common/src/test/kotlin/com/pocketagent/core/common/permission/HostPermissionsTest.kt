package com.pocketagent.core.common.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        canWriteSecure: Boolean = false,
        canWriteSystem: Boolean = false,
    ) = HostSignals(
        sdkInt = sdkInt,
        accessibilityServiceEnabled = accessibility,
        canDrawOverlays = overlay,
        notificationsEnabled = notifications,
        screenshotDeclared = screenshotDeclared,
        applicationId = APP_ID,
        canWriteSecureSettings = canWriteSecure,
        canWriteSystemSettings = canWriteSystem,
    )

    /** 全授予的版本，省得每条"已经好了"的测试都写一长串参数。 */
    private fun allGranted() = signals(
        accessibility = true,
        overlay = true,
        notifications = true,
        screenshotDeclared = true,
        canWriteSecure = true,
        canWriteSystem = true,
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

    // ══════════════════════════════════════════════════════════════
    //  ★★ 写设置的两项 —— 本文件里**最容易说假话**的地方
    // ══════════════════════════════════════════════════════════════

    @Test
    fun `修改系统安全设置没拿到时判「需电脑」而不是「未授予」`() {
        // ★ 这一条是新增两项里最要紧的。
        //
        //   `WRITE_SECURE_SETTINGS` 是签名级权限（保护级别里带 `development` 位），
        //   系统设置里**没有它的开关**。判成 `Denied` 并给一个「去设置」按钮，
        //   用户会在「应用 → 特殊应用权限」里逐页找一个不存在的条目 ——
        //   然后以为是自己机型的问题。
        val v = HostPermissions.verdicts(signals())
            .byId(HostPermissions.ID_WRITE_SECURE_SETTINGS)

        assertEquals(GrantState.NeedsComputer, v.state)
        assertNull("手机上根本打不开那一页，按钮点了只会让人困惑", v.action)
        assertNotNull("必须告诉用户去电脑上跑哪条命令", v.guidance)
    }

    @Test
    fun `修改系统安全设置已拿到时不给按钮也不给指引`() {
        val v = HostPermissions.verdicts(signals(canWriteSecure = true))
            .byId(HostPermissions.ID_WRITE_SECURE_SETTINGS)

        assertEquals(GrantState.Granted, v.state)
        assertNull(v.action)
        assertNull("已经好了，就别再占一行说「怎么开」", v.guidance)
    }

    @Test
    fun `修改系统设置没拿到时给「去系统设置」按钮 —— 与上一项正相反`() {
        // ★ 两项看起来都是"写设置"，但这一项**手机上有开关**
        //   （特殊应用权限 → 修改系统设置）。两项的授予路径不同这件事，
        //   必须体现在界面上，而不只是体现在注释里。
        val v = HostPermissions.verdicts(signals())
            .byId(HostPermissions.ID_WRITE_SETTINGS)

        assertEquals(GrantState.Denied, v.state)
        assertEquals(PermissionAction.OPEN_WRITE_SETTINGS, v.action)
        assertNotNull(v.guidance)
    }

    @Test
    fun `两项写设置的状态永远不同 —— 一个在手机上能解决，一个不能`() {
        // ⚠️ 这条防的是"把两项合并成一条"。合并之后必然有一半的用户
        //    被指引到错误的地方，而合并看起来更整洁、代码更短。
        val verdicts = HostPermissions.verdicts(signals())

        assertEquals(
            GrantState.NeedsComputer,
            verdicts.byId(HostPermissions.ID_WRITE_SECURE_SETTINGS).state,
        )
        assertEquals(
            GrantState.Denied,
            verdicts.byId(HostPermissions.ID_WRITE_SETTINGS).state,
        )
    }

    @Test
    fun `需电脑那一项的指引不会把用户指向系统设置页`() {
        // ★ 断言的是**反向**：`WRITE_SECURE_SETTINGS` 的指引里不能出现
        //   appop 那一条的措辞（"特殊应用权限"）—— 出现了就等于把用户
        //   引到一个没有这个开关的地方去。
        val guidance = HostPermissions.verdicts(signals())
            .byId(HostPermissions.ID_WRITE_SECURE_SETTINGS).guidance.orEmpty()

        assertTrue("要给出一条能直接抄的 adb 命令", guidance.contains("adb shell pm grant"))
        assertFalse(
            "签名级权限在系统设置里没有入口，不能这么写",
            guidance.contains("特殊应用权限"),
        )
    }

    @Test
    fun `写设置的指引里带的是传入的 applicationId，不是硬编码`() {
        // ⚠️ 本项目有两个 id（`com.pocketagent` / `com.pocketagent.debug`）。
        //    硬编码成前者时，debug 构建拿到的 `pm grant` 是一条
        //    **跑得通、但什么也没授**的命令 —— 用户执行完没报错，
        //    界面依然说没权限，而没有任何东西能解释为什么。
        val other = "com.example.some.other.app"
        val verdicts = HostPermissions.verdicts(
            signals().copy(applicationId = other),
        )

        for (id in listOf(HostPermissions.ID_WRITE_SECURE_SETTINGS, HostPermissions.ID_WRITE_SETTINGS)) {
            val guidance = verdicts.byId(id).guidance.orEmpty()
            assertTrue("$id 的指引必须带上真实的应用 id", guidance.contains(other))
            assertFalse("$id 不能出现任何硬编码的包名", guidance.contains("com.pocketagent"))
        }
    }

    // ── 汇总：不能把"用户开不了"的算成"待开启" ────────────────────

    @Test
    fun `汇总只把「未授予」算成手机上的待办`() {
        // API 32（通知未开，但那时没有权限可给）+ 无障碍已开
        // → 用户**在手机上**要做的有 3 件：悬浮窗、通知设置、修改系统设置
        // → 还有 1 件在电脑上：修改系统安全设置
        // → 应用侧没做 1 件：截图
        val summary = HostPermissions.summarize(
            HostPermissions.verdicts(signals(sdkInt = 32, accessibility = true)),
        )
        assertEquals("悬浮窗 + 通知 + 修改系统设置", 3, summary.actionable)
        assertEquals("修改系统安全设置", 1, summary.needsComputer)
        assertEquals("截图", 1, summary.appSidePending)
        assertEquals("无障碍", 1, summary.granted)
    }

    @Test
    fun `需电脑的那一项不算进手机待办`() {
        // ⚠️ 并进 `actionable` 的代价：用户先在手机设置里找一圈才可能意识到
        //    这件事根本不在这台设备上做。
        val summary = HostPermissions.summarize(HostPermissions.verdicts(signals()))

        assertEquals("无障碍 / 悬浮窗 / 通知 / 修改系统设置", 4, summary.actionable)
        assertEquals(1, summary.needsComputer)
    }

    @Test
    fun `徽章优先报用户能做的事`() {
        // 全未授予 + 截图未声明：手机上能处理的有 4 件，
        // 应用侧没做的有 1 件（截图）。徽章要报前者 —— 报"部分未实现"等于
        // 把用户**马上就能处理掉**的 4 件事藏起来。
        val summary = HostPermissions.summarize(HostPermissions.verdicts(signals()))
        assertEquals(4, summary.actionable)
        assertEquals(1, summary.appSidePending)
        assertEquals("4 项待开启 · 1 项需电脑", HostPermissions.badgeText(summary))
    }

    @Test
    fun `两类待处理同时存在时徽章必须都报出来`() {
        // ⚠️ 只报手机上那一类，用户会以为做完就齐了 ——
        //    而实际上还差一条在电脑上的命令，且它**永远不会**自己变绿。
        val summary = HostPermissions.summarize(HostPermissions.verdicts(signals()))
        val text = HostPermissions.badgeText(summary)

        assertTrue("要报出手机上的待办：$text", text.contains("4 项待开启"))
        assertTrue("也要报出电脑上的那一项：$text", text.contains("1 项需电脑"))
    }

    @Test
    fun `只剩电脑上那一项时徽章说的是需电脑，不是已就绪`() {
        // ★ 最危险的一种"已就绪"：手机上能开的都开了，用户看到"已就绪"，
        //   于是以为能力可用 —— 而写 `Settings.Global` 那几条一条都跑不了。
        val summary = HostPermissions.summarize(
            HostPermissions.verdicts(
                signals(
                    accessibility = true,
                    overlay = true,
                    notifications = true,
                    screenshotDeclared = true,
                    canWriteSystem = true,
                    canWriteSecure = false,
                ),
            ),
        )
        assertEquals(0, summary.actionable)
        assertEquals(1, summary.needsComputer)
        assertEquals("1 项需电脑", HostPermissions.badgeText(summary))
    }

    @Test
    fun `应用侧还没做时不能说「已就绪」`() {
        // 除截图外全部给齐 → 用户该做的做完了，可应用确实还不能截图。
        // 说"已就绪"就是假话。
        val summary = HostPermissions.summarize(
            HostPermissions.verdicts(
                signals(
                    accessibility = true,
                    overlay = true,
                    notifications = true,
                    screenshotDeclared = false,
                    canWriteSecure = true,
                    canWriteSystem = true,
                ),
            ),
        )
        assertEquals(0, summary.actionable)
        assertEquals(0, summary.needsComputer)
        assertEquals(1, summary.appSidePending)
        assertEquals("部分未实现", HostPermissions.badgeText(summary))
    }

    @Test
    fun `全部就绪时才是「已就绪」`() {
        val summary = HostPermissions.summarize(
            HostPermissions.verdicts(allGranted()),
        )
        assertEquals(0, summary.actionable)
        assertEquals(0, summary.needsComputer)
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
            applicationId = APP_ID,
            canWriteSecureSettings = false,
            canWriteSystemSettings = false,
        ).toString()

        assertEquals(
            "HostSignals(sdkInt=35, accessibilityServiceEnabled=false, " +
                "canDrawOverlays=false, notificationsEnabled=false, screenshotDeclared=false, " +
                "applicationId=$APP_ID, canWriteSecureSettings=false, canWriteSystemSettings=false)",
            actual,
        )
    }

    @Test
    fun `判定结果覆盖全部六项且 id 唯一`() {
        val verdicts = HostPermissions.verdicts(signals())
        assertEquals(6, verdicts.size)
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

    @Test
    fun `每一项没拿到时都有下一步 —— 否则界面上是一行无解的红字`() {
        // ⚠️ 这一条守的是"新加一项时忘了给出口"。
        //
        //   不变量**不是**"每个没拿到的项都要有指引"（第一版就是这么写的，
        //   结果被无障碍那一条打红 —— 它有「去开启无障碍」按钮，指引是多余的）。
        //   真正的不变量是：**每一条"没拿到"的路，至少有一条走法**：
        //     · 手机上有开关 → 给按钮（要不要再给文案都行）
        //     · 手机上没开关 → **必须**给指引（否则用户面前是一行无解的红字）
        val verdicts = HostPermissions.verdicts(signals())

        for (v in verdicts) {
            when (v.state) {
                GrantState.Denied -> assertTrue(
                    "${v.id} 是「未授予」，但既没有按钮也没有指引 —— 用户无处下手",
                    v.action != null || v.guidance != null,
                )

                GrantState.NeedsComputer -> {
                    assertNotNull("${v.id} 要上电脑，必须给出那条命令", v.guidance)
                    assertNull("${v.id} 在手机上没有入口，给按钮等于让用户白找", v.action)
                }

                // 已授予 / 用户插不上手 —— 指引必须为空（多一行说明会挤掉真正要看的）
                GrantState.Granted, GrantState.Undeclared -> assertNull(v.guidance)
            }
        }
    }

    private companion object {
        /** 测试里**刻意**用一个不是本项目真实 id 的值 —— 见"不是硬编码"那条。 */
        const val APP_ID = "com.example.host.permissions.test"
    }
}
