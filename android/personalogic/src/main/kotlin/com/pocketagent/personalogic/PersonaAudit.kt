package com.pocketagent.personalogic

import java.util.Locale

/**
 * 人格审计的结论。
 *
 * ⚠️ [REJECTED] 必须与 [CANDIDATE_RECORDED] 分开。前者是"这次没发生"，
 *    后者是"这次记下了但还没生效"。合并之后用户看到一堆"已记录"，
 *    会以为助理已经照做了 —— 而它一次都没做。
 */
enum class PersonaAuditOutcome {
    /** 已生效 */
    APPLIED,

    /** 已进入候选区，尚未生效 */
    CANDIDATE_RECORDED,

    /** 候选链因出现互相矛盾的值而被作废 */
    CANDIDATE_CLEARED,

    /** 目标值与当前值相同，无事发生。**不是失败**，但也要留痕 */
    NO_CHANGE,

    /** 已回滚 */
    ROLLED_BACK,

    /** 被硬拒绝（白名单外 / 骨骼 / 非法值 / 非法证据） */
    REJECTED,
}

/**
 * 一条人格审计记录。
 *
 * ⚠️ 字段是**全部**。没有 `rawInput`、没有 `prompt`。
 *    同 `CapabilityAuditLog` 的纪律 1：审计里绝不落用户内容原文。
 *    这里的 [detail] 只承载"改了什么、为什么"的结构化描述。
 */
data class PersonaAuditEvent(
    val timestamp: Long,
    val fieldName: String,
    val outcome: PersonaAuditOutcome,
    val detail: String = "",
    val source: DeltaSource? = null,
    val confidence: Double = 0.0,
)

/**
 * 人格审计日志 —— 内存环形缓冲 + 可选落库出口。形态照 `CapabilityAuditLog`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么人格变更需要独立于变更账本的审计
 * ═══════════════════════════════════════════════════════════════
 *
 * 变更账本记的是**已发生的事**（ACTIVE / CANDIDATE / ROLLED_BACK）。
 * 而审计要记的是**没发生的事**：
 *
 * | 没发生的事 | 为什么必须留痕 |
 * |---|---|
 * | 一次被拒的骨骼修改 | 这是攻击信号。没有它，用户永远不知道有人试过 |
 * | 一次被作废的候选链 | 否则用户会看到候选"凭空消失" |
 * | 一次"值和当前一样"的指令 | 用户以为说了就有用，实际什么都没变 |
 *
 * 三类里**最危险的是第一类**：静默拒绝会让"我明明说过以后别替我付款"
 * 这件事在事后完全无法回溯 —— 而它恰好关系支付。
 *
 * ## 消毒
 *
 * [detail] 会拼进展示行，其中可能含模型生成的文本。控制字符（`\n` 等）
 * 必须被显式转义，否则一条 `\n` 就能在日志里**伪造出一条"已生效"记录**。
 * 消毒收口在 [record] 这一个写入口。
 *
 * @param sink 落库出口。null 表示只保留内存缓冲
 * @param capacity 内存缓冲条数上限
 */
class PersonaAuditLog(
    private val sink: ((PersonaAuditEvent) -> Unit)? = null,
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(capacity > 0) { "capacity 必须为正数，当前为 $capacity" }
    }

    private val buffer = ArrayDeque<PersonaAuditEvent>()

    /** 记录一条审计。**这是唯一写入口**，消毒在这里收口 */
    @Synchronized
    fun record(event: PersonaAuditEvent) {
        val safe = event.sanitized()
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(safe)
        sink?.invoke(safe)
    }

    /** 最近 n 条，按时间从早到晚 */
    @Synchronized
    fun recent(n: Int): List<PersonaAuditEvent> {
        require(n >= 0) { "n 不能为负" }
        return buffer.takeLast(n)
    }

    /** 全量快照（用于透明度报告页） */
    @Synchronized
    fun snapshot(): List<PersonaAuditEvent> = buffer.toList()

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}

/** 审计事件里单字段的最大展示长度 */
internal const val MAX_AUDIT_FIELD_LENGTH = 512

