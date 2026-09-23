package com.pocketagent.capabilitylogic

import java.util.Locale

/**
 * 能力调用的审计记录 —— 「**第 0 档的能力用户看不见，所以必须能事后查**」的落地形式。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 第 0 档比"点屏幕"更需要审计
 * ═══════════════════════════════════════════════════════════════
 *
 * 点屏幕的能力天然带可见性：用户看得见界面在动，出问题也是当场发现。
 * 而第 0 档的设计目标**就是让屏幕上什么都不发生** ——
 *
 * ```
 * 凌晨 03:12  agent 把 Wi-Fi 关了、把一个 App 停用了、把亮度改成了 40
 * 早上 07:30  用户拿起手机：没有网、那个 App 不见了、屏幕有点暗
 * ```
 *
 * 这三件事之间**没有任何可见的因果链**。用户唯一能回答
 * "昨晚到底发生了什么"的途径，就是这份日志。
 *
 * 所以它不是"附加的日志功能"，它是第 0 档能被信任的前提 ——
 * 与 `FileAuditLog` 的定位完全一致。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 两条硬纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * **1. 绝不记录参数原值。**
 *
 * 这条与 `FileAuditLog` 的"绝不记录文件内容"是同一条纪律，但在这里
 * 更微妙 —— 因为**参数里既有"公开标识"也有"用户内容"**：
 *
 * | 参数 | 性质 | 能不能记 |
 * |---|---|---|
 * | `packageName = com.tencent.mm` | 公开标识 | **能** —— 这是"哪个 App 被停用了"的答案 |
 * | `text = 给妈妈打个电话` | 用户内容 | **不能** |
 * | `tag = 提醒` | 用户内容 | **不能** |
 *
 * 如果一刀切地"全记"，通知正文就会永久落进日志；如果一刀切地"全不记"，
 * 用户就问不出"昨晚停用的是哪个 App" —— 而那恰恰是最需要知道的一件事。
 *
 * 所以 [CapabilityAuditEvents] 用一个**单条规则**来切：
 * **只有长得像包名的值才被记下，其余一律只记参数名与长度。**
 * 规则只有一条，审查时一眼能扫完 —— 这比"给每条能力配一张字段表"更不容易出错，
 * 因为后者会随能力数量增长，而前者不会。
 *
 * **2. 一切文本进日志前必须消毒。**
 *
 * 审计日志的展示形态是**一行一条**。而 POSIX 允许文件名含换行符、
 * 参数值可以含任意控制字符。如果直接拼进去：
 *
 * ```
 * 2026-09-23 03:12:04 拒绝 通知正文不合法：我\n2026-09-23 03:12:05 已执行 停用应用
 * ```
 *
 * 中间那个 `\n` 会**伪造出一条"已执行"的记录** —— 而它根本没发生过，
 * 同时真实记录被淹掉。这不是理论问题：`notification.post` 的正文
 * 本来就来自模型，模型完全可以被诱导输出一个换行符。
 *
 * ⇒ 所以 [CapabilityAuditLog.record] 是唯一的写入口，消毒在那里收口。
 */

/** 审计事件的结论。 */
enum class CapabilityAuditOutcome {

    /** 判定放行，通道已执行 */
    EXECUTED,

    /** 需要确认，用户点了确认，然后执行 */
    EXECUTED_AFTER_CONFIRM,

    /**
     * 判定拒绝。
     *
     * 覆盖 [BlockReason] 里除 [BlockReason.NOT_GRANTED] 之外的全部原因，
     * 以及执行期发现的目标被拒。**这些结论没有任何用户操作能改变。**
     */
    DENIED,

    /**
     * 用户还没放行这条能力。
     *
     * ⚠️ **必须与 [DENIED] 分开记。** 两者对用户的意义完全不同：
     *    [DENIED] 是"这条路永远是堵的"，[NOT_GRANTED] 是"门还在，你没开"。
     *    合并成一条之后，用户看到一堆"被拒绝"会以为系统坏了，
     *    而实际上他只需要去点一下授权。
     */
    NOT_GRANTED,

    /** 判定放行，但通道执行失败（Shizuku 没激活、权限被撤、命令返回非零） */
    FAILED,

