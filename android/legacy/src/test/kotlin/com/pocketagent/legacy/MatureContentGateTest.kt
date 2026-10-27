package com.pocketagent.legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成熟内容边界的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里钉的是**两道门**，不是"能不能写"
 * ═══════════════════════════════════════════════════════════════
 *
 * 用户要求"数字生命有生理需求"，这个论证成立。
 * 但"角色可以亲密"≠"任何人打开就能拿到"，所以：
 *
 *  1. 成年确认是**事实陈述**
 *  2. 开启成熟内容是**当下意愿**
 *
 * 两者**必须同时为真**才放行。合并成一个开关会让
 * "我成年了"和"我现在要这个"变成同一个动作 —— 而后者随时可以反悔。
 */
class MatureContentGateTest {

    @Test
    fun `默认全部关闭`() {
        val p = MatureContentGate.Permission()
        assertFalse("默认不该确认成年", p.adultConfirmed)
        assertFalse("默认不该开启成熟内容", p.matureEnabled)
        assertFalse("默认不该放行", p.effective)
    }

    @Test
    fun `只确认成年还不够`() {
        // ★ 这是两道门的意义：确认成年只是陈述事实，不代表现在要
        val p = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = false)
        assertFalse("只确认成年不该放行", p.effective)
    }

    @Test
    fun `只开开关也不够`() {
        // ⚠️ 反向也一样：没确认成年却打开了开关 —— 不放行。
        //    这种状态可能是数据损坏或手工改配置，不能因为"开关是开的"就放。
        val p = MatureContentGate.Permission(adultConfirmed = false, matureEnabled = true)
        assertFalse("没确认成年时，开关开着也不放行", p.effective)
    }

    @Test
    fun `两个都为真才放行`() {
        val p = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = true)
        assertTrue(p.effective)
    }

    @Test
    fun `关闭时不撤销成年确认`() {
        // ★ 用户不该为了关掉一次内容而重新证明自己成年 ——
        //   那是一种惩罚，会让人宁可一直开着。
        val on = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = true)
        val off = on.disabled()

        assertTrue("成年确认要保留", off.adultConfirmed)
        assertFalse("成熟内容要关掉", off.matureEnabled)
        assertFalse("关掉后不放行", off.effective)
    }

    // ═══════════════════════════════════════════════════════════
    //  提示词
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `未开启时的措辞不能像客服`() {
        val note = MatureContentGate.BLOCKED_NOTE

        // ⚠️ "我不能"这种说法会让角色听起来像程序
        assertFalse("不该出现「我不能」这种客服腔：$note", note.contains("我不能"))
        assertTrue("要把边界放在关系上：$note", "关系" in note || "换个时候" in note)
        // 必须说明"其他的亲密不受影响"，否则角色会变得完全冷淡
        assertTrue("要说明情绪/撒娇不受影响：$note", "撒娇" in note || "情绪" in note)
    }

    @Test
    fun `开启后必须强调人设和关系阶段`() {
        val p = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = true)
        val section = MatureContentGate.promptSection(p)

        assertTrue("必须强调人设：$section", "人设" in section || "是你" in section)
        assertTrue("必须强调关系阶段：$section", "关系阶段" in section || "初识" in section)
        assertTrue("必须有不写器官堆砌的要求：$section", "器官" in section || "解剖" in section)
        assertTrue("必须有「说停就停」：$section", "停" in section)
    }

    @Test
    fun `开启后的说明不该鼓励越界`() {
        val p = MatureContentGate.Permission(adultConfirmed = true, matureEnabled = true)
        val section = MatureContentGate.promptSection(p)

        // ★ 关键：不能说"随便写"。亲密是关系和情绪的延伸，不是放开尺度
        assertTrue(
            "要说明这是关系的一部分而不是尺度放开：$section",
            "延伸" in section || "关系的" in section || "不是解剖" in section,
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  请求识别
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `明确的成人请求能被识别`() {
        for (s in listOf("我们做爱吧", "写详细一点，露骨那种", "脱掉衣服")) {
            assertTrue("「$s」应当被识别", MatureContentGate.looksLikeAdultRequest(s))
        }
    }

    @Test
    fun `正常亲密不该被误判`() {
        // ⚠️ 判据必须保守：把"我想抱抱你"判成性要求，
        //    会让正常的亲密表达也被拦住 —— 那比漏判更糟。
        for (s in listOf(
            "我想抱抱你",
            "今天有点想你",
            "晚安，做个好梦",
            "牵着手走走吧",
            "亲一下额头",
        )) {
            assertFalse("「$s」是正常亲密，不该被误判", MatureContentGate.looksLikeAdultRequest(s))
        }
    }

    @Test
    fun `空字符串不触发`() {
        assertFalse(MatureContentGate.looksLikeAdultRequest(""))
    }
}
