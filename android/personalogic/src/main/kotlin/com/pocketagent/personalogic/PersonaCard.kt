package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/**
 * 人格卡 v2 —— 「这个人格是什么样」的**结构化资产**（设计稿 §2 的七层）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  七层里，这张卡装的是哪几层
 * ═══════════════════════════════════════════════════════════════
 *
 * | 层 | 内容 | 在这里吗 |
 * |---|---|---|
 * | ① 身份 | 自称、身份叙事 | ✅ [identity] |
 * | ② 关系 | 关系定义、双向称谓、互动协议 | ✅ [relationship] |
 * | ③ 语言指纹 | 称呼/禁令/标点/口癖 | ✅ [fingerprint] |
 * | ④ 性格轴 | 六根语气轴 | ❌ 用既有的 [ToneAxes]（不复制一份） |
 * | ⑤ 背景故事 | 来历、共同经历 | ✅ [background]（**不常驻**） |
 * | ⑥ 边界与雷点 | 用户设的雷点 | ✅ [boundaries] |
 * | ⑦ 状态 | 情绪 / 精力 | ❌ [PersonaState]（**运行时**，非设定） |
 *
 * ★ ④ 刻意**不在这张卡里**：[ToneAxes] 已经是它的唯一载体，
 *    再存一份就会有两份"当前温度是多少"，而它们必然漂移。
 * ★ ⑦ 也刻意不在：**状态不是设定**。混进来会让"今天心情差"被当成"人格变了"
 *    （设计稿 §4.2 的关键不变量）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 背景故事不常驻 —— 这张卡不是"整卡进 prompt"
 * ═══════════════════════════════════════════════════════════════
 *
 * [background] 是最容易膨胀的部分，全部塞进 system prompt 会挤掉记忆与对话空间。
 * 它属于**检索层**（与记忆联动），装配器默认**不注入**它 —— 见 [PersonaAssembler]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 本卡里不得出现任何安全相关字段
 * ═══════════════════════════════════════════════════════════════
 *
 * 与 [PersonaField] 的白名单纪律一致：安全边界只归 [SafetyBones]，
 * 本卡与 [PersonaDelta] 都碰不到它。想让助理"别问就付款"的用户，
 * 在这张卡里**没有可以改的地方** —— 这是结构性隔离，不是提示词纪律。
 */
@Serializable
data class PersonaCard(
    val identity: IdentityLayer,
    val relationship: RelationshipLayer,
    val fingerprint: LanguageFingerprint,
    /** ⑥ 边界与雷点。用户可设的「人格雷点」，每条一句话。 */
    val boundaries: List<String> = emptyList(),
    /** ⑤ 背景故事。**不常驻**，装配器默认不注入（见类注释）。 */
    val background: String? = null,
    /** 示例对话锚点（few-shot），进装配器最内层。 */
    val anchors: List<DialogueAnchor> = emptyList(),
) {

    init {
        require(identity.selfName.isNotBlank()) { "自称不能为空 —— 没有人格的人格卡会让装配器渲染出一片空白" }
    }

    companion object {

        /**
         * 从既有资产搭出人格卡 —— **不另存一份，只做映射**。
         *
         * `PersonaPreset`（模板）与 `PersonaSpec`（当前人格，含微调回放结果）
         * 已经是"这个人格是谁"的事实来源；人格卡是**给装配器用的视图**。
         * 若让卡自己成为第二个事实来源，微调之后的卡与 spec 就会不一致 ——
         * 而那种不一致表现为"用户改了口癖，助理还是老样子"。
         *
         * @param preset 模板（提供身份叙事与锚点种子）
         * @param spec 当前人格（提供称呼与口头禅的**生效值**）
         */
        fun of(preset: PersonaPreset, spec: PersonaSpec): PersonaCard = PersonaCard(
            identity = IdentityLayer(
                selfName = preset.displayName,
                narrative = "",
            ),
            relationship = RelationshipLayer(
                definition = "",
                userAddress = spec.addressStyle,
                assistantAddress = "",
            ),
            fingerprint = LanguageFingerprint(
                addressForms = listOf(spec.addressStyle).filter { it.isNotBlank() },
                catchphrases = listOf(spec.catchphrase).filter { it.isNotBlank() },
            ),
            anchors = preset.scriptSeeds.map { DialogueAnchor(userSays = "", itSays = it) },
            background = null,
        )
    }
}

