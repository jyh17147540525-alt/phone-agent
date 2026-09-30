package com.pocketagent.personalogic

/**
 * 一次微调被**硬拒绝**的原因码。
 *
 * ⚠️ 这四种原因**都是"拒绝"，不是"降级处理"**。区别很重要：
 *    降级（比如"越界就钳到 100"）会把一次拒绝在日志里写成一次成功，
 *    于是用户永远看不到"我说的话没被照做"。
 */
enum class PersonaRejectionReason(val code: String) {

    /** 试图修改安全骨骼。见 [SafetyBones] */
    PROTECTED_BONES("PROTECTED_BONES"),

    /** 字段名不在人格白名单里 */
    NOT_WHITELISTED("NOT_WHITELISTED"),

    /** 值本身不合法（轴越界、类型不对） */
    INVALID_VALUE("INVALID_VALUE"),

    /**
     * 证据不合法：空证据，或引用了未知的信号种类。
     *
     * ★ 为什么单独一项而不是并进 [INVALID_VALUE]：
     *   它指向的是**调用方的实现问题**（该给的证据没给、信号名写错），
     *   而 [INVALID_VALUE] 指向的是**用户表达的内容问题**。
     *   两者的修复动作完全不同 —— 前者是改代码，后者是重新问用户。
     */
    INVALID_EVIDENCE("INVALID_EVIDENCE"),
}

/**
 * 微调被拒绝时抛出的异常。
 *
 * ## 为什么用异常而不是返回一个 `Rejected` 结果
 *
 * 拒绝意味着**调用方写错了**（或者发起了一次攻击）。返回结果值的话，
 * `tune` 的返回值会多出一个分支，而 `when` 未必是穷尽的 ——
 * 漏掉那个分支的表现是"拒绝被当成成功继续往下走"。
 *
 * 抛异常让编译器逼调用方处理，也让"拒绝了但流程继续"这种状态
 * **不可能被写出来**。
 */
class PersonaRejectionException(
    val reason: PersonaRejectionReason,
    /** 被拒的字段名（原文，便于对账） */
    val fieldName: String,
    detail: String,
) : IllegalArgumentException("微调被拒绝[${reason.code}] 字段=$fieldName：$detail")

/**
 * 拒绝的**唯一出口**：先落审计，再抛异常。
 *
 * ⚠️ 审计与抛异常必须**成对**发生，否则就会出现"记了日志但没拒绝"
 *   （攻击仍在继续）或"拒绝了但没日志"（用户查不到发生了什么）。
 *   把它收口成一个函数，是因为这两步分开写时，总有人在某个分支里
 *   只写了其中一步 —— 而那一步往往是**先漏掉审计**，因为异常路径
 *   看起来已经"处理了"。
 *
 * @return 本函数永不返回（`Nothing`），调用方可以直接写在
 *   `?:` / `when` 分支里当作终止路径
 */
internal fun rejectWithAudit(
    audit: PersonaAuditLog,
    reason: PersonaRejectionReason,
    fieldName: String,
    detail: String,
    now: Long,
): Nothing {
    audit.record(PersonaAuditEvents.rejected(reason, fieldName, detail, now))
    throw PersonaRejectionException(reason, fieldName, detail)
}