package com.pocketagent.mcp

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpHttpServer] 的**真 socket** 集成测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么必须有这一层（与网关同样的理由）
 * ═══════════════════════════════════════════════════════════════
 *
 * 单元测试直接构造消息对象，不经过 socket —— 也就永远不会暴露
 * "头解析错一个字段""202 体的形状不对""连接关不掉"这类**只有真连接**才有的缺陷。
 * 这里按 MCP 客户端的**真实报文形状**（源自官方 SDK 源码）发请求：
 * `POST`、`Accept: application/json, text/event-stream`、
 * 带 `Host` / `Content-Length`、通知之后接 `GET`（要能吃到 405）。
 */
class McpHttpServerTest {

    private var server: McpHttpServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun startServer(vararg tools: McpTool): McpHttpServer {
        val s = McpHttpServer(McpDispatcher(ToolRegistry(tools.toList())))
        assertTrue("服务器应能启动", s.start())
        server = s
        return s
    }

    private val screenReadStub = object : McpTool {
        override val name = "screen_read"
        override val description = "测试桩"
        override fun inputSchema(): JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun call(arguments: JsonObject): ToolOutcome =
            ToolOutcome.Success(
                text = "【屏幕快照】com.test 1440×3200 · 节点 1",
                structured = buildJsonObject { put("nodeCount", 1) },
            )
    }

    // ── HTTP 工具 ──────────────────────────────────────────────

    private data class Resp(val status: Int, val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private fun send(
        s: McpHttpServer,
        method: String = "POST",
        path: String = "/mcp",
        body: String? = null,
        token: String? = s.token,
        origin: String? = null,
        host: String? = null,
        /** 与 [body] 不一致的声明长度（造"伪造 Content-Length"的情形，不发 body）。 */
        declaredLength: Int? = null,
    ): Resp {
        val bodyBytes = (body ?: "").toByteArray(Charsets.UTF_8)
        val declared = declaredLength ?: bodyBytes.size

        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", s.port), 2000)
            socket.soTimeout = 5000

            val head = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: ${host ?: "127.0.0.1:${s.port}"}\r\n")
                append("Accept: application/json, text/event-stream\r\n")
                if (method == "POST") append("Content-Type: application/json\r\n")
                if (token != null) append("Authorization: Bearer $token\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                append("Content-Length: $declared\r\n")
                append("Connection: close\r\n\r\n")
            }

            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            if (declaredLength == null) out.write(bodyBytes)
            out.flush()

            val raw = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            return parse(raw)
        }
    }

    private fun parse(raw: String): Resp {
        val split = raw.indexOf("\r\n\r\n")
        val head = if (split >= 0) raw.substring(0, split) else raw
        val body = if (split >= 0) raw.substring(split + 4) else ""
        val lines = head.split("\r\n")
        val status = lines[0].split(" ")[1].toInt()
        val headers = lines.drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
        return Resp(status, headers, body)
    }

