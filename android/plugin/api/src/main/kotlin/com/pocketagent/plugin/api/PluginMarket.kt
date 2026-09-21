package com.pocketagent.plugin.api

/**
 * 插件市场 —— 索引解析、检索与版本比较。
 *
 * ═══════════════════════════════════════════════════════════════
 *  市场没有服务端
 * ═══════════════════════════════════════════════════════════════
 *
 * 所谓"插件市场"其实是**若干个静态 JSON 文件**，托管在 GitHub Pages / raw 上。
 * 本应用把用户订阅的源拉下来、合并、检索、展示。
 *
 * 这样做的好处不只是省钱：
 *
 *  - **没有单点**：源挂了只影响那个源，其他源照常
 *  - **没有审核权**：任何人都能架自己的源，本应用不承担内容审核义务
 *  - **没有账户体系**：不需要登录就能浏览与下载
 *
 * 代价是**没有中心化的下架能力**。一个源如果开始分发恶意插件，
 * 我们只能：① 把它标成"来源不可信"并提示用户 ② 在本体内置一份黑名单。
 * 所以下面的 [MarketCatalog] 会对每个源做能力检查，把"这个源在分发申请
 * 禁止能力的插件"这件事**明确暴露给用户**，而不是悄悄过滤掉。
 *
 * ⚠️ 设计要点：**过滤要留痕**。悄悄丢弃违规条目会让用户以为源是干净的。
 */
object MarketSearch {

    /**
     * 检索。
     *
     * 纯函数，不涉及网络 —— 所以它可以在没有 Android SDK 的机器上完整测试。
     */
    fun search(entries: List<MarketEntry>, query: MarketQuery): List<MarketEntry> {
        val filtered = entries.asSequence()
            .filter { matchesKeyword(it, query.keyword) }
            .filter { query.levels.isEmpty() || it.level in query.levels }
            .filter { it.capabilities.containsAll(query.requiredCapabilities) }
            .filter { !query.excludeHighRisk || !it.isHighRisk }
            .toList()

        return when (query.sort) {
            MarketQuery.Sort.RELEVANCE -> filtered.sortedWith(
                compareByDescending<MarketEntry> { relevance(it, query.keyword) }
                    .thenBy { it.name }
            )

            MarketQuery.Sort.NAME -> filtered.sortedBy { it.name }

            // 风险升序：最安全的排前面。用户想找"能用的"而不是"权限最大的"
            MarketQuery.Sort.RISK -> filtered.sortedWith(
                compareBy<MarketEntry> { it.highestRisk?.ordinal ?: -1 }
                    .thenBy { it.name }
            )

            MarketQuery.Sort.UPDATED -> filtered.sortedByDescending { it.updatedAt ?: "" }
        }
    }

    private fun matchesKeyword(entry: MarketEntry, keyword: String): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty()) return true

        // 中英文都要支持：英文忽略大小写，中文本来就是无大小写的
        val lower = kw.lowercase()
        return entry.name.lowercase().contains(lower) ||
            entry.id.lowercase().contains(lower) ||
            entry.description?.lowercase()?.contains(lower) == true ||
            entry.author?.lowercase()?.contains(lower) == true ||
            entry.capabilities.any { it.id.contains(lower) }
    }

    /**
     * 相关度打分。
     *
     * 权重顺序反映了用户的真实意图：他打"微信"是想找名字里带微信的，
     * 而不是描述里恰好提了一句微信的。
     */
    private fun relevance(entry: MarketEntry, keyword: String): Int {
        val kw = keyword.trim().lowercase()
        if (kw.isEmpty()) return 0

        val name = entry.name.lowercase()
        val id = entry.id.lowercase()

        return when {
            name == kw -> 100
            name.startsWith(kw) -> 80
            name.contains(kw) -> 60
            id.contains(kw) -> 40
            entry.description?.lowercase()?.contains(kw) == true -> 20
            entry.author?.lowercase()?.contains(kw) == true -> 10
            else -> 0
        }
    }
}

