package com.pocketagent.safety

/**
 * **进攻性能力的目标边界** —— 任何"对外发包"的动作在执行前必须先过这里。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这个类必须先于"能力"存在
 * ═══════════════════════════════════════════════════════════════
 *
 * 把一个能做侦察/扫描/利用的模块塞进**装在别人手机上、GPL 分发**的
 * 客户端里，最危险的不是它会不会用错，而是它**默认指向谁**。
 *
 * 一个"没有范围概念"的攻击工具，安装完成的那一刻就等于把枪口
 * 指向了互联网上所有人 —— 而它的使用者只需要打一行字。
 *
 * ⇒ 所以纪律是：**范围清单默认是空的。空清单 = 什么都打不了。**
 *   要打，得先手写 `scope.txt`（一行一条：IP / CIDR / 域名 / *.域名）。
 *   这条不是"免责声明"，它是**默认失败（fail-closed）的实现**：
 *   [check] 在清单为空时对任何目标都返回 [ScopeDecision.Denied]。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 支持的写法
 * ═══════════════════════════════════════════════════════════════
 *
 * ```
 * 192.0.2.10            # 单 IP
 * 198.51.100.0/24       # C 段
 * 2001:db8::/32         # IPv6 段（前缀比较，不做压缩归一化）
 * api.example.com       # 精确主机名
 * *.example.com         # 子域（**不含** example.com 本身）
 * .example.com          # 同上
 * ```
 *
 * ⚠️ 后缀匹配必须按**标签边界**比：`evilexample.com` 不能被
 *    `example.com` 命中。这条是扫描器最经典的越界方式（自建
 *    `example.com.attacker.net` 之类），[matchesDomain] 用
 *    "等于，或以 `.` 接在后面"两条判，绝不用 `endsWith` 裸比。
 *
 * ⚠️ 本类**不做 DNS 解析**。目标字符串里出现域名就按域名比 ——
 *    解析会引入 TOCTOU（校验时解析到 A 记录，发包时换成 B），
 *    而这类绕过是真实存在的。要打某个 IP，就把那个 IP 写进清单。
 */
class ScopeGuard(entries: List<String> = emptyList()) {

    private val rules: List<Rule> = entries.mapNotNull { Rule.parse(it) }

    /** 清单里一条有效规则都没有。此时任何目标都会被拒。 */
    val isEmpty: Boolean get() = rules.isEmpty()

    /** 解析出来的有效规则条数（界面显示"你授权了 N 个目标"）。 */
    val size: Int get() = rules.size

    /**
     * 判定一个目标在不在范围内。
     *
     * @param target 允许写成 `https://host:port/path`、`host:port`、`host`、`1.2.3.4`
     */
    fun check(target: String): ScopeDecision {
        val host = extractHost(target)
            ?: return ScopeDecision.Denied(target, "目标解析不出主机名")
        if (rules.isEmpty()) {
            return ScopeDecision.Denied(target, "范围清单为空 —— 先写 scope.txt 再谈发包")
        }
        for (rule in rules) {
            if (rule.matches(host)) {
                return ScopeDecision.Allowed(target, rule.text)
            }
        }
        return ScopeDecision.Denied(target, "不在范围清单里（$host）")
    }

