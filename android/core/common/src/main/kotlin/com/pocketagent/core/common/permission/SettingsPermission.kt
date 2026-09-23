package com.pocketagent.core.common.permission

/**
 * 写系统设置需要哪一个系统授权。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这件事必须写下来（2026-09-23 真机 dumpsys 实测）
 * ═══════════════════════════════════════════════════════════════
 *
 * 三个命名空间看起来是"同一件事的三个区"，实际上**授权机制完全不同**：
 *
 * | 命名空间 | 权限 | 保护级别（真机实测） | `pm grant` 能授吗 |
 * |---|---|---|---|
 * | GLOBAL / SECURE | `WRITE_SECURE_SETTINGS` | `signature\|privileged\|`**`development`**`\|installer\|role` | **能** |
 * | SYSTEM | `WRITE_SETTINGS` | `signature\|`**`appop`**`\|pre23\|preinstalled\|role` | **不能** |
 *
 * ⇒ 差别就在那一位上：`development` 位让 `pm grant` 生效；`appop` 型的权限
 *   不走包管理器，只能由用户在系统设置里开，或 `appops set … allow`。
 *
 * ⚠️ 所以这不是"记一个常量"，而是决定**界面上给用户什么指引**。
 *    把两者混成一个"没有写设置权限"，用户拿到的指引会有一半是错的 ——
 *    而错的指引比没有指引更坏：他会照着做一遍、什么都没发生，
 *    然后认定"这个功能坏了"，而真正的原因是他做了一件**结构上不可能成功**的事。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么这个枚举在 `:core:common`，而不在 `:capabilitylogic`
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为它有**两个**互不相干的消费方，而它们不能互相依赖：
 *
 * | 消费方 | 用途 |
 * |---|---|
 * | `:capabilitylogic`（能力层） | 执行失败时告诉用户"去跑哪条命令" |
 * | `HostPermissions`（权限页） | 还没授权时**就在页面上**显示同一条命令 |
 *
 * 两边各写一份文案的后果不是"重复"，而是**它们会不一致** ——
 * 用户在权限页抄到的命令，与执行失败时看到的命令不是同一条。
 * 而 `:capabilitylogic` 已经（通过生成器给每个模块的
 * `implementation(project(":core:common"))`）站在 `:core:common` 之上，
 * 反方向依赖会成环。⇒ 把**知识**放在下面的那层，两边都往上取。
 */
enum class SettingsPermission(val displayName: String) {

    /**
     * 写 `Settings.Global` / `Settings.Secure` 要的权限。
     *
     * ★ 它**可以**由 `adb shell pm grant` 授予（保护级别里有 `development` 位）
     *   —— 这是本项目"我们只负责开发、其余由用户自行配置"这条边界能成立的前提。
     *
     * ⚠️ 但它**没有**任何系统设置界面入口。所以对用户来说这是一件
     *   "手机上做不到"的事 —— 见 [SettingsPermissionGuide.isReachableOnDevice]。
     */
    WRITE_SECURE_SETTINGS("修改系统安全设置"),

    /**
     * 写 `Settings.System` 要的权限。
     *
     * ⚠️ **`pm grant` 对它无效**（保护级别里没有 `development`，是 `appop` 型）。
     *    看起来同样是"写设置"，但授予方式、报错时机、界面指引全都不同。
     *
     * ★ 实测（2026-09-23，红米 K60 / Android 15）：
     *   · `pm grant <pkg> android.permission.WRITE_SETTINGS`
     *     → `SecurityException: Permission android.permission.WRITE_SETTINGS
     *        is managed by role`，退出码 255。**会报错，不是静默无效** ——
     *     但这句报错里没有任何一个字提到"你该改用 appops"，
     *     所以仍然不能把它交到用户手里（见 [SettingsPermissionGuide.guidanceFor]）。
     *   · 声明进 manifest 之前，`cmd appops get <pkg> WRITE_SETTINGS` 报
     *     `No operations / Default mode: default`；声明之后变成 `ignore`。
     *   · `cmd appops set <pkg> WRITE_SETTINGS allow` → 复查为 `allow`。**可行。**
     *
     * ★ 它**有**系统设置界面入口（"特殊应用权限 → 修改系统设置"），
     *   所以在手机上能自己解决 —— 这一点与上一项正相反。
     */
    WRITE_SETTINGS_APPOP("修改系统设置"),
}

