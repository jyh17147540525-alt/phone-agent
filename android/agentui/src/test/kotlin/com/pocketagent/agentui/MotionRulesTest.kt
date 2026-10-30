package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **动效规则**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的三件事，都是"看起来能跑但手感坏了"的类型
 * ═══════════════════════════════════════════════════════════════
 *
 * 动画的错**不会报错**。它只会让用户觉得"这 App 有点慢"或"有点土"，
 * 而那种感觉很难归因到某一行代码。所以三条都要用数字钉死：
 *
 *  ① 用户关掉动画 ⇒ **必须真的立刻显示**，不是"动得更快"
 *  ② 省电模式 ⇒ 保留淡入但去掉位移/缩放（硬切是另一种粗糙）
 *  ③ 错峰必须封顶 ⇒ 否则第 30 项要等 1.2 秒
 */
class MotionRulesTest {

    // ═══════════════════════════════════════════════════════════
    //  ① 系统关掉动画
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `系统关掉动画时完全不动`() {
        val p = MotionRules.plan(animationsOn = false, richMotion = false, index = 0)
        assertTrue("必须是静态", p.isStatic)
        assertEquals("时长必须为 0", 0, p.durationMs)
        assertEquals("不该有上浮", 0f, p.riseDp, 0.0001f)
        assertEquals("不该有缩放", 1f, p.fromScale, 0.0001f)
    }

    @Test
    fun `关掉动画时连错峰延迟也要归零`() {
        // ⚠️ 这一条最容易漏：只把 duration 设 0、忘了 delay，
        //    结果列表项还是"依次瞬间出现" —— 那看起来像闪烁，
        //    比"慢一点"更糟。
        for (i in listOf(0, 1, 5, 20)) {
            val p = MotionRules.plan(animationsOn = false, richMotion = true, index = i)
            assertEquals("index=$i 时延迟必须为 0", 0, p.startDelayMs)
        }
    }

    @Test
    fun `关掉动画时即使 richMotion 为真也不动`() {
        // animationsOn 是**总闸**：它关了就什么都别做，
        // richMotion 只决定"能不能做贵的"。
        val p = MotionRules.plan(animationsOn = false, richMotion = true)
        assertTrue(p.isStatic)
    }

