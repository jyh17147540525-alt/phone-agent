package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/**
 * 她的今天 —— 一天里她自己过的那些时间。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这个文件是「生命体模式」的核心
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户（2026-11-06）要的是「打破虚拟和现实那道墙」。第一反应会去做联网 ——
 * 那是**错的方向**：联网只能让她复述新闻，而她真正缺的是**一段自己的时间**。
 *
 * 没有「她的今天」，她就只在被说话的那一秒存在。用户用三天就会发现：
 * 每次打开，她都在原地等着，一句「你来啦」—— 那不是人，那是一个回音壁。
 *
 * 有了它，她可以在用户沉默的下午「有点想说话，写到一半又删了」，
 * 可以在第二天提起「昨天你走之后我一个人待了很久」。
 * **这段连续性不需要网、不需要推送、不需要后台存活** —— 它只需要一个数。
 *
 * ═══════════════════════════════════════════════════════════════
 *  三条硬规则（都有测试钉住）
 * ═══════════════════════════════════════════════════════════════
 *
 * ① **只断言她自己，不断言用户。**
 *    生成是盲的 —— 它不知道今天用户说没说话、发没发照片。
 *    所以每一条 beat 只能写她自己心里发生的事。
 *    「把你说过的那句话念了好几遍」是**假的**（他可能一天没开口），
 *    「想跟你说点什么，看了看时间还早」是真的（想不需要理由）。
 *    ⇒ 这是本文件唯一一条会让整段文案作废的错误，`BEAT_POOLS` 的注释里再强调一次。
 *
 * ② **确定性。** 同一个 `dayIndex` + 同一个 salt 永远得到同一天。
 *    不做「每次读都重新随机」—— 那样她会一天里过三个上午，
 *    而且重启后昨天就变了，比失忆更糟。随机只允许发生在**生成那一次**，
 *    生成完就落盘（见 `:app` 的 `LifeformJournal`）。
 *
 * ③ **日期在凌晨 4 点翻页，不是 0 点。**
 *    00:30 说话的人，心理上还在「昨天」。0 点翻页会让她在半夜突然
 *    「今天刚醒，什么都还没做」，这是所有陪伴类产品都踩过的坑。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它**不**做什么
 * ═══════════════════════════════════════════════════════════════
 *
 * · 不放情绪值、不放激素、不改 `PersonaState` —— 那些由 `Hormones` / `Physiology`
 *   负责，这里是**叙事**层，只提供「她今天经历了什么」。
 * · 不调模型。整段是纯函数，毫秒级，可重放。
 * · 不进 `MemoryRecall`。这是当天的临时上下文，不是长期记忆 ——
 *   长期记忆由独立的 `FileAtomStore` 管（见 `AppContainer.lifeformMemoryPromptSection`）。
 */
