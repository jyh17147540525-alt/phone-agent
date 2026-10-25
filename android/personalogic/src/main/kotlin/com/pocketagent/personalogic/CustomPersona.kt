package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/**
 * 自定义角色的性别 —— **三档，含"不指定"**。
 *
 * ⚠️ 为什么不复用 `PresetGender`：那个是**内置模板**的属性，
 *    只有 MALE/FEMALE 两档（模板就是那两个）。自定义角色需要
 *    "不指定"这一档 —— 很多角色（比如"我的老同学"）不需要性别，
 *    强行二选一会逼用户做一个他没想过的决定。
 */
@Serializable
enum class PersonaGender(val label: String) {
    UNSPECIFIED("不指定"),
    MALE("男"),
    FEMALE("女"),
    ;

    /**
     * 映射到内置模板用的两档。
     *
     * ⚠️ `UNSPECIFIED` 落到 MALE 是**任意的** —— 但必须有映射，
     *    因为旧接口要一个值。真正该修的是那个旧接口，
     *    不是在这里假装"这是中性"。
     */
    fun toPresetGender(): PresetGender = when (this) {
        MALE -> PresetGender.MALE
        FEMALE -> PresetGender.FEMALE
        UNSPECIFIED -> PresetGender.MALE
    }
}

/**
 * **自定义角色** —— 用户自己定义"这个人是谁"。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 在此之前只有两个硬编码模板（男声/女声助理）
 * ═══════════════════════════════════════════════════════════════
 *
 * `PersonaPresets.ALL` 只有 MALE 和 FEMALE，注释里写着
 * "用户自建人格属于后续阶段"。现在补上。
 *
 * ⚠️ 但"自定义"不等于"什么都能改"：
 *    安全边界（[SafetyBones]）**不进这张表**，用户改不到。
 *    这是结构性隔离，不是提示词纪律 —— 详见 `PersonaField` 的白名单。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么自定义角色要单独一个类型，而不是直接塞进 PersonaPreset
 * ═══════════════════════════════════════════════════════════════
 *
 * [PersonaPreset] 是**内置模板**：它假设每个字段都有人填过、都合理。
 * 而自定义角色必然有大量"用户没填"的字段 —— 用可空字段表达，
 * 让"没填"和"填了空字符串"能被区分开。
 *
 * 好处很实际：没填"口癖"时，装配器**不会**生成
 * "你的口头禅是：（空）" 这种句子 —— 那会让模型开始自己编一个。
 */
