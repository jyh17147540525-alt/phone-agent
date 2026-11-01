package com.pocketagent.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **结果校验器的判定规则**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件盯的是"判错"的代价
 * ═══════════════════════════════════════════════════════════════
 *
 * `VerifyResult` 有四个分支，判错的后果完全不对等：
 *
 * | 判成 | 后果 |
 * |---|---|
 * | `Success` 但其实没成功 | **最坏** —— 模型以为做到了，继续往下走，越走越偏 |
 * | `Failed` 但其实成功了 | 重试一次，代价可接受 |
 * | `Unknown` | 上层决定重规划或继续，安全 |
 * | `Unexpected` | 上层重新观察，安全 |
 *
 * ⇒ 所有"拿不准"的情形都必须落到后三个之一，**不能落到 Success**。
 */
class ScreenVerifierTest {

    private fun digest(
        pkg: String = "com.foo",
        activity: String? = "Main",
        texts: List<String> = emptyList(),
        nodes: Int = 10,
    ) = ScreenVerifier.ScreenDigest(
        packageName = pkg,
        activityName = activity,
        visibleTexts = texts,
        nodeCount = nodes,
    )

    // ═══════════════════════════════════════════════════════════
    //  ★ 最重要的一条：什么都没变不能判成功
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `屏幕完全没变判失败`() {
        // ══════════════════════════════════════════════════════
        //  ★ 点了没反应是最常见的失败
        // ══════════════════════════════════════════════════════
        // 把它判成成功等于告诉模型"你做到了"，然后它继续往下走。
        val d = digest(texts = listOf("设置", "搜索"))
        val r = ScreenVerifier.verify("点击搜索", d, d)
        assertTrue("没变化必须判失败：$r", r is VerifyResult.Failed)
    }

    @Test
    fun `只有节点数变化也算变了`() {
        // 文本没变但节点数变了 —— 例如列表加载出新条目
        val r = ScreenVerifier.verify(
            "加载更多",
            digest(nodes = 10),
            digest(nodes = 20),
        )
        assertTrue("节点数变了就不该判「没变化」：$r", r !is VerifyResult.Failed)
    }

