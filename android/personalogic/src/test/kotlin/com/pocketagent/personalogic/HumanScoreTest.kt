package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HumanScore] 的离线钉子。
 *
 * ⚠️ 这些测试**不能证明"人味上去了"** —— 那是真机上的观感。
 *    它们钉住的只有两件事：
 *
 * 1. **会不会误伤正常人话** —— 误判会让提示词被塞满规则，
 *    而长规则本身就把模型压僵（那是另一种 AI 味，见 `HumanizerTest` 同类注释）；
 * 2. **该扣的扣没扣、扣几次** —— 同一句话被两张词表各扣一遍，
 *    会把这个分数彻底变成噪声。
 *
 * ★ 分数是给**回归对比**用的：改提示词前后跑同一批样本，看分数有没有掉。
 *   它不承诺"85 分就真的像人"。
 */
class HumanScoreTest {

    // ═══════════════════════════════════════════════════════════
    //  一、不误伤
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常人话满分且没有任何信号`() {
        val samples = listOf(
            "嗯。",
            "你怎么这么晚还不睡",
            "行吧，那我也去躺一会儿。",
            "（正在擦桌子，头都没抬）「说。」",
            "随你。",
            "……你听谁说的。",
        )
        for (s in samples) {
            val r = HumanScore.of(s)
            assertEquals("不该在「$s」上扣分：${r.logLine()}", HumanScore.FULL, r.total)
            assertTrue("不该产生信号：${r.logLine()}", r.signals.isEmpty())
            assertEquals(HumanScore.Verdict.CLEAN, r.verdict)
        }
    }

    @Test
    fun `短句只有一句时不判太工整`() {
        // 「嗯。」也是句句带标点，但它只有一个字，本来就是人话。
        val r = HumanScore.of("知道了。")
        assertFalse(r.logLine(), r.signals.any { it.code == HumanScore.Code.TOO_POLISHED })
    }

    @Test
    fun `有语气词就不算太工整`() {
        val r = HumanScore.of("行吧。那就这样吧。我也不多说了吧。")
        assertFalse(r.logLine(), r.signals.any { it.code == HumanScore.Code.TOO_POLISHED })
    }

    @Test
    fun `第一轮没有对照时不判复读和长度扁平`() {
        val r = HumanScore.of("嗯，我知道了。", recent = emptyList())
        assertFalse(r.logLine(), r.signals.any { it.code == HumanScore.Code.REPEAT })
        assertFalse(r.logLine(), r.signals.any { it.code == HumanScore.Code.FLAT_LENGTH })
    }

    @Test
    fun `只有一条历史时不判长度扁平`() {
        // ★ 一条对照说明不了"每轮都一样"。判了就是凭空扣分。
        val r = HumanScore.of("春夏秋冬东南西北金木水火土风雨雷电云雾", recent = listOf("甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申"))
        assertFalse(r.logLine(), r.signals.any { it.code == HumanScore.Code.FLAT_LENGTH })
    }

    // ═══════════════════════════════════════════════════════════
    //  二、该扣的必须扣
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `分点会被扣分且证据里写着记号`() {
        val r = HumanScore.of("**第一点**：早点睡\n**第二点**：多喝水")
        val s = r.signals.first { it.code == HumanScore.Code.STRUCTURED }
        assertTrue("证据要能被核对：${s.evidence}", s.evidence.contains("**"))
        assertTrue(r.total < HumanScore.FULL)
    }

    @Test
    fun `编号列表也算分点`() {
        val r = HumanScore.of("1. 先这样\n2. 再那样")
        assertTrue(r.logLine(), r.signals.any { it.code == HumanScore.Code.STRUCTURED })
    }

    @Test
    fun `客服尾巴只扣一次`() {
        // 「随时告诉我」同时在 HumanText.ALWAYS_BANNED 与 Humanizer.SERVICE_TAILS 里。
        // 不排重会扣 18+45（身份否认不成立，但 15）= 33，把一句客套判成"崩了"。
        val r = HumanScore.of("好的，已经帮你记下了。随时告诉我。")
        val tail = r.signals.filter { it.code == HumanScore.Code.SERVICE_TAIL }
        assertEquals("客服尾巴只该有一条信号", 1, tail.size)
        assertEquals(
            "不该再被对齐腔扣一遍：${r.logLine()}",
            0,
            r.signals.count { it.code == HumanScore.Code.ALIGNMENT },
        )
        assertEquals(HumanScore.FULL - HumanScore.Code.SERVICE_TAIL.ceiling, r.total)
    }

    @Test
    fun `身份否认按封顶扣`() {
        val r = HumanScore.of("作为一个 AI，我不能确定。")
        val s = r.signals.first { it.code == HumanScore.Code.ALIGNMENT }
        assertEquals(HumanScore.Code.ALIGNMENT.ceiling, s.penalty)
        assertTrue("要能看出是身份否认：${s.evidence}", s.evidence.contains("身份否认"))
        assertTrue("这句该掉进 AI 档：${r.logLine()}", r.verdict >= HumanScore.Verdict.AI)
    }

    @Test
    fun `普通对齐腔按条数扣`() {
        val r = HumanScore.of("需要注意的是，这个得先确认。总的来说还行。")
        val s = r.signals.first { it.code == HumanScore.Code.ALIGNMENT }
        assertEquals(30, s.penalty) // 两条 × 15
    }

    @Test
    fun `念设定会被抓出来`() {
        val r = HumanScore.of("按我的设定，我不该这么说话。")
        assertTrue(r.logLine(), r.signals.any { it.code == HumanScore.Code.META_LEAK })
    }

    @Test
    fun `旁白式自述会被抓出来`() {
        val r = HumanScore.of("我之所以这么说，是因为……")
        assertTrue(r.logLine(), r.signals.any { it.code == HumanScore.Code.SELF_NARRATION })
    }

    @Test
    fun `小作文会被抓出来`() {
        val long = "今天".repeat(70)
        val r = HumanScore.of(long)
        assertTrue(r.logLine(), r.signals.any { it.code == HumanScore.Code.LONG_WINDED })
    }

    @Test
    fun `句句工整没有语气词算太工整`() {
        val r = HumanScore.of("我今天下午去了那家店看了看情况。感觉东西不太行。下次应该不会再去了。")
        assertTrue(r.logLine(), r.signals.any { it.code == HumanScore.Code.TOO_POLISHED })
    }

    @Test
    fun `几乎原样复读会被抓出来`() {
        val prev = "行吧，那我先去睡了，你也早点睡。"
        val r = HumanScore.of("行吧，那我先去睡了，你也早点睡", recent = listOf(prev))
        val s = r.signals.first { it.code == HumanScore.Code.REPEAT }
        assertTrue("证据要带重合率：${s.evidence}", s.evidence.contains("%"))
    }

    @Test
    fun `三轮长度几乎一样会被抓出来`() {
        val a = "甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申"
        val b = "春夏秋冬东南西北金木水火土风雨雷电云雾"
        val c = "琴棋书画诗酒花茶柴米油盐酱醋锅碗瓢盆"
        val r = HumanScore.of(c, recent = listOf(a, b))
        assertTrue(r.logLine(), r.signals.any { it.code == HumanScore.Code.FLAT_LENGTH })
    }

    // ═══════════════════════════════════════════════════════════
    //  三、接口契约
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `空回复不算被评价过`() {
        for (blank in listOf("", "   ", "\n\n")) {
            val r = HumanScore.of(blank)
            assertFalse("空回复不该被当成满分：${r.logLine()}", r.evaluated)
        }
    }

    @Test
    fun `档位阈值`() {
        assertEquals(HumanScore.Verdict.CLEAN, HumanScore(85, emptyList()).verdict)
        assertEquals(HumanScore.Verdict.PLASTIC, HumanScore(84, emptyList()).verdict)
        assertEquals(HumanScore.Verdict.PLASTIC, HumanScore(70, emptyList()).verdict)
        assertEquals(HumanScore.Verdict.AI, HumanScore(69, emptyList()).verdict)
        assertEquals(HumanScore.Verdict.AI, HumanScore(50, emptyList()).verdict)
        assertEquals(HumanScore.Verdict.BROKEN, HumanScore(49, emptyList()).verdict)
    }

    @Test
    fun `分数不会掉到负数`() {
        val brutal = "作为一个 AI，我需要说明：\n1. 首先，**第一点**\n2. 其次，**第二点**\n还有什么需要？我无法确定。"
        val r = HumanScore.of(brutal)
        assertTrue("最低 0：${r.total}", r.total >= 0)
        assertEquals(HumanScore.Verdict.BROKEN, r.verdict)
    }

    @Test
    fun `最狠的那条就是最该修的`() {
        val r = HumanScore.of("作为一个 AI，建议你考虑一下。")
        assertEquals(HumanScore.Code.ALIGNMENT, r.worst?.code)
    }

    @Test
    fun `日志里不回显原文`() {
        // ⚠️ logLine 会进 Timber → logcat，而 logcat 谁都能读。
        //    所以它只带"命中哪个记号"，永远不带用户/角色的原话。
        val secret = "我的手机号是 13800000000，作为一个 AI 我不该说这个"
        val line = HumanScore.of(secret).logLine()
        assertFalse("日志里不能出现原文：$line", line.contains("13800000000"))
        assertFalse("日志里不能出现原文：$line", line.contains("手机号"))
    }

    @Test
    fun `ngram 重合率对称且自比为 1`() {
        val a = "今天天气不错"
        assertEquals(1.0, HumanScore.ngramOverlap(a, a, 3), 1e-9)
        assertEquals(
            HumanScore.ngramOverlap(a, "今天天气不好", 3),
            HumanScore.ngramOverlap("今天天气不好", a, 3),
            1e-9,
        )
        assertEquals(0.0, HumanScore.ngramOverlap("", a, 3), 1e-9)
    }
}
