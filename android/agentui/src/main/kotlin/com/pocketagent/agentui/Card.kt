package com.pocketagent.agentui

/**
 * 卡片的身份。
 *
 * ★ 用字符串而不是自增 int：时间线会被**导出与回放**（`TimelineExport`），
 *   而回放时 id 必须跨进程稳定 —— 自增 id 在第二次打开 App 时会从头再来，
 *   于是"回放出来的卡片"和"当初那张卡"对不上，用户看到的是一条假历史。
 */
@JvmInline
value class CardId(val value: String) {
    init {
        require(value.isNotBlank()) { "CardId 不能为空" }
    }

    override fun toString(): String = value
}

/** 卡片的状态。 */
enum class CardState {
    /** 草案：还没执行（预演卡停在等用户点头）。 */
    DRAFT,
    RUNNING,
    DONE,

    /** 被安全规则或用户主动拒绝。 */
    BLOCKED,
    FAILED,

    /**
     * 被取消（用户接管、任务结束、上游中止）。
     *
     * ★ 必须有这个状态，不能复用 [FAILED]：取消是"我们主动不做了"，
     *   失败是"做不成"。混在一起，用户在复盘时看到一排"失败"，
     *   会以为助理连试都没试成功过 —— 而他明明是自己按停的。
     *
     * ★ 更要紧的是它**不能不存在**：用户按了「我来」之后，正在跑的那张卡
     *   如果还显示"进行中"，就是本项目最忌讳的形态 ——
     *   界面在说"它还在动"，而它已经停了。
     */
    CANCELLED,
    ;

    /** 终态：一旦进入，任何后续事件都不该再改它。 */
    val isTerminal: Boolean
        get() = this == DONE || this == BLOCKED || this == FAILED || this == CANCELLED
}

/** 一条证据的来源。 */
enum class EvidenceKind {
    /** 屏幕上确实看到了这个节点（node_id / bounds）。 */
    NODE_REF,

    /** 命中了某条安全规则或人格规则。 */
    RULE_HIT,

    /** 来自用户本人的那句话。 */
    USER_INSTRUCTION,

    /** 模型自己说的 —— ★ 这一条**不算解释**，只算"它的说法"。 */
    MODEL_CLAIM,
}

/**
 * 一条证据。
 *
 * ★ 为什么要区分 [EvidenceKind.MODEL_CLAIM]：如果"解释"允许用模型自己的话
 *   复述一遍，那么解释永远编得出来，而用户永远无法分辨真假。
 *   把"模型声称"单列一类，界面才能如实标出"这句是它说的，不是我们查到的"。
 */
data class Evidence(val kind: EvidenceKind, val detail: String) {
    init {
        require(detail.isNotBlank()) { "证据内容不能为空 —— 空证据等于没有证据" }
    }
}

/** 卡片的类型。导出与回放要用它，所以不能只靠 `is` 判断。 */
enum class CardKind { INTENT, ACT, OBS, SAY, TROUBLE }

/**
 * 时间线上的一张卡。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 卡片是**投影**，不是上下文
 * ═══════════════════════════════════════════════════════════════
 *
 * 模型看到的始终只有工具返回的纯文本；卡片是从同一份数据**另外**渲染出来的。
 * 所以卡片再丰富也不会多烧一个 token —— 这条是 `:agentui` 存在的**前提**，
 * 一旦有哪张卡的内容反过来被塞回 prompt，整个设计就失去意义了。
 */
sealed interface Card {
    val id: CardId
    val at: Long
    val state: CardState
    val evidence: List<Evidence>

    val kind: CardKind

    /**
     * 我打算做什么 —— **预演**。
     *
     * ★ 只在「高风险」或「不可逆」时出现（见 [IntentGate]）。低风险动作也预演
     *   等于每一步都多一次点击，用户会把它关掉，然后连真正危险的那次也看不到。
     */
    data class Intent(
        override val id: CardId,
        override val at: Long,
        override val state: CardState,
        override val evidence: List<Evidence>,
        /** 一句话：我准备做什么。 */
        val action: String,
        /** 作用对象（应用名 / 联系人 / 文件），可为 null。 */
        val target: String?,
        val risk: RiskLevel,
        /** 这一步做完能不能反悔。 */
        val reversible: Boolean,
        /**
         * 用户的答复原话（"不要，那个群不对"）。
         *
         * ★ 必须存下来：用户否决之后，复盘里只剩一个"已取消"，
         *   他就无法解释自己当时为什么改主意 —— 而下次遇到同样的情形，
         *   助理还会再问一遍同样的问题。
         */
        val note: String? = null,
    ) : Card {
        override val kind: CardKind get() = CardKind.INTENT

        init {
            require(action.isNotBlank()) { "预演卡必须有动作描述" }
            require(action.length <= ACTION_MAX_CHARS) {
                "预演动作描述过长（${action.length} > $ACTION_MAX_CHARS）"
            }
        }

        companion object {
            const val ACTION_MAX_CHARS = 80
        }
    }

