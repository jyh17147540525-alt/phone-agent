package com.pocketagent.rootlogic

import java.io.ByteArrayOutputStream

/**
 * 守护进程的**服务循环**（纯逻辑，不碰 socket / Android）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 它是什么、不是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 是：一个「读字节 → 解帧 → 按动词分发 → 写回帧」的状态机。
 * 不是：socket 服务器、`app_process` 启动器、任何 Android API 调用。
 *
 * 这么切的理由是**可测**：把"分片/粘包/并发 id 错位/动词不认识/处理器抛异常"
 * 这些真正会出事的分支留在纯 Kotlin 里，用假通道跑 20 条断言；
 * 真机上要写的只剩「开 socket + 循环调 [pump]」这一层薄壳，
 * 而那层薄壳出错的表现是"连不上"，不会静默地应答错帧。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条硬纪律
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **一个请求一帧应答，id 原样回** —— 客户端靠 id 配对；错了就是"点错地方"
 *    而不是报错，是最难查的一类故障。
 * 2. **处理器抛异常 → ERROR 帧，绝不外抛** —— 守护进程一旦因为一条坏请求
 *    退出，客户端那边看到的是"所有 root 能力突然消失"，而真因是某条命令。
 * 3. **不认识的字 / 不认识的动词 → ERROR 帧，并继续服务** —— 半开连接、
 *    版本不匹配都会走到这里；退出的代价是用户要重新 su 一次。
 */
