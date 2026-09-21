package com.pocketagent.data

import com.pocketagent.plugin.api.PluginManifest

/**
 * 磁盘上的一个已安装插件。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么坏目录也要返回，而不是跳过
 * ═══════════════════════════════════════════════════════════════
 *
 * [manifest] 为 null 表示这个目录坏了 —— 清单被删、被改坏，或者磁盘出错。
 *
 * 直觉上应该跳过它（"解析不了的就不是插件"），但那样用户会看到
 * **自己装的插件从列表里消失了**。而"东西不见了"比"东西坏了"严重得多：
 * 前者会让人怀疑应用在偷删数据，后者只是个待处理的技术问题。
 *
 * 所以坏目录照样返回，标上 [isBroken] 和原因，让用户能看见它、能卸载它。
 */
data class InstalledPlugin(
    /** 磁盘目录名（由插件 id 净化而来，见 PluginInstaller.sanitizeDirName） */
    val dirName: String,

    /** 解析出的清单。null 表示目录损坏 */
    val manifest: PluginManifest?,

    /** 损坏原因，仅 [isBroken] 时有值 */
    val brokenReason: String?,

    /** 目录的最后修改时间，用作"安装时间"的近似值 */
    val installedAtMillis: Long,
) {
    val id: String get() = manifest?.id ?: dirName

    val displayName: String get() = manifest?.name ?: dirName

    val isBroken: Boolean get() = manifest == null
}
