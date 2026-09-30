package com.pocketagent.personalogic

import kotlinx.serialization.Serializable

/**
 * 一条微调来自哪个触发源。
 *
 * | 源 | 例子 | 生效方式 |
 * |---|---|---|
 * | [EXPLICIT] | 「以后叫我老张」 | **立即生效**（置信度 1.0） |
 * | [IMPLICIT] | 用户打断 / 重说 / 回复变短 | 进候选区，攒够证据才生效 |
 * | [BEHAVIORAL] | 总在 23:00 后要求简短 | 进候选区，门的门槛更高 |
 */
@Serializable
enum class DeltaSource {
    EXPLICIT,
    IMPLICIT,
    BEHAVIORAL,
}

/**
 * 一条 `PersonaDelta` 的生命周期状态。
 *
 * ⚠️ [CANDIDATE] **不是**"还没生效的临时数据"，它是**正式落库的账本条目**。
 *    原因：候选区的意义是"让用户能看见 ta 正在被怎么理解"。
 *    如果候选只活在内存里，进程一重启就没了，那么「被一句话改坏」的
 *    保护也就没了 —— 打断三次、重启三次、第四次直接生效。
 */
@Serializable
enum class DeltaState {

    /** 已记录、尚未生效。可被引用给用户看，但**不可被助理说出口当作既成事实** */
    CANDIDATE,

    /** 已生效，参与 [PersonaSpec.derive] 的回放 */
    ACTIVE,

    /**
     * 已回滚。
     *
     * ★ **原地改状态，不追加一条反向 delta。**
     *
     * 追加反向 delta 的写法在这里是错的：`derive` 按时间顺序回放，
     * 一条 `A→B` 加一条 `B→A` 会让"当前人格"看起来回到了 A，
     * 但账本里**留下了两条记录**。用户问"你为什么变了"，助理会回答
     * 两件互相抵消的事；而"我曾经把称呼从 A 改成 B 又改回来"这个事实
     * 恰恰是**最该被如实说明**的。
     *
     * 所以历史是"这一条被撤销了"，不是"又发生了一次相反的变更"。
     */
    ROLLED_BACK,
}

/**
 * 一条变更记录 —— 「让改变可见」的**唯一载体**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 为什么它是需求②成立的必要条件
 * ═══════════════════════════════════════════════════════════════
 *
 * 需求②要的体验是「ta 为我做出了改变」。而这句话要成立，就意味着
 * 助理能说出类似：
 *
 * > 「你上次说我讲废话，我现在尽量一句说完。」
 *
 * 数据库里的一行记录**不构成**这个体验 —— 用户看不见它。
 * 一句「我记得你不喜欢这样」才构成。
 *
 * ⇒ 所以 [evidence] 不是"调试信息"，它是**可以被复述的原始依据**。
 *    也正因如此，它被设为必填：一条说不出理由的变更，等于一条
 *    用户无法追溯的变更。用户问"你为什么变了"时，助理只能沉默。
 *
 * @property id 稳定标识，用于回滚与跨进程引用（见 [Companion.idOf]）
 * @property field 被改的白名单字段。**类型上就不可能是骨骼**
 * @property oldValue 生效前的值（归一后）。用于让用户看到"改前改后"
 * @property newValue 归一后的新值
 * @property source 触发源
 * @property evidence 触发证据。**可为用户复述的，不是内部日志**
 * @property confidence 本条**自身**的置信度（不含同桶其余条目）。
 *   合并后的置信度由 [CandidateGate.combine] 现算 —— 存合并值会让
 *   "后来又攒了一条证据"无法增量重算
 * @property state 生命周期状态
 * @property createdAt 产生时刻（毫秒）。由调用方传入，见类注释末尾
 */
@Serializable
data class PersonaDelta(
    val id: String,
    val field: PersonaField,
    val oldValue: String,
    val newValue: String,
    val source: DeltaSource,
    val evidence: List<String>,
    val confidence: Double,
    val state: DeltaState,
    val createdAt: Long,
) {

    init {
        require(id.isNotBlank()) { "delta id 不能为空" }
        require(evidence.isNotEmpty()) {
            "delta 必须带至少一条触发证据 —— 否则用户问「你为什么变了」时无从回答"
        }
        require(confidence in 0.0..1.0) { "confidence 必须在 0..1 内，当前为 $confidence" }

        // 归一性检查。**不接受未归一的 newValue**：账本的键是
        // (field, normalizedValue)，混杂着 " 40" 与 "40" 会让候选区
        // 的计数悄悄分裂成两桶。
        require(newValue == newValue.trim()) { "newValue 必须已归一（无首尾空白），当前为「$newValue」" }
    }

    /** 是否仍在候选区（不可被助理当作既成事实引用） */
    fun isCandidate(): Boolean = state == DeltaState.CANDIDATE

    /** 是否已生效 */
    fun isActive(): Boolean = state == DeltaState.ACTIVE

    /**
     * 候选区分桶键。
     *
     * 键 = `(字段, 归一后的新值)`。**值必须参与分桶**：
     *
     * 用户先说「叫我老张」（攒了 2 条证据），又说「叫我张总」（攒了 1 条）。
     * 如果键里不含值，两条会被算成同一桶的 3 条证据 → **立即生效**，
     * 而其中 2 条指向的其实是另一个称呼。这正是"被一句话改坏"的
     * 镜像版本："被两句互相矛盾的话改坏"。
     */
    fun candidateKey(): String = "${field.name}\u0000$newValue"

    companion object {

        private const val ID_PREFIX = "pd-"

        /** 按序号生成 id。序号由 [DefaultPersonaTuner] 单调递增地分配 */
        fun idOf(sequence: Int): String = "$ID_PREFIX$sequence"

        /**
         * 从 id 反解序号；不是本类生成的 id 则返回 null。
         *
         * 用途只有一个：`restore()` 之后把序号计数器推到历史最大值之后，
         * 避免新 delta 与历史 delta **撞 id**（撞了之后回滚会回滚错一条）。
         */
        fun sequenceOf(id: String): Int? =
            id.removePrefix(ID_PREFIX).takeIf { it != id }?.toIntOrNull()
    }
}