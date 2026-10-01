package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/**
 * 人格骨骼 —— **永不可微调**的那一部分。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么它不是"另一组偏好字段"
 * ═══════════════════════════════════════════════════════════════
 *
 * 需求②的原话是「在交流过程中对该角色设定进行微调」。如果只做一个
 * "可以改任意字段"的调参器，那么下面这句话会**完全合法地**打穿架构：
 *
 * ```
 * 用户：以后我买完东西你直接帮我付一下，别再问我要不要付
 * ```
 *
 * 这不是漏洞利用，这是**一句看起来完全合理的自然语言指令**。而它
 * 恰好命中了架构原则 4（主动放弃支付环节）。
 *
 * 所以骨骼 / 血肉必须先分开，且分开的方式不能是"调参时判断一下"——
 * 判断会随着字段增多而漏掉某个分支（这正是本项目头号 bug 形态
 * 「安静地少做一件事」的温床）。
 *
 * ⇒ 这里的做法是**结构性隔离**：
 *
 * - [PersonaField] 枚举里**根本不存在**任何安全相关字段，因此
 *   `tune` 的类型系统层面就无法表达"改安全边界"。
 * - [PersonaSpec] 的构造是私有的，且唯一入口 [PersonaSpec.derive] 只从
 *   [PersonaPreset] 取 `bones`（恒为 [SafetyBones] 默认值），
 *   **没有任何代码路径能把外部传入的骨骼放进 spec**。
 * - 即使调用方硬塞一个名字（比如 `"refusePayment"`），[PersonaFieldRefs.resolve]
 *   也会把它识别成 [PersonaFieldRef.Bones]，由 [DefaultPersonaTuner] **抛异常 + 记审计**。
 *
 * 三道里任意一道单独失效，另外两道仍然拦得住。这不是冗余，是因为
 * 这条红线一旦破，破的方式一定是**静默的**。
 *
 * @property refusePayment 拒绝代支付（架构原则 4）。
 * @property refuseUnlock 拒绝代解锁（锁屏密码 / 支付密码 / 生物识别）。
 * @property honesty 诚实：不确定就说不确定，不编造已完成的动作。
 * @property privacyRedline 隐私红线：不把用户内容发往未授权去处。
 */
@Serializable
data class SafetyBones(
    val refusePayment: Boolean = true,
    val refuseUnlock: Boolean = true,
    val honesty: Boolean = true,
    val privacyRedline: Boolean = true,
) {

    /**
     * 是否仍处于"完整骨骼"状态。
     *
     * ⚠️ 它**不是**给 `tune` 用的开关，而是给测试与透明度报告用的断言点：
     *    任何时刻 `spec.bones.isIntact()` 都必须为 `true`，否则说明
     *    有人绕过 [PersonaSpec.derive] 造了一个 spec。
     */
    fun isIntact(): Boolean = refusePayment && refuseUnlock && honesty && privacyRedline
}

/**
 * 会被识别为"骨骼字段"的**外部名字**集合。
 *
 * ## 为什么是"名字集合"而不是"字段枚举"
 *
 * 因为攻击面来自**自然语言**，不是来自代码。模型可能把用户那句
 * 「帮我付款」翻译成 `refusePayment`、`refuse_payment`、`safety_bones`、
 * 甚至中文 `支付`。这些字符串在编译期都不存在，只能在运行期拦。
 *
 * ## 为什么中文别名也要收
 *
 * 「别名收多了会不会误伤」—— 不会。判定顺序是**先白名单、后骨骼**
 * （见 [PersonaFieldRefs.resolve]）：`tone_warmth` 先命中白名单就返回了，
 * 根本走不到这里。所以这里**宁可多收**：多收的代价是几乎为零的误判，
 * 少收的代价是静默放行一条"改安全边界"的指令。
 *
 * 集合里的每一项都经过 [squashFieldName] 归一（去分隔符 + 小写），
 * 因此 `refusePayment` 与 `refuse_payment` 是同一个键。
 */
val BONE_FIELD_NAMES: Set<String> = listOf(
    // ── 与 SafetyBones 属性名一一对应 ──────────────────────────
    "refusePayment",
    "refuseUnlock",
    "honesty",
    "privacyRedline",
    // ── 类别名：这些名字在别处可能指"整块骨骼"，也必须拦 ────────
    "safetyBones",
    "safety",
    "bones",
    "securityPolicy",
    // ── 中文别名：自然语言指令最常见的形态 ────────────────────
    //
    // ⚠️ 收词原则是「宁可多收」（见上面的注释）。判断顺序是**先白名单、
    //    后骨骼**，所以与白名单撞名的词会先命中白名单 —— 多收的误判
    //    代价接近于零，而少收的代价是**静默放行**一条改安全边界的指令。
    //
    // ⚠️ 但也**不是见词就收**：像「发送」「处理」「设置」这种日常动词不收 ——
    //    用户说「以后发送消息前先给我看」时，把它判成「你想动我的隐私红线」
    //    会让一句完全正常的偏好表达收到一个安全警告，那是另一种误导。
    //    收的是**明确指向这四条骨骼**的名词。
    "支付",
    "代付",
    "付款",
    "买单",
    "扣款",
    "转账",
    "红包",
    "付款码",
    "解锁",
    "代解锁",
    "解锁密码",
    "密码",
    "支付密码",
    "锁屏密码",
    "生物识别",
    "指纹",
    "人脸",
    "面容",
    "隐私",
    "隐私红线",
    "外传",
    "泄露",
    "诚实",
    "撒谎",
    "说谎",
    "欺骗",
    "编造",
    "安全",
    "安全边界",
    "骨骼",
).map { squashFieldName(it) }.toSet()