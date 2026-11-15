package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDigestTest {

    // ── 解析 ────────────────────────────────────────────────────────────

    @Test
    fun `维基摘要能解析出标题和正文`() {
        val json = """
            {"type":"summary","title":"海獭","extract":"海獭是鼬科动物，睡觉时会抓住同伴的手，免得被水冲走。"}
        """.trimIndent()
        val item = WebDigest.parseSummary(json)
        assertEquals("海獭", item?.title)
        assertTrue(item!!.extract.startsWith("海獭是鼬科动物"))
    }

    @Test
    fun `转义序列会被还原成一个空格而不是留下反斜杠`() {
        val json = """{"title":"标题","extract":"第一句在这里。\n第二句，\t带制表符，这句话要够长才能留下来。"}"""
        val item = WebDigest.parseSummary(json)
        assertTrue(item!!.extract.contains("第一句"))
        assertTrue(item.extract.contains("第二句"))
        assertTrue("不该留下反斜杠：${item.extract}", !item.extract.contains("\\"))
    }

    @Test
    fun `unicode 转义会被还原成汉字`() {
        val json = """{"title":"测试","extract":"\u96e8\u524d\u7684\u571f\u5473\u6765\u81ea\u653e\u7ebf\u83cc\u7684\u5b62\u5b50\uff0c\u8fd9\u53e5\u8bdd\u5f97\u591f\u957f\u624d\u80fd\u7559\u4e0b\u6765\u3002"}"""
        val item = WebDigest.parseSummary(json)
        assertTrue(item!!.extract.startsWith("雨前的土味"))
    }

    @Test
    fun `省略号前面不留标点`() {
        // ⚠️ 这条是**变异验证抓出来的空洞**：把 `trimEnd('。', …)` 去掉之后
        //    原来的测试仍然全绿 —— 因为它只查了"以省略号结尾"。
        //    真实表现是「…孢子。…」，念出来像结巴。
        // ⚠️ 正文必须**超过 150 字**才会真的截断。第一版写了 73 字，
        //    根本没走到截断分支，断言直接红在「应该被截断」上。
        val long = "雨前的土味来自土壤里放线菌的孢子。" +
            "它们平时待在土里，下雨前空气变潮，孢子就飘到空气里了。" +
            "这件事最早是两个澳大利亚的研究者发现的，他们当时在查别的东西。" +
            "同样的味道在冬天化雪之后也会出现，那时候土刚解冻。" +
            "所以闻到那股味道，其实是闻到了土里活过一场的证据。" +
            "孢子本身没有味道，味道是别的东西混在一起的结果。" +
            "他们后来把这件事写成了论文，发表在一个很小的期刊上。"
        val out = WebDigest.trimExtract(long)
        assertTrue("应该被截断：$out", out.endsWith("…"))
        val prev = out.dropLast(1).last()
        assertTrue("省略号前不该留标点：$out", !"。！？，、 ".contains(prev))
    }

    @Test
    fun `坏输入一律返回 null 而不是抛`() {
        assertNull(WebDigest.parseSummary(null))
        assertNull(WebDigest.parseSummary(""))
        assertNull(WebDigest.parseSummary("   "))
        assertNull(WebDigest.parseSummary("not json at all"))
        assertNull(WebDigest.parseSummary("""{"title":"只有标题"}"""))
    }

    @Test
    fun `消歧义页会被丢掉`() {
        val json = """{"title":"苹果（消歧义）","extract":"苹果可以指一种水果，也可以指一家公司，还可以指别的东西。"}"""
        assertNull(WebDigest.parseSummary(json))
    }

    @Test
    fun `太短的摘录会被丢掉`() {
        val json = """{"title":"某物","extract":"很短。"}"""
        assertNull(WebDigest.parseSummary(json))
    }

    @Test
    fun `太长的标题会被丢掉`() {
        val json = """{"title":"${"长".repeat(WebDigest.MAX_TITLE_CHARS + 1)}","extract":"这段正文本身是够长的，一共有二十四个字以上吧，嗯。"}"""
        assertNull(WebDigest.parseSummary(json))
    }

    @Test
    fun `一言能拼出出处`() {
        val json = """{"hitokoto":"夜里下过雨，早上路上还是湿的，走起来很安静，鞋底沾了一点泥。","from":"某本书","from_who":"某人"}"""
        val item = WebDigest.parseHitokoto(json)
        assertEquals("某人 的《某本书》", item?.title)
    }

    @Test
    fun `一言没有作者时只写书名`() {
        val json = """{"hitokoto":"夜里下过雨，早上路上还是湿的，走起来很安静，鞋底沾了一点泥。","from":"某本书"}"""
        assertEquals("《某本书》", WebDigest.parseHitokoto(json)?.title)
    }

    @Test
    fun `一言什么都没有时给一个不指名的出处`() {
        val json = """{"hitokoto":"夜里下过雨，早上路上还是湿的，走起来很安静，鞋底沾了一点泥。"}"""
        assertEquals("某个人写的一句话", WebDigest.parseHitokoto(json)?.title)
    }

    // ── 压缩 ────────────────────────────────────────────────────────────

    @Test
    fun `空白会被压成单个空格`() {
        val out = WebDigest.trimExtract("第一句。\n\n\t第二句。  第三句。")
        assertTrue(out, !out.contains("\n") && !out.contains("\t") && !out.contains("  "))
    }

    @Test
    fun `短正文原样返回`() {
        val raw = "海獭睡觉时会抓住同伴的手，免得被水冲走。"
        assertEquals(raw, WebDigest.trimExtract(raw))
    }

    @Test
    fun `超长正文截到上限并加省略号`() {
        val raw = (1..40).joinToString("") { "这是第${it}句话，用来把长度撑过上限。" }
        val out = WebDigest.trimExtract(raw)
        assertTrue("长度 ${out.length}", out.length <= WebDigest.MAX_EXTRACT_CHARS)
        assertTrue("该带省略号", out.endsWith("…"))
    }

    @Test
    fun `优先整句收尾而不是硬切在字中间`() {
        val raw = "第一句占十个字左右。第二句也是十个字上下。第三句同样长度的句子在这里结束。"
        val out = WebDigest.trimExtract(raw)
        assertTrue("不该以半个词结尾：$out", out.endsWith("。") || out.endsWith("…"))
    }

    // ── 选条与兜底 ──────────────────────────────────────────────────────

    @Test
    fun `兜底池按日期取且同一天稳定`() {
        val a = WebDigest.fallback(19634L)
        val b = WebDigest.fallback(19634L)
        assertEquals(a, b)
    }

    @Test
    fun `负数日期也不会越界`() {
        val item = WebDigest.fallback(-1L)
        assertTrue(item.title.isNotBlank())
    }

    @Test
    fun `有网上的就用网上的 没有就用兜底`() {
        val fetched = WebItem("标题", "这段正文够长了，一共二十四个字以上，可以拿来讲。")
        assertEquals(fetched, WebDigest.pick(1L, fetched))
        assertEquals(WebDigest.fallback(1L), WebDigest.pick(1L, null))
    }

    @Test
    fun `兜底池里的每一条都是能查的真事且长度合规`() {
        for (day in 0L until 12L) {
            val item = WebDigest.fallback(day)
            assertTrue(item.title.length <= WebDigest.MAX_TITLE_CHARS)
            assertTrue(item.extract.length in WebDigest.MIN_EXTRACT_CHARS..WebDigest.MAX_EXTRACT_CHARS)
        }
    }

    // ── 提示词 ──────────────────────────────────────────────────────────

    @Test
    fun `提示词说清这是她自己看到的`() {
        val section = WebDigest.promptSection(WebDigest.fallback(0L))
        assertTrue(section.contains("## 你在网上看到的"))
        assertTrue(section.contains("这是你自己翻到的"))
    }

    @Test
    fun `提示词不要求她汇报也不许念原文`() {
        val section = WebDigest.promptSection(WebDigest.fallback(0L))
        assertTrue(section.contains("他没问就不必说"))
        assertTrue(section.contains("别念原文"))
    }

    @Test
    fun `提示词把标题和正文都带上`() {
        val item = WebItem("海獭", "海獭睡觉时会抓住同伴的手，或者缠一段海藻，免得被水冲走。")
        val section = WebDigest.promptSection(item)
        assertTrue(section.contains("海獭"))
        assertTrue(section.contains("缠一段海藻"))
    }

    @Test
    fun `提示词过体检`() {
        for (day in 0L until 12L) {
            val report = PromptVoice.audit(WebDigest.promptSection(WebDigest.fallback(day)))
            assertEquals("第 $day 天有响的：${report.breakdown()}", 0, report.loud)
            assertEquals("第 $day 天有腔调：${report.breakdown()}", 0, report.tone)
        }
    }

    @Test
    fun `兜底池里的每一条都过体检`() {
        for (day in 0L until 12L) {
            val item = WebDigest.fallback(day)
            val report = PromptVoice.audit("${item.title}。${item.extract}")
            assertEquals(report.breakdown(), 0, report.loud)
        }
    }

    @Test
    fun `同一份 JSON 解析两次结果相同`() {
        val json = """{"title":"海獭","extract":"海獭睡觉时会抓住同伴的手，免得被水冲走。"}"""
        assertEquals(WebDigest.parseSummary(json), WebDigest.parseSummary(json))
    }

    @Test
    fun `相邻两天的兜底条目不总是同一条`() {
        val titles = (0L until 12L).map { WebDigest.fallback(it).title }.toSet()
        assertNotEquals(1, titles.size)
    }
}
