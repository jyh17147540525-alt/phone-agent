package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/** 字段取值属于哪一类。决定 [PersonaField.normalize] 怎么校验。 */
@Serializable
enum class ValueKind {
    /** 自由文本，归一 = 去首尾空白 */
    TEXT,

    /** 0..100 的整数轴，归一 = 解析 + 范围校验 */
    AXIS,
}

/**
 * 人格白名单字段 —— **不在此枚举内的一律不可微调**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个枚举就是白名单本身，不是"白名单的一份拷贝"
 * ═══════════════════════════════════════════════════════════════
 *
 * 常见写法是把白名单做成一个 `Set<String>`，然后靠人记得同步维护。
 * 那种写法一旦漏加/忘删，表现是**静默**的：要么一条合法微调被拒，
 * 要么一条非法微调被放行。
 *
 * 这里让"能用哪些字段"直接编码进类型：`tune` 的入参在能走到落库之前，
 * 必须先被 [PersonaFieldRefs.resolve] 映射成本枚举的某个成员。
 * 想加一个可微调字段，就必须改这个枚举 —— 改动是**显式**的，
 * code review 时一眼能看见。
 *
 * ⚠️ 本枚举里**没有、也绝不允许出现**任何与安全边界相关的成员。
 *    见 [SafetyBones] 的类注释。
 */
@Serializable
enum class PersonaField(
    val kind: ValueKind,
    /** 轴类字段对应的轴；文本类字段为 null */
    val axis: ToneAxis?,
    /** 面向用户的展示名 */
    val label: String,
) {
    ADDRESS_STYLE(ValueKind.TEXT, null, "称呼方式"),
    DIALECT(ValueKind.TEXT, null, "方言 / 口癖"),
    CATCHPHRASE(ValueKind.TEXT, null, "口头禅"),

    TONE_WARMTH(ValueKind.AXIS, ToneAxis.WARMTH, "温度"),
    TONE_BREVITY(ValueKind.AXIS, ToneAxis.BREVITY, "简洁"),
    TONE_HUMOR(ValueKind.AXIS, ToneAxis.HUMOR, "幽默"),
    TONE_PROACTIVITY(ValueKind.AXIS, ToneAxis.PROACTIVITY, "主动程度"),
    TONE_FORMALITY(ValueKind.AXIS, ToneAxis.FORMALITY, "正式度"),
    TONE_EMOJI(ValueKind.AXIS, ToneAxis.EMOJI, "表情"),
    ;

    /**
     * 把一个外部字符串归一成可以落库的形式。
     *
     * @return 归一后的值；**null 表示这个值不合法**（而不是"用默认值补上"）。
     *   调用方必须把它当成 [PersonaRejectionReason.INVALID_VALUE] 处理，
     *   不能 `?: "默认值"`。
     */
    fun normalize(raw: String): String? = when (kind) {
        ValueKind.TEXT -> raw.trim()

        // ⚠️ 越界返回 null，**不钳制**。钳制会让"用户说了 200"在日志里
        //    变成一条成功的 "100" —— 见 ToneAxes.withAxis 的注释。
        ValueKind.AXIS -> raw.trim().toIntOrNull()
            ?.takeIf { it in ToneAxes.MIN..ToneAxes.MAX }
            ?.toString()
    }
}

/**
 * 一个"外部字段名"解析后的归宿。
 *
 * ⚠️ **三态，不是两态。** 「骨骼」与「未知」必须分开：
 *
 * | 归宿 | 含义 | 用户看到的 |
 * |---|---|---|
 * | [Bones] | 试图改安全边界 | 「这个不能改，它是我拒绝做某些事的底线」 |
 * | [Unknown] | 只是名字不认识 | 「我没听懂要改哪个方面」 |
 *
 * 合并成一句"不支持"会让一个**攻击尝试**和一次**正常但没听清**的请求
 * 长得一模一样，用户也就无从判断"它是不是想动我的安全设置"。
 * —— 与 `CapabilityAuditOutcome` 里把 `DENIED` 与 `NOT_GRANTED` 分开是同一条理由。
 */
