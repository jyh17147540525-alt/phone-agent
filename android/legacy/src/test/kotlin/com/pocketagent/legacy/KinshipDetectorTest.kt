package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 亲人识别的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 为什么这块测试最要紧
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是整条辅助线的入口。判错的两种方向都很难看：
 *
 *  - **过宽**：用户写"我想要个温柔点的助手"，被判定是在描述逝者，
 *    弹出一个"未亡人"配置页 —— 他会莫名其妙，甚至被冒犯。
 *  - **过窄**：用户明明写了"想再和我妈说说话"，却没识别出来 ——
 *    那个他真正需要的东西永远不会出现。
 *
 * 所以测试的重点是**两侧都要**：该认的必须认出来，不该认的必须保持沉默。
 */
class KinshipDetectorTest {

    // ═══════════════════════════════════════════════════════════
    //  该识别的
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `关系词加离开语境判为确定`() {
        val cases = listOf(
            "我妈妈去年走了，我想再听听她的声音" to RelationKind.PARENT,
            "我爱人不在了，每天都想跟她说说话" to RelationKind.PARTNER,
            "外婆去世三年了，还是会想起她做的饭" to RelationKind.GRANDPARENT,
            "哥哥离开以后，家里就安静了" to RelationKind.SIBLING,
            "女儿走了，我一直没走出来" to RelationKind.CHILD,
        )
        for ((text, expected) in cases) {
            val s = KinshipDetector.detect(text)
            assertTrue("「$text」应当判为确定，实际是 $s", s is LegacySignal.Likely)
            assertEquals(expected, (s as LegacySignal.Likely).relation)
        }
    }

    @Test
    fun `明确写出名字时要抽出来`() {
        val s = KinshipDetector.detect("我妈妈叫李秀兰，她去年走了")

        assertTrue(s is LegacySignal.Likely)
        assertEquals("李秀兰", (s as LegacySignal.Likely).displayName)
    }

    @Test
    fun `括号里的名字也要抽出来`() {
        val s = KinshipDetector.detect("我妈（王芳）不在了")
        assertEquals("王芳", (s as LegacySignal.Likely).displayName)
    }

    @Test
    fun `抽不到名字时留空而不是瞎猜`() {
        // ⚠️ 抽错名字比抽不到更糟 —— 用户会看到一个陌生的名字
        //    出现在本该是他母亲的位置上。
        val s = KinshipDetector.detect("我妈不在了")
        assertTrue(s is LegacySignal.Likely)
        assertNull("抽不到就该是 null", (s as LegacySignal.Likely).displayName)
    }