/**
 * 「怎么授权」的唯一一份文案。
 *
 * ⚠️ 放在纯模块里而不是 Android 适配器里，有两个理由：
 *   ① 这张映射表是**知识**，不是管道 —— 它值得被测试钉住；
 *   ② 指引文案是**用户可见的**，而文案里最容易被写错的是包名
 *      （硬编码成 `com.pocketagent` 时，debug 构建的 `com.pocketagent.debug`
 *      会拿到一条永远失败的 `pm grant` 命令）。所以它必须能被断言。
 */
object SettingsPermissionGuide {

    /**
     * 授权这一项要跑的那条命令。
     *
     * ⚠️ [applicationId] 必须由调用方传入**真实的应用 id**，不能硬编码。
     *    本项目有两个 id（`com.pocketagent` 与 `com.pocketagent.debug`），
     *    而写错的 `pm grant` 是一条**跑得通、但什么也没授**的命令。
     */
    fun adbCommandFor(permission: SettingsPermission, applicationId: String): String =
        when (permission) {
            SettingsPermission.WRITE_SECURE_SETTINGS ->
                "adb shell pm grant $applicationId android.permission.WRITE_SECURE_SETTINGS"

            SettingsPermission.WRITE_SETTINGS_APPOP ->
                "adb shell appops set $applicationId WRITE_SETTINGS allow"
        }

    /**
     * 这一项**能不能在手机上自己解决**。
     *
     * ⚠️ 它决定界面上给不给"去设置"按钮，而给错按钮的代价是
     *    **用户去系统设置里找一个不存在的东西**（见 `GrantState.Undeclared`
     *    与 `PermissionAction` 的注释里同一类论证）。
     *
     * `WRITE_SECURE_SETTINGS` 是 `signature|privileged|development|…` 型的
     * 签名级权限：**系统设置里没有它的开关**。真机上把"应用 → 特殊应用权限"
     * 翻遍也找不到 —— 因为它从来不在那里。
     */
    fun isReachableOnDevice(permission: SettingsPermission): Boolean = when (permission) {
        SettingsPermission.WRITE_SECURE_SETTINGS -> false
        SettingsPermission.WRITE_SETTINGS_APPOP -> true
    }

    /**
     * 没有权限时给用户的那句话。
     *
     * ⚠️ 两个分支的**结构**必须不同，不能只是把名词换掉：
     *    · `WRITE_SECURE_SETTINGS` → 手机上没有开关，只能去电脑上跑一条命令；
     *    · `WRITE_SETTINGS_APPOP`   → 手机上有开关（**优先说这一条**），
     *      顺带说明"`pm grant` 那条路走不通" —— 因为网上到处都能搜到它，
     *      而它对这个权限**只会报错**。
     */
    fun guidanceFor(permission: SettingsPermission, applicationId: String): String =
        when (permission) {
            SettingsPermission.WRITE_SECURE_SETTINGS ->
                "这一条要改写系统安全设置，需要「修改系统安全设置」授权。" +
                    "它在手机的设置里没有开关（属于签名级权限），" +
                    "所以需要在电脑上执行一次：" +
                    adbCommandFor(permission, applicationId) +
                    "（这个授权只需要做一次，之后一直有效）。"

            SettingsPermission.WRITE_SETTINGS_APPOP ->
                "这一条要改写系统设置，需要「修改系统设置」授权。" +
                    "请到「设置 → 应用 → 特殊应用权限 → 修改系统设置」里为本应用打开。" +
                    "⚠️ 网上常见的 pm grant 那条命令对「这一项无效」—— " +
                    "它会直接报错（SecurityException: ... is managed by role），什么也不会变。" +
                    "如果在手机上找不到那个开关，也可以在电脑上执行：" +
                    adbCommandFor(permission, applicationId)
        }
}