    @Test
    fun `列表顺序变化不算页面变化`() {
        // ══════════════════════════════════════════════════════
        //  ★ 指纹必须对顺序不敏感
        // ══════════════════════════════════════════════════════
        // 列表重排不代表页面变了。若指纹顺序敏感，
        // 会把"没变化"误判成"变了"，于是 Resolver 拿到 Unknown，
        // 而不是明确的 Failed。
        val a = digest(texts = listOf("甲", "乙"))
        val b = digest(texts = listOf("乙", "甲"))
        assertEquals("顺序不同但内容相同，指纹应当一致", a.fingerprint(), b.fingerprint())
        assertTrue(ScreenVerifier.verify("随便", a, b) is VerifyResult.Failed)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 崩溃必须优先于一切
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `中文崩溃提示判 Unexpected`() {
        val r = ScreenVerifier.verify(
            "点击确定",
            digest(texts = listOf("设置")),
            digest(texts = listOf("设置", "很抱歉，设置已停止")),
        )
        assertTrue("崩溃不能被当成成功：$r", r is VerifyResult.Unexpected)
    }

    @Test
    fun `英文崩溃提示也认`() {
        // ⚠️ 系统语言是英文时弹的是 "has stopped"。
        //    只认中文会漏判 —— 而漏判的后果是判成 Success。
        val r = ScreenVerifier.verify(
            "tap",
            digest(texts = listOf("Settings")),
            digest(texts = listOf("Settings", "Settings has stopped")),
        )
        assertTrue(r is VerifyResult.Unexpected)
    }

    @Test
    fun `崩溃优先于关键词命中`() {
        // ══════════════════════════════════════════════════════
        //  ★ 顺序是刻意的
        // ══════════════════════════════════════════════════════
        // 崩溃对话框出现时页面内容**也变了**。若先判"关键词出现"，
        // 会把崩溃误判成成功。
        val r = ScreenVerifier.verify(
            "搜索框出现",
            digest(texts = listOf("设置")),
            digest(texts = listOf("设置", "搜索框", "应用无响应")),
        )
        assertTrue(
            "「搜索框」出现了，但同一屏还有「无响应」⇒ 必须判 Unexpected：$r",
            r is VerifyResult.Unexpected,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  跳应用
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `前台应用变了判 Unexpected`() {
        val r = ScreenVerifier.verify(
            "点击链接",
            digest(pkg = "com.browser"),
            digest(pkg = "com.shopping"),
        )
        assertTrue(r is VerifyResult.Unexpected)
    }

    @Test
    fun `包名为空时不判跳应用`() {
        // ⚠️ 某些场景拿不到包名（权限不足/过渡动画中）。
        //    拿不到 ≠ 跳了应用 —— 不能凭这个判异常。
        val r = ScreenVerifier.verify(
            "点击",
            digest(pkg = ""),
            digest(pkg = "com.foo", nodes = 20),
        )
        assertTrue("包名缺失不该判 Unexpected：$r", r !is VerifyResult.Unexpected)
    }

    // ═══════════════════════════════════════════════════════════
    //  关键词从无到有 / 从有到无
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `期望的文本出现了判成功`() {
        val r = ScreenVerifier.verify(
            "打开设置页",
            digest(texts = listOf("主屏")),
            digest(texts = listOf("设置", "无线网络")),
        )
        assertTrue("「设置」从无到有应当判成功：$r", r is VerifyResult.Success)
    }

    @Test
    fun `期望的文本消失了也判成功`() {
        // 期望可能是"关闭对话框" —— 东西消失同样说明生效
        // ⚠️ 期望必须**真的提到**那个文本。
        //    我最初写「关闭确认框」，但它没提到「取消」——
        //    那种情况本来就该是 Unknown，不是 Success。
        val r = ScreenVerifier.verify(
            "确认删除消失",
            digest(texts = listOf("确认删除", "取消")),
            digest(texts = listOf("列表")),
        )
        assertTrue("「确认删除」从有到无应当判成功：$r", r is VerifyResult.Success)
    }

    @Test
    fun `中文长句里的目标能被认出来`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是重写主逻辑的原因
        // ══════════════════════════════════════════════════════
        // 「打开设置页」**没有词边界** —— 切成词就是整串，
        // 永远匹配不上屏幕上的「设置」。
        // ⇒ 反过来在可见文本里找"被期望提到"的。
        val ks = ScreenVerifier.mentionedTexts("打开设置页", listOf("设置", "无线网络"))
        assertEquals(listOf("设置"), ks)
    }

    @Test
    fun `关键词大小写不敏感`() {
        val r = ScreenVerifier.verify(
            "open Settings",
            digest(texts = listOf("home")),
            digest(texts = listOf("settings")),
        )
        assertTrue(r is VerifyResult.Success)
    }

    // ═══════════════════════════════════════════════════════════
    //  抽不出关键词 ⇒ Unknown，不瞎猜
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `期望里没有可比对的关键词时判 Unknown`() {
        val r = ScreenVerifier.verify(
            "点击那个按钮",
            digest(texts = listOf("甲"), nodes = 10),
            digest(texts = listOf("乙"), nodes = 12),
        )
        assertTrue("拿不准就该说 Unknown，不能猜成功：$r", r is VerifyResult.Unknown)
    }

    @Test
    fun `空期望不产生关键词`() {
        val texts = listOf("设置", "搜索")
        assertTrue(ScreenVerifier.mentionedTexts("", texts).isEmpty())
        assertTrue(ScreenVerifier.mentionedTexts("   ", texts).isEmpty())
    }

    @Test
    fun `单字不当作关键词`() {
        // ⚠️ 单字在任何页面都出现，没有区分度。
        //    留它们会让判定随机命中，等于没有判定。
        val ks = ScreenVerifier.mentionedTexts("点 关 开", listOf("点", "关", "开"))
        assertTrue("单字应当被过滤掉：$ks", ks.isEmpty())
    }

    @Test
    fun `常见无意义词被过滤`() {
        val ks = ScreenVerifier.mentionedTexts(
            "点击 打开 设置",
            listOf("点击", "打开", "设置"),
        )
        assertEquals(listOf("设置"), ks)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 全景：不判错方向的底线
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `只有确实观察到变化才可能判成功`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是「不假装成功」的总闸
        // ══════════════════════════════════════════════════════
        val expectations = listOf("打开设置", "关闭弹窗", "点击那个", "搜索", "")
        for (e in expectations) {
            val same = digest(texts = listOf("甲", "乙"))
            val r = ScreenVerifier.verify(e, same, same)
            assertTrue(
                "屏幕完全没变时，期望「$e」不该判成功：$r",
                r !is VerifyResult.Success,
            )
        }
    }
}
