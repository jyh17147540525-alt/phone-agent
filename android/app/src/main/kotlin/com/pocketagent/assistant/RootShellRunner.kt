package com.pocketagent.assistant

import com.pocketagent.capabilitylogic.ChannelResult
import com.pocketagent.capabilitylogic.ChannelUnavailableReason
import com.pocketagent.capabilitylogic.ShellRunner
import com.pocketagent.rootlogic.PrivilegeMode
import com.pocketagent.rootlogic.RootProbeResult
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * **root 通道** —— 用 `su` 以 uid 0 执行命令。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它和 [PendingShellRunner] 是两条不同的路，不要合并
 * ═══════════════════════════════════════════════════════════════
 *
 * | | shell（Shizuku） | root（本文件） |
 * |---|---|---|
 * | 身份 | uid 2000 (shell) | uid 0 (root) |
 * | 改 SELinux | ✗ | ✓ |
 * | 写 `setprop` 受限项 | ✗ | ✓ |
 * | 读写任意路径 | 部分 | ✓ |
 * | 用户要做什么 | 装并激活 Shizuku | 手机已 root + 在 Magisk 里放行 |
 *
 * ⚠️ 两者的**前置条件完全不同**，所以探测也必须分开 ——
 *    把"Shizuku 没激活"和"Magisk 没放行"说成同一句"通道不可用"，
 *    用户会去折腾错误的那一个（本项目最忌讳的误导形态）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 安全约束（照 [ShellRunner] 的契约）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **只收 argv，禁止自行拼接 shell 字符串** —— 一旦拼字符串，
 *    参数里的 `;` `&&` `$()` 就会变成注入点。而这条通道是 **root**，
 *    注入进去的命令是 root 权限。
 * 2. **超时必须有** —— 以 root 跑的命令挂住时，普通应用连杀都杀不掉它
 *    （没有权限），会一直占着进程。所以超时后**强杀进程**。
 * 3. **输出要截断** —— `cat /dev/urandom` 或 `dumpsys` 能吐几十 MB，
 *    而结果要进模型上下文（花用户的钱）。超过上限直接截断并**标注**。
 */
class RootShellRunner(
    /** 怎么执行 argv —— 抽成函数便于离线测试与替换。 */
    private val exec: (List<String>) -> Process = { argv ->
        // ★ 用 ProcessBuilder 的数组形式，**不经过 shell** ——
        //   `su -c` 之后的那一段由 su 自己解析，所以绝不能把用户输入拼进去。
        ProcessBuilder(argv).redirectErrorStream(false).start()
    },
    private val timeoutMs: Long = 15_000,
    private val maxOutputChars: Int = 8_000,
) : ShellRunner {

    override suspend fun run(argv: List<String>): ChannelResult {
        if (argv.isEmpty()) {
            return ChannelResult.Failed(reason = "命令为空")
        }

        // ★ 先探一次 root 是否真的可用。
        //   不探的话，`su` 被拒绝时 Process 会正常启动但立刻返回非零，
        //   错误信息只有一句 "exit code 1" —— 用户完全不知道该去 Magisk 放行。
        when (val probe = RootProbe.probe()) {
            is RootProbeResult.Available -> Unit
            else -> return ChannelResult.Unavailable(
                reason = ChannelUnavailableReason.PERMISSION_DENIED,
                detail = probe.explainForChannel(),
            )
        }

        // ── 拼成 su 的调用形式 ────────────────────────────────
        // ⚠️ `su -c` 后面那一段是**单个字符串**，由 su 交给 sh 解析。
        //    所以我们把 argv 逐个**单引号转义**后拼接 —— 这是唯一安全的方式。
        //    直接用空格 join 会让参数里的空格/分号变成另一条命令。
        val command = argv.joinToString(" ") { quoteForSh(it) }
        val full = listOf("su", "-c", command)

        val process = try {
            exec(full)
        } catch (e: Exception) {
            // su 二进制不存在 ⇒ 真的没 root
            return ChannelResult.Unavailable(
                reason = ChannelUnavailableReason.PORT_NOT_CONFIGURED,
                detail = "这台手机没有 su（普通模式）。原始错误：${e.message ?: e::class.simpleName}",
            )
        }

        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                // ★ 超时必须强杀：root 进程挂住时，普通应用连信号都发不进去，
                //   会一直占着 fd 与进程槽。
                process.destroyForcibly()
                return ChannelResult.Failed(
                    reason = "命令超时（${timeoutMs}ms）已被强制结束。" +
                        "以 root 跑的命令挂住时会一直占着资源，所以不会无限等。",
                )
            }

            val out = process.inputStream.bufferedReader().use { it.readCapped(maxOutputChars) }
            val err = process.errorStream.bufferedReader().use { it.readCapped(maxOutputChars) }
            val code = process.exitValue()

            Timber.i("root 命令退出码=$code 输出=${out.length}字 错误=${err.length}字")

            if (code == 0) {
                ChannelResult.Succeeded(payload = out)
            } else {
                ChannelResult.Failed(
                    reason = "命令返回 $code" + if (err.isNotBlank()) "：${err.trim()}" else "",
                )
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            ChannelResult.Failed(reason = "执行异常：${e.message ?: e::class.simpleName}")
        }
    }

    /**
     * 把单个参数转成 **sh 单引号安全形式**。
     *
     * ⚠️ 这是防注入的**唯一**屏障，改之前先读完：
     *    单引号内的字符对 sh 来说全是字面量，除了单引号自己。
     *    所以把 `'` 换成 `'\''`（结束引号 + 转义引号 + 重开引号）即可闭合。
     *
     * 例：`a; rm -rf /` → `'a; rm -rf /'`（整段被当成一个参数，不会执行 rm）
     */
    private fun quoteForSh(arg: String): String =
        "'" + arg.replace("'", "'\\''") + "'"

    /**
     * 读输出但**不超过上限**。
     *
     * ★ 截断必须标注：不标注的话模型会以为输出就这样，从而得出错误结论
     *   （"只有这些文件"），而真相是后面还有几 MB。
     */
    private fun BufferedReader.readCapped(limit: Int): String {
        val sb = StringBuilder()
        val buf = CharArray(4096)
        var truncated = false
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (sb.length + n > limit) {
                sb.append(buf, 0, maxOf(0, limit - sb.length))
                truncated = true
                break
            }
            sb.append(buf, 0, n)
        }
        return if (truncated) {
            sb.toString() + "\n…（输出超过 $limit 字，已截断）"
        } else {
            sb.toString()
        }
    }
}

