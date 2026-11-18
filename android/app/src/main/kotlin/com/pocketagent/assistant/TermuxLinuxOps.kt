package com.pocketagent.assistant

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.pocketagent.mcp.LinuxAvailability
import com.pocketagent.mcp.LinuxOpsPort
import com.pocketagent.mcp.LinuxReadResult
import com.pocketagent.mcp.LinuxRunResult
import com.pocketagent.rootlogic.LinuxRejected
import com.pocketagent.rootlogic.TermuxCommands
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import timber.log.Timber

/**
 * B1：借 Termux 的手跑命令（`com.termux.RUN_COMMAND`）。
 *
 * ## 为什么不是"绕过沙箱"
 *
 * Termux 自己把 `targetSdk` 钉在 28，所以它能执行自己目录里的二进制；我们通过公开的
 * `RUN_COMMAND` 接口**请它跑** —— 用的是它已经获得的合法性，不是我们绕开限制。
 * 前提三条（缺一条就不通）：装了 Termux、给了 `com.termux.permission.RUN_COMMAND`、
 * Termux 侧开了 `allow-external-apps`。
 *
 * ## 文件边界（必须说清）
 *
 * Termux 是**另一个 uid**，读不到我们的 `/work`（应用私有目录）。
 * 所以这个后端只适合"不需要我们的文件"的命令（查版本、装包、跑 Termux 自己 home 里的东西）；
 * 需要 `/work` 的任务走内置引擎或 root。`explain` 里会写明，不假装支持。
 */
