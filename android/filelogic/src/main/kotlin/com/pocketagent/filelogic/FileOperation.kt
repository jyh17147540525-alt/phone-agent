package com.pocketagent.filelogic

/**
 * 文件操作的类型与裁决结果。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"读取"与"删除"要分成不同的裁决结果
 * ═══════════════════════════════════════════════════════════════
 *
 * 电脑端的 agent 工具里，"读文件"和"删文件"长得几乎一样 ——
 * 都是调一个函数、拿一个结果。但这里刻意把它们分开了：
 *
 * - **读取不需要确认**。确认得太频繁，用户会养成"看见就点确定"的习惯，
 *   而那个习惯会让真正的危险确认也失效。**把确认留给不可逆的操作。**
 * - **删除、覆盖、移动必须确认**。这三件事的共同点是"用户的原文件没了"。
 *   它们与"读一下"的区别不是技术上的，是**能不能撤销**。
 *
 * 这条线画在哪里，决定了用户会不会认真读确认框。
 */
enum class FileOp(
    val id: String,

    /** 给用户看的名字。界面上的按钮用它，不要用枚举名。 */
    val userLabel: String,
) {
    READ("read", "读取"),
    LIST("list", "查看目录"),

    /**
     * 写入。
     *
     * ⚠️ **只在目标已存在时才需要确认**（那就是覆盖）。
     *    新建文件不打断用户 —— 否则"帮我建个笔记"这种最常见的请求
     *    会变成两次交互，而第二次交互里用户什么信息都没得到。
     */
    WRITE("write", "写入"),

    /** 删除。**无条件确认**，没有例外。 */
    DELETE("delete", "删除"),

    /**
     * 移动 / 重命名。
     *
     * ⚠️ 重命名**也走这里**，因为它在文件系统层面与移动是同一个操作
     *    （`rename(2)`）。把它单独拆出来会让人以为"重命名比较安全"——
     *    实际上对用户来说，`报告.md` 变成 `报告-旧.md` 与文件消失
     *    一样让人困惑，而且同样可能覆盖掉一个已存在的目标。
     */
    MOVE("move", "移动或重命名"),
    ;

    /** 是否属于"用户的原文件可能消失"这一类 */
    val isDestructive: Boolean
        get() = this == DELETE || this == MOVE
}

/**
 * 一次待裁决的文件操作请求。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ [targetExists] / [isDirectory] / [descendantCount] 由 Android 层探测后传入
 * ═══════════════════════════════════════════════════════════════
 *
 * 本模块**不碰文件系统**（那是它能在离线跑测试的唯一原因）。
 * 所以"这个文件在不在""是不是目录""里面有多少东西"必须由调用方探测。
 *
 * 这带来一个**真实的竞态**（TOCTOU）：探测与真正执行之间，目标可能被创建或删除。
 * 具体的危险方向是**覆盖**：探测时不存在 → 判定为"新建、无需确认" →
 * 执行时它已经存在 → 用户的原文件被静默覆盖。
 *
 * ⚠️ 因此调用方的义务是：**在执行前重新探测一次，若状态与裁决时不一致，
 *    必须放弃执行并重新走一次 [FileAccessDecider.decide]**。
 *    这条无法在本模块内保证 —— 它需要原子性，而原子性在通道实现里。
 */
data class FileOperationRequest(
    val op: FileOp,

    /** 原始路径。**未归一化**，归一化是裁决器的第一步。 */
    val path: String,

    /** 目标是否已存在。WRITE 时决定要不要确认（覆盖）。 */
    val targetExists: Boolean = false,

    /** 目标是否是目录。DELETE 时影响文案与风险等级。 */
    val isDirectory: Boolean = false,

    /**
     * 目录内的子项数量。仅用于生成确认文案
     * （"其中还有 12 个文件"比"要删除文件夹"有用得多）。
     *
     * ⚠️ **null ≠ 0**。null 是"探测不到"，0 是"确实是空的"。
     *    把前者显示成"要删除空文件夹"是在说假话 —— 用户会以为里面没东西，
     *    点下确认才发现丢了 200 个文件。同 [existingSizeBytes] 的立场。
     */
    val descendantCount: Int? = null,

    /**
     * 已存在文件的大小（字节）。仅用于确认文案。
     *
     * ⚠️ 拿不到时传 null —— **不要传 0**。0 会被格式化成「0 B」，
     *    于是确认框会告诉用户"这个文件是空的"，而那可能是假的。
     *    **宁可不说，也不要说错。**（同 `HostPermissions` 的立场）
     */
    val existingSizeBytes: Long? = null,

    /** 已存在文件的修改时间，**已由 Android 层格式化好**。仅用于确认文案。 */
    val existingModifiedAtText: String? = null,

    /** MOVE 的目标路径。其他操作为 null。 */
    val destinationPath: String? = null,

    /**
     * 用户是否已经就**这一次**操作点过确认。
     *
     * ⚠️ 它**只在裁决的最后一步被消费**，不能跳过归一化、范围与黑名单检查。
     *    见 [FileAccessDecider.decide] 的说明。
     */
    val confirmedByUser: Boolean = false,
)

