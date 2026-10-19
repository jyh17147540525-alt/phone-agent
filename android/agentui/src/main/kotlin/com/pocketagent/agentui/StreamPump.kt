package com.pocketagent.agentui

/**
 * 流式泵：把模型的流式分片喂成 [CardEvent]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么不让 UI 直接往卡片里塞文字
 * ═══════════════════════════════════════════════════════════════
 *
 * 流式最诱人的写法是"每来一片就 append 到界面上的字符串"。
 * 那样做的三个后果，全部是**静默**的：
 *
 *  1. 推理分片混进正文 —— 用户把模型的草稿当成回答读；
 *  2. 半截工具调用变成一张"我要执行 xxx"的卡 —— 名字还没到，卡上已经写了；
 *  3. 流断在半路，说卡永远停在"正在输入…" —— 没有人收到过"结束"。
 *
 * 所以分片先过这里，变成事件；事件再过 [Timeline.reduce]，变成卡片。
 * 这条路上每一步都是纯函数，可以离线钉住。
 *
 * ⚠️ 本模块**不依赖** `:provider`：这里的 [Chunk] 是 `ChatChunk` 的镜像形状，
 *    由 `:app` 负责搬运（与 [CardProjection.ToolResult] 同一条纪律）。
 */
class StreamPump(private val sayMaxChars: Int = SAY_MAX_CHARS) {

    /** 流式分片（`ChatChunk` 的镜像，只保留本层需要的字段）。 */
    sealed interface Chunk {

        /** 正文增量。 */
        data class Delta(val text: String) : Chunk

        /**
         * 推理增量（`reasoning_content` 之类）。
         *
         * ★ 它**不是**回答，绝不能混进说卡。见本文件顶部注释。
         */
        data class Reasoning(val text: String) : Chunk

        /** 工具调用增量（一次调用可能拆成多片到达）。 */
        data class ToolCall(
            val id: String?,
            val name: String?,
            val argumentsFragment: String?,
        ) : Chunk

        /**
         * 结束分片。
         *
         * ⚠️ 真实 provider 的流**经常不送这一片**（连接被掐、进程被杀）。
         *    所以 [StreamPump.end] 是必需的，不能只靠它。
         */
        data class Done(val finishReason: String?) : Chunk
    }

    /** 一片分片喂进去之后，产出的东西。 */
    data class Emission(
        /** 该进 [Timeline.reduce] 的事件。 */
        val events: List<CardEvent>,
        /**
         * 本次凑齐的工具调用（参数已完整）。
         *
         * ★ 只给**凑齐的**：半截调用交出去，调用方就会去执行一个参数不全的动作 ——
         *   而参数解析那边可能"尽力而为"，于是点到了别的东西。
         */
        val toolCalls: List<ReadyCall>,
        /** 累计推理字符数（不建卡，但要能看见）。 */
        val reasoningChars: Int,
        /** 正文是否已因超长被截断。 */
        val truncated: Boolean,
    )

    /**
     * 一次**凑齐了**的工具调用（可以拿去执行）。
     *
     * ⚠️ 刻意不叫 `ToolCall`：[Chunk.ToolCall] 是**可能半截**的分片。
     *    两者重名会让读代码的人分不清手里这个能不能直接执行 ——
     *    而拿半截参数去执行，后果是点到别的地方。
     */
    data class ReadyCall(val id: String, val name: String, val argumentsJson: String)

    private class Pending(val id: String?, var name: String?, val args: StringBuilder = StringBuilder())

    /** 按出现顺序保存未凑齐的工具调用。键是"分片给的 id"，匿名调用用 `anon-N`。 */
    private val pending = LinkedHashMap<String, Pending>()
    private var anonSeq = 0

    private var sayChars = 0
    private var open = false
    private var ended = false

    /** 累计推理字符数 —— 不建卡，但对外可见。 */
    var reasoningChars: Int = 0
        private set

    /** 正文是否被截断过。 */
    var truncated: Boolean = false
        private set

    /** 断流时丢掉的半截工具调用数（0 表示干净收尾）。 */
    var droppedToolCalls: Int = 0
        private set

    /** 当前是否有开着的说卡（UI 用它决定要不要显示"正在输入"）。 */
    val isSpeaking: Boolean get() = open

    init {
        require(sayMaxChars > 0) { "sayMaxChars 必须为正：$sayMaxChars" }
    }

