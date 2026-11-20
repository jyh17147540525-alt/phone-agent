package com.pocketagent.mcp

/**
 * 联网工具的**纯逻辑**：URL 把关、HTML 变文字、搜索结果解析。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么单独拆出来
 * ═══════════════════════════════════════════════════════════════
 *
 * 这三件事都**不需要网络**就能验证：
 *  · 一个 URL 该不该放行；
 *  · 一段 HTML 变成什么文字；
 *  · 搜索页里那些链接有几条、指向哪。
 *
 * 而它们恰好是联网工具里最容易出错、又最难在真机上复现的部分
 * （对面改一版 HTML、或者有人让我们去访问 `127.0.0.1`）。
 * 把它们留在纯函数里，就能用固定样本钉死。
 *
 * ⚠️ 真正的 socket 在 `:app`（`HttpWebOps`）—— 这一层只做判断，不做 IO。
 */

/** 一次搜索命中的一条。 */
data class WebHit(
    val title: String,
    val url: String,
    val snippet: String = "",
)

/** 一个取回来的页面。 */
data class WebPage(
    val title: String?,
    val text: String,
    val truncated: Boolean,
)

/** URL 把关的结论。 */
sealed interface WebUrlVerdict {
    data class Ok(val url: String) : WebUrlVerdict

    data class Bad(val reason: String) : WebUrlVerdict
}

/**
 * URL 把关。
 *
 * ⚠️ 这一层的存在理由不是"礼貌"，是**别让模型把本机变成别人的跳板**：
 *  `http://127.0.0.1:8080/...`、`http://192.168.1.1/...`、云元数据
 *  `http://169.254.169.254/...` 这三类地址在手机上都真实可用，
 *  一旦被写进提示词或用户消息里，模型会照做。
 */
object WebUrl {

    const val MAX_LENGTH = 2_048

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /** 回环/私有/链路本地：**按字符串判**，不做 DNS 解析（解析会被 DNS rebinding 绕过）。 */
    private val BLOCKED_HOST_SUFFIXES = listOf(".local", ".internal", ".localhost", ".localdomain")

    private val BLOCKED_HOSTS = setOf("localhost", "localhost.localdomain", "ip6-localhost")

    fun check(raw: String): WebUrlVerdict {
        val url = raw.trim()
        if (url.isEmpty()) return WebUrlVerdict.Bad("网址是空的。")
        if (url.length > MAX_LENGTH) {
            return WebUrlVerdict.Bad("网址太长（${url.length} 字符，上限 $MAX_LENGTH）。")
        }
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (scheme.isEmpty()) {
            return WebUrlVerdict.Bad("网址要以 http:// 或 https:// 开头，收到的是「$url」。")
        }
        if (scheme.lowercase() !in ALLOWED_SCHEMES) {
            return WebUrlVerdict.Bad("只支持 http 和 https，「$scheme://」不行。")
        }
        // 只收 https：应用的 network_security_config 里公网明文是被系统拦掉的，
        // 放行 http 只会让模型拿到一句 "Cleartext HTTP traffic not permitted"。
        if (scheme.equals("http", ignoreCase = true)) {
            return WebUrlVerdict.Bad("只收 https 的网址（公网明文会被系统拦掉），把 http:// 换成 https:// 再试。")
        }
        val rest = url.substring(scheme.length + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return WebUrlVerdict.Bad("网址里没有域名。")
        if (authority.contains('@')) {
            // user:pass@host 这种写法能把真实目标藏在 @ 后面
            return WebUrlVerdict.Bad("网址里不能带用户名或密码。")
        }
        // ⚠️ IPv6 字面量是 [::1] 这种方括号写法：先按方括号取，再按冒号切端口，
        //    否则 `[::1]` 会被截成 `[`，回环地址就混过去了（离线测试抓到过一次）。
        val host = if (authority.startsWith("[")) {
            authority.substringBefore(']') .plus("]").lowercase()
        } else {
            authority.substringBefore(':').lowercase()
        }
        if (host.isEmpty()) return WebUrlVerdict.Bad("网址里没有域名。")
        if (host in BLOCKED_HOSTS || BLOCKED_HOST_SUFFIXES.any { host.endsWith(it) }) {
            return WebUrlVerdict.Bad("「$host」是本机地址，不能访问（会变成拿你的手机当跳板）。")
        }
        privateHostReason(host)?.let { return WebUrlVerdict.Bad(it) }
        return WebUrlVerdict.Ok(url)
    }

    /** IPv4 字面量落在私有段，或 IPv6 回环/链路本地/唯一本地。 */
    private fun privateHostReason(host: String): String? {
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            val isPrivate = a == 10 || a == 127 || a == 0 ||
                (a == 100 && b in 64..127) || // CGNAT
                (a == 169 && b == 254) || // 链路本地（含云元数据 169.254.169.254）
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                a >= 224 // 组播/保留
            if (isPrivate) return "「$host」是内网/保留地址，不能访问。"
            return null
        }
        val v6 = host.removePrefix("[").removeSuffix("]")
        if (v6.contains(':')) {
            val low = v6.lowercase()
            if (low == "::1" || low == "::" ||
                low.startsWith("fe80") || low.startsWith("fc") || low.startsWith("fd")
            ) {
                return "「$host」是内网/回环地址，不能访问。"
            }
        }
        return null
    }
}

