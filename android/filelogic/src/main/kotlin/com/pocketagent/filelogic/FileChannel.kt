package com.pocketagent.filelogic

/**
 * 文件通道 —— **"怎么动"这件事的抽象**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么要有这一层（照抄 `ActionExecutor` 的理由，但成因不同）
 * ═══════════════════════════════════════════════════════════════
 *
 * `ActionExecutor` 的多通道是为了**抗政策风险**（Android 17 起高级保护模式
 * 会撤销无障碍权限）。文件通道不是那个问题 —— 它的问题更朴素：
 *
 * 1. **SAF 够不到的地方，Shizuku 够得到。** SAF 拿不到 `/data/data`、
 *    拿不到 `Android/data`，而用户完全可能希望 agent 整理某个应用的导出目录。
 * 2. **SAF 在有些机型上很慢。** 每次 `DocumentFile` 操作都是一次
 *    ContentResolver IPC，列一个 500 个文件的目录可能要好几秒。
 *    Shizuku 直接走 `File` 会快一个数量级。
 * 3. **将来还会有新通道**（如系统「文件」应用的 intent）。
 *
 * ⚠️ 但**判定与通道必须分开**：本模块里 [FileAccessDecider] 负责"能不能动"，
 *    通道只负责"怎么动"。**通道实现不许自己做安全判定** ——
 *    一旦某个通道"顺手放行"，绕过就成立了，而且只在那个通道上成立。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 实现者的三条义务（违反任一条，安全模型就破了）
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 入参的 [ScopeRoot.path] 是展示值，不要用它去拼路径。**
 *    实际操作一律从 [ScopeRoot.token] 出发、按 [relativePath] 逐段下走。
 *    理由：云盘 provider 的目录没有本地路径，`path` 只是一个尽力而为的显示值。
 *    用它拼路径会得到一个"看起来对但指向别处"的结果。
 *
 * **2. 执行前必须重新探测目标状态。**
 *    [FileAccessDecider.decide] 的结论基于调用方传入的 `targetExists` 等快照，
 *    而快照与执行之间有真实的时间差。危险方向是**覆盖**：
 *    探测时文件不存在 → 判定为"新建，无需确认" → 执行时它已经存在 → 静默覆盖。
 *    所以：**执行前再 `stat` 一次，状态与裁决时不一致就放弃，
 *    并重新走一次 `decide`。**
 *
 * **3. 符号链接必须在进入本层之前解析掉。**
 *    [PathNormalizer] 只做字符串运算，不认识 `/sdcard` → `/storage/emulated/0`
 *    这类链接。实现方必须传 `canonicalPath`（已解析）的路径；
 *    **取不到 canonical 路径时应当拒绝这次操作**，而不是退回原路径 ——
 *    退回意味着"解析失败"和"确实就是这条路径"长得一模一样。
 */
interface FileChannel {

    val kind: FileChannelKind

    /** 该通道当前是否可用（授权还在、Shizuku 已激活）。 */
    fun isAvailable(): Boolean

    /** 优先级，数值越大越优先尝试。调度器按降序挑第一个可用的。 */
    fun priority(): Int

    /**
     * 探测一个路径。
     *
     * ⚠️ 它是**裁决的输入**（`targetExists` / `isDirectory` / 大小都来自它），
     *    所以实现必须如实反映"现在"的状态，不能缓存 ——
     *    一个过期的 stat 会让"覆盖"被误判成"新建"。
     */
    suspend fun stat(root: ScopeRoot, relativePath: String): ChannelResult<EntryStat>

    /** 列目录。只列直接子项，不递归。 */
    suspend fun list(root: ScopeRoot, relativePath: String): ChannelResult<List<DirEntry>>

    /**
     * 读文本。
     *
     * ⚠️ [maxBytes] **不是可选优化，是必须遵守的硬上限**。
     *    没有它，agent 读一个 4GB 的视频文件会把整个字符串塞进内存 ——
     *    结果是 OOM 崩溃，而崩溃现场完全指不到"读了太大的文件"。
     *
     * 实现必须在读取**之前**用 `stat` 拿大小并判断，而不是读完再截断。
     */
    suspend fun read(
        root: ScopeRoot,
        relativePath: String,
        maxBytes: Int = DEFAULT_MAX_READ_BYTES,
    ): ChannelResult<FileReadResult>

    /**
     * 写文本。
     *
     * ⚠️ **必须走 `AtomicTextFile`**（在 `:core:common`），不要直接 `writeText`。
     *    直接覆盖时进程可能在任何一刻消失，留下一个"能打开但内容不全"的文件 ——
     *    它看起来完全正常。见那个类的注释。
     */
    suspend fun write(
        root: ScopeRoot,
        relativePath: String,
        content: String,
    ): ChannelResult<Unit>

