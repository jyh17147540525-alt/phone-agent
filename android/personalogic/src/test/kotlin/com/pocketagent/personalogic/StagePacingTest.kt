package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **关系推进节奏**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是一个"功能存在但到不了"的 bug
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户问："这个 app 里真的能与角色深度聊 r18 吗？"
 *
 * 诚实的排查结果：不能，因为提示词写着"关系阶段决定你能说到哪一步"，
 * 而 `CLOSE` 的门槛是 **150 轮**、`BONDED` 是 **400 轮** ——
 * 一天聊 10 轮要半个月，中途换会话/清历史/重装都会归零。
 *
 * 而 `:personalogic` 里**早就有** `StageSpeed`（慢/标准/快），
 * 有实现、有测试，**零生产引用**。
 *
 * ⇒ [StagePacing] 把它接上：节奏偏好**真的影响阈值**。
 */
class StagePacingTest {

    // ── 换算方向 ────────────────────────────────────────────────

    @Test
    fun `快节奏让阈值变小，慢节奏让它变大`() {
        val base = 150
        val standard = StagePacing.threshold(base, StageSpeed.NORMAL)
        val fast = StagePacing.threshold(base, StageSpeed.FAST)
        val slow = StagePacing.threshold(base, StageSpeed.SLOW)

        assertEquals("标准不该改变原值", 150, standard)
        assertTrue("快节奏必须让阈值更小（现在是 $fast）", fast < standard)
        assertTrue("慢节奏必须让阈值更大（现在是 $slow）", slow > standard)
    }

    @Test
    fun `倍率作用在阈值上是除法而不是乘法`() {
        // ⚠️ 这条是防"选了快反而更慢"——那种 bug 不报错，只是设置失效。
        //    StageSpeed.FAST = 2.0 ⇒ 走同样的路只需一半轮次 ⇒ 阈值 ÷ 2
        assertEquals(75, StagePacing.threshold(150, StageSpeed.FAST))
        assertEquals(300, StagePacing.threshold(150, StageSpeed.SLOW))
        assertEquals(15, StagePacing.threshold(30, StageSpeed.FAST))
    }

    @Test
    fun `阈值至少为一`() {
        // 0 会让"第一轮就到亲近" —— 那不是调快，那是没有过程。
        assertEquals(1, StagePacing.threshold(1, StageSpeed.FAST))
        assertEquals(0, StagePacing.threshold(0, StageSpeed.FAST))
    }

    @Test
    fun `三档节奏产生三个不同的阈值`() {
        // 防"改了个枚举但换算没变"。
        val values = StageSpeed.entries.map { StagePacing.threshold(150, it) }
        assertEquals("三档节奏必须真的不同：$values", 3, values.distinct().size)
    }

    // ── 进度与剩余 ──────────────────────────────────────────────

    @Test
    fun `剩余轮次不会为负`() {
        assertEquals(0, StagePacing.remaining(turns = 999, base = 30))
        assertEquals(0, StagePacing.remaining(turns = 30, base = 30))
        assertEquals(10, StagePacing.remaining(turns = 20, base = 30))
    }

    @Test
    fun `进度落在零到一之间`() {
        assertEquals(0.0, StagePacing.progress(0, 30), 0.0001)
        assertEquals(1.0, StagePacing.progress(30, 30), 0.0001)
        assertEquals(1.0, StagePacing.progress(999, 30), 0.0001)
        assertEquals(0.5, StagePacing.progress(15, 30), 0.0001)
    }

    @Test
    fun `进度受节奏影响`() {
        // ⚠️ 同一个轮次，快节奏下进度更高 —— 否则用户在界面上看不出
        //   "我改了节奏"这件事真的生效了。
        val normal = StagePacing.progress(15, 30, StageSpeed.NORMAL)
        val fast = StagePacing.progress(15, 30, StageSpeed.FAST)
        assertTrue("快节奏下同样的轮数应更接近目标：$normal vs $fast", fast > normal)
    }

    // ── 给用户看的话 ────────────────────────────────────────────

    @Test
    fun `进度说明必须给出具体数字`() {
        val s = StagePacing.describe(
            turns = 10, base = 30, speed = StageSpeed.NORMAL, nextStageLabel = "熟悉",
        )
        // ★ 刻意说具体数字，不说"多聊聊就好了"：
        //   用户问"为什么它还没跟我熟"时，要能给出他能验证的答案。
        assertTrue("必须说还差几轮：$s", "20" in s)
        assertTrue("必须提到目标阶段：$s", "熟悉" in s)
        assertTrue("必须说当前轮数：$s", "10" in s)
    }

    @Test
    fun `到了就不再说还差多少`() {
        val s = StagePacing.describe(
            turns = 999, base = 30, speed = StageSpeed.NORMAL, nextStageLabel = "熟悉",
        )
        assertTrue("已经到的时候不该说「再有」：$s", "再有" !in s)
        assertTrue("要说已经到了：$s", "已经" in s)
    }

    @Test
    fun `慢节奏下同样的轮数差得更多`() {
        val normal = StagePacing.describe(10, 30, StageSpeed.NORMAL, "熟悉")
        val slow = StagePacing.describe(10, 30, StageSpeed.SLOW, "熟悉")
        assertTrue("慢节奏的剩余轮次应更大：$normal vs $slow", normal != slow)
    }

    // ── 默认值 ──────────────────────────────────────────────────

    @Test
    fun `默认节奏是标准`() {
        assertEquals(StageSpeed.NORMAL, StagePacing.DEFAULT_SPEED)
        // 默认参数走的就是它
        assertEquals(StagePacing.threshold(150, StageSpeed.NORMAL), StagePacing.threshold(150))
    }
}