sealed interface PersonaFieldRef {

    /** 命中白名单 */
    data class Whitelisted(val field: PersonaField) : PersonaFieldRef

    /** 命中骨骼字段名 —— 必须硬拒绝 + 记审计 */
    data class Bones(val raw: String) : PersonaFieldRef

    /** 既不在白名单也不在骨骼表里 */
    data class Unknown(val raw: String) : PersonaFieldRef
}

/**
 * 字段名解析 —— 从"外部字符串"到 [PersonaFieldRef] 的**唯一入口**。
 *
 * ## 判定顺序是有讲究的：先白名单，后骨骼
 *
 * 反过来的话，一个恰好在两张表里都存在的键（现实中确实可能出现，
 * 比如以后有人加一个叫 `safety_tone` 的轴）会被判成骨骼而**永久不可改**，
 * 而错误信息说的是"这是安全边界"，没人能看出问题出在顺序上。
 *
 * 先白名单的另一个好处：白名单是显式的强意图（要改这个字段），
 * 骨骼表是**兜底的拒绝网**。兜底网不该有比强意图更高的优先级。
 */
object PersonaFieldRefs {

    /**
     * 解析外部字段名。
     *
     * 归一规则见 [squashFieldName]：去大小写、去空格、去 `_`/`-`/`.`。
     * 因此 `TONE_WARMTH`、`toneWarmth`、`tone.warmth`、`warmth` 都会
     * 命中 [PersonaField.TONE_WARMTH]。
     */
    fun resolve(raw: String): PersonaFieldRef {
        val key = squashFieldName(raw)
        FIELD_BY_KEY[key]?.let { return PersonaFieldRef.Whitelisted(it) }
        if (key in BONE_FIELD_NAMES) return PersonaFieldRef.Bones(raw)
        return PersonaFieldRef.Unknown(raw)
    }
}

/**
 * 字段名归一：去首尾空白 → 小写 → 只保留字母与数字。
 *
 * 中文不会被 `isLetterOrDigit()` 滤掉（CJK 属于 Letter），
 * 所以 `"支付"` 归一后仍然是 `"支付"` —— 这正是骨骼表里
 * 中文别名能生效的前提。
 */
internal fun squashFieldName(raw: String): String =
    raw.trim().lowercase().filter { it.isLetterOrDigit() }

/**
 * 裸轴名别名。
 *
 * 为什么要：模型把"再温柔一点"翻成字段名时，几乎一定会写 `warmth`
 * 而不是 `TONE_WARMTH` —— 因为前者才是自然语言里那个词。
 * 不接受它，就要让模型去猜一个本项目内部的命名规范，那是在
 * 给一个可以避免的失败留门。
 *
 * ⚠️ 只收**不会有歧义**的裸名。像 `tone` 这种（指代哪根轴？）一律不收 ——
 *    有歧义时应当落到 [PersonaFieldRef.Unknown]，让用户重新说清楚。
 */
private val FIELD_ALIASES: Map<String, PersonaField> = mapOf(
    "warmth" to PersonaField.TONE_WARMTH,
    "brevity" to PersonaField.TONE_BREVITY,
    "humor" to PersonaField.TONE_HUMOR,
    "proactivity" to PersonaField.TONE_PROACTIVITY,
    "formality" to PersonaField.TONE_FORMALITY,
    "emoji" to PersonaField.TONE_EMOJI,
    "address" to PersonaField.ADDRESS_STYLE,
    "addressstyle" to PersonaField.ADDRESS_STYLE,
)

/**
 * 归一后的字段名 → 白名单字段。
 *
 * ⚠️ 定义在**文件顶层**而不是 [PersonaField] 的 companion 里：
 *    枚举 companion 的初始化时机与枚举项初始化有微妙的先后关系，
 *    顶层 val 是首次访问时初始化，没有这个坑。
 */
internal val FIELD_BY_KEY: Map<String, PersonaField> =
    PersonaField.entries.associateBy { squashFieldName(it.name) } + FIELD_ALIASES