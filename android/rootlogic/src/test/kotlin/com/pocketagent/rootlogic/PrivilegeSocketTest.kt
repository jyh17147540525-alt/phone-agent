package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常驻通道的传输层 —— **真起一个 loopback 服务端**再跑完整往返。
 *
 * 这一层以前只有接口（`PrivilegePort`）与假实现（测试里的 `FakePort`）。
 * 也就是说"通道到底能不能把字节送出去"从来没被证过。这里用真 socket 证四件事：
 *
 * 1. 握手对得上 → 一批请求能拿到一批应答；
 * 2. token 不对 → 连接直接被拒（不是"连上再报错"）；
 * 3. 服务端没了 → 客户端抛 [PrivilegeClosed]，而不是把异常漏给上层；
 * 4. 大块输出（> 单次读取的缓冲区）不会撕裂。
 */
class PrivilegeSocketTest {

    private val token = "0123456789abcdef0123456789abcdef"

    private fun loop(handler: (ByteArray) -> DaemonLoop.WireReply = { DaemonLoop.WireReply.Ok(0, "pong", "") }): DaemonLoop =
        DaemonLoop(mapOf(RootVerb.PING to handler, RootVerb.QUERY to handler))

    private fun withServer(
        handler: (ByteArray) -> DaemonLoop.WireReply = { DaemonLoop.WireReply.Ok(0, "pong", "") },
        body: (DaemonSocketServer, PrivilegeSocketPort) -> Unit,
    ) {
        val loop = loop(handler)
        val server = DaemonSocketServer(loop, token)
        val port = server.start()
        try {
            body(server, PrivilegeSocketPort(port = port, token = token, readTimeoutMs = 5_000))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `握手成功之后一批请求能拿到对应应答`() = withServer { _, client ->
        val request = DaemonLoop.request(RootVerb.PING, 7, listOf("ping"))
        val reply = client.roundTrip(request)

        val frames = PrivilegeWire.decode(reply).frames
        assertEquals(1, frames.size)
        assertEquals(7, frames[0].id)
        assertEquals(PrivilegeWire.Kind.REPLY, frames[0].kind)
        assertEquals("pong" to "", DaemonLoop.outputOf(frames[0]))
    }

    @Test
    fun `同一连接上连续两批都通 不需要重新握手`() = withServer { _, client ->
        val first = client.roundTrip(DaemonLoop.request(RootVerb.PING, 1, listOf("a")))
        val second = client.roundTrip(DaemonLoop.request(RootVerb.PING, 2, listOf("b")))

        assertEquals(1, PrivilegeWire.decode(first).frames.size)
        assertEquals(2, PrivilegeWire.decode(second).frames[0].id)
    }

    @Test
    fun `一批里的两条请求各回一帧`() = withServer { _, client ->
        val batch = DaemonLoop.request(RootVerb.PING, 1, listOf("a")) +
            DaemonLoop.request(RootVerb.QUERY, 2, listOf("b"))

        val frames = PrivilegeWire.decode(client.roundTrip(batch)).frames

        assertEquals(2, frames.size)
        assertEquals(setOf(1, 2), frames.map { it.id }.toSet())
    }

    @Test
    fun `token 不对时连接被拒 客户端抛 PrivilegeClosed`() {
        val server = DaemonSocketServer(loop(), token)
        val port = server.start()
        try {
            val wrong = PrivilegeSocketPort(port = port, token = "ffffffffffffffffffffffffffffffff")
            val closed = try {
                wrong.roundTrip(DaemonLoop.request(RootVerb.PING, 1, listOf("x")))
                false
            } catch (e: PrivilegeClosed) {
                true
            }
            assertTrue("token 不对时必须拒绝", closed)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `服务端关掉之后客户端抛 PrivilegeClosed 而不是漏 IO 异常`() {
        val server = DaemonSocketServer(loop(), token)
        val port = server.start()
        val client = PrivilegeSocketPort(port = port, token = token, readTimeoutMs = 3_000, connectTimeoutMs = 1_000)
        client.roundTrip(DaemonLoop.request(RootVerb.PING, 1, listOf("a")))

        server.stop()

        val closed = try {
            client.roundTrip(DaemonLoop.request(RootVerb.PING, 2, listOf("b")))
            false
        } catch (e: PrivilegeClosed) {
            true
        }
        assertTrue("服务端消失后必须是 PrivilegeClosed", closed)
    }

    @Test
    fun `连不上时也是 PrivilegeClosed`() {
        val client = PrivilegeSocketPort(port = 1, token = token, connectTimeoutMs = 300)
        val closed = try {
            client.roundTrip(DaemonLoop.request(RootVerb.PING, 1, listOf("a")))
            false
        } catch (e: PrivilegeClosed) {
            true
        }
        assertTrue(closed)
    }

    @Test
    fun `大块输出不会撕裂`() = withServer(
        handler = { DaemonLoop.WireReply.Ok(0, "x".repeat(300_000), "") },
    ) { _, client ->
        val frames = PrivilegeWire.decode(
            client.roundTrip(DaemonLoop.request(RootVerb.QUERY, 1, listOf("dumpsys"))),
        ).frames

        assertEquals(1, frames.size)
        assertEquals(300_000, DaemonLoop.outputOf(frames[0]).first.length)
    }

    @Test
    fun `空请求不发包也不连服务端`() {
        val client = PrivilegeSocketPort(port = 1, token = token, connectTimeoutMs = 200)
        // 端口 1 连不上 —— 空请求若真去连，这里会抛 PrivilegeClosed。
        assertEquals(0, client.roundTrip(ByteArray(0)).size)
    }

    @Test
    fun `非请求帧不发出去`() {
        val client = PrivilegeSocketPort(port = 1, token = token, connectTimeoutMs = 200)
        val replyFrame = PrivilegeWire.Frame(PrivilegeWire.Kind.REPLY, 1, 0, ByteArray(0)).encode()
        assertEquals(0, client.roundTrip(replyFrame).size)
    }

    @Test
    fun `退出码非零照常回去 不当成传输错误`() = withServer(
        handler = { DaemonLoop.WireReply.Ok(1, "", "ls: 权限不够") },
    ) { _, client ->
        val frame = PrivilegeWire.decode(
            client.roundTrip(DaemonLoop.request(RootVerb.QUERY, 3, listOf("ls"))),
        ).frames.single()

        assertEquals(PrivilegeWire.Kind.REPLY, frame.kind)
        assertEquals(1, frame.code)
        assertEquals("ls: 权限不够", DaemonLoop.outputOf(frame).second)
    }

    @Test
    fun `常量时间比较对长度不同的输入也返回 false`() {
        assertTrue(constantTimeEquals("abc", "abc"))
        assertFalse(constantTimeEquals("abc", "abd"))
        assertFalse(constantTimeEquals("abc", "abcd"))
        assertFalse(constantTimeEquals("", "a"))
        assertTrue(constantTimeEquals("", ""))
    }

    @Test
    fun `readLine 遇到超长输入返回 null 而不是无限读`() {
        val input = java.io.ByteArrayInputStream(ByteArray(64) { 'A'.code.toByte() })
        assertNull(readLine(input, 8))
    }

    @Test
    fun `服务端只用 loopback`() = withServer { server, _ ->
        // 绑的不是 0.0.0.0 —— 从本机可用地址里挑一个非 loopback 的去连，应当连不上。
        val nonLoopback = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
        if (nonLoopback == null) return@withServer // 无外网地址的机器上跳过

        val client = PrivilegeSocketPort(
            host = nonLoopback.hostAddress,
            port = server.boundPort,
            token = token,
            connectTimeoutMs = 300,
        )
        val closed = try {
            client.roundTrip(DaemonLoop.request(RootVerb.PING, 1, listOf("a")))
            false
        } catch (e: PrivilegeClosed) {
            true
        }
        assertTrue("服务端不该在非 loopback 地址上可达", closed)
    }
}
