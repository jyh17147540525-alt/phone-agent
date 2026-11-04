package com.pocketagent.personalogic

/**
 * **漂移测量** —— 回答「她是不是越聊越像 AI」这个用户亲口抱怨过的问题。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么单开一个类而不是塞进 [Humanizer]
 * ═══════════════════════════════════════════════════════════════
 *
 * [Humanizer] 看的是**最近 8 条回复**，回答"这一轮要不要写去味规则"。
 * 它天然看不见的是**横跨几十轮的下滑**：每一轮的回复都"还行"（85 分上下），
 * 但第 40 轮和第 3 轮比，句长多了一倍、语气词全没了、每段都以"总之"收尾。
 *
 * 用户在 `docs/工程/会话交接-2026-11-04.md` 里的原话是"对话轮次越多 AI 味越浓"，
 * 那是**趋势判断**，不是**单点判断** —— 单点判断永远得出"还行"。
 *
 * ⇒ 本类把一串分数变成三个可断言的量：**趋势、斜率、拐点**。
 *   三个都只用确定性算术（首尾均值、最小二乘、滑动窗口最小值），
 *   所以它可以在离线测试里被钉住，也可以在生产里被 logcat 打出来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 阈值是**这套角色**的阈值，不是普适真理
 * ═══════════════════════════════════════════════════════════════
 *
 * 判"下滑"的门槛是每轮 −0.5 分。定这么低的理由：如果每轮真的掉半分，
 * 20 轮就是 −10 分 —— 从 CLEAN 掉进 AI 档，正好对应"聊三四十条之后不对劲"。
 * 反过来，模型每轮在 ±0.3 内抖动是**正常波动**（换个话题、情绪起伏都会影响
 * 句长和语气词），把它叫成"下滑"会让这个指标天天报警，然后就没人看它了。
 */
object StyleDrift {

    /** 一个观测点：第几轮、多少分。 */
    data class Point(val turn: Int, val score: Int)

    enum class Trend(val label: String) {
        /** 样本太少，叫不出趋势。**这是最常见也最该被如实回报的状态**。 */
        UNKNOWN("样本不足"),

        /** 在变好。 */
        IMPROVING("在回升"),

        /** 在噪声范围内。 */
        STABLE("平稳"),

        /** 在往下走，但还没崩。 */
        DECLINING("在下滑"),

        /** 已经崩：尾部掉进 AI 档，或者陡降。 */
        COLLAPSING("在崩塌"),
    }

    /**
     * 一次漂移诊断。
     *
     * @property head 前 1/3 的均值（"她刚开口时"）
     * @property tail 后 1/3 的均值（"她现在"）
     * @property slope 每轮变化多少分（最小二乘；负数=下滑）
     * @property worstTurn 拐点所在的轮次：**比开头低至少 [TURNING_DROP] 分**、
     *   且是滑动窗口均值最低处的那一轮。null = 没有拐点 ——
     *   这是**好结论**，不是"没算出来"。
     * @property samples 参与统计的样本数（已剔除 `evaluated=false` 的空回复）
     */
    data class Report(
        val trend: Trend,
        val head: Double,
        val tail: Double,
        val slope: Double,
        val worstTurn: Int?,
        val samples: Int,
    ) {

        /** 首尾差（负数=掉了）。 */
        val drop: Double get() = tail - head

        /** 一行日志，格式固定，便于 `| grep 漂移`。 */
        fun logLine(): String = buildString {
            append("漂移=").append(trend.label)
            append("（%d 条，首 %.1f → 尾 %.1f，斜率 %+.2f/轮".format(samples, head, tail, slope))
            worstTurn?.let { append("，拐点在 $it") }
            append("）")
        }

        /** 多行说明（体检报告用）。 */
        fun breakdown(): String = buildString {
            appendLine("漂移：${trend.label}")
            appendLine("· 样本 $samples 条：开头均值 %.1f，最近均值 %.1f，差 %+.1f".format(head, tail, drop))
            appendLine("· 斜率 %+.2f 分/轮（$SLOPE_NOTE）".format(slope))
            if (worstTurn != null) {
                appendLine("· 拐点在第 $worstTurn 轮左右 —— 那一带开始不对劲，值得回看原文")
            } else {
                appendLine("· 没有拐点（全程没有低于开头 $TURNING_DROP 分以上的段落）")
            }
        }.trimEnd()

        companion object {
            private const val SLOPE_NOTE = "负数=越聊越像 AI"
        }
    }