    /** 需要确认，用户明确点了"不要" */
    CONFIRMATION_DECLINED,

    /**
     * 需要确认，但超时没有回应 —— **视为拒绝**。
     *
     * ⚠️ 必须与 [CONFIRMATION_DECLINED] 分开。用户主动拒绝说明他不同意；
     *    超时说明他可能根本没看到。合并成一条会让
     *    "我明明没点过同意，怎么执行了"这种质疑无从查起。
     *    —— 与 `FileAuditOutcome` 的同名区分是同一个理由。
     */
    CONFIRMATION_TIMEOUT,
}

/**
 * 一条审计记录。
 *
 * ⚠️ **字段就是全部**。这里没有、也不该有 `args` / `summary` / `command`。
 *    见本文件头的纪律 1。
 */
data class CapabilityAuditEvent(
    val timestamp: Long,

    /**
     * 能力 id。
     *
     * ⚠️ 被拒绝的调用**也记** —— 而且往往更值得记。
     *    一个反复请求"停用 com.android.systemui"的插件，
     *    在日志里的样子是连续多条 [CapabilityAuditOutcome.DENIED]，
     *    这是用户判断"这个插件想干什么"的唯一线索。
     */
    val capabilityId: String,

    /**
     * 谁发起的。
     *
     * ⚠️ 它是这份日志里**信息量最大的一个字段**：定时任务的
     *    "凌晨自动做了"和 agent 的"白天有人看着做的"，
     *    在 [CapabilityAuditOutcome] 上完全一样，只有这里能区分。
     */
    val origin: CallOrigin,

    val outcome: CapabilityAuditOutcome,

    /**
     * 这次动作**指向什么**。
     *
     * 取值规则只有一条（见文件头纪律 1）：
     * - 写系统设置 → `命名空间/键`（**来自配方字面量，不含任何用户输入**）
     * - shell 命令 → 命令里那些**长得像包名**的项，逗号分隔；一个都没有则为空串
     *
     * ⚠️ 所以它**可能是空串**，而且空串是正常情况
     *    （`ui.night_mode`、`statusbar.collapse` 这类没有目标可言）。
     *    界面不要因为它是空的就显示"未知"。
     */
    val targetDigest: String = "",

    /**
     * 补充说明。**同样必须不含参数原值。**
     *
     * 它承载的是"为什么"：拒绝原因码、失败原因。
     * 由 [CapabilityAuditEvents] 的工厂方法生成，避免调用方随手拼字符串。
     */
    val detail: String = "",
)

/**
 * 审计日志。
 *
 * 形态照 `FileAuditLog`：**内存环形缓冲 + 可选落库出口**。
 * 内存里保留最近 [DEFAULT_CAPACITY] 条，供"透明度报告"页面即时展示；
 * 需要长期留存时由 [sink] 写进数据库。
 */
