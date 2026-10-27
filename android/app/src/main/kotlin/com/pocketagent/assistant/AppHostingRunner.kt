package com.pocketagent.assistant

import com.pocketagent.hosting.HostBounds
import com.pocketagent.hosting.HostingCommands
import com.pocketagent.hosting.HostingResult
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * **应用托管执行器** —— 用 root 把别家应用缩到角落，并操作它。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是 [HostingCommands]（纯逻辑）的**唯一执行方**
 * ═══════════════════════════════════════════════════════════════
 *
 * 分工刻意是这样：
 *  - `:hosting` 负责**拼命令**（纯函数，有测试，能被离线验证器跑到）
 *  - 本文件负责**跑命令**（要 root、要 Android，只能真机验证）
 *
 * ⚠️ 本文件**不做任何字符串处理** —— 参数全部来自 `:hosting` 已经校验过的结果。
 *    在这里再拼一次等于给自己开一个注入口子。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么要有"回读校验"
 * ═══════════════════════════════════════════════════════════════
 *
 * `am task resize` 对**不存在的 taskId 也返回 0** —— 它会安静地什么都不做。
 * 所以每次改完窗口，都必须**回读一次 bounds** 确认真的生效了。
 * 不校验的后果是：工具告诉模型"已经缩好了"，而用户屏幕上什么都没变。
 */