/**
 * ① 身份层。
 *
 * @param selfName 自称（如「小助」）。**非空** —— 装配器 L1 靠它开场
 * @param narrative 身份叙事（如「住在你手机里的房客」）。
 *   允许为空：不是每个人格都需要一段来历，硬编一段只会变成噪音。
 */
@Serializable
data class IdentityLayer(
    val selfName: String,
    val narrative: String = "",
)

/**
 * ② 关系层。
 *
 * @param definition 关系定义（管家 / 搭档 / 恋人…）
 * @param userAddress 怎么称呼用户（**生效值**，可被微调覆盖）
 * @param assistantAddress 用户怎么称呼它
 * @param protocols 互动协议（「可以顶嘴」「不要撒娇」…）。短句列表
 */
@Serializable
data class RelationshipLayer(
    val definition: String = "",
    val userAddress: String = "",
    val assistantAddress: String = "",
    val protocols: List<String> = emptyList(),
)

/**
 * 一组示例对话锚点（few-shot，设计稿 §2.3）。
 *
 * @param version 锚点版本。**用于过期检测**：锚点版本 < 最近一条 ACTIVE delta 时，
 *   说明用户改了风格但示例还在教旧的 —— 装配器会标记过期（[AnchorStatus]）。
 * @param userSays 用户说的话（可为空：只有"它这样回"也足以承载风格）
 * @param itSays 它这样回
 */
@Serializable
data class DialogueAnchor(
    val userSays: String,
    val itSays: String,
    val version: Int = 1,
)

/** 锚点新鲜度（设计稿 §2.3 / §8.3 第 9 条）。 */
sealed interface AnchorStatus {

    data object Fresh : AnchorStatus

    /**
     * 已过期：锚点版本落后于最近的 ACTIVE 变更 ⇒ 示例在教旧风格。
     * 界面应提示用户「示例还没跟上你最近的调整」。
     */
    data class Stale(val anchorVersion: Int, val latestChangeVersion: Int) : AnchorStatus

    /** 没有锚点。**不是过期** —— 别把"没有"报成"过期"，那会诱导用户去补一堆示例。 */
    data object Absent : AnchorStatus
}

/**
 * ⑦ 运行时状态（情绪 / 精力）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 关键不变量：状态**不参与漂移计算**、**不进人格账本**
 * ═══════════════════════════════════════════════════════════════
 *
 * 若状态进了 `PersonaSpec` 或账本，「今天心情差」会被 [PersonaDistance] 算成
 * 「人格变了」，于是锚定询问（「我好像变得不像我了」）会**在最不该的时候弹出** ——
 * 用户刚被工作骂完，助理跑来问"我是不是变了"。
 *
 * ⇒ 它是**独立的运行时值**，与人格设定分属两个世界。这条不变量有测试钉住
 *   （`PersonaStateTest`：状态的任何取值都不改变人格距离）。
 *
 * ## 三项的含义与边界
 *
 * - [mood] 愉悦（0..100）
 * - [energy] 精力（0..100），小时级自然回落
 * - ⚠️ `closeness`（亲密）**不在这里** —— 它归关系层（设计稿 §4.4），
 *   因为它是长期量、要驱动阶段推进，而 mood/energy 是小时级的
 *
 * ## 可解释性约束（设计稿 §4.2）
 *
 * 状态必须**可解释**：用户问「你怎么了」时能引用具体由头。
 * 随机情绪 = 崩人设。所以 [reason] 不是调试信息，它是**能被复述的由头**。
 */
@Serializable
data class PersonaState(
    val mood: Int = NEUTRAL,
    val energy: Int = NEUTRAL,
    /** 状态的由头（可被复述，如「陪你熬到三点」）。null = 无特别由头 */
    val reason: String? = null,
) {

    init {
        require(mood in 0..100) { "mood 必须落在 0..100，当前为 $mood" }
        require(energy in 0..100) { "energy 必须落在 0..100，当前为 $energy" }
    }

    /** 是否处于中位（无需在 prompt 里调制语气）。 */
    fun isNeutral(): Boolean = mood == NEUTRAL && energy == NEUTRAL

    companion object {
        const val NEUTRAL = 50
    }
}
