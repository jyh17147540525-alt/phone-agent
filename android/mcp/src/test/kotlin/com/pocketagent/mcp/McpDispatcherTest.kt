package com.pocketagent.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分派器：握手、工具调用、错误码 —— 全部离线断言（不经 socket）。
 *
 * socket 层由 `McpHttpServerTest` 单独覆盖；这里只关心**语义**。
 */
class McpDispatcherTest {

    // ── 测试工具 ───────────────────────────────────────────────

    private fun tool(
        name: String = "screen_read",
        behavior: suspend (JsonObject) -> ToolOutcome,
    ): McpTool = object : McpTool {
        override val name = name
        override val description = "测试用工具"
        override fun inputSchema(): JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun call(arguments: JsonObject): ToolOutcome = behavior(arguments)
    }

    private fun okTool(name: String = "screen_read"): McpTool = tool(name) {
        ToolOutcome.Success(text = "节点列表", structured = buildJsonObject { put("nodeCount", 3) })
    }

    private fun dispatcher(vararg tools: McpTool, timeoutMs: Long = 25_000): McpDispatcher =
        McpDispatcher(ToolRegistry(tools.toList()), timeoutMs)

    private fun request(method: String, params: JsonObject? = null): IncomingMessage.Request =
        IncomingMessage.Request(JsonPrimitive(1), method, params)

    // ── initialize ─────────────────────────────────────────────

    @Test
    fun `initialize 回显版本并声明 tools 能力与服务信息`() = runTest {
        val response = dispatcher().dispatch(
            request(
                McpProtocol.METHOD_INITIALIZE,
                buildJsonObject { put("protocolVersion", JsonPrimitive("2025-11-25")) },
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertEquals("2025-11-25", result["protocolVersion"]!!.jsonPrimitive.content)
        assertTrue(result["capabilities"]!!.jsonObject.containsKey("tools"))
        assertEquals(McpProtocol.SERVER_NAME, result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `initialize 不带版本时回落而不是报错`() = runTest {
        // SDK 一定带版本；宽容处理是为了"其它客户端"不会因为一个可选字段被拒。
        val response = dispatcher().dispatch(request(McpProtocol.METHOD_INITIALIZE))!!
        val result = response["result"]!!.jsonObject
        assertEquals(McpProtocol.FALLBACK_PROTOCOL_VERSION, result["protocolVersion"]!!.jsonPrimitive.content)
    }

    // ── 通知与杂项 ─────────────────────────────────────────────

    @Test
    fun `通知不回响应`() = runTest {
        // 回响应体是最坏的一种"处理"：客户端会把它当成一条无主消息丢掉，
        // 而我们这边以为通知被应答了 —— 双方都静默地错了。
        val response = dispatcher().dispatch(
            IncomingMessage.Notification("notifications/initialized", null),
        )
        assertNull(response)
    }

    @Test
    fun `ping 回空结果`() = runTest {
        val response = dispatcher().dispatch(request(McpProtocol.METHOD_PING))!!
        assertTrue(response["result"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `未知方法回 -32601`() = runTest {
        val response = dispatcher().dispatch(request("resources/list"))!!
        assertEquals(
            JsonRpcErrorCode.METHOD_NOT_FOUND,
            response["error"]!!.jsonObject["code"]!!.jsonPrimitive.int,
        )
    }

    // ── tools/list ─────────────────────────────────────────────

    @Test
    fun `tools list 按注册顺序列出全部工具`() = runTest {
        val response = dispatcher(okTool("a"), okTool("b")).dispatch(request("tools/list"))!!
        val tools = response["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(2, tools.size)
        assertEquals("a", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("b", tools[1].jsonObject["name"]!!.jsonPrimitive.content)
    }

    // ── tools/call ─────────────────────────────────────────────

    @Test
    fun `成功结果含文本块与 structuredContent`() = runTest {
        val response = dispatcher(okTool()).dispatch(
            request(
                McpProtocol.METHOD_TOOLS_CALL,
                buildJsonObject {
                    put("name", JsonPrimitive("screen_read"))
                    put("arguments", buildJsonObject { })
                },
            ),
        )!!
        val result = response["result"]!!.jsonObject
        val content = result["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", content["type"]!!.jsonPrimitive.content)
        assertEquals("节点列表", content["text"]!!.jsonPrimitive.content)
        assertEquals(3, result["structuredContent"]!!.jsonObject["nodeCount"]!!.jsonPrimitive.int)
        // 成功结果里**不应**出现 isError（缺省即 false；写了反而多此一举）
        assertFalse(result.containsKey("isError"))
    }

    @Test
    fun `arguments 缺席按空参数处理`() = runTest {
        // 模型对无参工具经常只发 name —— 因此丢参数是**正常情况**，不是错误。
        val response = dispatcher(okTool()).dispatch(
            request(McpProtocol.METHOD_TOOLS_CALL, buildJsonObject { put("name", JsonPrimitive("screen_read")) }),
        )!!
        assertTrue(response.containsKey("result"))
    }

    @Test
    fun `未知工具回 -32602 并回显工具名`() = runTest {
        val response = dispatcher(okTool()).dispatch(
            request(
                McpProtocol.METHOD_TOOLS_CALL,
                buildJsonObject { put("name", JsonPrimitive("screen_raed")) }, // 故意拼错
            ),
        )!!
        val error = response["error"]!!.jsonObject
        assertEquals(JsonRpcErrorCode.INVALID_PARAMS, error["code"]!!.jsonPrimitive.int)
        // 回显名字是给模型自我纠错的线索 —— 少了它，模型只会一直收到"未知工具"。
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("screen_raed"))
    }

    @Test
    fun `拒绝走 isError 结果而不是 JSON-RPC 错误`() = runTest {
        val refusing = tool { ToolOutcome.Refused("当前页面有密码输入框，不会读取。") }
        val response = dispatcher(refusing).dispatch(
            request(
                McpProtocol.METHOD_TOOLS_CALL,
                buildJsonObject { put("name", JsonPrimitive("screen_read")) },
            ),
        )!!
        // 拒绝是**业务结论**：必须走 result + isError（dsh 会把它抛给模型看），
        // 不是协议层错误 —— 协议层错误在 dsh 侧是一条没有用户文案的失败。
        assertTrue(response.containsKey("result"))
        val result = response["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `工具抛异常回内部错误且不外泄异常文本`() = runTest {
        val explosive = tool { throw IllegalStateException("内部细节：路径 /data/secret 不该被看到") }
        val response = dispatcher(explosive).dispatch(
            request(
                McpProtocol.METHOD_TOOLS_CALL,
                buildJsonObject { put("name", JsonPrimitive("screen_read")) },
            ),
        )!!
        val error = response["error"]!!.jsonObject
        assertEquals(JsonRpcErrorCode.INTERNAL_ERROR, error["code"]!!.jsonPrimitive.int)
        assertFalse(error["message"]!!.jsonPrimitive.content.contains("secret"))
    }

    @Test
    fun `工具超时回可读的失败而不是永远沉默`() = runTest {
        val hanging = tool { delay(60_000); ToolOutcome.Success("never") }
        val response = dispatcher(hanging, timeoutMs = 500).dispatch(
            request(
                McpProtocol.METHOD_TOOLS_CALL,
                buildJsonObject { put("name", JsonPrimitive("screen_read")) },
            ),
        )!!
        val result = response["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean)
        assertTrue(
            result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("超时"),
        )
    }
}
