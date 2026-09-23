package com.pocketagent.capability

import com.pocketagent.capabilitylogic.ChannelResult
import com.pocketagent.capabilitylogic.ChannelUnavailableReason
import com.pocketagent.capabilitylogic.ShellRunner

/**
 * shell 通道的**尚未实现**声明。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 它不是一个"占位实现"，是一个明确的事实陈述
 * ═══════════════════════════════════════════════════════════════
 *
 * 为什么不干脆给 [com.pocketagent.capabilitylogic.CapabilityRunner] 传 `shell = null`：
 *
 * 因为 `null` 让判定层说出的话是
 * **「这条动作要以 shell 身份执行，但当前没有接上 shell 通道」** ——
 * 那句话把原因指向了**配置**（"没接上"），而事实是**我们还没写**。
 * 两者的下一步完全不同：前者用户会去翻设置找一个开关，后者他只能等一个更新。
 *
 * ⇒ 本项目反复踩的那一类坑就是"不报错、只是安静地少做一件事"。
 *   把"还没做"说成"没接上"，正是那个坑的一种形态。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不能先用别的办法把 shell 命令跑起来
 * ═══════════════════════════════════════════════════════════════
 *
 * 以 shell 身份执行在 Android 上只有两条路：`Runtime.exec`（无特权，等于没做）
 * 与 Shizuku。而 **`Shizuku#newProcess` 已经被移除了**（13.1.1 起）——
 * 照旧文档写出来的实现会**静默降级**成无特权的 `Runtime.exec`，
 * 界面上却照常显示"Shizuku 模式"。详细的证据在
 * `ShellRunner` 的 KDoc 里。
 *
 * ⇒ 正解是 Shizuku 的 `UserService`（应用自带 `.aidl` + service 类，
 *   由 Shizuku 在 shell 身份的进程里拉起）。那一步尚未开工。
 *
 * ⚠️ 这个类的存在还有第二个作用：**让界面上那几条 shell 能力显示成
 *   「还差一步配置」而不是「没有接上通道」** —— 前者指向一条明确的下一步，
 *   后者指向一个用户永远找不到的开关。
 */
class PendingShellRunner : ShellRunner {

    override suspend fun run(argv: List<String>): ChannelResult = ChannelResult.Unavailable(
        reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
        // ⚠️ 不得含 argv —— 这条字符串会进审计日志。
        //    说清"还没实现"以及"差的是哪一步"，而不是"配置没接上"。
        detail = "以 shell 身份执行的那条路（Shizuku）还没实现，" +
            "所以这一条现在跑不了 —— 它要等 UserService 那一步做完。",
    )
}