    /** 每轮掉这么多分就算"下滑"。见类注释：定低是为了在 20 轮内能被察觉。 */
    private const val DECLINE_SLOPE = -0.5

    /** 每轮涨这么多分才算"回升"（对称，避免把噪声叫成回升）。 */
    private const val IMPROVE_SLOPE = 0.5

    /**
     * 尾部比开头低这么多，判为"陡降"（不论斜率）。
     *
     * ★ 公开而非 private：主体报告要在页面上画这条警戒线，
     *   测试也要把它当分界来钉（"差 15 分"是和"慢慢变味"的分水岭）。
     */
    const val STEEP_DROP = -15.0

    /** 拐点至少要比起步低这么多才算拐点，否则 null。 */
    private const val TURNING_DROP = 8.0

    /** 样本少于这个数叫不出趋势。 */
    private const val MIN_SAMPLES = 4

    /**
     * 测量。
     *
     * @param scores 按时间升序的人味分（**旧的在前**）。
     *   `evaluated=false` 的空回复不该传进来，但传了也不致命 ——
     *   调用方可以先用 [HumanScore.evaluated] 过滤，这里只负责算。
     */
    fun measure(scores: List<Int>): Report {
        if (scores.isEmpty()) {
            return Report(Trend.UNKNOWN, 0.0, 0.0, 0.0, null, 0)
        }
        val n = scores.size
        if (n < MIN_SAMPLES) {
            // 样本不足时**仍然算出首尾**，因为"两条都是 92"是有信息量的，
            // 只是不足以叫"趋势"。把数字给出去，把结论标成 UNKNOWN。
            val head = scores.take(maxOf(1, n / 3)).average()
            val tail = scores.takeLast(maxOf(1, n / 3)).average()
            return Report(Trend.UNKNOWN, head, tail, 0.0, null, n)
        }

        val bucket = maxOf(1, n / 3)
        val head = scores.take(bucket).average()
        val tail = scores.takeLast(bucket).average()
        val slope = leastSquaresSlope(scores)
        val worstTurn = turningPoint(scores, head)

        val trend = when {
            tail < HumanScore.BROKEN_AT -> Trend.COLLAPSING
            tail - head <= STEEP_DROP -> Trend.COLLAPSING
            slope <= DECLINE_SLOPE -> Trend.DECLINING
            slope >= IMPROVE_SLOPE -> Trend.IMPROVING
            else -> Trend.STABLE
        }
        return Report(trend, head, tail, slope, worstTurn, n)
    }

    /**
     * 最小二乘斜率（x 用下标，**不用轮次号**）。
     *
     * ⚠️ 用下标而不是真实 turn：分数序列中间可能有空洞（模板回复不计分、
     *    空回复被剔除），拿真实轮次做 x 会让斜率被空洞的间距影响。
     *    我们要回答"每**观测一次**变多少"，不是"每过一轮变多少"。
     */
    private fun leastSquaresSlope(ys: List<Int>): Double {
        val n = ys.size
        if (n < 2) return 0.0
        val meanX = (n - 1) / 2.0
        val meanY = ys.average()
        var num = 0.0
        var den = 0.0
        ys.forEachIndexed { i, y ->
            val dx = i - meanX
            num += dx * (y - meanY)
            den += dx * dx
        }
        return if (den == 0.0) 0.0 else num / den
    }

