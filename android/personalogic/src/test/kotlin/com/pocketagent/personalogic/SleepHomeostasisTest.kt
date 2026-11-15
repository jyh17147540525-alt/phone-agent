package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **睡眠稳态的数学**。
 *
 * 这个文件里没有"角色"，只有曲线 —— 因为 [SleepHomeostasis] 是可以脱离
 * 会话单独验算的：给定债和小时数，答案只有一个。
 *
 * 它防的是两类错误：
 * 1. **公式抄错**（τ 用成了 8.2、exp 的符号反了）；
 * 2. **忘了夹取**（压力超过 1 之后把清醒度压成负数）。
 */
class SleepHomeostasisTest {

    // ═══════════════════════════════════════════════════════════
    //  压力映射
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `睡眠压力按上限线性归一`() {
        assertEquals(0.0, SleepHomeostasis.pressure(0.0), 1e-9)
        assertEquals(0.5, SleepHomeostasis.pressure(Physiology.MAX_DEBT / 2), 1e-9)
        assertEquals(1.0, SleepHomeostasis.pressure(Physiology.MAX_DEBT), 1e-9)
    }

    @Test
    fun `压力夹在 0 到 1`() {
        assertEquals("超过上限也只是拉满", 1.0, SleepHomeostasis.pressure(Physiology.MAX_DEBT * 3), 1e-9)
        assertEquals("负的债不该产生压力", 0.0, SleepHomeostasis.pressure(-2.0), 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  恢复曲线
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `睡满一个时间常数 剩三分之一`() {
        // τ=4.2h 的定义就是这个：S(t) = S0·e^(-t/τ)
        val left = SleepHomeostasis.debtAfterRest(6.0, SleepHomeostasis.TAU_SLEEP_HOURS)
        assertEquals(6.0 / kotlin.math.E, left, 1e-6)
    }

    @Test
    fun `补觉的边际收益递减`() {
        // 这不是"数学性质"那么无所谓 —— 它决定了角色怎么劝人睡觉：
        // 第一个四小时能挽回大半，第二个只挽回一点。
        val after4 = SleepHomeostasis.debtAfterRest(8.0, 4.0)
        val first = 8.0 - after4
        val second = after4 - SleepHomeostasis.debtAfterRest(8.0, 8.0)
        assertTrue("第一个四小时($first) 应当比第二个($second) 还得多", first > second)
    }

    @Test
    fun `睡很久之后就归零 不会留尾数`() {
        assertEquals(0.0, SleepHomeostasis.debtAfterRest(1.0, 48.0), 1e-9)
    }

    @Test
    fun `不睡就不恢复`() {
        assertEquals(5.0, SleepHomeostasis.debtAfterRest(5.0, 0.0), 1e-9)
        assertEquals("时钟回拨不制造恢复", 5.0, SleepHomeostasis.debtAfterRest(5.0, -3.0), 1e-9)
    }

    // ═══════════════════════════════════════════════════════════
    //  乘法削弱
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `睡眠压力是乘法削弱`() {
        // 同一份压力作用在两个时段上：**相对**损失一样，**绝对**损失随当时高低而变。
        // 这就是"乘法"唯一的含义，也是这次改动的全部理由。
        val peak = SleepHomeostasis.alertness(0.95, 0.5)
        val trough = SleepHomeostasis.alertness(0.42, 0.5)
        assertEquals(0.95 * 0.625, peak, 1e-9)
        assertEquals(0.42 * 0.625, trough, 1e-9)
        assertTrue("低谷掉的绝对量更小，但比例一样", 0.95 - peak > 0.42 - trough)
    }

    @Test
    fun `压力拉满也只削掉四分之三`() {
        assertEquals(0.25, SleepHomeostasis.alertness(1.0, 1.0), 1e-9)
        assertEquals("压不穿 0", 0.0, SleepHomeostasis.alertness(0.0, 1.0), 1e-9)
        assertEquals("超过 1 的压力夹住", 0.25, SleepHomeostasis.alertness(1.0, 2.0), 1e-9)
    }

    @Test
    fun `没有债的时候曲线原样返回`() {
        // 保底：不欠睡的人不该被这套公式改动一点点
        for (c in listOf(0.10, 0.42, 0.78, 0.95)) {
            assertEquals(c, SleepHomeostasis.alertness(c, 0.0), 1e-9)
        }
    }
}
