package com.pocketagent.memorylogic

/**
 * 一轮对话的说话人。
 *
 * [TOOL] 是 L0 里**体积最大的一类** —— 工具/能力的原始结果（无障碍树 dump、
 * 接口返回的整页 JSON、截图说明）会以指数速度把上下文顶爆。它就是上下文卸载
 * （[ContextOffloader]）的首要对象。
 */
enum class TurnRole {
    USER,
    ASSISTANT,

    /** 工具 / 能力的执行结果。卸载的主要来源。 */
    TOOL,
}

/**
 * L0 原始对话的一轮。
 *
 * ⚠️ 这是**全量**层：不做摘要、不丢字段。任何"为了省空间在这里做过滤"的想法
 * 都要先想清楚 —— L1 的原子提取、L3 的画像蒸馏都只能从**原始文本**里长出来，
 * 在 L0 就丢掉的细节永远回不来。
 */
data class Turn(
    val id: String,
    val sessionId: String,
    val role: TurnRole,
    val text: String,
    val at: Long,
) {
    init {
        require(id.isNotBlank()) {
            "Turn.id 不能为空 —— 每条记忆回溯到源 Turn 的全链路溯源靠它"
        }
        require(sessionId.isNotBlank()) {
            "Turn.sessionId 不能为空 —— 跨会话的区分（以及「上次我们说到」）靠它"
        }
    }

    /**
     * UTF-8 字节数。
     *
     * ⚠️ 卸载阈值按**字节**算而不是按 `text.length`：一个汉字 3 字节，
     * 按字符算会让中英文的阈值差三倍，而用户完全看不出为什么。
     */
    val byteSize: Int get() = text.toByteArray(Charsets.UTF_8).size
}

/**
 * L0 的落库出口。实现在 `:memory`（Room）；本模块只声明契约。
 *
 * ⚠️ 刻意是**同步**的：本模块零 IO 依赖，调用方（Android 侧）自己决定
 * 放到哪个 dispatcher 上。做成 `suspend` 会让"这行会不会阻塞主线程"
 * 变成需要读实现才能回答的问题（同 `:personalogic` §4.2.5-1 的取舍）。
 */
fun interface TurnSink {
    fun append(turn: Turn)
}

/**
 * L0 原始对话层 —— 全量保留每一轮。
 *
 * 判错的后果**是静默的**：少记一轮不会有任何报错，用户只会觉得
 * 「你怎么又忘了」。所以这里的每条行为都要能被离线断言钉住。
 */
class TurnLog(private val sink: TurnSink? = null) {

    private val turns = mutableListOf<Turn>()

    /**
     * 追加一轮。
     *
     * **幂等**：id 已存在时返回 `false` 且什么都不做（不重复进内存、不重复落库）。
     *
     * 为什么不抛异常：L0 的写入会重试（SAF / Room 都可能失败重来），
     * 重试撞上"已写入"是**正常路径**；抛异常会让一次网络抖动变成一次崩溃。
     * 但也不静默 —— 返回值就是那个可观测的信号。
     */
    @Synchronized
    fun append(turn: Turn): Boolean {
        if (turns.any { it.id == turn.id }) return false
        turns += turn
        sink?.append(turn)
        return true
    }

    @Synchronized
    fun contains(id: String): Boolean = turns.any { it.id == id }

    @Synchronized
    fun size(): Int = turns.size

    /** 按**插入顺序**返回全部轮次 —— L0 的顺序即真实发生的顺序。 */
    @Synchronized
    fun all(): List<Turn> = turns.toList()

    @Synchronized
    fun bySession(sessionId: String): List<Turn> = turns.filter { it.sessionId == sessionId }

    /** 最近 [n] 轮，**保持时间顺序**（最早的在前）。[n] 为 0 时返回空。 */
    @Synchronized
    fun recent(n: Int): List<Turn> {
        require(n >= 0) { "n 不能为负：$n" }
        return turns.takeLast(n)
    }

    /**
     * 体积超过 [thresholdBytes] 的轮次 —— 上下文卸载的候选。
     *
     * ⚠️ 这里**只筛不裁**：本方法不修改任何内容，卸载与否由
     * [ContextOffloader.offload] 在过完隐私关卡之后决定。
     */
    @Synchronized
    fun oversized(thresholdBytes: Int): List<Turn> {
        require(thresholdBytes >= 0) { "thresholdBytes 不能为负：$thresholdBytes" }
        return turns.filter { it.byteSize > thresholdBytes }
    }
}