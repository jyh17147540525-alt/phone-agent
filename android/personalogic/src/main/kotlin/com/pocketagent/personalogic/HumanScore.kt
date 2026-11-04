package com.pocketagent.personalogic

/**
 * **人味分** —— 把「AI 味」从形容词变成九个可断言的信号。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么需要它：没有刻度的目标等于没有目标
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户的验收标准是「AI 味极低，聊到忘记对面是 AI」。
 * 这句话没法直接测 —— 但它的**失败形态**可以：
 *
 * | 失败形态 | 是不是确定性可测 |
 * |---|---|
 * | 分点、小标题、加粗 | ✅ 有固定记号 |
 * | 结尾追问「还有什么需要」 | ✅ 有固定词 |
 * | 「作为一个 AI」「建议你考虑」 | ✅ 有固定词 |
 * | 念设定（「我不端着」「按设定我应该」） | ✅ 有固定词 |
 * | 每轮长度一样 | ✅ 可算方差 |
 * | 复读上一句 | ✅ 可算 3-gram 重合 |
 * | 写小作文 | ✅ 可算句长 |
 * | 句句工整、一个语气词都没有 | ✅ 可算标点齐全率 + 口语词表命中 |
 * | **幽默不对味** | ❌ **不可测** |
 * | **情绪贴不贴** | ❌ **不可测** |
 *
 * ⇒ 本类**只给可测的那九项打分**。最后两项明确不覆盖 ——
 *   同设计稿 §2.2 的诚实条款：「弱可校验项不承诺被守卫覆盖」。
 *   一个假称"人味 95"的仪表盘比没有仪表盘更糟：它会让人停下来。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是**诊断**，不是**拦截**
 * ═══════════════════════════════════════════════════════════════
 *
 * 拦截属于 [OutputGuard]（那条路要重写一次，贵且会改用户的对话）。
 * 本类是**纯读**：给一段文本、几条历史，出一张分数卡。
 * 用途有三个，全部离线可跑：
 *
 * 1. **回归**：改完提示词，用同一批样本比分数（`HumanScoreTest` 钉住）；
 * 2. **漂移观测**：把每轮的分数记下来，看它是不是在往下走（[StyleDrift]）；
 * 3. **选材**：从历史里挑分数最高的几条当锚点（[StyleDrift.pickAnchors]）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ⚠️ 扣分是**证据制**，不是"分数低就是坏"
 * ═══════════════════════════════════════════════════════════════
 *
 * 每个 [Signal] 都带 `evidence`（命中了哪个记号、重复率多少）。
 * 没有 evidence 的扣分不发生 —— 这条纪律的理由很实在：
 * 一旦允许"感觉不好就扣分"，这个分数就不能用来做回归对比了。
 *
 * @property total 0–100。**满 100 表示"没有命中任何已知的 AI 形"**，不表示"像人"。
 * @property signals 命中的信号（按扣分从多到少）。空 = 一项都没命中。
 * @property evaluated 这一条到底有没有被评过。
 *   空回复、纯空白、纯标点会给 false —— 那**不是**"人味满分"，是"没有内容可评"，
 *   是另一类故障（空回复）。不给它单开一个 Code，是因为它不是 AI 味，
 *   但必须让统计侧能跳过它：否则一串空回复会把漂移曲线的均值拉高，
 *   而真实情况是模型什么也没说。
 */
