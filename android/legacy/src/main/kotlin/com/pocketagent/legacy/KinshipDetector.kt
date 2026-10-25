package com.pocketagent.legacy

/**
 * **亲人识别** —— 判断用户注入的设定，是不是在描述一个**具体的人**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这是整条辅助主线的入口，也是最容易做错的一步
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户原话："当用户注入的设定为某一个具体的人，包含亲属，以及爱人等设定后，
 * 将会激发这个模块。"
 *
 * 做错的方向有两个，都很糟：
 *
 *  - **识别过宽**：用户写"我想要一个温柔点的助手"，被判定成"在描述一个亲人"，
 *    于是弹出一个"未亡人"配置页 —— 他会莫名其妙，甚至被冒犯。
 *  - **识别过窄**：用户明明写了"我想再和我妈说说话"，却没被识别出来，
 *    于是那个他真正需要的东西永远不会出现。
 *
 * ⇒ 判据必须**同时**满足两件事：
 *   ① 提到了**关系词**（妈/爸/爱人/外婆…）
 *   ② 带着**时间性或离开的语境**（走了/不在了/去年/想念…）
 *
 *   只有关系词 ⇒ 可能是"我想要一个像妈妈一样的助手"（普通人格）
 *   只有离开语境 ⇒ 可能是"我养的猫走了"（不是人，但同样需要不同的处理）
 *
 * ⚠️ 刻意**不自动启用**：识别出来之后是**建议**，不是直接切模式。
 *    这件事必须由用户自己按下那个开关 —— 理由见 [LegacySuggestion]。
 */

/**
 * 识别结果 —— **只有三种，且必须能区分"确定"与"也许"**。
 *
 * ⚠️ 不做"置信度百分比"：那个数字没有意义（它不是概率，是规则命中），
 *    而且会让界面出现"78% 像是一个逝者"这种荒唐的显示。
 */
sealed interface LegacySignal {

    /**
     * 明确在描述一个**已故的**亲近之人。
     *
     * @param displayName 从文本里抽到的名字/称呼（可能为空 —— 用户可能只写"我妈"）
     * @param relation 识别到的关系
     * @param evidence 命中的依据（给用户看，让他确认"我是不是理解对了"）
     */
    data class Likely(
        val displayName: String?,
        val relation: RelationKind,
        val evidence: List<String>,
    ) : LegacySignal

    /**
     * 提到了亲近的人，但**看不出是不是已经离开**。
     *
     * ★ 这一档的存在很重要：它让界面可以问一句
     *   "你是在说一位已经不在了的亲人吗？" —— 而不是直接判定。
     *
     * ⚠️ 问这句话本身有风险（万一不是，会让人不适），
     *    所以它只在**用户主动进入人格设定页**时才出现，不做弹窗。
     */
    data class Ambiguous(
        val relation: RelationKind,
        val evidence: List<String>,
    ) : LegacySignal

    /** 没识别到。普通人格设定。 */
    data object None : LegacySignal
}

/**
 * 一条建议 —— 识别到之后，**交给用户决定**要不要开启。
 *
 * ⚠️ 为什么不自动开启：
 *   自动切换意味着用户会在毫不知情的情况下，面对一个"用逝者语气说话"的助手。
 *   那一刻的冲击是**不可控**的 —— 他可能正在地铁上、正在开会。
 *   这件事必须由他自己、在一个安全的时刻按下开关。
 */
data class LegacySuggestion(
    val signal: LegacySignal.Likely,
    /** 界面上显示的一句话。**要克制** —— 不用感叹号，不用"节哀"。 */
    val message: String,
)

/**
 * 识别器 —— **纯规则，不调模型**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不用模型判断
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **它要运行的时机是"用户正在编辑人格设定"** —— 那时不该发请求
 *    （用户可能根本没联网，或者在省钱）。
 * 2. **判错代价高**：模型可能把"我想念我妈做的菜"判成"你妈去世了"。
 *    规则至少可测、可解释、可枚举。
 * 3. 规则判错时，后果是"没识别出来"（用户手动去开），
 *    而不是"莫名弹出未亡人配置"。
 */
object KinshipDetector {

