package com.pocketagent.mcp

import com.pocketagent.provider.gateway.http.GatewayTokenProvider
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 能力桥的端点抽象 —— 会话层只认识这个窄接口（与 `GatewayEndpoint` 同模式）。
 * 于是"起桥 → 渲染草稿"整条链路可以用假端点离线测试。
 */
interface McpEndpoint {
    fun start(): Boolean
    fun stop()

    /** 本次启动的 token。未启动时为空串。**不落日志、不落盘。** */
    val token: String

    /** 形如 `http://127.0.0.1:12345/mcp`；未启动时为空串。 */
    val mcpUrl: String

    /** 实际监听的端口。0 = 未启动。 */
    val port: Int
}

/**
 * MCP 流的 HTTP 服务器（`POST /mcp`，纯 JSON 响应）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  它刻意与 `HttpGatewayServer` 长得像，这是有意的
 * ═══════════════════════════════════════════════════════════════
 *
 * 同一台设备上第二个 loopback 服务器，同一套加固（只监听 127.0.0.1 / 内核分配端口 /
 * 一次性 token / 常量时间比较 / 不回显路径 / 头与体的多重上限）。两处**相似**
 * 是有代价的（将来加固一处、另一处可能会漂移），所以：
 *
 * - 已经抽出来的（token 原语）**直接复用**（`GatewayTokenProvider`）；
 * - 没抽出来的（HTTP 解析）**注释互相指认** —— 改任何一处前先看另一处的注释；
 * - 第三个 loopback 服务器出现时，才是抽公共件的时机（两条定律还不够）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  与网关不同的两处（都是 MCP 规范/客户端源码决定的）
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **通知回 `202` 空体**（网关没有"通知"这个概念）；
 * 2. **`GET` / `DELETE` 回 `405`** —— 官方 SDK 源码把 405 当**明确容忍**的
 *    正常分支（"server may not support it"），这是最省事也最规范的拒绝。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 反 DNS rebinding（网关没有的加固）
 * ═══════════════════════════════════════════════════════════════
 *
 * loopback 服务器有一个浏览器能发起的经典攻击：网页脚本向 `127.0.0.1:<port>`
 * 发请求（DNS rebinding 后同源）。防御是**拒绝一切带 `Origin` 头的请求** ——
 * 浏览器跨源请求必带 `Origin`，而 Node 的 fetch（dsh）不带。再加一层
 * `Host` 必须是 loopback 的校验。两层都不花性能，且不影响任何正常客户端。
 */