class DaemonLoop(
    private val handlers: Map<RootVerb, (ByteArray) -> WireReply>,
) {

    /** 一条应答的三种形状。 */
    sealed interface WireReply {

        /** 正常完成。[code] 一般 0，非 0 表示"命令跑了但退出码非零"。 */
        data class Ok(val code: Int, val stdout: String, val stderr: String) : WireReply

        /** 协议/权限层拒绝（参数不合法、动词被禁）。 */
        data class Refused(val reason: String) : WireReply
    }

    /** 错误帧的 code —— 与 `PrivilegeClient` 的 `PrivilegeOutcome.Protocol` 对齐。 */
    enum class ErrorCode(val wire: Int) {
        /** 动词不认识（客户端与守护进程版本不匹配）。 */
        UNKNOWN_VERB(1),

        /** 请求帧的 kind 不是 REQUEST。 */
        NOT_A_REQUEST(2),

        /** 处理器抛了异常。 */
        HANDLER_FAILED(3),

        /** 处理器拒绝了这条请求。 */
        REFUSED(4),
    }

    /**
     * 往 [buffer] 里追加"本次读到的字节 + 上一轮剩下的尾巴"，
     * 把所有完整请求都处理掉，返回**新的尾巴**。
     *
     * @param onReply 每生成一帧应答就回调一次（薄壳里直接写 socket）
     * @return 还没凑成整帧的剩余字节 —— 调用方必须原样保留到下一轮
     */
    fun pump(
        leftover: ByteArray,
        incoming: ByteArray,
        onReply: (ByteArray) -> Unit,
    ): ByteArray {
        val merged = if (leftover.isEmpty()) incoming else leftover + incoming
        val decoded = PrivilegeWire.decode(merged)

        for (frame in decoded.frames) {
            onReply(handle(frame).encode())
        }
        return decoded.leftover
    }

    /** 处理一帧请求，产出**一帧**应答（`PrivilegeWire.Frame`）。 */
    fun handle(frame: PrivilegeWire.Frame): PrivilegeWire.Frame {
        if (frame.kind != PrivilegeWire.Kind.REQUEST) {
            return error(frame.id, ErrorCode.NOT_A_REQUEST)
        }

        val verb = RootVerb.of(frame.code) ?: return error(frame.id, ErrorCode.UNKNOWN_VERB)
        val handler = handlers[verb] ?: return error(frame.id, ErrorCode.UNKNOWN_VERB)

        val reply = try {
            handler(frame.body)
        } catch (t: Throwable) {
            // ⚠️ 连 Error 都要吞：守护进程活着比"这条命令的异常语义正确"重要。
            return PrivilegeWire.Frame(
                kind = PrivilegeWire.Kind.ERROR,
                id = frame.id,
                code = ErrorCode.HANDLER_FAILED.wire,
                body = describe(t).toByteArray(Charsets.UTF_8),
            )
        }

        return when (reply) {
            is WireReply.Ok -> PrivilegeWire.Frame(
                kind = PrivilegeWire.Kind.REPLY,
                id = frame.id,
                code = reply.code,
                body = PrivilegeWire.encodeOutput(reply.stdout, reply.stderr),
            )

            is WireReply.Refused -> PrivilegeWire.Frame(
                kind = PrivilegeWire.Kind.ERROR,
                id = frame.id,
                code = ErrorCode.REFUSED.wire,
                body = reply.reason.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun error(id: Int, code: ErrorCode): PrivilegeWire.Frame =
        PrivilegeWire.Frame(
            kind = PrivilegeWire.Kind.ERROR,
            id = id,
            code = code.wire,
            body = describe(code).toByteArray(Charsets.UTF_8),
        )

    private fun describe(code: ErrorCode): String = when (code) {
        ErrorCode.UNKNOWN_VERB -> "这个动词不认识：客户端与守护进程的版本可能不一致，请重启常驻通道。"
        ErrorCode.NOT_A_REQUEST -> "收到一帧不是请求的数据。"
        ErrorCode.HANDLER_FAILED -> "处理这条请求时出错。"
        ErrorCode.REFUSED -> "这条请求被拒绝。"
    }

    private fun describe(t: Throwable): String =
        "${describe(ErrorCode.HANDLER_FAILED)}（${t.javaClass.simpleName}: ${t.message ?: "无消息"}）"

    companion object {

        /**
         * 组装一条请求帧（客户端用；测试也用它）。
         *
         * ⚠️ 与 [PrivilegeClient] 里的 `Call` 是同一份编码规则 ——
         *    这里单独留一个函数是为了让"编解码往返"能被单独测到。
         */
        fun request(verb: RootVerb, id: Int, argv: List<String>): ByteArray =
            PrivilegeWire.Frame(
                kind = PrivilegeWire.Kind.REQUEST,
                id = id,
                code = verb.wire,
                body = PrivilegeWire.encodeArgv(argv),
            ).encode()

        /** 把一帧应答的正文拆成 (stdout, stderr)。 */
        fun outputOf(frame: PrivilegeWire.Frame): Pair<String, String> =
            PrivilegeWire.decodeOutput(frame.body)
    }
}

/**
 * 一个最小的"字节进、字节出"通道 —— 真机上是 socket，测试里是内存队列。
 *
 * ⚠️ 刻意只暴露 [read] / [write]，不暴露 close/超时：
 *    这一层只负责搬运，生命周期由薄壳管。
 */
interface DaemonChannel {
    /** 读一批字节；返回 null 表示对端关闭。 */
    fun read(): ByteArray?

    fun write(bytes: ByteArray)
}

/**
 * 把 [DaemonLoop] 接到通道上跑到底。
 *
 * 这样薄壳里只剩「开 socket → 造 channel → 调 serve」—— 没有协议逻辑。
 *
 * @param shouldStop 每轮检查一次；测试里用一个可翻转的开关
 * @return 结束原因（对端关闭 / 主动停止）
 */
fun DaemonLoop.serve(
    channel: DaemonChannel,
    shouldStop: () -> Boolean = { false },
    maxRounds: Int = Int.MAX_VALUE,
): String {
    var leftover = ByteArray(0)
    var rounds = 0
    while (rounds++ < maxRounds) {
        if (shouldStop()) return "主动停止"

        val incoming = channel.read() ?: return "对端关闭"
        if (incoming.isEmpty()) continue

        val out = ByteArrayOutputStream()
        leftover = pump(leftover, incoming) { out.write(it) }
        if (out.size() > 0) channel.write(out.toByteArray())
    }
    return "达到轮数上限"
}
