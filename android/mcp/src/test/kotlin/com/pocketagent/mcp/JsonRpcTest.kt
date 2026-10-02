package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON-RPC 的最小核心：解析（三态）、版本协商、响应构造。
 *
 * ⚠️ 这一层处理的是**不可信输入**（哪怕客户端是 dsh）——所以畸形输入的分支
 *    与正常分支同等重要，每个畸形分支都有独立用例。
 */
class JsonRpcTest {

    // ── 解析：正常 ─────────────────────────────────────────────

    @Test
    fun `解析一个合法请求`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":7,"method":"tools/list","params":{}}""")
        val request = (parsed as IncomingParse.Parsed).message as IncomingMessage.Request
        assertEquals("tools/list", request.method)
        assertEquals(JsonPrimitive(7), request.id)
    }

    @Test
    fun `没有 id 的消息是通知`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val notification = (parsed as IncomingParse.Parsed).message as IncomingMessage.Notification
        assertEquals("notifications/initialized", notification.method)
        assertEquals(null, notification.params)
    }

    @Test
    fun `字符串 id 原样保留`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":"abc-1","method":"ping"}""")
        val request = (parsed as IncomingParse.Parsed).message as IncomingMessage.Request
        assertEquals(JsonPrimitive("abc-1"), request.id)
    }

    // ── 解析：畸形 ─────────────────────────────────────────────

    @Test
    fun `连 JSON 都不是就报 NotJson`() {
        assertEquals(IncomingParse.NotJson, JsonRpc.parse("{oops"))
        assertEquals(IncomingParse.NotJson, JsonRpc.parse(""))
    }

    @Test
    fun `批处理数组被明确拒绝而不是静默当成空`() {
        // 静默"当成一条"或"当成空"都会让客户端以为消息被处理了。
        val parsed = JsonRpc.parse("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")
        assertTrue((parsed as IncomingParse.Invalid).reason.contains("批处理"))
    }

    @Test
    fun `顶层不是对象被拒`() {
        assertTrue(JsonRpc.parse("\"hi\"") is IncomingParse.Invalid)
        assertTrue(JsonRpc.parse("42") is IncomingParse.Invalid)
    }

    @Test
    fun `jsonrpc 字段缺失或写错被拒`() {
        assertTrue(JsonRpc.parse("""{"id":1,"method":"ping"}""") is IncomingParse.Invalid)
        assertTrue(JsonRpc.parse("""{"jsonrpc":"1.0","id":1,"method":"ping"}""") is IncomingParse.Invalid)
    }

    @Test
    fun `method 必须是字符串`() {
        assertTrue(JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"method":123}""") is IncomingParse.Invalid)
        assertTrue(JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"method":"  "}""") is IncomingParse.Invalid)
    }

    @Test
    fun `数组 params 被拒 —— 不能静默当成没有参数`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"method":"m","params":[1,2]}""")
        assertTrue(parsed is IncomingParse.Invalid)
    }

    @Test
    fun `对象 id 被拒`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":{"x":1},"method":"m"}""")
        assertTrue(parsed is IncomingParse.Invalid)
    }

    // ── 版本协商 ───────────────────────────────────────────────

    @Test
    fun `回显客户端在其支持清单内的版本`() {
        // 清单逐字抄自 SDK 1.30.0；客户端发的 LATEST 是 2025-11-25。
        assertEquals("2025-11-25", McpProtocol.negotiate("2025-11-25"))
        assertEquals("2025-06-18", McpProtocol.negotiate("2025-06-18"))
        assertEquals("2024-10-07", McpProtocol.negotiate("2024-10-07"))
    }

    @Test
    fun `清单外的版本回落到明确的默认值`() {
        assertEquals(McpProtocol.FALLBACK_PROTOCOL_VERSION, McpProtocol.negotiate("1.2.3"))
        assertEquals(McpProtocol.FALLBACK_PROTOCOL_VERSION, McpProtocol.negotiate(null))
    }

    // ── 响应构造 ───────────────────────────────────────────────

    @Test
    fun `错误响应的 id 为 null 时用 JSON null`() {
        val error = JsonRpc.error(null, JsonRpcErrorCode.PARSE_ERROR, "坏输入")
        assertEquals(JsonNull, error["id"])
        assertEquals(
            JsonRpcErrorCode.PARSE_ERROR,
            error["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
        )
    }

    @Test
    fun `成功响应回显 id 且 result 缺省为空对象`() {
        val result = JsonRpc.result(JsonPrimitive("x9"))
        assertEquals(JsonPrimitive("x9"), result["id"])
        assertTrue(result["result"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `initialize 回包里 listChanged 明确为 false`() = runTest {
        // 这是一个**事实声明**（我们不实现服务端推送）——写 true 会让
        // 客户端等待一个永不出现的通知。
        val dispatcher = McpDispatcher(ToolRegistry(emptyList()))
        val response = dispatcher.dispatch(
            IncomingMessage.Request(
                JsonPrimitive(1),
                McpProtocol.METHOD_INITIALIZE,
                buildJsonObject { put("protocolVersion", JsonPrimitive("2025-11-25")) },
            ),
        )!!
        val tools = response["result"]!!.jsonObject["capabilities"]!!.jsonObject["tools"]!!.jsonObject
        assertEquals(false, tools["listChanged"]!!.jsonPrimitive.boolean)
    }
}
