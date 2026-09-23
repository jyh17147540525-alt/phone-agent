package com.pocketagent.capabilitylogic

import com.pocketagent.core.common.permission.SettingsPermission
import com.pocketagent.core.common.permission.SettingsPermissionGuide

/**
 * 命名空间 → 所需授权。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 这个文件只留「映射」，不留「文案」
 * ═══════════════════════════════════════════════════════════════
 *
 * [SettingsPermission] 与它的指引文案住在 `:core:common`（理由写在那边）。
 * 这里只剩一件本模块**独有**的知识：**哪个命名空间要哪一项授权** ——
 * 而它必须留在这里，因为 [SettingNamespace] 是本模块的类型。
 *
 * 拆开的动机是"文案只能有一份"：权限页要在**还没授权时**就把命令显示给用户，
 * 而执行层要在**执行失败时**说同一句话。两边各写一份 → 它们会不一致，
 * 而用户会照着权限页抄来的命令跑一遍，然后发现执行层说的是另一条。
 */
object SettingsPermissionPolicy {

    /** 写这个命名空间需要哪一项授权。 */
    fun requiredFor(namespace: SettingNamespace): SettingsPermission = when (namespace) {
        SettingNamespace.GLOBAL,
        SettingNamespace.SECURE,
        -> SettingsPermission.WRITE_SECURE_SETTINGS

        SettingNamespace.SYSTEM -> SettingsPermission.WRITE_SETTINGS_APPOP
    }

    /**
     * 没有权限时给用户的那句话。
     *
     * ⚠️ 实现**故意**只是一层转发：文案本体在
     * [SettingsPermissionGuide.guidanceFor]，与权限页用的是同一份。
     *    这里保留这个函数是因为调用方（`AndroidSettingsAccess`）手上只有
     *    命名空间，让它自己去查映射会把那张表复制一遍。
     */
    fun guidanceFor(namespace: SettingNamespace, applicationId: String): String =
        SettingsPermissionGuide.guidanceFor(requiredFor(namespace), applicationId)
}