/**
 * root 可用性探测 —— 一次真实执行，不靠猜。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么要单独探测，而不是"跑命令失败再说"
 * ═══════════════════════════════════════════════════════════════
 *
 * 「跑一条命令失败」有很多种原因（命令本身错、参数错、目标不存在），
 * 而"root 不可用"只是其中一种。混在一起说的后果是：
 * 用户看到"命令失败"，去查命令；真正的问题是 Magisk 没放行。
 */
object RootProbe {

    /** 探测超时要比普通命令短：这只是问一句"你是谁"。 */
    private const val PROBE_TIMEOUT_MS = 6_000L

    /**
     * 探一次。
     *
     * 判据（按顺序，**每一步的结论不同**）：
     * 1. `su` 进程起不来      → [RootProbeResult.NotRooted]（真的没有）
     * 2. 起来但超时           → [RootProbeResult.Failed]（授权对话框可能正等着用户点）
     * 3. 退出码非 0           → [RootProbeResult.Denied]（有 su 但被拒绝）
     * 4. 输出里没有 `uid=0`   → [RootProbeResult.Failed]（跑通了但不是 root）
     * 5. 其余                 → [RootProbeResult.Available]
     */
    fun probe(
        exec: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    ): RootProbeResult {
        val process = try {
            exec(listOf("su", "-c", "id"))
        } catch (e: Exception) {
            return RootProbeResult.NotRooted
        }

        return try {
            if (!process.waitFor(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                // ★ 最常见的原因是 Magisk 弹了授权框、用户还没点。
                //   说成"没 root"会让用户去重新 root 一遍。
                return RootProbeResult.Failed(
                    "su 在 ${PROBE_TIMEOUT_MS}ms 内没有返回 —— " +
                        "可能是 root 管理器弹了授权框正等你点「允许」",
                )
            }

            val out = process.inputStream.bufferedReader().use { br ->
                val sb = StringBuilder()
                val buf = CharArray(1024)
                while (true) {
                    val n = br.read(buf)
                    if (n < 0) break
                    if (sb.length > 4096) break
                    sb.append(buf, 0, n)
                }
                sb.toString()
            }
            val code = process.exitValue()

            when {
                code != 0 -> RootProbeResult.Denied
                out.contains("uid=0") -> RootProbeResult.Available(out.trim())
                // 退出码 0 但没有 uid=0：极少见（su 被替换成了别的东西）
                else -> RootProbeResult.Failed("su 返回成功但身份不是 root：${out.trim().take(120)}")
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            RootProbeResult.Failed("探测异常：${e.message ?: e::class.simpleName}")
        }
    }
}

/**
 * 探测结论 → 通道层的"不可用说明"。
 *
 * ★ 单独抽出来是因为**这句话决定了用户下一步去哪**：
 *   - 没 root     → 接受普通模式（不要折腾）
 *   - 被拒绝      → 去 Magisk 放行
 *   - 执行失败    → 看具体原因
 *   三者的动作完全不同，而通道层只有一个 `detail` 字段能承载。
 */
fun RootProbeResult.explainForChannel(): String = when (this) {
    is RootProbeResult.Available -> "root 可用（${identity.take(80)}）"
    RootProbeResult.NotRooted ->
        "这台手机没有 root，root 通道不可用。普通模式下的能力不受影响。"
    RootProbeResult.Denied ->
        "手机有 root，但本应用**没有被授权**。请到 Magisk（或你的 root 管理器）" +
            "→ 超级用户 → 找到 PocketAgent → 允许。⚠️ 不是去重新 root。"
    is RootProbeResult.Failed -> "root 通道执行失败：$reason"
}

/**
 * 当前模式 + 探测结论 → 实际生效的模式。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"选了 root"不等于"在 root 模式"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户可能在 Magisk 更新后丢了授权、也可能换了手机。
 * 如果他选的是 root 而实际拿不到，**能力必须降级**，
 * 而且界面要说清"你选的是 root，但现在拿不到，因为 X"。
 *
 * ⚠️ 反过来（选了普通、但其实有 root）**不能自动升级** ——
 *    用户明确选了普通模式，我们不该悄悄用 root 权限做事。
 *    那是最严重的越权。
 */
fun effectiveMode(selected: PrivilegeMode, probe: RootProbeResult): PrivilegeMode =
    when {
        selected == PrivilegeMode.ROOT && probe is RootProbeResult.Available -> PrivilegeMode.ROOT
        else -> PrivilegeMode.NON_ROOT
    }
