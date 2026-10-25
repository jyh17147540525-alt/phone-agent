package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 毛病解析的测试。
 *
 * ★ 核心要求：**宁可收下也不丢弃**。
 *   用户认真写下的一条毛病，如果因为格式不对被静默丢掉，
 *   他会发现"我写了但它没反应" —— 那比不支持更让人失望。
 */
class FlawParserTest {

    @Test
    fun `标准格式`() {
        val f = FlawParser.parseLine("爱较真：一说到工作就数落我")
        assertNotNull(f)
        assertEquals("爱较真", f!!.label)
        assertEquals("一说到工作就数落我", f.detail)
    }

    @Test
    fun `半角冒号也认`() {
        val f = FlawParser.parseLine("爱较真: 一说到工作就数落我")
        assertEquals("爱较真", f!!.label)
    }

    @Test
    fun `破折号也认`() {
        val f = FlawParser.parseLine("爱较真——一说到工作就数落我")
        assertEquals("爱较真", f!!.label)
    }

    @Test
    fun `没有分隔符也要收下`() {
        // ★ 这是"不丢"的关键：用户写了就得用上。
        val f = FlawParser.parseLine("一说到工作就要数落我")
        assertNotNull(f)
        assertTrue("整行应当成为表现：${f!!.detail}", f.detail.contains("数落"))
        assertTrue("标签要能指认：${f.label}", f.label.isNotBlank())
    }

    @Test
    fun `项目符号要剥掉`() {
        val f = FlawParser.parseLine("- 爱较真：数落我")
        assertEquals("爱较真", f!!.label)
    }

    @Test
    fun `空行跳过`() {
        assertNull(FlawParser.parseLine(""))
        assertNull(FlawParser.parseLine("   "))
        assertNull(FlawParser.parseLine("-"))
    }

    @Test
    fun `一行格式怪不影响其他行`() {
        // ⚠️ 用户写十条有一条怪，不该让九条都失效。
        val flaws = FlawParser.parse(listOf("爱较真：数落我", "", "爱唠叨", "- 爱沉默：生气就不说话"))
        assertTrue("应当收到三条，实际 ${flaws.size}", flaws.size == 3)
    }

    @Test
    fun `格式化回来能再解析`() {
        // ★ 往返一致：用户再进去编辑，看到的还是他写的格式。
        val original = listOf(
            Flaw("爱较真", "数落我"),
            Flaw("爱沉默", "生气就不说话"),
        )
        val text = FlawParser.format(original)
        val parsed = FlawParser.parse(text.lines())

        assertEquals(original.size, parsed.size)
        assertEquals(original[0].label, parsed[0].label)
        assertEquals(original[1].detail, parsed[1].detail)
    }
}

/**
 * 冲突注入的集成测试 —— **这是整个机制对外的唯一出口**。
 */
class LegacyConflictPortTest {

    private val day = 86_400_000L
    private val t0 = 1_700_000_000_000L

    private val flaws = listOf("爱较真：一说到我的工作就要数落我，说我不该辞职")

    private fun stats(days: Int = 60, turns: Int = 300, deep: Int = 3) =
        InteractionStats(days, turns, deep)

