package com.pocketagent.personalogic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **认可的检测** —— 宁可漏，不要误判。
 *
 * ⚠️ 与 `FrictionLevel` 的取向**相反**：
 *    那边宁重不轻（吵架要吵起来才有戏），
 *    这边宁漏不误（认错夸奖会让角色变得谄媚）。
 */
class PraiseSignalTest {

    @Test
    fun `明确的夸奖算`() {
        for (s in listOf(
            "你真好", "太好了，谢谢", "谢谢你", "说得对", "有你真好",
            "你好厉害", "这回答太棒了", "舒服多了", "你最懂我",
        )) {
            assertTrue("「$s」应当算认可", PraiseSignal.isPraise(s))
        }
    }

    @Test
    fun `问句不算`() {
        // ⚠️ 问句里的"好""对"通常是提问
        for (s in listOf("这样好吗？", "你说得对吗", "厉害吗", "谢谢？")) {
            assertFalse("「$s」是提问，不算认可", PraiseSignal.isPraise(s))
        }
    }

    @Test
    fun `否定式不算`() {
        // ══════════════════════════════════════════════════════
        //  ★ 最重要的一条
        // ══════════════════════════════════════════════════════
        // 把抱怨读成夸奖会让激素往**反方向**动，
        // 表现为"被骂了反而更高兴" —— 明显的失真。
        for (s in listOf("不太好", "不怎么样", "一点也不厉害", "没帮我大忙", "别谢谢了")) {
            assertFalse("「$s」是否定，不算认可", PraiseSignal.isPraise(s))
        }
    }

    @Test
    fun `否定词只影响它自己那一小段`() {
        // ⚠️ 一刀切会把「不太好，但谢谢你」整句判否，
        //    而那句确实包含认可。
        assertTrue(
            "后半句是明确的认可",
            PraiseSignal.isPraise("不太好，但谢谢你"),
        )
    }

    @Test
    fun `空串与闲聊不算`() {
        for (s in listOf("", "   ", "今天天气不错", "帮我打开设置", "嗯")) {
            assertFalse("「$s」不该算认可", PraiseSignal.isPraise(s))
        }
    }
}