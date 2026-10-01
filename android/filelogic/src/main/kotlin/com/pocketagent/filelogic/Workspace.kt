package com.pocketagent.filelogic

import kotlinx.serialization.Serializable

/**
 * 工作区 —— **用户授权的一个目录 + 一份使用策略**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它不是新东西，是把已有的 [ScopeRoot] **提升成产品概念**
 * ═══════════════════════════════════════════════════════════════
 *
 * [ScopeRoot] 本来就带着 `id` / `displayName` / `path` / `token` ——
 * 也就是说，"用户授权的一个目录"这件事**早就建模过了**，
 * 只是它一直停留在"判定层的输入"这个位置上，用户看不见它。
 *
 * 工作区补的是**用户侧缺的那一半**：
 * - 它是什么时候加的、上次什么时候打开的（用于排序与"最近使用"）
 * - 它能干什么（[WorkspacePolicy]：能不能写、能不能删、产出放哪）
 *
 * ⚠️ **一个工作区 = 一条 SAF 授权**，刻意不支持"多条授权合成一个工作区"。
 *    理由见 [DenyReason.CROSS_ROOT_MOVE]：跨 root 的移动只能在同一个
 *    tree 内工作，跨 tree 需要"复制 + 删除"而那不是原子的 ——
 *    中途失败会留下两个副本（用户以为文件重复）或零个副本（以为文件丢了）。
 *    一个工作区一条授权，从根上避开这个复杂度。
 *
 * @property root 授权根。**判定与执行都从它出发**，见 [ScopeRoot] 的注释。
 * @property createdAt 创建时刻（毫秒）。由调用方传入，内核不读时钟。
 * @property lastOpenedAt 上次打开时刻。用于"最近使用"排序。
 * @property policy 使用策略。见 [WorkspacePolicy]。
 */
@Serializable
data class Workspace(
    val root: ScopeRoot,
    val createdAt: Long,
    val lastOpenedAt: Long = createdAt,
    val policy: WorkspacePolicy = WorkspacePolicy(),
) {
    /** 稳定标识 —— 直接复用 [ScopeRoot.id]，不另造一个 id 造成两个真相来源。 */
    val id: String get() = root.id

    /** 给用户看的名字。 */
    val displayName: String get() = root.displayName

    /** 展示用的绝对路径。**不要用它去拼实际操作路径**，见 [ScopeRoot.path]。 */
    val path: String get() = root.path

    /** 标记一次打开。返回新实例（不可变），由调用方决定何时落盘。 */
    fun openedAt(now: Long): Workspace = copy(lastOpenedAt = now)

    companion object {
        /**
         * 从一条新的授权创建 [Workspace]。
         *
         * [createdAt] 由调用方传入（内核不读时钟，同 `:personalogic` 的取舍：
         * 离线可钉住、排队的操作不会因为处理时刻不同而乱序）。
         */
        fun fromGrant(root: ScopeRoot, now: Long): Workspace =
            Workspace(root = root, createdAt = now, lastOpenedAt = now)
    }
}

/**
 * 工作区的使用策略。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ [outputSubdir] 是这个类里最重要的一个字段
 * ═══════════════════════════════════════════════════════════════
 *
 * 智能体生成的文件**默认落在工作区下的一个子目录**里，而不是工作区根。
 *
 * 为什么这条比"每次弹确认框"更可靠：`FileOp.WRITE` 已经说明
 * "只在目标已存在时才需要确认（那就是覆盖）"，但那条规则依赖
 * **用户每次都认真读确认框** —— 而人不会。一旦用户在"看见就点确定"
 * 的状态下点过一次，原文件就没了，且不可逆。
 *
 * 把产出隔离到一个默认目录，是在**结构上**消除这类事故，
 * 而不是靠用户的注意力。用户仍然可以把产出移出去（那是一次显式的移动），
 * 但"助理顺手覆盖了我的原文件"这件事不会再发生。
 *
 * ⚠️ 它只有**一段**（不含 `/`）。多段路径需要在 SAF 里逐段创建，
 *    而"逐段创建"在中途失败时会留下半截目录树 —— 那又是一类静默故障。
 *
 * @property allowWrite 是否允许写入。默认开 —— 办公的核心就是产出文件。
 * @property allowDelete 是否允许删除。**默认关**，且即使打开也要每次确认。
 *   删除是这里唯一"用户的原文件彻底没了"的操作，它值得一个单独的开关。
 * @property outputSubdir 产出目录名（工作区内，单段）。
 * @property maxFileBytes 单次写入的字节上限。防的是 OOM：
 *   一个 4GB 的字符串塞进内存会崩，而崩溃现场指不到"写了太大的文件"。
 */
@Serializable
data class WorkspacePolicy(
    val allowWrite: Boolean = true,
    val allowDelete: Boolean = false,
    val outputSubdir: String = DEFAULT_OUTPUT_SUBDIR,
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {
    init {
        require(outputSubdir.isNotBlank()) { "产出目录名不能为空" }
        require('/' !in outputSubdir && '\\' !in outputSubdir) {
            "产出目录名只能是**一段**（不含路径分隔符），当前为「$outputSubdir」"
        }
        require(outputSubdir != "." && outputSubdir != "..") {
            "产出目录名不能是「$outputSubdir」"
        }
        require(outputSubdir.none { it == '\u0000' }) { "产出目录名不能含 NUL" }
        require(maxFileBytes > 0) { "单文件上限必须为正，当前为 $maxFileBytes" }
    }

    companion object {
        const val DEFAULT_OUTPUT_SUBDIR = "输出"

        /**
         * 单次写入上限：4 MiB。
         *
         * 取值依据：本项目要产出的是**办公文档**，不是媒体。
         * 一份几十万行的 CSV 也就几 MB，而 `FileChannel.DEFAULT_MAX_READ_BYTES`
         * （256 KiB）是**读**的上限 —— 写的上限刻意比读大一个量级，
         * 因为"生成一份大表"是合理需求，而"读一份大表进上下文"不是。
         */
        const val DEFAULT_MAX_FILE_BYTES: Long = 4L * 1024 * 1024
    }
}
