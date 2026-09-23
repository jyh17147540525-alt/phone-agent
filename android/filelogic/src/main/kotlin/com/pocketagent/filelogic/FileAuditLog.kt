package com.pocketagent.filelogic

/**
 * 文件操作的审计记录 —— 「**全过程对用户完全透明可见**」的落地形式。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"透明"不能只靠界面上的实时提示
 * ═══════════════════════════════════════════════════════════════
 *
 * 实时提示只在用户当时正看着屏幕时有效。而 agent 干活的特点是
 * **用户不看**：他让 agent 整理文档，然后去干别的了。
 *
 * 等他回来，屏幕上只有一句"已完成"。中间动过哪些文件、删了什么、
 * 覆盖了什么 —— 全都没有痕迹。而"我的文件怎么少了一个"这种问题，
 * **只能靠事后回看回答**。
 *
 * 所以审计日志不是"附加的日志功能"，它是这个能力能被信任的前提。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 两条硬纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 绝不记录文件内容。**
 *    这条由**类型**保证：[FileAuditEvent] 里没有任何能装内容的字段，
 *    只有路径、操作、结论。所以"顺手把内容也记下来方便调试"这件事
 *    在类型上就做不到 —— 同 `RedactRegion` 的"刻意不含保留原文的字段"。
 *
 * **2. 路径必须**消毒**后再记。**
 *    POSIX 允许文件名里含换行符。如果直接把路径拼进一行日志：
 *
 *    ```
 *    2026-09-23 11:20:03 删除 /Documents/正常.txt
 *    2026-09-23 11:20:04 删除 /Documents/我\n2026-09-23 11:20:05 删除 /Documents/工资单.xlsx
 *    ```
 *
 *    第二条路径里的 `\n` 会**伪造出一条不存在的审计记录**。
 *    用户回看时看到"删除 工资单.xlsx"，但那次操作根本没发生 ——
 *    而真实的记录反而被淹掉了。这不是理论问题：agent 完全可以
 *    被诱导去创建一个带换行符的文件名。
 *
 *    所以 [record] 会先把路径里的控制字符转义、并截断超长路径。
 */

/** 审计事件的结论。 */
enum class FileAuditOutcome {
    /** 判定放行，通道已执行 */
    EXECUTED,

    /** 需要确认，用户点了确认，然后执行 */
    EXECUTED_AFTER_CONFIRM,

    /** 判定拒绝（越界、黑名单、路径非法） */
    DENIED,

    /** 判定放行，但通道执行失败（权限被撤、文件被占用、磁盘满） */
    FAILED,

    /** 需要确认，用户明确点了"不要" */
    CONFIRMATION_DECLINED,

    /**
     * 需要确认，但超时没有回应 —— **视为拒绝**。
     *
     * ⚠️ 必须与 [CONFIRMATION_DECLINED] 分开记。
     *    用户主动拒绝说明他不同意这件事；超时说明他可能根本没看到。
     *    合并成一条会让"我明明没点过同意，怎么删了"这种质疑无从查起。
     */
    CONFIRMATION_TIMEOUT,
}

/**
 * 一条审计记录。
 *
 * ⚠️ **字段就是全部**。这里没有、也不该有 `content` / `snippet` / `preview`。
 *    见本文件头的纪律 1。
 */
data class FileAuditEvent(
    val timestamp: Long,

    /** 这次是什么操作 */
    val op: FileOp,

    /** 归一化后的路径。**已经过消毒** —— 见 [FileAuditLog.record] */
    val path: String,

    /** 落在哪条授权上。null 表示判定在"找到授权根"之前就结束了 */
    val rootId: String? = null,

    val outcome: FileAuditOutcome,

    /**
     * 补充说明。**同样必须不含文件内容。**
     *
     * 它承载的是"为什么"：拒绝原因、失败原因、确认理由。
     * 由 [FileAuditLog] 的工厂方法生成，避免调用方随手拼字符串。
     */
    val detail: String = "",
)

/**
 * 审计日志。
 *
 * 形态照 `DefaultSafetyGuard` 的审计部分：**内存环形缓冲 + 可选落库出口**。
 * 内存里保留最近 [DEFAULT_CAPACITY] 条，供"透明度报告"页面即时展示；
 * 需要长期留存时由 [sink] 写进数据库。
 */