    private fun initializeBody(version: String = "2025-11-25"): String =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"dsh-mcp-client","version":"0.0.1"}}}"""

    // ── 完整握手（客户端真实流程）──────────────────────────────

    @Test
    fun `完整握手与工具调用`() {
        val s = startServer(screenReadStub)

        // ① initialize —— 回显版本；**不发 Mcp-Session-Id**（我们无状态）。
        val init = send(s, body = initializeBody())
        assertEquals(200, init.status)
        assertNull("不得发 session id（无状态服务器）", init.header("mcp-session-id"))
        val initResult = kotlinx.serialization.json.Json.parseToJsonElement(init.body)
            .jsonObject["result"]!!.jsonObject
        assertEquals("2025-11-25", initResult["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(initResult["capabilities"]!!.jsonObject.containsKey("tools"))

        // ② notifications/initialized —— 202 空体（客户端随后会试 GET，见下一条）。
        val initialized = send(
            s,
            body = """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertEquals(202, initialized.status)
        assertTrue(initialized.body.isEmpty())

        // ③ 通知之后客户端会发 GET 试开 SSE —— 必须吃到 405（SDK 明确容忍）。
        val get = send(s, method = "GET", body = null)
        assertEquals(405, get.status)
        assertEquals("POST", get.header("Allow"))

        // ④ tools/list —— 能看到 screen_read。
        val list = send(s, body = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals(200, list.status)
        val tools = kotlinx.serialization.json.Json.parseToJsonElement(list.body)
            .jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals("screen_read", tools[0].jsonObject["name"]!!.jsonPrimitive.content)

        // ⑤ tools/call —— 文本块 + structuredContent。
        val call = send(
            s,
            body = """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"screen_read","arguments":{}}}""",
        )
        assertEquals(200, call.status)
        val callResult = kotlinx.serialization.json.Json.parseToJsonElement(call.body)
            .jsonObject["result"]!!.jsonObject
        assertEquals(
            "【屏幕快照】com.test 1440×3200 · 节点 1",
            callResult["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals(1, callResult["structuredContent"]!!.jsonObject["nodeCount"]!!.jsonPrimitive.int)
    }

    // ── 鉴权与加固 ─────────────────────────────────────────────

    @Test
    fun `没有 token 与错误 token 都是 401`() {
        val s = startServer(screenReadStub)

        val noToken = send(s, body = initializeBody(), token = null)
        assertEquals(401, noToken.status)

        val wrongToken = send(s, body = initializeBody(), token = "deadbeef")
        assertEquals(401, wrongToken.status)
        // 401 的体里不解释"token 哪里不对"（那是给攻击者的免费信息）。
        assertFalse(wrongToken.body.contains("Bearer"))
    }

    @Test
    fun `带 Origin 的请求被 403（反 DNS rebinding）`() {
        val s = startServer(screenReadStub)
        val resp = send(s, body = initializeBody(), origin = "http://evil.example")
        assertEquals(403, resp.status)
    }

    @Test
    fun `Host 不是回环的请求被 403`() {
        val s = startServer(screenReadStub)
        val resp = send(s, body = initializeBody(), host = "evil.example.com")
        assertEquals(403, resp.status)
    }

    @Test
    fun `其它路径 404 且不回显路径`() {
        val s = startServer(screenReadStub)
        val resp = send(s, path = "/admin/secret", body = "")
        assertEquals(404, resp.status)
        assertFalse("不得成为路径探测的反射点", resp.body.contains("admin"))
    }

    @Test
    fun `声明超大请求体直接 413`() {
        val s = startServer(screenReadStub)
        // 只声明、不发体 —— 这正是"伪造 Content-Length"的攻击形状。
        val resp = send(s, body = "", declaredLength = 512 * 1024)
        assertEquals(413, resp.status)
    }

    // ── 协议层错误 ─────────────────────────────────────────────

    @Test
    fun `未知方法 批处理 坏 JSON 各自的错误码`() {
        val s = startServer(screenReadStub)

        val unknown = send(s, body = """{"jsonrpc":"2.0","id":9,"method":"resources/list"}""")
        assertEquals(-32601, errorCode(unknown))

        val batch = send(s, body = """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
        assertEquals(-32600, errorCode(batch))

        val broken = send(s, body = "{oops")
        assertEquals(-32700, errorCode(broken))
    }

    private fun errorCode(resp: Resp): Int =
        kotlinx.serialization.json.Json.parseToJsonElement(resp.body)
            .jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int

    // ── 生命周期 ───────────────────────────────────────────────

    @Test
    fun `重复 start 幂等且不换端口与 token`() {
        val s = startServer(screenReadStub)
        val port = s.port
        val token = s.token
        assertTrue(s.start())
        assertEquals(port, s.port)
        assertEquals(token, s.token)
    }

    @Test
    fun `stop 之后端口不再可达`() {
        val s = startServer(screenReadStub)
        val port = s.port
        s.stop()

        assertEquals(0, s.port)
        assertTrue(s.token.isEmpty())

        var failed = false
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
            }
        } catch (e: Exception) {
            failed = true
        }
        assertTrue("stop 后端口应不可达", failed)
    }

    /**
     * ★ 绑定地址必须是 **IPv4 字面量**。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 这条测试**结构性地抓不住那个真正的 bug**（2026-10-02 真机踩的）
     * ═══════════════════════════════════════════════════════════════
     *
     * 那个 bug 是 `InetAddress.getLoopbackAddress()`：
     * - **JVM 上返回 `127.0.0.1`**
     * - **Android 上返回 `::1`**
     *
     * 于是"绑 IPv6、对外公布 IPv4"这个漂移，在桌面单测里
     * **两边一起错、测试照样绿** —— 而真机上客户端 `ECONNREFUSED`。
     *
     * ⇒ 本测试只能钉住**配置一致性**（地址只有一个来源、且是 IPv4 字面量）。
     *   真正拦住那个 bug 的是 `LOOPBACK_HOST` 的注释 + 它被绑定/URL/Host 校验
     *   **三处共用**。这条测试的价值是：下次有人把它改成 `::1`、
     *   或者改回 `getLoopbackAddress()` 时，**它会红**。
     *
     * ★ 记一条通用教训：**跨平台的 API 行为差异，离线验证器天然测不到。**
     *   这类东西只能靠"不要用它"（用字面量）+ 注释说清为什么。
     */
    @Test
    fun `绑定地址是 IPv4 字面量`() {
        assertEquals(
            "LOOPBACK_HOST 必须是 127.0.0.1 —— 用 ::1 会让客户端连 127.0.0.1 时被拒",
            "127.0.0.1",
            McpHttpServer.LOOPBACK_HOST,
        )
        assertTrue(
            "必须是 IPv4 点分字面量，不能是主机名或 IPv6",
            McpHttpServer.LOOPBACK_HOST.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")),
        )
    }
}
