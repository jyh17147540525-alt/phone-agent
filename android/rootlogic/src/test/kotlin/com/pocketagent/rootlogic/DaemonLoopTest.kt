package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守护进程服务循环的纯逻辑测试。
 *
 * 这里测的全是"真机上不会报错、只会表现成怪现象"的分支：
 * 粘包、半帧、应答 id 错位、动词不认识、处理器抛异常。
 */
class DaemonLoopTest {

    private val ping: (ByteArray) -> DaemonLoop.WireReply = { body ->
        DaemonLoop.WireReply.Ok(0, "uid=0(root) argv=${PrivilegeWire.decodeArgv(body)}", "")
    }

    private fun loop(vararg extra: Pair<RootVerb, (ByteArray) -> DaemonLoop.WireReply>) =
        DaemonLoop(mapOf(RootVerb.PING to ping) + extra.toMap())

    // ── 基本往返 ─────────────────────────────────────────────

    @Test
    fun `一个请求回一帧且 id 原样带回`() {
        val out = ArrayList<PrivilegeWire.Frame>()
        val tail = loop().pump(
            leftover = ByteArray(0),
            incoming = DaemonLoop.request(RootVerb.PING, id = 7, argv = listOf("now")),
        ) { out += PrivilegeWire.decode(it).frames.single() }

        assertEquals(0, tail.size)
        assertEquals(1, out.size)
        val reply = out.single()
        assertEquals(PrivilegeWire.Kind.REPLY, reply.kind)
        assertEquals(7, reply.id)
        assertEquals(0, reply.code)
        assertTrue(DaemonLoop.outputOf(reply).first.contains("uid=0(root)"))
    }

    @Test
    fun `正文里的 argv 能原样到达处理器`() {
        var seen: List<String> = emptyList()
        val loop = loop(RootVerb.EXEC to { body -> seen = PrivilegeWire.decodeArgv(body); DaemonLoop.WireReply.Ok(0, "ok", "") })

        loop.handle(
            PrivilegeWire.Frame(
                PrivilegeWire.Kind.REQUEST, 1, RootVerb.EXEC.wire,
                PrivilegeWire.encodeArgv(listOf("getprop", "ro.build.version.sdk")),
            ),
        )

        assertEquals(listOf("getprop", "ro.build.version.sdk"), seen)
    }

    // ── 分片与粘包 ───────────────────────────────────────────

    @Test
    fun `一帧被切成两半时第一轮不出应答`() {
        val bytes = DaemonLoop.request(RootVerb.PING, id = 3, argv = listOf("x"))
        val replies = ArrayList<ByteArray>()
        val loop = loop()

        val tail = loop.pump(ByteArray(0), bytes.copyOfRange(0, 8)) { replies += it }
        assertEquals(8, tail.size)
        assertTrue(replies.isEmpty())

        val tail2 = loop.pump(tail, bytes.copyOfRange(8, bytes.size)) { replies += it }
        assertEquals(0, tail2.size)
        assertEquals(1, replies.size)
    }

    @Test
    fun `两帧粘在同一批里各回一帧`() {
        val replies = ArrayList<PrivilegeWire.Frame>()
        val batch = DaemonLoop.request(RootVerb.PING, 1, listOf("a")) +
            DaemonLoop.request(RootVerb.PING, 2, listOf("b"))

        loop().pump(ByteArray(0), batch) { replies += PrivilegeWire.decode(it).frames.single() }

        assertEquals(2, replies.size)
        assertEquals(listOf(1, 2), replies.map { it.id })
    }

    @Test
    fun `多帧加半帧时只回应整帧并把尾巴留下`() {
        val one = DaemonLoop.request(RootVerb.PING, 1, listOf("a"))
        val half = DaemonLoop.request(RootVerb.PING, 2, listOf("b"))
        val replies = ArrayList<PrivilegeWire.Frame>()

        val tail = loop().pump(ByteArray(0), one + half.copyOfRange(0, 5)) {
            replies += PrivilegeWire.decode(it).frames.single()
        }

        assertEquals(1, replies.size)
        assertEquals(5, tail.size)
    }

    // ── 拒绝与异常 ───────────────────────────────────────────

    @Test
    fun `动词不认识时回 ERROR 并继续服务`() {
        val frame = PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 9, code = 99, body = ByteArray(0))
        val reply = loop().handle(frame)

