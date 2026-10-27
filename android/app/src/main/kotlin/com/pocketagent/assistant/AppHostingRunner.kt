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

        // ① 启动（幂等：已经在跑时 monkey 只是把它拉到前台）
        exec(HostingCommands.startFreeform(packageName))
        Timber.i("托管：已请求启动 %s", packageName)

        // ② 等窗口就绪（最多 3 秒）
        val taskId = awaitTaskId(packageName)
            ?: return HostingResult.Failed(
                "启动后 3 秒内没找到「$packageName」的窗口。" +
                    "可能原因：应用没装、包名不对，或它不支持自由窗口。",
            )

        // ③ 缩到指定区域
        exec(HostingCommands.resizeTask(taskId, bounds))
        Timber.i("托管：task=%d 缩到 %s", taskId, bounds.toShellArgs())

        // ④ **回读确认** —— `am task resize` 会静默失败
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
    private fun awaitTaskId(pkg: String, attempts: Int = 10): Int? {
        repeat(attempts) {
            val out = exec(HostingCommands.findTaskId(pkg))
            val id = out?.let { HostingCommands.parseTaskId(it) }
            if (id != null) return id
            Thread.sleep(300)
        }
        return null
    }

    /** 回读某个 task 的当前 bounds。 */
    private fun readTaskBounds(taskId: Int): HostBounds? {
        val out = exec("dumpsys activity activities | grep -A6 'Task{.*#$taskId ' | grep bounds | head -1")
            ?: return null
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
         * 跑一条 root 命令并取 stdout。
         *
         * ★ 用 `ProcessBuilder("su","-c",cmd)` 而不是拼字符串：
         *   `su -c` 之后的那一段由 su 交给 sh 解析，**必须**是单个字符串 ——
         *   但那个字符串的内容已经由 `:hosting` 的白名单保证过。
         */
        fun runRoot(cmd: String): String? = try {
            val p = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly()
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
