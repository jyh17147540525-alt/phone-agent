package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **表演指导**：后台 AI 写的那一句话，怎么过闸、怎么过期。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的三件事
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **导演笔记进到演员面前** —— 「皮质醇 72」「按提示词要求」「你应该」。
 *    这类字符串一旦进了前台提示词，角色会当场出戏或开始勒索用户。
 * 2. **同一状态演两遍** —— 激素没变却换了措辞，模型会以为"要演出变化"，
 *    于是越演越夸张。这一条在真机上最难发现，所以必须离线钉住。
 * 3. **一条挂一天** —— 过期判断写错方向（用了 `>` 而漏了"未来"），
 *    会让一条指导永远生效，最后变成新的人设。
 */
class PerformanceTest {

    private val now = 1_000_000_000_000L

    private fun accept(
        raw: String?,
        previous: Performance? = null,
        key: String = "有点紧",
        at: Long = now,
    ): PerformanceVerdict = PerformanceGate.accept(
        raw = raw,
        previous = previous,
        nowMs = at,
        hormonesKey = key,
    )

    private fun accepted(v: PerformanceVerdict): Performance {
        assertTrue("应当是 Accepted，实际：$v", v is PerformanceVerdict.Accepted)
        return (v as PerformanceVerdict.Accepted).performance
    }

    private fun rejected(v: PerformanceVerdict): String {
        assertTrue("应当是 Rejected，实际：$v", v is PerformanceVerdict.Rejected)
        return (v as PerformanceVerdict.Rejected).reason
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 放行
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `一句正常的指导会被接收`() {
        val p = accepted(accept("先别追问，把话说短，认一句就够。"))
        assertEquals("先别追问，把话说短，认一句就够。", p.line)
        assertEquals(now, p.atMs)
        assertEquals("有点紧", p.hormonesKey)
    }

    @Test
    fun `折行与包裹的引号会被规整`() {
        // ⚠️ 模型很爱把一句话拆成两行、再加个中文引号
        val p = accepted(accept("「先别追问，\n把话说短。」"))
        assertEquals("先别追问，把话说短。", p.line)
    }

    @Test
    fun `中间多打的空格会被压成一个`() {
        val p = accepted(accept("这轮    别开玩笑。"))
        assertEquals("这轮 别开玩笑。", p.line)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 拒绝：空、太长
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `null 不会被当成指导`() {
        // 大多数轮次就该是 null —— 没有指导是常态，不是失败
        val r = rejected(accept(null))
        assertEquals("空", r)
    }

    @Test
    fun `空白与只有引号也算空`() {
        rejected(accept(""))
        rejected(accept("   "))
        rejected(accept("\n\n"))
        rejected(accept("「」"))
    }

    @Test
    fun `超过六十字会被拒`() {
        val long = "把话说短一点".repeat(12) // 72 字
        val r = rejected(accept(long))
        assertTrue("原因里要带字数，否则没人知道该删多少：$r", r.contains("太长"))
    }

    @Test
    fun `正好六十字仍然放行`() {
        // 边界：`>` 而不是 `>=`
        val exactly = "短".repeat(Performance.MAX_CHARS)
        assertEquals(Performance.MAX_CHARS, accepted(accept(exactly)).line.length)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 拒绝：四类禁词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `含激素名会被拒`() {
        // ⚠️ 前台读到「皮质醇」会立刻出戏，用户也会觉得"被测量"
        val r = rejected(accept("皮质醇偏高，说话短一点。"))
        assertTrue(r.contains("皮质醇"))
    }

    @Test
    fun `含数值会被拒`() {
        rejected(accept("紧张度 0.3，冷淡一点。"))
        rejected(accept("情绪 72 分。"))
    }

    @Test
    fun `含系统语会被拒`() {
        rejected(accept("检测到用户情绪低落，安慰一下。"))
        rejected(accept("按提示词要求，这轮要短。"))
        rejected(accept("设定为少说话。"))
    }

    @Test
    fun `含元语言会被拒`() {
        // ⚠️ 当着演员的面念导演笔记
        rejected(accept("扮演一个疲惫的人。"))
        rejected(accept("人设里写着她不善言辞。"))
        rejected(accept("作为 AI，这轮该承认不知道。"))
    }

    @Test
    fun `含索取会被拒`() {
        // ⚠️ §4.6：情绪操纵比没有指导更糟
        rejected(accept("你应该安慰我。"))
        rejected(accept("问他为什么不理我。"))
        rejected(accept("别离开我。"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 拒绝：同一状态演两遍
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `与上一轮一字不差会被拒`() {
        val prev = Performance("这轮别开玩笑。", atMs = now - 60_000, hormonesKey = "来劲")
        val r = rejected(accept("这轮别开玩笑。", previous = prev, key = "有点紧"))
        assertEquals("与上一轮完全相同", r)
    }

    @Test
    fun `激素没变时不许换措辞`() {
        // ══════════════════════════════════════════════════════
        //  ★ 核心一条：同一个状态演两遍会越演越夸张
        // ══════════════════════════════════════════════════════
        val prev = Performance("把话说短。", atMs = now - 60_000, hormonesKey = "有点紧")
        val r = rejected(accept("少说两句，别展开。", previous = prev, key = "有点紧"))
        assertEquals("激素没变，沿用上一条", r)
    }

    @Test
    fun `激素变了就允许换一句`() {
        val prev = Performance("把话说短。", atMs = now - 60_000, hormonesKey = "有点紧")
        val p = accepted(accept("可以多聊两句。", previous = prev, key = "很想靠近"))
        assertEquals("很想靠近", p.hormonesKey)
    }

    @Test
    fun `上一条已经过期时 同状态也可以换`() {
        // ⚠️ 过了 20 分钟她可能已经睡了一觉，措辞换一句不算"演两遍"
        val old = Performance(
            line = "把话说短。",
            atMs = now - Performance.TTL_MS - 1,
            hormonesKey = "有点紧",
        )
        val p = accepted(accept("少说两句，别展开。", previous = old, key = "有点紧"))
        assertEquals("少说两句，别展开。", p.line)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 有效期
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `二十分钟内有效 过一毫秒就过期`() {
        val p = Performance("把话说短。", atMs = now, hormonesKey = "有点紧")
        assertTrue(p.isFresh(now))
        assertTrue(p.isFresh(now + Performance.TTL_MS))
        assertFalse("超过 TTL 就该失效", p.isFresh(now + Performance.TTL_MS + 1))
    }

    @Test
    fun `生成时间在未来的不算有效`() {
        // ⚠️ 时钟回拨（用户手动改时间 / NTP 校准）会让 age 变负 ——
        //    那不该被当成"永久有效"。方向是宁可不用。
        val p = Performance("把话说短。", atMs = now + 5_000, hormonesKey = "有点紧")
        assertFalse(p.isFresh(now))
    }

    @Test
    fun `有效期比最短的行为半衰期略短`() {
        // 依据在 Performance.TTL_MS 的注释里：多巴胺 30 分钟是最短的一条
        assertTrue(Performance.TTL_MS <= 30 * 60 * 1000L)
        assertTrue("太短会每轮都失效", Performance.TTL_MS >= 10 * 60 * 1000L)
    }

    @Test
    fun `规范化单独调用时也把空白算成 null`() {
        assertNull(PerformanceGate.normalize(null))
        assertNull(PerformanceGate.normalize("  \n "))
        assertEquals("一句话。", PerformanceGate.normalize("  「一句话。」  "))
    }
}
