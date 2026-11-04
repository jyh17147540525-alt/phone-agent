package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
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
 * 档位网关的测试。
 *
 * ★ 钉的是"不可用的工具怎么被看见"，不是"怎么被删掉"：
 *   1. 工具**留在清单里**，描述尾部挂原因
 *   2. 被挡的调用**一个字节都不执行**
 *   3. 网关每次现问（不缓存）—— 用户随时可能关掉权限
 */
class ToolGateTest {

    private var calls = 0

    private fun tool(name: String = "android_tap"): McpTool = object : McpTool {
        override val name = name
        override val description = "点按屏幕"
        override fun inputSchema(): JsonObject = buildJsonObject { put("type", "object") }
        override suspend fun call(arguments: JsonObject): ToolOutcome {
            calls++
            return ToolOutcome.Success(text = "点了一下")
        }
    }

    private fun request(method: String, params: JsonObject? = null): IncomingMessage.Request =
        IncomingMessage.Request(JsonPrimitive(1), method, params)

    private fun callParams(name: String): JsonObject = buildJsonObject {
        put("name", name)
        put("arguments", buildJsonObject { })
    }

    /** 从 tools/list 结果里取某条工具的 description。 */
    private fun descriptionOf(result: JsonObject, name: String): String =
        result["tools"]!!.jsonArray
            .map { it.jsonObject }
            .first { it["name"]!!.jsonPrimitive.content == name }["description"]!!.jsonPrimitive.content

    @Test
    fun `没有网关时描述原样`() {
        val registry = ToolRegistry(listOf(tool()))
        val desc = descriptionOf(registry.listResult(), "android_tap")
        assertEquals("点按屏幕", desc)
    }

    @Test
    fun `被挡的工具仍然在清单里，描述尾部挂原因`() {
        // ★ 工具凭空消失，模型就会去瞎试别的路径 —— 它需要一个能被读到的理由。
        val registry = ToolRegistry(listOf(tool())) { "缺无障碍服务" }
        val result = registry.listResult()

        assertEquals("工具不该被删", 1, result["tools"]!!.jsonArray.size)
        val desc = descriptionOf(result, "android_tap")
        assertTrue("原描述必须还在（前缀稳定）", desc.startsWith("点按屏幕"))
        assertTrue("要写明不可用：$desc", desc.contains("当前不可用"))
        assertTrue("要写明原因：$desc", desc.contains("缺无障碍服务"))
    }

    @Test
    fun `被挡的调用一个字节都不执行`() = runTest {
        val registry = ToolRegistry(listOf(tool())) { "缺无障碍服务" }
        val dispatcher = McpDispatcher(registry)

        val resp = dispatcher.dispatch(
            request(McpProtocol.METHOD_TOOLS_CALL, callParams("android_tap")),
        )
        val body = resp!!["result"]!!.jsonObject
        assertEquals(true, body["isError"]!!.jsonPrimitive.booleanOrNull)
        assertEquals("被挡的工具不该被执行", 0, calls)

        val text = body["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("失败原因要能直接给用户看：$text", text.contains("缺无障碍服务"))
    }

    @Test
    fun `允许的工具照常执行`() = runTest {
        val registry = ToolRegistry(listOf(tool())) { null }
        val dispatcher = McpDispatcher(registry)

        val resp = dispatcher.dispatch(
            request(McpProtocol.METHOD_TOOLS_CALL, callParams("android_tap")),
        )
        val body = resp!!["result"]!!.jsonObject
        assertNull("正常调用不该带 isError", body["isError"])
        assertEquals(1, calls)
    }

    @Test
    fun `网关每次现问，不会缓存上次的结论`() = runTest {
        // 用户可能在任务跑到一半关掉无障碍 —— 缓存会把旧权限继续当真。
        var blocked = true
        val registry = ToolRegistry(listOf(tool())) { if (blocked) "缺无障碍服务" else null }
        val dispatcher = McpDispatcher(registry)

        val first = dispatcher.dispatch(request(McpProtocol.METHOD_TOOLS_CALL, callParams("android_tap")))
        assertEquals(true, first!!["result"]!!.jsonObject["isError"]!!.jsonPrimitive.booleanOrNull)

        blocked = false
        val second = dispatcher.dispatch(request(McpProtocol.METHOD_TOOLS_CALL, callParams("android_tap")))
        assertNull(second!!["result"]!!.jsonObject["isError"])
        assertEquals("权限放开后应该真的跑了一次", 1, calls)
    }

    @Test
    fun `同一份网关反复 list 不会把原因叠加`() {
        // 描述是每次现拼的；如果拼在工具对象上，第二次就会变成
        // "⚠️ 当前不可用：X ⚠️ 当前不可用：X" —— 越跑越长。
        val registry = ToolRegistry(listOf(tool())) { "缺无障碍服务" }
        val once = descriptionOf(registry.listResult(), "android_tap")
        val twice = descriptionOf(registry.listResult(), "android_tap")
        assertEquals(once, twice)
        assertEquals(1, Regex("当前不可用").findAll(twice).count())
    }

    @Test
    fun `未知工具不被网关拦`() {
        // 未知工具走的是 -32602「未知工具」，不是"不可用"。
        // 两句话混在一起，模型会以为那个工具只是缺权限，从而反复重试。
        val registry = ToolRegistry(listOf(tool())) { "缺无障碍服务" }
        assertNull(registry.blockReason("no_such_tool"))
    }

    @Test
    fun `不可用的原因里不能出现英文标记`() {
        // 这条原因会直接进模型上下文与界面文案；混入 TODO/FIXME 之类的
        // 占位符会让整句看起来像未完工的提示。
        val registry = ToolRegistry(listOf(tool())) { "缺无障碍服务" }
        val desc = descriptionOf(registry.listResult(), "android_tap")
        assertFalse(desc.contains("TODO"))
        assertFalse(desc.contains("FIXME"))
        assertFalse("不可用说明不该是空的", desc.endsWith("当前不可用："))
    }
}
