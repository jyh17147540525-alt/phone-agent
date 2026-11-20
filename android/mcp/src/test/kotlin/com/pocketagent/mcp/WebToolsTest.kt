package com.pocketagent.mcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 两个联网工具的**行为**验证：用假端口，不碰网络。 */
class WebToolsTest {

    private class FakeOps(
        private val page: WebPage = WebPage(title = "示例站", text = "正文内容", truncated = false),
        private val hits: List<WebHit> = listOf(WebHit("标题一", "https://a.example/1", "摘要一")),
        private val throwOnFetch: Boolean = false,
    ) : WebOpsPort {
        var lastUrl: String? = null
        var lastChars: Int = 0
        var lastQuery: String? = null
        var lastLimit: Int = 0

        override suspend fun fetch(url: String, maxChars: Int): WebPage {
            lastUrl = url
            lastChars = maxChars
            if (throwOnFetch) throw IllegalStateException("connect timed out")
            return page
        }

        override suspend fun search(query: String, limit: Int): List<WebHit> {
            lastQuery = query
            lastLimit = limit
            return hits
        }
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    // ── web_fetch ──────────────────────────────────────────────

    @Test
    fun `fetch 把标题 网址 正文一起给模型`() = runTest {
        val ops = FakeOps()
        val outcome = WebFetchTool(ops).call(args("url" to "https://example.com/a"))
        val text = (outcome as ToolOutcome.Success).text
        assertTrue(text, text.contains("标题：示例站"))
        assertTrue(text, text.contains("网址：https://example.com/a"))
        assertTrue(text, text.contains("正文内容"))
    }

    @Test
    fun `fetch 默认只要 6000 字，可传参覆盖`() = runTest {
        val ops = FakeOps()
        WebFetchTool(ops).call(args("url" to "https://example.com/"))
        assertEquals(6_000, ops.lastChars)
        WebFetchTool(ops).call(args("url" to "https://example.com/", "maxChars" to "900"))
        assertEquals(900, ops.lastChars)
    }

    @Test
    fun `fetch 拒绝内网地址且不发起请求`() = runTest {
        val ops = FakeOps()
        val outcome = WebFetchTool(ops).call(args("url" to "http://127.0.0.1:8080/admin"))
        assertTrue(outcome is ToolOutcome.Failed)
        assertEquals(null, ops.lastUrl)
    }

    @Test
    fun `fetch 缺参数时告诉要带协议`() = runTest {
        val outcome = WebFetchTool(FakeOps()).call(args())
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue(reason, reason.contains("url"))
    }

    @Test
    fun `fetch 打不开时说人话`() = runTest {
        val outcome = WebFetchTool(FakeOps(throwOnFetch = true)).call(args("url" to "https://example.com/"))
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue(reason, reason.contains("打不开"))
    }

    @Test
    fun `fetch 遇到没有文字的页面要说清`() = runTest {
        val outcome = WebFetchTool(FakeOps(page = WebPage("标题", "   ", true)))
            .call(args("url" to "https://example.com/"))
        assertTrue((outcome as ToolOutcome.Failed).reason.contains("没有可读的文字"))
    }

    @Test
    fun `fetch 截断时如实标注`() = runTest {
        val outcome = WebFetchTool(FakeOps(page = WebPage("t", "正文", truncated = true)))
            .call(args("url" to "https://example.com/"))
        assertTrue((outcome as ToolOutcome.Success).text.contains("只取了一部分"))
    }

    // ── web_search ─────────────────────────────────────────────

    @Test
    fun `search 列出编号 标题 网址 摘要`() = runTest {
        val outcome = WebSearchTool(FakeOps()).call(args("query" to "kotlin 协程"))
        val text = (outcome as ToolOutcome.Success).text
        assertTrue(text, text.contains("1. 标题一"))
        assertTrue(text, text.contains("https://a.example/1"))
        assertTrue(text, text.contains("摘要一"))
    }

    @Test
    fun `search 默认 5 条，超过 10 被夹住`() = runTest {
        val ops = FakeOps()
        WebSearchTool(ops).call(args("query" to "x"))
        assertEquals(5, ops.lastLimit)
        WebSearchTool(ops).call(args("query" to "x", "limit" to "99"))
        assertEquals(10, ops.lastLimit)
    }

    @Test
    fun `search 空结果不编造，给下一步`() = runTest {
        val outcome = WebSearchTool(FakeOps(hits = emptyList())).call(args("query" to "很怪的东西"))
        val reason = (outcome as ToolOutcome.Failed).reason
        assertTrue(reason, reason.contains("没有搜到"))
        assertTrue(reason, reason.contains("换个说法"))
    }

    @Test
    fun `search 缺查询词直接拒`() = runTest {
        assertTrue(WebSearchTool(FakeOps()).call(args()) is ToolOutcome.Failed)
    }
}
