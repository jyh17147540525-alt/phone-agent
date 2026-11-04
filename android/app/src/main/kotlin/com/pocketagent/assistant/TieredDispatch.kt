package com.pocketagent.assistant

import com.pocketagent.mcp.ActionDispatchPort
import com.pocketagent.mcp.AgentAction
import com.pocketagent.mcp.DispatchOutcome
import com.pocketagent.mcp.GlobalKeyName
import com.pocketagent.rootlogic.PrivilegeMode
import com.pocketagent.rootlogic.ScreenLease
import com.pocketagent.rootlogic.RootAction
import com.pocketagent.rootlogic.RootActions
import com.pocketagent.rootlogic.RootShellPort
import com.pocketagent.rootlogic.ShellResult

/**
 * **两档执行的汇合点** —— 同一个 `android_tap`，普通档走无障碍手势，root 档走 `su -c input tap`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么档位在这层判，而不是在工具里判
 * ═══════════════════════════════════════════════════════════════
 *
 * 工具（`:mcp`）是**跨端复用**的：同一份 `TapTool` 也给 dsh 用，它不该知道
 * "手机有没有 root"。档位是**这一侧的事实**，由 `:app` 注入最合适 ——
 * 于是工具的代码一行都不用改，两档自动分流。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ root 路径失败时**不许悄悄退回无障碍**
 * ═══════════════════════════════════════════════════════════════
 *
 * 退回无障碍意味着**占住用户的屏幕**，而用户选 root 档就是为了不占屏。
 * 悄悄退回去，用户只会看到"手机突然自己跳页面了"。
 * ⇒ 失败就如实返回 `Failed`，让模型/用户知道 su 那条路断了
 *   （通常是 Magisk 里被拒了），而不是假装成功。
 */
class TieredDispatchPort(
    private val plain: ActionDispatchPort,
    private val root: RootActions,
    private val mode: () -> PrivilegeMode,
    /**
     * 占屏租约 —— **只在普通档生效**。
     *
     * ⚠️ 普通档每一下点按都要**先问一句"现在能不能动屏幕"**：
     *   · 用户正在用手机（刚摸过屏）→ 让开，等手松开；
     *   · 用户按了停 → 就是停下，不自动回来；
     *   · 到期 → 任务该收尾。
     * 这三件事以前只有 `ScreenLease` 的纯逻辑，没有调用方；
     * 现在是**真正的调用点**：拒绝的理由直接来自租约，不是这里另写一句。
     *
     * root 档不受它影响：整条路不占用屏幕，也就没有"让开"这回事。
     */
    private val lease: ScreenLease? = null,
) : ActionDispatchPort {

    override suspend fun dispatch(action: AgentAction): DispatchOutcome =
        if (mode() == PrivilegeMode.ROOT) dispatchAsRoot(action) else dispatchAsPlain(action)

    private suspend fun dispatchAsPlain(action: AgentAction): DispatchOutcome {
        val lease = this.lease ?: return plain.dispatch(action)

        // ★ 先续租再判：Idle/Expired 会**新开**一份租约，Yielded 会恢复原来的
        //   总期限（不因为"又点了一下"就把 90 秒重新数起）。
        lease.acquire(SCREEN_REASON)
        if (!lease.canAct()) {
            // 拒绝文案直接来自租约 —— 三种让开原因各有各的说法，
            // 在这里另写一句必然会和租约的状态机漂移。
            return DispatchOutcome.Refused(lease.blockedReason() ?: "现在不能操作屏幕。")
        }
        return plain.dispatch(action)
    }

    private fun dispatchAsRoot(action: AgentAction): DispatchOutcome {
        val started = System.nanoTime()
        val steps: List<RootAction> = when (action) {
            is AgentAction.Tap -> listOfNotNull(tapTarget(action.ref, action.point) ?: return noTarget())

            is AgentAction.Swipe -> listOf(
                RootAction.Swipe(
                    fromX = action.from.x,
                    fromY = action.from.y,
                    toX = action.to.x,
                    toY = action.to.y,
                    millis = action.durationMs.toInt().coerceIn(1, 60_000),
                ),
            )

            // 打字 = 先点一下目标（拿到焦点）再 input text。
            // ★ 顺序不能反：没有焦点时 input text 会打进上一次的输入框。
            is AgentAction.TypeText -> run {
                val tap = tapTarget(action.ref, action.point) ?: return noTarget()
                listOf(tap, RootAction.Text(action.text))
            }

            // 只映射 `GlobalKeyName` 里的三个 —— 枚举穷尽匹配，
            // 写错一个键名在编译期就炸，不会变成"按了没反应"。
            is AgentAction.GlobalKey -> listOf(
                RootAction.Key(
                    when (action.key) {
                        GlobalKeyName.BACK -> "KEYCODE_BACK"
                        GlobalKeyName.HOME -> "KEYCODE_HOME"
                        GlobalKeyName.RECENTS -> "KEYCODE_APP_SWITCH"
                    },
                ),
            )
        }

        // 一条动作可能有多个步骤（打字）：**任一步失败就停**，不带着半个结果继续。
        for (step in steps) {
            val result = root.run(step)
            if (!result.ok) {
                return DispatchOutcome.Failed(
                    "root 通道执行失败（${step.javaClass.simpleName}）：${result.describe()}。" +
                        "常见原因是 Magisk 里没给本应用放行，或 SELinux 拦了这条命令。",
                )
            }
        }
        val latency = (System.nanoTime() - started) / 1_000_000
        return DispatchOutcome.Dispatched(channel = ROOT_CHANNEL, latencyMs = latency)
    }

    /** 点击目标：有坐标用坐标，只有节点引用就用它的中心点。 */
    private fun tapTarget(
        ref: com.pocketagent.mcp.ResolvedNodeRef?,
        point: com.pocketagent.mcp.ScreenPoint?,
    ): RootAction.Tap? = when {
        point != null -> RootAction.Tap(point.x, point.y)
        ref != null -> RootAction.Tap(
            x = (ref.bounds.left + ref.bounds.right) / 2,
            y = (ref.bounds.top + ref.bounds.bottom) / 2,
        )

        else -> null
    }

    private fun noTarget(): DispatchOutcome = DispatchOutcome.Failed(
        "工具内部错误：动作同时缺少节点引用与坐标（这是接线 bug，不该发生）。",
    )

    companion object {
        /** 通道名 —— 诊断"这一下到底是哪个通道干的"的唯一线索。 */
        const val ROOT_CHANNEL = "root-su-input"

        /** 占屏理由 —— 会出现在租约状态与「让开」的文案里。 */
        const val SCREEN_REASON = "agent 正在操作屏幕"
    }
}

