package com.pocketagent.core.common.permission

/**
 * 宿主权限的**诚实**判定。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这件事值得一个纯 Kotlin 文件 + 一组测试
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为这里每一条错误都**没有报错、没有崩溃，只是界面上多了一句假话**：
 *
 *  · 在 Android 12（API 32）上给一个「请求通知权限」的按钮
 *    → `POST_NOTIFICATIONS` 在那个版本**根本不存在**，点了要么无反应、
 *      要么弹一个系统不认识的东西。用户会以为应用坏了。
 *  · 把"应用还没声明这个能力"显示成"你还没授权"
 *    → 用户做什么都没用，但他会一直试
 *  · 用进程内标志（服务是否被系统绑定）判断"权限有没有开"
 *    → 那个标志**会滞后**，于是界面在"已开启 / 未开启"之间闪
 *
 * 三条都不是异常，都是**界面在说假话**。而按本项目的立场：
 * **一个说错话的界面比一个不说这话的界面更糟** —— 用户不会怀疑文案，
 * 他会怀疑自己。（见 `SettingsViewModel` 的类注释）
 *
 * 所以这里的原则是：**宁可显示"这项现在拿不到"，也不显示一句好听的假话。**
 *
 * ⚠️ 本文件**零 Android 依赖**：`sdkInt` 是传进来的 `Int`，不是 `Build.VERSION`。
 *    这样"版本阈值判错"才能被离线测试抓住（同 `RoomUsageRecorder(dao, clock)` 的 `clock`）。
 */
object HostPermissions {

    /**
     * `POST_NOTIFICATIONS` 从 API 33（Android 13）起才存在。
     *
     * ⚠️ 这个常量必须**只有一份**。散在 Android 侧的 `if (SDK_INT >= 33)` 里，
     *    就没有任何东西能拦住"有人把它改成 31"。
     */
    const val NOTIFICATION_PERMISSION_MIN_API = 33

    const val ID_ACCESSIBILITY = "accessibility"
    const val ID_OVERLAY = "overlay"
    const val ID_NOTIFICATIONS = "notifications"
    const val ID_SCREENSHOT = "screenshot"

    /**
     * 判定。
     *
     * ⚠️ 输入类型 [HostSignals] 里**刻意没有**"无障碍服务是否被系统绑定"这个字段。
     *    `AgentAccessibilityService.connected` 是**进程内**状态、会滞后，
     *    与"权限有没有被授予"是两件事（那条警告就写在那个字段上面）。
     *    把它放进输入类型，等于给调用方留一个"顺手用错"的机会 ——
     *    所以它**在类型上不存在**。（同 `DshConfigPatch` 里"真 Key 在类型上不存在"）
     */
    fun verdicts(signals: HostSignals): List<PermissionVerdict> = listOf(
        verdict(
            id = ID_ACCESSIBILITY,
            label = "无障碍服务",
            purpose = "读屏、点击、滑动都靠它",
            state = if (signals.accessibilityServiceEnabled) {
                GrantState.Granted
            } else {
                GrantState.Denied
            },
            action = PermissionAction.OPEN_ACCESSIBILITY_SETTINGS,
        ),
        verdict(
            id = ID_OVERLAY,
            label = "悬浮窗",
            purpose = "急停按钮与手动引导要浮在别的应用上面",
            state = if (signals.canDrawOverlays) GrantState.Granted else GrantState.Denied,
            action = PermissionAction.OPEN_OVERLAY_SETTINGS,
        ),
        verdict(
            id = ID_NOTIFICATIONS,
            label = "通知",
            purpose = "前台服务保活时显示常驻通知",
            state = if (signals.notificationsEnabled) GrantState.Granted else GrantState.Denied,
            // ⚠️⚠️ 这一条是本文件最要紧的地方：**动作随版本变**。
            //    API 33+ 才有运行时权限可请求；在那之前，"通知开没开"是应用通知设置里的
            //    一个开关。两种情况的入口**不是同一个地方** —— 给错了用户就白跑。
            action = if (signals.sdkInt >= NOTIFICATION_PERMISSION_MIN_API) {
                PermissionAction.REQUEST_NOTIFICATIONS
            } else {
                PermissionAction.OPEN_NOTIFICATION_SETTINGS
            },
        ),
        verdict(
            id = ID_SCREENSHOT,
            label = "屏幕截图",
            purpose = "无障碍树读不到内容时的兜底手段",
            // ⚠️ 这一项**不是**用户在系统设置里能开的权限 —— 它来自无障碍服务配置里的
            //    `android:canTakeScreenshot`。所以两种"拿不到"必须分开：
            state = when {
                // 应用侧还没声明 → 用户做什么都没用，**不能显示成"未授权"**
                !signals.screenshotDeclared -> GrantState.Undeclared
                // 声明了，但它靠无障碍服务生效 → 无障碍没开就等于没开
                !signals.accessibilityServiceEnabled -> GrantState.Denied
                else -> GrantState.Granted
            },
            action = PermissionAction.OPEN_ACCESSIBILITY_SETTINGS,
        ),
    )

