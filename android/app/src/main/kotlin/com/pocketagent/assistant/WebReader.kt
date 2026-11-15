package com.pocketagent.assistant

import com.pocketagent.personalogic.WebDigest
import com.pocketagent.personalogic.WebItem
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * **她今天在网上看到了什么** —— 在场模式唯一的联网读取。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 只要「一条」，不像搜索
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要的是"角色会分享自己在今天在网上看到了什么有趣的事情"。
 * 那不是"帮她查资料"，是"她随手翻到了点什么" —— 所以：
 *  · **不传任何用户信息**（没有查询词、没有 id），只是访问一个公开页面；
 *  · 一天只取一条，取到就锁住（见 `AppContainer.lifeformWebItem`）；
 *  · 取不到就返回 null，由调用方用兜底池 —— 这里**不重试、不兜圈子**。
 *
 * ⚠️ 两个源都不需要 Key、不需要账号（本项目是 BYOK，不该为了"看点什么"
 *    让用户再注册一个账号）：
 *  1. 维基百科 `page/random/summary` —— 是真的"随手翻到一篇"，比固定
 *     话题列表更接近"逛"这件事；
 *  2. 一言（hitokoto）—— 维基那页常常是地名或年份，读起来像查资料；
 *     一句话的短句更像"看到了一句喜欢的话"。
 * ⇒ 两个都失败才算失败。
 *
 * ⚠️ **超时 5 秒、体积封顶**：这是在后台 tick 里跑的，不能因为对方卡住
 *    把主动消息那条链整体拖住。
 */
class WebReader {

    /** 取一条。失败返回 null（**不抛**）。 */
    fun fetch(): WebItem? =
        runCatching { fetchWiki() }.getOrNull() ?: runCatching { fetchHitokoto() }.getOrNull()

    private fun fetchWiki(): WebItem? {
        val body = get(WIKI_RANDOM) ?: return null
        return WebDigest.parseSummary(body)
    }

    private fun fetchHitokoto(): WebItem? {
        val body = get(HITOKOTO) ?: return null
        return WebDigest.parseHitokoto(body)
    }

    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // ★ 维基要求带 User-Agent（不带会被 403）。写清是谁在访问，
            //   不夹带任何设备信息。
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                Timber.d("网上见闻：%s 返回 %d", url.substringAfter("://").take(24), code)
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }.take(MAX_BODY_CHARS)
        } catch (t: Throwable) {
            Timber.d("网上见闻取不到：%s", t.message)
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000

        /** 一条摘要正常不到 2KB；封顶只是防止对面回一整页 HTML。 */
        const val MAX_BODY_CHARS = 20_000

        const val USER_AGENT = "PocketAgent/1.0 (Android; personal companion app)"

        const val WIKI_RANDOM = "https://zh.wikipedia.org/api/rest_v1/page/random/summary"

        /** `c=d` 文学、`c=i` 诗词、`c=k` 哲学 —— 只有这三类像"看到一句话"。 */
        const val HITOKOTO = "https://v1.hitokoto.cn/?c=d&c=i&c=k"
    }
}
