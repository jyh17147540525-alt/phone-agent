package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **感知档位接线**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是"省成本"策略的实质
 * ═══════════════════════════════════════════════════════════════
 *
 * 规划文档的结论：**轮次能耗差三个数量级**（30 轮 0.27% vs 8 轮 0.07%）。
 * 而每一轮里最贵的是**截图**：PNG 编码 + base64 + 上传，几百毫秒 + 几百 KB。
 *
 * ⇒ 对不需要看屏幕的动作坚决不截。
 *
 * 写错的代价分两个方向，**都不报错**：
 *   · 该省没省 ⇒ 每一轮多几百毫秒（用户觉得"慢"）
 *   · 该采没采 ⇒ 工具看不到东西而失败（用户觉得"笨"）
 */
class PerceptionPlannerTest {

    private val full = PerceptionCapability(
        accessibilityAvailable = true,
        screenshotAvailable = true,
        ocrAvailable = true,
        onUnmeteredNetwork = true,
    )

    private val noScreenshot = full.copy(screenshotAvailable = false)
    private val nothing = PerceptionCapability(
        accessibilityAvailable = false,
        screenshotAvailable = false,
        ocrAvailable = false,
    )

    // ═══════════════════════════════════════════════════════════
    //  ★ 零采集：不该截图的动作
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `纯动作工具不采集`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是整个"省成本"的核心
        // ══════════════════════════════════════════════════════
        // `android_tap` 坐标已经给了，点就完了 —— 屏幕上长什么样
        // 与这次点击无关。截一张图只是让这一步慢 300ms。
        for (tool in listOf("android_tap", "android_swipe", "android_key", "type_text")) {
            val p = PerceptionPlanner.plan(tool, full)
            assertEquals("「$tool」不该采集（当前 ${p.tier}）", 0, p.tier)
            assertFalse("不该触发采集", p.shouldCapture)
        }
    }

    @Test
    fun `文件与上下文类工具完全不碰屏幕`() {
        for (tool in listOf(
            "root_exec", "office_run", "office_tools",
            "context_offload", "context_recall", "persona_brief",
        )) {
            val p = PerceptionPlanner.plan(tool, full)
            assertEquals("「$tool」不该采集", 0, p.tier)
        }
    }

    @Test
    fun `纯导航走第零档`() {
        for (tool in listOf("app_host", "open_app", "go_home", "go_back")) {
            val p = PerceptionPlanner.plan(tool, full)
            assertEquals("「$tool」是纯导航，应当零采集", 0, p.tier)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  该采集的
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `读屏必须采集`() {
        val p = PerceptionPlanner.plan("screen_read", full)
        assertTrue("读屏当然要看屏幕（当前 ${p.tier}）", p.shouldCapture)
        assertTrue("至少第 1 档", p.tier >= 1)
    }

    @Test
    fun `视觉理解类工具给更高档`() {
        // ★ 图标识别/OCR 需要像素，光有元素树不够。
        val read = PerceptionPlanner.plan("screen_read", full)
        val visual = PerceptionPlanner.plan("screen_describe", full)
        assertTrue(
            "视觉理解(${visual.tier}) 应当不低于普通读屏(${read.tier})",
            visual.tier >= read.tier,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 未知工具的保守方向
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `未知工具按需要元素处理`() {
        // ══════════════════════════════════════════════════════
        //  ★ 保守方向是**多采集**，不是少采集
        // ══════════════════════════════════════════════════════
        // 未知工具如果其实需要屏幕而我们没采，它会因为"看不到东西"
        // 而失败 —— 那是**功能性失败**，比多花一次截图严重得多。
        val p = PerceptionPlanner.plan("some_future_tool", full)
        assertTrue("未知工具应当保守地采集（当前 ${p.tier}）", p.shouldCapture)
    }

    @Test
    fun `未知工具在无能力时也不会崩`() {
        val p = PerceptionPlanner.plan("some_future_tool", nothing)
        assertEquals("完全没有感知能力时应当是 0", 0, p.tier)
    }

    // ═══════════════════════════════════════════════════════════
    //  能力缺失时的降级
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `没有截图能力时降级并标记`() {
        // ★ `degraded = true` 让界面能提示"打开无障碍/截图权限"。
        //   静默降级会让用户以为"它本来就这么笨"。
        val p = PerceptionPlanner.plan("screen_describe", noScreenshot)
        assertTrue("应当标记为降级", p.degraded)
        assertTrue("必须给出可展示的理由", p.reason.isNotBlank())
    }

    @Test
    fun `完全无能力时不采集但不崩`() {
        for (tool in listOf("screen_read", "screen_describe", "android_tap")) {
            val p = PerceptionPlanner.plan(tool, nothing)
            assertEquals("「$tool」在无能力时只能是 0", 0, p.tier)
            assertFalse(p.shouldCapture)
        }
    }

    @Test
    fun `每种情况都必须给理由`() {
        // ⚠️ 理由是**给用户看**的（"执行过程可见"原则）。
        //    空理由等于让用户对着一个说不清的状态。
        val cases = listOf(
            "android_tap" to full,
            "screen_read" to full,
            "screen_describe" to noScreenshot,
            "unknown_tool" to full,
            "screen_read" to nothing,
        )
        for ((tool, cap) in cases) {
            val p = PerceptionPlanner.plan(tool, cap)
            assertTrue("「$tool」缺理由", p.reason.isNotBlank())
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  缓存元素引用
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `有缓存引用时可以零采集`() {
        // ★ 元素引用已经解析过（比如上一步读到过），这一步就不必再读屏。
        val p = PerceptionPlanner.plan(
            toolName = "screen_read",
            capability = full,
            hasCachedElementRef = true,
        )
        // ⚠️ `screen_read` 本身 needsElement=true，所以它仍然要采集。
        //    缓存优化主要作用于"按元素操作"类工具。
        assertTrue("读屏本身仍要采集", p.shouldCapture)
    }

    // ═══════════════════════════════════════════════════════════
    //  升档重试
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `失败后升档`() {
        // ⚠️ 低档失败后"再用同样档位试一次"必然再失败 ——
        //    必须升档，否则用户看到"它反复失败"。
        assertEquals(2, PerceptionPlanner.escalateIfFailed(previousTier = 1, failed = true))
        assertEquals(3, PerceptionPlanner.escalateIfFailed(previousTier = 2, failed = true))
    }

    @Test
    fun `成功不升档`() {
        assertEquals(1, PerceptionPlanner.escalateIfFailed(previousTier = 1, failed = false))
        assertEquals(0, PerceptionPlanner.escalateIfFailed(previousTier = 0, failed = false))
    }

    @Test
    fun `升档到顶不再升`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是崩溃
        // ══════════════════════════════════════════════════════
        // `PerceptionTier` 只有 0..4。不封顶的话越界后
        // `fromLevel(5)` 会走 else 分支（可能有兜底，也可能是异常）。
        val max = PerceptionTier.entries.maxOf { it.level }
        assertEquals(max, PerceptionPlanner.escalateIfFailed(max, failed = true))
        assertEquals(max, PerceptionPlanner.escalateIfFailed(max + 5, failed = true))
    }

    @Test
    fun `从零档失败升到一档`() {
        assertEquals(1, PerceptionPlanner.escalateIfFailed(previousTier = 0, failed = true))
    }

    // ═══════════════════════════════════════════════════════════
    //  demandFor 的映射表
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `纯导航标记正确`() {
        assertTrue(PerceptionPlanner.demandFor("app_host").isPureNavigation)
        assertTrue(PerceptionPlanner.demandFor("go_home").isPureNavigation)
        // ⚠️ `android_tap` 也标成纯导航 —— 见 `demandFor` 的注释：
        //    不这样标它会掉进 ladder 的兜底分支拿到第 3 档（全屏截图），
        //    而这正是那个需要修的真实 bug。
        assertTrue(
            "点击不需要看屏幕内容，必须标成纯导航才不会被兜底升档",
            PerceptionPlanner.demandFor("android_tap").isPureNavigation,
        )
    }

    @Test
    fun `视觉理解标记只给需要像素的工具`() {
        assertTrue(
            PerceptionPlanner.demandFor("screen_describe").requiresVisualUnderstanding,
        )
        assertFalse(
            "普通读屏用元素树就够，不需要像素",
            PerceptionPlanner.demandFor("screen_read").requiresVisualUnderstanding,
        )
    }

    @Test
    fun `映射表不会给出矛盾的需求`() {
        // ⚠️ `isPureNavigation` 与 `needsElement` 同时为真会让 ladder 的
        //    短路顺序变得依赖实现细节 —— 那是不可靠的。
        //
        // ★ 但两者**同时为假**是合法的（比如"未声明"），
        //   虽然我们这个映射表里不会产生那种组合 ——
        //   它会掉进兜底档拿第 3 档，而每个工具都该被显式分类。
        val allTools = listOf(
            "android_tap", "android_swipe", "android_key", "type_text",
            "app_host", "open_app", "go_home", "go_back",
            "screen_read", "screen_describe", "screen_ocr",
            "root_exec", "office_run", "unknown_x",
        )
        for (tool in allTools) {
            val d = PerceptionPlanner.demandFor(tool)
            assertFalse(
                "「$tool」不该同时是纯导航又需要元素",
                d.isPureNavigation && d.needsElement,
            )
            // ★ 每个**已知**工具都必须被显式分类：
            //   两个都是 false ⇒ 会掉进兜底第 3 档 ⇒ 悄悄多截图。
            if (tool != "unknown_x") {
                assertTrue(
                    "「$tool」必须被显式分类（needsElement 或 isPureNavigation 至少一个为真）",
                    d.needsElement || d.isPureNavigation,
                )
            }
        }
    }
}
