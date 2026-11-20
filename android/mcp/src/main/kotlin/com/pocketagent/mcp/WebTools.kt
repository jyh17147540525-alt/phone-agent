package com.pocketagent.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 联网：`web_search` 与 `web_fetch`。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这两个工具值得单独存在
 * ═══════════════════════════════════════════════════════════════
 *
 * 在此之前，模型想查点东西只有一条路：`linux_exec` 里 `curl` 一下，
 * 然后自己从一整页 HTML 里挑字。实际后果是：
 *  · 经常把 `<style>` 里的 CSS 当正文读回来；
 *  · 不知道对面返回的是 302 还是 200；
 *  · 一不小心 curl 到 `127.0.0.1`（手机上真能通）。
 *
 * 这两个工具把那三件事都收进代码里：URL 先过关、HTML 转成文字、体积封顶。
 *
 * ⚠️ 失败必须说**人话**：`Refused` 用于"这台机器/这个设置现在不让做"，
 *    `Failed` 用于"试了但没成"。合成一句"失败了"会让模型反复重试同一件事。
 */

/** 联网能力。由 `:app` 实现（真正开 socket 的地方）。 */
interface WebOpsPort {

    /** 取一个页面。`url` 已经过 [WebUrl.check]。 */
    suspend fun fetch(url: String, maxChars: Int): WebPage

    /** 搜一次。返回空列表表示"没搜到或被挡了"，不是异常。 */
    suspend fun search(query: String, limit: Int): List<WebHit>
}

class WebFetchTool(
    private val ops: WebOpsPort,
    private val policy: () -> WebPolicy = { WebPolicy.Default },
) : McpTool {

    override val name: String = "web_fetch"

    override val description: String =
        "打开一个网页，把它变成纯文字读回来（去掉脚本、样式、标签）。" +
            "适合看新闻、文档、说明页。取不到或不是网页时会说清原因；只读，不改任何东西。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("url") {
                put("type", "string")
                put("description", "要打开的网址，必须以 http:// 或 https:// 开头")
            }
            putJsonObject("maxChars") {
                put("type", "integer")
                put("description", "最多返回多少字，200–20000，默认 6000")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("url")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val raw = arguments["url"].stringOrNull()
            ?: return ToolOutcome.Failed("缺少 url 参数（要带 http:// 或 https://）。")
        val url = when (val verdict = WebUrl.check(raw, allowPrivate = policy().allowPrivate)) {
            is WebUrlVerdict.Ok -> verdict.url
            is WebUrlVerdict.Bad -> return ToolOutcome.Failed(verdict.reason)
        }
        val maxChars = (arguments["maxChars"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CHARS)
            .coerceIn(MIN_CHARS, MAX_CHARS)

        val page = runCatching { ops.fetch(url, maxChars) }.getOrElse { error ->
            return ToolOutcome.Failed("打不开这个网址（${error.javaClass.simpleName}）。")
        }
        if (page.text.isBlank()) {
            return ToolOutcome.Failed("这个页面上没有可读的文字（可能是纯图片或需要登录）。")
        }
        val text = buildString {
            page.title?.takeIf { it.isNotBlank() }?.let { append("标题：").append(it).append('\n') }
            append("网址：").append(url).append('\n')
            append("--- 正文 ---\n").append(page.text)
            if (page.truncated) append("\n\n（内容太长，只取了一部分）")
        }
        return ToolOutcome.Success(text = text)
    }

    private companion object {
        const val DEFAULT_CHARS = 6_000
        const val MIN_CHARS = 200
        const val MAX_CHARS = 20_000
    }
}

class WebSearchTool(
    private val ops: WebOpsPort,
    private val policy: () -> WebPolicy = { WebPolicy.Default },
) : McpTool {

    override val name: String = "web_search"

    override val description: String =
        "在网上搜东西，返回若干条结果（标题 + 网址 + 摘要）。" +
            "查资料、看最新消息、找官网时用它；要读全文再用 web_fetch。" +
            "搜不到会如实说，不会编造结果。"

    override fun inputSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "查询词。短一点更好用，像人搜东西那样。")
            }
            putJsonObject("limit") {
                put("type", "integer")
                put("description", "要几条，1–10，默认 5")
            }
        }
        putJsonArray("required") { add(JsonPrimitive("query")) }
    }

    override suspend fun call(arguments: JsonObject): ToolOutcome {
        val query = arguments["query"].stringOrNull()?.trim().orEmpty()
        if (query.isEmpty()) return ToolOutcome.Failed("缺少 query 参数（要搜什么）。")
        if (query.length > MAX_QUERY) {
            return ToolOutcome.Failed("查询词太长（${query.length} 字，上限 $MAX_QUERY）。")
        }
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: DEFAULT_LIMIT).coerceIn(1, 10)

        val hits = runCatching { ops.search(query, limit) }.getOrElse { error ->
            return ToolOutcome.Failed("搜索没成功（${error.javaClass.simpleName}）。")
        }
        if (hits.isEmpty()) {
            return ToolOutcome.Failed(
                "这次没有搜到结果（也可能搜索源被挡了）。可以换个说法再搜，或者直接给我网址。",
            )
        }
        val text = buildString {
            append("搜索「").append(query).append("」得到 ").append(hits.size).append(" 条：\n")
            hits.forEachIndexed { index, hit ->
                append(index + 1).append(". ").append(hit.title).append('\n')
                append("   ").append(hit.url).append('\n')
                if (hit.snippet.isNotBlank()) append("   ").append(hit.snippet).append('\n')
            }
        }
        return ToolOutcome.Success(text = text.trim())
    }

    private companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_QUERY = 200
    }
}

private fun kotlinx.serialization.json.JsonElement?.stringOrNull(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive is kotlinx.serialization.json.JsonNull) return null
    return primitive.content
}