/**
 * **真机**的 `su` 端口。
 *
 * ⚠️ 这是整个 root 档唯一碰 Android 进程与 IO 的地方，所以刻意做得极薄：
 *    参数校验在 `:rootlogic` 的 `RootCommands`（有离线测试），这里只负责
 *    「数组形态交给 ProcessBuilder → 等 → 收 stdout/stderr → 超时杀掉」。
 *
 * ★ 用 `ProcessBuilder("su", "-c", payload)` 的**数组**形态，不拼字符串：
 *   payload 里已经被 `RootCommands` 校验过，但**多一层不拼字符串**是免费的保险。
 */
class SuShellPort(
    private val timeoutMillis: Long = 15_000,
) : RootShellPort {

    override fun run(argv: List<String>): ShellResult = try {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        // ★ 先读流再 waitFor：命令输出超过管道缓冲（约 64KB）时，
        //   先 waitFor 会永远等不到 —— 这是 `screencap`/`dumpsys` 最常见的死锁。
        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            ShellResult(code = TIMEOUT, stdout = stdout, stderr = "命令超过 ${timeoutMillis}ms 未返回，已终止")
        } else {
            ShellResult(code = process.exitValue(), stdout = stdout, stderr = stderr)
        }
    } catch (e: Exception) {
        // ⚠️ 不抛：端口契约与 `ActionDispatchPort` 同一条 —— 失败归入返回值。
        ShellResult(code = SPAWN_FAILED, stdout = "", stderr = "启动 su 失败：${e.message}")
    }

    companion object {
        /** 超时的伪退出码（真实进程不会返回 124，与 `timeout(1)` 的约定对齐）。 */
        const val TIMEOUT = 124

        /** 连进程都起不来（没有 su / 被 SELinux 拦）。 */
        const val SPAWN_FAILED = 125
    }
}
