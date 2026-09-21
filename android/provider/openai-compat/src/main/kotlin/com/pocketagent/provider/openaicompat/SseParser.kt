package com.pocketagent.provider.openaicompat

import okio.BufferedSource

/**
 * Server-Sent Events 解析器。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么不用 OkHttp 的 okhttp-sse 模块？
 * ═══════════════════════════════════════════════════════════════
 *
 * 因为各大厂商的"OpenAI 兼容"实现都不完全符合 SSE 规范，常见的坑：
 *
 *  1. **不发送 `event:` 字段**，只有裸 `data:`
 *  2. **不使用双换行分隔事件**，而是一行一个 JSON
 *  3. **结束标记不统一**：有的发 `data: [DONE]`，有的直接断流，有的发 `data: {}`
 *  4. **keep-alive 注释行**：部分厂商发 `: ping` 保活，必须忽略
 *  5. **JSON 里带换行**：极少数厂商会把多行 JSON 塞进单个 data 字段
 *  6. **空 data 行**：`data: ` 后面什么都没有
 *
 * 自己实现一个宽容的解析器比适配 okhttp-sse 更省事，也更容易针对厂商打补丁。
 *
 * ═══════════════════════════════════════════════════════════════
 *  设计要点
 * ═══════════════════════════════════════════════════════════════
 *
 * - **流式读取，不缓冲整个响应体**：模型输出可能很长，全缓冲会吃内存
 * - **必须支持取消**：用户点中止时，调用方取消协程，读取循环随之退出
 * - **不做 JSON 反序列化**：只负责切分出 data 载荷，解析交给上层
 *   （这样解析器本身可以纯逻辑测试，不需要构造完整 Provider）
 */
class SseParser(private val source: BufferedSource) {

    /**
     * 逐条产出 data 载荷。
     *
     * 调用方用 `for (payload in parser) { ... }` 消费。
     * 遇到 `[DONE]` 会返回 [SseEvent.Done]，之后循环结束。
     */
    operator fun iterator(): Iterator<SseEvent> = object : Iterator<SseEvent> {
        private var nextEvent: SseEvent? = null
        private var finished = false

        override fun hasNext(): Boolean {
            if (nextEvent != null) return true
            if (finished) return false
            nextEvent = readNext()
            return nextEvent != null
        }

        override fun next(): SseEvent {
            // 已经吐过 Done 之后，再调 next() 属于调用方用错，直接抛而不是继续读流
            if (nextEvent == null && finished) throw NoSuchElementException("SSE 流已结束")
            val e = nextEvent ?: readNext() ?: throw NoSuchElementException("SSE 流已结束")
            nextEvent = null
            if (e is SseEvent.Done) finished = true
            return e
        }
    }

    /**
     * 读取下一条事件。
     *
     * 返回 null 表示流正常结束（对端关闭连接）。
     */
    private fun readNext(): SseEvent? {
        while (true) {
            // 对端已关闭且无缓冲数据 → 结束
            if (source.exhausted()) return null

            val rawLine = source.readUtf8Line() ?: return null
            val line = rawLine.trimEnd('\r', '\n')

            // 空行：SSE 的事件分隔符，跳过
            if (line.isEmpty()) continue

            // 注释行（保活 ping）：以 ':' 开头，忽略
            if (line.startsWith(':')) continue

            // 非 data 字段（event: / id: / retry:）：忽略，我们只关心数据
            if (!line.startsWith(DATA_PREFIX)) continue

            val payload = line.substring(DATA_PREFIX.length).trim()

            // 空载荷：跳过（部分厂商会发 `data: `）
            if (payload.isEmpty()) continue

            // 结束标记：各家写法不一，全部覆盖
            if (payload in DONE_MARKERS) return SseEvent.Done

            return SseEvent.Data(payload)
        }
    }

    companion object {
        private const val DATA_PREFIX = "data:"

        /**
         * 已知的流结束标记。
         *
         * ⚠️ 新增厂商适配时，若遇到新的结束写法，加到这里。
         *
         * ⚠️ **不要把 `{}` / `null` 加进来。** 它们曾经在表里，是个隐患：
         *    部分厂商会在流中途发一个空的 data 载荷（keep-alive 或空 delta），
         *    若把它当成结束，响应会被**静默截断**——对 agent 来说等于拿到半份
         *    动作计划，且没有任何报错。而放它们过去是无害的：空 JSON 反序列化
         *    后 choices 为空，上层什么都不会发出；真正的结束由对端关闭连接触发，
         *    读取循环自然退出。
         *
         * 判断依据：**宁可多读一行，不可少读一行。**
         */
        private val DONE_MARKERS = setOf(
            "[DONE]",
            "[done]",
            "DONE",
            "done",
        )
    }
}

/** SSE 事件 */
sealed interface SseEvent {
    /** 一条数据载荷（JSON 字符串） */
    data class Data(val payload: String) : SseEvent

    /** 流结束 */
    data object Done : SseEvent
}

/**
 * 判断一行是否可能是 SSE 数据行。
 *
 * 用于"非流式降级"场景：某些厂商在 stream=true 时实际返回的是
 * 一个完整的 JSON（Content-Type 是 application/json 而非 text/event-stream）。
 * 这时需要识别出来并整体解析。
 */
fun looksLikeSseStream(contentType: String?, firstLine: String?): Boolean {
    if (contentType != null && contentType.contains("text/event-stream", ignoreCase = true)) {
        return true
    }
    // 有些厂商 Content-Type 给的是 text/plain，只能靠内容判断
    return firstLine?.trimStart()?.startsWith("data:") == true
}
