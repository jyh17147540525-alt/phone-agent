package com.pocketagent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网那部分的**纯逻辑**验证。
 *
 * 这些用例都不碰网络：给固定样本，判固定结果。
 * 它们要防的不是"对面改版"（那种事只能靠真机发现），而是
 * **我们自己把危险地址放行了**、或者把脚本当正文读回来。
 */
class WebTextTest {

    // ── URL 过关 ────────────────────────────────────────────────

    @Test
    fun `普通公网地址放行`() {
        for (url in listOf(
            "https://example.com/a/b?c=1",
            "https://news.163.com/",
            "https://zh.wikipedia.org/wiki/Kotlin",
        )) {
            val v = WebUrl.check(url)
            assertTrue("$url 应放行，实际 $v", v is WebUrlVerdict.Ok)
        }
    }

    @Test
    fun `公网明文 http 被拒并说清换成 https`() {
        val v = WebUrl.check("http://example.com/") as WebUrlVerdict.Bad
        assertTrue(v.reason, v.reason.contains("https"))
    }

    @Test
    fun `非 http 协议一律拒`() {
        for (bad in listOf("ftp://example.com/x", "file:///etc/passwd", "javascript:alert(1)")) {
            assertTrue(bad, WebUrl.check(bad) is WebUrlVerdict.Bad)
        }
    }

    @Test
    fun `没写协议的地址被拒并说清要怎么写`() {
        val v = WebUrl.check("example.com") as WebUrlVerdict.Bad
        assertTrue(v.reason, v.reason.contains("http"))
    }

    @Test
    fun `本机与内网地址一个都不许过`() {
        for (bad in listOf(
            "http://localhost/",
            "http://127.0.0.1:8080/admin",
            "http://10.0.0.5/",
            "http://192.168.1.1/",
            "http://172.20.0.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://[::1]/",
            "http://printer.local/",
        )) {
            val v = WebUrl.check(bad)
            assertTrue("$bad 不该放行，实际 $v", v is WebUrlVerdict.Bad)
        }
    }

    @Test
    fun `带用户名密码的地址被拒`() {
        assertTrue(WebUrl.check("https://user:pass@example.com/") is WebUrlVerdict.Bad)
    }

    @Test
    fun `超长地址被拒`() {
        val long = "https://example.com/" + "a".repeat(WebUrl.MAX_LENGTH)
        assertTrue(WebUrl.check(long) is WebUrlVerdict.Bad)
    }

    // ── HTML → 文字 ────────────────────────────────────────────

    @Test
    fun `脚本与样式不進正文`() {
        val html = """
            <html><head><title>标题在这</title>
            <style>.a{color:red}</style>
            <script>var secret = '不该被读出来';</script></head>
            <body><p>正文一段。</p></body></html>
        """.trimIndent()
        val text = HtmlText.toText(html)
        assertTrue(text.contains("正文一段。"))
        assertFalse(text, text.contains("不该被读出来"))
        assertFalse(text, text.contains("color:red"))
        assertEquals("标题在这", HtmlText.titleOf(html))
    }

    @Test
    fun `块级标签之间要换行，不然整页挤成一行`() {
        val text = HtmlText.toText("<div>第一段</div><div>第二段</div><li>一条</li>")
        assertTrue(text, text.contains("第一段\n"))
        assertTrue(text, text.contains("第二段"))
    }

    @Test
    fun `实体被还原`() {
        val text = HtmlText.toText("<p>A &amp; B &lt;tag&gt; &quot;q&quot; &nbsp;完</p>")
        assertTrue(text, text.contains("A & B <tag> \"q\""))
    }

    @Test
    fun `数字实体也能还原，含 emoji`() {
        assertEquals("🎉", HtmlText.decodeEntities("&#x1F389;"))
        assertEquals("中", HtmlText.decodeEntities("&#20013;"))
        // 代理区码点不能崩
        assertEquals("\uFFFD", HtmlText.decodeEntities("&#xD800;"))
    }

    @Test
    fun `超长正文按上限截断`() {
        val text = HtmlText.toText("<p>" + "字".repeat(5_000) + "</p>", maxChars = 100)
        assertEquals(100, text.length)
    }

    // ── 搜索结果解析 ────────────────────────────────────────────

    private val sample = """
        <div class="result results_links">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage&amp;rut=abc">示例<b>标题</b></a>
          </h2>
          <a class="result__snippet" href="https://example.com/page">这是摘要 <b>带标签</b></a>
        </div>
        <div class="result results_links">
          <h2 class="result__title">
            <a rel="nofollow" class="result__a" href="https://second.example.org/x">第二条</a>
          </h2>
          <a class="result__snippet">第二条摘要</a>
        </div>
    """.trimIndent()

    @Test
    fun `解析出标题 真实地址 摘要`() {
        val hits = DuckDuckGo.parse(sample, limit = 5)
        assertEquals(2, hits.size)
        assertEquals("示例标题", hits[0].title)
        assertEquals("https://example.com/page", hits[0].url)
        assertTrue(hits[0].snippet, hits[0].snippet.contains("这是摘要"))
        assertEquals("https://second.example.org/x", hits[1].url)
    }

    @Test
    fun `limit 生效`() {
        assertEquals(1, DuckDuckGo.parse(sample, limit = 1).size)
    }

    @Test
    fun `被包装的内网跳转不会混进结果`() {
        val evil = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=http%3A%2F%2F127.0.0.1%3A8080%2Fadmin">内网面板</a>
        """.trimIndent()
        assertTrue(DuckDuckGo.parse(evil, 5).isEmpty())
    }

    @Test
    fun `改版或空页时返回空列表而不是抛异常`() {
        assertTrue(DuckDuckGo.parse("<html><body>无结果</body></html>", 5).isEmpty())
        assertTrue(DuckDuckGo.parse("", 5).isEmpty())
    }
}