/** HTML → 纯文字。够用就好：不建 DOM，只删"读起来是噪音"的东西。 */
object HtmlText {

    private val DROP_BLOCKS = listOf(
        Regex("(?is)<script\\b[^>]*>.*?</script>"),
        Regex("(?is)<style\\b[^>]*>.*?</style>"),
        Regex("(?is)<noscript\\b[^>]*>.*?</noscript>"),
        Regex("(?is)<svg\\b[^>]*>.*?</svg>"),
        Regex("(?is)<!--.*?-->"),
    )

    /** 这些标签结束时要换行，否则整页挤成一行。 */
    private val BLOCK_TAGS = listOf(
        "p", "div", "section", "article", "br", "li", "tr", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "table", "ul", "ol", "header", "footer", "nav", "main",
    )

    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val SPACES = Regex("[ \\t\\u00A0]+")
    private val BLANK_LINES = Regex("\\n{3,}")

    private val ENTITIES = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&mdash;" to "—",
        "&ndash;" to "–",
        "&hellip;" to "…",
        "&middot;" to "·",
        "&ldquo;" to "“",
        "&rdquo;" to "”",
        "&lsquo;" to "‘",
        "&rsquo;" to "’",
    )

    fun titleOf(html: String): String? =
        TITLE.find(html)?.groupValues?.getOrNull(1)
            ?.let { decodeEntities(it) }
            ?.replace(SPACES, " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun toText(html: String, maxChars: Int = 8_000): String {
        var out = html
        for (re in DROP_BLOCKS) out = re.replace(out, " ")
        for (tag in BLOCK_TAGS) {
            out = out.replace(Regex("(?i)</?$tag\\b[^>]*>"), "\n")
        }
        out = TAG.replace(out, "")
        out = decodeEntities(out)
        out = SPACES.replace(out, " ")
        out = out.lines().joinToString("\n") { it.trim() }
        out = BLANK_LINES.replace(out, "\n\n").trim()
        return out.take(maxChars.coerceAtLeast(1))
    }

    fun decodeEntities(input: String): String {
        var out = input
        for ((entity, char) in ENTITIES) out = out.replace(entity, char)
        // 数字实体：&#123; / &#x1F600;
        out = Regex("&#(x?)([0-9A-Fa-f]{1,6});").replace(out) { m ->
            val code = m.groupValues[2].toIntOrNull(m.groupValues[1].let { if (it == "x") 16 else 10 })
            when {
                code == null -> m.value
                code in 0xD800..0xDFFF -> "\uFFFD"
                code <= 0 -> m.value
                code > 0x10FFFF -> m.value
                else -> String(Character.toChars(code))
            }
        }
        return out
    }
}

/**
 * DuckDuckGo 的 HTML 版结果页。
 *
 * ⚠️ 为什么是它：本项目 BYOK、没有自有服务端，搜索**不能**再要求用户注册一个 key。
 *    DDG 的 html 端点不需要 key、不要账号，返回的是稳定结构的静态 HTML。
 *    代价是它随时可能改版 —— 所以解析失败时**返回空列表**，由工具如实说
 *    "没搜到/被挡了"，而不是编几条结果出来。
 */
object DuckDuckGo {

    const val ENDPOINT = "https://html.duckduckgo.com/html/"

    private val RESULT_ANCHOR = Regex(
        """(?is)<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
    )
    private val SNIPPET = Regex("""(?is)<a[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""")
    private val UDDG = Regex("""[?&]uddg=([^&"]+)""")

    fun parse(html: String, limit: Int = 6): List<WebHit> {
        // 先按块切开，让「标题 + 摘要」能配成对
        val anchors = RESULT_ANCHOR.findAll(html).toList()
        if (anchors.isEmpty()) return emptyList()
        val snippets = SNIPPET.findAll(html).map { HtmlText.toText(it.groupValues[1], 400) }.toList()
        val hits = ArrayList<WebHit>(anchors.size)
        anchors.forEachIndexed { index, m ->
            val href = unwrap(m.groupValues[1])
            val title = HtmlText.toText(m.groupValues[2], 200).replace("\n", " ").trim()
            if (title.isEmpty() || href == null) return@forEachIndexed
            hits += WebHit(title = title, url = href, snippet = snippets.getOrElse(index) { "" }.trim())
        }
        return hits.take(limit.coerceAtLeast(1))
    }

    /** DDG 把真实地址放在 `uddg=` 里；没有包装就原样返回。 */
    fun unwrap(href: String): String? {
        val raw = HtmlText.decodeEntities(href)
        val target = UDDG.find(raw)?.groupValues?.get(1)?.let { decodeUrlComponent(it) } ?: raw
        val verdict = WebUrl.check(target)
        return (verdict as? WebUrlVerdict.Ok)?.url
    }

    private fun decodeUrlComponent(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrElse { s }
}
