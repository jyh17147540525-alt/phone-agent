package com.pocketagent.assistant

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Xml
import android.view.accessibility.AccessibilityManager
import com.pocketagent.R
import com.pocketagent.capability.AndroidSettingsAccess
import com.pocketagent.core.common.permission.HostSignals
import com.pocketagent.core.common.permission.PermissionAction
import com.pocketagent.core.common.permission.SettingsPermission
import org.xmlpull.v1.XmlPullParser

/**
 * 读宿主权限的**真实**状态。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这个类只做一件事：把系统的话**原样**搬给 [HostSignals]
 * ═══════════════════════════════════════════════════════════════
 *
 * 所有"该判成什么状态""该给什么按钮"的逻辑都在
 * `:core:common` 的 `HostPermissions` 里（纯 Kotlin、有测试）。
 * 这里**刻意不做任何判断** —— 一旦判断散落到 Android 侧，离线测试就够不着了，
 * 而这一层的错误（用错 API、读错字段）恰恰是最静默的。
 *
 * ⚠️ 特别地：**不要**用 [AgentAccessibilityService.connected] 判断权限。
 *    那是进程内状态、会滞后，两件事不是一回事（见那个字段的注释）。
 */
class HostPermissionReader(
    private val context: Context,

    /**
     * 写设置那两项的判据**唯一来源**。
     *
     * ⚠️ 刻意复用执行层的那一个对象，而不是在这里另写一份
     *    `checkSelfPermission` —— 两边用不同 API 判断同一件事，
     *    就会出现「界面说已开启、执行说没权限」，而**没有任何东西会报错**。
     *    这里的两个 `isGranted` 调用与 `AndroidSettingsAccess.write()`
     *    开头那个守卫走的是同一段代码。
     */
    private val settingsAccess: AndroidSettingsAccess,
) {

    fun read(): HostSignals = HostSignals(
        sdkInt = Build.VERSION.SDK_INT,
        accessibilityServiceEnabled = isOurServiceEnabled(),
        canDrawOverlays = Settings.canDrawOverlays(context),
        notificationsEnabled = areNotificationsEnabled(),
        screenshotDeclared = isScreenshotDeclaredInConfig(),
        // ⚠️ 真实的应用 id，不是 `context.packageName` 的近似 ——
        //    debug 构建是 `com.pocketagent.debug`，而写错的 `pm grant`
        //    是一条跑得通、但什么也没授的命令。
        applicationId = context.packageName,
        // ⚠️ 问的是**权限**，不是命名空间 —— 这一层不该认识「能力层的命名空间」
        //    这个概念，而且那样写会让 `:app` 凭空多一条对 `:capabilitylogic`
        //    的依赖：离线跑器看不出来（全模块一次 kotlinc），Gradle 才会挂。
        canWriteSecureSettings =
        settingsAccess.isGranted(SettingsPermission.WRITE_SECURE_SETTINGS),
        canWriteSystemSettings =
        settingsAccess.isGranted(SettingsPermission.WRITE_SETTINGS_APPOP),
    )

    /**
     * 某个动作对应的系统页面。
     *
     * @return `null` = 这个动作不走 Intent（[PermissionAction.REQUEST_NOTIFICATIONS]
     *         是运行时权限请求，必须由 Activity 用 launcher 发起）
     */
    fun settingsIntent(action: PermissionAction): Intent? = when (action) {
        PermissionAction.OPEN_ACCESSIBILITY_SETTINGS ->
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

        // ⚠️ 带 `package:` 才落到"本应用"那一页；不带的话用户会看到
        //    一整列应用，还得自己找我们 —— 而他很可能找不到。
        PermissionAction.OPEN_OVERLAY_SETTINGS ->
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )

        // ⚠️ 只在 API 32 及以下用。那时没有 POST_NOTIFICATIONS 可请求，
        //    开关在"应用通知"里 —— 与 REQUEST_NOTIFICATIONS 是**两个不同的地方**。
        PermissionAction.OPEN_NOTIFICATION_SETTINGS ->
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

        // ⚠️ appop 型权限的入口。带 `package:` 才落到「本应用」那一页
        //    —— 与悬浮窗同一手法。不带的话用户要在一列应用里自己找我们。
        PermissionAction.OPEN_WRITE_SETTINGS ->
            Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            )

        PermissionAction.REQUEST_NOTIFICATIONS -> null
    }

    /**
     * 本应用的无障碍服务当前**有没有被启用**。
     *
     * ⚠️ 判据必须是系统给的启用列表，不能是进程内标志。
     *    两者会不一致，而且不一致的时间窗正是用户刚开完权限的那几秒 ——
     *    界面最容易说假话的时刻。
     */
    private fun isOurServiceEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val expected = ComponentName(context, AgentAccessibilityService::class.java)
        return manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == expected.packageName &&
                    serviceInfo.name == expected.className
            }
    }

    /**
     * 通知**会不会真的显示**。
     *
     * ⚠️ 用 `areNotificationsEnabled()` 而不是
     *    `checkSelfPermission(POST_NOTIFICATIONS)`：后者在 API 33 以下
     *    永远返回 DENIED，会把"这个版本没有这项权限"误报成"用户拒绝了"。
     *    这个 API 在所有版本上回答同一个问题。
     */
    private fun areNotificationsEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()
            ?: false

    /**
     * 无障碍服务配置里有没有声明 `android:canTakeScreenshot`。
     *
     * ⚠️ **从 XML 实际读，不在代码里再写一份常量** ——
     *    写一份 `private const val SCREENSHOT_DECLARED = false` 看起来更简单，
     *    但它和 XML 之间没有任何东西保证同步。等到感知层落地、有人往 XML 里
     *    加了这一行，界面上会继续显示"还没实现" —— 而且**没有任何东西会报错**。
     *
     * 读不到时返回 `false`：宁可少报一个能力，也不要谎报一个不存在的。
     */
    private fun isScreenshotDeclaredInConfig(): Boolean = runCatching {
        val parser = context.resources.getXml(R.xml.agent_accessibility_service)
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val declared = (0 until parser.attributeCount).any { i ->
                    parser.getAttributeName(i) == ATTR_CAN_TAKE_SCREENSHOT &&
                        parser.getAttributeValue(i) == "true"
                }
                if (declared) return@runCatching true
            }
            event = parser.next()
        }
        false
    }.getOrDefault(false)

    private companion object {
        /**
         * XML 里属性的名字。`android:` 前缀不会出现在 `getAttributeName` 里
         * （命名空间是分开的），所以这里不带前缀。
         */
        const val ATTR_CAN_TAKE_SCREENSHOT = "canTakeScreenshot"
    }
}
