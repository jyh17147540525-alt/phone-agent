package com.pocketagent.rootlogic

/**
 * 内置 Python 后端（Chaquopy，进程内解释器）的**纯逻辑**部分。
 *
 * ## 它为什么必须存在
 *
 * `targetSdk ≥ 29` 的 app 在 Android 10+ 上**不能执行自己可写目录里的文件**，
 * 所以"解一个 rootfs 再跑"对普通用户天然不通。Chaquopy 是进程内调用（JNI），
 * 一个字节都不 exec —— 这是唯一一条"所有用户都能用 Python"的路。
 *
 * ## 它做什么、不做什么
 *
 * 只回答两个问题：**这条 argv 能不能在进程内执行**、**容器路径对应哪个真实路径**。
 * 真正的执行在 `:app`（需要 Android 上下文与 Chaquopy 运行时），本类保持零依赖可测。
 *
 * ⚠️ 这里**只认 `python3 -c <code>` 与 `python3 --version`**：
 *    脚本文件、`-m 模块`、`-i` 交互都不支持 —— 它们要么要文件系统语义，
 *    要么要一个真正的进程。假装支持会让用户以为内置引擎"和真的一样"。
 */
sealed interface BuiltinInvocation {

    /** 跑一段代码。 */
    data class Code(val code: String) : BuiltinInvocation

    /** 问版本。 */
    data object Version : BuiltinInvocation

    /** 不接受，附带人话原因。 */
    data class Reject(val reason: String) : BuiltinInvocation
}

object BuiltinPython {

    /** 程序名。两个都认：很多提示词里写的是 `python`。 */
    val HEADS = setOf("python3", "python")

    /**
     * 把一条 argv 翻成"要做什么"。
     *
     * 校验顺序：程序名 → 子命令 → 代码长度。先报程序名：用户写了 `ls` 时说
     * "不支持 -m" 会让他以为换个子命令就行。
     */
    fun plan(argv: List<String>): BuiltinInvocation {
        if (argv.isEmpty()) return BuiltinInvocation.Reject("命令是空的。")
        val head = argv.first()
        if (head !in HEADS) {
            return BuiltinInvocation.Reject(
                "内置引擎只支持 python3（收到的是「$head」）。要跑别的程序，需要 Termux 或 root 通道。",
            )
        }
        if (argv.size >= 2 && (argv[1] == "--version" || argv[1] == "-V")) {
            return BuiltinInvocation.Version
        }
        if (argv.size < 3 || argv[1] != "-c") {
            return BuiltinInvocation.Reject(
                "内置引擎只支持 python3 -c \"代码\"（不支持脚本文件、-m 模块、交互模式）。",
            )
        }
        val code = argv[2]
        if (code.isBlank()) return BuiltinInvocation.Reject("要执行的代码是空的。")
        if (code.length > MAX_CODE_CHARS) {
            return BuiltinInvocation.Reject("代码太长（${code.length} 字符，上限 $MAX_CODE_CHARS）。")
        }
        return BuiltinInvocation.Code(code)
    }

    /**
     * 容器路径 → 真实路径。
     *
     * 只映射 `/work`（以及它下面的东西）；其余路径原样返回，由调用方决定要不要拒 ——
     * 这一层不该假装自己懂整个文件系统。
     */
    fun realPath(containerPath: String, realWorkDir: String): String = when {
        containerPath == LinuxEnv.INNER_WORK_DIR -> realWorkDir
        containerPath.startsWith("${LinuxEnv.INNER_WORK_DIR}/") ->
            realWorkDir.trimEnd('/') + containerPath.removePrefix(LinuxEnv.INNER_WORK_DIR)
        else -> containerPath
    }

    /** 真实路径 → 容器路径（读回结果时给模型看的那个）。 */
    fun containerPath(realPath: String, realWorkDir: String): String {
        val base = realWorkDir.trimEnd('/')
        return if (realPath == base) {
            LinuxEnv.INNER_WORK_DIR
        } else if (realPath.startsWith("$base/")) {
            LinuxEnv.INNER_WORK_DIR + realPath.removePrefix(base)
        } else {
            realPath
        }
    }

    /** 单段代码的上限。够写一次性脚本，不至于把整本文件塞进提示词。 */
    const val MAX_CODE_CHARS = 20_000
}
