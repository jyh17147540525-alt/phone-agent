package com.pocketagent.memorylogic

/**
 * **上下文压缩** —— 把"聊过的那些话"压成一段**只有模型看得懂**的密文。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 用户要的是三件事
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. 上下文压下来，**省 token**；
 * 2. 压完的东西**还要能被想起来** —— 分段融进记忆分层系统；
 * 3. 压完的表达**只有模型能看懂** —— 人读着费劲没关系，模型读着准就行。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么"只有模型能看懂"是个好要求，不是噱头
 * ═══════════════════════════════════════════════════════════════
 *
 * 人话很贵：为了让人类读着舒服，得补主语、补连接词、补礼貌，
 * 一句话三四十字里，真正带信息的可能只有八个字。
 *
 * 而模型不需要这些。它需要的是**关系**：
 * 谁、对谁、发生了什么、结果是什么、有没有被否掉。
 *
 * ⇒ 这里用的是一套**极简、稳定、可被模型解析的记号**：
 *
 * ```
 * U> 想要A            （用户说的）
 * A< 给了A，但也说了X  （助理回的）
 * !  A 被否掉         （明确失败/用户拒绝）
 * =  事实：住在杭州
 * ```
 *
 * ⚠️ 它**不是**给用户看的界面文案，也**不写进任何 UI**。
 *    唯一的去处是系统提示词里的「压缩记忆」段落。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 三条不能破的底线
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **不丢"被否掉"这件事**。压缩最容易犯的错是把失败那一轮压成
 *    "尝试过 X" —— 于是模型下一轮又去试同一个 X。`!` 前缀就是为此存在。
 * 2. **不编造**。压缩只做删减与记号化，**不引入原对话里没有的内容**。
 * 3. **可回溯**。每一段都带 `sourceTurnIds`，点得回原始轮次 ——
 *    没有溯源的压缩，等于把记忆变成传说。
 */
object ContextCompressor {

    /**
     * 什么时候该压。
     *
     * ★ 用**字符数**而不是轮数：决定开销的是 token，不是条数。
     *   十条短问短答比三轮长文便宜得多。
     */
    const val COMPRESS_THRESHOLD_CHARS: Int = 6_000

    /** 最近这么多轮**永不压缩** —— 逐字上下文是"接得上话"的关键。 */
    const val KEEP_RECENT_TURNS: Int = 8

    /** 压缩后允许占的最大字符数。 */
    const val COMPRESSED_MAX_CHARS: Int = 1_600

    /**
     * 一段压缩记忆。
     *
     * @param text 记号化的密文（见文件头）
     * @param sourceTurnIds 它来自哪几轮 —— **硬要求**，同 [Atom]
     */
    data class Segment(
        val text: String,
        val sourceTurnIds: List<String>,
        val droppedChars: Int,
    )

    /**
     * 压缩结果。
     *
     * @param segments 按时间顺序的分段（**分段**是用户明确要的：
     *   一次性压成一坨会让"哪段是哪段"消失，L2 归纳就没法做）
     * @param keptRecent 原样保留的最近几轮（调用方要照常传进 messages）
     * @param beforeChars / afterChars 用于如实告诉用户省了多少
     */
    data class Result(
        val segments: List<Segment>,
        val keptRecent: List<Turn>,
        val beforeChars: Int,
        val afterChars: Int,
    ) {
        val savedChars: Int get() = (beforeChars - afterChars).coerceAtLeast(0)
        val worthIt: Boolean get() = savedChars > beforeChars / 10
    }

    /**
     * 判断要不要压。
     *
     * ⚠️ 返回 true 也**不代表**一定压得动：短句压缩后反而更长，
     *    所以真正的判据是 [Result.worthIt]。
     */
    fun shouldCompress(turns: List<Turn>): Boolean =
        turns.sumOf { it.text.length } > COMPRESS_THRESHOLD_CHARS &&
            turns.size > KEEP_RECENT_TURNS