    /**
     * 找拐点：3 点滑动均值的最低位置。
     *
     * 返回的是**窗口中心**的 1-based 序号（给人看的"第几轮"），
     * 且必须低于 [head] 至少 [TURNING_DROP] 分才算拐点。
     */
    private fun turningPoint(scores: List<Int>, head: Double): Int? {
        if (scores.size < 3) return null
        var minAvg = Double.MAX_VALUE
        var minAt = -1
        for (i in 1..scores.size - 2) {
            val avg = (scores[i - 1] + scores[i] + scores[i + 1]) / 3.0
            if (avg < minAvg) {
                minAvg = avg
                minAt = i
            }
        }
        if (minAt < 0 || head - minAvg < TURNING_DROP) return null
        return minAt + 1
    }

    /**
     * **现在该不该插再锚定段**。
     *
     * 判据：最近**连续两条**都低于 [HumanScore.PLASTIC_AT]。
     *
     * ⚠️ 为什么不是"低于一条就插"：再锚定段本身也是提示词，
     *    每轮都插会让它变成新的模板（这个项目已经吃过"规则本身成模板"的亏，
     *    见 `RoleplayDiscipline.VoiceSamples` 里"这个套路用一次是俏皮，用三次是模板"）。
     *    等连续两条确认了才动手，代价是多一条差回复，收益是她不会察觉到
     *    "每轮都被塞了一张纸条"。
     *
     * @param recentScores 最近的分数，**升序**（旧→新）
     */
    fun needsReanchor(recentScores: List<Int>): Boolean {
        if (recentScores.size < 2) return false
        return recentScores.takeLast(2).all { it < HumanScore.PLASTIC_AT }
    }

    // ═══════════════════════════════════════════════════════════
    //  锚点选材
    // ═══════════════════════════════════════════════════════════

    /**
     * 一条锚点样本。
     *
     * @property text 原文。**必须原样使用**，一个字都不改 ——
     *   改一个字它就变成"我们写的仿句"，而模型对仿句的模仿远弱于对真实历史的自回归。
     * @property score 它自己拿的人味分
     * @property why 为什么选它（形状描述，如"12 字、半句"），只给人看
     */
    data class Anchor(val text: String, val score: Int, val why: String)

    /** 锚点最低人味分。低于它的样本**不配当示范**。 */
    const val ANCHOR_MIN_SCORE = 80

    /** 太短：没有可迁移的节奏（"嗯"学不出东西）。 */
    private const val ANCHOR_MIN_CHARS = 4

    /** 太长：会教她写小作文。上限与 [HumanScore.MAX_CHARS] 对齐。 */
    private const val ANCHOR_MAX_CHARS = HumanScore.MAX_CHARS

    /** 与当前用户这句话 2-gram 重合超过它 → 跳过（会变成复读用户）。 */
    private const val QUERY_OVERLAP_MAX = 0.5

    /** 与已选锚点 3-gram 重合超过它 → 视为同一条。 */
    private const val ANCHOR_DEDUP = 0.5