class CapabilityAuditLog(

    /** 落库出口。不传则只留在内存里（单元测试与数据库未就绪时的兜底）。 */
    private val sink: ((CapabilityAuditEvent) -> Unit)? = null,

    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private val buffer = ArrayList<CapabilityAuditEvent>(capacity)

    /**
     * 记一条。
     *
     * ⚠️ **这里做消毒，而不是在构造 [CapabilityAuditEvent] 时做** ——
     *    因为构造点可能有很多个（裁决拒绝、通道失败、用户取消、超时），
     *    而"每个构造点都要记得消毒"是不可依赖的。收口到唯一的写入口，
     *    与 `PrivacyFilter` 收口所有上传路径是同一个手法。
     */
    @Synchronized
    fun record(event: CapabilityAuditEvent) {
        val sanitized = event.copy(
            capabilityId = sanitizeForDisplay(event.capabilityId, MAX_FIELD_LENGTH),
            targetDigest = sanitizeForDisplay(event.targetDigest, MAX_FIELD_LENGTH),
            detail = sanitizeForDisplay(event.detail, MAX_FIELD_LENGTH),
        )

        // 上限保护：审计日志自己不能变成内存泄漏源
        if (buffer.size >= capacity) buffer.removeAt(0)
        buffer += sanitized

        sink?.invoke(sanitized)
    }

    /** 目前保留的全部记录，按时间正序。供"透明度报告"页面展示。 */
    @Synchronized
    fun events(): List<CapabilityAuditEvent> = buffer.toList()

    /** 按结论筛选。界面上的"昨晚它动过什么"用这个。 */
    @Synchronized
    fun eventsWithOutcome(vararg outcomes: CapabilityAuditOutcome): List<CapabilityAuditEvent> =
        buffer.filter { it.outcome in outcomes }

    /**
     * 按来源筛选。
     *
     * ⚠️ 存在的理由很具体：**无人值守的调用需要单独审查**。
     *    用户想知道"我没看着的时候，它都干了什么" ——
     *    而这个问题的答案只能靠 [CallOrigin.SCHEDULE] 这个字段筛出来。
     */
    @Synchronized
    fun eventsFrom(vararg origins: CallOrigin): List<CapabilityAuditEvent> =
        buffer.filter { it.origin in origins }

    @Synchronized
    fun clear() = buffer.clear()

    companion object {
        /** 内存中保留的条数。与 `FileAuditLog.DEFAULT_CAPACITY` 取同一个量级。 */
        const val DEFAULT_CAPACITY = 500

        /**
         * 单个字段的最大长度。
         *
         * ⚠️ 截断会丢信息，但不截断会让一个 4000 字符的包名挤掉几十条正常记录。
         *    取舍依据：审计日志的用途是**让用户看出发生过什么**，而不是取证。
         */
        const val MAX_FIELD_LENGTH = 512
    }
}

/**
 * 消毒：转义控制字符 + 截断。
 *
 * ⚠️ 它是一个**顶层 internal 函数**，而不是 [CapabilityAuditLog] 的伴生成员 ——
 *    因为 [CapabilityGuard] 也要用它（参数问题的文案会回显用户传的值）。
 *    放在顶层只有一份实现，两处不会漂移。
 *
 * 转义用**可见的**形式（换行写成 `\n` 两个字符，而不是真的换行），
 * 这样它在任何日志查看器里都占一行，伪造不出第二条记录。
 */
internal fun sanitizeForDisplay(raw: String, maxLength: Int = CapabilityAuditLog.MAX_FIELD_LENGTH): String {
    val escaped = buildString(minOf(raw.length, maxLength + 1)) {
        for (ch in raw) {
            when {
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                // C0 / C1 控制字符与 DEL —— 它们在日志里不可见，最容易藏东西
                ch < ' ' || ch == '\u007F' || (ch in '\u0080'..'\u009F') ->
                    append(String.format(Locale.ROOT, "\\u%04x", ch.code))

                else -> append(ch)
            }
            if (length >= maxLength) break
        }
    }
    return if (escaped.length >= maxLength) escaped.take(maxLength - 1) + "…" else escaped
}

// ═══════════════════════════════════════════════════════════════
//  事件工厂 —— 让调用点不必自己拼字符串
// ═══════════════════════════════════════════════════════════════

/**
 * 由裁决结果直接生成审计事件。
 *
 * ⚠️ 存在的理由：如果把"拼 detail"与"决定记哪个字段"留给每个调用点，
 *    迟早有人会在 detail 里塞进参数原值（"失败了，正文是 xxx"）。
 *    收口到工厂之后，能进日志的取值空间是**有限的、可见的**，
 *    审查时一眼能扫完。
 */
object CapabilityAuditEvents {

    fun executed(
        timestamp: Long,
        capabilityId: String,
        origin: CallOrigin,
        execution: PlannedExecution,
        wasConfirmed: Boolean,
    ): CapabilityAuditEvent = CapabilityAuditEvent(
        timestamp = timestamp,
        capabilityId = capabilityId,
        origin = origin,
        outcome = if (wasConfirmed) CapabilityAuditOutcome.EXECUTED_AFTER_CONFIRM
        else CapabilityAuditOutcome.EXECUTED,
        targetDigest = digestOf(execution),
        // ⚠️ 这里**不能**用 execution.summary —— 它含参数原值。
        //    见 CapabilityAuditLog 文件头的纪律 1。
        detail = if (wasConfirmed) "用户确认后执行" else "直接执行",
    )