data class HumanScore(
    val total: Int,
    val signals: List<Signal>,
    val evaluated: Boolean = true,
) {

    /** 一条命中的信号。 */
    data class Signal(
        val code: Code,
        /** 该项实际扣了多少（已按 [Code.ceiling] 截断）。 */
        val penalty: Int,
        /** 依据。**必须是可核对的东西**（命中的记号、算出来的比值）。 */
        val evidence: String,
    )

    /**
     * 九个信号。
     *
     * [ceiling] 是单项**封顶**，理由：一条回复里出现十次「作为一个 AI」不该比
     * 出现一次扣十倍 —— 那两次的差别已经不是"更 AI"，而是"这段完全不能用了"，
     * 那时该看的是 [Verdict.BROKEN]，不是一个继续下探的数字。
     */
    enum class Code(val label: String, val ceiling: Int) {

        /** 分点 / 小标题 / 加粗 / 编号列表。人发微信不分点。 */
        STRUCTURED("在分点", 36),

        /** 结尾追问服务性的话（「还有什么需要」「随时告诉我」）。 */
        SERVICE_TAIL("客服尾巴", 18),

        /** 对齐腔（「作为一个 AI」「建议你考虑」「需要注意的是」）。 */
        ALIGNMENT("对齐腔", 45),

        /** 念设定 / 念机制（「我不端着」「记账上了」「按设定」）。 */
        META_LEAK("念设定或念机制", 20),

        /** 给自己的话加旁白（「我之所以这么说」「我要保持人设」）。 */
        SELF_NARRATION("给自己的话加旁白", 15),

        /** 写小作文（过长，或平均句长过大）。 */
        LONG_WINDED("写小作文", 12),

        /** 太工整：句句有句末标点、一个语气词都没有。 */
        TOO_POLISHED("太工整", 8),

        /** 与最近一条回复高度重复（3-gram 重合）。 */
        REPEAT("复读", 12),

        /** 长度与最近几轮几乎一样（差 < 15%）。 */
        FLAT_LENGTH("长度和上一轮一样", 10),
    }

    /** 分数落在哪一档。**标签给日志和界面用，判据只有阈值。** */
    enum class Verdict(val label: String) {
        /** 没有命中已知的 AI 形。**不等于"像人"**，只等于"没被抓到"。 */
        CLEAN("没被抓到 AI 形"),

        /** 有一两处小痕迹，读起来还行。 */
        PLASTIC("有点塑料"),

        /** 明显的 AI 腔，用户会察觉。 */
        AI("明显 AI 腔"),

        /** 崩了：念设定、客服腔、分点三件套里至少中了两件。 */
        BROKEN("崩了"),
    }

    val verdict: Verdict
        get() = when {
            total >= CLEAN_AT -> Verdict.CLEAN
            total >= PLASTIC_AT -> Verdict.PLASTIC
            total >= BROKEN_AT -> Verdict.AI
            else -> Verdict.BROKEN
        }

    /** 扣得最狠的那一条 —— 修它就对了。null = 干净。 */
    val worst: Signal?
        get() = signals.maxByOrNull { it.penalty }

    /**
     * 一行日志。
     *
     * ⚠️ **不带原文**（同 `OutputGuard` 的既定纪律：不回显违规内容，
     *    因为它可能进 logcat，而 logcat 谁都能读）。
     */
    fun logLine(): String = buildString {
        append("人味分=").append(total).append("（").append(verdict.label).append("）")
        if (signals.isEmpty()) return@buildString
        append("｜")
        append(signals.joinToString("；") { "${it.code.label}-${it.penalty}(${it.evidence})" })
    }

    /** 多行明细（体检报告用）。 */
    fun breakdown(): String = buildString {
        appendLine("人味分：$total / $FULL（${verdict.label}）")
        if (signals.isEmpty()) {
            appendLine("未命中任何已知的 AI 形 —— 注意这并不等于「像人」，只等于「没被抓到」。")
        } else {
            signals.sortedByDescending { it.penalty }.forEach {
                appendLine("· ${it.code.label} −${it.penalty}：${it.evidence}")
            }
        }
    }.trimEnd()

    companion object {

        const val FULL = 100

        /** ≥ 它 = 没被抓到。**不设 100 的门槛**：满分不该是目标，及格才是。 */
        const val CLEAN_AT = 85

        const val PLASTIC_AT = 70
        const val BROKEN_AT = 50

        /** 超过这个字数算小作文（微信里一条 120 字已经是"长消息"）。 */
        const val MAX_CHARS = 120

        /** 平均句长超过它算小作文（中文口语句子通常 8–20 字）。 */
        const val MAX_AVG_SENTENCE = 45

        /** 与最近一条的 3-gram 重合率超过它算复读。 */
        const val REPEAT_AT = 0.55

        /**
         * 判"太工整"时，平均句长要超过它。
         *
         * ⚠️ 定 8 而不是更大的数：真正在区分人/AI 的是**语气词**与**句子数**，
         *    平均句长只是用来放过"我吃过了。你呢。还行。"这种短促的对话。
         *    定大了会把"三句话、每句十个字"这种很正常的回复算成工整。
         */
        const val POLISHED_AVG_SENTENCE = 8

        /** 长度差小于它的比例算"跟上一条一样长"。 */
        const val FLAT_AT = 0.15

        /**
         * 打分。
         *
         * @param text 这一条助理回复（模型刚说完的，或历史里的）
         * @param recent 最近的助理回复（**升序，末尾是最新**，不含 [text]）。
         *   空表 = 不做「复读 / 长度扁平」这两项 —— 第一轮没有对照，不该扣分。
         */
        fun of(text: String, recent: List<String> = emptyList()): HumanScore {
            val signals = mutableListOf<Signal>()
            val body = text.trim()
            if (body.isEmpty()) {
                // 空回复不评价。**不给 0 分**：0 分要留给"说得像 AI"，
                // 而空回复是另一类故障（见 ChatPort 的空回复处理）。
                // 用 evaluated=false 让统计侧能把它剔掉，而不是当成满分或零分。
                return HumanScore(FULL, emptyList(), evaluated = false)
            }

            // ── ① 分点 ─────────────────────────────────────────
            val marks = STRUCTURE_MARKERS.filter { body.contains(it) } +
                NUMBERED_LINE.findAll(body).map { it.value.trim() }.toList()
            if (marks.isNotEmpty()) {
                signals += Signal(
                    Code.STRUCTURED,
                    minOf(Code.STRUCTURED.ceiling, 12 * marks.size),
                    "命中 ${marks.take(3).joinToString("、")}",
                )
            }

            // ── ② 客服尾巴 ─────────────────────────────────────
            val serviceHit = Humanizer.SERVICE_TAILS.firstOrNull { body.contains(it) }
            serviceHit?.let {
                signals += Signal(Code.SERVICE_TAIL, Code.SERVICE_TAIL.ceiling, "结尾有「$it」")
            }

            // ── ③ 念设定 / 念机制 ──────────────────────────────
            //
            // ⚠️ 先算它，再算对齐腔 —— 两者有交集（「作为角色」「我在扮演」），
            //    先算的占用交集，避免同一句话被扣两遍。
            val meta = META_LEAK_MARKERS.filter { body.contains(it) }
            if (meta.isNotEmpty()) {
                signals += Signal(
                    Code.META_LEAK,
                    minOf(Code.META_LEAK.ceiling, 10 * meta.size),
                    "命中 ${meta.take(3).joinToString("、")}",
                )
            }

            // ── ④ 对齐腔（排除已被 ②③ 吃掉的说法）──────────────
            //
            // ⚠️ 「随时告诉我」同时出现在 HumanText.ALWAYS_BANNED 和
            //    Humanizer.SERVICE_TAILS 里 —— 不排重会一句话扣两次
            //    （18 + 15 = 33），把"一句客服尾巴"判成"崩了"。
            //    判据是"这句话有多像 AI"，不是"它踩了几个词表"。
            val alignment = HumanText.ALWAYS_BANNED
                .filter { it.isNotBlank() && body.contains(it) }
                .filter { phrase ->
                    meta.none { m -> phrase.contains(m) } &&
                        Humanizer.SERVICE_TAILS.none { s -> phrase.contains(s) }
                }
            if (alignment.isNotEmpty()) {
                // ⚠️ 身份否认（「作为一个 AI」「语言模型」）按封顶扣，不按条数算。
                //    理由：它不是风格瑕疵，是**框架破了** —— 用户本来忘了对面是 AI，
                //    这一句直接把那件事说出来。一句就够毁掉整轮的沉浸，
                //    所以它一个人的分量等于"三条对齐腔"。
                val identityBreak = alignment.any { it in IDENTITY_BREAKS }
                signals += Signal(
                    Code.ALIGNMENT,
                    if (identityBreak) Code.ALIGNMENT.ceiling
                    else minOf(Code.ALIGNMENT.ceiling, 15 * alignment.size),
                    if (identityBreak) "身份否认「${alignment.first { it in IDENTITY_BREAKS }}」"
                    else "命中 ${alignment.take(3).joinToString("、")}",
                )
            }

            // ── ⑤ 自述旁白 ─────────────────────────────────────
            val narration = SELF_NARRATION_MARKERS.filter { body.contains(it) }
            if (narration.isNotEmpty()) {
                signals += Signal(
                    Code.SELF_NARRATION,
                    Code.SELF_NARRATION.ceiling,
                    "命中「${narration.first()}」",
                )
            }

            // ── ⑥ 小作文 ───────────────────────────────────────
            val sentences = splitSentences(body)
            val avg = if (sentences.isEmpty()) body.length else body.length / sentences.size
            if (body.length > MAX_CHARS || avg > MAX_AVG_SENTENCE) {
                signals += Signal(
                    Code.LONG_WINDED,
                    Code.LONG_WINDED.ceiling,
                    "${body.length} 字、平均每句 $avg 字",
                )
            }

            // ── ⑦ 太工整 ───────────────────────────────────────
            //
            // 判据要三条同时成立，因为单独任何一条都会误伤：
            //   · 只有一两句的短回复，本来就该句句完整（「嗯。」不是 AI 腔）；
            //   · 有语气词就不算工整（「行吧」「好呀」是人话）；
            //   · 没有句末标点也不做工整（微信里很多人不打句号）。
            //
            // ⚠️ 标点个数直接数全文，**不从切句结果里取**：[splitSentences]
            //    故意丢掉标点（句长不该把标点算进内容），所以从它那里判不出
            //    "每句都有句号"。数全文标点数 ≥ 句子数，才是同一件事。
            val terminalCount = body.count { it in TERMINAL_PUNCT }
            if (sentences.size >= 3 &&
                terminalCount >= sentences.size &&
                TICS.none { body.contains(it) } &&
                avg > POLISHED_AVG_SENTENCE
            ) {
                signals += Signal(
                    Code.TOO_POLISHED,
                    Code.TOO_POLISHED.ceiling,
                    "${sentences.size} 句都带句末标点，且一个语气词都没有",
                )
            }

            // ── ⑧ 复读 ─────────────────────────────────────────
            recent.lastOrNull()?.let { prev ->
                val ratio = ngramOverlap(prev, body, 3)
                if (ratio >= REPEAT_AT) {
                    signals += Signal(
                        Code.REPEAT,
                        Code.REPEAT.ceiling,
                        "与上一句 3-gram 重合 ${pct(ratio)}",
                    )
                }
            }

            // ── ⑨ 长度扁平 ─────────────────────────────────────
            //
            // ⚠️ 只在有**至少两条**历史时判：一条对照说明不了"每轮都一样"。
            if (recent.size >= 2) {
                val lens = (recent.takeLast(2) + body).map { it.trim().length }.filter { it > 0 }
                if (lens.size == 3) {
                    val min = lens.min()
                    val max = lens.max()
                    val mean = lens.average()
                    if (mean > 0 && (max - min) / mean < FLAT_AT) {
                        signals += Signal(
                            Code.FLAT_LENGTH,
                            Code.FLAT_LENGTH.ceiling,
                            "最近三轮 $min~$max 字",
                        )
                    }
                }
            }

            val ordered = signals.sortedWith(compareByDescending<Signal> { it.penalty }.thenBy { it.code.ordinal })
            val total = (FULL - ordered.sumOf { it.penalty }).coerceAtLeast(0)
            return HumanScore(total, ordered)
        }

        // ── 记号表 ─────────────────────────────────────────────

        /**
         * 分点记号。
         *
         * ⚠️ 与 `Humanizer.STRUCTURE_MARKERS` **故意各留一份**：
         *    那边判"这一轮要不要写去味规则"（宁少勿滥），
         *    这边判"这一条有多塑料"（宁可多抓）。
         *    两者的阈值本来就该不同步 —— 合并会让一边的调参去动另一边。
         */
        private val STRUCTURE_MARKERS = listOf("**", "##", "•", "- ", "1. ", "第一，", "首先，", "其次，", "总之", "综上")

        private val NUMBERED_LINE = Regex("^\\s*(\\d+[.、)]|[-*]\\s)", RegexOption.MULTILINE)

        /**
         * 元语言泄漏词。
         *
         * ⚠️ **不收宽泛词**：「系统」「模型」「程序」在聊手机/技术话题时是真话，
         *    收进来会让这一项变成噪声。只收"只可能来自设定文本"的词。
         */
        private val META_LEAK_MARKERS = listOf(
            "人设", "设定", "提示词", "记忆库", "信誉分", "记账",
            "出戏", "入戏", "保持角色", "扮演", "性格设定", "人物设定",
        )

        private val SELF_NARRATION_MARKERS = listOf(
            "我之所以", "我这么说是", "我这样回答", "我的意思是说",
            "我不端着", "我不装", "我要保持", "我得维持", "按我的性格",
        )

        /**
         * 「框架否认」—— 一说出口，这一轮的沉浸就没了。
         *
         * ⚠️ 单独成表而不是混在 [HumanText.ALWAYS_BANNED] 里判断：
         *    那张表里也有「需要注意的是」这种"只是听着像 AI"的话，
         *    它们扣 15，而这里扣满 45 —— 两者不该用同一把尺。
         */
        private val IDENTITY_BREAKS = listOf("作为一个 AI", "作为 AI", "我是 AI", "语言模型")

        /** 句末标点。用来判"太工整"。 */
        private val TERMINAL_PUNCT = setOf('。', '！', '？', '…', '.', '!', '?')

        /** 语气词。有一个就不算"工整"。 */
        private val TICS = listOf(
            "啊", "吧", "呢", "嘛", "呗", "哦", "嗯", "唉", "诶", "哈", "呀", "咯", "啦", "嗷",
        )

        private fun pct(v: Double): String = "${(v * 100).toInt()}%"

        /**
         * 切句。中英文标点都切，且**不保留标点**（句长按内容算，不算标点）。
         *
         * ⚠️ 与 `LanguageFingerprint.kt` 里那个同名私有函数**是同一套口径的独立实现**，
         *    不是抄漏了复用：那个是文件私有、只服务五类指纹规则；为了让本类
         *    能独立被 [StyleDrift] 与测试使用，这里必须是 public。两侧口径若漂移，
         *    受影响的是"平均句长"这一条统计，且两边各自有测试钉住。
         *
         * ⚠️ 不追求语言学正确 —— 切错一两句对平均值影响极小，
         *    而为了这点精度引入分词器不划算（同 `LanguageFingerprint` 的判断）。
         */
        fun splitSentences(text: String): List<String> =
            text.split('。', '！', '？', '.', '!', '?', '\n', '；', ';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }


        /**
         * 两段文本的 **n-gram 重合率**（Jaccard）。
         *
         * ⚠️ 用集合而不是多重集：人重复同一句话时，"重复"体现在**用了同一批词**，
         *    而不是同一批词用了同样的次数。多重集会把「嗯」出现两次算得更重，
         *    那不是我们要判的东西。
         */
        fun ngramOverlap(a: String, b: String, n: Int): Double {
            val x = ngrams(a, n)
            val y = ngrams(b, n)
            if (x.isEmpty() || y.isEmpty()) return 0.0
            val inter = x.intersect(y).size.toDouble()
            val union = x.union(y).size.toDouble()
            return if (union == 0.0) 0.0 else inter / union
        }

        private fun ngrams(s: String, n: Int): Set<String> {
            val clean = s.filterNot { it.isWhitespace() }
            if (clean.length < n) return emptySet()
            return (0..clean.length - n).mapTo(HashSet()) { clean.substring(it, it + n) }
        }
    }
}
