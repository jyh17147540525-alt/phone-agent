package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消歧策略的测试（D-AT 的落地）。
 *
 * 核心是那条阈值边界：**差距够大自动选、够小问用户**。
 * 边界两边各差 0.01 的表现必须相反 —— 这类浮点比较的边界
 * 是最容易写成 `>` 与 `>=` 搞混的地方。
 */
class AmbiguityResolverTest {

    private fun candidate(label: String, confidence: Float) =
        AmbiguousCandidate(label = label, confidence = confidence, refKey = "ref:$label")

    private val resolver = AmbiguityResolver()

    // ── 构造校验 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `阈值不在开区间时构造失败`() {
        AmbiguityResolver(minConfidenceGap = 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `阈值为 1 时构造失败`() {
        AmbiguityResolver(minConfidenceGap = 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `反问次数上限为 0 时构造失败`() {
        AmbiguityResolver(maxAskBacks = 0)
    }

    // ── 空列表 ─────────────────────────────────────────────────

    @Test
    fun `空候选列表报为上游状态映射错误`() {
        // 空列表**不是歧义**，是"没找到"。两者处理完全不同。
        val result = resolver.resolve("搜索框", emptyList()) as AmbiguityResolution.GiveUp
        assertTrue(result.reason.contains("NotFound"))
    }

    // ── 单候选 ─────────────────────────────────────────────────

    @Test
    fun `唯一候选且置信度可接受时直接选中`() {
        val result = resolver.resolve("搜索框", listOf(candidate("搜索", 0.9f)))
        assertTrue(result is AmbiguityResolution.PickFirst)
        assertEquals("搜索", (result as AmbiguityResolution.PickFirst).chosen.label)
    }

    @Test
    fun `唯一候选但置信度过低时放弃`() {
        // 一个 0.1 置信度的唯一候选不值得照做，不如交给用户
        val result = resolver.resolve("搜索框", listOf(candidate("搜索", 0.1f)))
        assertTrue(result is AmbiguityResolution.GiveUp)
    }

    @Test
    fun `唯一候选恰好在下限上时不放弃`() {
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("搜索", AmbiguityResolver.LOW_CONFIDENCE_FLOOR)),
        )
        assertTrue(result is AmbiguityResolution.PickFirst)
    }

    @Test
    fun `唯一候选略低于下限时放弃`() {
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("搜索", AmbiguityResolver.LOW_CONFIDENCE_FLOOR - 0.01f)),
        )
        assertTrue(result is AmbiguityResolution.GiveUp)
    }

    // ── ★ 阈值边界 ─────────────────────────────────────────────

    @Test
    fun `差距恰好等于阈值时自动选`() {
        // 0.8 - 0.6 = 0.2 == 阈值 → `>=` 应命中
        //
        // ★★ 这条测试第一次跑是**红的**，而它暴露的是源码真 bug：
        //    Float 减法不等于十进制减法 —— `0.8f - 0.6f` 得到的是
        //    `0.199999988`，而 `0.2f` 是 `0.200000003`。
        //    裸 `gap >= minConfidenceGap` 会算出 false，
        //    于是"恰好等于阈值"这个本该自动选的情形永远落到问用户那边。
        //
        //    修法：改用容差比较 `atLeast`（见 AmbiguityResolver.EPSILON）。
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("A", 0.8f), candidate("B", 0.6f)),
        )
        assertTrue("差距等于阈值时应自动选", result is AmbiguityResolution.PickFirst)
    }

    @Test
    fun `多个十进制上恰好等于阈值的组合都自动选`() {
        // 单点验证可能碰巧通过（某个组合的浮点误差恰好为正）。
        // 这里遍历一批"十进制差 == 0.2"的组合，任一个落到问用户那边都算回归。
        val pairs = listOf(
            0.8f to 0.6f,
            0.7f to 0.5f,
            0.9f to 0.7f,
            0.6f to 0.4f,
            0.5f to 0.3f,
            0.4f to 0.2f,
        )
        for ((high, low) in pairs) {
            val r = resolver.resolve("搜索框", listOf(candidate("高", high), candidate("低", low)))
            assertTrue(
                "$high - $low 十进制差为 0.2，应自动选而不是问用户（实际：$r）",
                r is AmbiguityResolution.PickFirst,
            )
        }
    }

    @Test
    fun `容差不会把真正的微小差距误判为达标`() {
        // 反向保证：EPSILON 只吸收 f32 的表示误差（约 3e-9），
        // 不能把有实际意义的 0.19 也吸收掉。
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("A", 0.79f), candidate("B", 0.6f)),
        )
        assertTrue("差 0.19 仍应问用户，容差不该改变判据", result is AmbiguityResolution.AskUser)
    }

    @Test
    fun `差距略小于阈值时问用户`() {
        // 0.79 - 0.6 = 0.19 < 0.2 → 应问用户
        // ⚠️ 这一对（含上面"差距恰好等于阈值"那条）是浮点边界测试：
        //    把比较写成 `>` 或写成裸 `>=`，只有这两条会红。
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("A", 0.79f), candidate("B", 0.6f)),
        )
        assertTrue("差距不足阈值时应问用户", result is AmbiguityResolution.AskUser)
    }

    @Test
    fun `差距明显不足时问用户`() {
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("A", 0.70f), candidate("B", 0.65f)),
        )
        assertTrue(result is AmbiguityResolution.AskUser)
    }

    @Test
    fun `差距很大时自动选`() {
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("A", 0.95f), candidate("B", 0.5f)),
        )
        assertTrue(result is AmbiguityResolution.PickFirst)
    }

    // ── 顺序无关 ───────────────────────────────────────────────

    @Test
    fun `候选顺序不影响结果`() {
        // ★ 若实现是"取第一个"而不排序，这两次会得到不同答案 ——
        //   而列表顺序取决于模型输出的先后，是不可控的。
        //   那意味着"偶尔点错元素"，且无法复现。
        val high = candidate("高", 0.95f)
        val low = candidate("低", 0.5f)

        val a = resolver.resolve("搜索框", listOf(high, low)) as AmbiguityResolution.PickFirst
        val b = resolver.resolve("搜索框", listOf(low, high)) as AmbiguityResolution.PickFirst

        assertEquals("高", a.chosen.label)
        assertEquals("高", b.chosen.label)
    }

    @Test
    fun `乱序输入也选中置信度最高的`() {
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("C", 0.4f), candidate("A", 0.9f), candidate("B", 0.5f)),
        ) as AmbiguityResolution.PickFirst

        assertEquals("A", result.chosen.label)
    }

    // ── 问用户的内容 ───────────────────────────────────────────

    @Test
    fun `问用户时选项按置信度降序`() {
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("C", 0.62f), candidate("A", 0.75f), candidate("B", 0.70f)),
        ) as AmbiguityResolution.AskUser

        // A 最高 → 排第一，用户最可能选的就是第一个
        assertEquals(listOf("A", "B", "C"), result.options)
    }

    @Test
    fun `问用户时理由包含差距与阈值`() {
        val result = resolver.resolve(
            "搜索框",
            listOf(candidate("A", 0.70f), candidate("B", 0.65f)),
        ) as AmbiguityResolution.AskUser

        assertTrue(result.reason.contains("0.05"))
        assertTrue(result.reason.contains("0.20"))
    }

    // ── ★ 反问次数上限 ─────────────────────────────────────────

    @Test
    fun `同一目标反问超过上限后放弃`() {
        // ★ 反复问同一个问题会让用户弃用。
        //   上限后转手动引导，而不是无限反问。
        val resolver = AmbiguityResolver(maxAskBacks = 2)
        val candidates = listOf(candidate("A", 0.70f), candidate("B", 0.65f))

        assertTrue(resolver.resolve("搜索框", candidates) is AmbiguityResolution.AskUser)
        assertTrue(resolver.resolve("搜索框", candidates) is AmbiguityResolution.AskUser)

        val third = resolver.resolve("搜索框", candidates)
        assertTrue("第 3 次应当放弃而不是再问", third is AmbiguityResolution.GiveUp)
    }

    @Test
    fun `反问次数按目标描述分别计数`() {
        val resolver = AmbiguityResolver(maxAskBacks = 2)
        val candidates = listOf(candidate("A", 0.70f), candidate("B", 0.65f))

        repeat(2) { resolver.resolve("搜索框", candidates) }
        // 换一个目标，计数应重新开始
        assertTrue(resolver.resolve("发送按钮", candidates) is AmbiguityResolution.AskUser)
    }

    @Test
    fun `用户选定后清空计数`() {
        val resolver = AmbiguityResolver(maxAskBacks = 2)
        val candidates = listOf(candidate("A", 0.70f), candidate("B", 0.65f))

        repeat(2) { resolver.resolve("搜索框", candidates) }
        assertEquals(1, resolver.pendingAsks)

        resolver.onUserResolved("搜索框")
        assertEquals(0, resolver.pendingAsks)
        // 计数清空后又能问了
        assertTrue(resolver.resolve("搜索框", candidates) is AmbiguityResolution.AskUser)
    }

    @Test
    fun `自动选择不消耗反问次数`() {
        // 只有真的问了用户才算一次 —— 否则"先自动选了几次"
        // 会把配额悄悄用光，到真正需要问的时候反而不能问了
        val resolver = AmbiguityResolver(maxAskBacks = 2)
        repeat(5) {
            resolver.resolve("搜索框", listOf(candidate("A", 0.95f), candidate("B", 0.5f)))
        }
        assertEquals(0, resolver.pendingAsks)
    }

    // ── 默认值 ─────────────────────────────────────────────────

    @Test
    fun `默认阈值与规划文档一致`() {
        // D-AT 建议 0.2
        assertEquals(0.2f, AmbiguityResolver.DEFAULT_MIN_GAP, 0.0001f)
        assertEquals(2, AmbiguityResolver.DEFAULT_MAX_ASK_BACKS)
    }
}