    /**
     * 喂一片分片。
     *
     * @throws IllegalStateException 已经 [end] 过 —— 那是接线错误（一条流被接了两次），
     *   静默接受会让第二段流的内容悄悄续在第一段后面。
     */
    fun feed(chunk: Chunk, at: Long): Emission {
        check(!ended) { "流已结束，不能再喂分片（是不是把两条流接到了同一个泵上？）" }
        val events = mutableListOf<CardEvent>()
        val completed = mutableListOf<ReadyCall>()

        when (chunk) {
            is Chunk.Delta -> {
                // ⚠️ 判据是"**加上这片**会超"，不是"已经满了"。
                //    写成"已经满了才截断"的话，最后一片正好越过上限时会被整片吃下，
                //    上限形同虚设；而且这种行为随网络分包方式变化 ——
                //    同一段话因为分包不同，出来的长度就不同。
                when {
                    chunk.text.isEmpty() -> Unit // 空增量不是错误，只是没内容
                    sayChars + chunk.text.length > sayMaxChars -> {
                        // ★ 截断必须**说出来**：把卡关掉并置位，而不是安静地停止 append。
                        //   安静停止的结果是用户以为模型就说到这儿了。
                        if (open) {
                            events += CardEvent.AssistantDone(at)
                            open = false
                        }
                        truncated = true
                    }
                    else -> {
                        events += CardEvent.AssistantDelta(at, chunk.text)
                        sayChars += chunk.text.length
                        open = true
                    }
                }
            }

            // ★ 刻意不产生任何事件。理由见文件顶部：它是草稿，不是回答。
            //   不产生的**代价**是"看起来被丢掉了"，所以这里保留计数：
            //   调试面板与导出能看到"这次推理了 N 字"，而不是彻底无踪。
            is Chunk.Reasoning -> reasoningChars += chunk.text.length

            is Chunk.ToolCall -> {
                val key = chunk.id ?: pending.keys.lastOrNull() ?: "anon-${anonSeq++}"
                // 新 id 出现 ⇒ 上一段工具调用已经完整（provider 用 id 的切换当分隔符）。
                if (chunk.id != null && pending.isNotEmpty() && !pending.containsKey(key)) {
                    completed += drain()
                }
                // 首片就把名字带上；后续分片才补名的情况由下一行处理。
                val buf = pending.getOrPut(key) { Pending(chunk.id, chunk.name) }
                if (chunk.name != null) buf.name = chunk.name
                chunk.argumentsFragment?.let { buf.args.append(it) }
            }

            is Chunk.Done -> {
                completed += drain()
                if (open) {
                    events += CardEvent.AssistantDone(at)
                    open = false
                }
            }
        }

        return Emission(events, completed, reasoningChars, truncated)
    }

    /**
     * 收尾。
     *
     * ★ 这个函数存在的唯一理由是"流会断"。
     *   没有它，连接被掐时最后一张说卡永远是 RUNNING —— 界面上它还在打字，
     *   而实际上没有任何东西还会到来。这与"接管后卡片还在转"是同一类谎话。
     *
     * 幂等：重复调用不重复产生事件。
     */
    fun end(at: Long): List<CardEvent> {
        if (ended) return emptyList()
        ended = true
        val events = mutableListOf<CardEvent>()
        if (open) {
            events += CardEvent.AssistantDone(at)
            open = false
        }
        // 断流时手里那半截工具调用**不交出去** —— 参数不全会让人点到别的地方。
        // 但它不能消失得无声无息：计数留着，调用方可据此提示"有一次调用没收到完整参数"。
        droppedToolCalls = pending.size
        pending.clear()
        return events
    }

    /** 复位，供第二条流复用（不新建对象，避免泵被到处传）。 */
    fun reset() {
        pending.clear()
        anonSeq = 0
        sayChars = 0
        open = false
        ended = false
        reasoningChars = 0
        truncated = false
        droppedToolCalls = 0
    }

    /** 把手里所有未完成的工具调用交出去，并清空。名字缺失的不交（无法执行）。 */
    private fun drain(): List<ReadyCall> {
        val out = mutableListOf<ReadyCall>()
        for ((key, p) in pending) {
            val name = p.name ?: continue
            out += ReadyCall(p.id ?: key, name, p.args.toString())
        }
        pending.clear()
        return out
    }

    companion object {
        /** 说卡正文的上限。 */
        const val SAY_MAX_CHARS: Int = 16_000
    }
}
