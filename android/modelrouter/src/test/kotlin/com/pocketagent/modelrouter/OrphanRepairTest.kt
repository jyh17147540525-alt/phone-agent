package com.pocketagent.modelrouter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 孤儿模型配置的改绑判断。
 *
 * 背景见 `OrphanRepair` 的注释：分身实例里模型全绑着已删的 Key，界面对应
 * 的却是"回复里外文太多"。这里钉死的是"什么时候可以动手"。
 */
class OrphanRepairTest {

    // ══════════════════════════════════════════════════════════
    //  ★ 恰好一条 → 就是它
    // ══════════════════════════════════════════════════════════

    @Test
    fun `只有一条对话用凭据时，它就是改绑目标`() {
        assertEquals("key-1", OrphanRepair.adoptionTarget(listOf("key-1")))
    }

    @Test
    fun `同一个 id 重复出现仍算一条`() {
        assertEquals(
            "key-1",
            OrphanRepair.adoptionTarget(listOf("key-1", "key-1", "key-1")),
        )
    }

    // ══════════════════════════════════════════════════════════
    //  ★ 不猜
    // ══════════════════════════════════════════════════════════

    @Test
    fun `一条对话用凭据都没有时，不动`() {
        assertNull(OrphanRepair.adoptionTarget(emptyList()))
        assertFalse(OrphanRepair.ambiguous(emptyList()))
    }

    @Test
    fun `有两条对话用凭据时，不猜该接哪一条`() {
        assertNull(OrphanRepair.adoptionTarget(listOf("key-1", "key-2")))
        assertTrue(OrphanRepair.ambiguous(listOf("key-1", "key-2")))
    }

    @Test
    fun `两条凭据时不算'没什么可修'，是'不敢猜'`() {
        // 这条区分很重要：日志里要能看出到底是"没有凭据"还是"有多条"，
        // 否则下次排查又是对着空白日志猜。
        assertTrue(OrphanRepair.ambiguous(listOf("openai", "anthropic")))
        assertFalse(OrphanRepair.ambiguous(listOf("openai")))
    }

    // ══════════════════════════════════════════════════════════
    //  ★ 空白 id 不算数
    // ══════════════════════════════════════════════════════════

    @Test
    fun `空白 id 会被忽略，不算第二条凭据`() {
        assertEquals("key-1", OrphanRepair.adoptionTarget(listOf("key-1", "  ", "")))
    }

    @Test
    fun `全是空白时等于没有凭据`() {
        assertNull(OrphanRepair.adoptionTarget(listOf("", " ")))
        assertFalse(OrphanRepair.ambiguous(listOf("", " ")))
    }
}
