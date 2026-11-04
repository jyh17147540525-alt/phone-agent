package com.pocketagent.rootlogic

import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * 常驻通道的**薄壳入口**（`app_process` 起的就是这个 `main`）。
 *
 * ```
 * su -c "CLASSPATH=/data/local/tmp/pocketagent-daemon.jar \\
 *        app_process /system/bin com.pocketagent.rootlogic.DaemonMainKt"
 * ```
 *
 * 三件事，按顺序：绑 `127.0.0.1:0` → 把 `端口 token` 写进发现文件 →
 * 服务循环（每个连接一次握手，之后交给 [DaemonLoop]）。
 *
 * ★ 为什么薄壳这么薄：
 * 所有能离线测的逻辑（协议、校验、处理表、服务循环、传输）都在别的文件里，
 * 这个文件只负责"起进程 + 写文件 + 别崩"。薄壳越薄，真机上出问题的面越小 ——
 * 而真机是唯一不能反复重来的地方。
 *
 * ★ 崩了要能自己重启：这里**不**做 `while(true)` 兜异常重启。原因：
 * 服务循环内部已经把单条连接的异常都吃了（单连接出错不影响别的连接），
 * 走到 `main` 退出说明是**端口绑不上**这种系统性故障 —— 那种情况重启
 * 一万次也是同一个结果，不如退出让上层的看护脚本（或用户）知道。
 */
object DaemonMain {

    /** 随机 token：16 字节 → 32 个十六进制字符。 */
    fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 写发现文件。先写临时文件再 `renameTo` —— 半截内容被客户端读到，
     * 会变成"端口对、token 截断"这种最难查的失效。
     */
    fun writeDiscovery(path: String, port: Int, token: String): Boolean = try {
        val target = File(path)
        val tmp = File("$path.tmp")
        tmp.writeText("$port $token\n")
        // 客户端是 untrusted_app，必须可读。
        runCatching {
            tmp.setReadable(true, false)
            tmp.setWritable(false, false)
        }
        tmp.renameTo(target) || run {
            // 某些实现下 renameTo 跨挂载点会失败：退化成直接写，再删临时文件。
            target.writeText("$port $token\n")
            runCatching { tmp.delete() }
            true
        }
    } catch (e: IOException) {
        false
    }

    /**
     * 起服务并写发现文件，返回服务端（调用方负责 `stop()`）。
     *
     * 抽成这个函数是为了**离线可测**：测试里可以用假 runner 起一套完整栈，
     * 再真的从客户端连进来 —— 那正是真机上第一步会做的事。
     */
    fun start(
        runner: LocalRunner,
        discoveryPath: String,
        token: String = newToken(),
    ): Started {
        val server = DaemonSocketServer(DaemonLoop(DaemonHandlers.standard(runner)), token)
        val port = server.start()
        val written = writeDiscovery(discoveryPath, port, token)
        return Started(server, port, token, written)
    }

    data class Started(
        val server: DaemonSocketServer,
        val port: Int,
        val token: String,
        val discoveryWritten: Boolean,
    )

    /**
     * `app_process` 调的入口。
     *
     * 参数：`[发现文件路径]`（可选）。token 在这里生成，不通过命令行传 ——
     * 命令行走 `ps` 是**全局可见**的，token 出现在那儿等于没有 token。
     */
    @JvmStatic
    fun main(args: Array<String>) {
        val discovery = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: DaemonEndpoint.DEFAULT_PATH
        val started = start(ProcessRunner(), discovery)

        // stdout 会被 `adb shell` 接走；这一行是排障时唯一能直接看到的信号。
        println(
            "pocketagent-daemon: listening 127.0.0.1:${started.port} " +
                "discovery=${if (started.discoveryWritten) "ok" else "FAILED"}",
        )
        if (!started.discoveryWritten) {
            // 写不了发现文件 → 客户端永远发现不了这个通道，等于没起来。
            System.err.println("pocketagent-daemon: 无法写发现文件 $discovery")
            started.server.stop()
            return
        }

        // 阻塞到进程被杀。用 wait 而不是死循环 sleep：被 kill 时能立刻退出。
        while (true) {
            Thread.sleep(60_000)
        }
    }
}

/**
 * 真的起进程的 [LocalRunner]：参数数组直传，不过 shell。
 *
 * 与 `:app` 里 [SuShellPort] 的区别只有一个 —— **不套 `su`**（守护进程本身是 root）。
 * 其余规则一致：先读流再 `waitFor`（避免大输出把管道堵死）、超时就强杀。
 */
class ProcessRunner(private val defaultTimeoutMs: Int = 15_000) : LocalRunner {

    override fun run(argv: List<String>, timeoutMs: Int): ShellResult {
        val effective = if (timeoutMs > 0) timeoutMs else defaultTimeoutMs
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(false).start()
        } catch (e: IOException) {
            return ShellResult(SPAWN_FAILED, "", "起不来：${e.message ?: e.javaClass.simpleName}")
        }

        return try {
            // ★ 先读流再 waitFor：`screencap`/`dumpsys` 输出可能几十 KB，
            //   而管道缓冲区只有 64KB —— 先等进程退出会死锁。
            val stdout = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val stderr = process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!process.waitFor(effective.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                ShellResult(TIMEOUT, stdout, "超时（${effective}ms）")
            } else {
                ShellResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            ShellResult(SPAWN_FAILED, "", "被中断")
        } catch (e: IOException) {
            process.destroyForcibly()
            ShellResult(SPAWN_FAILED, "", "读输出失败：${e.message}")
        }
    }

    companion object {
        /** 与 `:app` 的 `SuShellPort` 用同一组伪退出码，上层判断逻辑不用分两套。 */
        const val TIMEOUT = 124
        const val SPAWN_FAILED = 125
    }
}
