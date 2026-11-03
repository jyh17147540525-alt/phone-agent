package com.pocketagent.personalogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **要不要清洗** —— 宁可漏，不要滥。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件防的是"给记忆注水"
 * ═══════════════════════════════════════════════════════════════
 *
 * 把「嗯」「在吗」送去给模型"洗"，模型会原样返回（白花钱），
 * 或者**给它加上不存在的意味**：
 *
 * ```
 *   「嗯」  →  「嗯……我知道你在想什么」
 * ```
 *
 * 后者是**给记忆注水** —— 比不清洗更糟。
 */
class MemoryCleaningTest {

    private fun assess(
        text: String,
        tier: MemoryTier = MemoryTier.DIALOGUE,
        markers: List<String> = listOf("小雨", "哥哥"),
    ) = MemoryCleaning.assess(text, tier, markers)

    // ═══════════════════════════════════════════════════════════
    //  ★ 日常废话不清洗
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `纯应承不值得洗`() {
        for (t in listOf("嗯", "哦", "好的", "收到", "知道了", "哈哈哈哈", "嗯嗯")) {
            val a = assess(t)
            assertFalse("「$t」不该洗：${a.reasons}", a.worth)
        }
    }

    @Test
    fun `纯问候不值得洗`() {
        for (t in listOf("在吗", "在不在", "你好", "晚安", "早安呀")) {
            assertFalse("「$t」不该洗：${assess(t).reasons}", assess(t).worth)
        }
    }

    @Test
    fun `带感叹号的应承也不值得`() {
        // 标点不该影响判断
        assertFalse(assess("嗯！").worth)
        assertFalse(assess("好的~~").worth)
    }

    @Test
    fun `空文本不值得`() {
        assertFalse(assess("").worth)
        assertFalse(assess("   ").worth)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 有内容的要洗
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `含旧设定的一定要洗`() {
        val a = assess("我叫小雨，你以前都叫我小雨的")
        assertTrue("含旧名字必须洗：${a.reasons}", a.worth)
        assertTrue("应当说明原因", a.reasons.any { it.contains("旧设定") })
    }

    @Test
    fun `含关系断言的要洗`() {
        val a = assess("我是你的青梅竹马啊，你忘了吗")
        assertTrue("关系断言必须洗：${a.reasons}", a.worth)
    }

    @Test
    fun `够长的实质内容要洗`() {
        val a = assess("今天下午我去了一趟医院，医生说要复查，可能要住院观察几天")
        assertTrue("长且有事实：${a.reasons}", a.worth)
    }

    @Test
    fun `应承里带内容的不该判死`() {
        // ⚠️ "嗯，不过我觉得你变了" 是有内容的
        val a = assess("嗯，不过我觉得你变了，跟以前不太一样")
        assertTrue("不能因为开头是「嗯」就判死：${a.reasons}", a.worth)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 分层权重
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `约束永远要洗 即使文本很短`() {
        val a = assess("不能吃辣", MemoryTier.CONSTRAINT)
        assertTrue("约束改错会直接造成伤害", a.worth)
        assertEquals(100, a.score)
    }

    @Test
    fun `偏好够长就要洗`() {
        assertTrue(assess("他喜欢安静的环境", MemoryTier.PREFERENCE).worth)
    }

    @Test
    fun `事实基本不用洗`() {
        // "今天下雨" 与设定无关
        val a = assess("今天下雨了", MemoryTier.FACT)
        assertFalse("事实层不该花调用：${a.reasons}", a.worth)
    }

    @Test
    fun `事实层但含旧设定的仍要洗`() {
        // ⚠️ 分层是下限，不是天花板
        val a = assess("小雨说她今天下雨没带伞", MemoryTier.FACT)
        assertTrue("含旧设定就该洗：${a.reasons}", a.worth)
    }

    @Test
    fun `阶段结论不够长不洗`() {
        assertFalse(assess("他在备考", MemoryTier.STAGE_CONCLUSION).worth)
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 判据细节
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `事实痕迹要命中两个以上才算`() {
        // ⚠️ 单个"点"字很容易误伤（"一点点"）
        val one = assess("好一点点")
        assertFalse("单个痕迹不算：${one.reasons}", one.worth)
    }

    @Test
    fun `没有旧标记时不含旧设定的句子不会因此加分`() {
        val a = MemoryCleaning.assess("今天下午去了医院复查", MemoryTier.DIALOGUE, emptyList())
        // 靠"长度 + 事实痕迹"仍可能过线，但不该因为"旧设定"加分
        assertFalse("不该出现旧设定加分：${a.reasons}", a.reasons.any { it.contains("旧设定") })
    }

    @Test
    fun `判死的都带原因`() {
        // ⚠️ 静默跳过等于"清洗没生效"却没人知道
        for (t in listOf("嗯", "在吗", "")) {
            val a = assess(t)
            assertTrue("「$t」判死必须有原因", a.reasons.isNotEmpty())
        }
    }

    @Test
    fun `分数与是否值得一致`() {
        for (t in listOf("嗯", "我叫小雨", "今天下雨了", "我是你的青梅竹马")) {
            val a = assess(t)
            assertEquals(
                "分数与结论必须一致：「$t」score=${a.score}",
                a.score >= MemoryCleaning.THRESHOLD,
                a.worth,
            )
        }
    }
}
