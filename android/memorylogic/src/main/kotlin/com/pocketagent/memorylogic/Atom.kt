package com.pocketagent.memorylogic

/**
 * L1 原子记忆的**四类**。
 *
 * ★ 移植自 TencentDB Agent Memory 的 L1 层定义（事实 / 偏好 / 约束 / 阶段结论）。
 *
 * ⚠️ 这四类是**闭集**，不是开放标签：L2 场景归纳按类型分桶、L3 画像蒸馏
 * 只吃 [PREFERENCE] 与 [CONSTRAINT]（那才是"你是谁"的证据），
 * 而 `:personalogic` 的微调证据源只认 [PREFERENCE]。
 * 所以"模型返回了一个没见过的类别"必须**被丢弃并记下原因**，
 * 而不是硬塞进 [FACT] —— 硬塞会让偏好在蒸馏时凭空消失，且无人知道。
 */
enum class AtomKind(val label: String) {
    FACT("事实"),
    PREFERENCE("偏好"),
    CONSTRAINT("约束"),
    STAGE_CONCLUSION("阶段结论"),
    ;

    companion object {
        /**
         * 把 LLM 返回的类别名解析成枚举 —— **不认识就返回 null，不兜底**。
         *
         * 容忍大小写、前后空白、下划线/连字符分隔与中英文别名：
         * 模型输出这几种写法全都出现过，为风格差异丢弃一条真记忆是净损失。
         * 但**语义不认识就是不认识**，这一条不能放宽。
         */
        fun byName(raw: String): AtomKind? {
            val squashed = squashName(raw)
            if (squashed.isEmpty()) return null
            for (kind in entries) {
                if (squashName(kind.name) == squashed) return kind
            }
            return ALIASES[squashed]
        }

        /**
         * 别名表。⚠️ 键必须**预先 squash 过**（小写、只留字母数字）——
         * 因为查表用的是 squash 之后的值，键里留个下划线就永远匹配不上。
         */
        private val ALIASES: Map<String, AtomKind> = mapOf(
            "facts" to FACT,
            "事实" to FACT,
            "preferences" to PREFERENCE,
            "偏好" to PREFERENCE,
            "喜好" to PREFERENCE,
            "constraints" to CONSTRAINT,
            "约束" to CONSTRAINT,
            "限制" to CONSTRAINT,
            "stageconclusion" to STAGE_CONCLUSION,
            "阶段结论" to STAGE_CONCLUSION,
            "结论" to STAGE_CONCLUSION,
        )
    }
}

/** 归一化一个字段名 / 类别名：去空白、转小写、只留字母与数字（中文照留）。 */
internal fun squashName(raw: String): String =
    raw.trim().lowercase().filter { it.isLetterOrDigit() }

/**
 * 一条原子记忆。
 *
 * [sourceTurnIds] 是**硬要求**：没有溯源的记忆等于模型编的。
 * 「每条记忆可回溯到源 Turn」是移植映射表里的一行，不是修饰。
 */
data class Atom(
    val id: String,
    val kind: AtomKind,
    val text: String,
    val sourceTurnIds: List<String>,
    val confidence: Double,
    val at: Long,
) {
    init {
        require(id.isNotBlank()) { "Atom.id 不能为空" }
        require(text.isNotBlank()) {
            "空文本的原子没有意义 —— 它应该被 AtomExtractor 丢弃并记原因，而不是落库"
        }
        require(sourceTurnIds.isNotEmpty()) { "原子必须能回溯到源 Turn" }
        require(confidence in 0.0..1.0) {
            "置信度必须落在 0..1，得到 $confidence —— 越界值应被丢弃，不该被静默钳制"
        }
    }

    companion object {
        const val ID_PREFIX = "am-"

        /** 短标识长度。20 位十六进制 = 80 bit，对"同一用户的记忆条数"这个量级绰绰有余。 */
        const val ID_LENGTH = 20

        /**
         * 原子 id 由 **[类别 + 归一化文本]** 决定 —— **确定性**，不是自增序号。
         *
         * 这样做的直接收益：同一段对话被重复提取（或换个模型重跑）时，
         * 得到的是**同一个 id**，于是重复天然被识别成"同一条"而不是两条。
         * 代价是改一个字的文本会变成新原子 —— 那是对的，L2/L3 负责合近似项。
         */
        fun idOf(kind: AtomKind, text: String): String =
            ID_PREFIX + Hashing.shortHex("${kind.name}\u0000${text.trim()}", ID_LENGTH)

        /**
         * 同一条原子被再次提取时的合并：置信度取**更高**、溯源**并集**、
         * 时间取**更早**（第一次见到它的时刻）。
         *
         * ⚠️ 置信度绝不能取平均：一条原子被确认两次，置信度不该下降。
         */
        fun merge(a: Atom, b: Atom): Atom {
            require(a.id == b.id) { "只能合并同一条原子：${a.id} vs ${b.id}" }
            return a.copy(
                confidence = maxOf(a.confidence, b.confidence),
                sourceTurnIds = (a.sourceTurnIds + b.sourceTurnIds).distinct(),
                at = minOf(a.at, b.at),
            )
        }
    }
}