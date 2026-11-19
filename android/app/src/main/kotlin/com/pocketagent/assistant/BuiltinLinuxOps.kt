package com.pocketagent.assistant

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.pocketagent.mcp.LinuxAvailability
import com.pocketagent.mcp.LinuxOpsPort
import com.pocketagent.mcp.LinuxReadResult
import com.pocketagent.mcp.LinuxRunResult
import com.pocketagent.rootlogic.BuiltinInvocation
import com.pocketagent.rootlogic.BuiltinPython
import com.pocketagent.rootlogic.LinuxEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * B0「内置引擎」—— 所有用户都能用的那一条。
 *
 * ## 它为什么能"不需要 root、不需要 Termux"
 *
 * Chaquopy 把 CPython 以 JNI 嵌进进程，代码是**进程内 exec 语义**，不 exec 任何文件，
 * 因此不受 Android 10 起"不能执行应用可写目录里的文件"的限制。
 *
 * ## 它做不到什么（必须说在前面）
 *
 * - 只有 Python 标准库：装不了 `numpy/pandas`（那是 pip，要联网且有体积代价）
 * - 不是真 Linux：没有 shell、没有 `/bin`、没有 `chmod`、不能起子进程
 * - **断不了网**：进程内解释器和 app 同权限，网络策略由系统决定
 *
 * ## 超时为什么是"弃疗"而不是"杀掉"
 *
 * 进程内执行的 Python 拿不到"可杀"的句柄：`withTimeoutOrNull` 只能让调用方放弃等待，
 * 那段代码仍在跑。所以超时后**把后端标记为不可用**，后续调用直接拒 ——
 * 连开两个跑飞的解释器比一次超时危险得多。要恢复只能重启应用，
 * 这条会写进给用户的说明里。
 */
class BuiltinLinuxOps(
    private val context: Context,
    /** 用户开关（D5：默认关）。 */
    private val enabled: () -> Boolean,
    /** 容器 `/work` 对应的真实目录。 */
    private val workDir: File,
) : LinuxOpsPort {

    private val poisoned = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    private fun ensureStarted() {
        if (started.get()) return
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        started.set(true)
    }

    override suspend fun availability(): LinuxAvailability {
        if (!enabled()) {
            return LinuxAvailability(
                ready = false,
                explain = "内置 Linux 引擎默认关闭。它能在手机里直接跑 Python（标准库），" +
                    "但不装软件包、断不了网，也看不到相册和别的应用；" +
                    "它和应用同进程，所以能读到应用自己的数据（含聊天记录）。" +
                    // ══════════════════════════════════════════════════════
                    //  ★ 2026-11-18 真机：这里原本写「当前版本还没有开关界面
                    //    （下一步接进设置页），暂时只能由开发者打开」——
                    //    而开关早在 设置 → Linux 工作区 里，用户按这句话去找
                    //    只会以为"这版没做完"。
                    // ══════════════════════════════════════════════════════
                    "要去打开它：设置 → Linux 工作区 → 「内置 Linux 引擎」开关。",
            )
        }
        if (poisoned.get()) {
            return LinuxAvailability(
                ready = false,
                explain = "上一次命令超时，内置引擎已停用（进程内的 Python 无法中途杀掉）。" +
                    "重启应用即恢复；需要跑长任务请改用 Termux 或 root 通道。",
            )
        }
        return runCatching {
            withContext(Dispatchers.IO) { ensureStarted() }
            LinuxAvailability(
                ready = true,
                explain = "内置引擎可用：Python ${versionOrUnknown()}（进程内，只有标准库）。" +
                    "只能跑 python3 -c，/work 之外写不了，装不了软件包，也断不了网。",
            )
        }.getOrElse { error ->
            Timber.w(error, "内置 Python 起不来")
            LinuxAvailability(false, "内置引擎起不来（${error.javaClass.simpleName}）。这通常是安装包缺少对应 ABI 的 Python 运行时。")
        }
    }

    private fun versionOrUnknown(): String = runCatching {
        Python.getInstance().getModule("pa_builtin").callAttr("version").toString()
    }.getOrDefault("未知")

    override suspend fun run(argv: List<String>, timeoutSec: Int): LinuxRunResult {
        val startedAt = System.currentTimeMillis()
        when (val plan = BuiltinPython.plan(argv)) {
            is BuiltinInvocation.Reject -> return rejected(plan.reason)
            BuiltinInvocation.Version -> {
                val version = withContext(Dispatchers.IO) {
                    runCatching {
                        ensureStarted()
                        versionOrUnknown()
                    }.getOrElse { "未知（$it）" }
                }
                return LinuxRunResult(0, "Python $version\n", "", System.currentTimeMillis() - startedAt)
            }
            is BuiltinInvocation.Code -> {
                val encoded = Base64.getEncoder().encodeToString(plan.code.toByteArray())
                val timeoutMs = timeoutSec.coerceIn(1, 60) * 1_000L
                val raw = withTimeoutOrNull(timeoutMs) {
                    withContext(Dispatchers.IO) {
                        ensureStarted()
                        Python.getInstance().getModule("pa_builtin").callAttr("run_b64", encoded).toString()
                    }
                }
                if (raw == null) {
                    poisoned.set(true)
                    Timber.w("内置 Python 超时（%d ms），后端已停用", timeoutMs)
                    return LinuxRunResult(
                        exitCode = TIMEOUT_CODE,
                        stdout = "",
                        stderr = "超过 ${timeoutSec} 秒没有结束。进程内的 Python 无法中途杀掉，" +
                            "这个后端已停用，重启应用才能恢复；长任务请改用 Termux 或 root 通道。",
                        durationMs = System.currentTimeMillis() - startedAt,
                    )
                }
                val parsed = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
                    ?: return LinuxRunResult(1, "", "内置引擎返回了无法解析的结果。", System.currentTimeMillis() - startedAt)
                return LinuxRunResult(
                    exitCode = parsed["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1,
                    stdout = parsed["out"]?.jsonPrimitive?.content.orEmpty(),
                    stderr = parsed["err"]?.jsonPrimitive?.content.orEmpty(),
                    durationMs = System.currentTimeMillis() - startedAt,
                )
            }
        }
    }

    override suspend fun read(path: String, maxBytes: Int): LinuxReadResult {
        val real = BuiltinPython.realPath(path, workDir.absolutePath)
        if (!real.startsWith(workDir.absolutePath)) {
            return LinuxReadResult(ByteArray(0), false)
        }
        return withContext(Dispatchers.IO) {
            val file = File(real)
            if (!file.isFile) return@withContext LinuxReadResult(ByteArray(0), false)
            val length = file.length()
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(minOf(length, maxBytes.toLong()).toInt().coerceAtLeast(0))
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == buffer.size) buffer else buffer.copyOf(read)
            }
            LinuxReadResult(bytes, truncated = length > bytes.size)
        }
    }

    private fun rejected(reason: String) =
        LinuxRunResult(exitCode = NOT_EXECUTABLE_CODE, stdout = "", stderr = reason, durationMs = 0)

    /** 确保工作区目录存在（第一次调用时建）。 */
    fun prepareWorkDir(): Boolean = runCatching { workDir.mkdirs() || workDir.isDirectory }.getOrDefault(false)

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** 126 = 找到了但不可执行（shell 惯例），用来说"引擎不支持这条命令"。 */
        const val NOT_EXECUTABLE_CODE = 126

        /** 124 = 超时（与 `timeout(1)` 一致）。 */
        const val TIMEOUT_CODE = 124
    }
}
