package com.pocketagent.agentui

/**
 * 把时间线导出成可读、可回放的文本。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么导出格式要**自己**定义，而不是随手 toString
 * ═══════════════════════════════════════════════════════════════
 *
 * 导出的东西有两个用途，且都会被人当真：
 *  ① 用户想知道"它刚才到底干了什么"（贴给客服 / 贴进反馈）；
 *  ② 我们想复现一个 bug（"同一串事件再跑一遍"）。
 *
 * 如果格式不稳定（顺序不定、字段缺失、时间格式化依赖本地时区），
 * 那么 ② 永远复现不了，① 则会让用户贴出一段他自己也看不懂的东西。
 *
 * ⟹ 两种导出**各管一件事，绝不共用**：
 *   · [toMarkdown] 给人看 —— 允许中文，允许"说：""看到："
 *   · [toReplay]   给机器读 —— 不许出现**界面文案词汇**
 *
 * ★ 为什么回放里不许有界面文案（这条是真踩过的坑）：
 *   最初 [toReplay] 直接复用了给人看的 `line()`，于是回放串里混进了
 *   "·完成""·进行中""·已拦下"这类**界面措辞**。后果不是"不好看"：
 *     · 措辞一改（"进行中"→"执行中"），历史回放**全部作废**；
 *     · 回放器必须匹配中文才能解析，等于把 UI 文案焊进了协议；
 *     · 最坏的一种：文案翻译成英文后旧回放**静默解析失败**，
 *       而解析失败通常被实现成"跳过这一行"，于是复盘少几条卡却看不出来。
 *   ⇒ 回放只放**事实**：枚举名、原始文本、数字。人话在 UI 层生成。
 *
 * ⚠️ 注意"不许有界面文案"≠"不许有汉字"。用户原话（"给妈发个消息"）、
 *    工具返回原文（"已派发，通道=ACCESSIBILITY"）必须**逐字保留** ——
 *    把它们洗掉，复盘就成了编故事。禁的是 [stateMark] / [riskLabel] /
 *    [pauseLabel] / [exitLabel] 这一小撮**我们自己造的措辞**。
 *
 * ★ 有编码函数就必须有解码函数：只有 [toReplay] 而没有 [parseReplay] 时，
 *   "可回放"只是句口号 —— 转义规则从未被验证过，而转义写错的后果是
 *   **静默截断**（字段被多切一刀，解析方拿到半句话却不报错）。
 */
object TimelineExport {

    /** 回放格式的版本号。字段含义一旦变化必须同步 +1。 */
    const val REPLAY_VERSION: Int = 1

    // ── 给人看 ─────────────────────────────────────────────────────

    /** 导出为 Markdown（给用户看）。 */
    fun toMarkdown(timeline: Timeline, title: String = "任务复盘"): String {
        val sb = StringBuilder()
        sb.append("# ").append(title).append('\n')
        if (timeline.pause != null) {
            sb.append('\n').append("> 已暂停：").append(pauseLabel(timeline.pause)).append('\n')
        }
        sb.append('\n')
        timeline.cards.forEachIndexed { index, card ->
            sb.append(index + 1).append(". ").append(line(card)).append('\n')
            val detail = detailOf(card)
            if (detail != null) {
                sb.append("   - ").append(detail).append('\n')
            }
            if (card.evidence.isNotEmpty()) {
                sb.append("   - 依据：").append(card.evidence.joinToString("；") { evidence(it) }).append('\n')
            }
        }
        return sb.toString()
    }

    /** 一句话概括这张卡（Markdown 用，允许中文）。 */
    fun line(card: Card): String = when (card) {
        is Card.Intent -> {
            val target = card.target?.let { " → $it" } ?: ""
            "准备：${card.action}$target（${riskLabel(card.risk)}）"
        }
        is Card.Act -> {
            val ms = card.latencyMs?.let { " ${it}ms" } ?: ""
            "[${card.tool}] ${card.summary}$ms ${stateMark(card.state)}"
        }
        is Card.Obs -> "看到：${card.summary}（${card.nodeCount} 个节点）"
        is Card.Say -> "说：${card.text}"
        is Card.Trouble -> "问题（${FailurePlan.headline(card.gate)}）：${card.reason}"
    }

