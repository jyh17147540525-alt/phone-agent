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
/**
 * **说话长度** —— 去掉 AI 味的第一根杠杆。
 *
 * ★ 为什么它比"语气""风格"更重要：
 *   真人回微信绝大多数时候只有一两句。而模型的默认行为是
 *   "先总结、再分点、最后补充" —— 那个结构本身就宣告了它是 AI。
 *   把篇幅压下来，剩下的像不像，靠内容去解决。
 */
@Serializable
enum class PersonaVerbosity(val label: String, val promptHint: String) {
    BRIEF("惜字如金", "一到两句话就够。不要分点、不要解释、不要追问「还有什么需要」。"),
    NORMAL("正常", "一般两三句。只有对方明确要解释时才展开。"),
    CHATTY("话多", "可以说四五句，允许跑题、允许自己扯到别的事上。"),
}

/**
 * **主动程度** —— 它多久来找你一次。
 *
 * ⚠️ 只影响**频率**，不影响"该不该来"（那由 `:legacy` 的
 *   `ProactivePlanner` 判，避免用户把角色调成"每分钟都来"）。
 */
@Serializable
enum class PersonaInitiative(val label: String) {
    QUIET("很少主动"),
    NORMAL("正常"),
    CLINGY("爱找人说话"),
}

@Serializable
enum class PersonaGender(val label: String) {    UNSPECIFIED("不指定"),
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

    /**
     * 这个人的**毛病** —— 「不完美模拟」的燃料。
     *
     * ═══════════════════════════════════════════════════════════
     *  ⚠️ 为什么这里用字符串而不是 `:legacy` 的 `Flaw` 类型
     * ═══════════════════════════════════════════════════════════
     *
     * `:legacy` 依赖 `:personalogic`（未亡人模式建立在人格之上），
     * 反过来不行 —— 那会成环。
     *
     * ⇒ 这里存**原始文本**（每行一条，格式 `标签：具体表现`），
     *   由 `:legacy` 侧解析成 `Flaw`。解析失败的行**跳过而不是崩** ——
     *   用户随手写的一行不该让整个角色存不下来。
     */
    val flawsRaw: List<String> = emptyList(),

    /** 头像。null = 用名字首字生成的占位 */
    val avatarRes: String? = null,

    // ══════════════════════════════════════════════════════════════
    //  ★★ 2026-10-28：让角色"立体"的第二组字段
    // ══════════════════════════════════════════════════════════════
    //
    // 用户原话："除了名字、人物设定、与用户的关系、怎么称呼用户、口癖、
    // 以及毛病外，还要加入哪些，才能让角色的立体度起来，多维起来，
    // 而且说话办事的风格越来越像人，而没有机械感的 AI 味？"
    //
    // ★ 判断标准只有一条：**这一栏能不能改变它在某一轮里说的话？**
    //   改不了的栏位只是装饰 —— 填了会让用户以为有效果，实际什么都没变，
    //   那比不能填更糟。
    //
    // 按"最能去掉 AI 味"排序：
    //   ① 说话长度（AI 味的头号来源是"每次都三段论"）
    //   ② 日常（它此刻在干嘛 —— AI 永远"随时在线"）
    //   ③ 兴趣与雷点（人有所不谈）
    //   ④ 共同经历（只有你俩懂的梗 —— 这是"像人"最强的一维）
    //   ⑤ 主动程度（决定它多久来找你）
    //   ⑥ 作息（凌晨还精神抖擞是 AI；人困了会敷衍）

    /**
     * 说话长度偏好。
     *
     * ⚠️ AI 味最重的不是用词，是**篇幅**：不管问什么都回三段。
     *   真人回微信大多是一两句，偶尔才长篇。
     */
    val verbosity: PersonaVerbosity = PersonaVerbosity.NORMAL,

    /**
     * 它自己的**日常** —— 此刻在干嘛、平时忙什么。
     *
     * ★ 这一栏解决的是"AI 永远在线"那种违和感：
     *   一个刚下班的人，和一个凌晨三点还在秒回的人，是两种生物。
     *   填了它，模型才可能在回答里带出"我刚从地铁上来"这种真实感。
     */
    val dailyLife: String = "",

    /** 爱聊的话题。空 = 什么都聊 */
    val interests: List<String> = emptyList(),

    /**
     * **不谈的话题 / 会不高兴的点**。
     *
     * ★ 人有所不谈。一个什么都愿意聊、什么都不生气的角色，
     *   不是好脾气，是没有性格。
     */
    val taboos: List<String> = emptyList(),

    /**
     * **只有你们俩懂的梗**（共同经历的指代）。
     *
     * ★ 这是"像人"最强的一维：一句外人听不懂的暗号，
     *   比十行形容词更能让一段关系显得真实存在过。
     */
    val sharedReferences: List<String> = emptyList(),

    /** 主动程度。决定它多久来找你一次（配合 `ProactivePlanner`）。 */
    val initiative: PersonaInitiative = PersonaInitiative.NORMAL,

    /**
     * 作息：几点睡、几点起（0-23）。
     *
     * ⚠️ 只影响**语气**（困了会敷衍、刚醒会短），
     *    不影响"能不能回" —— 用户随时可以找它，它不许装睡不理人。
     */
    val sleepHour: Int = 23,
    val wakeHour: Int = 7,
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
        // ⚠️ 毛病**不在**这段里展开 —— 它由 `:legacy` 的 `ConflictScript.flawsBrief`
        //    单独注入，因为那段要说明"平时不用主动提"。
        //    这里只留一句"这个人不完美"，避免模型把他演成一个完人。
        if (flawsRaw.isNotEmpty()) {
            append("你也有你的毛病，不是个完人 —— 具体是什么，会在相关的时候自然显现。")
        }
        // ── 立体度的第二组（2026-10-28）────────────────────────
        if (dailyLife.isNotBlank()) {
            append("你平时").append(dailyLife.trimEnd('。')).append("。")
        }
        if (interests.isNotEmpty()) {
            append("你爱聊").append(interests.joinToString("、")).append("。")
        }
        if (taboos.isNotEmpty()) {
            // ★ 写法很重要：不能写成"你不能聊X"（那像系统限制），
            //   要写成"你不爱聊"（那是性格）。同一件事，两种说法在模型那里
            //   会得到完全不同的表演。
            append("你不爱聊").append(taboos.joinToString("、"))
            append("，被问到时你会岔开，或者直接说不聊这个。")
        }
        if (sharedReferences.isNotEmpty()) {
            append("你和对方之间有些只有你们懂的说法：")
            append(sharedReferences.joinToString("、") { "「$it」" })
            append("。提到的时候自然带出来，不要解释它是什么。")
        }
        append("你说话").append(verbosity.promptHint)
        if (sleepHour != wakeHour) {
            append("你的作息是 ${wakeHour} 点到 ${sleepHour} 点清醒 —— ")
            append("在那个时间段之外你会困，回得会短、会含糊，但**不会不理人**。")
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