    /**
     * 压。
     *
     * @param turns 按时间升序的轮次（当前路径）
     */
    fun compress(turns: List<Turn>, keepRecent: Int = KEEP_RECENT_TURNS): Result {
        if (turns.size <= keepRecent) {
            return Result(
                segments = emptyList(),
                keptRecent = turns,
                beforeChars = turns.sumOf { it.text.length },
                afterChars = turns.sumOf { it.text.length },
            )
        }
        val old = turns.dropLast(keepRecent)
        val recent = turns.takeLast(keepRecent)

        // ★ 按"每 CHUNK_TURNS 轮一段"切。分段的理由见 Result.segments 的注释。
        val segments = old.chunked(CHUNK_TURNS).map { chunk ->
            val body = chunk.joinToString(separator = "\n") { line(it) }
            val trimmed = body.take(COMPRESSED_MAX_CHARS / 4).trimEnd()
            val before = chunk.sumOf { it.text.length }
            Segment(
                text = trimmed,
                sourceTurnIds = chunk.map { it.id },
                droppedChars = (before - trimmed.length).coerceAtLeast(0),
            )
        }
        return Result(
            segments = segments,
            keptRecent = recent,
            beforeChars = turns.sumOf { it.text.length },
            afterChars = segments.sumOf { it.text.length } + recent.sumOf { it.text.length },
        )
    }

    /**
     * 生成写进系统提示词的那一段。
     *
     * @return 空串 = 没有可用的压缩记忆，调用方**不要**补一句"（无）"。
     */
    fun promptSection(result: Result): String {
        if (result.segments.isEmpty()) return ""
        return buildString {
            appendLine("## 压缩记忆（模型专用记号，不是给你复述的原文）")
            appendLine("记号：`U>`=用户说 · `A<`=你当时答 · `!`=被否掉/失败 · `=`=确定的事实")
            appendLine("用法：这些是**已经聊过的事**，别当成对方刚说的；需要细节时可以说「我记得你说过…」。")
            result.segments.forEach { appendLine(it.text) }
        }.trimEnd()
    }

    // ── 单行记号化 ──────────────────────────────────────────────

    /**
     * 把一轮压成一行。
     *
     * ★ 这一步是"只有模型看得懂"的落点：
     *   去掉语气词与冗余表述、保留主体与动作、失败显式标 `!`。
     */
    fun line(turn: Turn): String {
        val prefix = when (turn.role) {
            TurnRole.USER -> "U>"
            TurnRole.ASSISTANT -> "A<"
            TurnRole.TOOL -> "T|"
        }
        val normalized = normalize(turn.text)
        if (normalized.isEmpty()) return "$prefix（空）"
        // ★ 失败/拒绝要显式标出来 —— 这是压缩里最容易被抹掉、又最致命的信息。
        val flag = if (looksFailed(turn.text)) "!" else ""
        return "$prefix$flag$normalized"
    }

    /**
     * 抹掉"人话税"。
     *
     * ⚠️ 只删**不承载语义**的东西：语气词、重复标点、多余空白。
     *    **不删**否定词（不/没/别）—— 删掉它们会把意思整个翻过来。
     */
    fun normalize(text: String): String {
        var s = text.trim()
        if (s.isEmpty()) return ""
        // 连续空白 → 单个空格
        s = s.replace(Regex("\\s+"), " ")
        // 重复标点 → 单个
        // ⚠️ 必须同时含**全角**的 `！？`：中文里用的就是它们，
        //    只写半角的话 `真的吗？？？` 一个都不会被压（这条是测试抓出来的）。
        s = s.replace(Regex("([!！?？。，、~～])\\1+"), "$1")
        // 常见语气词（**不含否定词**）
        for (noise in NOISE) {
            s = s.replace(noise, "")
        }
        return s.trim()
    }

    /** 这一轮是不是"没成"。判据刻意保守 —— 误标 `!` 比漏标更糟。 */
    fun looksFailed(text: String): Boolean {
        val t = text.lowercase()
        return FAIL_MARKERS.any { t.contains(it) }
    }

    /** 每段装多少轮。 */
    private const val CHUNK_TURNS = 6

    /**
     * 语气词。★ 这里**一个否定词都不能有** ——
     * "不去""没做""别这样"里的字被当噪声删掉的话，意思会反过来。
     */
    private val NOISE = listOf(
        "嗯嗯", "嗯", "呃", "那个", "就是说", "其实吧", "老实说",
        "哈哈哈", "哈哈", "呵呵", "嘿嘿", "啦", "呀", "哦", "噢",
    )

    private val FAIL_MARKERS = listOf(
        "失败", "不支持", "无法", "报错", "错误", "拒绝", "被拦", "取消",
        "error", "failed", "denied", "refused",
    )
}