    // ── 给机器读 ───────────────────────────────────────────────────

    /** 回放文件的头部。 */
    data class Replay(
        val version: Int,
        val pause: PauseReason?,
        val saySeq: Int,
        val troubleSeq: Int,
        val lines: List<Line>,
    )

    /**
     * 回放文件里的一行。
     *
     * [fields] 是**有序**的键值对表（不是 Map）—— 出口用 `exit0/exit1/…`、
     * 依据用 `ev0/ev1/…` 逐条列出，顺序即"界面上的先后"，Map 会把它丢掉。
     */
    data class Line(
        val at: Long,
        val kind: CardKind,
        val id: String,
        val state: CardState,
        val fields: List<Pair<String, String>>,
    ) {
        /** 取一个字段（不存在返回 null）。 */
        fun field(key: String): String? = fields.firstOrNull { it.first == key }?.second

        /** 按序取所有出口。 */
        fun exits(): List<String> = indexed("exit")

        /**
         * 按序取所有依据，形如 `USER_INSTRUCTION:给妈发个消息`。
         *
         * ★ 依据必须进回放。它是"为什么这么干"的唯一载体：
         *   没有它，回放只剩下动作序列，复盘时会重新陷入
         *   "当时为什么点这个按钮？"—— 而那正是这套卡片要回答的问题。
         */
        fun evidence(): List<String> = indexed("ev")

        private fun indexed(prefix: String): List<String> =
            fields.filter { it.first.startsWith(prefix) && it.first.removePrefix(prefix).toIntOrNull() != null }
                .sortedBy { it.first.removePrefix(prefix).toInt() }
                .map { it.second }
    }

    /**
     * 导出为「可回放」的紧凑格式。
     *
     * ```
     * v=1
     * pause=USER_TAKEOVER|-        // 枚举名，无界面文案
     * saySeq=2
     * troubleSeq=1
     * <at>|<KIND>|<id>|<STATE>|<k=v;…>
     * ```
     *
     * ⚠️ 末行也带 `\n`，解析请用 [parseReplay]（Kotlin 的 `lines()` 会多给一个空串）。
     */
    fun toReplay(timeline: Timeline): String = buildString {
        append("v=").append(REPLAY_VERSION).append('\n')
        append("pause=").append(timeline.pause?.name ?: "-").append('\n')
        append("saySeq=").append(timeline.saySeq).append('\n')
        append("troubleSeq=").append(timeline.troubleSeq).append('\n')
        for (card in timeline.cards) {
            append(card.at).append('|')
                .append(card.kind.name).append('|')
                .append(escape(card.id.value)).append('|')
                .append(card.state.name).append('|')
                .append(fields(card).joinToString(";") { (k, v) -> "$k=${escape(v)}" })
                .append('\n')
        }
    }

    /**
     * 每类卡各自的字段，键名全 ASCII。
     *
     * ★ 出口用 `exit0/exit1/exit2`、依据用 `ev0/ev1` 逐条列，**不用分隔符拼串**。
     *   拼串就得为内层再定一套转义（标签里可能有逗号、分号），于是出现
     *   "先转义一次、字段层再转义一次"的双重转义 —— 而双重转义的后果是
     *   解析回来多出一个反斜杠，标签悄悄变样。索引字段直接消灭这个问题。
     *
     * 依据对所有卡类型都追加（编码成 `KIND:原文`），因为它不属于某一类卡。
     */
    fun fields(card: Card): List<Pair<String, String>> {
        val own = when (card) {
            is Card.Intent -> listOf(
                "act" to card.action,
                "tgt" to (card.target ?: "-"),
                "risk" to card.risk.name,
                "rev" to if (card.reversible) "1" else "0",
                "note" to (card.note ?: "-"),
            )
            is Card.Act -> listOf(
                "tool" to card.tool,
                "sum" to card.summary,
                "detail" to card.detail,
                "ch" to (card.channel ?: "-"),
                "ms" to (card.latencyMs?.toString() ?: "-"),
                "unc" to (card.uncertainty ?: "-"),
            )
            is Card.Obs -> listOf(
                "sum" to card.summary,
                "nodes" to card.nodeCount.toString(),
                "red" to if (card.redacted) "1" else "0",
            )
            is Card.Say -> listOf("text" to card.text)
            is Card.Trouble ->
                listOf(
                    "about" to card.about.value,
                    "gate" to card.gate.name,
                    "reason" to card.reason,
                ) + card.exits.mapIndexed { i, exit -> "exit$i" to exitCode(exit) }
        }
        return own + card.evidence.mapIndexed { i, e -> "ev$i" to "${e.kind.name}:${e.detail}" }
    }

