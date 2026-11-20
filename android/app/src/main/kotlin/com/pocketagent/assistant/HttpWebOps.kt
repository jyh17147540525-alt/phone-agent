package com.pocketagent.assistant

import com.pocketagent.mcp.DuckDuckGo
import com.pocketagent.mcp.HtmlText
import com.pocketagent.mcp.WebHit
import com.pocketagent.mcp.WebOpsPort
import com.pocketagent.mcp.WebPage
import com.pocketagent.mcp.WebPolicy
import com.pocketagent.mcp.WebUrl
import com.pocketagent.mcp.WebUrlVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 联网能力的**真实实现**：真正开 socket 的那一层。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 三条硬规矩
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **每一跳都重新过关**（[WebUrl.check]）。
 *    第一次 302 跳到 `http://127.0.0.1:8080/admin` 是完全合法的 HTTP，
 *    `HttpURLConnection` 会老老实实跟过去。所以重定向自己走，每跳都判。
 * 2. **体积与时间都封顶**：读 400KB 或 8 秒就停。手机在流量上网时，
 *    一个不封顶的抓取能把一次对话拖到超时。
 * 3. **只读文字**：只接受 text 开头的类型、`application/json`、`application/xml`。
 *    图片、压缩包、APK 一律不读 —— 那不是"页面"，读回来也只是乱码。
 */
class HttpWebOps(
    private val userAgent: String = USER_AGENT,
    /** 额度随「超级网络权限」开关变；每次调用现取，免得开关变了还要重建实例。 */
    private val policy: () -> WebPolicy = { WebPolicy.Default },
) : WebOpsPort {

    override suspend fun fetch(url: String, maxChars: Int): WebPage = withContext(Dispatchers.IO) {
        val budget = policy()
        val (finalUrl, body, contentType) = get(url, budget.maxPageBytes)
        if (!isTexty(contentType)) {
            throw IllegalStateException("这个地址返回的是 ${contentType.substringBefore(';')}，不是网页文字")
        }
        val text = if (contentType.contains("html", ignoreCase = true)) {
            HtmlText.toText(body, maxChars)
        } else {
            body.take(maxChars)
        }
        WebPage(
            title = if (contentType.contains("html", ignoreCase = true)) HtmlText.titleOf(body) else null,
            text = text,
            truncated = body.length >= budget.maxPageBytes || text.length >= maxChars,
        ).also {
            Timber.d("web_fetch：%s → %d 字（%s）", finalUrl.take(60), it.text.length, contentType.substringBefore(';'))
        }
    }

    override suspend fun search(query: String, limit: Int): List<WebHit> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val html = runCatching { get("${DuckDuckGo.ENDPOINT}?q=$encoded", policy().maxSearchBytes).second }
            .getOrElse { error ->
                Timber.w("web_search 取结果页失败：%s", error.message)
                return@withContext emptyList()
            }
        DuckDuckGo.parse(html, limit).also {
            Timber.d("web_search：「%s」→ %d 条", query.take(24), it.size)
        }
    }

    /** 手动跟重定向：每一跳都过 [WebUrl.check]。 */
    private fun get(rawUrl: String, maxBytes: Int): Triple<String, String, String> {
        val budget = policy()
        var current = rawUrl
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = budget.timeoutMs
                readTimeout = budget.timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.5")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("对方返回 $code 但没给跳转地址")
                    if (hops >= budget.maxHops) throw IllegalStateException("跳转太多次（>${budget.maxHops}），放弃")
                    val absolute = URL(URL(current), location).toString()
                    when (val verdict = WebUrl.check(absolute, allowPrivate = budget.allowPrivate)) {
                        is WebUrlVerdict.Bad ->
                            throw IllegalStateException("跳转到了不允许的地址（${verdict.reason}）")
                        is WebUrlVerdict.Ok -> current = verdict.url
                    }
                    hops++
                    continue
                }
                if (code != 200) throw IllegalStateException("对方返回 HTTP $code")
                val contentType = conn.contentType ?: "application/octet-stream"
                val body = conn.inputStream.use { readCapped(it, maxBytes) }
                return Triple(current, body, contentType)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    private fun readCapped(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8_192)
        val out = java.io.ByteArrayOutputStream(minOf(maxBytes, 64_000))
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buffer)
            if (read <= 0) break
            val keep = minOf(read, maxBytes - total)
            out.write(buffer, 0, keep)
            total += keep
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun isTexty(contentType: String): Boolean {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return mime.startsWith("text/") ||
            mime == "application/json" ||
            mime == "application/xml" ||
            mime == "application/xhtml+xml" ||
            mime == "application/rss+xml"
    }

    private companion object {
        const val USER_AGENT = "PocketAgent/1.0 (Android; personal companion app)"
    }
}
