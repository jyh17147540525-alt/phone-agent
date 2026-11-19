package com.pocketagent.rootlogic

/**
 * Linux 工作区的命令构造与参数校验 —— **纯逻辑，一个字节都不发给系统**。
 *
 * ## 为什么校验必须在这里，而不是在工具里
 *
 * 三条出口（`su`、常驻通道、root chroot）最终都汇到这个函数。校验写在工具里的话，
 * 将来多一条通道就多一个绕过点；写在这里是"所有路都得过这道门"。
 *
 * ## 三条硬规则
 *
 * 1. **首词白名单**。命令不是字符串拼出来的，`argv[0]` 必须是我们认识的那个程序。
 *    这挡不住 `python3 -c "os.system('curl …')"` —— 所以还有第 2、3 条。
 * 2. **工作区之外不可写**。以 app 自己的 uid 跑（不 root），系统目录本来就没有写权限；
 *    这里再把"看起来像路径的 token"限制在 `/work` / `/tmp` 下 —— 双重保险。
 * 3. **不带联网工具**。按决策 D3：普通档的白名单里**没有** `curl/wget/pip/npm`。
 *    这不是内核级断网（app 有 INTERNET 权限），只是"我们不给你递刀"；
 *    真隔离要 root 档的 `unshare -n`（见 [launch]）。
 *
 * ⚠️ **M1 不提供 script 模式**。`bash -lc '<任意脚本>'` 会把上面第一、三条变成摆设 ——
 *   脚本里一行 `curl` 就出去了。要做脚本，得先把"脚本解析 + 逐行校验"写出来，那是 M3 之后的事。
 */
sealed interface LinuxCall {

    /** 直接跑一条命令（argv 数组，不经 shell）。 */
    data class Run(val argv: List<String>, val timeoutSec: Int = 30) : LinuxCall

    /** 读回一个文件（只允许 `/work` 内）。 */
    data class Read(val path: String, val maxBytes: Int = DEFAULT_READ_BYTES) : LinuxCall

    companion object {
        const val DEFAULT_READ_BYTES = 64 * 1024
        const val MAX_READ_BYTES = 512 * 1024
    }
}

/** 参数被拒。**这不是执行失败** —— 换个写法也不会成功，除非改掉被拒的那一点。 */
class LinuxRejected(val what: String, val value: String) :
    IllegalArgumentException("$what 不被接受：$value")

object LinuxCommands {

    /** 容器内的唯一可写目录。 */
    const val INNER_WORK = LinuxEnv.INNER_WORK_DIR

    /** 允许作为 `argv[0]` 的程序。**刻意不含联网与包管理工具**（决策 D3/D4）。 */
    val ALLOWED_HEADS: Set<String> = setOf(
        // 解释器
        "python3", "python", "jq",
        // 媒体
        "ffmpeg", "ffprobe",
        // 版本控制（默认只用它看历史/打补丁，联网走不到）
        "git",
        // 文本
        "sed", "awk", "grep", "egrep", "sort", "uniq", "head", "tail", "wc", "cut", "tr", "xargs",
        // 文件与目录
        "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "touch", "stat", "file", "find",
        "ln", "chmod", "du", "df", "readlink", "realpath", "basename", "dirname",
        // 压缩
        "tar", "gzip", "gunzip", "zip", "unzip", "bzip2", "xz",
        // 杂项
        "date", "echo", "printf", "env", "printenv", "id", "uname", "sha256sum", "base64", "iconv",
    )

    /**
     * 明令禁止的首词。
     *
     * 列出来不是为了"拦得住"（真正的闸是白名单），而是为了让错误信息能说人话：
     * 用户看到「不许联网」比「不认识这个命令」更明白自己在被什么挡住。
     */
    val NETWORK_HEADS: Set<String> = setOf(
        "curl", "wget", "pip", "pip3", "npm", "yarn", "pnpm", "node",
        "apt", "apt-get", "apk", "ssh", "scp", "sftp", "rsync", "nc", "ncat", "ftp", "telnet",
    )

    /** 会提升权限或跳出容器的。 */
    val ESCALATION_HEADS: Set<String> = setOf(
        "su", "sudo", "chroot", "proot", "mount", "umount", "dd", "mkfs", "reboot", "shutdown",
        "insmod", "modprobe", "setenforce",
    )

    /** 容器里的标准 PATH（Alpine 的 applet 都在 /bin 与 /usr/bin）。 */
    const val CONTAINER_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    const val MIN_TIMEOUT_SEC = 1
    const val MAX_TIMEOUT_SEC = 60
    const val MAX_ARGV = 32
    const val MAX_TOKEN_CHARS = 4_096
    const val MAX_TOTAL_CHARS = 16_384
    const val MAX_READ_PATH = 1_024

    /** 超时夹取。给 0 或 999 都不是"听用户的"，而是把它夹到可执行的区间。 */
    fun timeoutOf(seconds: Int): Int = seconds.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