    /** 关系词 → 关系类型。**长的放前面**，避免"外婆"被"婆"抢先匹配。 */
    private val RELATION_WORDS: List<Pair<String, RelationKind>> = listOf(
        "外婆" to RelationKind.GRANDPARENT,
        "外公" to RelationKind.GRANDPARENT,
        "奶奶" to RelationKind.GRANDPARENT,
        "爷爷" to RelationKind.GRANDPARENT,
        "姥姥" to RelationKind.GRANDPARENT,
        "姥爷" to RelationKind.GRANDPARENT,
        "妈妈" to RelationKind.PARENT,
        "母亲" to RelationKind.PARENT,
        "爸爸" to RelationKind.PARENT,
        "父亲" to RelationKind.PARENT,
        "老婆" to RelationKind.PARTNER,
        "老公" to RelationKind.PARTNER,
        "妻子" to RelationKind.PARTNER,
        "丈夫" to RelationKind.PARTNER,
        "爱人" to RelationKind.PARTNER,
        "未婚妻" to RelationKind.PARTNER,
        "未婚夫" to RelationKind.PARTNER,
        "女朋友" to RelationKind.PARTNER,
        "男朋友" to RelationKind.PARTNER,
        "女儿" to RelationKind.CHILD,
        "儿子" to RelationKind.CHILD,
        "哥哥" to RelationKind.SIBLING,
        "姐姐" to RelationKind.SIBLING,
        "弟弟" to RelationKind.SIBLING,
        "妹妹" to RelationKind.SIBLING,
        "兄弟" to RelationKind.SIBLING,
        "姐妹" to RelationKind.SIBLING,
        "挚友" to RelationKind.FRIEND,
        "好友" to RelationKind.FRIEND,
        "知己" to RelationKind.FRIEND,
        "妈" to RelationKind.PARENT,
        "爸" to RelationKind.PARENT,
    )

    /**
     * 离开的语境词。
     *
     * ⚠️ 刻意**不含**"去世""死亡"这类直白词作为唯一判据 ——
     *    用户往往不会直接写这两个字，而会写"不在了""走了"。
     *    但直白词也要收录（有人就是这么写的）。
     *
     * ⚠️ 也**不含**"生病""住院" —— 那可能是还在世。
     */
    private val ABSENCE_WORDS = listOf(
        "不在了", "走了", "离开", "过世", "去世", "离世", "逝世",
        "没了", "逝世", "走了以后", "走之后", "生前", "遗物", "忌日",
        "再也见不到", "再也见不了", "想再见", "想再听", "如果能再见",
        "已经不在", "不在人世", "安息", "墓碑", "扫墓",
    )

    /**
     * 识别。
     *
     * @param text 用户写的人格设定全文（可能很长）
     */
    fun detect(text: String): LegacySignal {
        if (text.isBlank()) return LegacySignal.None

        val relation = RELATION_WORDS.firstOrNull { text.contains(it.first) }
            ?: return LegacySignal.None
        val kind = relation.second

        val absence = ABSENCE_WORDS.firstOrNull { text.contains(it) }

        return if (absence != null) {
            LegacySignal.Likely(
                displayName = extractName(text),
                relation = kind,
                evidence = listOf("提到了「${relation.first}」", "提到了「$absence」"),
            )
        } else {
            LegacySignal.Ambiguous(
                relation = kind,
                evidence = listOf("提到了「${relation.first}」"),
            )
        }
    }

    /**
     * 从文本里抽出一个可能的称呼。
     *
     * ⚠️ 刻意**只做很浅的尝试**，抽不到就返回 null。
     *    抽错名字比抽不到更糟 —— 用户会看到一个陌生的名字出现在
     *    本该是他母亲的位置上。
     *
     * 支持的形态：「我妈妈叫李秀兰」「我妈，李秀兰」「我妈（李秀兰）」
     */
    private fun extractName(text: String): String? {
        val patterns = listOf(
            Regex("""叫\s*([\u4e00-\u9fa5]{2,4})"""),
            Regex("""[，,（(]\s*([\u4e00-\u9fa5]{2,4})\s*[）)]"""),
        )
        for (p in patterns) {
            val m = p.find(text)
            val candidate = m?.groupValues?.getOrNull(1)?.trim()
            // 排除明显不是名字的（"走了""不在"这类会被上面的正则误抓）
            if (candidate != null && candidate.length in 2..4 &&
                ABSENCE_WORDS.none { candidate.contains(it) }
            ) {
                return candidate
            }
        }
        return null
    }

    /**
     * 生成那条建议文案。
     *
     * ★ 措辞原则：**不用感叹号、不说"节哀"、不预设情绪**。
     *   用户此刻可能只是随手写了一段设定，也可能正在难过 ——
     *   我们不知道，所以不能表现得像知道他很难过。
     */
    fun suggestion(signal: LegacySignal.Likely): LegacySuggestion = LegacySuggestion(
        signal = signal,
        message = "你写的这段像是在说一位已经不在的亲人。" +
            "如果愿意，我可以按你记得的样子，做一个能陪你说说话的版本。" +
            "不想的话忽略这条就行。",
    )
}