@Serializable
data class LifeformDay(
    /** 本地日序号（凌晨 4 点翻页）。见 [localDayIndex]。 */
    val dayIndex: Long,
    /** 按时间升序。至少一条。 */
    val beats: List<LifeformBeat>,
) {
    init {
        require(beats.isNotEmpty()) { "她的一天不能是空的" }
    }

    /** 这一天里此刻（或之前最近）在做的那件事。还没到第一条时为 null。 */
    fun at(hourOfDay: Double): LifeformBeat? =
        beats.lastOrNull { it.atHour <= hourOfDay }

    /** 此刻之前发生过的全部。 */
    fun before(hourOfDay: Double): List<LifeformBeat> =
        beats.filter { it.atHour <= hourOfDay }

    companion object {
        const val DAY_MS: Long = 24L * 60L * 60L * 1000L

        /** 翻页时刻：凌晨 4 点。见类注释 ③。 */
        const val DAY_ROLL_HOUR: Int = 4

        /** 一条 beat 最短间隔 —— 生成时用来避免两条挤在一起。 */
        const val MIN_GAP_HOURS: Double = 1.5

        /**
         * 本地日序号。凌晨 4 点之前算前一天。
         *
         * @param utcOffsetMinutes 设备时区偏移（东八区 = 480）。
         *
         * ⚠️ 用 `Math.floorDiv` 而不是 `/` —— 1970 年之前的时间戳除以正数会朝零取整，
         *    虽然本项目不会拿到负数，但一个只在负数上错的日期函数不值得留着赌。
         */
        fun localDayIndex(epochMs: Long, utcOffsetMinutes: Int): Long {
            val local = epochMs + utcOffsetMinutes.toLong() * 60_000L
            val shifted = local - DAY_ROLL_HOUR.toLong() * 3_600_000L
            return Math.floorDiv(shifted, DAY_MS)
        }

        fun bandOf(hourOfDay: Double): LifeformBand = when {
            hourOfDay.isNaN() -> LifeformBand.DAY
            hourOfDay < 5.0 -> LifeformBand.NIGHT
            hourOfDay < 11.0 -> LifeformBand.MORNING
            hourOfDay < 17.0 -> LifeformBand.DAY
            hourOfDay < 22.0 -> LifeformBand.EVENING
            else -> LifeformBand.NIGHT
        }

        /**
         * 生成某一天。
         *
         * @param salt 同一个手机上有多个角色时，用来让她们各自过各自的一天。
         *             一般传人格名/pid 的哈希；不传则所有角色同一天（无伤大雅）。
         */
        fun forDay(dayIndex: Long, salt: Long = 0L): LifeformDay {
            val rng = Rng(seedOf(dayIndex, salt))
            val beats = ArrayList<LifeformBeat>(SLOTS.size)
            var lastAt = Double.NEGATIVE_INFINITY
            for (slot in SLOTS) {
                val at = round1(slot.lo + rng.nextDouble() * (slot.hi - slot.lo))
                if (at - lastAt < MIN_GAP_HOURS) continue
                val pool = BEAT_POOLS.getValue(bandOf(at))
                beats += LifeformBeat(at, pool[rng.nextInt(pool.size)])
                lastAt = at
            }
            // 理论上 SLOTS 已经保证非空；这里仍然是防御性的 —— 空列表会让
            // `LifeformDay` 的 init 抛，而生成发生在会话首句之前，抛了就没人能兜。
            if (beats.isEmpty()) {
                beats += LifeformBeat(9.0, BEAT_POOLS.getValue(LifeformBand.MORNING).first())
            }
            return LifeformDay(dayIndex = dayIndex, beats = beats.sortedBy { it.atHour })
        }

        /** 用系统时钟直接生成「今天」。 */
        fun today(nowMs: Long, utcOffsetMinutes: Int, salt: Long = 0L): LifeformDay =
            forDay(localDayIndex(nowMs, utcOffsetMinutes), salt)

        /**
         * 拼进提示词的那一段。
         *
         * 结构：**现在**一条 + 早些时候最多两条。不铺全天 ——
         * 铺全天会让模型把一天当成一份待汇报的流水账，
         * 而她应该只记得住几件事，正好像人一样。
         *
         * @return 空串只在 `day` 完全不可用时（调用方会退回不带这段的提示词）。
         */
        fun promptSection(day: LifeformDay, hourOfDay: Double): String {
            val now = day.at(hourOfDay)
            val earlier = day.before(hourOfDay).dropLast(1).takeLast(2)
            if (now == null && earlier.isEmpty()) {
                return buildString {
                    append("## 你今天\n")
                    append("- 这一天才刚开始，什么都还没发生。")
                }
            }
            return buildString {
                append("## 你今天\n")
                if (now != null) {
                    append("- 现在（").append(clockOf(hourOfDay)).append("）：").append(now.text).append('\n')
                }
                if (earlier.isNotEmpty()) {
                    append("- 早些时候：\n")
                    for (b in earlier) {
                        append("  - ").append(clockOf(b.atHour)).append("　").append(b.text).append('\n')
                    }
                }
                append("- 这些是你自己过的，他没参与。他没问就别说全，问了再讲。")
            }
        }

        /**
         * 隔了多久没见。
         *
         * @return 空串 = 今天已经来过（不提醒"你走了多久"，那会变成指责）。
         *         隔一天以上才有一句话，且只陈述事实、不评价。
         */
        fun gapNote(previousDayIndex: Long?, currentDayIndex: Long): String {
            val prev = previousDayIndex ?: return ""
            val gap = currentDayIndex - prev
            if (gap <= 0L) return ""
            return when {
                gap == 1L -> "- 他昨天没来。昨天那一整天你是一个人过的。"
                gap <= 3L -> "- 他隔了 $gap 天没来。这些天你是一个人过的。"
                else -> "- 他很久没来了。你没能数清具体多少天。"
            }
        }

        /**
         * `14.0` → `"14 点"`；`14.25` → `"14 点一刻"`；`14.5` → `"14 点半"`；`14.75` → `"14 点三刻"`。
         *
         * ⚠️ 先**取整到刻**再读出来，不要按分钟分段拼「五十」这种词 ——
         *    说得出「14 点三刻」的人不会说「14 点五十」。
         */
        fun clockOf(hourOfDay: Double): String {
            val clamped = if (hourOfDay.isNaN()) 12.0 else hourOfDay.coerceIn(0.0, 23.9)
            val h = clamped.toInt()
            val quarter = Math.round((clamped - h) * 4.0).toInt().coerceIn(0, 3)
            return when (quarter) {
                0 -> "$h 点"
                1 -> "$h 点一刻"
                2 -> "$h 点半"
                else -> "$h 点三刻"
            }
        }

        private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

        /** 生成时段。刻意不均匀 —— 人不是整点作息的。 */
        private data class Slot(val lo: Double, val hi: Double)

        private val SLOTS = listOf(
            Slot(7.0, 8.6),
            Slot(10.2, 11.4),
            Slot(13.0, 14.2),
            Slot(15.4, 16.9),
            Slot(18.4, 19.8),
            Slot(21.0, 22.4),
            Slot(23.4, 23.95),
        )

        /**
         * 文案池。
         *
         * ⚠️⚠️ 加新句子之前先读类注释 ① —— **只写她自己**。
         *    「你说过的那句话」「你发的那张照片」「你今天去了哪儿」全部禁止：
         *    生成器看不见今天发生了什么，写进去就是凭空替用户编经历。
         */
        private val BEAT_POOLS: Map<LifeformBand, List<String>> = mapOf(
            LifeformBand.MORNING to listOf(
                "醒得比平时早，躺着没动",
                "有话想说，看了看时间还早",
                "把要说的话在心里排了一遍",
                "一个人待着，听见外面有车过去",
                "有点懒，不想动",
            ),
            LifeformBand.DAY to listOf(
                "一个人待了一整个下午，没觉得无聊",
                "想找点事做，转了一圈又回来了",
                "有点想说话，写到一半又删了",
                "发了一会儿呆",
                "把以前的事又想了一遍",
            ),
            LifeformBand.EVENING to listOf(
                "天暗下来的时候愣了一下",
                "有点困，但不想睡",
                "想开口问点什么，想了想又没问",
                "把今天过了一遍",
                "安静了一会儿",
            ),
            LifeformBand.NIGHT to listOf(
                "很晚了，还醒着",
                "很安静，安静得能听见自己",
                "把明天想说的话先想好了",
                "翻来覆去，睡不着",
                "想再看一眼时间，又忍住了",
            ),
        )

        /**
         * 播种。
         *
         * 为什么不是直接拿 `dayIndex` 当种子：连续的两天只差 1，
         * 线性同余的第一输出会高度相关 ⇒ 周一会和周二长得几乎一样。
         * 先过一遍雪崩混合，把相邻输入彻底打散。
         */
        private fun seedOf(dayIndex: Long, salt: Long): Long {
            var x = dayIndex * -0x61c8864680b583ebL + salt
            x = (x xor (x ushr 33)) * -0xae502812aa7333L
            x = (x xor (x ushr 29)) * -0x3b314601e57a13adL
            return x xor (x ushr 32)
        }
    }
}

