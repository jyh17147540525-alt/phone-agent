package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常驻 root 通道：**帧**与**配对**。
 *
 * ⚠️ 这组测试保护的不是"能不能跑通"，而是两条在真机上极难复现的性质：
 *   ① 分片/粘包不能错位；② 乱序应答不能被当成别人的结果。
 *   这两件事一旦出错，表现是"点错了地方"，而不是报错。
 */
class PrivilegeWireTest {

    // ═══════════════════════════════════════════════════════════
    //  帧编解码
    // ═══════════════════════════════════════════════════════════

    private fun reply(id: Int, code: Int, out: String, err: String = ""): ByteArray =
        PrivilegeWire.Frame(
            kind = PrivilegeWire.Kind.REPLY,
            id = id,
            code = code,
            body = PrivilegeWire.encodeOutput(out, err),
        ).encode()

    @Test
    fun `单帧往返`() {
        val bytes = PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 7, 3, byteArrayOf(1, 2, 3)).encode()
        val d = PrivilegeWire.decode(bytes)
        assertEquals(1, d.frames.size)
        assertEquals(7, d.frames[0].id)
        assertEquals(3, d.frames[0].code)
        assertEquals(listOf<Byte>(1, 2, 3), d.frames[0].body.toList())
        assertEquals(0, d.leftover.size)
    }

    @Test
    fun `两帧粘在一起能一次解完`() {
        val d = PrivilegeWire.decode(reply(1, 0, "a") + reply(2, 0, "b"))
        assertEquals(2, d.frames.size)
        assertEquals(listOf(1, 2), d.frames.map { it.id })
        assertEquals(0, d.leftover.size)
    }

    @Test
    fun `分片读取不丢字节`() {
        val all = reply(1, 0, "第一段") + reply(2, 0, "第二段")
        // 切在**第一帧正文中间**：这才叫半帧（切在帧边界上就是"刚好读完一帧"）
        val cut = PrivilegeWire.HEADER + 2
        val half = all.copyOfRange(0, cut)

        val first = PrivilegeWire.decode(half)
        assertEquals("半帧不该解出任何东西", 0, first.frames.size)
        assertEquals("半帧必须原样留着", half.size, first.leftover.size)

        val second = PrivilegeWire.decode(first.leftover + all.copyOfRange(cut, all.size))
        assertEquals(2, second.frames.size)
        assertEquals(0, second.leftover.size)
    }

    @Test
    fun `长度前缀过大时拒绝而不是硬分配`() {
        // 手工造一个声称 0x7FFFFF00 字节的帧头
        val bad = ByteArray(16)
        bad[0] = 0x7F
        bad[1] = 0xFF.toByte()
        bad[2] = 0xFF.toByte()
        bad[3] = 0x00
        bad[4] = PrivilegeWire.Kind.REPLY.wire.toByte()
        val d = PrivilegeWire.decode(bad)
        assertEquals(0, d.frames.size)
        assertEquals("坏头之后不再猜着解", 0, d.leftover.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  参数与输出编码
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `argv 往返保住中文空格与斜杠`() {
        val argv = listOf("settings", "put", "system", "screen_brightness", "128", "带 空格/斜杠")
        assertEquals(argv, PrivilegeWire.decodeArgv(PrivilegeWire.encodeArgv(argv)))
    }

    @Test
    fun `参数里有 NUL 直接拒绝`() {
        var threw = false
        try {
            PrivilegeWire.encodeArgv(listOf("a", "b\u0000c"))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("协议表达不了 NUL，就必须显式失败", threw)
    }

    @Test
    fun `stdout 里出现分隔符时 stderr 仍然完整`() {
        val (out, err) = PrivilegeWire.decodeOutput(PrivilegeWire.encodeOutput("前\u0000后", "错"))
        assertEquals("前", out)
        assertEquals("后\u0000错", err)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★ 配对：乱序、缺失、断线
    // ═══════════════════════════════════════════════════════════

    private fun client(port: PrivilegePort) = PrivilegeClient(port)

    @Test
    fun `批量一次往返，按 id 配对`() {
        var trips = 0
        var sentCalls = 0
        val c = client { req ->
            trips++
            sentCalls = PrivilegeWire.decode(req).frames.size
            // 故意倒序回，模拟多线程守护进程
            reply(3, 0, "C") + reply(2, 0, "B") + reply(1, 0, "A")
        }

        val out = c.send(
            PrivilegeClient.Call(RootVerb.PING, listOf("ping")),
            PrivilegeClient.Call(RootVerb.QUERY, listOf("pkg", "com.a")),
            PrivilegeClient.Call(RootVerb.CAPTURE, listOf("raw")),
        )

        assertEquals("必须一次往返", 1, trips)
        assertEquals(3, sentCalls)
        assertEquals(
            listOf(
                PrivilegeOutcome.Ok("A"),
                PrivilegeOutcome.Ok("B"),
                PrivilegeOutcome.Ok("C"),
            ),
            out,
        )
    }

    @Test
    fun `缺应答的调用拿不到别人的结果`() {
        val c = client { _ -> reply(1, 0, "只有第一条") }
        val out = c.send(
            PrivilegeClient.Call(RootVerb.PING, listOf("a")),
            PrivilegeClient.Call(RootVerb.READ, listOf("b")),
        )
        assertEquals(PrivilegeOutcome.Ok("只有第一条"), out[0])
        assertEquals("★ 不许用最近的应答顶上", PrivilegeOutcome.NoReply, out[1])
    }

    @Test
    fun `断线是断线，不是命令失败`() {
        val c = client { _ -> throw PrivilegeClosed("socket closed") }
        val out = c.send(
            PrivilegeClient.Call(RootVerb.PING, listOf("a")),
            PrivilegeClient.Call(RootVerb.EXEC, listOf("b")),
        )
        assertEquals(listOf(PrivilegeOutcome.DaemonDown, PrivilegeOutcome.DaemonDown), out)
    }

    @Test
    fun `非零退出保留 stdout 与 stderr`() {
        val c = client { _ -> reply(1, 2, "部分输出", "permission denied") }
        val out = c.send(PrivilegeClient.Call(RootVerb.EXEC, listOf("ls", "/data")))
        assertEquals(PrivilegeOutcome.Exit(2, "部分输出", "permission denied"), out.single())
    }

    @Test
    fun `守护进程报错单独成一类`() {
        val c = client { _ ->
            PrivilegeWire.Frame(
                kind = PrivilegeWire.Kind.ERROR,
                id = 1,
                code = 0,
                body = "动词未在白名单：rm".toByteArray(),
            ).encode()
        }
        val out = c.send(PrivilegeClient.Call(RootVerb.EXEC, listOf("rm", "-rf")))
        assertTrue(out.single() is PrivilegeOutcome.DaemonError)
        assertTrue((out.single() as PrivilegeOutcome.DaemonError).reason.contains("白名单"))
    }

    @Test
    fun `空应答是协议错误`() {
        val c = client { _ -> ByteArray(0) }
        val out = c.send(PrivilegeClient.Call(RootVerb.PING, listOf("a")))
        assertTrue(out.single() is PrivilegeOutcome.Protocol)
    }

    @Test
    fun `应答里混入请求帧不会被当成结果`() {
        val c = client { _ ->
            PrivilegeWire.Frame(PrivilegeWire.Kind.REQUEST, 1, RootVerb.PING.wire, byteArrayOf()).encode()
        }
        val out = c.send(PrivilegeClient.Call(RootVerb.PING, listOf("a")))
        assertTrue(out.single() is PrivilegeOutcome.Protocol)
    }

    @Test
    fun `空批次不发包`() {
        var trips = 0
        val c = client { _ ->
            trips++
            ByteArray(0)
        }
        assertEquals(emptyList<PrivilegeOutcome>(), c.send(emptyList()))
        assertEquals(0, trips)
    }

    @Test
    fun `每条调用的 id 都是新的`() {
        val seen = mutableListOf<List<Int>>()
        val c = client { req ->
            seen += PrivilegeWire.decode(req).frames.map { it.id }
            PrivilegeWire.decode(req).frames.map { f ->
                PrivilegeWire.Frame(PrivilegeWire.Kind.REPLY, f.id, 0, PrivilegeWire.encodeOutput("", "")).encode()
            }.fold(ByteArray(0)) { acc, b -> acc + b }
        }
        c.send(
            PrivilegeClient.Call(RootVerb.PING, listOf("a")),
            PrivilegeClient.Call(RootVerb.PING, listOf("b")),
        )
        c.send(PrivilegeClient.Call(RootVerb.PING, listOf("c")))
        val flat = seen.flatten()
        assertEquals("id 不能重复，否则配对会串", flat.size, flat.toSet().size)
        assertFalse(flat.contains(0))
    }
}
