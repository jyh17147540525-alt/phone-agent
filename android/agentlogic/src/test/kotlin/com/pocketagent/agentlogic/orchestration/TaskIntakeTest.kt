package com.pocketagent.agentlogic.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一句话分流（[TaskIntake]）的测试。
 *
 * 这里钉的是**边界**，不是"能识别多少种说法"：
 * 每条断言背后都是一个"判错了会怎样"的场景 —— 把抱怨当任务会去乱点手机，
 * 把任务当聊天会让用户以为它没听见，把指代猜错会带用户去无关页面。
 * 说法再多也不值得为它们加词表；边界判错才值得。
 */
class TaskIntakeTest {

    // ── 空与纯聊天 ──────────────────────────────────────────────────────────

    @Test
    fun `空输入先问一句`() {
        val intake = TaskIntake.route("   ")
        assertTrue(intake is Intake.Clarify)
        val clarify = intake as Intake.Clarify
        assertTrue(clarify.question.contains("做什么"))
        assertTrue("没有给可点的候选", clarify.options.isNotEmpty())
    }

    @Test
    fun `纯提问当聊天`() {
        val intake = TaskIntake.route("明天会下雨吗")
        assertTrue(intake is Intake.Chat)
        assertTrue(intake.reason.contains("提问"))
    }

    @Test
    fun `抱怨手机卡当聊天而不是去清缓存`() {
        val intake = TaskIntake.route("我的手机好卡，怎么办")
        assertTrue("抱怨被判成了任务：$intake", intake is Intake.Chat)
    }

    @Test
    fun `没有动作词的话一律当聊天`() {
        val intake = TaskIntake.route("今天心情不错")
        assertTrue(intake is Intake.Chat)
        assertTrue(intake.reason.contains("动作"))
    }

    // ── 单件任务 ────────────────────────────────────────────────────────────

    @Test
    fun `打开微信是任务`() {
        val intake = TaskIntake.route("打开微信")
        assertTrue(intake is Intake.Task)
        val task = intake as Intake.Task
        assertEquals("打开微信", task.goal)
        assertFalse("打开应用不需要先问", task.needsConfirmation)
        assertTrue("置信度偏低：${task.confidence}", task.confidence >= TaskIntake.LOW_CONFIDENCE)
    }

    @Test
    fun `请求语气比裸命令更自信`() {
        val polite = TaskIntake.route("帮我打开微信") as Intake.Task
        val bare = TaskIntake.route("打开微信") as Intake.Task
        assertTrue("${polite.confidence} 应高于 ${bare.confidence}", polite.confidence > bare.confidence)
        assertTrue(polite.confidence <= 1.0)
    }

    @Test
    fun `带疑问语气但是请求仍按任务处理`() {
        val intake = TaskIntake.route("你能帮我打开微信吗")
        assertTrue(intake is Intake.Task)
        assertTrue(intake.reason.contains("请求"))
    }

    // ── 先问一次的那两类 ────────────────────────────────────────────────────

    @Test
    fun `涉及钱先问一次`() {
        val intake = TaskIntake.route("帮我给张三转账 50 块") as Intake.Task
        assertTrue("钱相关的任务必须先问", intake.needsConfirmation)
        assertTrue(intake.reason.contains("钱"))
    }

    @Test
    fun `删除类先问一次`() {
        val intake = TaskIntake.route("卸载淘宝") as Intake.Task
        assertTrue(intake.needsConfirmation)
        assertTrue(intake.reason.contains("撤回"))
    }

    // ── 任务链 ──────────────────────────────────────────────────────────────

    @Test
    fun `两件事按顺序拆成链`() {
        val intake = TaskIntake.route("先查一下明天天气，然后帮我订一张高铁票")
        assertTrue("没拆成链：$intake", intake is Intake.Chain)
        val chain = intake as Intake.Chain
        assertEquals(2, chain.goals.size)
        assertTrue(chain.goals[0].contains("天气"))
        assertTrue(chain.goals[1].contains("高铁票"))
    }

    @Test
    fun `顺便也算一件事`() {
        val intake = TaskIntake.route("把亮度调低一点，顺便把声音关掉")
        assertTrue(intake is Intake.Chain)
        assertEquals(2, (intake as Intake.Chain).goals.size)
    }

    // ── 指代 ────────────────────────────────────────────────────────────────

    @Test
    fun `指代没有前文就问清楚`() {
        val intake = TaskIntake.route("打开那个")
        assertTrue("指代被直接执行了：$intake", intake is Intake.Clarify)
        assertTrue((intake as Intake.Clarify).question.contains("那个"))
    }

    @Test
    fun `指代有前文就直接做`() {
        val intake = TaskIntake.route(
            "把那个也关掉",
            IntakeContext(lastTaskGoal = "关掉蓝牙"),
        )
        assertTrue("有上文时不该再问：$intake", intake is Intake.Task)
    }

    @Test
    fun `指代但宾语点明了就不用问`() {
        // 冒号后面是具体对象 —— 这时"那个"只是口语，不是真的不知道
        val intake = TaskIntake.route("把刚才那个发给他：会议纪要")
        assertTrue(intake is Intake.Task)
    }

    // ── 停 ──────────────────────────────────────────────────────────────────

    @Test
    fun `任务跑着时说停就停`() {
        val intake = TaskIntake.route("停", IntakeContext(hasActiveTask = true))
        assertTrue("运行中的「停」没被拦住：$intake", intake is Intake.Stop)
    }

    @Test
    fun `没在跑任务时说停不算停止命令`() {
        assertNotEquals(true, TaskIntake.route("停") is Intake.Stop)
    }

    @Test
    fun `停止命令只认短的`() {
        assertTrue(TaskIntake.isStop("停"))
        assertTrue(TaskIntake.isStop("停下"))
        assertTrue(TaskIntake.isStop("取消吧"))
        assertFalse(TaskIntake.isStop("打开微信"))
        assertFalse(TaskIntake.isStop("停车场的照片发我"))
        assertFalse(TaskIntake.isStop(""))
    }

    // ── 文案与稳健性 ────────────────────────────────────────────────────────

    @Test
    fun `每条分流的理由都短且非空`() {
        val samples = listOf(
            "",
            "明天会下雨吗",
            "打开微信",
            "卸载淘宝",
            "先查天气，然后订票",
            "打开那个",
            "把亮度调低一点",
        )
        for (text in samples) {
            val intake = TaskIntake.route(text)
            assertTrue("「$text」没有理由", intake.reason.isNotBlank())
            assertTrue("「$text」的理由太长：${intake.reason}", intake.reason.length <= 40)
        }
    }

    @Test
    fun `超长句子不会崩也不会被当成链`() {
        val long = "帮我把最近三个月的所有账单按类别整理好，"
        val intake = TaskIntake.route(long)
        assertTrue(intake is Intake.Task || intake is Intake.Chat)
    }

    @Test
    fun `置信度永远在零到一之间`() {
        val samples = listOf("打开微信", "帮我打开微信", "你能帮我打开淘宝看看订单吗", "改")
        for (text in samples) {
            val intake = TaskIntake.route(text)
            if (intake is Intake.Task) {
                assertTrue("${intake.confidence} 越界：$text", intake.confidence in 0.0..1.0)
            }
        }
    }
}
