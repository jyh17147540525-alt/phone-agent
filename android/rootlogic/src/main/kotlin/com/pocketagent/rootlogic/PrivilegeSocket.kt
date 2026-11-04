package com.pocketagent.rootlogic

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 常驻通道的**传输层**：一条 loopback TCP 连接 + 一次握手 token。
 *
 * 为什么先做 TCP 而不是 `LocalSocket`（unix domain）：
 * - Android 12+ 的 SELinux 会拦 `untrusted_app` → `root` 的 unix socket（见
 *   `docs/工程/agent能力概张-方案.md` §9 的风险段），而 loopback TCP 在多数
 *   机型上不拦；
 * - 更要紧的是**离线可测**：`ServerSocket` 两端都是纯 JVM，测试里可以真起一个
 *   服务端跑完整往返。unix socket 那版要真机才能验一句话。
 *
 * 安全模型（与 `:mcp` 的 loopback 服务同一套思路，不另发明）：
 * 1. 只监听 `127.0.0.1` —— 不对局域网开；
 * 2. 握手必须带本次启动生成的随机 token，常量时间比较；
 * 3. token 不对就直接关连接，**不回任何有用信息**（回 `DENY` 只是为了排障，
 *    不含版本、路径、任何可探测细节）。
 *
 * ⚠️ 这一层的职责**只是把字节挪过去**。动词白名单、参数校验、审计在前面
 * （`RootCommands` / `DaemonLoop`），不要在这里再加一套 —— 两套白名单迟早会漂移。
 */

/** 握手行（ASCII）。第一行是协议版本 + token，服务端只认这一行。 */
internal const val HANDSHAKE_PREFIX = "PA1 "

/**
 * 客户端侧：把一批请求帧发出去、读回对应数量的应答帧。
 *
 * 与 [PrivilegeClient] 的分工：那边管"哪条应答属于哪个 id"，这边只管
 * "字节进出"。两个关注点分开，是因为 token / 超时 / 重连与协议配对
 * 是两类完全不同的问题。
 */
class PrivilegeSocketPort(
    private val host: String = "127.0.0.1",
    private val port: Int,
    private val token: String,
    private val connectTimeoutMs: Int = 1_500,
    private val readTimeoutMs: Int = 20_000,
) : PrivilegePort {

    private val lock = Any()
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun roundTrip(request: ByteArray): ByteArray {
        // 空请求不发包：调用方（PrivilegeClient）已经拦了一次，这里再拦一次是
        // 因为传输层不该依赖调用方守规矩。
        if (request.isEmpty()) return ByteArray(0)

        val ids = PrivilegeWire.decode(request).frames
            .filter { it.kind == PrivilegeWire.Kind.REQUEST }
            .map { it.id }
        if (ids.isEmpty()) return ByteArray(0)

        synchronized(lock) {
            ensureConnected()
            return try {
                val out = output!!
                out.write(request)
                out.flush()
                readReplies(ids)
            } catch (e: IOException) {
                // 连接坏了就丢掉：下次调用重新握手。**不在这里重试** ——
                // 重试会把"命令是否已经执行"变成未知，那是更坏的问题。
                closeQuietly()
                throw PrivilegeClosed("通道读写失败：${e.javaClass.simpleName}", e)
            }
        }
    }

    private fun ensureConnected() {
        val current = socket
        if (current != null && current.isConnected && !current.isClosed) return

        val created = Socket()
        try {
            created.connect(InetSocketAddress(host, port), connectTimeoutMs)
            created.soTimeout = readTimeoutMs
            created.tcpNoDelay = true
            val out = created.getOutputStream()
            out.write((HANDSHAKE_PREFIX + token + "\n").toByteArray(Charsets.US_ASCII))
            out.flush()

            val reply = readLine(created.getInputStream(), MAX_HANDSHAKE_REPLY)
            if (reply != "OK") {
                created.close()
                throw PrivilegeClosed("握手被拒（token 不对，或对面不是常驻通道）")
            }

            socket = created
            input = created.getInputStream()
            output = out
        } catch (e: IOException) {
            runCatching { created.close() }
            throw PrivilegeClosed("连不上常驻通道 $host:$port（${e.javaClass.simpleName}）", e)
        }
    }

    /** 读到**每个** id 都有应答为止；多余字节留到下次（跨调用不丢帧）。 */
    private fun readReplies(ids: List<Int>): ByteArray {
        val pending = LinkedHashSet(ids)
        val collected = ArrayList<ByteArray>(ids.size)
        var buffer = leftover
        leftover = ByteArray(0)

        while (pending.isNotEmpty()) {
            val decoded = PrivilegeWire.decode(buffer)
            buffer = decoded.leftover
            for (frame in decoded.frames) {
                if (frame.kind == PrivilegeWire.Kind.REQUEST) continue
                if (pending.remove(frame.id)) collected += frame.encode()
            }
            if (pending.isEmpty()) break

            val chunk = readChunk()
            buffer += chunk
        }

        leftover = buffer

        // 顺序不重要（调用方按 id 配对），但保持"同一批内的到达顺序"更容易排障。
        var total = 0
        collected.forEach { total += it.size }
        val out = ByteArray(total)
        var at = 0
        collected.forEach { out[at] = 0; it.copyInto(out, at); at += it.size }
        return out
    }

    private var leftover: ByteArray = ByteArray(0)

    private fun readChunk(): ByteArray {
        val stream = input ?: throw PrivilegeClosed("通道已关闭")
        val buf = ByteArray(8 * 1024)
        val n = stream.read(buf)
        if (n <= 0) throw PrivilegeClosed("常驻通道断开了")
        return buf.copyOf(n)
    }

    fun close() = closeQuietly()

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        leftover = ByteArray(0)
    }

    private companion object {
        const val MAX_HANDSHAKE_REPLY = 16
    }
}

