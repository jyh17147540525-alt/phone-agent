package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelTier] 的档位选择逻辑。
 *
 * 这两个函数是降级链的地基 —— 它们错了，所有缺档位场景都会选错模型，
 * 而选错模型的表现是"任务失败"或"莫名很贵"，**都不报错**。
 */
class ModelTierTest {

    // ── atLeast：取不低于给定档位的最低可用档 ─────────────────────

    @Test
    fun `atLeast 精确命中时返回该档位`() {
        val result = ModelTier.atLeast(ModelTier.STANDARD, listOf(ModelTier.STANDARD))

        assertEquals(ModelTier.STANDARD, result)
    }

    @Test
    fun `atLeast 只有更高档位时返回最低的那个`() {
        val result = ModelTier.atLeast(
            ModelTier.LIGHT,
            listOf(ModelTier.STANDARD, ModelTier.HEAVY),
        )

        assertEquals(ModelTier.STANDARD, result)
    }

    @Test
    fun `atLeast 只有更低档位时返回 null`() {
        val result = ModelTier.atLeast(ModelTier.HEAVY, listOf(ModelTier.LIGHT))

        assertNull(result)
    }

    @Test
    fun `atLeast 空集合返回 null`() {
        assertNull(ModelTier.atLeast(ModelTier.LIGHT, emptyList()))
    }

    // ── atMost：取不高于给定档位的最高可用档 ─────────────────────

    @Test
    fun `atMost 精确命中时返回该档位`() {
        val result = ModelTier.atMost(ModelTier.STANDARD, listOf(ModelTier.STANDARD))

        assertEquals(ModelTier.STANDARD, result)
    }

    @Test
    fun `atMost 只有更低档位时返回最高的那个`() {
        val result = ModelTier.atMost(
            ModelTier.HEAVY,
            listOf(ModelTier.LIGHT, ModelTier.STANDARD),
        )

        assertEquals(ModelTier.STANDARD, result)
    }

    @Test
    fun `atMost 只有更高档位时返回 null`() {
        assertNull(ModelTier.atMost(ModelTier.LIGHT, listOf(ModelTier.HEAVY)))
    }

    // ── rank 顺序 ───────────────────────────────────────────────

    @Test
    fun `档位顺序是 LIGHT 小于 STANDARD 小于 HEAVY`() {
        assertTrue(ModelTier.LIGHT.rank < ModelTier.STANDARD.rank)
        assertTrue(ModelTier.STANDARD.rank < ModelTier.HEAVY.rank)
    }
}