    fun denied(
        timestamp: Long,
        capabilityId: String,
        origin: CallOrigin,
        reason: BlockReason,

        /**
         * 判定发生在规划之前时传 null。
         *
         * ⚠️ [BlockReason.NOT_GRANTED] **必须**单独归类 ——
         *    它不是拒绝，是"还没配置"。见 [CapabilityAuditOutcome.NOT_GRANTED]。
         */
        execution: PlannedExecution? = null,
    ): CapabilityAuditEvent = CapabilityAuditEvent(
        timestamp = timestamp,
        capabilityId = capabilityId,
        origin = origin,
        outcome = if (reason == BlockReason.NOT_GRANTED) CapabilityAuditOutcome.NOT_GRANTED
        else CapabilityAuditOutcome.DENIED,
        targetDigest = execution?.let { digestOf(it) } ?: "",
        // 记原因码而不是整段用户文案 —— 文案会随版本改，原因码不会
        detail = "原因 $reason",
    )

    fun failed(
        timestamp: Long,
        capabilityId: String,
        origin: CallOrigin,
        execution: PlannedExecution,

        /** 失败原因。⚠️ 由**通道实现方**给出，必须自行保证不含参数原值。 */
        reason: String,
    ): CapabilityAuditEvent = CapabilityAuditEvent(
        timestamp = timestamp,
        capabilityId = capabilityId,
        origin = origin,
        outcome = CapabilityAuditOutcome.FAILED,
        targetDigest = digestOf(execution),
        detail = reason,
    )

    /**
     * 通道执行失败 —— 由 [ChannelResult] 直接生成。
     *
     * ⚠️ [ChannelResult.Unavailable] 与 [ChannelResult.Failed] **都**归到
     *    [CapabilityAuditOutcome.FAILED]（与那个枚举的注释一致：
     *    「Shizuku 没激活、权限被撤、命令返回非零」）。两者的区别记在
     *    [CapabilityAuditEvent.detail] 里，因为**界面上它们是两句不同的话**：
     *    一句是「去配置」，一句是「试过了，没成」。
     *
     * ⚠️ 传 [ChannelResult.Succeeded] 进来是**调用方的 bug** ——
     *    那会把一次成功记成失败，在日志里留下一条安静的假记录。
     *    所以这里当场抛，而不是兜一句文案：宁可炸在测试里，
     *    也不要往日志里写错账。
     *
     * ⚠️ [ChannelResult.Succeeded.previousValue] **不进这里** ——
     *    它是值，不是标识。见本文件头纪律 1。
     *
     * ⚠️ 调用方注意：`WRITE_SECURE_SETTINGS` 没给时，**每一次**设置类调用
     *    都会产生一条 [ChannelResult.Unavailable]。连续的同原因记录说明
     *    配置没做，可以考虑合并（同一原因连续出现时只记第一条）——
     *    否则 500 条的环形缓冲会被同一句话填满。
     */
    fun channelFailed(
        timestamp: Long,
        capabilityId: String,
        origin: CallOrigin,
        execution: PlannedExecution,
        result: ChannelResult,
    ): CapabilityAuditEvent = CapabilityAuditEvent(
        timestamp = timestamp,
        capabilityId = capabilityId,
        origin = origin,
        outcome = CapabilityAuditOutcome.FAILED,
        targetDigest = digestOf(execution),
        detail = when (result) {
            is ChannelResult.Unavailable ->
                "通道不可用（${result.reason}）：" +
                    if (result.detail.isBlank()) result.reason.displayName else result.detail

            is ChannelResult.Failed ->
                "通道执行失败：${result.reason}" +
                    (result.exitCode?.let { "，退出码 $it" } ?: "")

            is ChannelResult.Succeeded -> error("channelFailed 只接受失败结论，收到的是 Succeeded")

            // ⚠️ 和 Succeeded 同一条纪律：**它也不是失败**。
            //
            //    `NeedsConfirmation` 的意思是"这一次**没有执行**，在等用户回答"
            //    —— 既没成功也没失败。把它记成 FAILED 的后果是具体的：
            //    用户会在审计区看到一条"没做成"，而实际上什么都没尝试过，
            //    他刚点的那一下确认框**还没被回答**。两件事在历史里长得一样，
            //    于是"我明明没点过同意，怎么失败了"无从查起。
            //
            //    它本来就不该走到这里 —— `CapabilityRuntime` 给它留了单独的分支，
            //    且**明确不记账**（见那里 `RequireConfirmation` 的注释）。
            //    走到这里说明有人绕过了编排层，宁可炸在测试里。
            is ChannelResult.NeedsConfirmation ->
                error("channelFailed 只接受失败结论，收到的是 NeedsConfirmation（它没执行，不是失败）")
        },
    )

