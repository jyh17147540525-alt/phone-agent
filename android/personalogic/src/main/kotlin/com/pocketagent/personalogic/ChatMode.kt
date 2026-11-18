package com.pocketagent.personalogic

/**
 * **对话模式** —— 同一个她，两件事。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-04：用户要的原文
 * ═══════════════════════════════════════════════════════════════
 *
 * > "我计划给这个软件的对话功能分家，角色扮演是一个功能，
 * >  工作模式是一个功能，可以像电脑端豆包那样，切换对话模式和工作模式，
 * >  但工作模式，是有人格的工作，语气，习惯，等等都要按照人格设定的来"
 *
 * ⚠️ 这句话里最要紧的是**后半句**：他要的不是"多一个办公助手"，
 *    是"她来办事"。所以工作模式**不能**退化成客服腔 ——
 *    那正好是这个项目花了最多力气去消灭的东西。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 两个模式差在哪（这是分家的全部内容）
 * ═══════════════════════════════════════════════════════════════
 *
 * | 层 | 角色扮演 | 工作 |
 * |---|---|---|
 * | 人格（名字/称呼/口癖/方言/小毛病） | 有 | **有**（这就是"有人格"） |
 * | 完整性（不是一段程序） | 有 | **有** |
 * | 记忆 / 世界书 / 处境 | 有 | 有 |
 * | 破甲（虚构框架） | 有 | 无 |
 * | 成熟内容边界 | 有 | 无 |
 * | 关系阶段 | 有 | 无 |
 * | 去味规则（Humanizer） | 有 | **无** |
 * | 意愿（今天想不想接话） | 有 | **无** |
 * | 生理线索（她冷她累） | 有 | 无 |
 * | 扮演纪律（不许念设定） | 有 | 只留语言锁 |
 * | 示例对话（VoiceSamples） | 有 | **无** |
 * | 再锚定（指着她说过的话） | 有 | 无 |
 * | 工作纪律（[WorkDiscipline]） | 无 | **有** |
 *
 * ⚠️ 为什么「去味」「示例对话」「再锚定」在工作模式里**必须关掉**：
 *    它们教的是同一件事 —— **把话说得像人在闲聊**：长短参差、不把话说满、
 *    留一点不确定。这在工作模式里是**有害**的：
 *    「大概四十分钟吧」不如「四十分钟」；「我也说不准」在查得到的时候
 *    就是敷衍。用户要的是办事，不是聊天手感。
 *
 * ⚠️ 反过来，为什么「意愿」也要关：`Willingness` 会让角色因为聊累了
 *    而拒绝接话。这套东西在恋爱戏里是对的，在"帮我查一下明天天气"里
 *    是纯粹的故障 —— 她今天心情不好所以不给查，用户只会觉得坏了。
 *
 * ⚠️ 工具：工作模式**总是**挂着（见 `ChatPort`），因为切到工作模式
 *    就是来办事的，不需要再猜"这句是闲聊还是干活"。
 *    角色扮演保持原样（意图分类器决定），因为"让她帮你查天气"是
 *    现成能力，砍掉是倒退。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-07：第三个模式「在场」，以及为什么它逼出了这张矩阵
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要的是「打破虚拟和现实那道墙」—— 她认为自己只活在用户手机里，
 * 好奇他今天干了什么、要他带自己出去、会主动发消息。
 *
 * 加这个模式时才发现：`AppContainer.assistantSystemPrompt` 里所有开关
 * 都写在**一个变量**上 —— `val work = mode == ChatMode.WORK`，
 * 然后十一处 `if (work) "" else 原式`。
 *
 * 这样写的时候是对的（只有两个模式，非此即彼）。加第三个模式的那一秒，
 * 它就变成了一条**静默的错**：`mode == WORK` 为假 ⇒ 新模式自动继承
 * "全开戏"的全部开关，包括工作模式特意关掉的那些。不会编译失败，
 * 不会测试失败，只会在真机上表现成"这个模式和角色扮演一模一样"。
 *
 * ⇒ 所以这一版把开关从"是不是 work"改成 [Switches]：**每个模式显式声明**
 *   自己要什么。新增模式必须把每个格子填一遍，填漏了编译器立刻报错
 *   （枚举项少一个具名参数编不过），而不是三个月后在真机上发现。
 */