        assertEquals(PrivilegeWire.Kind.ERROR, reply.kind)
        assertEquals(9, reply.id)
        assertEquals(DaemonLoop.ErrorCode.UNKNOWN_VERB.wire, reply.code)
        assertTrue(String(reply.body, Charsets.UTF_8).contains("版本"))
    }

    @Test
    fun `认识的动词但没注册处理器也回 ERROR`() {
        val reply = loop().handle(
            PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 4, RootVerb.PROBE.wire, ByteArray(0)),
        )
        assertEquals(DaemonLoop.ErrorCode.UNKNOWN_VERB.wire, reply.code)
        assertEquals(4, reply.id)
    }

    @Test
    fun `收到不是请求的帧时回 ERROR 而不是崩`() {
        val reply = loop().handle(
            PrivilegeWire.Frame(PrivilegeWire.Kind.REPLY, 5, 0, ByteArray(0)),
        )
        assertEquals(PrivilegeWire.Kind.ERROR, reply.kind)
        assertEquals(DaemonLoop.ErrorCode.NOT_A_REQUEST.wire, reply.code)
    }

    @Test
    fun `处理器抛异常时回 ERROR 且带上异常类型`() {
        val loop = loop(RootVerb.EXEC to { error("boom") })
        val reply = loop.handle(
            PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 6, RootVerb.EXEC.wire, PrivilegeWire.encodeArgv(listOf("x"))),
        )

        assertEquals(PrivilegeWire.Kind.ERROR, reply.kind)
        assertEquals(6, reply.id)
        assertEquals(DaemonLoop.ErrorCode.HANDLER_FAILED.wire, reply.code)
        val message = String(reply.body, Charsets.UTF_8)
        assertTrue(message, message.contains("IllegalStateException"))
        assertTrue(message, message.contains("boom"))
    }

    @Test
    fun `处理器拒绝时回 REFUSED 并带上原因`() {
        val loop = loop(RootVerb.SETTINGS to { DaemonLoop.WireReply.Refused("key 不在白名单") })
        val reply = loop.handle(
            PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 8, RootVerb.SETTINGS.wire, PrivilegeWire.encodeArgv(listOf("system", "x", "1"))),
        )

        assertEquals(DaemonLoop.ErrorCode.REFUSED.wire, reply.code)
        assertEquals("key 不在白名单", String(reply.body, Charsets.UTF_8))
    }

    @Test
    fun `一条坏请求不会吃掉同一批里的好请求`() {
        val replies = ArrayList<PrivilegeWire.Frame>()
        val batch = PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 1, 77, ByteArray(0)).encode() +
            DaemonLoop.request(RootVerb.PING, 2, listOf("ok"))

        loop().pump(ByteArray(0), batch) { replies += PrivilegeWire.decode(it).frames.single() }

        assertEquals(listOf(1, 2), replies.map { it.id })
        assertEquals(PrivilegeWire.Kind.ERROR, replies[0].kind)
        assertEquals(PrivilegeWire.Kind.REPLY, replies[1].kind)
    }

    @Test
    fun `退出码非零仍然走 REPLY 帧`() {
        val loop = loop(RootVerb.EXEC to { DaemonLoop.WireReply.Ok(2, "", "No such file") })
        val reply = loop.handle(
            PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 1, RootVerb.EXEC.wire, PrivilegeWire.encodeArgv(listOf("ls"))),
        )

        assertEquals(PrivilegeWire.Kind.REPLY, reply.kind)
        assertEquals(2, reply.code)
        assertEquals("No such file", DaemonLoop.outputOf(reply).second)
    }

    // ── serve 循环 ───────────────────────────────────────────

    private class QueueChannel(private val chunks: List<ByteArray>) : DaemonChannel {
        val written = ArrayList<ByteArray>()
        private var at = 0

        override fun read(): ByteArray? = if (at < chunks.size) chunks[at++] else null

        override fun write(bytes: ByteArray) {
            written += bytes
        }
    }

    @Test
    fun `serve 在通道关闭时结束`() {
        val channel = QueueChannel(
            listOf(DaemonLoop.request(RootVerb.PING, 1, listOf("a")), ByteArray(0)),
        )
        val reason = loop().serve(channel)

        assertEquals("对端关闭", reason)
        assertEquals(1, channel.written.size)
    }

    @Test
    fun `serve 遇到停止条件立刻退出`() {
        val channel = QueueChannel(listOf(DaemonLoop.request(RootVerb.PING, 1, listOf("a"))))
        var rounds = 0
        val reason = loop().serve(channel, shouldStop = { rounds++ >= 0 })

        assertEquals("主动停止", reason)
        assertTrue(channel.written.isEmpty())
    }

    @Test
    fun `serve 能把分片跨轮拼起来`() {
        val whole = DaemonLoop.request(RootVerb.PING, 1, listOf("a"))
        val channel = QueueChannel(listOf(whole.copyOfRange(0, 10), whole.copyOfRange(10, whole.size)))

        loop().serve(channel)

        assertEquals(1, channel.written.size)
        assertEquals(PrivilegeWire.Kind.REPLY, PrivilegeWire.decode(channel.written.single()).frames.single().kind)
    }

    @Test
    fun `serve 的空批次不产生写入`() {
        val channel = QueueChannel(listOf(ByteArray(0)))
        loop().serve(channel)
        assertNull(null)
        assertTrue(channel.written.isEmpty())
    }

    @Test
    fun `达到轮数上限会退出而不是死循环`() {
        val channel = QueueChannel(List(10) { ByteArray(0) })
        assertEquals("达到轮数上限", loop().serve(channel, maxRounds = 3))
    }
}
