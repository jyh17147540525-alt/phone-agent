package com.pocketagent.rootlogic

import java.util.concurrent.atomic.AtomicInteger

/**
 * 一次往返的载体。真实现是 `LocalSocket` / `Socket`；
 * 测试里是一段假字节。把它抽出来，是为了让"配对逻辑"能离线验证 ——
 * 而这层的 bug 在真机上表现为**点错了地方**，最难复现。
 */
fun interface PrivilegePort {
    /** 写入全部请求字节，阻塞读回全部应答字节。 */
    fun roundTrip(request: ByteArray): ByteArray
}

/** 连接断了（守护进程被杀 / `su` 被撤 / socket 超时）。 */
class PrivilegeClosed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 一批调用的结果，与输入**同序**一一对应。 */
sealed interface PrivilegeOutcome {

    /** 退出码 0。 */
    data class Ok(val stdout: String) : PrivilegeOutcome

    /** 跑完了但失败 —— 这是**真实结论**，不是故障。 */
    data class Exit(val code: Int, val stdout: String, val stderr: String) : PrivilegeOutcome

    /** 守护进程自己报错（动词不认识、参数没过校验、权限不足）。 */
    data class DaemonError(val reason: String) : PrivilegeOutcome

    /** 这一条没等到应答 —— 常见于守护进程处理到一半被打断。 */
    data object NoReply : PrivilegeOutcome

    /** 应答头损坏 / 与请求对不上。出现这个说明有粘包或版本不一致。 */
    data class Protocol(val reason: String) : PrivilegeOutcome

    /** 连接没了。**必须与 [Exit] 分开**：一个要用户去查 Magisk，一个只要看 stderr。 */
    data object DaemonDown : PrivilegeOutcome
}

/**
 * 常驻通道的客户端：**一次往返发一批**，按 id 配对。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须按 id 配对，而不是按顺序
 * ═══════════════════════════════════════════════════════════════
 *
 * 守护进程是多线程处理请求的，**应答回来不保证与请求同序**。
 * 按顺序取的后果不是"报个错"，而是：`tap(100,200)` 的应答被当成
 * `type_text("你好")` 的应答 —— 界面看着正常，动作全错位，
 * 而且**没有任何一处日志会显示异常**。
 *
 * ⚠️ 所以配对失败（缺应答 / 多余 id）一律走 [PrivilegeOutcome.NoReply]
 *    与 [PrivilegeOutcome.Protocol]，**绝不用"最近的应答"顶上**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 批量是延迟的关键
 * ═══════════════════════════════════════════════════════════════
 *
 * 一次任务常见形态是「取包信息 → 读屏 → 点一下 → 再读屏」。
 * 逐条发是 4 次往返；批量发是 1 次往返 + 4 条并行处理。
 * 在 uid 0 的 JVM 里这些动作本身就是毫秒级，**往返次数**才是瓶颈。
 */
class PrivilegeClient(
    private val port: PrivilegePort,
    private val ids: AtomicInteger = AtomicInteger(1),
) {

    /**
     * 发一批调用。
     *
     * @return 与 [calls] 同序的结果
     */
    fun send(calls: List<Call>): List<PrivilegeOutcome> {
        if (calls.isEmpty()) return emptyList()

        val assigned = calls.map { it.copy(id = ids.getAndIncrement()) }
        val request = ByteArrayOut(assigned.sumOf { PrivilegeWire.HEADER + it.body.size })
        for (c in assigned) request.write(c.frame().encode())

        val answer = try {
            port.roundTrip(request.toByteArray())
        } catch (e: PrivilegeClosed) {
            return List(calls.size) { PrivilegeOutcome.DaemonDown }
        }

        val decoded = PrivilegeWire.decode(answer)
        if (decoded.frames.isEmpty()) {
            return List(calls.size) {
                PrivilegeOutcome.Protocol("应答里没有完整帧（收到 ${answer.size} 字节）")
            }
        }

        val byId = decoded.frames.associateBy { it.id }
        return assigned.map { c ->
            val frame = byId[c.id]
                ?: return@map PrivilegeOutcome.NoReply
            when (frame.kind) {
                PrivilegeWire.Kind.ERROR ->
                    PrivilegeOutcome.DaemonError(String(frame.body, Charsets.UTF_8))
                PrivilegeWire.Kind.REPLY -> {
                    val (out, err) = PrivilegeWire.decodeOutput(frame.body)
                    if (frame.code == 0) PrivilegeOutcome.Ok(out)
                    else PrivilegeOutcome.Exit(frame.code, out, err)
                }
                // 请求帧出现在应答里：对端明显在回显，不能当成结果
                PrivilegeWire.Kind.REQUEST -> PrivilegeOutcome.Protocol("应答里混进了请求帧")
            }
        }
    }

    fun send(vararg calls: Call): List<PrivilegeOutcome> = send(calls.toList())

    /**
     * 一条调用。
     *
     * @param verb 见 [RootVerb]；**没有"执行任意命令"这个动词**
     * @param argv 参数；不经 shell，所以 `;` `|` `$()` 只是普通字符
     */
    data class Call(
        val verb: RootVerb,
        val argv: List<String>,
        val id: Int = 0,
    ) {
        val body: ByteArray get() = PrivilegeWire.encodeArgv(argv)

        fun frame(): PrivilegeWire.Frame = PrivilegeWire.Frame(
            kind = PrivilegeWire.Kind.REQUEST,
            id = id,
            code = verb.wire,
            body = body,
        )
    }

    private class ByteArrayOut(size: Int) {
        private val buf = ByteArray(size)
        private var at = 0
        fun write(bytes: ByteArray) {
            bytes.copyInto(buf, at)
            at += bytes.size
        }
        fun toByteArray(): ByteArray = buf.copyOfRange(0, at)
    }
}