    @Test
    fun `名字不会抓到语境词`() {
        // "叫"后面紧跟语境词时不该当成名字
        val s = KinshipDetector.detect("我妈叫走了，我很难受")
        if (s is LegacySignal.Likely) {
            val name = s.displayName
            assertTrue(
                "不该把语境词当成名字：$name",
                name == null || name !in listOf("走了", "不在"),
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  不该识别的 —— 这部分同样重要
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `普通人格设定不该被识别`() {
        val cases = listOf(
            "我想要一个温柔、话不多的助手",
            "说话直接一点，别绕弯子",
            "你是个严谨的工程师，喜欢用数据说话",
            "请用轻松的语气和我聊天",
        )
        for (text in cases) {
            assertEquals(
                "「$text」是普通人格设定，不该被判为逝者",
                LegacySignal.None,
                KinshipDetector.detect(text),
            )
        }
    }

    @Test
    fun `只提到关系词但没离开语境时判为待确认`() {
        // ★ 这一档的存在让界面可以问一句，而不是直接判定。
        for (text in listOf(
            "我想要一个像我妈那样的助手",
            "说话像我奶奶一样慈祥",
        )) {
            val s = KinshipDetector.detect(text)
            assertTrue(
                "「$text」提到亲人但没说离开，应当是 Ambiguous，实际 $s",
                s is LegacySignal.Ambiguous,
            )
        }
    }

    @Test
    fun `只提到离开但没有关系词时不该识别`() {
        // ⚠️ "我养的猫走了" 不是人。它可能需要别的处理，
        //    但不该进「未亡人」。
        val cases = listOf(
            "我养的猫走了",
            "那家店不在了",
            "这个功能已经离开了",
        )
        for (text in cases) {
            assertEquals(
                "「$text」没有关系词，不该被判为逝者",
                LegacySignal.None,
                KinshipDetector.detect(text),
            )
        }
    }

    @Test
    fun `空输入不识别`() {
        assertEquals(LegacySignal.None, KinshipDetector.detect(""))
        assertEquals(LegacySignal.None, KinshipDetector.detect("   "))
    }

    // ═══════════════════════════════════════════════════════════
    //  建议文案
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `建议文案要克制`() {
        val s = KinshipDetector.detect("我妈不在了") as LegacySignal.Likely
        val msg = KinshipDetector.suggestion(s).message

        // ★ 措辞原则：不用感叹号、不说"节哀"、不预设情绪。
        assertTrue("不该用感叹号：$msg", '！' !in msg && '!' !in msg)
        assertTrue("不该说节哀：$msg", "节哀" !in msg)
        // 必须给用户**拒绝的出口**
        assertTrue("必须让用户能忽略这条：$msg", "忽略" in msg || "不想" in msg)
    }

    @Test
    fun `每条确定识别都必须给出依据`() {
        // ⚠️ 依据是让用户确认"我理解对了吗"的唯一线索。
        //    没有依据的判定，用户只能盲信或盲拒。
        val s = KinshipDetector.detect("外婆去世三年了") as LegacySignal.Likely
        assertTrue("必须有依据", s.evidence.isNotEmpty())
        assertTrue("依据要提到具体命中的词：${s.evidence}", s.evidence.size >= 2)
    }
}

/**
 * 哀伤分期与边界提示的测试。
 */
class GriefPhaseTest {

    @Test
    fun `分期按天数推进`() {
        assertEquals(GriefPhase.EARLY, GriefPhase.of(0))
        assertEquals(GriefPhase.EARLY, GriefPhase.of(30))
        assertEquals(GriefPhase.MIDDLE, GriefPhase.of(31))
        assertEquals(GriefPhase.MIDDLE, GriefPhase.of(365))
        assertEquals(GriefPhase.LONG_TERM, GriefPhase.of(366))
    }

    @Test
    fun `时钟回拨时按最初处理`() {
        // ⚠️ 负数天数（时钟回拨 / 填了未来日期）按 EARLY 处理是**更保守**的选择：
        //    宁可把提示做得更轻，也不要在最脆弱的时候说重话。
        assertEquals(GriefPhase.EARLY, GriefPhase.of(-5))
    }

    @Test
    fun `最初日子的边界提示必须极轻`() {
        val note = LegacyBoundary.openingNote(GriefPhase.EARLY, "李秀兰")

        // ★ 这个阶段用户最脆弱，一句"这只是模拟"可能直接把人推走。
        //   所以措辞重点在"我用的是你给我的记忆"，而不是"我是假的"。
        assertTrue("不该出现「不是他/她是假的」这类直白否定：$note",
            "不是他" !in note && "假的" !in note && "AI" !in note)
        assertTrue("应当提到用了他/她的记忆：$note", "记忆" in note || "告诉我" in note)
    }

    @Test
    fun `长期必须开始引导`() {
        val note = LegacyBoundary.openingNote(GriefPhase.LONG_TERM, "李秀兰")
        assertTrue("长期应当开始引导找人说话：$note",
            "说话" in note || "人" in note)
    }

    @Test
    fun `最初的日子不该问有没有和活人说过话`() {
        // ⚠️ 在最初的日子里说这种话是残忍的 —— 那时候每天来是正常的。
        for (days in listOf(1, 7, 30, 100)) {
            assertNull(
                "第 $days 天不该说这种话",
                LegacyBoundary.gentleCheckIn(GriefPhase.EARLY, days),
            )
        }
    }

    @Test
    fun `长期且高频才主动关心`() {
        assertNull(
            "短期高频不该说",
            LegacyBoundary.gentleCheckIn(GriefPhase.LONG_TERM, 10),
        )
        assertNotNull(
            "长期且高频才说",
            LegacyBoundary.gentleCheckIn(GriefPhase.LONG_TERM, 60),
        )
    }
}