    /**
     * 从各种写法里抠出主机名。
     *
     * ⚠️ 端口一律丢掉：范围管的是"哪台机器"，不是"哪个端口"。
     *    端口在能力参数里另行限制（例如只允许 80/443/8080）。
     */
    private fun extractHost(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        // 去 scheme
        val schemeAt = s.indexOf("://")
        if (schemeAt >= 0) s = s.substring(schemeAt + 3)
        // 去 path / query / fragment
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        // 去 userinfo
        s = s.substringAfterLast('@')
        // 去端口（IPv6 字面量 [::1]:80 单独处理）
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end < 0) return null
            return s.substring(1, end).lowercase().ifEmpty { null }
        }
        // IPv6 字面量常写成裸形式（2001:db8::1），不能按「去端口」截断 ——
        // 那样 `2001:db8::1` 会被切成 `2001`，于是 /32 规则意外命中一切。
        if (s.count { it == ':' } > 1) return s.lowercase()
        s = s.substringBefore(':')
        return s.lowercase().ifEmpty { null }
    }

    private sealed interface Rule {
        val text: String
        fun matches(host: String): Boolean

        /** 精确主机 / 后缀。 */
        data class Domain(override val text: String, val bare: String, val suffixOnly: Boolean) : Rule {
            override fun matches(host: String): Boolean =
                if (suffixOnly) matchesDomain(host, bare) && host != bare
                else host == bare
        }

        /** IPv4 / IPv6 前缀。 */
        data class Cidr(override val text: String, val prefix: ByteArray, val bits: Int) : Rule {
            override fun matches(host: String): Boolean {
                val addr = parseIp(host) ?: return false
                if (addr.size != prefix.size) return false
                var remaining = bits
                for (i in addr.indices) {
                    if (remaining <= 0) return true
                    val take = if (remaining >= 8) 8 else remaining
                    val mask = (0xFF shl (8 - take)) and 0xFF
                    if ((addr[i].toInt() and mask) != (prefix[i].toInt() and mask)) return false
                    remaining -= take
                }
                return true
            }
        }

        companion object {
            fun parse(line: String): Rule? {
                val t = line.substringBefore('#').trim()
                if (t.isEmpty()) return null
                val slash = t.indexOf('/')
                if (slash > 0) {
                    val prefix = parseIp(t.substring(0, slash)) ?: return null
                    val bits = t.substring(slash + 1).toIntOrNull() ?: return null
                    val maxBits = prefix.size * 8
                    if (bits !in 0..maxBits) return null
                    return Cidr(t, prefix, bits)
                }
                parseIp(t)?.let { addr ->
                    // 单个 IP 也走 /32、/128
                    return Cidr(t, addr, addr.size * 8)
                }
                val lower = t.lowercase()
                // 长得像 IPv4 却解析不出来（999.1.1.1）时**必须丢弃**：
                // 否则它会被当成主机名规则存进清单，而它永远命中不了任何目标，
                // 界面上却显示"授权了 N 个目标" —— 一条静默失效的授权。
                if (lower.matches(Regex("""\d+(\.\d+){3}"""))) return null
                return when {
                    lower.startsWith("*.") -> {
                        val bare = lower.removePrefix("*.")
                        if (bare.isEmpty()) null else Domain(t, bare, suffixOnly = true)
                    }
                    lower.startsWith(".") -> {
                        val bare = lower.removePrefix(".")
                        if (bare.isEmpty()) null else Domain(t, bare, suffixOnly = true)
                    }
                    else -> Domain(t, lower, suffixOnly = false)
                }
            }
        }
    }

    private companion object {

        /** 标签边界匹配：等于，或以「.」接在后面。绝不用裸 endsWith。 */
        fun matchesDomain(host: String, domain: String): Boolean =
            host == domain || host.endsWith(".$domain")

        fun parseIp(s: String): ByteArray? {
            if (s.contains(':')) {
                // IPv6：只支持完整写法与 :: 展开，够用于前缀比较
                return parseIpv6(s)
            }
            val parts = s.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0 until 4) {
                val v = parts[i].toIntOrNull() ?: return null
                if (v !in 0..255) return null
                out[i] = v.toByte()
            }
            return out
        }

        fun parseIpv6(s: String): ByteArray? {
            val dbl = s.split("::")
            if (dbl.size > 2) return null
            val head = if (dbl[0].isEmpty()) emptyList() else dbl[0].split(':')
            val tail = if (dbl.size == 2 && dbl[1].isNotEmpty()) dbl[1].split(':') else emptyList()
            val fill = 8 - head.size - tail.size
            if (fill < 0) return null
            val groups = head + List(fill) { "0" } + tail
            if (groups.size != 8) return null
            val out = ByteArray(16)
            for (i in groups.indices) {
                val v = groups[i].toIntOrNull(16) ?: return null
                if (v !in 0..0xFFFF) return null
                out[i * 2] = (v shr 8).toByte()
                out[i * 2 + 1] = (v and 0xFF).toByte()
            }
            return out
        }
    }
}

/** [ScopeGuard.check] 的判定结果。 */
sealed interface ScopeDecision {

    data class Allowed(val target: String, val matched: String) : ScopeDecision

    /** @param reason 给日志与界面看的一句话，不要写"已阻止"这种没有信息量的词。 */
    data class Denied(val target: String, val reason: String) : ScopeDecision
}