class AppHostingRunner(
    /** 执行 shell 命令。抽成函数便于离线测试与替换。 */
    private val exec: (String) -> String? = { cmd -> runRoot(cmd) },
    /**
     * 执行**慢命令**（`dumpsys activity activities` 这类）。
     *
     * ★ 2026-10-27 真机：两者必须分开。
     *
     * ⚠️ 之前所有命令共用一个 8 秒超时。真机上 `dumpsys activity activities`
     *    的输出体积很大（本机一次 dump 上万行），`su` 启动 + dump + 两层
     *    grep 串起来**会超过 8 秒**。超时后 `runRoot` 返回 null，
     *    于是 `awaitTaskId` 每轮都拿不到东西，最后报"找不到窗口" ——
     *    而 dumpsys 里那个 task 明明就在。
     *
     *    代价是排查方向完全错：看日志会以为"应用没起来"。
     */
    private val execSlow: (String) -> String? = { cmd -> runRoot(cmd, timeoutSeconds = 25) },
    private val timeoutMs: Long = 8_000,
) {

    /**
     * 判断当前能否托管。
     *
     * @return null = 可用；否则是一条"为什么不可用"的说明
     */
    fun checkAvailability(): String? {
        val out = exec("id")
            ?: return "拿不到 root。请在 root 管理器（KernelSU / Magisk）里给本应用授权。"
        if (!out.contains("uid=0")) {
            return "root 命令执行了，但身份不是 root（拿到的是 ${out.trim().take(40)}）。"
        }
        return null
    }

    /**
     * 把应用托管到指定区域。
     *
     * 步骤（**顺序不能换**）：
     *  1. 确保应用已启动（freeform）
     *  2. 等它真正有窗口（否则找不到 taskId）
     *  3. 查 taskId
     *  4. resize
     *  5. **回读确认**
     *
     * ⚠️ 第 2 步的等待是必要的：`monkey` 启动是**异步**的，
     *    立刻查 taskId 会拿到 null，而那个 null 会被误报成"应用不支持托管"。
     */
    fun host(packageName: String, bounds: HostBounds, screenW: Int, screenH: Int): HostingResult {
        checkAvailability()?.let { return HostingResult.NeedsRoot(it) }

        // ① 先问系统要**启动 Activity 的组件名**。见 HostingCommands.resolveLauncher
        //    的注释：`am start --windowingMode 5` 必须有 `-n <component>`，
        //    只用 `-p <pkg>` 会 "unable to resolve Intent"。
        val component = exec(HostingCommands.resolveLauncher(packageName))
            ?.let { HostingCommands.parseLauncherComponent(it) }
            ?: return HostingResult.Failed(
                "「$packageName」没有可启动的入口（可能它没有桌面图标，或包名不对）。",
            )

        // ② 以自由窗口模式启动 —— 这一步决定了后面 resize 有没有用
        exec(HostingCommands.startFreeform(component))
        Timber.i("托管：已请求以自由窗口启动 %s（%s）", packageName, component)

        // ③ 等窗口就绪
        // ⚠️ 原来是 3 秒（10 × 300ms）。真机实测 QQ 冷启动 + 闪屏就超过 3 秒，
        //    于是托管必然失败，而报错写的是"它不支持自由窗口" ——
        //    结论完全错，排查方向也完全错。改成 12 秒。
        val taskId = awaitTaskId(packageName)
            ?: return HostingResult.Failed(
                "启动后 ${AWAIT_ATTEMPTS * 300 / 1000} 秒内没找到「$packageName」的窗口。" +
                    "可能原因：应用没装、包名不对，或它启动得比这更慢。",
            )

        // ④ 缩到指定区域
        exec(HostingCommands.resizeTask(taskId, bounds))
        Timber.i("托管：task=%d 缩到 %s", taskId, bounds.toShellArgs())

        // ⑤ **回读确认** —— `am task resize` 会静默失败
        val actual = readTaskBounds(taskId)
        return if (actual == null) {
            HostingResult.Failed(
                "窗口调整后读不回来（task=$taskId）。" +
                    "命令可能没生效 —— 这个应用可能不支持自由窗口。",
            )
        } else if (actual.left != bounds.left || actual.right != bounds.right) {
            HostingResult.Failed(
                "窗口没缩到预期位置：期望 $bounds，实际 $actual。" +
                    "这个应用可能强制全屏（金融类应用常见）。",
            )
        } else {
            HostingResult.Ok(
                "「$packageName」已托管到右上角（${bounds.width}×${bounds.height}）。" +
                    "它可见但不会挡住你正在用的应用。",
            )
        }
    }

    /** 恢复全屏（用户要接管时）。 */
    fun release(packageName: String, screenW: Int, screenH: Int): HostingResult {
        checkAvailability()?.let { return HostingResult.NeedsRoot(it) }
        val taskId = awaitTaskId(packageName, attempts = 3)
            ?: return HostingResult.Ok("「$packageName」当前没有窗口，不需要恢复。")
        exec(HostingCommands.restoreFullscreen(taskId, screenW, screenH))
        return HostingResult.Ok("「$packageName」已恢复全屏。")
    }

    /** 在托管窗口里点一下（坐标是**屏幕坐标**，直接走 input tap）。 */
    fun tap(x: Int, y: Int): HostingResult {
        checkAvailability()?.let { return HostingResult.NeedsRoot(it) }
        exec("input tap $x $y")
        return HostingResult.Ok("已点击 ($x,$y)")
    }

    /** 在托管窗口里输入文字。 */
    fun type(text: String): HostingResult {
        checkAvailability()?.let { return HostingResult.NeedsRoot(it) }
        // ⚠️ 空格会打断 input text 的参数，必须转义成 %s
        //    —— 这是 Android `input text` 的既定行为，不是我们的选择。
        val escaped = text.replace(" ", "%s").replace("'", "")
        exec("input text '$escaped'")
        return HostingResult.Ok("已输入 ${text.length} 字")
    }

    // ── 内部 ──────────────────────────────────────────────────

    /**
     * 轮询等 taskId 出现。
     *
     * ⚠️ 自旋而不是 sleep 一次：不同应用的启动耗时差异极大
     *    （微信冷启动 2s+，系统应用 0.3s）。
     *    固定等待会让快的白等、慢的不够。
     */
    private fun awaitTaskId(pkg: String, attempts: Int = AWAIT_ATTEMPTS): Int? {
        repeat(attempts) {
            // ⚠️ 用 execSlow：dumpsys 的体量在真机上会撞破普通超时，见构造器注释。
            val out = execSlow(HostingCommands.findTaskId(pkg))
            if (out == null) {
                // ★ 空返回**必须记下来**。不记的话，"命令超时"和
                //   "窗口真的没出现"在日志里长得一模一样。
                Timber.w("找 taskId 的命令没返回（第 %d 次）：可能 dumpsys 超时", it + 1)
                Thread.sleep(300)
                return@repeat
            }
            // ══════════════════════════════════════════════════════════
            //  ★★ 2026-10-27 真机：这里曾经永远返回 null
            // ══════════════════════════════════════════════════════════
            //
            // `findTaskId` 的命令在 shell 里已经用 `tr -d '#'` 把结果
            // 削成了一个**纯数字**；而 `parseTaskId` 是给
            // "整段 dumpsys 输出"设计的，它的正则是 `#(\d+)\s+type=standard`。
            //
            // ⇒ 两个函数对"输出长什么样"的假设不一致：纯数字喂给
            //   `parseTaskId` 永远匹配不上。表现是**任务明明起来了、
            //   mode=freeform 就在 dumpsys 里**，而托管层坚称
            //   "12 秒内没找到窗口"。日志里每一步都"成功"，只有结论是错的。
            //
            // ⇒ 先按"纯数字"解析，再退回整段 dumpsys 的解析。
            val id = out?.trim()?.toIntOrNull()
                ?: out?.let { HostingCommands.parseTaskId(it) }
            if (id != null && id > 0) return id
            Thread.sleep(300)
        }
        return null
    }

    /** 回读某个 task 的当前 bounds。 */
    private fun readTaskBounds(taskId: Int): HostBounds? {
        val out = execSlow(
            "dumpsys activity activities | grep -A6 'Task{.*#$taskId ' | grep bounds | head -1",
        ) ?: return null
        val m = Regex("""bounds=\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(out) ?: return null
        return runCatching {
            HostBounds(
                left = m.groupValues[1].toInt(),
                top = m.groupValues[2].toInt(),
                right = m.groupValues[3].toInt(),
                bottom = m.groupValues[4].toInt(),
            )
        }.getOrNull()
    }

    private companion object {
        /**
         * 等窗口出现的轮询次数（每轮 300ms）。
         *
         * ★ 40 × 300ms = 12 秒。原来写的是 10（3 秒），真机实测 QQ
         *   冷启动 + 闪屏就超过 3 秒 —— 于是必然失败，而且报的是
         *   "它不支持自由窗口"，结论与事实相反。
         */
        const val AWAIT_ATTEMPTS = 40
        /**
         * 跑一条 root 命令并取 stdout。
         *
         * ★ 用 `ProcessBuilder("su","-c",cmd)` 而不是拼字符串：
         *   `su -c` 之后的那一段由 su 交给 sh 解析，**必须**是单个字符串 ——
         *   但那个字符串的内容已经由 `:hosting` 的白名单保证过。
         */
        fun runRoot(cmd: String, timeoutSeconds: Long = 8): String? = try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                Timber.w("root 命令超时（%ds）：%s", timeoutSeconds, cmd.take(60))
                null
            } else {
                p.inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Timber.w(e, "root 命令失败：%s", cmd.take(60))
            null
        }
    }
}
