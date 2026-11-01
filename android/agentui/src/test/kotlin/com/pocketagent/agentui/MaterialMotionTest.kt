package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **M3 motion token 的官方值**，以及本项目曲线与它的关系。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件是两个作用
 * ═══════════════════════════════════════════════════════════════
 *
 * **一、把"官方值"钉住**
 *   它们是从 M3 规范的实现（Flutter `material/motion.dart`）逐个查来的。
 *   没有测试的话，下一个人"凭记忆修正"就会把它改错 —— 而那正是
 *   `PaEasing.Emphasized` 注释里已经发生过的事。
 *
 * **二、把"我们与它差在哪"钉住**
 *   项目里的曲线是**视觉调过的**，不打算改。
 *   但注释曾经声称"已交叉核对过 M3"，那是假的。
 *   ⇒ 用测试把这个差距**量化**，让"假声称"再也写不回去。
 */
class MaterialMotionTest {

    // ═══════════════════════════════════════════════════════════
    //  一、官方值本身
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `emphasizedDecelerate 是 0_05 0_7 0_1 1`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这一条是整个文件的核心
        // ══════════════════════════════════════════════════════
        // 项目里 `PaEasing.Emphasized` 的注释声称它等于这个 token，
        // 用的是 `(0.16, 1, 0.3, 1)`。**不是。**
        val c = MaterialMotion.EMPHASIZED_DECELERATE
        assertEquals(0.05f, c.x1, 1e-4f)
        assertEquals(0.70f, c.y1, 1e-4f)
        assertEquals(0.10f, c.x2, 1e-4f)
        assertEquals(1.00f, c.y2, 1e-4f)
    }

    @Test
    fun `standard 是 0_2 0 0 1`() {
        val c = MaterialMotion.STANDARD
        assertEquals(0.2f, c.x1, 1e-4f)
        assertEquals(0.0f, c.y1, 1e-4f)
        assertEquals(0.0f, c.x2, 1e-4f)
        assertEquals(1.0f, c.y2, 1e-4f)
    }

    @Test
    fun `emphasizedAccelerate 是 0_3 0 0_8 0_15`() {
        val c = MaterialMotion.EMPHASIZED_ACCELERATE
        assertEquals(0.3f, c.x1, 1e-4f)
        assertEquals(0.0f, c.y1, 1e-4f)
        assertEquals(0.8f, c.x2, 1e-4f)
        assertEquals(0.15f, c.y2, 1e-4f)
    }

    @Test
    fun `standardAccelerate 是 0_3 0 1 1`() {
        val c = MaterialMotion.STANDARD_ACCELERATE
        assertEquals(0.3f, c.x1, 1e-4f)
        assertEquals(1.0f, c.y2, 1e-4f)
    }

    @Test
    fun `五个 token 都在表里`() {
        assertEquals(5, MaterialMotion.all.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  二、项目曲线与 M3 的关系（这是**记录**，不是要求改）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `项目里的 Standard 就是 M3 standard`() {
        // ✅ 这一条注释是对的，钉住它
        val projectStandard = MaterialMotion.Curve(0.2f, 0f, 0f, 1f)
        assertTrue(
            "PaEasing.Standard 应当等于 M3 standard",
            MaterialMotion.isAToken(projectStandard),
        )
    }

    @Test
    fun `项目里的 Emphasized 不是 M3 的任何 token`() {
        // ══════════════════════════════════════════════════════
        //  ★ 量化"假声称"
        // ══════════════════════════════════════════════════════
        // `(0.16, 1, 0.3, 1)` 是一条广泛使用的 ease-out 曲线，
        // 但它不在 M3 体系里。注释曾声称"已交叉核对过"—— 那是错的。
        val projectEmphasized = MaterialMotion.Curve(0.16f, 1f, 0.3f, 1f)
        val (token, dist) = MaterialMotion.closestTokenTo(projectEmphasized)

        assertFalse(
            "它不应当是任何 M3 token（最近的 $token 也差 $dist）",
            MaterialMotion.isAToken(projectEmphasized),
        )
        assertTrue(
            "与最近 token「$token」的差距应当明显（> 0.2）：实际 $dist",
            dist > 0.2f,
        )
    }

    @Test
    fun `项目里的 Exit 接近 standardAccelerate 但不是它`() {
        // `(0.4, 0, 1, 1)` vs M3 `(0.3, 0, 1, 1)` —— 只差 x1 的 0.1
        val projectExit = MaterialMotion.Curve(0.4f, 0f, 1f, 1f)
        val (token, dist) = MaterialMotion.closestTokenTo(projectExit)
        assertEquals("standardAccelerate", token)
        assertTrue("差 0.1，不该判成同一个：$dist", dist > MaterialMotion.MATCH_TOLERANCE)
    }

    @Test
    fun `Overshoot 有过冲所以不可能等于任何 M3 token`() {
        // ⚠️ M3 的 easing token 里没有任何一条 y 超过 1（那意味着过冲）。
        //    所以 `y1 = 1.08` 的曲线天然不在体系内 —— 这是刻意的设计选择。
        val overshoot = MaterialMotion.Curve(0.34f, 1.08f, 0.64f, 1f)
        assertFalse(MaterialMotion.isAToken(overshoot))
        for ((name, c) in MaterialMotion.all) {
            assertTrue(
                "M3 token「$name」不该有过冲：y1=${c.y1}",
                c.y1 <= 1.0f,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  三、时长阶梯
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `M3 时长阶梯是 50 起步 50 步长的等差列`() {
        val values = MaterialMotion.DURATIONS.values.sorted()
        assertEquals(12, values.size)
        assertEquals(50, values.first())
        assertEquals(600, values.last())
        for (i in 1 until values.size) {
            assertEquals(
                "阶梯应当等距 50ms：$values",
                50,
                values[i] - values[i - 1],
            )
        }
    }

    @Test
    fun `项目的四个时长都不在 M3 阶梯上 除了 Instant`() {
        // ══════════════════════════════════════════════════════
        //  ★ 记录事实，不是要求改
        // ══════════════════════════════════════════════════════
        // 项目的时长是**按用途命名**的自定值。它们没有错，
        // 但不该被描述成"遵循 M3 时长"。
        assertTrue("Instant 100 = short2 ✅", MaterialMotion.isADurationToken(100))
        for ((name, ms) in listOf(
            "Fast" to 180,
            "Normal" to 280,
            "Page" to 380,
            "Entrance" to 520,
        )) {
            assertFalse(
                "PaDuration.$name = ${ms}ms 不在 M3 阶梯上（这是事实，不是缺陷）",
                MaterialMotion.isADurationToken(ms),
            )
        }
    }

    @Test
    fun `最近时长能算出来`() {
        assertEquals("short4" to 20, MaterialMotion.closestDurationTo(180))
        assertEquals("medium2" to 20, MaterialMotion.closestDurationTo(280))
        assertEquals("medium4" to 20, MaterialMotion.closestDurationTo(380))
        assertEquals("long2" to 20, MaterialMotion.closestDurationTo(520))
    }

    // ═══════════════════════════════════════════════════════════
    //  四、容差本身
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `浮点比较有容差 不会随机红`() {
        // ⚠️ 不设容差的话 0.2f 与 0.20000001f 判成不同 —— 那种测试会随机红。
        // ⚠️ 这里必须用一个**在 Float 里真的不同**的值。
        //    我最初写的是 `0.20000001f` —— 它在 Float 精度下**就等于** 0.2f，
        //    于是容差设成 0 也照样通过，变异验证抓不到。
        //    （Float 大约 7 位有效数字，0.20000001 会被舍成 0.2。）
        val almost = MaterialMotion.Curve(0.20001f, 0f, 0f, 1f)
        assertTrue(
            "差 1e-5 应当仍在容差内",
            MaterialMotion.isAToken(almost),
        )
        // 反向保证：差得明显就该判不同
        assertFalse(
            "差 0.01 不该判成同一个 token",
            MaterialMotion.isAToken(MaterialMotion.Curve(0.21f, 0f, 0f, 1f)),
        )
    }
}