class FileAuditLog(
    /** 落库出口。不传则只留在内存里（单元测试与数据库未就绪时的兜底）。 */
    private val sink: ((FileAuditEvent) -> Unit)? = null,

    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private val buffer = ArrayList<FileAuditEvent>(capacity)

    /**
     * 记一条。
     *
     * ⚠️ **这里做消毒，而不是在构造 [FileAuditEvent] 时做** ——
     *    因为构造点可能有很多个（裁决拒绝、通道失败、用户取消），
     *    而"每个构造点都要记得消毒"是不可依赖的。收口到唯一的写入口，
     *    与 `PrivacyFilter` 收口所有上传路径是同一个手法。
     */
    @Synchronized
    fun record(event: FileAuditEvent) {
        val sanitized = event.copy(
            path = sanitize(event.path),
            detail = sanitize(event.detail),
        )

        // 上限保护：审计日志自己不能变成内存泄漏源
        if (buffer.size >= capacity) buffer.removeAt(0)
        buffer += sanitized

        sink?.invoke(sanitized)
    }

    /** 目前保留的全部记录，按时间正序。供"透明度报告"页面展示。 */
    @Synchronized
    fun events(): List<FileAuditEvent> = buffer.toList()

    /** 按结论筛选。界面上的"我删过哪些东西"用这个。 */
    @Synchronized
    fun eventsWithOutcome(vararg outcomes: FileAuditOutcome): List<FileAuditEvent> =
        buffer.filter { it.outcome in outcomes }

    @Synchronized
    fun clear() = buffer.clear()

    companion object {
        /** 内存中保留的条数。与 `DefaultSafetyGuard.MAX_AUDIT_EVENTS` 取同一个量级。 */
        const val DEFAULT_CAPACITY = 500

        /**
         * 单条路径/说明的最大长度。
         *
         * ⚠️ 截断会丢失信息，但不截断会让一条 4000 字符的路径挤掉几十条正常记录。
         *    取舍依据：审计日志的用途是**让用户看出发生过什么**，
         *    而不是取证。512 足够放下任何正常路径。
         */
        const val MAX_TEXT_LENGTH = 512

        /**
         * 消毒：转义控制字符 + 截断。
         *
         * 转义用**可见的**形式（`\n` 写成 `\n` 两个字符，而不是真的换行），
         * 这样它在任何日志查看器里都占一行，伪造不出第二条记录。
         */
        internal fun sanitize(raw: String): String {
            val escaped = buildString(minOf(raw.length, MAX_TEXT_LENGTH + 1)) {
                for (ch in raw) {
                    when {
                        ch == '\n' -> append("\\n")
                        ch == '\r' -> append("\\r")
                        ch == '\t' -> append("\\t")
                        // C0 / C1 控制字符与 DEL —— 它们在日志里不可见，最容易藏东西
                        ch < ' ' || ch == '\u007F' || (ch in '\u0080'..'\u009F') ->
                            append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                        else -> append(ch)
                    }
                    if (length >= MAX_TEXT_LENGTH) break
                }
            }
            return if (escaped.length >= MAX_TEXT_LENGTH) {
                escaped.take(MAX_TEXT_LENGTH - 1) + "…"
            } else {
                escaped
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  事件工厂 —— 让调用点不必自己拼字符串
// ═══════════════════════════════════════════════════════════════

/**
 * 由裁决结果直接生成审计事件。
 *
 * ⚠️ 存在的理由：如果把"拼 detail"这件事留给每个调用点，
 *    迟早有人会在 detail 里塞进文件内容（"失败了，内容是 xxx"）。
 *    收口到工厂方法之后，detail 的取值空间是**有限的、可见的**，
 *    审查时一眼能扫完。
 */
object FileAuditEvents {

    fun executed(
        timestamp: Long,
        target: ResolvedTarget,
        wasConfirmed: Boolean,
    ): FileAuditEvent = FileAuditEvent(
        timestamp = timestamp,
        op = target.op,
        path = target.normalizedPath,
        rootId = target.root.id,
        outcome = if (wasConfirmed) FileAuditOutcome.EXECUTED_AFTER_CONFIRM
        else FileAuditOutcome.EXECUTED,
        detail = buildString {
            append("在「${target.root.displayName}」内")
            target.destinationNormalizedPath?.let { append("，目标 $it") }
            if (wasConfirmed) append("，已由用户确认")
        },
    )

    fun denied(
        timestamp: Long,
        op: FileOp,
        path: String,
        reason: DenyReason,
        userMessage: String,
    ): FileAuditEvent = FileAuditEvent(
        timestamp = timestamp,
        op = op,
        path = path,
        rootId = null,
        outcome = FileAuditOutcome.DENIED,
        // 记原因码而不是整段用户文案 —— 文案会随版本改，原因码不会
        detail = "拒绝原因 $reason",
    )

    fun failed(
        timestamp: Long,
        target: ResolvedTarget,
        reason: String,
    ): FileAuditEvent = FileAuditEvent(
        timestamp = timestamp,
        op = target.op,
        path = target.normalizedPath,
        rootId = target.root.id,
        outcome = FileAuditOutcome.FAILED,
        detail = reason,
    )

    fun confirmationResolved(
        timestamp: Long,
        target: ResolvedTarget,
        reason: ConfirmReason,
        accepted: Boolean,
        timedOut: Boolean = false,
    ): FileAuditEvent = FileAuditEvent(
        timestamp = timestamp,
        op = target.op,
        path = target.normalizedPath,
        rootId = target.root.id,
        outcome = when {
            timedOut -> FileAuditOutcome.CONFIRMATION_TIMEOUT
            accepted -> FileAuditOutcome.EXECUTED_AFTER_CONFIRM
            else -> FileAuditOutcome.CONFIRMATION_DECLINED
        },
        detail = "确认类型 $reason，${if (timedOut) "超时未回应" else if (accepted) "用户同意" else "用户拒绝"}",
    )
}
