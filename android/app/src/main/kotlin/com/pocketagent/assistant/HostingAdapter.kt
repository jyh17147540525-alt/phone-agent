package com.pocketagent.assistant

import com.pocketagent.hosting.HostBounds
import com.pocketagent.hosting.HostingResult
import com.pocketagent.mcp.AppHostingPort

/**
 * `AppHostingRunner` → `AppHostingPort` 的适配。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要这一层薄的适配，而不是让 runner 直接实现端口
 * ═══════════════════════════════════════════════════════════════
 *
 * `AppHostingRunner` 的返回值是 `:hosting` 的 `HostingResult`（纯逻辑模块），
 * 而 `AppHostingPort` 是 `:mcp` 的端口形状。
 *
 * ⚠️ 两者**刻意不是同一个类型**：
 *    `:hosting` 要能进离线验证器（零 Android 依赖），
 *    所以它的结果类型不能反过来依赖 `:mcp`。
 *
 * ⇒ 这个文件是**唯一知道两边**的地方 —— 与 `AgentAdapters` 的定位相同。
 *
 * ★ 三分支映射是这里最要紧的三行：
 *   `Ok` → `Ok`；`Failed` → `Failed`（可重试）；
 *   `NeedsRoot` → `NeedsRoot`（**用户要去处理**，模型换参数没用）。
 *   把 NeedsRoot 并进 Failed 会让模型反复重试一个它永远做不成的事。
 */
class HostingAdapter(
    private val runner: AppHostingRunner,
) : AppHostingPort {

    override fun host(
        packageName: String,
        bounds: HostBounds,
        screenW: Int,
        screenH: Int,
    ): AppHostingPort.Outcome = runner.host(packageName, bounds, screenW, screenH).toPort()

    override fun release(packageName: String, screenW: Int, screenH: Int): AppHostingPort.Outcome =
        runner.release(packageName, screenW, screenH).toPort()

    override fun tap(x: Int, y: Int): AppHostingPort.Outcome = runner.tap(x, y).toPort()

    override fun type(text: String): AppHostingPort.Outcome = runner.type(text).toPort()

    private fun HostingResult.toPort(): AppHostingPort.Outcome = when (this) {
        is HostingResult.Ok -> AppHostingPort.Ok(detail)
        is HostingResult.Failed -> AppHostingPort.Failed(reason)
        is HostingResult.NeedsRoot -> AppHostingPort.NeedsRoot(reason)
    }
}
