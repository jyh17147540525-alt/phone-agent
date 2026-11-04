package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 常驻通道与 `su` 的**取舍**。
 *
 * 这段逻辑决定"这一趟到底动不动 su 进程"。它错了的后果很具体：
 * 要么每次点按都白等 100ms+ 的 fork，要么通道明明好用却一直退回 su
 * （等于新做的东西没接上），要么通道坏了却还在往里发（任务卡住）。
 */
class RootActionsDaemonTest {

    /** 记录 su 那条路被调了几次。 */
    private class SuRecorder : RootShellPort {
        val calls = ArrayList<List<String>>()
        override fun run(argv: List<String>): ShellResult {
            calls += argv
            return ShellResult(0, "su-ok", "")
        }
    }

    /** 通道：按脚本回一段字节，或者抛 [PrivilegeClosed]。 */
    private class FakePort(
        private val behavior: (ByteArray) -> ByteArray,
    ) : PrivilegePort {
        var rounds = 0
        override fun roundTrip(request: ByteArray): ByteArray {
            rounds += 1
            return behavior(request)
        }
    }

    private fun replyTo(request: ByteArray, stdout: String, code: Int = 0): ByteArray {
        val id = PrivilegeWire.decode(request).frames.first().id
        return PrivilegeWire.Frame(
            kind = PrivilegeWire.Kind.REPLY,
            id = id,
            code = code,
            body = PrivilegeWire.encodeOutput(stdout, ""),
        ).encode()
    }

    private fun errorTo(request: ByteArray, reason: String): ByteArray {
        val id = PrivilegeWire.decode(request).frames.first().id
        return PrivilegeWire.Frame(
            kind = PrivilegeWire.Kind.ERROR,
            id = id,
            code = 1,
            body = reason.toByteArray(Charsets.UTF_8),
        ).encode()
    }

    @Test
    fun `通道可用时走通道 su 一次都不跑`() {
        val su = SuRecorder()
        val port = FakePort { replyTo(it, "via-daemon") }
        val actions = RootActions(su, daemon = port)

        val result = actions.run(RootAction.Tap(1, 2))

        assertEquals("via-daemon", result.stdout)
        assertEquals(1, port.rounds)
        assertTrue("通道通的时候不该再跑 su", su.calls.isEmpty())
    }

    @Test
    fun `通道连不上时退回 su`() {
        val su = SuRecorder()
        val port = FakePort { throw PrivilegeClosed("连不上") }
        val actions = RootActions(su, daemon = port)

        val result = actions.run(RootAction.Tap(1, 2))

        assertEquals("su-ok", result.stdout)
        assertEquals(listOf(listOf("su", "-c", "input tap 1 2")), su.calls)
    }

    @Test
    fun `通道协议不对时退回 su`() {
        val su = SuRecorder()
        // 回一帧"不是应答"的东西：id 对不上 → PrivilegeClient 报 Protocol
        val port = FakePort { PrivilegeWire.Frame(PrivilegeWire.Kind.REPLY, 999, 0, ByteArray(0)).encode() }
        val actions = RootActions(su, daemon = port)

        actions.run(RootAction.Tap(1, 2))

        assertEquals(1, su.calls.size)
    }

    @Test
    fun `通道明确拒绝时不再退回 su 那是结论`() {
        val su = SuRecorder()
        val port = FakePort { errorTo(it, "参数越界") }
        val actions = RootActions(su, daemon = port)

        val result = actions.run(RootAction.Tap(1, 2))

        assertEquals(RootActions.REJECTED, result.code)
        assertTrue(result.stderr.contains("参数越界"))
        assertTrue("明确拒绝是结论，不该再用 su 试一遍", su.calls.isEmpty())
    }

    @Test
    fun `退出码非零原样带回 不退回 su`() {
        val su = SuRecorder()
        val port = FakePort { replyTo(it, "permission denied", code = 1) }
        val actions = RootActions(su, daemon = port)

        val result = actions.run(RootAction.ReadFile("/data/local/tmp/x"))

        assertEquals(1, result.code)
        assertEquals("permission denied", result.stdout)
        assertTrue(su.calls.isEmpty())
    }

    @Test
    fun `参数不合法时两条路都不发包`() {
        val su = SuRecorder()
        val port = FakePort { replyTo(it, "不应该走到这里") }
        val actions = RootActions(su, daemon = port)

        val result = actions.run(RootAction.DisablePackage("com.x; reboot"))

        assertEquals(RootActions.REJECTED, result.code)
        assertEquals(0, port.rounds)
        assertTrue(su.calls.isEmpty())
    }

    @Test
    fun `没给通道时行为与以前完全一样`() {
        val su = SuRecorder()
        val actions = RootActions(su)

        actions.run(RootAction.Key("KEYCODE_BACK"))

        assertEquals(listOf(listOf("su", "-c", "input keyevent KEYCODE_BACK")), su.calls)
    }

    @Test
    fun `每次调用用新的 id 不复用`() {
        val su = SuRecorder()
        val seen = ArrayList<Int>()
        val port = FakePort { request ->
            seen += PrivilegeWire.decode(request).frames.first().id
            replyTo(request, "ok")
        }
        val actions = RootActions(su, daemon = port, ids = AtomicInteger(1))

        repeat(3) { actions.run(RootAction.Tap(it, it)) }

        assertEquals(3, seen.distinct().size)
    }
}
