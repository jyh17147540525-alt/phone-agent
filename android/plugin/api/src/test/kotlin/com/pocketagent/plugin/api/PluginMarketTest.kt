package com.pocketagent.plugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件市场的纯逻辑测试：版本比较、检索、多源合并。
 *
 * 全部是纯函数，不发网络请求 —— 所以能在没有 Android SDK 的机器上完整跑。
 */
class PluginMarketTest {

    // ═══════════════════════════════════════════════════════════
    //  语义化版本
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `解析合法版本号`() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parse("1.2.3"))
        assertEquals(SemanticVersion(1, 0, 0, "beta.1"), SemanticVersion.parse("1.0.0-beta.1"))
        assertEquals(SemanticVersion(2, 3, 4, "rc.1"), SemanticVersion.parse("2.3.4-rc.1+build.7"))
        assertEquals(SemanticVersion(10, 20, 30), SemanticVersion.parse("  10.20.30  "))
    }

    @Test
    fun `解析非法版本号返回 null 而不是 0_0_0`() {
        // 返回 0.0.0 会让"版本比较"给出错误结论，而且悄无声息
        for (bad in listOf("1.0", "1.0.0.0", "v1.0.0", "abc", "", "1.a.0")) {
            assertNull("「$bad」不该被解析成功", SemanticVersion.parse(bad))
        }
    }

    /**
     * ★ 核心回归用例。
     *
     * 用 `String.compareTo` 比版本，"1.10.0" < "1.9.0"（字典序），
     * 结果是装了 1.9.0 的用户**永远收不到 1.10.0 的更新**，且没有任何报错。
     * 这是版本比较里最经典也最难发现的一个坑。
     */
    @Test
    fun `1_10_0 高于 1_9_0`() {
        val newer = SemanticVersion.parse("1.10.0")!!
        val older = SemanticVersion.parse("1.9.0")!!

        assertTrue("1.10.0 应高于 1.9.0", newer > older)
        assertTrue(older < newer)
    }

    @Test
    fun `逐段比较主次修订号`() {
        assertTrue(SemanticVersion.parse("2.0.0")!! > SemanticVersion.parse("1.99.99")!!)
        assertTrue(SemanticVersion.parse("1.2.0")!! > SemanticVersion.parse("1.1.99")!!)
        assertTrue(SemanticVersion.parse("1.0.2")!! > SemanticVersion.parse("1.0.1")!!)
        assertEquals(0, SemanticVersion.parse("1.0.0")!!.compareTo(SemanticVersion.parse("1.0.0")!!))
    }

    @Test
    fun `预发布版本低于同号正式版`() {
        val beta = SemanticVersion.parse("1.0.0-beta")!!
        val release = SemanticVersion.parse("1.0.0")!!

        assertTrue("1.0.0-beta 应低于 1.0.0", beta < release)
        assertTrue(release > beta)
    }

    @Test
    fun `预发布版本按数字段数值比较`() {
        // 字符串比较下 "beta.10" < "beta.9"，但按数值应该反过来
        val b10 = SemanticVersion.parse("1.0.0-beta.10")!!
        val b9 = SemanticVersion.parse("1.0.0-beta.9")!!

        assertTrue("beta.10 应高于 beta.9", b10 > b9)
    }

    @Test
    fun `数字标识符低于字母标识符`() {
        // semver 规范：数字标识符的优先级总是低于字母标识符
        val alpha = SemanticVersion.parse("1.0.0-alpha")!!
        val num = SemanticVersion.parse("1.0.0-1")!!

        assertTrue(alpha > num)
    }

    @Test
    fun `toString 还原成原格式`() {
        assertEquals("1.2.3", SemanticVersion(1, 2, 3).toString())
        assertEquals("1.2.3-beta.1", SemanticVersion(1, 2, 3, "beta.1").toString())
    }

    // ═══════════════════════════════════════════════════════════
    //  更新检测
    // ═══════════════════════════════════════════════════════════

    private fun entry(
        id: String = "community.example.demo",
        name: String = "示例插件",
        version: String = "1.0.0",
        level: PluginLevel = PluginLevel.L1_RULES,
        capabilities: List<PluginCapability> = listOf(PluginCapability.SCREEN_READ),
        description: String? = "示例描述",
        author: String? = "某作者",
        sourceName: String = "社区源",
    ) = MarketEntry(
        id = id,
        name = name,
        version = version,
        level = level,
        capabilities = capabilities,
        description = description,
        author = author,
        downloadUrl = "https://example.com/$id.pagent",
        sha256 = "a".repeat(64),
        sourceName = sourceName,
    )

    @Test
    fun `已安装旧版本时提示有更新`() {
        assertTrue(entry(version = "1.2.0").hasUpdate("1.1.9"))
    }

    @Test
    fun `未安装时不提示更新`() {
        // 未安装是"可安装"，不是"可更新" —— 界面上是两种不同的操作
        assertFalse(entry(version = "1.0.0").hasUpdate(null))
    }

    @Test
    fun `版本相同或更旧时不提示更新`() {
        assertFalse(entry(version = "1.0.0").hasUpdate("1.0.0"))
        assertFalse(entry(version = "1.0.0").hasUpdate("1.1.0"))
    }

    @Test
    fun `已安装版本号非法时不提示更新`() {
        // 宁可漏提示，也不能因为解析失败把 1.10 当成比 1.9 旧
        assertFalse(entry(version = "2.0.0").hasUpdate("不是版本号"))
    }

    // ═══════════════════════════════════════════════════════════
    //  检索 —— 关键词
    // ═══════════════════════════════════════════════════════════

    private val sample = listOf(
        entry(id = "community.wechat.auto-reply", name = "微信自动回复", description = "收到消息自动回复"),
        entry(id = "community.wechat.red-packet", name = "微信红包助手", description = "自动拆红包"),
        entry(id = "community.alipay.cleaner", name = "支付宝清理", description = "清理缓存"),
        entry(id = "community.timer.night", name = "夜间定时任务", description = "睡觉时自动执行"),
        entry(id = "community.wechat.helper", name = "消息助手", author = "微信生态组", description = "通用的消息处理"),
    )

    private fun search(
        keyword: String = "",
        levels: Set<PluginLevel> = emptySet(),
        capabilities: Set<PluginCapability> = emptySet(),
        excludeHighRisk: Boolean = false,
        sort: MarketQuery.Sort = MarketQuery.Sort.RELEVANCE,
    ) = MarketSearch.search(
        sample,
        MarketQuery(keyword, levels, capabilities, excludeHighRisk, sort),
    )

    @Test
    fun `空关键词返回全部`() {
        assertEquals(sample.size, search().size)
    }

    @Test
    fun `按名称匹配`() {
        val result = search("红包")

        assertEquals(1, result.size)
        assertEquals("微信红包助手", result.single().name)
    }

    @Test
    fun `按描述匹配`() {
        val result = search("缓存")

        assertEquals(1, result.size)
        assertEquals("支付宝清理", result.single().name)
    }

    @Test
    fun `按作者匹配`() {
        val result = search("微信生态组")

        assertEquals(1, result.size)
        assertEquals("消息助手", result.single().name)
    }

    @Test
    fun `按 ID 匹配`() {
        val result = search("timer")

        assertEquals(1, result.size)
        assertEquals("夜间定时任务", result.single().name)
    }

    @Test
    fun `英文关键词忽略大小写`() {
        assertEquals(1, search("TIMER").size)
        assertEquals(1, search("Timer").size)
    }

    @Test
    fun `名称命中的排在描述命中之前`() {
        // 用户搜「微信」是想找名字里带微信的，不是作者名里恰好有微信的。
        //
        // ⚠️ 注意别把这条测试写成断言具体第一名是谁。「微信自动回复」和
        //    「微信红包助手」都以关键词开头，相关度**完全并列**，谁在前由
        //    名称兜底排序决定 —— 那是任意的（中文按 Unicode 码位排，不是拼音）。
        //    断言并列项的顺序，等于在测试一个没有设计含义的实现细节。
        //    要测的是「名称命中 整体高于 作者/描述命中」这件事。
        val result = search("微信")

        assertEquals("应命中三个：两个名称命中 + 一个作者名命中", 3, result.size)

        // 前两个都是名称命中，内部顺序不作断言
        assertEquals(
            "名称命中的应是这两个（顺序不限）",
            setOf("微信自动回复", "微信红包助手"),
            result.take(2).map { it.name }.toSet(),
        )

        // 「消息助手」只靠作者名「微信生态组」命中，必须排在名称命中之后
        assertEquals("消息助手", result.last().name)
    }

    @Test
    fun `搜索无结果时返回空列表`() {
        assertTrue(search("这个词肯定不存在zzz").isEmpty())
    }

    @Test
    fun `关键词首尾空白被忽略`() {
        assertEquals(search("红包").size, search("  红包  ").size)
    }

    // ═══════════════════════════════════════════════════════════
    //  检索 —— 筛选
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `按级别筛选`() {
        val mixed = listOf(
            entry(id = "a.b.l1", name = "L1", level = PluginLevel.L1_RULES),
            entry(id = "a.b.l2", name = "L2", level = PluginLevel.L2_SCRIPT),
        )

        val l1 = MarketSearch.search(mixed, MarketQuery(levels = setOf(PluginLevel.L1_RULES)))
        assertEquals(1, l1.size)
        assertEquals("L1", l1.single().name)
    }

    @Test
    fun `按能力筛选要求全部具备`() {
        val mixed = listOf(
            entry(id = "a.b.one", name = "只有读", capabilities = listOf(PluginCapability.SCREEN_READ)),
            entry(
                id = "a.b.two", name = "读加点击",
                capabilities = listOf(PluginCapability.SCREEN_READ, PluginCapability.ACTION_CLICK),
            ),
        )

        val result = MarketSearch.search(
            mixed,
            MarketQuery(requiredCapabilities = setOf(PluginCapability.SCREEN_READ, PluginCapability.ACTION_CLICK)),
        )

        assertEquals(1, result.size)
        assertEquals("读加点击", result.single().name)
    }

    @Test
    fun `可以过滤掉高风险插件`() {
        val mixed = listOf(
            entry(id = "a.b.safe", name = "安全", capabilities = listOf(PluginCapability.APP_LAUNCH)),
            entry(id = "a.b.risky", name = "危险", capabilities = listOf(PluginCapability.SCREEN_CAPTURE)),
        )

        val result = MarketSearch.search(mixed, MarketQuery(excludeHighRisk = true))

        assertEquals(1, result.size)
        assertEquals("安全", result.single().name)
    }

    @Test
    fun `高风险判定覆盖高与极高两档`() {
        assertTrue(entry(capabilities = listOf(PluginCapability.SCREEN_CAPTURE)).isHighRisk)   // CRITICAL
        assertTrue(entry(capabilities = listOf(PluginCapability.SCREEN_READ)).isHighRisk)      // HIGH
        assertFalse(entry(capabilities = listOf(PluginCapability.APP_LAUNCH)).isHighRisk)      // LOW
    }

    // ═══════════════════════════════════════════════════════════
    //  检索 —— 排序
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `按名称排序`() {
        val result = search(sort = MarketQuery.Sort.NAME)
        val names = result.map { it.name }

        assertEquals(names.sorted(), names)
    }

    @Test
    fun `按风险从低到高排序`() {
        val mixed = listOf(
            entry(id = "a.b.c", name = "极高", capabilities = listOf(PluginCapability.SCREEN_CAPTURE)),
            entry(id = "a.b.a", name = "低", capabilities = listOf(PluginCapability.APP_LAUNCH)),
            entry(id = "a.b.b", name = "中", capabilities = listOf(PluginCapability.ACTION_GESTURE)),
        )

        val result = MarketSearch.search(mixed, MarketQuery(sort = MarketQuery.Sort.RISK))

        assertEquals(listOf("低", "中", "极高"), result.map { it.name })
    }

    @Test
    fun `按更新时间倒序排序`() {
        val mixed = listOf(
            entry(id = "a.b.old", name = "旧").copy(updatedAt = "2026-01-01"),
            entry(id = "a.b.new", name = "新").copy(updatedAt = "2026-09-01"),
        )

        val result = MarketSearch.search(mixed, MarketQuery(sort = MarketQuery.Sort.UPDATED))

        assertEquals(listOf("新", "旧"), result.map { it.name })
    }

    // ═══════════════════════════════════════════════════════════
    //  多源合并
    // ═══════════════════════════════════════════════════════════

    private fun source(name: String, vararg plugins: SubscriptionEntry) =
        SubscriptionSource(name = name, plugins = plugins.toList())

    private fun subEntry(
        id: String,
        name: String = "插件",
        version: String = "1.0.0",
        capabilities: List<String> = listOf("screen.read"),
        level: PluginLevel = PluginLevel.L1_RULES,
    ) = SubscriptionEntry(
        id = id,
        name = name,
        version = version,
        level = level,
        capabilities = capabilities,
        downloadUrl = "https://example.com/$id.pagent",
        sha256 = "a".repeat(64),
    )

    @Test
    fun `合并多个源`() {
        val catalog = MarketCatalog.merge(
            listOf(
                "源A" to source("源A", subEntry("a.b.one", "一号"), subEntry("a.b.two", "二号")),
                "源B" to source("源B", subEntry("c.d.three", "三号")),
            )
        )

        assertEquals(3, catalog.entries.size)
        assertEquals(2, catalog.sources.size)
        assertTrue(catalog.sources.all { it.ok })
    }

    @Test
    fun `同 ID 保留版本更高的`() {
        val catalog = MarketCatalog.merge(
            listOf(
                "源A" to source("源A", subEntry("a.b.one", "旧", version = "1.0.0")),
                "源B" to source("源B", subEntry("a.b.one", "新", version = "2.0.0")),
            )
        )

        assertEquals(1, catalog.entries.size)
        assertEquals("2.0.0", catalog.entries.single().version)
    }

    @Test
    fun `版本相同时保留先出现的源`() {
        // 让用户能通过调整订阅顺序来表达偏好
        val catalog = MarketCatalog.merge(
            listOf(
                "源A" to source("源A", subEntry("a.b.one", "先出现")),
                "源B" to source("源B", subEntry("a.b.one", "后出现")),
            )
        )

        assertEquals("先出现", catalog.entries.single().name)
        assertEquals("源A", catalog.entries.single().sourceName)
    }

    @Test
    fun `源加载失败被记录而不是静默忽略`() {
        val catalog = MarketCatalog.merge(
            listOf(
                "源A" to source("源A", subEntry("a.b.one")),
                "坏源" to null,
            )
        )

        assertEquals(1, catalog.entries.size)
        assertEquals(2, catalog.sources.size)
        val bad = catalog.sources.single { !it.ok }
        assertEquals("坏源", bad.name)
        assertTrue(bad.message.isNotBlank())
    }

    @Test
    fun `申请禁止能力的市场条目被拒绝并上报`() {
        // ★ 安全用例。
        // 过滤要留痕：悄悄丢弃会让用户以为这个源是干净的。
        val catalog = MarketCatalog.merge(
            listOf(
                "坏源" to source(
                    "坏源",
                    subEntry("a.b.evil", "恶意插件", capabilities = listOf("payment.pay")),
                    subEntry("a.b.good", "正常插件"),
                )
            )
        )

        assertEquals("恶意插件不该出现在可安装列表里", 1, catalog.entries.size)
        assertEquals("正常插件", catalog.entries.single().name)

        assertTrue("必须上报被拒条目", catalog.hasRejected)
        val rejected = catalog.rejected.single()
        assertEquals("a.b.evil", rejected.id)
        assertEquals("坏源", rejected.sourceName)
        assertTrue("应说明原因：${rejected.reasons}", rejected.reasons.first().contains("永不开放"))
    }

    @Test
    fun `未知能力字符串被丢弃但保留原始记录`() {
        // 条目本身不拦（可能是本体版本旧），但能力要丢掉 —— 不能给一个我们不认识的能力授权
        val catalog = MarketCatalog.merge(
            listOf(
                "源A" to source(
                    "源A",
                    subEntry("a.b.one", capabilities = listOf("screen.read", "future.telepathy")),
                )
            )
        )

        val e = catalog.entries.single()
        assertEquals(listOf(PluginCapability.SCREEN_READ), e.capabilities)
        assertTrue("应保留原始字符串用于排错", e.rawCapabilityIds.contains("future.telepathy"))

        // 界面要能据此提示"它需要更新的本体"，所以必须能问出"哪些能力用不了"
        assertEquals(listOf("future.telepathy"), e.unsupportedCapabilityIds)
    }

    /**
     * ★ 核心回归用例。
     *
     * 曾经把「未知能力」判成 ERROR，后果是：一个用了新版本能力的插件，
     * 整个条目被 [MarketCatalog.merge] 当成恶意插件丢掉 —— 用户连"市场里
     * 有这么个插件"都看不到，还以为是市场没收录。
     *
     * 判据回到 ERROR 的定义：「这个字段能不能被恶意利用」。我们**没有实现**的
     * 能力利用不了（PluginHost 直接不认），所以这是兼容性问题，不是安全问题。
     */
    @Test
    fun `未知能力只警告不拒绝整个条目`() {
        val issues = PluginValidator.inspectRawCapabilities(
            listOf("screen.read", "future.telepathy")
        )

        assertEquals("只应产生一条 issue", 1, issues.size)
        assertEquals(IssueSeverity.WARNING, issues.single().severity)
        assertFalse(
            "未知能力不能被判成 ERROR",
            issues.any { it.severity == IssueSeverity.ERROR },
        )
    }

    /** 而禁止前缀依然必须是 ERROR —— 别在修上一个 bug 的时候把这个放跑了 */
    @Test
    fun `禁止前缀依然判 ERROR`() {
        for (bad in listOf("payment.pay", "key.read", "crypto.decrypt", "system.shell")) {
            val issues = PluginValidator.inspectRawCapabilities(listOf(bad))
            assertTrue(
                "「$bad」应判 ERROR",
                issues.any { it.severity == IssueSeverity.ERROR },
            )
        }
    }

    @Test
    fun `条目带有来源信息`() {
        val catalog = MarketCatalog.merge(
            listOf("社区源" to source("社区源", subEntry("a.b.one")))
        )

        assertEquals("社区源", catalog.entries.single().sourceName)
    }

    @Test
    fun `空源列表产生空目录而不是崩溃`() {
        val catalog = MarketCatalog.merge(emptyList())

        assertTrue(catalog.entries.isEmpty())
        assertFalse(catalog.hasRejected)
    }

    @Test
    fun `未签名的市场条目信任等级为未签名`() {
        val catalog = MarketCatalog.merge(listOf("源A" to source("源A", subEntry("a.b.one"))))

        assertEquals(PluginTrustLevel.MARKET_UNSIGNED, catalog.entries.single().trustLevel)
    }

    @Test
    fun `带签名的市场条目信任等级为已签名`() {
        val signed = subEntry("a.b.one").copy(signature = "sig")
        val catalog = MarketCatalog.merge(listOf("源A" to source("源A", signed)))

        assertEquals(PluginTrustLevel.VERIFIED_SIGNATURE, catalog.entries.single().trustLevel)
    }

    @Test
    fun `合并结果按名称排序`() {
        val catalog = MarketCatalog.merge(
            listOf(
                "源A" to source(
                    "源A",
                    subEntry("a.b.z", "Z 插件"),
                    subEntry("a.b.a", "A 插件"),
                    subEntry("a.b.m", "M 插件"),
                )
            )
        )

        assertEquals(listOf("A 插件", "M 插件", "Z 插件"), catalog.entries.map { it.name })
    }

    @Test
    fun `无签名条目的信任等级不是已验证`() {
        assertNotNull(entry().trustLevel)
        assertEquals(PluginTrustLevel.MARKET_UNSIGNED, entry().trustLevel)
    }
}