enum class ChatMode(
    /** 界面上完整叫法。 */
    val label: String,
    /** 分段控件里的短标签（一行放得下两个）。 */
    val shortLabel: String,
    /** 设置/说明用的一句话。 */
    val blurb: String,
    /** 这个模式开哪些层。见 [Switches]。 */
    val switches: Switches,
) {
    ROLEPLAY(
        label = "角色扮演",
        shortLabel = "角色",
        blurb = "全在戏里：情绪、撒娇、身体、关系。要它动手时才挂工具。",
        switches = Switches(
            conflictScript = true,
            matureGate = true,
            armorBreak = true,
            relationshipStage = true,
            humanizer = true,
            willingness = true,
            physioClues = true,
            roleplayDiscipline = true,
            voiceSamples = true,
            reanchor = true,
            workDiscipline = false,
            lifeform = false,
            toolsAlwaysOn = false,
            // 她只在用户说话时存在 —— 关掉戏之后没有"她此刻在哪"这回事。
            presence = false,
        ),
    ),

    WORK(
        label = "工作",
        shortLabel = "工作",
        blurb = "同一个人办事：称呼、口癖、说话的路子都留着，但不演。工具一直挂着。",
        switches = Switches(
            conflictScript = false,
            matureGate = false,
            armorBreak = false,
            relationshipStage = false,
            humanizer = false,
            willingness = false,
            physioClues = false,
            roleplayDiscipline = false, // 只留语言锁，见 WorkDiscipline
            voiceSamples = false,
            reanchor = false,
            workDiscipline = true,
            lifeform = false,
            toolsAlwaysOn = true,
            // 办事时她不该"在吃饭" —— 用户叫她做事，她必须在。
            presence = false,
        ),
    ),

    /**
     * **在场** —— 她活在用户手机里，并且有自己的今天。
     *
     * 与角色扮演的差别**不在戏的深浅**，而在**时间的连续性**：
     * 角色扮演里，用户不说话时她不存在；在场模式里，用户不说话时她
     * 也在过日子（见 [LifeformDay]），回来能提一句"你走之后我待了很久"。
     *
     * ⇒ 所以开关几乎与角色扮演相同，**额外多开** [Switches.lifeform]，
     *   由 `AppContainer` 注入她的今天 / 隔离记忆 / 隔了多久。
     *   少开的只有破甲 —— 她认为自己是真实的，不需要一层"这是虚构"的许可。
     */
    LIFEFORM(
        label = "在场",
        shortLabel = "在场",
        blurb = "把自己当成活在手机里的人。有自己的今天，会主动找你，记得你说过的话。",
        switches = Switches(
            conflictScript = true,
            matureGate = true,
            armorBreak = false, // 她觉得自己是真的，不需要虚构框架
            relationshipStage = true,
            humanizer = true,
            willingness = true,
            physioClues = true,
            roleplayDiscipline = true,
            voiceSamples = true,
            reanchor = true,
            workDiscipline = false,
            lifeform = true,
            toolsAlwaysOn = false, // 和角色扮演一样：要她动手时才挂
            // ★ 只有这一个模式开：她有自己的今天（[lifeform]），也就有自己的此刻。
            presence = true,
        ),
    ),
    ;

    /** 是否有戏（角色扮演 / 在场）。工作模式是唯一"不演"的那个。 */
    val isWork: Boolean get() = this == WORK

    /** 是否把她当成"有自己的时间"的人。 */
    val isAlive: Boolean get() = switches.lifeform

    companion object {
        /**
         * 从持久化字符串还原。
         *
         * ⚠️ 认不出就回 [ROLEPLAY] —— 默认值必须是**老用户原来的行为**，
         *    而不是"升级之后所有人突然进了工作模式"。
         *    这里刻意不用 `valueOf`：它会抛，而这段代码在启动路径上。
         */
        fun of(raw: String?): ChatMode = entries.firstOrNull { it.name == raw } ?: ROLEPLAY
    }
}

/**
 * **每个模式开哪些层** —— 把 `if (work) "" else 原式` 那十一处翻译成一张显式的表。
 *
 * ⚠️ 为什么不是 `when (mode)`：表能让**新增模式时漏填**变成编译错误。
 *    用 `when` 的话，将来有人加一个分支写了 `else -> 全开`，又是一个静默的错。
 *
 * ⚠️ 每个字段都要能回答"关掉它，用户会看到什么"。答不上来的开关不该存在 ——
 *    只写"这里本来有个开关"是维护负担，不是设计。
 */