    /** 徽章用的一行汇总。 */
    fun summarize(verdicts: List<PermissionVerdict>): PermissionSummary = PermissionSummary(
        granted = verdicts.count { it.state == GrantState.Granted },
        // ⚠️ 只有 Denied 算"用户现在就该去处理"。
        //    把 Undeclared 也算进来，就会出现"3 项待开启"而其中一项用户根本开不了
        //    —— 他会一直试。
        actionable = verdicts.count { it.state == GrantState.Denied },
        appSidePending = verdicts.count { it.state == GrantState.Undeclared },
    )

    /**
     * 徽章文案。
     *
     * ⚠️ 顺序要紧：**先报用户能做的事**。应用侧还没做的（[GrantState.Undeclared]）
     *    排第二，因为那件事用户插不上手，写在前面只会让他白找。
     */
    fun badgeText(summary: PermissionSummary): String = when {
        summary.actionable > 0 -> "${summary.actionable} 项待开启"
        summary.appSidePending > 0 -> "部分未实现"
        else -> "已就绪"
    }

    private fun verdict(
        id: String,
        label: String,
        purpose: String,
        state: GrantState,
        action: PermissionAction,
    ) = PermissionVerdict(
        id = id,
        label = label,
        purpose = purpose,
        state = state,
        // ⚠️ 用户已经给过的、用户做什么都没用的项，不给"去设置"按钮 ——
        //    点一个不解决问题的按钮，比没有按钮更让人困惑。
        action = when (state) {
            GrantState.Granted, GrantState.Undeclared -> null
            GrantState.Denied -> action
        },
    )
}

/** 从系统读到的**原始信号**。全部是"系统怎么说"，不含任何推断。 */
data class HostSignals(
    val sdkInt: Int,
    /** `AccessibilityManager.getEnabledAccessibilityServiceList()` 里有没有本应用的服务 */
    val accessibilityServiceEnabled: Boolean,
    val canDrawOverlays: Boolean,
    /**
     * 本应用的通知**会不会真的显示出来**。
     *
     * ⚠️ 用 `NotificationManager.areNotificationsEnabled()` 而不是
     *    `checkSelfPermission(POST_NOTIFICATIONS)` —— 后者在 API 33 以下
     *    永远返回 DENIED，会把"这个版本没有这项权限"误报成"用户拒绝了"。
     *    前者在**所有版本**上都回答同一个问题，于是版本差异只剩"该跳哪个设置页"，
     *    而那件事已经由 [HostPermissions.verdicts] 统一处理了。
     */
    val notificationsEnabled: Boolean,
    /** 无障碍服务配置里有没有声明 `android:canTakeScreenshot` */
    val screenshotDeclared: Boolean,
)

/**
 * 一项权限的状态。
 *
 * ⚠️ **三种而不是两种**。合并成"已授权 / 未授权"的代价见 [Undeclared]。
 */
enum class GrantState {
    /** 系统确认已授予 */
    Granted,

    /** 没授予，**且用户去系统设置能解决** */
    Denied,

    /**
     * **这个能力当前根本拿不到，因为应用侧还没声明。**
     *
     * ⚠️ 与 [Denied] 的区别是**用户能不能自己解决**：
     *    [Denied] → 去系统设置开一下就行；
     *    [Undeclared] → 用户做什么都没用。
     *    把后者显示成前者，等于让用户白跑一趟系统设置。
     */
    Undeclared,
}

/** 用户能做的那一个动作。**刻意是枚举而非 Intent 字符串** —— "跳去哪个系统页"留在 Android 侧。 */
enum class PermissionAction {
    OPEN_ACCESSIBILITY_SETTINGS,

    /** 悬浮窗只能用 `ACTION_MANAGE_OVERLAY_PERMISSION` 跳转授权，**没有**运行时请求 */
    OPEN_OVERLAY_SETTINGS,

    /** API 33+ 的运行时权限请求 */
    REQUEST_NOTIFICATIONS,

    /** API 32 及以下的"应用通知"设置页 —— 那时没有权限可请求，开关在那里 */
    OPEN_NOTIFICATION_SETTINGS,
}

/**
 * 一项权限的判定结果。
 *
 * @param action `null` = 用户无事可做（已授予 / 应用还没做）
 */
data class PermissionVerdict(
    val id: String,
    val label: String,
    val purpose: String,
    val state: GrantState,
    val action: PermissionAction?,
)

/**
 * @param actionable 用户**现在就该去处理**的项数（只有 [GrantState.Denied] 算）
 * @param appSidePending 应用侧还没做的项数 —— 用户插不上手，但也不该被说成"已就绪"
 */
data class PermissionSummary(
    val granted: Int,
    val actionable: Int,
    val appSidePending: Int,
)