    fun confirmationResolved(
        timestamp: Long,
        capabilityId: String,
        origin: CallOrigin,
        execution: PlannedExecution,
        confirmReason: ConfirmReason,
        accepted: Boolean,
        timedOut: Boolean = false,
    ): CapabilityAuditEvent = CapabilityAuditEvent(
        timestamp = timestamp,
        capabilityId = capabilityId,
        origin = origin,
        outcome = when {
            timedOut -> CapabilityAuditOutcome.CONFIRMATION_TIMEOUT
            accepted -> CapabilityAuditOutcome.EXECUTED_AFTER_CONFIRM
            else -> CapabilityAuditOutcome.CONFIRMATION_DECLINED
        },
        targetDigest = digestOf(execution),
        detail = "确认类型 $confirmReason，" +
            when {
                timedOut -> "超时未回应（视为拒绝）"
                accepted -> "用户同意"
                else -> "用户拒绝"
            },
    )

    /**
     * 从一次执行里提取"这次动作指向什么"。
     *
     * ═══════════════════════════════════════════════════════════
     *  ★ 规则只有一条：**只有长得像包名的值才被记下**
     * ═══════════════════════════════════════════════════════════
     *
     * - [PlannedExecution.SettingWrite] → `命名空间/键`。它来自配方**字面量**，
     *   不含任何用户输入，所以可以直接记。而且它是用户在
     *   "为什么我的亮度变了"这类排查里最需要的那一行。
     *
     * - [PlannedExecution.ShellArgv] → 遍历 argv，挑出**匹配包名规则**的项。
     *   一个都没有就返回空串。
     *
     * - [PlannedExecution.FileIntent] → **空串**。这不是漏了，是分工。
     *   路径属于上面表格里"用户内容"那一类（`/Documents/离婚协议书.docx`
     *   是用户的私事，不是公开标识），按纪律 1 的**同一条规则**就不该进这里。
     *   而"昨晚动的是哪个文件"这个问题由 `:filelogic` 的 `FileAuditLog` 回答 ——
     *   它专门为文件设计，自带路径消毒（控制字符转义 + 超长截断），
     *   并且它的纪律是"绝不记录文件内容"而不是"绝不记录路径"。
     *   ⇒ 两份日志各记各的：这里记"调用过哪条能力"，那里记"动过哪个文件"。
     *
     * ⚠️ 为什么不用"参数名表"来白名单：那张表会随能力数量增长，
     *    而且新增能力时**忘记更新它不会报错**，只会静默地少记一个字段 ——
     *    正是本项目里反复出现的那类"不报错、只是安静地少做一件事"的 bug。
     *    单条规则不会随能力增长，也就不会漂移。
     *
     * ⚠️ 为什么用 `CapabilityParams.packageNamePattern` 而不是另写一份正则：
     *    两份正则必然漂移，而漂移的方向是**松的那一份生效** ——
     *    一份松正则会让通知正文被误判成包名、从而永久落进日志。
     */
    private fun digestOf(execution: PlannedExecution): String = when (execution) {
        is PlannedExecution.SettingWrite ->
            "${execution.namespace.wireName}/${execution.key}"

        is PlannedExecution.ShellArgv ->
            execution.argv.filter { CapabilityParams.packageNamePattern.matches(it) }
                .joinToString(",")

        is PlannedExecution.FileIntent -> ""
    }
}
