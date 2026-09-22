package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `PerceptionLadder` 的测试。
 *
 * ## 最重要的一组：第 0 档必须被优先命中
 *
 * 第 0 档是**唯一的零成本路径**。它一旦被别的条件抢先，
 * 表现是"功能完全正常，只是每步都多花一次截图" ——
 * **没有任何症状**，只有用户的流量账单和电量知道。
 *
 * 所以这里用大量组合去钉"什么情况下必须走第 0 档"。
 */
class PerceptionLadderTest {

    private val fullCapability = PerceptionCapability(
        accessibilityAvailable = true,
        screenshotAvailable = true,
        ocrAvailable = true,
        onUnmeteredNetwork = true,
    )

    private fun ladder(
        accessibility: Boolean = true,
        screenshot: Boolean = true,
        ocr: Boolean = false,
        unmetered: Boolean = true,
    ) = PerceptionLadder(
        PerceptionCapability(
            accessibilityAvailable = accessibility,
            screenshotAvailable = screenshot,
            ocrAvailable = ocr,
            onUnmeteredNetwork = unmetered,
        ),
    )

    // ── ★ 第 0 档：零成本路径 ──────────────────────────────────

    @Test
    fun `纯导航动作走第 0 档`() {
        // 「打开微信」这类动作完全不需要知道屏幕长什么样
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = false, isPureNavigation = true),
        )

        assertEquals(PerceptionTier.NONE, decision.tier)
        assertEquals("纯导航不应采集快照", false, decision.needsCapture)
    }

    @Test
    fun `纯导航即使需要元素也走第 0 档`() {
        // ★ 顺序验证：若"需要元素"这一条先判，纯导航会被抢先采一张快照。
        //   看起来功能正常，只是成本翻倍 —— 这正是最难发现的一类退化。
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = true, isPureNavigation = true),
        )

        assertEquals(
            "纯导航的判定必须优先于需要元素的判定",
            PerceptionTier.NONE, decision.tier,
        )
    }

    @Test
    fun `纯导航即使要求视觉理解也走第 0 档`() {
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(
                needsElement = true,
                isPureNavigation = true,
                requiresVisualUnderstanding = true,
            ),
        )

        assertEquals(PerceptionTier.NONE, decision.tier)
    }

    @Test
    fun `已有缓存元素引用且不需要元素定位时走第 0 档`() {
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = false, hasCachedElementRef = true),
        )

        assertEquals(PerceptionTier.NONE, decision.tier)
    }

    @Test
    fun `已有缓存引用但仍需要元素定位时不能走第 0 档`() {
        // hasCachedElementRef 只在"不需要重新定位"时才省掉采集
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = true, hasCachedElementRef = true),
        )

        assertEquals(PerceptionTier.ACCESSIBILITY_TREE, decision.tier)
    }

    @Test
    fun `第 0 档的决策理由对用户可读`() {
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = false, isPureNavigation = true),
        )

        assertTrue("理由不能为空（执行过程要对用户可见）", decision.reason.isNotBlank())
    }

    @Test
    fun `第 0 档不是降级`() {
        // ★ 第 0 档是**正常路径**，不是"因为没能力才退化"。
        //   若它被标成 degraded，UI 会弹出"权限受限"的提示 —— 而权限其实好好的。
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = false, isPureNavigation = true),
        )

        assertFalse("第 0 档是正常路径，不应标记为降级", decision.degraded)
    }

    // ── 第 1 档：无障碍树 ─────────────────────────────────────

    @Test
    fun `需要定位元素且树可用时用第 1 档`() {
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = true),
        )

        assertEquals(PerceptionTier.ACCESSIBILITY_TREE, decision.tier)
        assertTrue(decision.needsCapture)
    }

    @Test
    fun `不因为树可能不全而预先升档`() {
        // ★ 刻意保持第 1 档。预先升到第 2 档的成本是**每步都付**，
        //   而"树描述不全"只发生在少数页面。
        //   正确的做法是"定位失败 → NeedMoreInfo → 再升档"。
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = true),
        )

        assertEquals(PerceptionTier.ACCESSIBILITY_TREE, decision.tier)
        assertFalse(decision.degraded)
    }

    @Test
    fun `树不可用但截图可用时需要元素时退化到全屏截图`() {
        val decision = ladder(accessibility = false, screenshot = true).decide(
            PerceptionDemand(needsElement = true),
        )

        assertEquals(PerceptionTier.FULL_SCREENSCALE_REDUCED, decision.tier)
        assertTrue("能力不足导致的档位应标记降级", decision.degraded)
    }

    @Test
    fun `两者都不可用且需要元素时不采集并标记降级`() {
        val decision = ladder(accessibility = false, screenshot = false).decide(
            PerceptionDemand(needsElement = true),
        )

        // ⚠️ 这里返回 NONE 但同时标 degraded ——
        //    NONE 是"不采集"，degraded 是"这不是我们想要的"。
        //    两者语义不同，不能混为一谈。
        assertEquals(PerceptionTier.NONE, decision.tier)
        assertTrue(decision.degraded)
        assertTrue(decision.reason.isNotBlank())
    }

    // ── 视觉理解需求 ───────────────────────────────────────────

    @Test
    fun `要求视觉理解且有截图能力时用全屏档`() {
        val decision = PerceptionLadder(fullCapability).decide(
            PerceptionDemand(needsElement = false, requiresVisualUnderstanding = true),
        )

        assertEquals(PerceptionTier.FULL_SCREENSCALE_REDUCED, decision.tier)
    }

    @Test
    fun `要求视觉理解但截图不可用时退化为树并标记降级`() {
        // ★ 绝不静默降级 —— UI 必须能告诉用户"这次没看到屏幕"
        val decision = ladder(accessibility = true, screenshot = false).decide(
            PerceptionDemand(needsElement = false, requiresVisualUnderstanding = true),
        )

        assertEquals(PerceptionTier.ACCESSIBILITY_TREE, decision.tier)
        assertTrue(decision.degraded)
        assertTrue("理由要说明结果可能不完整", decision.reason.isNotBlank())
    }

    @Test
    fun `要求视觉理解且两者都不可用时返回不采集并降级`() {
        val decision = ladder(accessibility = false, screenshot = false).decide(
            PerceptionDemand(needsElement = false, requiresVisualUnderstanding = true),
        )

        assertEquals(PerceptionTier.NONE, decision.tier)
        assertTrue(decision.degraded)
    }

    // ── 兜底 ───────────────────────────────────────────────────

    @Test
    fun `未声明需求但有采集能力时走兜底全屏档`() {
        val decision = PerceptionLadder(fullCapability).decide(PerceptionDemand(needsElement = false))

        assertEquals(PerceptionTier.FULL_SCREENSCALE_REDUCED, decision.tier)
    }

    @Test
    fun `无采集能力且未声明需求时不采集`() {
        val decision = ladder(accessibility = false, screenshot = false).decide(
            PerceptionDemand(needsElement = false),
        )

        assertEquals(PerceptionTier.NONE, decision.tier)
        assertTrue(decision.degraded)
    }

    // ── 计费网络 ───────────────────────────────────────────────

    @Test
    fun `计费网络下不使用原质截图`() {
        // 截图上传是流量最大头，非 WiFi 下代价不同
        val decision = ladder(unmetered = false).decide(
            PerceptionDemand(needsElement = false, requiresVisualUnderstanding = true),
        )

        assertEquals(PerceptionTier.FULL_SCREENSCALE_REDUCED, decision.tier)
        assertTrue(decision.reason.contains("计费"))
    }

    @Test
    fun `计费网络的判断在档位决策内部而非调用方`() {
        // ★ 放到调用方会让每个调用点各写一遍，且必然有漏。
        //   这里验证：同一个 demand，只改网络的计费状态，理由里就能看出来。
        val demand = PerceptionDemand(needsElement = false, requiresVisualUnderstanding = true)

        val unmetered = ladder(unmetered = true).decide(demand)
        val metered = ladder(unmetered = false).decide(demand)

        assertFalse(unmetered.reason.contains("计费"))
        assertTrue(metered.reason.contains("计费"))
    }

    // ── 升档 ───────────────────────────────────────────────────

    @Test
    fun `信息不足时逐档上升`() {
        val l = PerceptionLadder(fullCapability)

        assertEquals(
            PerceptionTier.ACCESSIBILITY_TREE,
            l.escalate(PerceptionTier.NONE, GrounderFeedback.NeedMoreInfo("树为空"))!!.tier,
        )
        assertEquals(
            PerceptionTier.TREE_PLUS_PARTIAL,
            l.escalate(PerceptionTier.ACCESSIBILITY_TREE, GrounderFeedback.NeedMoreInfo("x"))!!.tier,
        )
        assertEquals(
            PerceptionTier.FULL_SCREENSCALE_REDUCED,
            l.escalate(PerceptionTier.TREE_PLUS_PARTIAL, GrounderFeedback.NeedMoreInfo("x"))!!.tier,
        )
        assertEquals(
            PerceptionTier.FULL_QUALITY,
            l.escalate(PerceptionTier.FULL_SCREENSCALE_REDUCED, GrounderFeedback.NeedMoreInfo("x"))!!.tier,
        )
    }

    @Test
    fun `已到第 4 档时无法再升`() {
        // 没有更多手段了 → 返回 null，让上层走"手动引导"而不是原地重试
        assertNull(
            PerceptionLadder(fullCapability)
                .escalate(PerceptionTier.FULL_QUALITY, GrounderFeedback.NeedMoreInfo("还是不够")),
        )
    }

    @Test
    fun `目标确实不存在时升档无意义`() {
        // ★ NotFound 说明"目标真的不在屏幕上"，再看一遍也还是没有 ——
        //   升档只是白白多付一次采集成本
        assertNull(
            PerceptionLadder(fullCapability)
                .escalate(PerceptionTier.ACCESSIBILITY_TREE, GrounderFeedback.NotFound("找不到")),
        )
    }

    @Test
    fun `多个候选时升档无意义`() {
        // ★ Ambiguous 需要的是**消歧**（交给 AmbiguityResolver），不是更多像素
        assertNull(
            PerceptionLadder(fullCapability)
                .escalate(PerceptionTier.ACCESSIBILITY_TREE, GrounderFeedback.Ambiguous(3)),
        )
    }

    @Test
    fun `能力不支持下一档时不升档`() {
        // 树与截图都没有 → 从第 0 档升不到第 1 档
        val l = ladder(accessibility = false, screenshot = false)
        assertNull(l.escalate(PerceptionTier.NONE, GrounderFeedback.NeedMoreInfo("看不到")))

        // 只有截图没有树 → 从第 1 档升不到第 2 档（那需要两者）
        val onlyScreenshot = ladder(accessibility = false, screenshot = true)
        assertNull(
            onlyScreenshot.escalate(PerceptionTier.ACCESSIBILITY_TREE, GrounderFeedback.NeedMoreInfo("x")),
        )

        // 只有树没有截图 → 从第 2 档升不到第 3 档（那需要截图）
        val onlyTree = ladder(accessibility = true, screenshot = false)
        assertNull(
            onlyTree.escalate(PerceptionTier.TREE_PLUS_PARTIAL, GrounderFeedback.NeedMoreInfo("x")),
        )
    }

    @Test
    fun `升档的决策理由说明原因`() {
        val decision = PerceptionLadder(fullCapability).escalate(
            PerceptionTier.ACCESSIBILITY_TREE,
            GrounderFeedback.NeedMoreInfo("节点数过少"),
        )!!

        assertTrue("理由应包含上一次失败的原因", decision.reason.contains("节点数过少"))
        assertTrue(decision.reason.contains("2"))
    }

    @Test
    fun `升档不标记为降级`() {
        // 升档是"主动获取更多信息"，不是"能力不足的退化"
        val decision = PerceptionLadder(fullCapability).escalate(
            PerceptionTier.ACCESSIBILITY_TREE, GrounderFeedback.NeedMoreInfo("x"),
        )!!

        assertFalse(decision.degraded)
    }

    // ── 枚举与能力模型 ─────────────────────────────────────────

    @Test
    fun `档位序号连续且从零开始`() {
        assertEquals(listOf(0, 1, 2, 3, 4), PerceptionTier.entries.map { it.level })
    }

    @Test
    fun `只有第 0 档不需要采集`() {
        assertEquals(false, PerceptionTier.NONE.needsCapture)
        for (tier in PerceptionTier.entries.filter { it != PerceptionTier.NONE }) {
            assertTrue("$tier 应当需要采集", tier.needsCapture)
        }
    }

    @Test
    fun `按序号还原档位`() {
        for (tier in PerceptionTier.entries) {
            assertEquals(tier, PerceptionTier.fromLevel(tier.level))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `未知档位序号报错而不是静默回退`() {
        PerceptionTier.fromLevel(99)
    }

    @Test
    fun `可采集能力要求树或截图至少其一`() {
        assertTrue(PerceptionCapability(true, false, false).canCaptureAnything)
        assertTrue(PerceptionCapability(false, true, false).canCaptureAnything)
        assertFalse(PerceptionCapability(false, false, true).canCaptureAnything)
    }

    @Test
    fun `OCR 单独可用不算能采集`() {
        // OCR 需要截图作为输入，所以"只有 OCR"在现实中不成立 ——
        // 这个断言记录的正是"不能仅凭 ocrAvailable 就走采集路径"
        assertFalse(PerceptionCapability(false, false, ocrAvailable = true).canCaptureAnything)
    }
}