/**
 * 服务端：只绑 loopback，逐个连接做握手，然后交给 [DaemonLoop] 的服务循环。
 *
 * 用法（薄壳里）：
 * ```
 * val server = DaemonSocketServer(loop, token, port = 0)   // 0 = 让系统挑端口
 * server.start()
 * writePortTo(tokenFile, server.port)   // 客户端读这个文件拿到端口与 token
 * ```
 *
 * 为什么端口要 `0` 再回写文件：**写死端口**在手机上迟早撞上别的服务，
 * 而"撞了之后连到别人的服务"是最坏的失败形态。让系统分配、再把实际端口
 * 告诉客户端，多一次文件读写，换来的是不可能撞。
 */
class DaemonSocketServer(
    private val loop: DaemonLoop,
    private val token: String,
    private val port: Int = 0,
    private val bindHost: String = "127.0.0.1",
) {

    private var server: ServerSocket? = null
    private var acceptor: Thread? = null
    private val connections = java.util.Collections.synchronizedList(ArrayList<Socket>())

    /** 实际监听端口（`start()` 之后可用）。 */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start(): Int {
        val socket = ServerSocket()
        // 只绑 loopback：显式给 InetAddress，而不是靠"默认是 localhost"这种假设。
        socket.bind(InetSocketAddress(InetAddress.getByName(bindHost), port), BACKLOG)
        server = socket
        acceptor = thread(name = "pa-daemon-accept", isDaemon = true) {
            acceptLoop(socket)
        }
        return socket.localPort
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break // close() 之后 accept 会抛，正常退出
            }
            connections += client
            thread(name = "pa-daemon-conn", isDaemon = true) {
                try {
                    if (!handshake(client)) return@thread
                    loop.serve(SocketDaemonChannel(client))
                } catch (e: IOException) {
                    // 单条连接出错不影响服务：客户端会重连。
                } finally {
                    runCatching { client.close() }
                    connections.remove(client)
                }
            }
        }
    }

    private fun handshake(client: Socket): Boolean {
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        val line = readLine(client.getInputStream(), MAX_HANDSHAKE_LINE) ?: return false
        val ok = line.startsWith(HANDSHAKE_PREFIX) &&
            constantTimeEquals(line.removePrefix(HANDSHAKE_PREFIX), token)
        val reply = if (ok) "OK\n" else "DENY\n"
        runCatching {
            client.getOutputStream().apply {
                write(reply.toByteArray(Charsets.US_ASCII))
                flush()
            }
        }
        if (!ok) runCatching { client.close() }
        client.soTimeout = 0 // 握手之后交给服务循环，读写自己控制
        return ok
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(connections) {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
        }
        acceptor = null
    }

    private companion object {
        const val BACKLOG = 8
        const val HANDSHAKE_TIMEOUT_MS = 3_000
        const val MAX_HANDSHAKE_LINE = 512
    }
}

/** 把一条已握手的 socket 包成 [DaemonChannel]。 */
class SocketDaemonChannel(private val socket: Socket) : DaemonChannel {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private val buffer = ByteArray(8 * 1024)

    override fun read(): ByteArray? {
        val n = try {
            input.read(buffer)
        } catch (e: IOException) {
            return null
        }
        return if (n <= 0) null else buffer.copyOf(n)
    }

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }
}

/**
 * 常量时间比较 —— token 比较用。
 *
 * 长度不同直接返回 false 也会泄漏长度，但长度本身不是秘密（协议固定 32 字节随机串）。
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

/** 读一行（以 `\n` 结尾）；超过上限返回 null 而不是无限读。 */
internal fun readLine(input: InputStream, maxBytes: Int): String? {
    val out = StringBuilder()
    var count = 0
    while (count < maxBytes) {
        val b = input.read()
        if (b < 0) return if (out.isEmpty()) null else out.toString()
        if (b == '\n'.code) return out.toString()
        out.append(b.toChar())
        count += 1
    }
    return null
}