    // ═══════════════════════════════════════════════════════════
    //  ② 省电模式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `省电模式下保留淡入但去掉位移与缩放`() {
        val p = MotionRules.plan(animationsOn = true, richMotion = false)
        assertFalse("仍然要有动画（硬切是另一种粗糙）", p.isStatic)
        assertEquals("时长照常", MotionRules.BASE_DURATION_MS, p.durationMs)
        assertEquals("不该上浮", 0f, p.riseDp, 0.0001f)
        assertEquals("不该缩放", 1f, p.fromScale, 0.0001f)
    }

    @Test
    fun `省电模式下错峰仍然保留`() {
        // 错峰是"零成本"的（只是延迟），省电时没必要砍。
        val p = MotionRules.plan(animationsOn = true, richMotion = false, index = 2)
        assertEquals(MotionRules.STAGGER_STEP_MS * 2, p.startDelayMs)
    }

    // ═══════════════════════════════════════════════════════════
    //  ③ 完整效果
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `完整模式给上浮与缩放`() {
        val p = MotionRules.plan(animationsOn = true, richMotion = true)
        assertFalse(p.isStatic)
        assertTrue("必须有上浮", p.riseDp > 0f)
        assertTrue("必须有缩放", p.fromScale < 1f)
    }

    @Test
    fun `上浮距离很小`() {
        // ⚠️ 8dp 是量出来的：4dp 感觉不到，16dp 开始像"飞入"
        //    （那是网页的做法，不是系统 UI）。
        val p = MotionRules.plan(animationsOn = true, richMotion = true)
        assertTrue("上浮不能大到像飞入（现在 ${p.riseDp}dp）", p.riseDp <= 10f)
        assertTrue("上浮不能小到感觉不到（现在 ${p.riseDp}dp）", p.riseDp >= 4f)
    }

    @Test
    fun `缩放幅度极小`() {
        val p = MotionRules.plan(animationsOn = true, richMotion = true)
        // 0.95 以下会明显看出"从小变大"，那是廉价感的来源。
        assertTrue("缩放不能明显（现在 ${p.fromScale}）", p.fromScale >= 0.95f)
        assertTrue("缩放必须存在（现在 ${p.fromScale}）", p.fromScale < 1f)
    }

    @Test
    fun `进场时长不超过三百毫秒`() {
        // 页面级转场才该到 380ms。内容浮现超过 300ms 就开始拖住用户。
        assertEquals(MotionRules.BASE_DURATION_MS, 260)
        assertTrue(MotionRules.BASE_DURATION_MS <= 300)
    }

    // ═══════════════════════════════════════════════════════════
    //  错峰封顶
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `错峰延迟必须封顶`() {
        // ⚠️ 不封顶的话第 30 项要等 1200ms —— 用户以为界面坏了。
        assertEquals("第 0 项不延迟", 0, MotionRules.staggerDelay(0))
        assertEquals("第 1 项一个步长", MotionRules.STAGGER_STEP_MS, MotionRules.staggerDelay(1))
        // 第 6 项：6*40=240 正好到顶
        assertEquals(MotionRules.STAGGER_MAX_MS, MotionRules.staggerDelay(6))
        // 很靠后的项也不该再涨
        assertEquals("封顶之后不再增长", MotionRules.STAGGER_MAX_MS, MotionRules.staggerDelay(30))
        assertEquals("封顶之后不再增长", MotionRules.STAGGER_MAX_MS, MotionRules.staggerDelay(200))
    }

    @Test
    fun `错峰单调不减`() {
        var prev = -1
        for (i in 0..20) {
            val d = MotionRules.staggerDelay(i)
            assertTrue("第 $i 项的延迟回退了（$prev → $d）", d >= prev)
            prev = d
        }
    }

    @Test
    fun `封顶值本身要够小`() {
        // 240ms 上限：最后一项出现时，第一项还没动完（260ms）——
        // 整屏是一段连续的展开，而不是"前面完了再等后面"。
        assertTrue(
            "封顶 ${MotionRules.STAGGER_MAX_MS}ms 必须小于单次时长 ${MotionRules.BASE_DURATION_MS}ms",
            MotionRules.STAGGER_MAX_MS < MotionRules.BASE_DURATION_MS,
        )
    }

    @Test
    fun `负序号不会算出负延迟`() {
        // ⚠️ `delay(-40)` 会立刻返回，看起来像"跳过了动画"，很难查。
        assertEquals(0, MotionRules.staggerDelay(-1))
        assertEquals(0, MotionRules.staggerDelay(-100))
    }

    // ═══════════════════════════════════════════════════════════
    //  组合不变式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `任何组合都不会给出离谱的参数`() {
        for (on in listOf(true, false)) {
            for (rich in listOf(true, false)) {
                for (i in listOf(-5, 0, 1, 10, 999)) {
                    val p = MotionRules.plan(on, rich, i)
                    assertTrue("时长不能为负", p.durationMs >= 0)
                    assertTrue("延迟不能为负", p.startDelayMs >= 0)
                    assertTrue("上浮不能为负", p.riseDp >= 0f)
                    assertTrue("缩放必须在 (0,1]：${p.fromScale}", p.fromScale > 0f && p.fromScale <= 1f)
                    assertTrue("延迟必须封顶", p.startDelayMs <= MotionRules.STAGGER_MAX_MS)
                }
            }
        }
    }

    @Test
    fun `关掉动画是唯一能产生静态计划的输入`() {
        // ★ 反过来说：只要 animationsOn = true，就**一定**有动画。
        //   这防止"省电 + 某个 index"这种组合意外退化成硬切。
        for (rich in listOf(true, false)) {
            for (i in listOf(0, 3, 50)) {
                assertFalse(
                    "animationsOn=true 时不该出现静态计划（rich=$rich index=$i）",
                    MotionRules.plan(animationsOn = true, richMotion = rich, index = i).isStatic,
                )
            }
        }
    }
}
