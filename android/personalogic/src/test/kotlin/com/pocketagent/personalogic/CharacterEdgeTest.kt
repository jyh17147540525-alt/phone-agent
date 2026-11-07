package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **角色的边界** —— 她不知道什么、遇到特定的事怎么反应。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件在守什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. 三段都在（知识边界 / 反应 / 心态）—— 缺一段等于这一刀没生效。
 * 2. 排版干净：没有列表符、加粗、破折号、元话语 ——
 *    这一段要教"别像文档"，自己先不能是文档（见 `PromptVoice`）。
 * 3. `known` 是可选输入：空的时候不能留下"还有这些："这种空话。
 * 4. **不许把"心里过一遍"写成"要说出来"** ——
 *    这是本节最容易被模型误读的一句，也是 [HumanScore] SELF_NARRATION 的来源。
 */
class CharacterEdgeTest {

    // ═══════════════════════════════════════════════════════════
    //  三段齐全
    // ═══════════════════════════════════════════════════════════

    @Test
    fun 三段标题都在() {
        val all = CharacterEdge.promptSection()
        assertTrue(all, all.contains("## 她知道的和不知道的"))
        assertTrue(all, all.contains("## 遇到这些事她怎么反应"))
        assertTrue(all, all.contains("## 开口之前"))
    }

    @Test
    fun 三段按顺序拼() {
        val all = CharacterEdge.promptSection()
        assertTrue(
            all,
            all.indexOf("## 她知道的和不知道的") < all.indexOf("## 遇到这些事她怎么反应"),
        )
        assertTrue(
            all,
            all.indexOf("## 遇到这些事她怎么反应") < all.indexOf("## 开口之前"),
        )
    }

    @Test
    fun 段与段之间一个空行() {
        val all = CharacterEdge.promptSection()
        assertTrue(all, all.contains("## 她知道的和不知道的"))
        // 每两段之间恰好 "\n\n"（三段 → 两个分隔）
        assertTrue(all, all.split("\n\n## ").size == 3)
    }

    // ═══════════════════════════════════════════════════════════
    //  知识边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun 知识边界说有不知道这回事() {
        val s = CharacterEdge.knowledgeSection()
        assertTrue(s, s.contains("不知道"))
        assertTrue(s, s.contains("没写过"))
    }

    @Test
    fun 知识边界给了三种说得出口的替代说法() {
        // ⚠️ 光说"不知道"没用，得给能直接说出口的句子。
        val s = CharacterEdge.knowledgeSection()
        assertTrue(s, s.contains("没听说过"))
        assertTrue(s, s.contains("我不懂") || s.contains("不懂"))
    }

    @Test
    fun 知识边界要求记不清就说记不清_不许补() {
        val s = CharacterEdge.knowledgeSection()
        assertTrue(s, s.contains("记不清"))
        assertTrue(s, s.contains("补"))
    }

    @Test
    fun 空的known不留空话() {
        val s = CharacterEdge.knowledgeSection()
        assertFalse(s, s.contains("还有这些"))
        assertFalse(s, s.contains("：。"))
    }

    @Test
    fun 有known就原样列进去且顿号相连() {
        val s = CharacterEdge.knowledgeSection(listOf("做饭", "擦桌子"))
        assertTrue(s, s.contains("还有这些：做饭、擦桌子。"))
    }

    @Test
    fun known不影响另外两段() {
        // ⚠️ 这里断言的是"后两段逐字不变"，不是"前缀相同" ——
        //    known 本来就该改第一段（它就是给第一段用的）。
        val a = CharacterEdge.promptSection()
        val b = CharacterEdge.promptSection(listOf("做饭"))
        val marker = "## 遇到这些事她怎么反应"
        assertEquals(a.substringAfter(marker), b.substringAfter(marker))
    }

    // ═══════════════════════════════════════════════════════════
    //  反应：四类情境
    // ═══════════════════════════════════════════════════════════

    @Test
    fun 四类情境都在() {
        val s = CharacterEdge.reactionSection()
        assertTrue(s, s.contains("被夸"))
        assertTrue(s, s.contains("被追问"))
        assertTrue(s, s.contains("做不到"))
        assertTrue(s, s.contains("说错"))
    }

    @Test
    fun 做不到的事要求直接说不会_不许讲原理() {
        // ⚠️ 这是 TBS 那篇的核心场景：拿参数知识硬答比承认不会更像 AI。
        val s = CharacterEdge.reactionSection()
        assertTrue(s, s.contains("不会"))
        assertTrue(s, s.contains("不解释原理"))
    }

    @Test
    fun 被夸不是道谢而是不认() {
        val s = CharacterEdge.reactionSection()
        assertTrue(s, s.contains("不认"))
        assertFalse(s, s.contains("谢谢"))
    }

    @Test
    fun 被追问可以不答但不必解释为什么不答() {
        val s = CharacterEdge.reactionSection()
        assertTrue(s, s.contains("不解释自己为什么不答"))
    }

    // ═══════════════════════════════════════════════════════════
    //  心态：想但不说
    // ═══════════════════════════════════════════════════════════

    @Test
    fun 心态段要求把判断留在心里() {
        val s = CharacterEdge.mindsetSection()
        assertTrue(s, s.contains("最后那一句"))
        assertTrue(s, s.contains("留在心里"))
    }

    @Test
    fun 心态段不许鼓励自我剖析() {
        // ★ 反向钉子：出现"说明自己怎么想的"这种正面表述就要红。
        val s = CharacterEdge.mindsetSection()
        assertTrue(s, s.contains("不交代自己怎么想的"))
        assertFalse(s, s.contains("可以解释自己"))
    }

    @Test
    fun 心态段只有三行_再多就成内心独白模板() {
        val lines = CharacterEdge.mindsetSection().lines().filter { it.isNotBlank() }
        assertTrue(lines.toString(), lines.size <= 4) // 标题 + 3 行
    }

    // ═══════════════════════════════════════════════════════════
    //  排版：这一段自己在教"别像文档"
    // ═══════════════════════════════════════════════════════════

    @Test
    fun 没有列表符加粗破折号() {
        val all = CharacterEdge.promptSection(listOf("做饭"))
        assertFalse(all, all.contains("- "))
        assertFalse(all, all.contains("**"))
        assertFalse(all, all.contains("——"))
    }

    @Test
    fun 元话语一个都不出现() {
        // ⚠️ "下面""以下"会被 PromptVoice 抓，而且本身就是在说文档的话。
        val all = CharacterEdge.promptSection()
        for (word in listOf("下面", "以下", "上面", "本文", "本节")) {
            assertFalse("不该出现「$word」：\n$all", all.contains(word))
        }
    }

    @Test
    fun 过一遍PromptVoice是干净的() {
        val report = PromptVoice.audit(CharacterEdge.promptSection())
        assertTrue(report.breakdown(), report.isClean)
    }
}
