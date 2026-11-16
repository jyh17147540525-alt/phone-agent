package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **表演指导的持有者**：过闸、保留、过期、能不能重新生效。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个类原来在 `AppContainer` 里，等于没测
 * ═══════════════════════════════════════════════════════════════
 *
 * `AppContainer` 要 `Context`，`:app` 的单测跑不起 Android 运行时，
 * 于是「拒了要不要清空」「过期算不算数」「过期后同一句能不能回来」
 * 这三件事**零覆盖** —— 而它们正好是这条链最容易写错的地方。
 */
class PerformanceSlotTest {

    private var now = 1_600_000_000_000L

    private val slot = PerformanceSlot { now }

    private fun offer(raw: String?, key: String = "有点紧") = slot.offer(raw, key)

    private fun acceptAndGet(raw: String, key: String = "有点紧"): Performance {
        val v = offer(raw, key)
        assertTrue("应当被接受，实际：$v", v is PerformanceVerdict.Accepted)
        return (v as PerformanceVerdict.Accepted).performance
    }

    // ── 基本存取 ────────────────────────────────────────────────

    @Test
    fun `第一次接受之后就能读到`() {
        val p = acceptAndGet("把话说短。")
        assertEquals("把话说短。", p.line)
        assertEquals("把话说短。", slot.current()?.line)
        assertEquals("把话说短。", slot.lastLine())
        assertEquals(p, slot.raw())
    }

    @Test
    fun `什么都没喂过时是空的`() {
        assertNull(slot.current())
        assertNull(slot.lastLine())
        assertNull(slot.raw())
    }

    // ── 拒绝时**不清空**（最关键的一条）────────────────────────

    @Test
    fun `被拒时保留旧值 而不是清空`() {
        // ⚠️ 模型偶然写一句垃圾，不该把这一轮本来有效的指导弄没
        acceptAndGet("把话说短。")
        val v = offer("皮质醇 72，别说话了。")
        assertTrue(v is PerformanceVerdict.Rejected)
        assertEquals("旧句还在", "把话说短。", slot.current()?.line)
    }

    @Test
    fun `空值不清空旧值`() {
        // 大多数轮次本来就没有指导 —— 那是常态，不是"撤回上一条"
        acceptAndGet("把话说短。")
        val v = offer(null)
        assertEquals("空", (v as PerformanceVerdict.Rejected).reason)
        assertEquals("把话说短。", slot.current()?.line)
    }

    @Test
    fun `同一激素换措辞被拒 旧句还在`() {
        acceptAndGet("把话说短。")
        val v = offer("少说两句，别展开。")
        assertEquals(
            "激素没变，沿用上一条",
            (v as PerformanceVerdict.Rejected).reason,
        )
        assertEquals("把话说短。", slot.current()?.line)
    }

    @Test
    fun `激素变了可以换一句`() {
        acceptAndGet("把话说短。")
        val p = (offer("可以多聊两句。", key = "很想靠近") as PerformanceVerdict.Accepted).performance
        assertEquals("可以多聊两句。", p.line)
        assertEquals("很想靠近", slot.current()?.hormonesKey)
    }

    // ── 过期 ────────────────────────────────────────────────────

    @Test
    fun `过期后读不到 但原始值还在`() {
        acceptAndGet("把话说短。")
        now += Performance.TTL_MS + 1
        assertNull("过期就不该再进提示词", slot.current())
        assertNull(slot.lastLine())
        assertEquals("原始值保留，供日志与排查", "把话说短。", slot.raw()?.line)
    }

    @Test
    fun `过期后同一句可以重新生效`() {
        // ══════════════════════════════════════════════════════
        //  ★ 防抖基准必须只看**未过期**的旧值
        // ══════════════════════════════════════════════════════
        // 否则：状态真的没变 → 模型照实写回同一句 → 被判「与上一轮完全相同」
        // → 这条指导在 20 分钟后**永久失效**，而且没人看得出来。
        acceptAndGet("把话说短。")
        now += Performance.TTL_MS + 1
        val v = offer("把话说短。")
        assertTrue("过期之后同一句必须能回来，实际：$v", v is PerformanceVerdict.Accepted)
        assertEquals("把话说短。", slot.current()?.line)
    }

    // ── 清理 ────────────────────────────────────────────────────

    @Test
    fun `clear 之后读不到任何东西`() {
        acceptAndGet("把话说短。")
        slot.clear()
        assertNull(slot.current())
        assertNull(slot.raw())
    }
}