class McpHttpServer(
    private val dispatcher: McpDispatcher,
    private val tokenProvider: GatewayTokenProvider = GatewayTokenProvider(),
    /** 日志出口。**实现方绝不能把 token 传进来。** */
    private val log: (String) -> Unit = {},
) : McpEndpoint {

    /** 本次启动的 token。**每次 start 重新生成**（与网关加固第 3 条同款）。 */
    @Volatile
    override var token: String = ""
        private set

    @Volatile
    override var port: Int = 0
        private set

    override val mcpUrl: String
        get() = if (port == 0) "" else "http://127.0.0.1:$port$MCP_PATH"

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    private var scope: CoroutineScope? = null

    private val activeConnections = AtomicInteger(0)

    private val running = AtomicBoolean(false)

    /**
     * 启动（幂等：已在运行时不重新分配端口与 token，直接返回 true）。
     * 绑定 `127.0.0.1:0` —— 只允许本机，端口交给内核分配（无 TOCTOU 窗口）。
     */
    override fun start(): Boolean {
        if (!running.compareAndSet(false, true)) {
            log("能力桥已在运行，忽略重复 start")
            return true
        }

        return try {
            token = tokenProvider.issue()

            val socket = ServerSocket()
            // 先设 reuse 再 bind —— 反过来在某些平台会抛 SocketException
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), BACKLOG)
            serverSocket = socket
            port = socket.localPort

            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = newScope
            acceptJob = newScope.launch { acceptLoop(socket) }

            // ⚠️ 只打端口，绝不打 token（同网关纪律：日志会被收集与展示）。
            log("MCP 能力桥已启动于 127.0.0.1:$port（token 未记录）")
            true
        } catch (e: Exception) {
            running.set(false)
            port = 0
            log("能力桥启动失败：${e::class.simpleName}")
            false
        }
    }

    /** 停止：取消 scope（在途连接）→ 关 socket（让 accept 退出）→ 清 token。 */
    override fun stop() {
        if (!running.compareAndSet(true, false)) return

        scope?.cancel()
        scope = null
        acceptJob = null

        runCatching { serverSocket?.close() }
        serverSocket = null
        port = 0

        // 清掉 token：下次 start 会生成新的。不清的话旧 token 在 stop 之后
        // 仍然"看起来有效"，而它对应的端口已经关了 —— 排查会往完全错的方向走。
        token = ""

        log("MCP 能力桥已停止")
    }

    // ─────────────────────────────────────────────────────────────
    //  连接循环
    // ─────────────────────────────────────────────────────────────

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (running.get()) log("accept 失败：${e::class.simpleName}")
                break
            }

            if (activeConnections.incrementAndGet() > MAX_CONNECTIONS) {
                activeConnections.decrementAndGet()
                runCatching { client.close() }
                continue
            }

            scope?.launch {
                try {
                    client.use { handleConnection(it) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("连接处理失败：${e::class.simpleName}")
                } finally {
                    activeConnections.decrementAndGet()
                }
            }
        }
    }

    private suspend fun handleConnection(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        // 小包高频场景的常规设置（与网关同一理由）。
        runCatching { client.tcpNoDelay = true }

        val input = client.getInputStream()
        val output = BufferedOutputStream(client.getOutputStream())

        val request = readRequest(input) ?: run {
            writeSimple(output, 400, """{"error":"malformed request"}""")
            return
        }

        when {
            request.method == "POST" && request.path == MCP_PATH -> handlePost(request, output)

            (request.method == "GET" || request.method == "DELETE") && request.path == MCP_PATH ->
                writeSimple(output, 405, """{"error":"method not allowed"}""", mapOf("Allow" to "POST"))

            // 其余一律 404，且**不回显路径**（同网关：不给反射探测)。
            else -> writeSimple(output, 404, """{"error":"not found"}""")
        }
    }

    private suspend fun handlePost(request: HttpRequest, output: OutputStream) {
        // ── 反 DNS rebinding（见类注释）─────────────────────────
        if (request.header("Origin") != null) {
            writeSimple(output, 403, """{"error":"forbidden"}""")
            return
        }
        val host = request.header("Host")
        if (host == null || !isLoopbackHost(host)) {
            writeSimple(output, 403, """{"error":"forbidden"}""")
            return
        }

        // ── 鉴权 ────────────────────────────────────────────────
        if (!authorized(request)) {
            writeSimple(output, 401, """{"error":"unauthorized"}""")
            return
        }

        // ⚠️ 先看上限，**再**读进内存（一个伪造的 Content-Length 会把 OOM 带给整个应用）。
        val declared = request.contentLength
        if (declared != null && declared > MAX_BODY_BYTES) {
            writeSimple(output, 413, """{"error":"request body too large"}""")
            return
        }

        val body = readBodyOnce(request)

        when (val parsed = JsonRpc.parse(body)) {
            is IncomingParse.Parsed -> {
                val response = dispatcher.dispatch(parsed.message)
                if (response == null) {
                    // 通知：202 空体（规范对"已接受、无响应"的规定形状）。
                    writeSimple(output, 202, "")
                } else {
                    writeSimple(output, 200, response.toString())
                }
            }

            // JSON-RPC 层的错误仍然回 200 + error 对象（协议的既有约定）。
            IncomingParse.NotJson ->
                writeSimple(output, 200, JsonRpc.error(null, JsonRpcErrorCode.PARSE_ERROR, "无法解析为 JSON").toString())

            is IncomingParse.Invalid ->
                writeSimple(output, 200, JsonRpc.error(null, JsonRpcErrorCode.INVALID_REQUEST, parsed.reason).toString())
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  鉴权与加固
    // ─────────────────────────────────────────────────────────────

    private fun authorized(request: HttpRequest): Boolean {
        val expected = token
        // 空 token = "没在跑"或"刚 stop 过" —— 此时**一律拒绝**（同网关：
        // 空 token 放行会变成"没起来时反而没有鉴权"的漏洞）。
        if (expected.isEmpty()) return false
        val candidate = GatewayTokenProvider.extract(request.headers)
        return tokenProvider.matches(expected, candidate)
    }

    /**
     * `Host` 头必须是 loopback。
     *
     * 只接受 `127.0.0.1[:port]` 与 `localhost[:port]` —— 我们**只**监听 IPv4 loopback，
     * 所以 IPv6 字面量（`[::1]`）也不在允许集里（多一个允许项就多一个要维护的判断）。
     */
    internal fun isLoopbackHost(header: String): Boolean {
        val h = header.trim().lowercase()
        if (h.isEmpty()) return false
        val hostPart = when {
            h.startsWith("[") -> h.substringBefore(']') + "]"
            ':' in h -> h.substringBefore(':')
            else -> h
        }
        return hostPart == "127.0.0.1" || hostPart == "localhost"
    }

    // ─────────────────────────────────────────────────────────────
    //  HTTP 解析与写出（与网关同一套「明确声明不支持什么」的策略）
    // ─────────────────────────────────────────────────────────────

    private class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val contentLength: Int?,
        val input: InputStream,
    ) {
        /** body 只读一次的缓存（网关踩过"读第二遍得到空串"的坑，照抄教训）。 */
        var bodyCache: String? = null

        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /**
     * 读请求行 + 头。
     *
     * ⚠️ 明确**不支持**（是声明，不是遗漏）：`Transfer-Encoding: chunked`（直接拒）、
     *    `Expect: 100-continue`（不发 100，dsh 不会发它）、TLS（只监听 loopback）、
     *    keep-alive（每响应都 `Connection: close`）。理由与网关逐条相同。
     */
    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input, MAX_LINE_BYTES) ?: return null
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 3) return null

        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?').substringBefore('#')

        val headers = mutableMapOf<String, String>()
        var totalHeaderBytes = 0
        while (true) {
            val line = readLine(input, MAX_LINE_BYTES) ?: return null
            if (line.isEmpty()) break

            totalHeaderBytes += line.length
            if (totalHeaderBytes > MAX_HEADER_BYTES) return null

            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            if (name.isEmpty()) return null
            headers[name] = value
        }

        if (headers.keys.any { it.equals("Transfer-Encoding", ignoreCase = true) }) {
            return null
        }

        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value
            ?.toIntOrNull()

        return HttpRequest(method, path, headers, contentLength, input)
    }

    private fun readBody(request: HttpRequest, max: Int): String {
        val length = request.contentLength ?: 0
        if (length <= 0) return ""
        val toRead = minOf(length, max)
        val buffer = ByteArray(toRead)
        var read = 0
        while (read < toRead) {
            val n = request.input.read(buffer, read, toRead - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun readBodyOnce(request: HttpRequest): String {
        if (request.bodyCache == null) {
            request.bodyCache = readBody(request, MAX_BODY_BYTES)
        }
        return request.bodyCache!!
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > max) return null
        }
    }

    private fun writeSimple(
        output: OutputStream,
        status: Int,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHeaders(
            output, status,
            buildMap {
                put("Content-Type", "application/json; charset=utf-8")
                put("Content-Length", bytes.size.toString())
                putAll(extraHeaders)
            },
        )
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    /**
     * 写状态行与头。
     * ⚠️ 绝不加 `Server: pocketagent` 之类的标识；值里的 CR/LF 剥掉（响应拆分防御）。
     */
    private fun writeHeaders(output: OutputStream, status: Int, headers: Map<String, String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reasonFor(status)).append("\r\n")
        for ((k, v) in headers) {
            sb.append(k).append(": ").append(v.replace('\r', ' ').replace('\n', ' ')).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        else -> "OK"
    }

    companion object {
        /** MCP 端点路径。dsh 配置里的 URL 与这里必须一致（渲染器用的就是 [mcpUrl]）。 */
        const val MCP_PATH: String = "/mcp"

        private const val BACKLOG = 16
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024

        /**
         * 请求体上限。
         *
         * ⚠️ 256KB 是**刻意宽松**的：`tools/call` 的参数目前只有空对象，
         *    但将来的工具参数（比如输入文本）可能上量；同时它必须**有**上限
         *    （见 `handlePost` 里的顺序注释）。
         */
        private const val MAX_BODY_BYTES = 256 * 1024

        private const val MAX_CONNECTIONS = 32
    }
}
