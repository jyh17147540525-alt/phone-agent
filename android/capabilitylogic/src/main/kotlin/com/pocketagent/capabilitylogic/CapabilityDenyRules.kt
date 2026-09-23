package com.pocketagent.capabilitylogic

/**
 * 硬拒绝清单 —— 一张**单向阀**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 它挡的不是"用户乱点"，是"目录被改宽"
 * ═══════════════════════════════════════════════════════════════
 *
 * [CapabilityCatalog] 是内置的，但它**不是唯一的目录**：插件可以带自己的能力清单，
 * 未来也可能允许用户自定义。所以"目录里没有 = 安全"这个等式**不成立**。
 *
 * 这张清单的性质与 `filelogic` 的 `DenyRules` 完全一样：
 * **它不参与任何覆盖逻辑，只会拒绝。** 用户把授权范围划到最大、
 * 插件往目录里塞进任意能力，都越不过它。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 最重要的一条：`secure/enabled_accessibility_services`
 * ═══════════════════════════════════════════════════════════════
 *
 * 如果这条键可写，那么下面这个链条**只需两步**：
 *
 * ```
 * 1. settings put secure enabled_accessibility_services com.pocketagent.debug/...Service
 * 2. settings put secure accessibility_enabled 1
 * ```
 *
 * 后果：agent 给自己授予了无障碍权限 —— **读全屏、点全屏、看得到支付页**。
 * 而本项目的整个安全模型（插件默认关进沙箱、默认拒绝逐项放行、敏感页面硬拦截）
 * 建立在一个前提上：**特殊权限只能由用户亲手授予**。
 *
 * 这条键一开，那个前提就没了 —— 不需要漏洞、不需要 root、不需要诱导用户点任何东西，
 * **一条设置写入就够了**。而且它**不报错、不弹窗、不留痕**。
 *
 * 同类还有 `enabled_notification_listeners`（读全部通知）、
 * `default_input_method`（换成自己的输入法即可读所有键盘输入）、
 * `adb_enabled`（开启调试）、`lockscreen.disabled`（解除锁屏）。
 *
 * ⇒ 这些键**不是"危险"，是"会把护栏本身拆掉"**。它们必须在这里，而不是靠
 *   "别往目录里加"这种约定。
 */
object CapabilityDenyRules {

    /**
     * 永不可写的设置键，写作 `namespace/key`。
     *
     * ⚠️ 用**完整键名**匹配，不做前缀匹配 —— 前缀匹配会连带拒掉
     *    `enabled_accessibility_services_for_xxx` 这类无关键，
     *    而"宽了一点"和"紧了一点"在这里的代价不对称：
     *    误拒只是让某条能力用不了（可见、可排查），
     *    漏放则是整个安全模型失效（不可见、不可回溯）。
     *    ⇒ 所以这份清单**宁长勿短**，每加一条都写清理由。
     */
    val DENIED_SETTING_KEYS: Set<String> = setOf(
        // ── 无障碍：开了它，agent 就给自己发了全屏读写权限 ──────────
        "secure/enabled_accessibility_services",
        "secure/accessibility_enabled",
        "secure/accessibility_shortcut_target_service",
        "secure/accessibility_shortcut_enabled",
        "secure/accessibility_button_targets",
        "secure/accessibility_qs_targets",

        // ── 通知使用权：开了它，agent 能读全部通知（含验证码短信）────
        "secure/enabled_notification_listeners",
        "secure/notification_listener_services",

        // ── 输入法：换成自己的输入法，等于拿到所有键盘输入 ──────────
        "secure/default_input_method",
        "secure/enabled_input_methods",
        "secure/input_methods_subtype_history",

        // ── 锁屏与凭据：见 MEMORY 里的「代解锁 = 已否决」─────────────
        "secure/lockscreen.disabled",
        "secure/lock_screen_lock_after_timeout",
        "secure/lock_screen_allow_private_notifications",
        "secure/lock_screen_show_notifications",
        "secure/lockscreen_password_length",

        // ── 调试与安装：开了它，等于把设备交给 adb ──────────────────
        "global/adb_enabled",
        "global/development_settings_enabled",
        "global/package_verifier_enable",
        "global/package_verifier_user_consent",
        "secure/install_non_market_apps",

        // ── 设备管理：能设 device owner / 设备管理员 ────────────────
        "global/device_provisioned",
        "global/device_name",
        "secure/user_setup_complete",

        // ── 网络与代理：改这些能把流量导向别处 ──────────────────────
        "global/http_proxy",
        "global/global_http_proxy_host",
        "global/global_http_proxy_port",
        "global/private_dns_mode",
        "global/private_dns_specifier",
    )

    /**
     * 永不放行的能力 id 前缀。
     *
     * 这些能力**没有**出现在 [CapabilityCatalog] 里（按"让能力不存在"的立场），
     * 这里是为了挡住"有人自己加进来"。
     */
    val DENIED_CAPABILITY_PREFIXES: List<String> = listOf(
        // 授予/撤销权限 —— 与"特殊权限只能用户亲手授予"直接冲突
        "package.grant",
        "package.revoke",
        // 角色授予（能拿到通话、短信、浏览器等系统角色）
        "role.",
        // 安装/卸载
        "package.install",
        "package.uninstall",
        // 凭据与锁屏
        "locksettings.",
        "credential.",
        // 直接写任意设置键（绕过 [CapabilityCatalog] 的键白名单）
        "setting.write",
        "settings.put",
        // 进程与调试
        "process.kill",
        "debug.",
    )

    /**
     * 永不可停用的包。
     *
     * ⚠️ 这条清单的必要性容易被低估：`cmd package disable` 是**一条命令**，
     *    而 `com.android.systemui` 被停用之后**界面直接没了** ——
     *    没有状态栏、没有导航、没有通知，用户只能靠 adb 或恢复模式救回来。
     *    对一台"只有手机"的用户来说，这等于设备变砖。
     *
     * 判据：**停用它会让用户失去"把手机用回来"的能力**。
     */
    val DENIED_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.providers.settings",
        "com.android.shell",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        // 桌面：停用它之后用户没有可回的主屏
        "com.miui.home",
        "com.android.launcher3",
        // 本项目自己 —— 停用它等于 agent 自杀，且用户没法从应用内恢复
        "com.pocketagent.debug",
        "com.pocketagent",
    )

    /** 设置键是否被硬拒绝。 */
    fun isSettingKeyDenied(namespace: SettingNamespace, key: String): Boolean =
        "${namespace.wireName}/$key" in DENIED_SETTING_KEYS

    /** 能力 id 是否被硬拒绝。 */
    fun isCapabilityDenied(capabilityId: String): Boolean =
        DENIED_CAPABILITY_PREFIXES.any { capabilityId.startsWith(it) }

    /** 包名是否被硬拒绝。 */
    fun isPackageDenied(packageName: String): Boolean = packageName in DENIED_PACKAGES

    /** 供界面展示："哪些东西是我永远不会碰的"。透明度报告要用。 */
    fun summaryForUser(): String =
        "有 ${DENIED_SETTING_KEYS.size} 项系统设置、${DENIED_CAPABILITY_PREFIXES.size} 类能力、" +
            "${DENIED_PACKAGES.size} 个系统组件是固定不可操作的。" +
            "这一条不受任何授权或插件影响。"
}