    private fun exitCode(exit: Exit): String = when (exit) {
        Exit.Retry -> "RETRY"
        Exit.TakeOver -> "TAKEOVER"
        // 替代出口的标签是**用户可见文案**，可能含中文 —— 它进回放是刻意允许的，
        // 因为它承载"当时还能选什么"这个事实。丢了它，复盘会得出
        // "当时只能重试"这种错误结论。转义由字段层统一负责，这里不再动手。
        is Exit.Alternative -> "ALT:${exit.label}"
    }

    /**
     * 解析回放文本。
     *
     * @throws IllegalArgumentException 头部缺失、版本不认、行结构损坏。
     *   ★ 损坏**必须抛**，不能跳过：把"解析不了"实现成"跳过这一行"，
     *     会让复盘安静地少几条卡，而看的人以为那就是全部。
     */
    fun parseReplay(text: String): Replay {
        val lines = text.lines()
        if (lines.isEmpty()) throw IllegalArgumentException("回放文本为空")

        var version: Int? = null
        var pause: PauseReason? = null
        var saySeq = 0
        var troubleSeq = 0
        val cards = mutableListOf<Line>()

        for (raw in lines) {
            if (raw.isBlank()) continue
            if (raw.startsWith("v=")) {
                version = raw.removePrefix("v=").trim().toIntOrNull()
                    ?: throw IllegalArgumentException("版本号不是数字：$raw")
                continue
            }
            if (raw.startsWith("pause=")) {
                val v = raw.removePrefix("pause=").trim()
                pause = if (v == "-") null else PauseReason.valueOf(v)
                continue
            }
            if (raw.startsWith("saySeq=")) {
                saySeq = raw.removePrefix("saySeq=").trim().toInt()
                continue
            }
            if (raw.startsWith("troubleSeq=")) {
                troubleSeq = raw.removePrefix("troubleSeq=").trim().toInt()
                continue
            }
            cards += parseLine(raw)
        }

        val v = version ?: throw IllegalArgumentException("回放文本缺少版本号行（v=…）")
        require(v == REPLAY_VERSION) { "回放版本不匹配：文件 v=$v，当前支持 v=$REPLAY_VERSION" }
        return Replay(v, pause, saySeq, troubleSeq, cards)
    }

    private fun parseLine(raw: String): Line {
        val parts = splitEscaped(raw, '|')
        if (parts.size < 5) throw IllegalArgumentException("回放行字段不足：$raw")
        val at = parts[0].toLongOrNull() ?: throw IllegalArgumentException("时间不是数字：${parts[0]}")
        return Line(
            at = at,
            kind = CardKind.valueOf(parts[1]),
            id = unescape(parts[2]),
            state = CardState.valueOf(parts[3]),
            // 第 5 段之后若还有内容，说明 payload 里有未转义的 `|` —— 直接抛。
            fields = splitEscaped(parts.drop(4).joinToString("|"), ';').map { field ->
                val i = field.indexOf('=')
                require(i > 0) { "回放字段缺少等号：$field" }
                unescape(field.substring(0, i)) to unescape(field.substring(i + 1))
            },
        )
    }