    /**
     * 从历史里挑"最像人、且形状不一"的几条当锚点。
     *
     * ═══ 为什么是"挑"而不是"全部给" ═══
     * 把 8 条历史全塞进去，等于把**平均风格**喂回去 ——
     * 而平均风格正是漂移的方向（见 [Report]）。锚点必须**高于当前均值**。
     *
     * ═══ 六条排除纪律（每条都对应一类已知翻车）═══
     * | 排除 | 翻车形态 |
     * |---|---|
     * | 太短（<4 字） | 学不到节奏，只学到"敷衍" |
     * | 太长（>120 字） | 教她写小作文 |
     * | 结尾是问句 | 教她每轮追问（`Humanizer` 的 SERVICE_TAIL 就在抓这个） |
     * | 含元语言 | 把"我不端着"当示范，越学越出戏 |
     * | 与用户这句话高度重合 | 她开始复读用户刚说的 |
     * | 与已选锚点高度重合 | 三条样本其实是同一条，多样性归零 |
     *
     * ═══ 形状多样性 ═══
     * 按长度分三桶（短 <15 / 中 15–45 / 长 >45），每桶最多取一条，
     * 贪心按分数从高到低。宁可只给 2 条**形状不同**的，
     * 也不要给 3 条**形状相同**的 —— 后者会让她的输出更统一，正好反向。
     *
     * @param history 助理的历史回复，**升序**（旧→新）。不含刚生成的那条。
     * @param query 用户刚说的话（用来避免锚点复读用户）
     * @param limit 最多几条（默认 3；再多就是拿提示词换噪声）
     */
    fun pickAnchors(
        history: List<String>,
        query: String,
        limit: Int = 3,
    ): List<Anchor> {
        if (history.isEmpty() || limit <= 0) return emptyList()

        data class Candidate(val text: String, val score: Int, val bucket: Int)

        val cands = history.asSequence()
            .map { it.trim() }
            .filter { it.length in ANCHOR_MIN_CHARS..ANCHOR_MAX_CHARS }
            // 结尾问句：会教她追问
            .filter { it.lastOrNull() !in setOf('？', '?') }
            .map { it to HumanScore.of(it) }
            // 含元语言：会把"念设定"当示范
            .filter { (_, sc) -> sc.signals.none { it.code == HumanScore.Code.META_LEAK } }
            // 客服尾巴同理：那是我们要去掉的东西，不能拿它当范本
            .filter { (_, sc) -> sc.signals.none { it.code == HumanScore.Code.SERVICE_TAIL } }
            .filter { (_, sc) -> sc.total >= ANCHOR_MIN_SCORE }
            .filter { (t, _) -> HumanScore.ngramOverlap(t, query, 2) <= QUERY_OVERLAP_MAX }
            .map { (t, sc) -> Candidate(t, sc.total, bucketOf(t)) }
            .toList()

        if (cands.isEmpty()) return emptyList()

        val picked = mutableListOf<Candidate>()
        val usedBuckets = mutableSetOf<Int>()
        // 第一轮：每桶取一条最高分的（形状多样优先）
        for (c in cands.sortedByDescending { it.score }) {
            if (picked.size >= limit) break
            if (c.bucket in usedBuckets) continue
            if (picked.any { HumanScore.ngramOverlap(it.text, c.text, 3) >= ANCHOR_DEDUP }) continue
            picked += c
            usedBuckets += c.bucket
        }
        // 第二轮：桶不够（历史里形状本来就单一）就按分数补足
        for (c in cands.sortedByDescending { it.score }) {
            if (picked.size >= limit) break
            if (picked.any { it.text == c.text }) continue
            if (picked.any { HumanScore.ngramOverlap(it.text, c.text, 3) >= ANCHOR_DEDUP }) continue
            picked += c
        }

        // 输出顺序保持**时间顺序**（旧→新）：让模型看到"她一直是这么说话的"，
        // 而不是"她被教导过几条例句"。
        val order = history.map { it.trim() }
        return picked
            .sortedBy { order.indexOf(it.text) }
            .map { Anchor(it.text, it.score, shapeOf(it.text)) }
    }

    /** 长度分桶：0=短，1=中，2=长。 */
    private fun bucketOf(text: String): Int = when {
        text.length < 15 -> 0
        text.length <= 45 -> 1
        else -> 2
    }

    /** 形状描述，进日志给人看。 */
    private fun shapeOf(text: String): String {
        val sentences = HumanScore.splitSentences(text)
        val tics = listOf("啊", "吧", "呢", "嘛", "呗", "哦", "嗯", "唉", "哈", "呀").count { text.contains(it) }
        return buildString {
            append(text.length).append(" 字")
            append("、").append(sentences.size).append(" 句")
            if (tics > 0) append("、带语气词")
            if (sentences.any { it.length <= 3 }) append("、有短句")
            if (text.lastOrNull() !in setOf('。', '！', '？')) append("、没句号")
        }
    }
}
