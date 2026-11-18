package com.pocketagent.rootlogic

/**
 * **执行后端**的选择 —— 「这台机器上，谁来真正干活」。
 *
 * ## 为什么需要这一层
 *
 * `LinuxEnv` 回答的是"有没有一个真 Linux"，但用户要的是"能不能把事办成"。
 * 两者在**没有 root、也没有 Termux** 的机器上是分离的：真 Linux 没有，
 * 但 Python、媒体转换、文本处理这些能力仍然可以给 —— 只要不 exec 任何文件。
 *
 * 所以执行被拆成四种**后端**，按"能给多少"排序：
 *
 * | 后端 | 谁有 | 能不能装包 | 能不能真断网 |
 * |---|---|---|---|
 * | [ROOT_CHROOT] | root 用户 | 能 | 能（`unshare -n`） |
 * | [TERMUX] | 装了 Termux 的人 | 能 | 不能（proot 没有 netns） |
 * | [BUILTIN] | **所有人** | 不能 | 不能 |
 * | [NONE] | 都没有时 | — | — |
 *
 * ## 一条硬约束（决定排序的根据）
 *
 * Android 10 起，`targetSdk ≥ 29` 的 app **不能执行自己可写目录里的文件**。
 * 本项目 `targetSdk = 36`，所以"把 rootfs 解出来再跑 `/bin/sh`"对普通用户天然不通；
 * 而 [BUILTIN] 走的是"进程内调用"（Python 解释器是 JNI 嵌进来的，媒体转换是系统 API），
 * **一个字节都不 exec**，因此不受这条限制。
 *
 * ⚠️ [BUILTIN] 也不是"什么都能干"：它装不了软件包、断不了网、跑不了任意 ELF。
 *   这一点必须在界面上说清 —— 让用户以为"有 Linux"，然后发现 `apt` 用不了，
 *   比一开始就说明白更伤信任。
 */
enum class ExecBackend(val label: String, val realLinux: Boolean) {
    /** root 通道里的 chroot（或 proot）。 */
    ROOT_CHROOT("root 通道", realLinux = true),

    /**
     * 借 Termux 的 Linux。
     *
     * ★ 走 `com.termux.RUN_COMMAND`（Termux 官方给外部 app 的接口）。
     *   Termux 自己钉在 targetSdk 28，所以在它那边 exec 是合法的 —— 这是"借它的合法性"，
     *   不是绕过沙箱。代价是用户得装 Termux 并打开 `allow-external-apps`。
     */
    TERMUX("Termux 通道", realLinux = true),

    /** 进程内引擎：Python（JNI 内嵌）+ 系统媒体 API + 纯 Kotlin 文本/压缩。 */
    BUILTIN("内置引擎", realLinux = false),

    /** 一个都没有。 */
    NONE("不可用", realLinux = false),
}

/** 后端能提供的能力。用于界面展示与提示词，避免模型承诺做不到的事。 */
enum class LinuxCapability(val label: String) {
    PYTHON("Python 脚本"),
    MEDIA("音视频转换"),
    TEXT("文本 / JSON / 正则"),
    ARCHIVE("压缩解压"),
    PACKAGES("安装软件包"),
    SHELL("任意命令行工具"),
    NETWORK_ISOLATION("断网隔离"),
}

/** 探测到的事实。全部由调用方（`:app`）填，本层不碰 Android。 */
data class BackendFacts(
    /** root 通道可用（常驻进程能跑 chroot）。 */
    val rootAvailable: Boolean = false,
    /** 装了 Termux 且允许外部调用。 */
    val termuxUsable: Boolean = false,
    /** 内置 Python 可用（Chaquopy 已初始化且版本达标）。 */
    val builtinPython: Boolean = false,
    /** 内置媒体能力可用（Media3 Transformer 能处理目标格式）。 */
    val builtinMedia: Boolean = false,
)

/** 选择结果。 */
data class BackendChoice(
    val backend: ExecBackend,
    val capabilities: Set<LinuxCapability>,
    /** 给用户/模型看的一句话。 */
    val explain: String,
    /**
     * 是不是**降级**了：本来有更强的后端，但它的前提不满足。
     *
     * ★ 单独标出来，是因为"降级"必须显式：用户以为在用真 Linux、实际在内置引擎上，
     *   会得到"为什么 apt 装不了"的困惑。
     */
    val degraded: Boolean = false,
)

object LinuxBackends {

    /**
     * 按优先级选后端。
     *
     * 顺序固定为 root → Termux → 内置。**不按"哪个快"排**：root 的 chroot 也快，
     * 但它还多给"能装包、能断网"这两样，所以先排它。
     */
    fun choose(facts: BackendFacts): BackendChoice = when {
        facts.rootAvailable -> BackendChoice(
            backend = ExecBackend.ROOT_CHROOT,
            capabilities = setOf(
                LinuxCapability.PYTHON,
                LinuxCapability.MEDIA,
                LinuxCapability.TEXT,
                LinuxCapability.ARCHIVE,
                LinuxCapability.PACKAGES,
                LinuxCapability.SHELL,
                LinuxCapability.NETWORK_ISOLATION,
            ),
            explain = "用 root 通道跑 Linux：能装软件包、能真断网，也是最快的一条路。",
        )

        facts.termuxUsable -> BackendChoice(
            backend = ExecBackend.TERMUX,
            capabilities = setOf(
                LinuxCapability.PYTHON,
                LinuxCapability.MEDIA,
                LinuxCapability.TEXT,
                LinuxCapability.ARCHIVE,
                LinuxCapability.PACKAGES,
                LinuxCapability.SHELL,
            ),
            explain = "没有 root，但装了 Termux：借它那边的 Linux 干活（能装包；断网隔离做不到）。",
        )

        facts.builtinPython || facts.builtinMedia -> BackendChoice(
            backend = ExecBackend.BUILTIN,
            capabilities = buildSet {
                add(LinuxCapability.TEXT)
                add(LinuxCapability.ARCHIVE)
                if (facts.builtinPython) add(LinuxCapability.PYTHON)
                if (facts.builtinMedia) add(LinuxCapability.MEDIA)
            },
            explain = buildString {
                append("用内置引擎干活：")
                append(
                    listOfNotNull(
                        "Python 脚本".takeIf { facts.builtinPython },
                        "音视频转换".takeIf { facts.builtinMedia },
                        "文本与压缩处理",
                    ).joinToString("、"),
                )
                append("。装不了软件包、也断不了网 —— 那是 root 或 Termux 才有的。")
            },
            degraded = true,
        )

        else -> BackendChoice(
            backend = ExecBackend.NONE,
            capabilities = emptySet(),
            explain = "这台机器上还没有可用的执行后端：装 Termux（推荐，零体积）、" +
                "给应用 root、或等内置引擎初始化完成。",
        )
    }

    /** 给提示词用的三行摘要（短、可判定，别写成长段落）。 */
    fun promptLine(choice: BackendChoice): String = buildString {
        append("执行后端：").append(choice.backend.label)
        if (choice.capabilities.isNotEmpty()) {
            append("（可做：").append(choice.capabilities.joinToString("、") { it.label }).append("）")
        }
        if (choice.degraded) append("。注意：这是降级后的能力，装包与断网不可用")
    }
}
