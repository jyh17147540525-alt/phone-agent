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
 *  · 把"只能在电脑上授予"的权限显示成"未开启"并给一个「去设置」按钮
 *    → 用户把系统设置翻遍也找不到那个开关（见 [GrantState.NeedsComputer]）
 *
 * 四条都不是异常，都是**界面在说假话**。而按本项目的立场：
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
     * 写 `Settings.Global` / `Settings.Secure` 要的权限。
     *
     * ⚠️ 它与下面那一项**不是同一件事**，见 [SettingsPermission] 的实测表。
     */
    const val ID_WRITE_SECURE_SETTINGS = "write_secure_settings"

    /** 写 `Settings.System` 要的权限（appop 型，授予路径完全不同）。 */
    const val ID_WRITE_SETTINGS = "write_settings"

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

        // ══════════════════════════════════════════════════════════
        //  第 0 档能力要用的两项 —— 授予路径**不同**，所以是两个条目
        // ══════════════════════════════════════════════════════════

        verdict(
            id = ID_WRITE_SECURE_SETTINGS,
            label = "修改系统安全设置",
            purpose = "让 agent 能改深色模式、充电保持亮屏这类系统开关",
            // ⚠️ 这一项**没有**系统设置入口（签名级权限，且带 `development` 位）。
            //    所以"没授予"时不能给"去设置"按钮 —— 用户会翻遍设置也找不到。
            //    它是本文件里唯一一个 [GrantState.NeedsComputer] 的项。
            state = if (signals.canWriteSecureSettings) {
                GrantState.Granted
            } else {
                GrantState.NeedsComputer
            },
            // 给了也不会画按钮（见 `verdict`），传它是为了让"该去哪"这件事
            // 在类型里是明确的，而不是靠"这个 state 恰好不给按钮"来暗示。
            action = null,
            guidance = SettingsPermissionGuide.guidanceFor(
                SettingsPermission.WRITE_SECURE_SETTINGS,
                signals.applicationId,
            ),
        ),
        verdict(
            id = ID_WRITE_SETTINGS,
            label = "修改系统设置",
            purpose = "让 agent 能改亮度、自动旋转、熄屏时间这类系统开关",
            // ⚠️ 这一项与上面正相反：**手机上有开关**（特殊应用权限 → 修改系统设置）。
            //    两项看起来都在说"写设置"，但一个能在手机上解决、一个不能 ——
            //    合并成一条会让其中一半的用户被指引到错误的地方。
            state = if (signals.canWriteSystemSettings) {
                GrantState.Granted
            } else {
                GrantState.Denied
            },
            action = PermissionAction.OPEN_WRITE_SETTINGS,
            guidance = SettingsPermissionGuide.guidanceFor(
                SettingsPermission.WRITE_SETTINGS_APPOP,
                signals.applicationId,
            ),
        ),
    )

    /** 徽章用的一行汇总。 */
    fun summarize(verdicts: List<PermissionVerdict>): PermissionSummary = PermissionSummary(
        granted = verdicts.count { it.state == GrantState.Granted },
        // ⚠️ 只有 Denied 算"用户**在手机上**现在就能处理"。
        //    把 Undeclared 也算进来，就会出现"3 项待开启"而其中一项用户根本开不了
        //    —— 他会一直试。
        actionable = verdicts.count { it.state == GrantState.Denied },
        // ⚠️ NeedsComputer **不能**并进 actionable：它同样是"用户要做点什么"，
        //    但做的事在另一台设备上。并进去的话，用户会先在手机设置里找一圈。
        needsComputer = verdicts.count { it.state == GrantState.NeedsComputer },
        appSidePending = verdicts.count { it.state == GrantState.Undeclared },
    )

    /**
     * 徽章文案。
     *
     * ⚠️ 顺序要紧：**先报用户能做的事**。应用侧还没做的（[GrantState.Undeclared]）
     *    排第二，因为那件事用户插不上手，写在前面只会让他白找。
     *
     * ⚠️ 两类"待处理"同时存在时**必须都报出来** —— 只报手机上那一类，
     *    用户会以为做完就齐了，而实际上还差一条在电脑上的命令。
     */
    fun badgeText(summary: PermissionSummary): String = when {
        summary.actionable > 0 && summary.needsComputer > 0 ->
            "${summary.actionable} 项待开启 · ${summary.needsComputer} 项需电脑"

        summary.actionable > 0 -> "${summary.actionable} 项待开启"
        summary.needsComputer > 0 -> "${summary.needsComputer} 项需电脑"
        summary.appSidePending > 0 -> "部分未实现"
        else -> "已就绪"
    }

    /**
     * @param guidance 这一项**没拿到时**要告诉用户怎么做。
     *
     * ⚠️ 它只对"用户能解决"的两种状态有意义（[GrantState.Denied] /
     *    [GrantState.NeedsComputer]）。已授予时给 `null` —— 一句
     *    "已经好了"的说明只会把真正要看的那一行挤下去。
     *
     * ⚠️ 文案来自 [SettingsPermissionGuide]，与执行层失败时用的是**同一份**。
     *    两边各写一份的后果不是"重复"，而是它们会不一致。
     */
    private fun verdict(
        id: String,
        label: String,
        purpose: String,
        state: GrantState,
        action: PermissionAction?,
        guidance: String? = null,
    ) = PermissionVerdict(
        id = id,
        label = label,
        purpose = purpose,
        state = state,
        // ⚠️ 用户已经给过的、用户做什么都没用的项，不给"去设置"按钮 ——
        //    点一个不解决问题的按钮，比没有按钮更让人困惑。
        action = when (state) {
            GrantState.Granted, GrantState.Undeclared -> null
            GrantState.Denied, GrantState.NeedsComputer -> action
        },
        // 同理：已授予 / 用户插不上手的，不写"怎么授权"。
        guidance = if (state == GrantState.Granted || state == GrantState.Undeclared) {
            null
        } else {
            guidance
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

    /**
     * 本应用真实的 applicationId。
     *
     * ⚠️ 它只用于拼"去电脑上跑哪条命令"的文案，而**必须**是真实的那一个：
     *    本项目有两个 id（`com.pocketagent` / `com.pocketagent.debug`），
     *    写错的那条 `pm grant` 是**跑得通、但什么也没授**的命令。
     *    所以它从 `context.packageName` 来，不写死。
     */
    val applicationId: String,

    /**
     * `WRITE_SECURE_SETTINGS` 拿到了没有。
     *
     * ⚠️ 判据必须与执行层**同源**（`AndroidSettingsAccess.isGranted()`）——
     *    两边用不同的 API 判断同一件事，就会出现"界面说已开启、执行说没权限"，
     *    而这种不一致没有任何东西会报错。
     */
    val canWriteSecureSettings: Boolean,

    /**
     * `WRITE_SETTINGS` 拿到了没有。
     *
     * ⚠️ 它是 **appop 型**权限，所以判据是 `Settings.System.canWrite(context)`，
     *    **不能**用 `checkSelfPermission(WRITE_SETTINGS)` —— 后者对它永远返回
     *    DENIED，会把"用户已经开好了"显示成"还没开"。
     */
    val canWriteSystemSettings: Boolean,
)

/**
 * 一项权限的状态。
 *
 * ⚠️ **四种而不是两种**。合并成"已授权 / 未授权"的代价见 [Undeclared] 与 [NeedsComputer]。
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

    /**
     * 没授予，**且系统设置里没有它的开关** —— 需要在电脑上跑一条命令。
     *
     * ⚠️ 与 [Denied] 的区别同样是"用户能不能在**这台设备上**解决"：
     *    [Denied] → 就在手机上，去设置里开；
     *    [NeedsComputer] → 手机上翻遍也找不到（签名级权限没有界面入口），
     *                      要用 adb 从电脑上授予。
     *
     * 把这一项显示成 [Denied] 的代价很具体：用户会在
     * 「应用 → 特殊应用权限」里逐页找「修改系统安全设置」，找不到，
     * 于是以为是自己手机型号的问题 —— 而那个开关**从来不在那里**。
     */
    NeedsComputer,
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

    /**
     * 「特殊应用权限 → 修改系统设置」。
     *
     * ⚠️ 只有 appop 型的 `WRITE_SETTINGS` 走这里。签名级的
     *    `WRITE_SECURE_SETTINGS` **没有**对应的系统页，所以它不配一个动作
     *    —— 给了就是一个永远点不开正确地方的按钮。
     */
    OPEN_WRITE_SETTINGS,
}

/**
 * 一项权限的判定结果。
 *
 * @param action `null` = 用户无事可做（已授予 / 应用还没做 / 系统里没这个开关）
 * @param guidance 没拿到时要告诉用户怎么做。已授予或用户插不上手时为 `null`
 */
data class PermissionVerdict(
    val id: String,
    val label: String,
    val purpose: String,
    val state: GrantState,
    val action: PermissionAction?,
    val guidance: String? = null,
)

/**
 * @param actionable 用户**在手机上**现在就该去处理的项数（只有 [GrantState.Denied] 算）
 * @param needsComputer 要在电脑上处理才能拿到的项数（[GrantState.NeedsComputer]）
 * @param appSidePending 应用侧还没做的项数 —— 用户插不上手，但也不该被说成"已就绪"
 */
data class PermissionSummary(
    val granted: Int,
    val actionable: Int,
    val needsComputer: Int,
    val appSidePending: Int,
)
