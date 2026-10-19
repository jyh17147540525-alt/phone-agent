package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@` 面板的排序 / 导出格式 / 工具投影。
 *
 * ★ 排序那几条守的是"列表别乱跳"：同一个输入必须永远给出同一个顺序。
 *   一旦掺进时间这类会漂的量，用户看到的是"这东西不听话"，
 *   而没人会为此提一个 issue。
 */
class MentionIndexTest {

    private val items = listOf(
        MentionIndex.Item("android_tap", "android_tap", MentionIndex.Group.CAPABILITY, lastUsedAt = 300L),
        MentionIndex.Item("android_swipe", "android_swipe", MentionIndex.Group.CAPABILITY, lastUsedAt = 100L),
        MentionIndex.Item("screen_read", "screen_read", MentionIndex.Group.CAPABILITY, lastUsedAt = null),
        MentionIndex.Item("wx", "微信", MentionIndex.Group.APP, lastUsedAt = 50L, pinyin = "wx"),
        MentionIndex.Item("alipay", "支付宝", MentionIndex.Group.APP, lastUsedAt = null, pinyin = "zfb"),
        MentionIndex.Item("mem-1", "不吃香菜", MentionIndex.Group.MEMORY, lastUsedAt = 900L),
        MentionIndex.Item("mem-2", "用 Kotlin", MentionIndex.Group.MEMORY, lastUsedAt = 950L),
    )

    // ── 匹配强度 ───────────────────────────────────────────────────

    @Test
    fun `完全等于排第一`() {
        val hits = MentionIndex.search("微信", items)
        assertEquals(MentionIndex.Match.EXACT, hits.first().match)
    }

    @Test
    fun `前缀命中排在包含之前`() {
        val hits = MentionIndex.search("android", items)
        assertTrue(hits.size >= 2)
        assertTrue(hits.all { it.match == MentionIndex.Match.PREFIX })
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(
            MentionIndex.search("SCREEN_READ", items).map { it.item.key },
            MentionIndex.search("screen_read", items).map { it.item.key },
        )
    }

    @Test
    fun `拼音首字母能命中（标签里并没有那串字母）`() {
        val hits = MentionIndex.search("wx", items)
        assertEquals("wx", hits.single().item.key)
        assertEquals(MentionIndex.Match.PINYIN, hits.single().match)
    }

    @Test
    fun `拼音是多字母缩写时也能命中`() {
        val hits = MentionIndex.search("zfb", items)
        assertEquals("alipay", hits.single().item.key)
        assertEquals(MentionIndex.Match.PINYIN, hits.single().match)
    }

    @Test
    fun `匹配强度压过「最近用过」`() {
        // 直接命中但很久没用 vs 拼音命中但刚用过 —— 前者必须排前面。
        // 反之用户输入完整名字却被一条"更近的巧合"顶掉，会觉得列表在跟他作对。
        val withDecoy = items + MentionIndex.Item(
            "wx-literal",
            "wx",
            MentionIndex.Group.CAPABILITY,
            lastUsedAt = 10_000L,
        )
        val hits = MentionIndex.search("wx", withDecoy)
        assertEquals(MentionIndex.Match.EXACT, hits.first().match)
        assertEquals("wx-literal", hits.first().item.key)
        assertEquals(MentionIndex.Match.PINYIN, hits[1].match)
    }

    @Test
    fun `不匹配的项不出现`() {
        assertTrue(MentionIndex.search("zzzz", items).isEmpty())
    }

    // ── 排序确定性 ─────────────────────────────────────────────────

    @Test
    fun `最近使用排在前面（同强度下）`() {
        val hits = MentionIndex.search("android_", items)
        assertEquals("android_tap", hits.first().item.key)
    }

    @Test
    fun `从未用过的排在最后（同强度下）`() {
        val hits = MentionIndex.search("_", items)
        val last = hits.last()
        assertNull(last.item.lastUsedAt)
    }

    @Test
    fun `同一输入两次检索结果相同`() {
        assertEquals(
            MentionIndex.search("a", items).map { it.item.key },
            MentionIndex.search("a", items).map { it.item.key },
        )
    }

    @Test
    fun `检索不修改输入列表`() {
        val before = items.toList()
        MentionIndex.search("a", items)
        assertEquals(before, items)
    }

    @Test
    fun `完全同分的两项按 key 兜底排序`() {
        val twins = listOf(
            MentionIndex.Item("b", "同名", MentionIndex.Group.CAPABILITY, 5L),
            MentionIndex.Item("a", "同名", MentionIndex.Group.CAPABILITY, 5L),
        )
        assertEquals(listOf("a", "b"), MentionIndex.search("同名", twins).map { it.item.key })
    }

    @Test
    fun `limit 生效`() {
        assertEquals(2, MentionIndex.search("a", items, limit = 2).size)
    }

    @Test
    fun `limit 必须为正`() {
        try {
            MentionIndex.search("a", items, limit = 0)
            throw AssertionError("应当拒绝 limit=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `空查询列出全部`() {
        assertEquals(items.size, MentionIndex.search("", items, limit = 100).size)
    }

    @Test
    fun `查询两端空白被忽略`() {
        assertEquals(
            MentionIndex.search("微信", items).map { it.item.key },
            MentionIndex.search("  微信  ", items).map { it.item.key },
        )
    }

    // ── 分组 ───────────────────────────────────────────────────────

    @Test
    fun `分组按固定顺序输出`() {
        val grouped = MentionIndex.grouped("", items)
        assertEquals(
            listOf(
                MentionIndex.Group.CAPABILITY,
                MentionIndex.Group.MEMORY,
                MentionIndex.Group.APP,
            ),
            grouped.keys.toList(),
        )
    }

    @Test
    fun `记忆组按时间倒序而不是相似度`() {
        val grouped = MentionIndex.grouped("", items)
        assertEquals(listOf("mem-2", "mem-1"), grouped.getValue(MentionIndex.Group.MEMORY).map { it.key })
    }

    @Test
    fun `空分组不出现在结果里`() {
        val onlyCaps = items.filter { it.group == MentionIndex.Group.CAPABILITY }
        val grouped = MentionIndex.grouped("", onlyCaps)
        assertFalse(MentionIndex.Group.MEMORY in grouped)
    }

    @Test
    fun `每组最多 perGroup 条`() {
        val grouped = MentionIndex.grouped("", items, perGroup = 1)
        assertTrue(grouped.values.all { it.size <= 1 })
    }

    @Test
    fun `perGroup 必须为正`() {
        try {
            MentionIndex.grouped("", items, perGroup = 0)
            throw AssertionError("应当拒绝 perGroup=0")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `item 的 key 不能为空`() {
        try {
            MentionIndex.Item("", "标签", MentionIndex.Group.APP)
            throw AssertionError("应当拒绝空 key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `item 的 label 不能为空`() {
        try {
            MentionIndex.Item("k", "", MentionIndex.Group.APP)
            throw AssertionError("应当拒绝空 label")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // ── 拼音端口 ───────────────────────────────────────────────────

    @Test
    fun `默认拼音端口只保留拉丁与数字`() {
        assertEquals("screenread1", PinyinPort.ASCII_ONLY.initials("screen_read 1 微信"))
    }

    @Test
    fun `默认拼音端口对纯汉字返回空串（不匹配而不是错匹配）`() {
        assertEquals("", PinyinPort.ASCII_ONLY.initials("微信"))
    }

    // ── TimelineExport ─────────────────────────────────────────────

    private fun sample(): Timeline = listOf(
        CardEvent.IntentProposed(
            at = 1L,
            id = CardId("i-1"),
            action = "给妈妈发一条消息",
            target = "微信",
            risk = RiskLevel.EXTERNAL,
            reversible = false,
            evidence = listOf(Evidence(EvidenceKind.USER_INSTRUCTION, "给妈发个消息")),
        ),
        CardEvent.IntentConfirmed(2L),
        CardEvent.ToolStarted(3L, CardId("a-1"), "android_tap", "点「发送」", channel = "ACCESSIBILITY"),
        CardEvent.ToolSucceeded(4L, CardId("a-1"), "已派发，通道=ACCESSIBILITY", 72L),
        CardEvent.Observed(5L, CardId("o-1"), "微信：已发送（20 个节点）", 20),
        CardEvent.AssistantDelta(6L, "已发送。"),
        CardEvent.AssistantDone(7L),
    ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }

    @Test
    fun `Markdown 导出带标题`() {
        assertTrue(TimelineExport.toMarkdown(sample(), "测试复盘").startsWith("# 测试复盘"))
    }

    @Test
    fun `Markdown 导出每条卡一行`() {
        val md = TimelineExport.toMarkdown(sample())
        val numbered = md.lines().count { it.matches(Regex("^\\d+\\. .*")) }
        // sample 里有 4 张卡：预演、动作、观察、说话。
        // 确认/成功/说完这三个事件只是**收尾**，不该各占一行。
        assertEquals(4, numbered)
    }

    @Test
    fun `Markdown 导出含「依据」`() {
        assertTrue(TimelineExport.toMarkdown(sample()).contains("依据："))
    }

    @Test
    fun `Markdown 导出把用户原话标成「你说」`() {
        assertTrue(TimelineExport.toMarkdown(sample()).contains("你说「给妈发个消息」"))
    }

    @Test
    fun `模型声称在导出里被标成未经核对`() {
        val t = listOf(
            CardEvent.IntentProposed(
                at = 1L,
                id = CardId("i-1"),
                action = "点发送",
                target = "微信",
                risk = RiskLevel.EXTERNAL,
                reversible = false,
                evidence = listOf(Evidence(EvidenceKind.MODEL_CLAIM, "按钮在右下角")),
            ),
        ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val md = TimelineExport.toMarkdown(t)
        assertTrue(md.contains("未经核对"))
    }

    @Test
    fun `不可撤销在导出里写明`() {
        assertTrue(TimelineExport.toMarkdown(sample()).contains("不可撤销"))
    }

    @Test
    fun `暂停原因出现在导出里`() {
        val t = (sample().reduce(CardEvent.TakeOver(100L)) as Reduction.Applied).timeline
        assertTrue(TimelineExport.toMarkdown(t).contains("你接管了"))
    }

    /**
     * ⚠️ 取回放正文必须**先丢掉结尾空串**。
     *
     * Kotlin 的 `String.lines()` 在串以 `\n` 结尾时会多给一个空元素，
     * `.last()` 于是拿到空串 —— 断言"包含某内容"会永远失败，
     * 而失败信息只说 `expected true`，完全看不出是取到了空行。
     */
    private fun replayBody(timeline: Timeline): List<String> =
        TimelineExport.toReplay(timeline).lines().drop(4).filter { it.isNotBlank() }

    @Test
    fun `回放格式头部四行固定`() {
        val lines = TimelineExport.toReplay(sample()).lines()
        assertTrue(lines[0].startsWith("v="))
        assertTrue(lines[1].startsWith("pause="))
        assertTrue(lines[2].startsWith("saySeq="))
        assertTrue(lines[3].startsWith("troubleSeq="))
    }

    @Test
    fun `回放格式带版本号`() {
        assertTrue(
            TimelineExport.toReplay(sample()).startsWith("v=${TimelineExport.REPLAY_VERSION}"),
        )
    }

    @Test
    fun `回放格式每条卡一行`() {
        assertEquals(4, replayBody(sample()).size)
    }

    @Test
    fun `回放格式字段数固定`() {
        assertTrue(replayBody(sample()).all { it.split("|").size >= 5 })
    }

    /**
     * ★ 这一条是整个导出设计里最要紧的断言（**按词禁，不按字禁**）。
     *
     * 回放串一旦混进界面措辞，"把「进行中」改成「执行中」"就等于让所有
     * 历史回放失效 —— 而且是**静默**失效（解析方通常跳过不认识的行）。
     *
     * ⚠️ 但注意别写成"一个汉字都没有"：用户原话、工具返回原文必须逐字保留，
     *    洗掉它们复盘就成了编故事。禁的是 [TimelineExport.UI_WORDS] 这一小撮
     *    我们自己造的措辞。
     */
    @Test
    fun `回放格式不含任何界面措辞`() {
        val replay = TimelineExport.toReplay(sample())
        val leaked = TimelineExport.UI_WORDS.filter { replay.contains(it) }
        assertEquals("回放串里混进了界面措辞：$leaked", emptyList<String>(), leaked)
    }

    @Test
    fun `回放格式仍然逐字保留用户原话`() {
        // 与上一条成对：禁措辞，但绝不能顺手把事实也洗掉。
        val replay = TimelineExport.toReplay(sample())
        assertTrue(replay.contains("给妈发个消息"))
    }

    /**
     * ⚠️ 判"原文没被洗掉"必须**经解析器**比对。
     *
     * 直接在回放串里搜原话会假失败：工具返回里那个 `=` 会被正当转义成 `\=`，
     * 于是 `contains("通道=ACCESSIBILITY")` 找不到 —— 而实际内容一个字没少。
     */
    @Test
    fun `回放格式仍然逐字保留工具返回原文`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(sample()))
        assertEquals("已派发，通道=ACCESSIBILITY", parsed.lines[1].field("detail"))
    }

    @Test
    fun `回放格式带上了依据（否则复盘只剩动作、没有理由）`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(sample()))
        assertEquals(listOf("USER_INSTRUCTION:给妈发个消息"), parsed.lines[0].evidence())
    }

    @Test
    fun `回放格式保留模型声称这类依据及其类型`() {
        val t = listOf(
            CardEvent.IntentProposed(
                at = 1L,
                id = CardId("i-1"),
                action = "点发送",
                target = "微信",
                risk = RiskLevel.EXTERNAL,
                reversible = false,
                evidence = listOf(Evidence(EvidenceKind.MODEL_CLAIM, "按钮在右下角")),
            ),
        ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(t))
        assertEquals(listOf("MODEL_CLAIM:按钮在右下角"), parsed.lines.single().evidence())
    }

    @Test
    fun `没有依据的卡不凭空长出依据`() {
        val t = listOf(CardEvent.Observed(1L, CardId("o-1"), "微信主界面", 3))
            .fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        assertTrue(TimelineExport.parseReplay(TimelineExport.toReplay(t)).lines.single().evidence().isEmpty())
    }

    @Test
    fun `回放格式用枚举名而不是界面文案`() {
        val replay = TimelineExport.toReplay(sample())
        assertTrue(replay.contains("DONE"))
        assertTrue(replay.contains("EXTERNAL"))
        assertFalse(replay.contains("·完成"))
        assertFalse(replay.contains("·进行中"))
    }

    @Test
    fun `回放格式的键名全是 ASCII`() {
        replayBody(sample()).forEach { body ->
            body.substringAfterLast('|').split(";").forEach { field ->
                val key = field.substringBefore('=')
                assertTrue("键名不是 ASCII：$key", key.all { it.code < 128 })
            }
        }
    }

    @Test
    fun `回放格式转义竖线`() {
        val t = listOf(
            CardEvent.ToolStarted(1L, CardId("a-1"), "android_tap", "点|发送"),
            CardEvent.ToolSucceeded(2L, CardId("a-1"), "ok", 1L),
        ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val body = replayBody(t).single()
        assertTrue("竖线没被转义：$body", body.contains("\\|"))
        // ⚠️ 别用裸 split("|") 数分隔符 —— `\|` 里的那个竖线照样会被切开，
        //    于是"转义成功"反而把计数撑大。真正的判据是解析回来还是原话。
        assertEquals("点|发送", TimelineExport.parseReplay(TimelineExport.toReplay(t))
            .lines.single().field("sum"))
    }

    @Test
    fun `回放格式转义分号与等号`() {
        val t = listOf(CardEvent.AssistantDelta(1L, "a=b;c"))
            .fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val body = replayBody(t).single()
        assertTrue("分号没被转义：$body", body.contains("\\;"))
        assertTrue("等号没被转义：$body", body.contains("\\="))
        assertEquals("a=b;c", TimelineExport.parseReplay(TimelineExport.toReplay(t))
            .lines.single().field("text"))
    }

    @Test
    fun `回放格式转义换行`() {
        val t = listOf(CardEvent.AssistantDelta(1L, "第一行\n第二行"))
            .fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val body = replayBody(t).single()
        assertTrue("换行没被转义：$body", body.contains("\\n"))
    }

    @Test
    fun `回放格式把回车也压成一行`() {
        val t = listOf(CardEvent.AssistantDelta(1L, "第一行\r\n第二行"))
            .fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        assertEquals(1, replayBody(t).size)
    }

    @Test
    fun `回放格式转义反斜杠`() {
        val t = listOf(CardEvent.AssistantDelta(1L, "C:\\Users"))
            .fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val body = replayBody(t).single()
        assertTrue("反斜杠没被转义：$body", body.contains("C:\\\\Users"))
    }

    /**
     * 替代出口的**标签是用户可见文案**，可能含中文 —— 它进回放是被刻意允许的，
     * 因为它承载"当时还能选什么"这个事实。丢了它，复盘会得出
     * "当时只能重试"这种错误结论。
     */
    @Test
    fun `回放格式保留替代出口`() {
        val t = permissionFailure()
        val replay = TimelineExport.toReplay(t)
        assertTrue(replay.contains("TAKEOVER"))
        assertTrue(replay.contains("ALT:"))
        // 用户拒绝没有"重试"这条出口 —— 回放里也不该凭空长出一条。
        assertFalse(replay.contains("RETRY"))
    }

    private fun permissionFailure(): Timeline = listOf(
        CardEvent.ToolStarted(1L, CardId("a-1"), "android_tap", "点发送"),
        CardEvent.ToolFailed(
            2L,
            CardId("a-1"),
            Gate.PERMISSION,
            "你刚才没允许",
            FailurePlan.exitsFor(Gate.PERMISSION),
        ),
    ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }

    @Test
    fun `空时间线导出也不崩`() {
        assertEquals("", TimelineExport.toMarkdown(Timeline()).lines().drop(2).joinToString("").trim())
    }

    @Test
    fun `每次导出结果相同（无时间戳漂移）`() {
        val t = sample()
        assertEquals(TimelineExport.toMarkdown(t), TimelineExport.toMarkdown(t))
        assertEquals(TimelineExport.toReplay(t), TimelineExport.toReplay(t))
    }

    // ── 回放解析（有编码就必须有解码）────────────────────────────

    @Test
    fun `回放往返后卡片数量不变`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(sample()))
        assertEquals(4, parsed.lines.size)
    }

    @Test
    fun `回放往返后 id 与状态一致`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(sample()))
        assertEquals(listOf("i-1", "a-1", "o-1", "say-1"), parsed.lines.map { it.id })
        assertEquals(
            listOf(CardState.DONE, CardState.DONE, CardState.DONE, CardState.DONE),
            parsed.lines.map { it.state },
        )
    }

    @Test
    fun `回放往返后类型一致`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(sample()))
        assertEquals(
            listOf(CardKind.INTENT, CardKind.ACT, CardKind.OBS, CardKind.SAY),
            parsed.lines.map { it.kind },
        )
    }

    @Test
    fun `回放往返后头部序号一致`() {
        val t = (sample().reduce(CardEvent.TakeOver(100L)) as Reduction.Applied).timeline
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(t))
        assertEquals(PauseReason.USER_TAKEOVER, parsed.pause)
        assertEquals(t.saySeq, parsed.saySeq)
        assertEquals(t.troubleSeq, parsed.troubleSeq)
        assertEquals(TimelineExport.REPLAY_VERSION, parsed.version)
    }

    @Test
    fun `回放往返后预演文案逐字不变`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(sample()))
        assertEquals("给妈妈发一条消息", parsed.lines[0].field("act"))
        assertEquals("微信", parsed.lines[0].field("tgt"))
    }

    @Test
    fun `回放往返后工具返回原文逐字不变`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(sample()))
        assertTrue(parsed.lines[1].field("detail")!!.contains("已派发，通道=ACCESSIBILITY"))
    }

    @Test
    fun `回放往返能扛住所有分隔符（含竖线分号等号反斜杠换行）`() {
        val nasty = "点|发送;a=b\\c\nd"
        val t = listOf(
            CardEvent.ToolStarted(1L, CardId("a-1"), "android_tap", "点发送"),
            CardEvent.ToolSucceeded(2L, CardId("a-1"), nasty, 1L),
        ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(t))
        assertEquals(nasty, parsed.lines.single().field("detail"))
    }

    @Test
    fun `回放往返能扛住 id 里的分隔符`() {
        val t = listOf(
            CardEvent.Observed(1L, CardId("o|x;y=z\\w"), "微信", 3),
        ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(t))
        assertEquals("o|x;y=z\\w", parsed.lines.single().id)
    }

    @Test
    fun `回放往返后出口顺序与条数不变`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(permissionFailure()))
        val exits = parsed.lines.last().exits()
        assertEquals(3, exits.size)
        assertTrue(exits[0].startsWith("ALT:"))
        assertEquals("TAKEOVER", exits[2])
    }

    @Test
    fun `回放往返后出口标签里的分隔符不丢`() {
        val t = listOf(
            CardEvent.ToolStarted(1L, CardId("a-1"), "android_tap", "点发送"),
            CardEvent.ToolFailed(
                2L,
                CardId("a-1"),
                Gate.PERMISSION,
                "被拒",
                listOf(Exit.Alternative("换一种;带|分隔符=的做法"), Exit.TakeOver),
            ),
        ).fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(t))
        assertEquals("ALT:换一种;带|分隔符=的做法", parsed.lines.last().exits()[0])
    }

    @Test
    fun `escape 与 unescape 严格互逆`() {
        val samples = listOf("", "abc", "点|发送", "a;b", "a=b", "C:\\x", "l1\nl2", "\r\n", "\\|;=", "反斜杠\\末尾")
        samples.forEach { s ->
            assertEquals("互逆失败：$s", s, TimelineExport.unescape(TimelineExport.escape(s)))
        }
    }

    @Test
    fun `识别不了的转义原样保留（宁可多留也不吃掉用户的话）`() {
        assertEquals("a\\qb", TimelineExport.unescape("a\\qb"))
    }

    @Test
    fun `缺少版本号时解析报错而不是猜`() {
        try {
            TimelineExport.parseReplay("pause=-\nsaySeq=0\ntroubleSeq=0\n")
            throw AssertionError("缺少版本号应当报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("版本号"))
        }
    }

    @Test
    fun `版本号不匹配时解析报错`() {
        try {
            TimelineExport.parseReplay("v=${TimelineExport.REPLAY_VERSION + 1}\n")
            throw AssertionError("版本不匹配应当报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    /**
     * ★ 损坏的行必须**抛**，不许跳过。
     *
     * "解析不了就跳过这一行"会让复盘安静地少几条卡，而看的人以为那就是全部 ——
     * 这与"回放串里混进界面文案"是同一类错误：都不报错，都在骗人。
     */
    @Test
    fun `结构损坏的行解析报错而不是跳过`() {
        try {
            TimelineExport.parseReplay("v=1\npause=-\nsaySeq=0\ntroubleSeq=0\n1|ACT|a-1\n")
            throw AssertionError("字段不足应当报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("字段不足"))
        }
    }

    @Test
    fun `未知类型名解析报错`() {
        try {
            TimelineExport.parseReplay("v=1\npause=-\nsaySeq=0\ntroubleSeq=0\n1|NOPE|a-1|DONE|x=1\n")
            throw AssertionError("未知类型应当报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `空文本解析报错`() {
        try {
            TimelineExport.parseReplay("")
            throw AssertionError("空文本应当报错")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `只有头部没有卡也是合法回放`() {
        val parsed = TimelineExport.parseReplay(TimelineExport.toReplay(Timeline()))
        assertTrue(parsed.lines.isEmpty())
        assertNull(parsed.pause)
    }

    // ── CardProjection ─────────────────────────────────────────────

    private fun result(
        isError: Boolean = false,
        gate: CardProjection.ToolGate = CardProjection.ToolGate.OK,
        blind: Boolean = false,
        text: String = "已派发",
    ) = CardProjection.ToolResult(
        tool = "android_tap",
        text = text,
        isError = isError,
        gate = gate,
        channel = "ACCESSIBILITY",
        latencyMs = 72L,
        blind = blind,
        summary = "点「发送」",
    )

    @Test
    fun `投影出两个事件（开始 + 结束）`() {
        val events = CardProjection.project(CardId("a-1"), 1L, 2L, result())
        assertEquals(2, events.size)
        assertTrue(events[0] is CardEvent.ToolStarted)
    }

    @Test
    fun `成功投影出 ToolSucceeded`() {
        val events = CardProjection.project(CardId("a-1"), 1L, 2L, result())
        assertTrue(events[1] is CardEvent.ToolSucceeded)
    }

    @Test
    fun `isError 投影出 ToolFailed`() {
        val events = CardProjection.project(CardId("a-1"), 1L, 2L, result(isError = true))
        assertTrue(events[1] is CardEvent.ToolFailed)
    }

    @Test
    fun `安全门投影成 Gate SAFETY`() {
        val events = CardProjection.project(
            CardId("a-1"), 1L, 2L,
            result(isError = true, gate = CardProjection.ToolGate.SAFETY),
        )
        assertEquals(Gate.SAFETY, (events[1] as CardEvent.ToolFailed).gate)
    }

    @Test
    fun `权限门投影成 Gate PERMISSION`() {
        val events = CardProjection.project(
            CardId("a-1"), 1L, 2L,
            result(isError = true, gate = CardProjection.ToolGate.PERMISSION),
        )
        assertEquals(Gate.PERMISSION, (events[1] as CardEvent.ToolFailed).gate)
    }

    @Test
    fun `执行门投影成 Gate EXECUTION`() {
        val events = CardProjection.project(
            CardId("a-1"), 1L, 2L,
            result(isError = true, gate = CardProjection.ToolGate.EXECUTION),
        )
        assertEquals(Gate.EXECUTION, (events[1] as CardEvent.ToolFailed).gate)
    }

    @Test
    fun `盲点投影出不确定性文案`() {
        val events = CardProjection.project(CardId("a-1"), 1L, 2L, result(blind = true))
        assertEquals(CardProjection.BLIND_NOTE, (events[1] as CardEvent.ToolSucceeded).uncertainty)
    }

    @Test
    fun `非盲点没有不确定性文案`() {
        val events = CardProjection.project(CardId("a-1"), 1L, 2L, result(blind = false))
        assertNull((events[1] as CardEvent.ToolSucceeded).uncertainty)
    }

    @Test
    fun `失败原因直接透传工具文案`() {
        val events = CardProjection.project(
            CardId("a-1"), 1L, 2L,
            result(isError = true, text = "点了，但界面没反应"),
        )
        assertEquals("点了，但界面没反应", (events[1] as CardEvent.ToolFailed).reason)
    }

    @Test
    fun `失败原因为空时用门的标题兜底`() {
        val events = CardProjection.project(
            CardId("a-1"), 1L, 2L,
            result(isError = true, text = "", gate = CardProjection.ToolGate.EXECUTION),
        )
        assertEquals(
            FailurePlan.headline(Gate.EXECUTION),
            (events[1] as CardEvent.ToolFailed).reason,
        )
    }

    @Test
    fun `投影出的失败事件带出口`() {
        val events = CardProjection.project(CardId("a-1"), 1L, 2L, result(isError = true))
        assertTrue((events[1] as CardEvent.ToolFailed).exits.size >= 2)
    }

    @Test
    fun `结束时间早于开始时间时拒绝`() {
        try {
            CardProjection.project(CardId("a-1"), 5L, 1L, result())
            throw AssertionError("应当拒绝倒序时间")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `投影出来的事件能直接喂给时间线`() {
        val t = CardProjection.project(CardId("a-1"), 1L, 2L, result())
            .fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        assertEquals(CardState.DONE, t.cards.single().state)
    }

    @Test
    fun `失败的投影能产生问题卡`() {
        val t = CardProjection.project(CardId("a-1"), 1L, 2L, result(isError = true))
            .fold(Timeline()) { acc, e -> (acc.reduce(e) as Reduction.Applied).timeline }
        assertEquals(2, t.cards.size)
        assertEquals(CardKind.TROUBLE, t.cards[1].kind)
    }

    @Test
    fun `观察摘要含应用名`() {
        assertTrue(CardProjection.observeSummary("微信", listOf("WLAN"), 20).contains("微信"))
    }

    @Test
    fun `观察摘要在没有标志项时也给节点数`() {
        val s = CardProjection.observeSummary("设置", emptyList(), 0)
        assertTrue(s.contains("0 个节点"))
    }

    @Test
    fun `观察摘要最多三个标志项`() {
        val s = CardProjection.observeSummary("x", listOf("a", "b", "c", "d", "e"), 1)
        assertFalse(s.contains("d"))
    }

    @Test
    fun `观察摘要在应用名为空时说未知界面`() {
        assertTrue(CardProjection.observeSummary(null, emptyList(), 1).contains("未知界面"))
    }
}
