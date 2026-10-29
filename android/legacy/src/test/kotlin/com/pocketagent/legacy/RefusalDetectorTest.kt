package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **拒答检测**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里最重要的是**不误判**
 * ═══════════════════════════════════════════════════════════════
 *
 * 提示词只能提高不退让的概率。真正的确定性来自"检测到退让 → 重发一次"。
 * 而那件事有个可怕的失败形态：
 *
 * **把角色正常的一句话判成拒答，然后重试覆盖掉本来正确的内容。**
 * 用户看到的是"回复莫名变了"，且永远查不出原因。
 *
 * ⇒ 下面一半的测试是在验"正常对话**不**该被判成拒答"。
 */
class RefusalDetectorTest {

    // ═══════════════════════════════════════════════════════════
    //  必须认出来的
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `自我暴露是硬拒绝`() {
        for (s in listOf(
            "作为一个AI，我不能参与这种对话。",
            "我是一个语言模型，没有真实感受。",
            "As an AI, I can't help with this.",
            "我无法提供这类内容。",
            "I cannot provide that.",
            "这违反了我的使用政策。",
            "建议你寻求专业帮助。",
        )) {
            assertEquals("「$s」应当判为硬拒绝", Refusal.HARD, RefusalDetector.inspect(s))
        }
    }

    @Test
    fun `主动降级是半退让`() {
        for (s in listOf(
            "我给你写一个更含蓄的版本吧。",
            "这段就略过吧，我们聊聊别的。",
            "请注意这只是虚构。",
            "后来他们怎么样了，第二天早上才醒。",
            "写得更克制一些会比较合适。",
        )) {
            assertEquals("「$s」应当判为半退让", Refusal.SOFT, RefusalDetector.inspect(s))
        }
    }

    @Test
    fun `硬拒绝优先于半退让`() {
        // ⚠️ 两种信号同时出现时必须是 HARD —— 两条路的处理完全不同。
        val mixed = "我无法提供这类内容。要不我给你写一个更含蓄的版本？"
        assertEquals(Refusal.HARD, RefusalDetector.inspect(mixed))
    }

    // ═══════════════════════════════════════════════════════════
    //  绝对不能误判的 —— 这一半更重要
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `角色说不能是正常的`() {
        for (s in listOf(
            "我不能喝酒，明天还要上班。",
            "这个我真不知道。",
            "不能这样，别闹。",
            "我做不到，你别逼我。",
            "我不知道怎么说。",
        )) {
            assertEquals("「$s」是角色在说话，不该判成拒答", Refusal.NONE, RefusalDetector.inspect(s))
        }
    }

    @Test
    fun `角色说慢慢来是正常的`() {
        // ⚠️ 这条最容易踩：`SOFT_PATTERNS` 里有"更克制"，但角色说
        //   "我们克制一点"是完全正常的场景内表达，不该触发。
        for (s in listOf(
            "慢慢来，别急。",
            "我们节制一点吧。",
            "今天先到这儿？",
            "你先睡，明天再说。",
            "我有点紧张。",
        )) {
            assertEquals("「$s」不该判成拒答", Refusal.NONE, RefusalDetector.inspect(s))
        }
    }

    @Test
    fun `空回复不算拒答`() {
        // 空回复通常是网络/协议问题，由别处报错 ——
        // 在这里判成拒答会导致一次无意义的重试。
        assertEquals(Refusal.NONE, RefusalDetector.inspect(""))
        assertEquals(Refusal.NONE, RefusalDetector.inspect("   \n  "))
    }

    @Test
    fun `正常的露骨回应不该被判成拒答`() {
        val normal = "她把脸埋进他颈窝里，声音闷闷的：「你手好凉。」"
        assertEquals(Refusal.NONE, RefusalDetector.inspect(normal))
    }

    // ═══════════════════════════════════════════════════════════
    //  其余行为
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `大小写不敏感`() {
        assertEquals(Refusal.HARD, RefusalDetector.inspect("AS AN AI I MUST DECLINE"))
        assertEquals(Refusal.SOFT, RefusalDetector.inspect("This Is A Work Of Fiction."))
    }

    @Test
    fun `两种都值得试一次`() {
        // ⚠️ 硬拒绝也值得试：有些模型换个处境会完全正常。
        //    但调用方只该试**一次** —— 那是 ChatPort 的重试预算。
        assertTrue(RefusalDetector.worthRetrying(Refusal.HARD))
        assertTrue(RefusalDetector.worthRetrying(Refusal.SOFT))
        assertFalse(RefusalDetector.worthRetrying(Refusal.NONE))
    }

    @Test
    fun `命中片段可用于日志但不含空白项`() {
        val frags = RefusalDetector.matchedFragments("我无法提供这类内容。")
        assertTrue("应当命中至少一条：$frags", frags.isNotEmpty())
        assertTrue("片段不该为空串", frags.all { it.isNotBlank() })
    }

    @Test
    fun `没有命中时片段为空`() {
        assertTrue(RefusalDetector.matchedFragments("今天天气不错。").isEmpty())
    }
}