/**
 * 把任意文本消毒成**单行可展示**的形式。
 *
 * - `\n` / `\r` / `\t` → 可见转义（否则可伪造日志行）
 * - 其余控制字符 → `\uXXXX`
 * - 超长 → 截断并加 `…`
 *
 * 与 `capabilitylogic` 的同名函数是刻意的重复实现：模块间不共享工具类
 * 是本项目的约定（`core` 之外的模块彼此独立）。重复的代价是几行代码，
 * 收益是"改一个模块的消毒规则不会波及另一个模块的日志格式"。
 */
internal fun sanitizeForDisplay(raw: String, maxLength: Int = MAX_AUDIT_FIELD_LENGTH): String {
    val sb = StringBuilder(minOf(raw.length, maxLength))
    var written = 0
    for (ch in raw) {
        if (written >= maxLength) {
            sb.append('…')
            break
        }
        when {
            ch == '\n' -> { sb.append("\\n"); written += 2 }
            ch == '\r' -> { sb.append("\\r"); written += 2 }
            ch == '\t' -> { sb.append("\\t"); written += 2 }
            ch.isISOControl() -> {
                sb.append(String.format(Locale.ROOT, "\\u%04X", ch.code))
                written += 6
            }
            else -> { sb.append(ch); written++ }
        }
    }
    return sb.toString()
}

private fun PersonaAuditEvent.sanitized(): PersonaAuditEvent = copy(
    fieldName = sanitizeForDisplay(fieldName, 64),
    detail = sanitizeForDisplay(detail),
)

/**
 * 审计事件的工厂 —— 把细节字符串的拼接收口到一处。
 *
 * 理由同 `CapabilityAuditEvents`：调用方随手拼字符串，迟早会有人
 * 把用户原话拼进去。收口之后，"detail 里只有结构化描述"这件事
 * 只需要在一个文件里成立。
 */
object PersonaAuditEvents {

    fun applied(delta: PersonaDelta, now: Long): PersonaAuditEvent = PersonaAuditEvent(
        timestamp = now,
        fieldName = delta.field.name,
        outcome = PersonaAuditOutcome.APPLIED,
        detail = "「${delta.field.label}」${delta.oldValue} → ${delta.newValue}（${delta.source.name}，置信 ${delta.confidence}）",
        source = delta.source,
        confidence = delta.confidence,
    )

    fun candidateRecorded(
        field: PersonaField,
        newValue: String,
        have: Int,
        need: Int,
        confidence: Double,
        source: DeltaSource,
        now: Long,
    ): PersonaAuditEvent = PersonaAuditEvent(
        timestamp = now,
        fieldName = field.name,
        outcome = PersonaAuditOutcome.CANDIDATE_RECORDED,
        detail = "「${field.label}」候选值为「$newValue」，证据 $have/$need，置信 $confidence（${source.name}）",
        source = source,
        confidence = confidence,
    )

    fun candidateCleared(field: PersonaField, droppedCount: Int, now: Long): PersonaAuditEvent =
        PersonaAuditEvent(
            timestamp = now,
            fieldName = field.name,
            outcome = PersonaAuditOutcome.CANDIDATE_CLEARED,
            detail = "「${field.label}」出现了不同的候选值，作废 $droppedCount 条旧候选证据",
        )

    fun noChange(field: PersonaField, value: String, now: Long): PersonaAuditEvent = PersonaAuditEvent(
        timestamp = now,
        fieldName = field.name,
        outcome = PersonaAuditOutcome.NO_CHANGE,
        detail = "「${field.label}」当前已经是「$value」，无需变更",
    )

    fun rolledBack(delta: PersonaDelta, now: Long): PersonaAuditEvent = PersonaAuditEvent(
        timestamp = now,
        fieldName = delta.field.name,
        outcome = PersonaAuditOutcome.ROLLED_BACK,
        detail = "「${delta.field.label}」由「${delta.newValue}」回到「${delta.oldValue}」",
        source = delta.source,
    )

    fun rejected(
        reason: PersonaRejectionReason,
        fieldName: String,
        detail: String,
        now: Long,
    ): PersonaAuditEvent = PersonaAuditEvent(
        timestamp = now,
        fieldName = fieldName,
        outcome = PersonaAuditOutcome.REJECTED,
        detail = "${reason.code}：$detail",
    )
}