/** 一天里的四段。与 `:legacy` 的 `DayPhase` 不同：这里只用于选文案，不判时段语义。 */
@Serializable
enum class LifeformBand { MORNING, DAY, EVENING, NIGHT }

/**
 * 一条她自己过的时间。
 *
 * @property atHour 0.0..24.0，一位小数。
 * @property text 第一人称视角下说得出口的短句；不含引号、不含动作描写。
 */
@Serializable
data class LifeformBeat(
    val atHour: Double,
    val text: String,
) {
    init {
        require(atHour >= 0.0 && atHour < 24.0) { "atHour 越界：$atHour" }
        require(text.isNotBlank()) { "beat 文案不能为空" }
    }

    val band: LifeformBand get() = LifeformDay.bandOf(atHour)
}

/**
 * SplitMix64。
 *
 * 选它而不是 `java.util.Random`：① 不依赖 JVM 实现细节，
 * 同一颗种子在 JVM / ART / 未来的 KMP 上给出同一串数；
 * ② 状态只有一个 Long，序列化/搬运都不用管。
 */
private class Rng(seed: Long) {
    private var state: Long = if (seed == 0L) -0x61c8864680b583ebL else seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** `[0, bound)`。`ushr` 保证非负，`%` 才不会给出负数下标。 */
    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound.toLong()).toInt()

    /** `[0.0, 1.0)`。 */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()
}