    // ── 细节 ───────────────────────────────────────────────────────

    private fun detailOf(card: Card): String? = when (card) {
        is Card.Act -> {
            val parts = buildList {
                if (card.channel != null) add("通道=${card.channel}")
                if (card.uncertainty != null) add("不确定：${card.uncertainty}")
                if (card.detail.isNotBlank()) add(card.detail)
            }
            parts.takeIf { it.isNotEmpty() }?.joinToString("；")
        }
        is Card.Intent -> if (card.reversible) null else "不可撤销"
        is Card.Obs -> if (card.redacted) "含已遮蔽内容" else null
        is Card.Trouble -> card.exits.joinToString(" / ") { exitLabel(it) }
        is Card.Say -> null
    }

    private fun evidence(e: Evidence): String = when (e.kind) {
        EvidenceKind.NODE_REF -> "看到「${e.detail}」"
        EvidenceKind.RULE_HIT -> "规则「${e.detail}」"
        EvidenceKind.USER_INSTRUCTION -> "你说「${e.detail}」"
        // ★ 必须与上面三条措辞明显不同 —— 它不是我们的发现，是模型的说法。
        EvidenceKind.MODEL_CLAIM -> "模型声称「${e.detail}」（未经核对）"
    }

    private fun stateMark(state: CardState): String = when (state) {
        CardState.DRAFT -> "·待确认"
        CardState.RUNNING -> "·进行中"
        CardState.DONE -> "·完成"
        CardState.BLOCKED -> "·已拦下"
        CardState.FAILED -> "·失败"
        CardState.CANCELLED -> "·已取消"
    }

    private fun riskLabel(risk: RiskLevel): String = when (risk) {
        RiskLevel.READ -> "只读"
        RiskLevel.WRITE -> "改动本机"
        RiskLevel.EXTERNAL -> "对外发送"
        RiskLevel.MONEY -> "花钱"
        RiskLevel.IDENTITY -> "身份信息"
    }

    private fun pauseLabel(reason: PauseReason): String = when (reason) {
        PauseReason.USER_TAKEOVER -> "你接管了"
        PauseReason.BUDGET_EXHAUSTED -> "预算用完"
    }

    private fun exitLabel(exit: Exit): String = when (exit) {
        Exit.Retry -> "重试"
        is Exit.Alternative -> exit.label
        Exit.TakeOver -> "我来"
    }

    /** 界面文案的同义词表 —— 回放串里出现任何一个即为失败。 */
    val UI_WORDS: List<String> = listOf(
        "待确认", "进行中", "完成", "已拦下", "失败", "已取消",
        "只读", "改动本机", "对外发送", "花钱", "身份信息",
        "你接管了", "预算用完", "重试", "我来",
    )

    /**
     * 转义所有会破坏行/字段结构的字符。
     *
     * ⚠️ 反斜杠必须**第一个**替换，否则后几步产生的反斜杠会被二次转义。
     * 与 [unescape] 构成严格互逆的一对（有测试钉住）。
     */
    internal fun escape(s: String): String = s.replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace(";", "\\;")
        .replace("=", "\\=")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** [escape] 的逆。 */
    internal fun unescape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.lastIndex) {
                sb.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                '\\' -> sb.append('\\')
                '|' -> sb.append('|')
                ';' -> sb.append(';')
                '=' -> sb.append('=')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                // 不认识的转义原样保留 —— 宁可多留一个反斜杠，
                // 也不要把用户原话里的字符悄悄吃掉。
                else -> {
                    sb.append(c).append(n)
                }
            }
            i += 2
        }
        return sb.toString()
    }

    /** 按分隔符切分，但**不**在 `\` 后面切。 */
    private fun splitEscaped(s: String, delim: Char): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i < s.lastIndex -> {
                    cur.append(c).append(s[i + 1])
                    i += 2
                }
                c == delim -> {
                    out += cur.toString()
                    cur.setLength(0)
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        out += cur.toString()
        return out
    }
}