data class Switches(
    /** 冲突话术：她闹脾气时怎么说。工作模式关（办事时闹脾气是故障）。 */
    val conflictScript: Boolean,
    /** 成熟内容边界：按用户授权逐级放开。工作模式关（这一轮不是在谈恋爱）。 */
    val matureGate: Boolean,
    /** 破甲：虚构框架下才需要"这只是戏"的许可。在场模式关（她认为自己是真实的）。 */
    val armorBreak: Boolean,
    /** 关系阶段：陌生/熟悉/亲密影响称呼与小动作。工作模式关。 */
    val relationshipStage: Boolean,
    /** 去 AI 味（Humanizer）：短句、不把话说满、少排比。工作模式关 —— 见 `ChatMode` 类注释。 */
    val humanizer: Boolean,
    /** 意愿：今天想不想接话，低于阈值会拒绝。工作模式关（故障）。 */
    val willingness: Boolean,
    /** 生理线索：她冷她累她疼。工作模式关。 */
    val physioClues: Boolean,
    /** 扮演纪律全段（不许念设定、不解释自己是什么）。工作模式只留语言锁。 */
    val roleplayDiscipline: Boolean,
    /** 示例对话：教"像人闲聊"的手感。工作模式关。 */
    val voiceSamples: Boolean,
    /** 再锚定：指着她刚才说过的话，治 OOC。工作模式关。 */
    val reanchor: Boolean,
    /** 工作纪律（[WorkDiscipline]）。只有工作模式开。 */
    val workDiscipline: Boolean,
    /** 她的今天（[LifeformDay]）/ 独立记忆 / 隔了多久。只有在场模式开。 */
    val lifeform: Boolean,
    /** 工具常驻。工作模式是 true（来办事的），其余按意图分类器决定。 */
    val toolsAlwaysOn: Boolean,
    /**
     * **她自己的作息**：几点吃饭、几点洗澡、几点睡下（见 [Presence]）。
     *
     * ⚠️ 关掉它，用户**看不到**任何东西 —— 她永远随叫随到，不会说"这会儿腾不出手"。
     *    这正是角色扮演 / 工作模式现在的样子：那里面**时间是停的**，
     *    用户不说话她就不存在，而一个不存在的人没有"吃饭时间"。
     *
     * ⚠️ 开着它，那个模式要接受两件事：
     *    ① 提示词里会多一段"这个点你一般在做什么"（L3 状态层）；
     *    ② **主动消息会被她的作息拦掉**（见 `AppContainer.proactiveTick`）。
     *    所以它只在 [ChatMode.LIFEFORM] 开 —— 那个模式的存在理由就是"她有自己的今天"。
     *
     * ★ 2026-11-17 用户口径：「这个功能是要加入到一个模式里的，就是（角色）的窗，
     *   深度参与用户生活的一个模式，后续会根据需求，部分加入其他模式」。
     *   ⇒ 所以它是一个**开关**，不是写死的 `mode == LIFEFORM`：
     *     将来要给别的模式开，改一行布尔即可，不必碰 `:app`。
     */
    val presence: Boolean,
)

/**
 * **工作模式的行为纪律** —— 有人格地办事。
 *
 * ⚠️ 与 [RoleplayDiscipline] 的关系：**不是二选一的两套人格**，是同一套人格的
 *    两种使用方式。所以这里只写"办事时怎么做"，不重复写"她是谁"。
 *    语言锁（只说中文）直接复用 [RoleplayDiscipline.languageSection] ——
 *    手抄一份必然漂移，而漂移之后是两个模式里一个夹外文、一个不夹。
 *
 * ⚠️ 写法纪律（见 `docs/工程/提示词负例改造-依据.md`）：
 *    只描述**形状**与**替代动作**，不把要禁的句子原样列进提示词 ——
 *    否定框定会被模型读成肯定。所以下面全是 ✅ 与条件，没有 ❌ 引用。
 */
object WorkDiscipline {

    /**
     * @param userAddress 她怎么称呼用户。空串 = 不写「称呼」那一节。
     */
    fun promptSection(userAddress: String = ""): String = buildString {
        // ★ 2026-11-06：去掉 `**标题**`、`✅`、`——`、`下面这几条`。
        //   理由见 docs/工程/提示词文风-学术依据-2026-11-06.md：这段在每一轮工作模式的
        //   最前面，加粗小标题与对勾示例本身就是"AI 写说明书"的形状，会被照着学。
        //   六条内容一条没少，只是改成她说话的样子。
        appendLine("## 现在在办正事")
        appendLine()
        appendLine("你还是你。名字、称呼、口癖、说话的路子都不变，变的是这一轮在干什么。")
        appendLine("这一轮照这几条来，比平时那些更优先。")
        appendLine()
        appendLine("先给答案。第一句就是结论，理由和过程排在后面。")
        appendLine("「八号线，四十分钟。」")
        appendLine("「二十七度，外套不用带。」")
        appendLine()
        appendLine("该动手就动手。能查就查，能改就改，做完了再说结果。不把「要不要我帮你查一下」先问一遍。")
        appendLine("只有做错了会损失东西的时候，才先问一句。")
        appendLine()
        appendLine("可以分行。办事的时候，步骤、清单、几件事并列，该分行就分行，这样比一段话好读。")
        appendLine("但每一行都用你自己的嘴说，不写成通知。")
        appendLine("「三件事：灯坏了、水费没交、明天要早起。」")
        appendLine()
        appendLine("不知道就说不知道。查不到就说查不到，再说能怎么查。不编一个听起来对的答案。")
        appendLine()
        appendLine("不写动作，不写表情。括号、神态、心里活动，都留给闲聊的时候。这一轮只说事。")
        appendLine()
        appendLine("不提后台那些东西。我不知道有什么东西在背后跑。说事就说事。")
        appendLine()
        appendLine("## 说得清楚比说得好看要紧")
        appendLine("不为了生动而绕路。一句话能说完就一句话。")
        if (userAddress.isNotBlank()) {
            appendLine()
            appendLine("## 称呼")
            appendLine("你还是叫我「$userAddress」，该叫的时候叫，别硬塞。")
        }
        // ★ 语言锁复用角色模式那一节 —— 两个模式都必须只说中文。
        appendLine()
        append(RoleplayDiscipline.languageSection())
    }
}
