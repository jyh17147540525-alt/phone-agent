package com.pocketagent.assistant

import com.pocketagent.mcp.LinuxAvailability
import com.pocketagent.mcp.LinuxOpsPort
import com.pocketagent.mcp.LinuxReadResult
import com.pocketagent.mcp.LinuxRunResult
import com.pocketagent.rootlogic.LinuxKind
import com.pocketagent.rootlogic.LinuxRejected
import com.pocketagent.rootlogic.RootAction
import com.pocketagent.rootlogic.RootActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * B2：root 档的 chroot 容器。
 *
 * ## 它比内置引擎多什么
 *
 * 能装包（`apt/apk`）、能跑任意已装程序、能 `unshare -n` **真断网**。
 * 代价是：必须有 root，而且必须先有一份根文件系统 —— 那个下载器属于 M5，这里不做。
 *
 * ## 没有根文件系统时说什么
 *
 * 不说"不可用"，说清**缺哪一步**：root 有了、目录还没有。否则用户会以为 root 白拿了。
 *
 * ## `/work` 的边界（必须诚实）
 *
 * 容器里的 `/work` 是我们 bind 进去的那个目录；当前实现直接 `chroot`，
 * 没有做 bind mount —— 所以容器内 `/work` 只有根文件系统自己带的时候才存在。
 * 需要处理手机里的文件时，走内置引擎（同进程、读写真实路径），不要假装 chroot 能看见。
 */
class RootLinuxOps(
    private val actions: RootActions,
    private val workDir: File,
    private val rootfsDir: File,
    private val rootAvailable: () -> Boolean,
    /**
     * 跑命令前确认 `/work` 挂上了。
     *
     * ★ 挂载点**重启后消失**，而 rootfs 不会。只装一次就完事的后果是：
     *   装完当天好好的，第二天开机后容器里 `/work` 变成空目录，
     *   用户以为"我上次写的文件没了"。
     */
    private val ensureWorkMount: (suspend () -> Unit)? = null,
) : LinuxOpsPort {

    override suspend fun availability(): LinuxAvailability {
        if (!rootAvailable()) {
            return LinuxAvailability(
                ready = false,
                // ══════════════════════════════════════════════════════
                //  ★ 2026-11-18 真机：原来是「这台机器没有 root（或没给本应用
                //    授权）」。可这台机器是 KernelSU root，日志也打了
                //    「root 通道：已发现常驻通道」—— 用不了的真正原因是
                //    effectivePrivilegeMode() 还是普通档（第 0 档，选普通时
                //    绝不自动升级）。把档位说成"设备没有 root"，用户会去查
                //    手机是不是丢了 root，而不是去切档。
                // ══════════════════════════════════════════════════════
                explain = "root 容器这一档现在用不上：要么权限档位还是普通（第 0 档），" +
                    "要么这台机器没给 root 授权。它比内置引擎强得多——能装软件包、能真断网；" +
                    "要开它：设置 → 运行模式，把那一行切成 Root 模式（需要 Magisk/KernelSU 授权）。" +
                    "不切也能用内置引擎或 Termux。",
            )
        }
        if (!looksLikeRootfs()) {
            return LinuxAvailability(
                ready = false,
                explain = "root 有了，但还没有根文件系统（${rootfsDir.absolutePath}）。" +
                    "去 设置 → LINUX 工作区 → 「root 容器」点一下安装（约 3.6 MB，一次性），" +
                    "装完这一档就能跑任意程序；不装也能先用内置引擎或 Termux。",
            )
        }
        return LinuxAvailability(
            ready = true,
            explain = "root 容器可用（能装包、能 unshare -n 断网）。注意：容器里的 /work 只在根文件系统自带时才有。",
        )
    }

    override suspend fun run(argv: List<String>, timeoutSec: Int): LinuxRunResult {
        val startedAt = System.currentTimeMillis()
        // 挂载失败不阻断命令：没有 /work 也能跑 `id`，只是写文件会落到容器里。
        runCatching { ensureWorkMount?.invoke() }
        val result = try {
            actions.run(
                RootAction.LinuxShell(
                    kind = LinuxKind.ROOT_CHROOT,
                    rootfs = rootfsDir.absolutePath,
                    workDir = workDir.absolutePath,
                    inner = argv,
                    // 容器以 root 跑 ⇒ 它在 /work 里建的文件属主是 root，
                    // 应用之后读不回来（linux_read 与工作区同步都会 EACCES）。
                    // 每条命令跑完把属主改回应用自己。
                    workOwner = "${android.os.Process.myUid()}:${android.os.Process.myUid()}",
                ),
            )
        } catch (e: LinuxRejected) {
            throw IllegalArgumentException(e.message ?: "参数不被接受")
        }
        return LinuxRunResult(
            exitCode = result.code,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    /** 读 `/work` 与"哪个后端跑的命令"无关：应用自己读自己的目录。 */
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

    /** 根文件系统"看起来像"的最低判据：有 `bin/`（chroot 进去至少能执行东西）。 */
    private fun looksLikeRootfs(): Boolean {
        val bin = File(rootfsDir, "bin")
        return rootfsDir.isDirectory && bin.isDirectory
    }
}