/**
 * 已解析的操作目标 —— 通道实现真正需要的东西。
 *
 * 把 [root] 与 [relativePath] 一起给出去，是因为 SAF 的通道只能从 tree 根
 * 逐段往下走，拿不到"绝对路径 → 文件句柄"的捷径。让调用方自己算相对路径
 * 会导致每个通道实现里都有一份（可能算错的）换算。
 */
data class ResolvedTarget(
    val op: FileOp,

    /** 归一化后的绝对路径。用于展示、审计。 */
    val normalizedPath: String,

    /** 相对授权根的路径。空串表示"就是这个目录本身"。 */
    val relativePath: String,

    /** 这次操作落在哪条授权上 —— 通道要用它的 [ScopeRoot.token]。 */
    val root: ScopeRoot,

    val destinationNormalizedPath: String? = null,
    val destinationRelativePath: String? = null,
)

/** 裁决结论 */
sealed interface FileAccessDecision {

    /**
     * 放行。
     *
     * [wasConfirmed] 为 true 表示这次放行是"用户看过并点了确认"之后的结果，
     * 而**不是**因为它本身无害。审计日志必须能区分这两者 ——
     * 否则"用户确认过多少次删除"这个问题就答不上来。
     */
    data class Allowed(
        val target: ResolvedTarget,
        val wasConfirmed: Boolean,
    ) : FileAccessDecision

    /**
     * 需要用户显式确认。
     *
     * [userMessage] 必须能**直接展示**，且要让用户理解"为什么问"以及
     * "确认之后会发生什么"。按项目纪律，含糊的确认等于没有确认。
     */
    data class RequireConfirmation(
        val target: ResolvedTarget,
        val reason: ConfirmReason,
        val userMessage: String,
        /** 超时视为**拒绝**（不是默认同意）。 */
        val timeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
    ) : FileAccessDecision

    /**
     * 拒绝。
     *
     * ⚠️ [userMessage] 里要说明"这是安全设计，不是故障"。
     *    被拒绝时用户最自然的反应是"软件坏了"，而一句
     *    "我不会碰密钥文件"能把那个误解挡掉。
     */
    data class Denied(
        val reason: DenyReason,
        val userMessage: String,
    ) : FileAccessDecision

    companion object {
        const val DEFAULT_CONFIRM_TIMEOUT_MS: Long = 30_000
    }
}

/** 为什么需要确认。审计日志按它分类统计。 */
enum class ConfirmReason {
    /** 目标已存在，写入会替换掉原有内容 */
    OVERWRITE_EXISTING,

    /** 删除一个文件 */
    DELETE_FILE,

    /** 删除一个目录（可能含子项） */
    DELETE_DIRECTORY,

    /** 移动或重命名 */
    MOVE_OR_RENAME,
}

/** 拒绝的原因。 */
enum class DenyReason {
    /** 用户还没授权任何目录 —— 这不是错误，是"还没配置" */
    NO_SCOPE,

    /** 路径本身非法（空、相对路径、含 NUL、`..` 越根） */
    MALFORMED_PATH,

    /** 不在任何已授权的目录之内 */
    OUT_OF_SCOPE,

    /** 在授权范围内，但命中了位置黑名单 */
    DENIED_LOCATION,

    /** MOVE 没有给出目标路径 */
    MISSING_DESTINATION,

    /** MOVE 的目标不在授权范围内 */
    DESTINATION_OUT_OF_SCOPE,

    /** MOVE 的目标命中了位置黑名单 */
    DESTINATION_DENIED_LOCATION,

    /**
     * MOVE 的目标在**另一条**授权下。
     *
     * SAF 的 `DocumentsContract.moveDocument` 只能在同一个 tree 内工作，
     * 跨 tree 需要"复制 + 删除"，而那不是原子的 —— 中途失败会留下
     * 两个副本（用户会以为文件重复了）或零个副本（用户会以为文件丢了）。
     * 所以在纯逻辑层就拒掉，并告诉用户分两步做。
     */
    CROSS_ROOT_MOVE,
}