    /** 我做了什么 —— 一次工具调用。 */
    data class Act(
        override val id: CardId,
        override val at: Long,
        override val state: CardState,
        override val evidence: List<Evidence>,
        val tool: String,
        /** 一句话摘要，界面上永远只显示这一行。 */
        val summary: String,
        /** 原始返回文本，只在用户点开时显示。 */
        val detail: String,
        val channel: String?,
        val latencyMs: Long?,
        /**
         * 不确定性说明。非 null 表示"这一次我们无法确认结果"。
         *
         * ★ 盲点点击（坐标处没有可识别元素）必须填这个字段，且文案必须
         *   如实说明"无法确认点到了什么"。这不是可选的美化 ——
         *   界面上写着"已点击"而实际不知道点到了哪里，是**欺骗**。
         */
        val uncertainty: String?,
    ) : Card {
        override val kind: CardKind get() = CardKind.ACT

        init {
            require(tool.isNotBlank()) { "动作卡必须有工具名" }
            require(summary.isNotBlank()) { "动作卡必须有摘要" }
            require(summary.length <= SUMMARY_MAX_CHARS) {
                "摘要过长（${summary.length} > $SUMMARY_MAX_CHARS）：摘要是一行，不是一段"
            }
        }

        companion object {
            const val SUMMARY_MAX_CHARS = 60
        }
    }

    /** 我看到了什么 —— 一次观察（读屏 / 截图摘要）。 */
    data class Obs(
        override val id: CardId,
        override val at: Long,
        override val state: CardState,
        override val evidence: List<Evidence>,
        val summary: String,
        /** 节点数；0 表示空屏或读不到。 */
        val nodeCount: Int,
        /** 是否含被遮蔽的内容（输入框、验证码等）。 */
        val redacted: Boolean,
    ) : Card {
        override val kind: CardKind get() = CardKind.OBS

        init {
            require(summary.isNotBlank()) { "观察卡必须有摘要" }
            require(nodeCount >= 0) { "节点数不能为负" }
        }
    }

    /** 我说了什么。 */
    data class Say(
        override val id: CardId,
        override val at: Long,
        override val state: CardState,
        override val evidence: List<Evidence>,
        val text: String,
    ) : Card {
        override val kind: CardKind get() = CardKind.SAY
    }

    /**
     * 出问题了。
     *
     * ★ 硬约束：**出口至少两个**。只给一句错误原文，用户唯一能做的就是关掉它 ——
     *   而"关掉"不是出口，是放弃。构造期 `require` 拦死，不靠文案纪律。
     */
    data class Trouble(
        override val id: CardId,
        override val at: Long,
        override val state: CardState,
        override val evidence: List<Evidence>,
        /** 是哪张卡出了问题。★ 有了它，问题卡是"补救"，不是"重复一遍失败"。 */
        val about: CardId,
        val gate: Gate,
        /** 用户能读懂的原因（不是堆栈）。 */
        val reason: String,
        val exits: List<Exit>,
    ) : Card {
        override val kind: CardKind get() = CardKind.TROUBLE

        init {
            require(reason.isNotBlank()) { "问题卡必须有原因" }
            require(exits.size >= 2) {
                "问题卡至少要两个出口（当前 ${exits.size} 个）—— 只给一个出口等于逼用户放弃"
            }
            require(exits.distinct().size == exits.size) { "问题卡不能有重复出口" }
            // ★ 安全拒绝不许给"重试"：那一步永远做不成，给了重试就是在骗用户去撞墙。
            require(!(gate == Gate.SAFETY && Exit.Retry in exits)) {
                "安全拒绝的出口里不能有「重试」—— 重试必然再次被拒"
            }
        }
    }
}

/**
 * 三道门。
 *
 * ★ 三者的**文案与出口必须不同**：混成一句"失败了"，用户永远不知道该找谁 ——
 *   是安全规则不让做（找他没用）？是他自己刚才点了"不要"（他自己能改）？
 *   还是技术故障（重试就行）？
 */
enum class Gate {
    /** 安全规则硬拒绝 —— 重试没有意义，所以这一门的出口里**没有**重试。 */
    SAFETY,

    /** 用户拒绝授权 —— 他能改，所以出口是"重新授权"。 */
    PERMISSION,

    /** 执行失败 —— 技术故障，可重试。 */
    EXECUTION,
}

/** 问题卡上的一个出口。 */
sealed interface Exit {
    /** 再试一次。 */
    data object Retry : Exit

    /** 换一种做法（label 说明换什么，不能是"换个方法"这种空话）。 */
    data class Alternative(val label: String) : Exit {
        init {
            require(label.isNotBlank()) { "替代出口必须说明换什么" }
        }
    }

    /** 我来：用户接管，助理给操作提示。 */
    data object TakeOver : Exit
}
