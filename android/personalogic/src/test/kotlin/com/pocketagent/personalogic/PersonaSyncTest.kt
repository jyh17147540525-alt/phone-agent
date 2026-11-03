package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **记忆使者的规矩** —— 这是不可逆操作，闸门要比功能严。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"改坏了还写进历史"
 * ═══════════════════════════════════════════════════════════════
 *
 * 改写历史是**不可逆**的。模型翻车有无数种方式，
 * 而每一种的代价都是"用户永远失去那句话"。
 *
 * ⇒ 原则：**没改成比改坏了强**。
 */
class PersonaSyncTest {

    // ═══════════════════════════════════════════════════════════
    //  ★ 哪些句子该改
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `含旧名字的句子该改`() {
        assertTrue(
            PersonaSync.needsRewrite("小雨今天不太想说话", listOf("小雨")),
        )
    }

    @Test
    fun `不含旧设定的句子不该动`() {
        // ⚠️ 全量重写既贵又会把没问题的句子改走味
        assertFalse(
            PersonaSync.needsRewrite("嗯，我知道了", listOf("小雨", "哥哥")),
        )
    }

    @Test
    fun `没有旧标记时一句都不改`() {
        assertFalse(PersonaSync.needsRewrite("随便什么", emptyList()))
    }

    @Test
    fun `空文本不改`() {
        assertFalse(PersonaSync.needsRewrite("", listOf("小雨")))
    }

    @Test
    fun `单字不算标记`() {
        // ⚠️ "你""我"这种到处都有，当标记会让每句都被判成"要改"
        val m = PersonaSync.markersOf(selfName = "你", addressStyle = "我", relationship = "")
        assertTrue("单字应当被过滤掉：$m", m.isEmpty())
    }

    @Test
    fun `标记取名字称谓与关系`() {
        val m = PersonaSync.markersOf(
            selfName = "小雨",
            addressStyle = "哥哥",
            relationship = "青梅竹马",
        )
        assertTrue("小雨" in m)
        assertTrue("哥哥" in m)
        assertTrue("青梅竹马" in m)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 闸门：翻车的六种形态
    // ═══════════════════════════════════════════════════════════

    private fun keep(r: PersonaSync.Verdict): Boolean = r is PersonaSync.Verdict.Keep

    @Test
    fun `正常改写会被接受`() {
        val r = PersonaSync.accept(
            original = "小雨今天不太想说话",
            rewritten = "阿岚今天不太想说话",
        )
        assertTrue(r is PersonaSync.Verdict.Accept)
        assertEquals("阿岚今天不太想说话", (r as PersonaSync.Verdict.Accept).text)
    }

    @Test
    fun `空返回保留原文`() {
        assertTrue(keep(PersonaSync.accept("原句", "")))
        assertTrue(keep(PersonaSync.accept("原句", "   ")))
    }

    @Test
    fun `膨胀成一段会保留原文`() {
        // ⚠️ 模型最爱犯的错：以为在"丰富细节"
        val inflated = "小雨今天不太想说话".repeat(20)
        assertTrue(keep(PersonaSync.accept("小雨今天不太想说话", inflated)))
    }

    @Test
    fun `缩得太短会保留原文`() {
        assertTrue(keep(PersonaSync.accept("这是一句相当长的话，说了一件事", "哦")))
    }

    @Test
    fun `带前言会保留原文`() {
        // ⚠️ 前言会被写进历史，看起来像角色在自言自语
        for (p in listOf("这是改写后的：阿岚不太想说话", "改写后：阿岚不太想说话", "好的，阿岚不太想说话")) {
            assertTrue("应当拒绝：$p", keep(PersonaSync.accept("小雨不太想说话", p)))
        }
    }

    @Test
    fun `整体被引号包住会保留原文`() {
        assertTrue(keep(PersonaSync.accept("小雨不太想说话", "\"阿岚不太想说话\"")))
        assertTrue(keep(PersonaSync.accept("小雨不太想说话", "「阿岚不太想说话」")))
    }

    @Test
    fun `原样返回会被接受`() {
        // "本来就不冲突就原样返回" —— 这是合法结果，不算失败
        val r = PersonaSync.accept("嗯，我知道了", "嗯，我知道了")
        assertTrue(r is PersonaSync.Verdict.Accept)
    }

    // ═══════════════════════════════════════════════════════════
    //  提示词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `提示词包含新旧设定与原文`() {
        val p = PersonaSync.buildPrompt(
            oldSummary = "她叫小雨",
            newSummary = "她叫阿岚",
            text = "小雨今天不太想说话",
        )
        assertTrue("旧设定" in p && p.contains("小雨"))
        assertTrue("新设定" in p && p.contains("阿岚"))
        assertTrue("要改的话" in p)
    }

    @Test
    fun `提示词明确要求只改冲突部分`() {
        val p = PersonaSync.buildPrompt("旧", "新", "原文")
        assertTrue("应当要求只改冲突部分", p.contains("只改") && p.contains("其余一个字都不要动"))
        assertTrue("应当要求不写解释", p.contains("不要") && p.contains("解释"))
    }

    @Test
    fun `提示词要求原样返回 当不冲突时`() {
        val p = PersonaSync.buildPrompt("旧", "新", "原文")
        assertTrue(p.contains("原样返回"))
    }
}
