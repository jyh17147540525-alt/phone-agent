package com.pocketagent.assistant

import com.pocketagent.mcp.SystemOpOutcome
import com.pocketagent.mcp.SystemOpsPort
import com.pocketagent.rootlogic.RootAction
import com.pocketagent.rootlogic.RootActions
import com.pocketagent.rootlogic.SettingNamespace
import com.pocketagent.rootlogic.ShellResult

/**
 * 把 root 通道接到 `:mcp` 的 [SystemOpsPort] 上。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这层单独存在，而不是让工具直接拿 RootActions
 * ═══════════════════════════════════════════════════════════════
 *
 * `:mcp` 里的工具是**跨端复用**的（同一份工具代码也跑在别的宿主里），
 * 它们不该知道"这台手机有没有 root、root 是怎么拿到的"。这层薄适配的职责
 * 只有一个：**把 [ShellResult] 的三档结局翻译成工具能据以行动的三种话术**。
 *
 * ⚠️ 翻译的关键是 [SystemOpOutcome.Rejected] 与 [SystemOpOutcome.Failed] 的分界：
 *   · `REJECTED = -1` 是 [RootActions] **本地拒绝**（参数白名单没过，命令没发出去）
 *     ⇒ 模型该改参数；
 *   · 其它非零退出码是**真的跑了但失败**（SELinux、权限、目标不存在）
 *     ⇒ 模型改参数没用，得换思路或告诉用户。
 */
class RootSystemOps(private val root: RootActions) : SystemOpsPort {

    override fun readFile(path: String): SystemOpOutcome =
        translate(root.run(RootAction.ReadFile(path)))

    override fun putSetting(namespace: String, key: String, value: String): SystemOpOutcome {
        val ns = NAMESPACES[namespace] ?: return SystemOpOutcome.Rejected("未知命名空间：$namespace")
        return translate(root.run(RootAction.PutSetting(ns, key, value)))
    }

    override fun setProp(name: String, value: String): SystemOpOutcome =
        translate(root.run(RootAction.SetProp(name, value)))

    override fun disablePackage(packageName: String): SystemOpOutcome =
        translate(root.run(RootAction.DisablePackage(packageName)))

    private fun translate(result: ShellResult): SystemOpOutcome = when {
        result.code == RootActions.REJECTED -> SystemOpOutcome.Rejected(
            // `describe()` 里已经写了"哪个参数不合法"，原样透出即可 —— 改写反而丢线索。
            result.stderr.ifBlank { "参数未通过白名单校验" },
        )
        result.code == 0 -> SystemOpOutcome.Ok(result.stdout.trim())
        else -> SystemOpOutcome.Failed(result.describe())
    }

    private companion object {
        /**
         * 与 [SystemArgs.NAMESPACES] 一一对应。
         *
         * ⚠️ 这里**必须**显式映射而不是 `valueOf(upper)`：`settings` 的命名空间
         *    在 CLI 里是小写、在枚举里是大写，用反射转换会在新增命名空间时静默崩。
         */
        val NAMESPACES = mapOf(
            "system" to SettingNamespace.SYSTEM,
            "secure" to SettingNamespace.SECURE,
            "global" to SettingNamespace.GLOBAL,
        )
    }
}