/** 检索条件 */
data class MarketQuery(
    val keyword: String = "",
    /** 只显示这些级别。空 = 不限 */
    val levels: Set<PluginLevel> = emptySet(),
    /** 必须包含这些能力 */
    val requiredCapabilities: Set<PluginCapability> = emptySet(),
    /** 隐藏高/极高风险的插件 */
    val excludeHighRisk: Boolean = false,
    val sort: Sort = Sort.RELEVANCE,
) {
    enum class Sort {
        /** 相关度（默认） */
        RELEVANCE,
        NAME,
        /** 风险从低到高 */
        RISK,
        UPDATED,
    }
}

/** 市场里的一个插件条目 */
data class MarketEntry(
    val id: String,
    val name: String,
    val version: String,
    val level: PluginLevel,
    val capabilities: List<PluginCapability>,
    val targetApps: List<String> = emptyList(),
    val description: String? = null,
    val author: String? = null,
    val downloadUrl: String,
    val sha256: String,
    val signature: String? = null,
    val updatedAt: String? = null,
    /** 来自哪个订阅源，用于展示"来源"并让用户判断可信度 */
    val sourceName: String = "",
    /** 该条目声明的原始能力字符串（含未知项），用于排错与审计 */
    val rawCapabilityIds: List<String> = emptyList(),
) {
    val highestRisk: PluginCapability.RiskLevel?
        get() = PluginValidator.summarizeRisk(capabilities)

    val isHighRisk: Boolean
        get() = highestRisk == PluginCapability.RiskLevel.HIGH ||
            highestRisk == PluginCapability.RiskLevel.CRITICAL

    /**
     * 声明了、但本版本不认识的能力。
     *
     * 这些能力在转成 [MarketEntry] 时已经被丢掉（不会授权给插件，也授权不了），
     * 但原始字符串留在 [rawCapabilityIds] 里 —— 因为「这个插件需要更新的本体」
     * 是用户有权知道的事。界面据此提示"它可能无法正常工作，请升级本应用"。
     *
     * 与 [MarketCatalog.rejected] 的区别：那些是申请了禁止能力、**整个被拒**的条目；
     * 这些是条目还在、只是部分能力用不了。两者都不能悄悄吞掉。
     */
    val unsupportedCapabilityIds: List<String>
        get() = rawCapabilityIds.filter { PluginCapability.fromId(it.trim().lowercase()) == null }

    val trustLevel: PluginTrustLevel
        get() = if (signature != null) PluginTrustLevel.VERIFIED_SIGNATURE
        else PluginTrustLevel.MARKET_UNSIGNED

    /** 是否比已安装的版本更新。未安装时返回 false（那是"可安装"而不是"可更新"） */
    fun hasUpdate(installedVersion: String?): Boolean {
        if (installedVersion == null) return false
        val installed = SemanticVersion.parse(installedVersion) ?: return false
        val available = SemanticVersion.parse(version) ?: return false
        return available > installed
    }
}

/**
 * 合并后的市场目录。
 *
 * [rejected] 不是内部日志 —— 它必须被展示给用户。
 * 「你订阅的某个源正在分发申请禁止能力的插件」这件事，用户有权知道。
 */