@Serializable
data class CustomPersona(
    /** 稳定 id。改名不改 id —— 否则历史会话会与人格脱钩。 */
    val id: String,

    /** 名字。**必填** —— 没有名字就没法称呼，模型会编一个奇怪的 */
    val name: String,

    /**
     * 这是一个什么人。自由文本，如"一位退休的中学语文老师"。
     *
     * ⚠️ 这是**最有信息量**的一栏：它同时决定了说话方式、
     *    知识面和关心人的方式。提示用户写清楚它，比让他调六个语气滑杆有用。
     */
    val identity: String = "",

    /** 怎么称呼用户。空 = 用"你" */
    val addressUser: String = "",

    /** 用户怎么称呼它。空 = 用名字 */
    val addressSelf: String = "",

    /** 语气轴。null = 用中性默认 */
    val tone: ToneAxes? = null,

    /**
     * 性别。**用户自己选**，不由名字猜。
     *
     * ⚠️ 它只影响音色与自称，不影响"这个人是谁" ——
     *    所以单独一栏而不是揉进 [identity]。
     */
    val gender: PersonaGender = PersonaGender.UNSPECIFIED,

    /** 口癖 / 常用说法。**每条一句**，会作为 few-shot 锚点 */
    val catchphrases: List<String> = emptyList(),

    /** 方言 / 说话习惯（如"带点四川口音""说话爱用歇后语"） */
    val dialect: String = "",

    /** 它和用户的关系（自由文本："我的大学室友"） */
    val relationTo: String = "",

    /** 用户希望它记得的事（会被检索，不全量注入） */
    val memories: List<String> = emptyList(),

    /** 头像。null = 用名字首字生成的占位 */
    val avatarRes: String? = null,
) {

    init {
        require(name.isNotBlank()) { "角色必须有名字 —— 没有名字模型会自己编一个" }
        require(name.length <= MAX_NAME) { "名字过长（${name.length}），上限 $MAX_NAME" }
        require(catchphrases.size <= MAX_CATCHPHRASES) {
            "口癖最多 $MAX_CATCHPHRASES 条 —— 太多会挤占上下文，且模型会混着用"
        }
    }

    /**
     * 转成内置模板能吃的形状，供既有装配器复用。
     *
     * ⚠️ 这只是**适配**，不是"自定义角色本质上就是个 preset" ——
     *    `CustomPersona` 保留了 preset 装不下的东西（可空的语气轴、
     *    关系描述、记忆列表）。转过去会丢掉那些，
     *    所以它只用于"需要一个 preset 的地方"，不是唯一形态。
     */
    fun toPreset(): PersonaPreset = PersonaPreset(
        id = id,
        displayName = name,
        // ⚠️ `PresetGender` 只有 MALE/FEMALE，没有"不确定"这一档。
        //    自定义角色不该被强行归入某一性别 —— 但 `PersonaPreset`
        //    又必须给一个值。取舍：**默认 FEMALE 会让男性角色音色不对，
        //    反之亦然**，所以这里取一个中性的做法 + 注释说明它只是占位。
        //
        //    ⚠️ 真正的性别选择应该在界面上让用户自己选（`CustomPersona.gender`）。
        //    这一处只服务于"需要一个 preset"的旧接口。
        gender = gender.toPresetGender(),
        addressStyle = addressUser.ifBlank { "你" },
        tone = tone ?: ToneAxes(),
        voice = VoiceHint(timbre = "自然", pitchSemitones = 0.0, speedRatio = 1.0),
        catchphrase = catchphrases.firstOrNull().orEmpty(),
        dialect = dialect,
        scriptSeeds = catchphrases,
        avatarRes = avatarRes,
    )

    /**
     * 给模型看的一段自我介绍。
     *
     * ★ 只拼**填了的**字段 —— 没填就不出现。
     *   如果无条件写"你的口癖是：」"，模型会以为那是一个真的空口癖，
     *   然后自己编一个来填坑。
     */
    fun describeForPrompt(): String = buildString {
        append("你叫").append(name).append("。")
        if (identity.isNotBlank()) append(identity.trimEnd('。')).append("。")
        if (relationTo.isNotBlank()) {
            append("你和对方的关系：").append(relationTo.trimEnd('。')).append("。")
        }
        if (addressUser.isNotBlank()) {
            append("你称呼对方为「").append(addressUser).append("」。")
        }
        if (dialect.isNotBlank()) append("你说话").append(dialect.trimEnd('。')).append("。")
        if (catchphrases.isNotEmpty()) {
            append("你常说的话：").append(catchphrases.joinToString("、") { "「$it」" }).append("。")
        }
    }

    companion object {
        const val MAX_NAME = 24
        const val MAX_CATCHPHRASES = 8

        /** 空白角色 —— 新建时的起点。 */
        fun blank(id: String): CustomPersona = CustomPersona(id = id, name = "")
    }
}

/**
 * 自定义角色的效果预估 —— **给用户看"改了会发生什么"**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么需要这个
 * ═══════════════════════════════════════════════════════════════
 *
 * 自定义角色最容易的失败是：用户填了一堆，结果**没有任何效果** ——
 * 因为它填的字段装配器不用。那种"填了没用"的体验比"不能填"更糟。
 *
 * 这个小工具把"你填的东西会怎么被用"说清楚，同时指出
 * **哪些地方还空着、补上会明显变好**。
 *
 * ⚠️ 它不做"评分"（85 分的人格）—— 那个数字没有意义，
 *    而且会让用户去追分而不是追"像不像"。
 */
object PersonaCompleteness {

    /** 一项还没填、但值得填的东西。 */
    data class Hint(val field: String, val why: String)

    /**
     * 列出值得补的项，**按影响从大到小**。
     *
     * @return 空 = 已经够完整
     */
    fun hints(persona: CustomPersona): List<Hint> = buildList {
        if (persona.identity.isBlank()) {
            // ★ 这一栏信息量最大：它一句话就决定了说话方式、知识面和关心方式
            add(Hint("这是一个什么人", "一句话说清他是谁（职业、性格、说话方式），比调语气滑杆有用得多"))
        }
        if (persona.catchphrases.isEmpty()) {
            add(Hint("口癖", "写一两句他常说的话，模型会照着这个语气说"))
        }
        if (persona.relationTo.isBlank()) {
            add(Hint("和你的关系", "比如「我的大学室友」—— 关系决定了亲近程度"))
        }
        if (persona.addressUser.isBlank()) {
            add(Hint("怎么称呼你", "他叫你什么？小名、外号、还是「你」"))
        }
    }

    /** 是否已经够完整（没有任何提示）。 */
    fun isComplete(persona: CustomPersona): Boolean = hints(persona).isEmpty()
}
