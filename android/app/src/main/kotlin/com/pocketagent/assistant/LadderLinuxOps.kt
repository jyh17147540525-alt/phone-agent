package com.pocketagent.assistant

import com.pocketagent.mcp.LinuxAvailability
import com.pocketagent.mcp.LinuxOpsPort
import com.pocketagent.mcp.LinuxReadResult
import com.pocketagent.mcp.LinuxRunResult

/**
 * 后端阶梯：**能借 Termux 就借，借不到用内置引擎**。
 *
 * ## 为什么在运行时就地选，而不是开机选一次
 *
 * 用户可能中途装 Termux、中途撤权限、Termux 被系统冻结。开机选一次的后端会一直
 * 报"不可用"，而用户明明已经从 F-Droid 装好了 —— 那种"我明明装了它却说没装"的体验
 * 比直接不支持更糟。
 *
 * ## 为什么降级要写在文案里
 *
 * 两个后端的能力**不一样**（Termux 能装包、能跑任意已装程序；内置只能跑 Python 标准库）。
 * 如果降级后只说"可用"，用户会以为 `pip install` 也能跑，然后在 Termux 那边找不到原因。
 */
class LadderLinuxOps(
    private val termux: LinuxOpsPort,
    private val builtin: LinuxOpsPort,
    /** root 容器（B2）。没有 root 时它自己会给"缺什么"的说明。 */
    private val root: LinuxOpsPort? = null,
) : LinuxOpsPort {

    override suspend fun availability(): LinuxAvailability {
        // ★ 顺序 root → Termux → 内置：能力从多到少，用户拿到的永远是当前能给的**最好那一档**
        val rootStatus = root?.availability()
        if (rootStatus?.ready == true) return rootStatus

        val termuxStatus = termux.availability()
        if (termuxStatus.ready) return termuxStatus

        val fallback = builtin.availability()
        return if (fallback.ready) {
            LinuxAvailability(
                ready = true,
                explain = fallback.explain +
                    "（这是内置引擎：装不了软件包、断不了网。" +
                    "Termux：${termuxStatus.explain}" +
                    (rootStatus?.let { "；root 容器：${it.explain}" } ?: "") + "）",
            )
        } else {
            LinuxAvailability(
                ready = false,
                explain = "${fallback.explain}；Termux：${termuxStatus.explain}" +
                    (rootStatus?.let { "；root 容器：${it.explain}" } ?: ""),
            )
        }
    }

    override suspend fun run(argv: List<String>, timeoutSec: Int): LinuxRunResult =
        pick().run(argv, timeoutSec)

    override suspend fun read(path: String, maxBytes: Int): LinuxReadResult =
        pick().read(path, maxBytes)

    private suspend fun pick(): LinuxOpsPort = when {
        root?.availability()?.ready == true -> root
        termux.availability().ready -> termux
        else -> builtin
    }
}