    @Test
    fun `没写毛病时什么都不注入`() {
        // ⚠️ 没写毛病 = 不希望他吵架。这是保守的选择。
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = emptyList(),
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(),
            existing = emptyList(),
            currentTopic = "我最近在考虑换工作",
            friction = FrictionSignal.NONE,
            nowMs = t0,
            userName = "小陈",
        )
        assertEquals("没毛病就不该有任何冲突内容", "", out)
    }

    @Test
    fun `写得乱七八糟的毛病也能用`() {
        // ★ 不丢原则的终点验证
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = listOf("一说到工作就要数落我"),
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(),
            existing = emptyList(),
            currentTopic = "随便聊聊",
            friction = FrictionSignal.NONE,
            nowMs = t0,
            userName = null,
        )
        assertTrue("应当有毛病常驻描述", out.isNotBlank())
        assertTrue("应当提到数落：$out", "数落" in out)
    }

    @Test
    fun `刚认识时不注入新冲突`() {
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = flaws,
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(days = 3, turns = 10, deep = 0),
            existing = emptyList(),
            currentTopic = "我最近在考虑换工作",
            friction = FrictionSignal.NONE,
            nowMs = t0,
            userName = null,
        )
        assertFalse(
            "刚认识不该有「此刻的情境」：$out",
            "此刻的情境" in out,
        )
    }

    @Test
    fun `熟了之后相关话题会引发冲突`() {
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = flaws,
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(),
            existing = emptyList(),
            currentTopic = "我最近在考虑换工作",
            friction = FrictionSignal.NONE,
            nowMs = t0,
            userName = "小陈",
        )
        assertTrue("够了就该吵：$out", "此刻的情境" in out)
        assertTrue("要说明收场方式：$out", "递个台阶" in out)
    }

    @Test
    fun `用户抱怨后必须出现那句反应指导`() {
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = flaws,
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(),
            existing = emptyList(),
            currentTopic = "随便",
            friction = FrictionSignal(FrictionLevel.STRONG, t0),
            nowMs = t0,
            userName = null,
        )
        assertTrue("应当有「以前不是这样」的反应指导：$out", "以前不是这样" in out)
        // ★ 关键：明确禁止三种错误反应
        assertTrue("要禁止客服式道歉：$out", "客服" in out)
        assertTrue("要禁止搬出 AI 身份：$out", "AI" in out)
    }

    @Test
    fun `不相关话题不注入旧账`() {
        val existing = listOf(
            Conflict("爱较真", "辞职换工作的事", atMs = t0, resolved = true),
        )
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = flaws,
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(),
            existing = existing,
            currentTopic = "今天天气不错",
            friction = FrictionSignal.NONE,
            nowMs = t0 + 2 * day,
            userName = null,
        )
        assertFalse(
            "不相关的话题绝不能提旧账：$out",
            "你想起了那件事" in out,
        )
    }

    @Test
    fun `相关话题会提起旧账`() {
        val existing = listOf(
            Conflict("爱较真", "辞职换工作的事", atMs = t0, resolved = true),
        )
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = flaws,
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(),
            existing = existing,
            currentTopic = "我又想换工作了",
            friction = FrictionSignal.NONE,
            // 刚吵完一天，概率高（0.7 * 1.0 * 1.0 = 0.7 > 0.3）
            nowMs = t0 + day,
            userName = null,
        )
        assertTrue("相关且概率高就该提：$out", "你想起了那件事" in out)
    }

    @Test
    fun `同一状态下重复调用结果一致`() {
        // ★ 不掷骰子的直接好处：可测试、且用户不会觉得"同一句话两次反应不同"。
        val args = {
            LegacyConflictPort.buildInjection(
                flawsRaw = flaws,
                makeupStyle = MakeupStyle.OFFER_STEP,
                stats = stats(),
                existing = listOf(Conflict("爱较真", "辞职换工作的事", atMs = t0, resolved = true)),
                currentTopic = "我又想换工作了",
                friction = FrictionSignal.NONE,
                nowMs = t0 + day,
                userName = null,
            )
        }
        val a = args()
        repeat(5) { assertEquals("同一状态必须完全一致", a, args()) }
    }

    @Test
    fun `提过两次之后不再主动提`() {
        val existing = listOf(
            Conflict(
                flawLabel = "爱较真",
                topic = "辞职换工作的事",
                atMs = t0,
                mentionsAtMs = listOf(t0 + day, t0 + 2 * day),
                resolved = true,
            ),
        )
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = flaws,
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(days = 30, turns = 100, deep = 2),
            existing = existing,
            currentTopic = "我又想换工作了",
            friction = FrictionSignal.NONE,
            nowMs = t0 + 3 * day,
            userName = null,
        )
        // 概率 = 0.7 * 0.15 = 0.105 < 0.3 阈值 → 不该提
        assertFalse(
            "提过两次后不该再主动提：$out",
            "你想起了那件事" in out,
        )
    }

    @Test
    fun `禁止模型解释自己的设定`() {
        // ⚠️ 这是最容易毁掉幻觉的一句话，必须在提示词里明确禁止。
        val out = LegacyConflictPort.buildInjection(
            flawsRaw = flaws,
            makeupStyle = MakeupStyle.OFFER_STEP,
            stats = stats(),
            existing = emptyList(),
            currentTopic = "换工作",
            friction = FrictionSignal.NONE,
            nowMs = t0,
            userName = null,
        )
        assertTrue("要禁止说「因为我的设定」：$out", "设定" in out)
    }
}