    /** 删除。目录删除是否递归由实现决定，但**必须在结果里如实报告删了什么**。 */
    suspend fun delete(root: ScopeRoot, relativePath: String): ChannelResult<Unit>

    /**
     * 移动 / 重命名。
     *
     * ⚠️ 只支持**同一 [root] 内**。跨授权根的移动由 [FileAccessDecider] 拒绝
     *    （见 [DenyReason.CROSS_ROOT_MOVE]），通道不该收到这种请求。
     *    如果收到了，说明有人绕过了裁决器 —— 应当直接失败并记录。
     */
    suspend fun move(
        root: ScopeRoot,
        fromRelativePath: String,
        toRelativePath: String,
    ): ChannelResult<Unit>

    companion object {
        /**
         * 单次读取的默认上限（256 KB）。
         *
         * 取值依据：本项目要读的是**办公文档与配置文件**，不是媒体。
         * 一份 256 KB 的 Markdown 约合 13 万字，远超任何"让 agent 读一下"的场景。
         *
         * ⚠️ 上限存在的意义不是省流量，是**避免崩溃**：读进内存的东西
         *    还要经过模型上下文，一个 100 MB 的文本会让 token 计数直接爆掉，
         *    而那件事发生在计费之后。
         */
        const val DEFAULT_MAX_READ_BYTES: Int = 256 * 1024
    }
}

/** 通道种类。 */
enum class FileChannelKind(
    /** 给用户看的名字。要说明**代价**，不能只写技术名词。 */
    val displayName: String,
) {
    /** Storage Access Framework —— 用户在系统文件选择器里授权一个目录 */
    SAF("系统文件选择器（不需要额外权限）"),

    /** 应用自己的私有目录。用户看不到，但零权限零风险 */
    APP_PRIVATE("PocketAgent 自己的存储空间"),

    /** 通过 Shizuku 用 shell 权限操作。**需要用户先装并激活 Shizuku** */
    SHIZUKU("Shizuku（需要你已激活 Shizuku）"),
}

/** 通道调用的结果。 */
sealed interface ChannelResult<out T> {

    data class Ok<T>(val value: T) : ChannelResult<T>

    /**
     * 通道不可用。
     *
     * ⚠️ 与 [Failed] 分开是刻意的：不可用是**配置问题**（用户去授权/激活就能解决），
     *    失败是**运行问题**。两者给用户的下一步完全不同，
     *    合并成一句"操作失败"会让用户不知道该干什么。
     */
    data class Unavailable(val reason: String) : ChannelResult<Nothing>

    data class Failed(val reason: String) : ChannelResult<Nothing>
}

/** 一个路径的状态。 */
data class EntryStat(
    val exists: Boolean,

    val isDirectory: Boolean = false,

    /**
     * 字节数。
     *
     * ⚠️ null ≠ 0。SAF 的 `DocumentFile.length()` 在拿不到时返回 0，
     *    而 0 会让上层判断"这个文件是空的" —— 那是假话。
     *    **实现方拿不到时必须传 null。**
     */
    val sizeBytes: Long? = null,

    /** 最后修改时间（epoch millis）。拿不到时为 null。 */
    val modifiedAt: Long? = null,
)

/** 目录里的一项。 */
data class DirEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long? = null,
    val modifiedAt: Long? = null,
)

/**
 * 读取结果。
 *
 * ⚠️ [TooLarge] 与 [NotUtf8] 是**正常业务状态**，不是错误 ——
 *    所以它们放在值里，而不是 `ChannelResult.Failed`。
 *    理由：这两个状态都需要**特定的界面处理**
 *    （"这个文件太大，要我只看前几行吗？" / "这个文件不是文本，打不开"），
 *    而 `Failed` 只会得到一句通用的错误提示。
 */
sealed interface FileReadResult {

    data class Text(val content: String) : FileReadResult

    /** 超过 [FileChannel.DEFAULT_MAX_READ_BYTES] */
    data class TooLarge(val sizeBytes: Long, val limitBytes: Int) : FileReadResult

    /**
     * 不是合法的 UTF-8 文本。
     *
     * ⚠️ **不要"尽力解码"**：把非法字节替换成 `�` 会得到一份看起来正常、
     *    实际已被损坏的文本，而 agent 会基于它做判断。
     *    本项目对这类取舍的立场一贯是：**宁可如实失败，也不给一份错的**。
     */
    data class NotUtf8(val reason: String) : FileReadResult
}
