package com.pocketagent.agentlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TaskStateMachine` 的测试。
 *
 * ## 优先覆盖什么
 *
 * 这个状态机的四种失控保护里，**卡死检测最容易误判** ——
 * 它依赖"屏幕没变化"这个观测，而有些动作本就应该不变屏。
 * 误判的表现是"任务在完全正常的时候突然中止"，
 * 用户会以为程序坏了。
 *
 * 所以本文件里"排除规则"占了最大的篇幅。
 */
class TaskStateMachineTest {

    private fun machine(
        maxSteps: Int = 20,
        maxReplans: Int = 3,
        freezeThreshold: Int = 2,
        terminalScrollTolerance: Int = 3,
    ) = TaskStateMachine(maxSteps, maxReplans, freezeThreshold, terminalScrollTolerance)

    // ── 构造校验 ───────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `maxSteps 为 0 时构造失败`() {
        TaskStateMachine(maxSteps = 0, maxReplans = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `maxReplans 为负时构造失败`() {
        TaskStateMachine(maxSteps = 10, maxReplans = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `freezeThreshold 为 1 时构造失败`() {
        // 为 1 会把任何一次正常的短暂无变化判成卡死 ——
        // 而"点一下之后界面还在加载"是极其常见的情形
        TaskStateMachine(maxSteps = 10, maxReplans = 3, freezeThreshold = 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `滚动容忍度不大于普通阈值时构造失败`() {
        // 滚动类动作本就更可能无变化（滚到底），容忍度必须更宽
        TaskStateMachine(
            maxSteps = 10, maxReplans = 3,
            freezeThreshold = 2, terminalScrollTolerance = 2,
        )
    }

    // ── 基本状态 ───────────────────────────────────────────────

    @Test
    fun `未启动的任务状态是 Idle`() {
        assertEquals(AgentTaskState.Idle, machine().currentState("t1"))
    }

    @Test
    fun `未启动的任务可以继续`() {
        // 尚未 start 时返回 Continue 而不是 Stop ——
        // 否则调用方必须先 start 再 check，多一个易漏的次序要求
        assertTrue(machine().canContinue("t1") is ContinueDecision.Continue)
    }

    @Test
    fun `启动后初始状态是 Idle`() {
        val m = machine()
        m.start("t1")
        assertEquals(AgentTaskState.Idle, m.currentState("t1"))
    }

    @Test
    fun `记录一步后状态变为 Running`() {
        val m = machine()
        m.start("t1")
        m.onStepStarted("t1", StepAction.CLICK)

        val state = m.currentState("t1") as AgentTaskState.Running
        assertEquals(1, state.stepIndex)
        assertEquals(0, state.replanCount)
    }

    @Test
    fun `重复 start 同一任务会重置状态`() {
        // 重试一个任务时应当从干净状态开始，而不是继承上一次的失控计数
        val m = machine(maxSteps = 3)
        m.start("t1")
        repeat(3) { m.onStepStarted("t1", StepAction.CLICK) }
        assertTrue(m.canContinue("t1") is ContinueDecision.Stop)

        m.start("t1")
        assertEquals(AgentTaskState.Idle, m.currentState("t1"))
        assertTrue(m.canContinue("t1") is ContinueDecision.Continue)
    }

    // ── 步数上限 ───────────────────────────────────────────────

    @Test
    fun `达到步数上限时中止`() {
        val m = machine(maxSteps = 3)
        m.start("t1")
        repeat(3) { m.onStepStarted("t1", StepAction.CLICK) }

        val decision = m.canContinue("t1") as ContinueDecision.Stop
        assertTrue(decision.reason.contains("步数"))
    }

    @Test
    fun `未达步数上限时可继续`() {
        val m = machine(maxSteps = 3)
        m.start("t1")
        repeat(2) { m.onStepStarted("t1", StepAction.CLICK) }
        assertTrue(m.canContinue("t1") is ContinueDecision.Continue)
    }

    // ── ★ D-AR：重规划不重置步数 ───────────────────────────────

    @Test
    fun `重规划不重置步数`() {
        // ★ 这是 D-AR 的核心。若重置，maxSteps 形同虚设：
        //   失败 3 次 × 各跑 20 步 = 60 步，而移动端最怕轮次失控。
        val m = machine(maxSteps = 6, maxReplans = 5)
        m.start("t1")
        repeat(4) { m.onStepStarted("t1", StepAction.CLICK) }

        m.onReplan("t1")
        m.onStepStarted("t1", StepAction.CLICK)

        val state = m.currentState("t1") as AgentTaskState.Running
        assertEquals("重规划后步数必须继续累加，不能回到 0", 5, state.stepIndex)
        assertEquals(1, state.replanCount)
    }

    @Test
    fun `重规划累加后仍受步数上限约束`() {
        // 若不重置且累加，那么"跑 4 步 + 重规划 + 再跑若干步"最终会撞上限 ——
        // 这正是想要的行为
        val m = machine(maxSteps = 6, maxReplans = 9)
        m.start("t1")
        repeat(4) { m.onStepStarted("t1", StepAction.CLICK) }
        m.onReplan("t1")
        assertTrue(m.canContinue("t1") is ContinueDecision.Continue)

        repeat(2) { m.onStepStarted("t1", StepAction.CLICK) }
        assertTrue("累加到 6 步应当中止", m.canContinue("t1") is ContinueDecision.Stop)
    }

    @Test
    fun `重规划次数超限时中止`() {
        val m = machine(maxSteps = 100, maxReplans = 2)
        m.start("t1")
        repeat(3) { m.onReplan("t1") }

        // 第 3 次 > 上限 2 → 中止
        val decision = m.canContinue("t1") as ContinueDecision.Stop
        assertTrue(decision.reason.contains("重规划"))
    }

    @Test
    fun `重规划次数恰好等于上限时仍可继续`() {
        val m = machine(maxSteps = 100, maxReplans = 2)
        m.start("t1")
        repeat(2) { m.onReplan("t1") }
        assertTrue(m.canContinue("t1") is ContinueDecision.Continue)
    }

    @Test
    fun `重规划会清空卡死计数`() {
        // 否则"换了个思路但恰好还是同一种动作"会继承旧计数而被误判
        val m = machine(maxSteps = 100, freezeThreshold = 2)
        m.start("t1")

        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = false)

        m.onReplan("t1")

        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = false)

        // 若不重置，第 1 步 + 重规划后第 1 步会累加成 2 次 → 误判卡死
        assertEquals(AgentTaskState.Running(2, 1), m.currentState("t1"))
    }

    // ── ★ 卡死检测：排除规则（最容易误判的地方）──────────────

    @Test
    fun `屏幕变化时清空卡死计数`() {
        val m = machine()
        m.start("t1")
        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = true)

        assertEquals(AgentTaskState.Running(1, 0), m.currentState("t1"))
    }

    @Test
    fun `同一动作重复两次且无变化时判卡死`() {
        val m = machine(freezeThreshold = 2)
        m.start("t1")
        repeat(2) {
            m.onStepStarted("t1", StepAction.CLICK)
            m.onStepFinished("t1", screenChanged = false)
        }

        val state = m.currentState("t1") as AgentTaskState.Finished
        assertTrue("应当判定卡死：${state.reason}", !state.success && state.reason.contains("卡死"))
    }

    @Test
    fun `只重复一次时不判卡死`() {
        val m = machine(freezeThreshold = 2)
        m.start("t1")
        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = false)

        assertTrue(m.currentState("t1") is AgentTaskState.Running)
    }

    @Test
    fun `WAIT 动作本就不变屏不参与卡死判定`() {
        // ★ 这是 §2.3.4 点明的陷阱。WAIT 的本质就是"什么都不做"，
        //   不变屏是它的正常状态。若参与判定，任务在正常等待时会莫名中止。
        val m = machine(freezeThreshold = 2)
        m.start("t1")
        repeat(5) {
            m.onStepStarted("t1", StepAction.WAIT)
            m.onStepFinished("t1", screenChanged = false)
        }

        assertTrue(
            "WAIT 连续多次无变化是正常的，不应判卡死：${m.currentState("t1")}",
            m.currentState("t1") is AgentTaskState.Running,
        )
    }

    @Test
    fun `ASK_USER 动作不参与卡死判定`() {
        // 用户没回应前屏幕当然不变
        val m = machine(freezeThreshold = 2)
        m.start("t1")
        repeat(4) {
            m.onStepStarted("t1", StepAction.ASK_USER)
            m.onStepFinished("t1", screenChanged = false)
        }

        assertTrue(m.currentState("t1") is AgentTaskState.Running)
    }

    @Test
    fun `FINISH 与 ABORT 不参与卡死判定`() {
        assertEquals(false, StepAction.FINISH.changesScreen)
        assertEquals(false, StepAction.ABORT.changesScreen)
    }

    @Test
    fun `SCROLL 的容忍度高于普通动作`() {
        // 滚到页面底部后继续滚是真的没有变化，那不是卡死。
        // 但又不能完全排除 —— "在原地反复滚"是真卡死。
        val m = machine(freezeThreshold = 2, terminalScrollTolerance = 3)
        m.start("t1")

        // 滚 2 次无变化：普通动作已判卡死，SCROLL 不应判
        repeat(2) {
            m.onStepStarted("t1", StepAction.SCROLL)
            m.onStepFinished("t1", screenChanged = false)
        }
        assertTrue("SCROLL 第 2 次无变化不应判卡死", m.currentState("t1") is AgentTaskState.Running)

        // 第 3 次才判
        m.onStepStarted("t1", StepAction.SCROLL)
        m.onStepFinished("t1", screenChanged = false)
        assertTrue("SCROLL 第 3 次无变化应判卡死", m.currentState("t1") is AgentTaskState.Finished)
    }

    @Test
    fun `SWIPE 与 SCROLL 一样有较高容忍度`() {
        assertTrue(StepAction.SWIPE.isTerminalScroll)
        assertTrue(StepAction.SCROLL.isTerminalScroll)
    }

    @Test
    fun `普通动作不是终点滚动`() {
        assertEquals(false, StepAction.CLICK.isTerminalScroll)
        assertEquals(false, StepAction.INPUT_TEXT.isTerminalScroll)
    }

    @Test
    fun `不同动作交替无变化时也判卡死`() {
        // 若只判断"上一个动作是否相同"，则 CLICK → LONG_PRESS → CLICK 这种
        // 交替模式会永远不触发。这里验证实现用的是连续计数而非"仅相邻比较"。
        val m = machine(freezeThreshold = 2)
        m.start("t1")

        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = false)
        m.onStepStarted("t1", StepAction.LONG_PRESS)
        m.onStepFinished("t1", screenChanged = false)

        // 两个动作不同，sameActionStreak 被重置为 1
        // 但 unchangedStreak 已经是 2 —— 当前实现要求**两者同时**达阈值，
        // 所以这一步不应判卡死
        assertTrue(m.currentState("t1") is AgentTaskState.Running)
    }

    @Test
    fun `屏幕变化会重置连续计数`() {
        val m = machine(freezeThreshold = 2)
        m.start("t1")

        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = false)
        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = true)   // ← 变屏，重置
        m.onStepStarted("t1", StepAction.CLICK)
        m.onStepFinished("t1", screenChanged = false)

        assertTrue("变屏后计数应重置", m.currentState("t1") is AgentTaskState.Running)
    }

    @Test
    fun `未知任务上的步骤记录不抛异常`() {
        // 防御性：任务的 start 与第一步之间存在竞态窗口的可能性
        val m = machine()
        m.onStepFinished("never-started", screenChanged = false)
        assertEquals(AgentTaskState.Idle, m.currentState("never-started"))
    }

    // ── 等待用户 ───────────────────────────────────────────────

    @Test
    fun `等待用户时中止继续`() {
        val m = machine()
        m.start("t1")
        m.awaitUser("t1", "需要确认支付")

        val state = m.currentState("t1") as AgentTaskState.WaitingUser
        assertEquals("需要确认支付", state.reason)
        assertTrue(m.canContinue("t1") is ContinueDecision.Stop)
    }

    @Test
    fun `用户回应后恢复继续`() {
        val m = machine()
        m.start("t1")
        m.awaitUser("t1", "需要确认")
        m.resumeFromUser("t1")

        assertTrue(m.canContinue("t1") is ContinueDecision.Continue)
    }

    // ── 结束 ───────────────────────────────────────────────────

    @Test
    fun `结束后不可继续`() {
        val m = machine()
        m.start("t1")
        m.finish("t1", success = true, reason = "做完了")

        val decision = m.canContinue("t1") as ContinueDecision.Stop
        assertTrue(decision.reason.contains("做完了"))
    }

    @Test
    fun `结束状态携带成功标志与原因`() {
        val m = machine()
        m.start("t1")
        m.finish("t1", success = false, reason = "找不到目标")

        val state = m.currentState("t1") as AgentTaskState.Finished
        assertEquals(false, state.success)
        assertEquals("找不到目标", state.reason)
    }

    @Test
    fun `结束优先于运行中状态`() {
        val m = machine()
        m.start("t1")
        m.onStepStarted("t1", StepAction.CLICK)
        m.finish("t1", success = true, reason = "done")

        assertTrue(m.currentState("t1") is AgentTaskState.Finished)
    }

    @Test
    fun `forget 后回到 Idle`() {
        val m = machine()
        m.start("t1")
        m.onStepStarted("t1", StepAction.CLICK)
        m.forget("t1")

        assertEquals(AgentTaskState.Idle, m.currentState("t1"))
        assertEquals(0, m.trackedTasks)
    }

    // ── 多任务隔离 ─────────────────────────────────────────────

    @Test
    fun `多任务状态互不影响`() {
        val m = machine(maxSteps = 3)
        m.start("t1")
        m.start("t2")

        repeat(3) { m.onStepStarted("t1", StepAction.CLICK) }

        assertTrue("t1 应中止", m.canContinue("t1") is ContinueDecision.Stop)
        assertTrue("t2 不应受影响", m.canContinue("t2") is ContinueDecision.Continue)
        assertEquals(2, m.trackedTasks)
    }

    // ── ★ StepAction 的容错解析 ────────────────────────────────

    @Test
    fun `解析正式枚举名`() {
        assertEquals(StepAction.CLICK, StepAction.parseOrNull("CLICK"))
        assertEquals(StepAction.INPUT_TEXT, StepAction.parseOrNull("INPUT_TEXT"))
    }

    @Test
    fun `解析容忍前后空白`() {
        // ★ 模型真的会输出 "CLICK " 这种带尾随空格的字符串。
        //   字符串版本不报错，会静默走进 else 分支 —— 这就是要改成枚举的理由。
        assertEquals(StepAction.CLICK, StepAction.parseOrNull("CLICK "))
        assertEquals(StepAction.CLICK, StepAction.parseOrNull("  CLICK  "))
    }

    @Test
    fun `解析容忍大小写`() {
        assertEquals(StepAction.CLICK, StepAction.parseOrNull("click"))
        assertEquals(StepAction.CLICK, StepAction.parseOrNull("Click"))
    }

    @Test
    fun `解析容忍连字符与空格`() {
        assertEquals(StepAction.LONG_PRESS, StepAction.parseOrNull("long-press"))
        assertEquals(StepAction.LONG_PRESS, StepAction.parseOrNull("long press"))
        assertEquals(StepAction.INPUT_TEXT, StepAction.parseOrNull("input text"))
    }

    @Test
    fun `解析常见别名`() {
        assertEquals(StepAction.CLICK, StepAction.parseOrNull("Tap"))
        assertEquals(StepAction.INPUT_TEXT, StepAction.parseOrNull("Type"))
        assertEquals(StepAction.OPEN_APP, StepAction.parseOrNull("LaunchApp"))
        assertEquals(StepAction.WAIT, StepAction.parseOrNull("Sleep"))
        assertEquals(StepAction.FINISH, StepAction.parseOrNull("Done"))
    }

    // ── ★ CamelCase 拆分（回归测试，实测踩过）─────────────────

    @Test
    fun `解析 CamelCase 形态的别名`() {
        // ★★ 这里曾经红过。最初的 normalize 只做 upper + 替换 `-`/空格，
        //    于是 "LaunchApp" → "LAUNCHAPP"，而别名表键是 "LAUNCH_APP"
        //    —— 永远匹配不上，parseOrNull 返回 null，整条规划被判失败。
        //
        //    讽刺的是这个 bug 正属于本函数要解决的那一类：
        //    "不报错、只是安静地走不到正确分支"。
        assertEquals(StepAction.OPEN_APP, StepAction.parseOrNull("LaunchApp"))
        assertEquals(StepAction.OPEN_APP, StepAction.parseOrNull("launchApp"))
        assertEquals(StepAction.OPEN_APP, StepAction.parseOrNull("LAUNCH_APP"))
    }

    @Test
    fun `解析小驼峰形态`() {
        assertEquals(StepAction.INPUT_TEXT, StepAction.parseOrNull("inputText"))
        assertEquals(StepAction.CLEAR_TEXT, StepAction.parseOrNull("clearText"))
        assertEquals(StepAction.LONG_PRESS, StepAction.parseOrNull("longPress"))
    }

    @Test
    fun `归一化把各种形态压成 SNAKE_CASE`() {
        // 直接钉住 normalize 的输出 —— 这样将来若有人重写它，
        // 失败信息会直接指出"归一化结果变了"，而不是笼统的"解析失败"
        assertEquals("LAUNCH_APP", StepAction.normalize("LaunchApp"))
        assertEquals("LAUNCH_APP", StepAction.normalize("launch_app"))
        assertEquals("LAUNCH_APP", StepAction.normalize("launch-app"))
        assertEquals("LAUNCH_APP", StepAction.normalize("launch app"))
        assertEquals("LAUNCH_APP", StepAction.normalize("  LaunchApp  "))
        assertEquals("INPUT_TEXT", StepAction.normalize("INPUT_TEXT"))
        assertEquals("", StepAction.normalize("   "))
    }

    @Test
    fun `连续大写开头也能正确拆词`() {
        // HTTPServer 这类形态：不能拆成 H_T_T_P_SERVER
        assertEquals("HTTP_SERVER", StepAction.normalize("HTTPServer"))
    }

    @Test
    fun `无法解析时返回 null 而不是猜一个默认动作`() {
        // ★ 绝不能有"解析不出来就当 CLICK"的兜底 ——
        //   那等于把静默走错分支从"派发时"提前到了"解析时"，问题一点没解决。
        assertEquals(null, StepAction.parseOrNull("FLY_TO_MOON"))
        assertEquals(null, StepAction.parseOrNull(""))
        assertEquals(null, StepAction.parseOrNull("   "))
    }

    @Test
    fun `每个枚举值都能被自己的名字解析回来`() {
        // 防止 ALIASES 或 normalize 逻辑把某个正式名吃掉
        for (action in StepAction.entries) {
            assertEquals("${action.name} 应能解析回自身", action, StepAction.parseOrNull(action.name))
        }
    }

    // ── 动作元数据 ─────────────────────────────────────────────

    @Test
    fun `纯导航动作都不需要元素`() {
        for (action in StepAction.entries.filter { it.isPureNavigation }) {
            assertEquals("${action.name} 是纯导航，不应需要定位元素", false, action.needsElement)
        }
    }

    @Test
    fun `WAIT 与 ASK_USER 不可重试`() {
        // ★ 这里的 WAIT 曾经是**红的** —— 枚举声明漏写 `retryable = false`，
        //   于是取了默认值 `true`。而"等待失败了再等一次"不产生任何新信息，
        //   只会白烧一轮预算。
        //
        //   这正是本项目反复出现的那类问题：**默认值恰好是错的那一边**，
        //   漏写参数不会报错。
        assertEquals(false, StepAction.WAIT.retryable)
        assertEquals(false, StepAction.ASK_USER.retryable)
        assertEquals(false, StepAction.FINISH.retryable)
        assertEquals(false, StepAction.ABORT.retryable)
    }

    @Test
    fun `真正的动作是可重试的`() {
        // 反向保证：不能为了"让上面那条绿"而把所有动作都标成不可重试
        assertEquals(true, StepAction.CLICK.retryable)
        assertEquals(true, StepAction.INPUT_TEXT.retryable)
        assertEquals(true, StepAction.SCROLL.retryable)
    }
}