class TermuxLinuxOps(
    private val context: Context,
    private val workDir: File,
) : LinuxOpsPort {

    override suspend fun availability(): LinuxAvailability {
        val installed = isInstalled()
        val granted = hasPermission()
        if (!installed) {
            return LinuxAvailability(
                ready = false,
                explain = "没装 Termux。想跑完整 Linux 工具链：从 F-Droid 装 Termux（应用商店那份已停更），" +
                    "再回来打开「允许外部程序执行命令」。不装也行 —— 内置引擎能跑 Python。",
            )
        }
        if (!granted) {
            return LinuxAvailability(
                ready = false,
                explain = "装好了 Termux，但没给它「执行命令」的权限。" +
                    "去 设置 → 应用 → PocketAgent → 权限 里允许「执行 Termux 命令」。",
            )
        }

        // 探一次活：权限对了但 allow-external-apps 没开时，命令会静默不回来
        val probe = runBounded(listOf("echo", PROBE_TOKEN), timeoutSec = 5)
        return when {
            probe == null -> LinuxAvailability(
                ready = false,
                explain = "Termux 没回应。多半是 `.termux/termux.properties` 里没开 allow-external-apps：" +
                    "在 Termux 里执行一次：echo allow-external-apps=true >> ~/.termux/termux.properties 再试。",
            )
            probe.stdout.contains(PROBE_TOKEN) -> LinuxAvailability(
                ready = true,
                explain = "Termux 通道可用（能装包、能跑已装程序；读不到本应用的 /work）。",
            )
            else -> LinuxAvailability(false, "Termux 有回应但输出不对：${probe.describe().take(160)}")
        }
    }

    override suspend fun run(argv: List<String>, timeoutSec: Int): LinuxRunResult {
        val request = try {
            TermuxCommands.build(argv)
        } catch (e: LinuxRejected) {
            // 参数问题：交给工具层当 Failed 处理，别让它变成"通道坏了"
            throw IllegalArgumentException(e.message ?: "参数不被接受")
        }
        val result = runBounded(argv, timeoutSec)
            ?: return LinuxRunResult(
                exitCode = TIMEOUT_CODE,
                stdout = "",
                stderr = "Termux 在 ${timeoutSec}s 内没有回话。可能是 allow-external-apps 没开，或它被系统冻结了。",
                durationMs = timeoutSec * 1_000L,
            )
        return LinuxRunResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = result.durationMs,
        )
    }

    /**
     * `/work` 是应用私有目录 —— 我们自己读得到，与"哪个后端跑的"无关。
     * 所以这里直接读文件，而不是绕一圈让 Termux 读（它读不到）。
     */
    override suspend fun read(path: String, maxBytes: Int): LinuxReadResult = withContext(Dispatchers.IO) {
        if (!path.startsWith("/work/")) throw IllegalArgumentException("只能读 /work 下：$path")
        val relative = path.removePrefix("/work/")
        if (relative.isBlank() || relative.split('/').any { it == ".." }) {
            throw IllegalArgumentException("路径越界：$path")
        }
        val file = File(workDir, relative)
        if (!file.isFile) throw java.io.FileNotFoundException("没有这个文件：$path")

        val bytes = file.readBytes()
        val truncated = bytes.size > maxBytes
        LinuxReadResult(if (truncated) bytes.copyOf(maxBytes) else bytes, truncated)
    }

    // ── 执行与回收结果 ──────────────────────────────────────────────────────

    private suspend fun runBounded(argv: List<String>, timeoutSec: Int): TermuxRun? {
        val request = runCatching { TermuxCommands.build(argv) }.getOrNull() ?: return null
        val token = nextToken()
        val deferred = CompletableDeferred<TermuxRun>()
        pending[token] = deferred

        val intent = Intent(TermuxCommands.ACTION).apply {
            setClassName(TermuxCommands.PACKAGE, RUN_COMMAND_SERVICE)
            putExtra(TermuxCommands.EXTRA_PATH, request.path)
            putExtra(TermuxCommands.EXTRA_ARGUMENTS, request.arguments.toTypedArray())
            putExtra(TermuxCommands.EXTRA_WORKDIR, request.workdir)
            putExtra(TermuxCommands.EXTRA_BACKGROUND, request.background)
            putExtra(EXTRA_PENDING_INTENT, resultIntent(token))
        }

        val startedAt = System.currentTimeMillis()
        val started = runCatching {
            // ⚠️ RUN_COMMAND 走 startService（Termux 侧是前台服务）；Android 8+ 从后台起服务会被拒，
            //    所以这要求在任务进行时应用处于"用户可见/前台服务"状态。
            context.startService(intent)
        }.isSuccess

        if (!started) {
            pending.remove(token)
            return null
        }

        val finished = withTimeoutOrNull(timeoutSec * 1_000L) { deferred.await() }
        pending.remove(token)
        return finished?.copy(durationMs = System.currentTimeMillis() - startedAt)
    }

    private fun resultIntent(token: Int): PendingIntent {
        val intent = Intent(ACTION_RESULT).setPackage(context.packageName)
            .putExtra(EXTRA_TOKEN, token)
        return PendingIntent.getBroadcast(
            context,
            token,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TermuxCommands.PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, TermuxCommands.PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun nextToken(): Int = sequence.incrementAndGet()

    /** 结果广播的落点。由 [install] 注册一次，进程内共用。 */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val token = intent.getIntExtra(EXTRA_TOKEN, -1)
            val deferred = pending.remove(token) ?: return
            val bundle: Bundle? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(RESULT_BUNDLE, Bundle::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(RESULT_BUNDLE)
            }
            if (bundle == null) {
                deferred.complete(TermuxRun(1, "", "Termux 没有带回结果（bundle 为空）"))
                return
            }
            deferred.complete(
                TermuxRun(
                    exitCode = bundle.getInt(RESULT_EXIT_CODE, 1),
                    stdout = bundle.getString(RESULT_STDOUT).orEmpty(),
                    stderr = bundle.getString(RESULT_STDERR).orEmpty(),
                ),
            )
        }
    }

    companion object {
        const val ACTION_RESULT = "com.pocketagent.TERMUX_RESULT"
        const val EXTRA_TOKEN = "pa_token"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

        /** Termux 侧把结果放在这个 bundle 里（键名是它的公开常量，值同样是公开的）。 */
        const val RESULT_BUNDLE = "result"
        const val RESULT_STDOUT = "stdout"
        const val RESULT_STDERR = "stderr"
        const val RESULT_EXIT_CODE = "exitCode"

        const val PROBE_TOKEN = "pocketagent-ok"
        const val TIMEOUT_CODE = 124

        private val pending = ConcurrentHashMap<Int, CompletableDeferred<TermuxRun>>()
        private val sequence = AtomicInteger(1)

        /** 注册结果接收器。调用方应在 `Application.onCreate` 里调一次。 */
        fun install(context: Context) {
            val filter = IntentFilter(ACTION_RESULT)
            ContextCompat.registerReceiver(
                context.applicationContext,
                ResultReceiver(),
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            Timber.i("Termux 结果接收器已注册")
        }
    }
}

/** 一次 Termux 执行的结果。 */
data class TermuxRun(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long = 0,
) {
    fun describe(): String = "exitCode=$exitCode stdout=${stdout.take(120)} stderr=${stderr.take(120)}"
}
