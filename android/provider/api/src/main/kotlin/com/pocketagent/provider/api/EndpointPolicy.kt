package com.pocketagent.provider.api

/**
 * 用户填的接口地址要做三件事，缺一件就是"接不上"：
 *
 * 1. **补协议**。用户习惯只写 `192.168.1.10:8091`，不写 `http://`。
 * 2. **拼路径**。有人填 `http://ip:8091`，有人填 `http://ip:8091/v1`，
 *    还有人直接把 `.../v1/audio/speech` 整条粘进来。三种都要能用。
 * 3. **判断明文能不能走**。局域网（含热点、回环）允许 http；公网必须 https，
 *    因为模型请求里带着用户的 Key。
 *
 * 纯字符串逻辑，不碰网络，可以在普通 JVM 上测。
 */
object EndpointPolicy {

    sealed interface Verdict {
        /** 可用。[lan] 表示这是局域网/本机地址（明文也不算泄露）。 */
        data class Ok(val url: String, val lan: Boolean) : Verdict

        /** 公网明文：能连上，但 Key 会裸奔。 */
        data class CleartextPublic(val url: String) : Verdict

        /** 地址本身不成立。 */
        data class Bad(val reason: String) : Verdict
    }

    /** 补上 `http://`，去掉首尾空白。已经是 http/https 的原样返回。 */
    fun normalize(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return ""
        val lower = text.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> text
            "://" in text -> text
            else -> "http://$text"
        }
    }

    /**
     * 拼出最终地址。
     *
     * - `base = http://ip:8091`，`path = /v1/audio/speech` → `http://ip:8091/v1/audio/speech`
     * - `base = http://ip:8091/v1`，`path = /v1/audio/speech` → 去掉重复的 `v1`
     * - `base` 已经以 `path` 结尾 → 原样返回（用户粘的是整条端点）
     */
    fun join(base: String, path: String): String {
        var b = normalize(base).trimEnd('/')
        if (b.isEmpty()) return b
        val p = path.trim()
        if (p.isEmpty()) return b
        val cleanPath = "/" + p.trim('/')
        if (b.endsWith(cleanPath, ignoreCase = true)) return b
        // base 以 /v1 结尾而 path 又是 /v1/... —— 只保留一个
        val v1Dupe = Regex("/v1$", RegexOption.IGNORE_CASE)
        if (cleanPath.startsWith("/v1/", ignoreCase = true) && v1Dupe.containsMatchIn(b)) {
            return b + cleanPath.removePrefix("/v1")
        }
        return b + cleanPath
    }

    /** 本机或局域网地址：明文流量不出网段，可以接受。 */
    fun isLanHost(host: String): Boolean {
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local") || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val (a, b) = nums
        return when {
            a == 127 -> true              // 回环
            a == 10 -> true               // 10/8
            a == 192 && b == 168 -> true  // 192.168/16
            a == 172 && b in 16..31 -> true
            a == 169 && b == 254 -> true  // 链路本地（含 Android 热点常见段）
            a == 100 && b in 64..127 -> true // 运营商级 NAT / 部分热点
            else -> false
        }
    }

    /** 拆出 scheme 与 host；解析不了返回 null。 */
    fun hostOf(url: String): String? {
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://([^/:?#]+)", RegexOption.IGNORE_CASE)
            .find(normalize(url)) ?: return null
        return m.groupValues[2]
    }

    fun schemeOf(url: String): String? {
        val m = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://", RegexOption.IGNORE_CASE)
            .find(normalize(url)) ?: return null
        return m.groupValues[1].lowercase()
    }

    /**
     * 判断一个地址能不能用在前端直连上。
     *
     * [path] 是这家服务商的固定路径（如 `/v1/audio/speech`），拼进来一起判。
     */
    fun check(raw: String, path: String = ""): Verdict {
        val url = join(raw, path)
        if (url.isEmpty()) return Verdict.Bad("地址是空的。填一个，比如 http://192.168.1.10:8091")
        val scheme = schemeOf(url)
            ?: return Verdict.Bad("这个地址看不出协议。写全一点，比如 http://192.168.1.10:8091")
        if (scheme != "http" && scheme != "https") {
            return Verdict.Bad("只支持 http 与 https，收到的是 $scheme://")
        }
        val host = hostOf(url) ?: return Verdict.Bad("地址里读不到主机名：$url")
        val lan = isLanHost(host)
        return if (scheme == "http" && !lan) {
            Verdict.CleartextPublic(url)
        } else {
            Verdict.Ok(url, lan)
        }
    }
}
