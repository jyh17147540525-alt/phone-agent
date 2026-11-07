package com.pocketagent.rootlogic

/**
 * 通道进程到底是什么身份。
 *
 * ## 为什么必须分清 root 与 shell
 *
 * 这个项目里"有没有特权"不是二值的。三条路能干的活不一样：
 *
 * | 身份 | 怎么来 | 能干什么 |
 * |---|---|---|
 * | root | Magisk/KernelSU 授权 | 全部：`SurfaceControl`、改属性、停用系统包 |
 * | shell（uid 2000） | 手机自己的无线调试配对 / 电脑 adb | 虚拟屏那套：`am start --display`、`input -d`、`screencap -d` |
 * | 都不是 | 普通进程 | 只剩无障碍（要占屏） |
 *
 * ⚠️ 以前这里只有"是 root / 不是 root"，于是 shell 被直接判成"通道不可用"——
 *    而虚拟屏要的正好是 shell。身份分不清，能力就没法按身份裁。
 */
enum class PrivilegeIdentity(val label: String) {
    ROOT("root"),
    SHELL("shell"),
    NONE("普通进程"),
}

/**
 * 把 `id` 的输出翻译成身份。
 *
 * 只认 uid，不认名字：`id` 在不同 ROM 上的用户名可能被改，
 * 但 uid 是内核定的（0 = root、2000 = shell）。
 */
object ProbeIdentity {

    private val UID_ROOT = Regex("""\buid=0\b""")
    private val UID_SHELL = Regex("""\buid=2000\b""")

    fun parse(identityLine: String): PrivilegeIdentity = when {
        UID_ROOT.containsMatchIn(identityLine) -> PrivilegeIdentity.ROOT
        UID_SHELL.containsMatchIn(identityLine) -> PrivilegeIdentity.SHELL
        else -> PrivilegeIdentity.NONE
    }

    /** 给用户看的一句话。 */
    fun describe(identity: PrivilegeIdentity): String = when (identity) {
        PrivilegeIdentity.ROOT -> "通道以 root 身份在跑，能做的都做得了。"
        PrivilegeIdentity.SHELL -> "通道以 shell 身份在跑（无线调试配对那种），虚拟屏够用，改系统设置不够。"
        PrivilegeIdentity.NONE -> "通道进程既不是 root 也不是 shell，这条通道用不了。"
    }
}