data class MarketCatalog(
    val entries: List<MarketEntry> = emptyList(),
    val rejected: List<RejectedEntry> = emptyList(),
    val sources: List<MarketSourceStatus> = emptyList(),
) {
    val hasRejected: Boolean get() = rejected.isNotEmpty()

    companion object {
        /**
         * 从多个订阅源合并出目录。
         *
         * 去重规则：**同一 id 保留版本更高的那个**。版本相同时保留先出现的
         * （即用户订阅列表里靠前的源）—— 让用户能通过调整订阅顺序来表达偏好。
         */
        fun merge(sources: List<Pair<String, SubscriptionSource?>>): MarketCatalog {
            val entries = LinkedHashMap<String, MarketEntry>()
            val rejected = mutableListOf<RejectedEntry>()
            val statuses = mutableListOf<MarketSourceStatus>()

            for ((sourceName, source) in sources) {
                if (source == null) {
                    statuses += MarketSourceStatus(sourceName, ok = false, message = "无法加载该订阅源")
                    continue
                }

                var accepted = 0
                for (raw in source.plugins) {
                    val issues = PluginValidator.inspectRawCapabilities(raw.capabilities)
                    val fatal = issues.filter { it.severity == IssueSeverity.ERROR }

                    if (fatal.isNotEmpty()) {
                        rejected += RejectedEntry(
                            id = raw.id,
                            name = raw.name,
                            sourceName = sourceName,
                            reasons = fatal.map { it.message },
                        )
                        continue
                    }

                    val entry = raw.toMarketEntry(sourceName)
                    val existing = entries[entry.id]
                    if (existing == null || (SemanticVersion.parse(entry.version) ?: RETURN_ZERO) >
                        (SemanticVersion.parse(existing.version) ?: RETURN_ZERO)
                    ) {
                        entries[entry.id] = entry
                    }
                    accepted++
                }

                statuses += MarketSourceStatus(
                    name = sourceName,
                    ok = true,
                    message = "已加载 $accepted 个插件",
                )
            }

            return MarketCatalog(
                entries = entries.values.sortedBy { it.name },
                rejected = rejected,
                sources = statuses,
            )
        }

        private val RETURN_ZERO = SemanticVersion(0, 0, 0)
    }
}

/** 被拒绝的市场条目 —— 必须展示给用户，不能悄悄丢掉 */
data class RejectedEntry(
    val id: String,
    val name: String,
    val sourceName: String,
    val reasons: List<String>,
)

/** 订阅源加载状态 */
data class MarketSourceStatus(
    val name: String,
    val ok: Boolean,
    val message: String,
)

/**
 * 把订阅源条目转成市场条目。未知能力字符串会被丢弃并记录在 rawCapabilityIds 里。
 *
 * ⚠️ [description] / [author] / [updatedAt] 必须透传。这三个字段曾经在
 *    [SubscriptionEntry] 里**根本不存在**，于是这里只能传 null —— 而市场界面
 *    一直在渲染它们。结果是用户面对一列只有名字和能力标签的插件，
 *    没有任何说明可读。界面与契约各说各话，谁都不会报错。
 */
fun SubscriptionEntry.toMarketEntry(sourceName: String): MarketEntry = MarketEntry(
    id = id,
    name = name,
    version = version,
    level = level,
    capabilities = capabilities.mapNotNull { PluginCapability.fromId(it.trim().lowercase()) },
    targetApps = targetApps,
    description = description,
    author = author,
    downloadUrl = downloadUrl,
    sha256 = sha256,
    signature = signature,
    updatedAt = updatedAt,
    sourceName = sourceName,
    rawCapabilityIds = capabilities,
)

// ═══════════════════════════════════════════════════════════════
//  语义化版本
// ═══════════════════════════════════════════════════════════════

/**
 * 语义化版本。
 *
 * ⚠️ 为什么不用 `String.compareTo`？因为 `"1.10.0" < "1.9.0"`（字典序），
 *    会导致装了 1.9.0 的用户永远收不到 1.10.0 的更新 —— 而且**没有任何报错**。
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }

        // 预发布版本低于同号正式版：1.0.0-beta < 1.0.0
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    /** 预发布标识符逐段比较：数字段按数值比，数字段低于字母段 */
    private fun comparePreRelease(a: String, b: String): Int {
        val asParts = a.split('.')
        val bsParts = b.split('.')
        for (i in 0 until maxOf(asParts.size, bsParts.size)) {
            val x = asParts.getOrNull(i) ?: return -1
            val y = bsParts.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val cmp = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        private val PATTERN = Regex(
            """^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.\-]+))?(?:\+[0-9A-Za-z.\-]+)?$"""
        )

        /** 解析失败返回 null —— 调用方必须处理，不能当成 0.0.0 */
        fun parse(raw: String): SemanticVersion? {
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            return SemanticVersion(
                major = m.groupValues[1].toIntOrNull() ?: return null,
                minor = m.groupValues[2].toIntOrNull() ?: return null,
                patch = m.groupValues[3].toIntOrNull() ?: return null,
                preRelease = m.groupValues[4].takeIf { it.isNotEmpty() },
            )
        }
    }
}