    /**
     * 校验一条命令，返回可以交给启动器的 argv。
     *
     * 校验顺序：形状 → 首词 → 路径。**先报形状**：一条空 argv 说"路径越界"会误导。
     */
    fun argv(call: LinuxCall.Run): List<String> {
        val argv = call.argv
        if (argv.isEmpty()) throw LinuxRejected("命令", "空")
        if (argv.size > MAX_ARGV) throw LinuxRejected("参数个数", "${argv.size}（上限 $MAX_ARGV）")

        var total = 0
        for ((index, token) in argv.withIndex()) {
            if (token.isEmpty()) throw LinuxRejected("第 ${index + 1} 个参数", "空串")
            if (token.contains('\u0000')) throw LinuxRejected("第 ${index + 1} 个参数", "含 NUL")
            if (token.length > MAX_TOKEN_CHARS) {
                throw LinuxRejected("第 ${index + 1} 个参数", "过长（${token.length} 字符）")
            }
            total += token.length
        }
        if (total > MAX_TOTAL_CHARS) throw LinuxRejected("参数总量", "$total 字符（上限 $MAX_TOTAL_CHARS）")

        val head = argv.first()
        if (head in ESCALATION_HEADS) throw LinuxRejected("命令", "$head（会提升权限或跳出工作区）")
        if (head in NETWORK_HEADS) throw LinuxRejected("命令", "$head（工作区默认不联网）")
        if (head !in ALLOWED_HEADS) {
            throw LinuxRejected("命令", "$head（不在允许清单里：${ALLOWED_HEADS.size} 个程序）")
        }

        // 只看"像路径"的 token：`python3 -c "print(1)"` 里的代码不是路径，不该被路径规则误伤
        for (token in argv) {
            if (!looksLikePath(token)) continue
            checkedPath(token)
        }
        return argv.toList()
    }

    /** 读回文件：只允许 [INNER_WORK] 内。 */
    fun readPath(path: String): String {
        if (path.isBlank()) throw LinuxRejected("路径", "空")
        if (path.length > MAX_READ_PATH) throw LinuxRejected("路径", "过长（${path.length} 字符）")
        if (path.contains('\u0000')) throw LinuxRejected("路径", "含 NUL")
        checkedPath(path)
        if (!path.startsWith("$INNER_WORK/")) {
            throw LinuxRejected("路径", "$path（只能读 $INNER_WORK 下的文件）")
        }
        return path
    }

    fun readBytes(requested: Int): Int =
        requested.coerceIn(1, LinuxCall.MAX_READ_BYTES)

    /**
     * 按环境类型拼出真正的启动命令。
     *
     * @param networkOff 只有 root 的 `chroot` 能真断网（`unshare -n`）；
     *   proot 没有 network namespace，传 true 也只是**不生效**——所以这里忽略它，
     *   而不是假装做到了（诚实比好看重要）。
     */
    fun launch(
        kind: LinuxKind,
        rootfs: String?,
        workDir: String,
        inner: List<String>,
        networkOff: Boolean = false,
    ): List<String> {
        if (inner.isEmpty()) throw LinuxRejected("命令", "空")
        return when (kind) {
            LinuxKind.ROOT_CHROOT -> {
                val root = rootfs ?: throw LinuxRejected("rootfs", "chroot 需要根目录")
                // ★ 必须经容器里的 /bin/sh 转一手再 exec。
                //   直接 `chroot <root> id` 时，`id` 是拿**宿主**的 PATH 去 exec 的
                //   （chroot 只换根，不换环境），于是找不到程序 → 退出码 127，
                //   模型看到的就是"这工作区里压根没有这个程序"。真机原样翻过。
                // ★ PATH 必须**在容器里重设**。chroot 只换根、不换环境：宿主的 PATH
                //   （/system/bin:…）被原样带进来，而容器里没有这些目录 ⇒ busybox 的 applet
                //   一个都找不到，退出码 127。真机上"id 没跑成"两次都是这个原因。
                val command = "PATH=$CONTAINER_PATH; export PATH; exec " +
                    inner.joinToString(" ") { quote(it) }
                val shell = listOf("/bin/sh", "-c", command)
                if (networkOff) listOf("unshare", "-n", "chroot", root) + shell
                else listOf("chroot", root) + shell
            }

            LinuxKind.EXTERNAL_PROOT, LinuxKind.BUNDLED_PROOT -> {
                val root = rootfs ?: throw LinuxRejected("rootfs", "proot 需要根目录")
                listOf(
                    "proot",
                    "--rootfs", root,
                    "-b", "$workDir:$INNER_WORK",
                    "-w", INNER_WORK,
                ) + inner
            }
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 看起来像路径：绝对路径、相对路径，或含分隔符。 */
    private fun looksLikePath(token: String): Boolean =
        token.startsWith("/") || token.startsWith("./") || token.startsWith("../") ||
            token == ".." || (token.contains('/') && !token.contains('\n'))

    /**
     * POSIX 单引号转义 —— 参数已过白名单与路径校验，这里只是**不让它变成两条命令**。
     *
     * `'` 本身用 `'\''` 断开再拼，是 shell 引号里唯一需要处理的字符。
     */
    internal fun quote(token: String): String = "'" + token.replace("'", "'\\''") + "'"

    /**
     * 路径规则：
     * - 不许出现 `..` 段（`.`, `..` 之外的空段也拒）
     * - 绝对路径只允许 `/work` 与 `/tmp`（其余是系统目录，只读也够不到）
     *
     * ⚠️ 允许 `/tmp` 是因为很多工具要写临时文件；容器内 `/tmp` 不是宿主机的 `/tmp`。
     */
    private fun checkedPath(token: String) {
        val segments = token.split('/')
        if (segments.any { it == ".." }) throw LinuxRejected("路径", "$token（不许用 .. 跳目录）")
        if (!token.startsWith("/")) return
        val allowed = token == INNER_WORK || token.startsWith("$INNER_WORK/") ||
            token == "/tmp" || token.startsWith("/tmp/")
        if (!allowed) throw LinuxRejected("路径", "$token（只能写/读 $INNER_WORK 与 /tmp）")
    }
}
