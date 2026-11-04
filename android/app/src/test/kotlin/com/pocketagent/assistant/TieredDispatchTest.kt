package com.pocketagent.assistant

import com.pocketagent.mcp.AgentAction
import com.pocketagent.mcp.ActionDispatchPort
import com.pocketagent.mcp.CaptureSource
import com.pocketagent.mcp.DispatchOutcome
import com.pocketagent.mcp.GlobalKeyName
import com.pocketagent.mcp.ResolvedNodeRef
import com.pocketagent.mcp.ScreenCaptureOutcome
import com.pocketagent.mcp.ScreenPoint
import com.pocketagent.mcp.ScreenReaderPort
import com.pocketagent.mcp.ScreenRect
import com.pocketagent.rootlogic.PrivilegeMode
import com.pocketagent.rootlogic.RootActions
import com.pocketagent.rootlogic.RootShellPort
import com.pocketagent.rootlogic.ScreenLease
import com.pocketagent.rootlogic.ShellResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两档汇合点的测试 —— 这一层以前**只有编译验证**（`:app` 不在离线验证器里）。
 *
 * 它要证明的是四件在真机上"错了也不会报错"的事：
 * 1. 档位是**每次调用现读**的（切换档位立刻生效，不是构造时快照）；
 * 2. root 档点按**不经过**无障碍通道（否则会悄悄占屏）；
 * 3. root 档失败**不退让**到无障碍（退让 = 用户屏幕被占）；
 * 4. 读屏相反：root 没就绪时**允许**退回无障碍（那边只多一次调用）。
 */
class TieredDispatchTest {

    private class RecordingPort(private val outcome: DispatchOutcome) : ActionDispatchPort {
        val actions = ArrayList<AgentAction>()

        override suspend fun dispatch(action: AgentAction): DispatchOutcome {
            actions += action
            return outcome
        }
    }

    private class RecordingShell(private val result: ShellResult) : RootShellPort {
        val argv = ArrayList<List<String>>()

        override fun run(argv: List<String>): ShellResult {
            this.argv += argv
            return result
        }
    }

    private val ok = ShellResult(code = 0, stdout = "", stderr = "")

    @Test
    fun `普通档走无障碍通道`() = runBlocking {
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 12))
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(plain, RootActions(shell), mode = { PrivilegeMode.NON_ROOT })

        val outcome = port.dispatch(AgentAction.Tap(null, ScreenPoint(10, 20), null))

        assertEquals(1, plain.actions.size)
        assertTrue(shell.argv.isEmpty())
        assertTrue(outcome is DispatchOutcome.Dispatched)
    }

    @Test
    fun `root 档点按走 su 且完全不碰无障碍通道`() = runBlocking {
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 12))
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(plain, RootActions(shell), mode = { PrivilegeMode.ROOT })

        val outcome = port.dispatch(AgentAction.Tap(null, ScreenPoint(10, 20), null))

        assertTrue(plain.actions.isEmpty())
        assertEquals(listOf("su", "-c", "input tap 10 20"), shell.argv.single())
        val dispatched = outcome as DispatchOutcome.Dispatched
        assertEquals(TieredDispatchPort.ROOT_CHANNEL, dispatched.channel)
    }

    @Test
    fun `档位是每次调用现读的`() = runBlocking {
        var mode = PrivilegeMode.NON_ROOT
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 1))
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(plain, RootActions(shell), mode = { mode })
        val tap = AgentAction.Tap(null, ScreenPoint(1, 1), null)

        port.dispatch(tap)
        mode = PrivilegeMode.ROOT
        port.dispatch(tap)

        assertEquals(1, plain.actions.size)
        assertEquals(1, shell.argv.size)
    }

    @Test
    fun `只有节点引用时点到它的中心`() = runBlocking {
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(
            RecordingPort(DispatchOutcome.Dispatched("a11y", 1)),
            RootActions(shell),
            mode = { PrivilegeMode.ROOT },
        )

        port.dispatch(
            AgentAction.Tap(ResolvedNodeRef("n1", ScreenRect(100, 200, 300, 400)), null, null),
        )

        assertEquals(listOf("su", "-c", "input tap 200 300"), shell.argv.single())
    }

    @Test
    fun `滑动时长被夹到合法区间`() = runBlocking {
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(
            RecordingPort(DispatchOutcome.Dispatched("a11y", 1)),
            RootActions(shell),
            mode = { PrivilegeMode.ROOT },
        )

        port.dispatch(
            AgentAction.Swipe(ScreenPoint(1, 2), ScreenPoint(3, 4), durationMs = 99_999, reason = null),
        )

        assertEquals(listOf("su", "-c", "input swipe 1 2 3 4 60000"), shell.argv.single())
    }

    @Test
    fun `打字先点目标再写文字`() = runBlocking {
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(
            RecordingPort(DispatchOutcome.Dispatched("a11y", 1)),
            RootActions(shell),
            mode = { PrivilegeMode.ROOT },
        )

        port.dispatch(AgentAction.TypeText(null, ScreenPoint(5, 6), "你好", null))

        assertEquals(
            listOf(
                listOf("su", "-c", "input tap 5 6"),
                listOf("su", "-c", "input text '你好'"),
            ),
            shell.argv,
        )
    }

    @Test
    fun `打字第一步失败时不再写文字`() = runBlocking {
        val shell = RecordingShell(ShellResult(code = 255, stdout = "", stderr = "denied"))
        val port = TieredDispatchPort(
            RecordingPort(DispatchOutcome.Dispatched("a11y", 1)),
            RootActions(shell),
            mode = { PrivilegeMode.ROOT },
        )

        val outcome = port.dispatch(AgentAction.TypeText(null, ScreenPoint(5, 6), "你好", null))

        assertEquals(1, shell.argv.size)
        assertTrue(outcome is DispatchOutcome.Failed)
    }

    @Test
    fun `root 失败时不退回无障碍`() = runBlocking {
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 1))
        val shell = RecordingShell(ShellResult(code = 255, stdout = "", stderr = "permission denied"))
        val port = TieredDispatchPort(plain, RootActions(shell), mode = { PrivilegeMode.ROOT })

        val outcome = port.dispatch(AgentAction.Tap(null, ScreenPoint(7, 8), null))

        assertTrue(plain.actions.isEmpty())
        val failed = outcome as DispatchOutcome.Failed
        // 文案要指向用户能做的下一步，而不是只说"失败了"。
        assertTrue(failed.reason, failed.reason.contains("Magisk"))
    }

    @Test
    fun `全局键映射到白名单里的三个 keycode`() = runBlocking {
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(
            RecordingPort(DispatchOutcome.Dispatched("a11y", 1)),
            RootActions(shell),
            mode = { PrivilegeMode.ROOT },
        )

        port.dispatch(AgentAction.GlobalKey(GlobalKeyName.BACK, null))
        port.dispatch(AgentAction.GlobalKey(GlobalKeyName.HOME, null))
        port.dispatch(AgentAction.GlobalKey(GlobalKeyName.RECENTS, null))

        assertEquals(
            listOf(
                listOf("su", "-c", "input keyevent KEYCODE_BACK"),
                listOf("su", "-c", "input keyevent KEYCODE_HOME"),
                listOf("su", "-c", "input keyevent KEYCODE_APP_SWITCH"),
            ),
            shell.argv,
        )
    }

    @Test
    fun `缺目标时不发任何命令`() = runBlocking {
        val shell = RecordingShell(ok)
        val port = TieredDispatchPort(
            RecordingPort(DispatchOutcome.Dispatched("a11y", 1)),
            RootActions(shell),
            mode = { PrivilegeMode.ROOT },
        )

        val outcome = port.dispatch(AgentAction.Tap(null, null, null))

        assertTrue(shell.argv.isEmpty())
        assertTrue(outcome is DispatchOutcome.Failed)
    }
}

/**
 * 读屏的汇合点 —— 与点按**规则相反**，所以单独测。
 */
class TieredScreenReaderTest {

    private class FixedReader(private val outcome: ScreenCaptureOutcome) : ScreenReaderPort {
        var calls = 0

        override suspend fun capture(): ScreenCaptureOutcome {
            calls++
            return outcome
        }
    }

    private fun shot(pkg: String) = ScreenCaptureOutcome.Ok(
        dto = com.pocketagent.mcp.ScreenCaptureDto(
            packageName = pkg,
            activityName = null,
            width = 100,
            height = 200,
            source = CaptureSource.ACCESSIBILITY,
            confidence = 0.5f,
            capturedAtMs = 0L,
            nodes = emptyList(),
        ),
    )

    @Test
    fun `root 档且 root 就绪时走 root 直读`() = runBlocking {
        val plain = FixedReader(shot("a11y"))
        val root = FixedReader(shot("root"))
        val reader = TieredScreenReader(plain, root, { PrivilegeMode.ROOT }, { true })

        val outcome = reader.capture() as ScreenCaptureOutcome.Ok

        assertEquals("root", outcome.dto.packageName)
        assertEquals(0, plain.calls)
    }

    @Test
    fun `root 档但 root 没就绪时退回无障碍`() = runBlocking {
        val plain = FixedReader(shot("a11y"))
        val root = FixedReader(shot("root"))
        val reader = TieredScreenReader(plain, root, { PrivilegeMode.ROOT }, { false })

        val outcome = reader.capture() as ScreenCaptureOutcome.Ok

        assertEquals("a11y", outcome.dto.packageName)
        assertEquals(0, root.calls)
    }

    @Test
    fun `普通档永远不碰 root 读屏`() = runBlocking {
        val plain = FixedReader(shot("a11y"))
        val root = FixedReader(shot("root"))
        val reader = TieredScreenReader(plain, root, { PrivilegeMode.NON_ROOT }, { true })

        reader.capture()

        assertEquals(1, plain.calls)
        assertEquals(0, root.calls)
    }

    @Test
    fun `root 就绪状态是现读的`() = runBlocking {
        var ready = false
        val plain = FixedReader(shot("a11y"))
        val root = FixedReader(shot("root"))
        val reader = TieredScreenReader(plain, root, { PrivilegeMode.ROOT }, { ready })

        reader.capture()
        ready = true
        reader.capture()

        assertEquals(1, plain.calls)
        assertEquals(1, root.calls)
    }

    @Test
    fun `读屏失败原样传出而不是换成空快照`() = runBlocking {
        val failure = ScreenCaptureOutcome.Failed("树读不到")
        val reader = TieredScreenReader(
            FixedReader(failure), FixedReader(failure), { PrivilegeMode.NON_ROOT }, { true },
        )

        val outcome = reader.capture()

        assertTrue(outcome is ScreenCaptureOutcome.Failed)
        assertNull((outcome as? ScreenCaptureOutcome.Ok)?.dto)
        assertFalse(outcome is ScreenCaptureOutcome.Ok)
    }
}

/**
 * 占屏租约作为**闸门**的行为。
 *
 * 这组测试存在的原因：`ScreenLease` 本身有 15 条测试，但那些只证明状态机自洽。
 * 它在系统里**有没有被调用、拦不拦得住**是另一件事 —— 之前 `TieredDispatchPort`
 * 根本没接租约，`canAct()` 恒 true，也就是"用户按停之后 agent 继续点屏"。
 * 这里逐条钉住"接上了，且拦得住"。
 */
class ScreenLeaseGateTest {

    private class RecordingPort(private val outcome: DispatchOutcome) : ActionDispatchPort {
        val actions = ArrayList<AgentAction>()
        override suspend fun dispatch(action: AgentAction): DispatchOutcome {
            actions += action
            return outcome
        }
    }

    private class RecordingShell(private val result: ShellResult) : RootShellPort {
        val argv = ArrayList<List<String>>()
        override fun run(argv: List<String>): ShellResult {
            this.argv += argv
            return result
        }
    }

    private val ok = ShellResult(code = 0, stdout = "", stderr = "")
    private val tap = AgentAction.Tap(null, ScreenPoint(10, 20), null)

    private fun leaseAt(now: TestClock): ScreenLease =
        ScreenLease(clock = { now.value }, holdMillis = 90_000)

    @Test
    fun `第一次点按先申请租约再放行`() = runBlocking {
        val now = TestClock(1_000L)
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 5))
        val port = TieredDispatchPort(
            plain, RootActions(RecordingShell(ok)),
            mode = { PrivilegeMode.NON_ROOT }, lease = leaseAt(now),
        )

        val outcome = port.dispatch(tap)

        assertEquals(1, plain.actions.size)
        assertTrue(outcome is DispatchOutcome.Dispatched)
    }

    @Test
    fun `用户按停之后点按被拒且无障碍一次都没被调用`() = runBlocking {
        val now = TestClock(1_000L)
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 5))
        val lease = leaseAt(now)
        val port = TieredDispatchPort(
            plain, RootActions(RecordingShell(ok)),
            mode = { PrivilegeMode.NON_ROOT }, lease = lease,
        )

        port.dispatch(tap)
        lease.onUserStop()
        val outcome = port.dispatch(tap)

        // 关键：不是"执行了但用户没看见"，是**根本没执行**。
        assertEquals(1, plain.actions.size)
        assertTrue(outcome is DispatchOutcome.Refused)
        val reason = (outcome as DispatchOutcome.Refused).reason
        assertTrue(reason, reason.contains("你让任务停一下"))
    }

    @Test
    fun `用户摸屏后立刻点按被让开 等手松开再放行`() = runBlocking {
        val now = TestClock(1_000L)
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 5))
        val lease = leaseAt(now)
        val port = TieredDispatchPort(
            plain, RootActions(RecordingShell(ok)),
            mode = { PrivilegeMode.NON_ROOT }, lease = lease,
        )

        port.dispatch(tap)
        lease.onUserTouch()
        now.value += 400                    // 才过 0.4 秒，还在宽限里
        val blocked = port.dispatch(tap)
        assertEquals(1, plain.actions.size)
        assertTrue(blocked is DispatchOutcome.Refused)

        now.value += 1_300                  // 累计 1.7 秒，超过 1.5 秒宽限
        val resumed = port.dispatch(tap)
        assertEquals(2, plain.actions.size)
        assertTrue(resumed is DispatchOutcome.Dispatched)
    }

    @Test
    fun `root 档完全不受占屏租约影响`() = runBlocking {
        val now = TestClock(1_000L)
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 5))
        val shell = RecordingShell(ok)
        val lease = leaseAt(now)
        val port = TieredDispatchPort(
            plain, RootActions(shell),
            mode = { PrivilegeMode.ROOT }, lease = lease,
        )

        lease.onUserStop()                  // 就算按了停
        val outcome = port.dispatch(tap)

        // root 档不占屏，也就不用"让开" —— 停的语义属于占屏这一档。
        // 这里断言的是"租约不是全局刹车"：它只拦会占屏的那条路。
        assertEquals(1, shell.argv.size)
        assertTrue(plain.actions.isEmpty())
        assertTrue(outcome is DispatchOutcome.Dispatched)
    }

    @Test
    fun `没传租约时行为与从前一致`() = runBlocking {
        val plain = RecordingPort(DispatchOutcome.Dispatched("a11y", 5))
        val port = TieredDispatchPort(
            plain, RootActions(RecordingShell(ok)),
            mode = { PrivilegeMode.NON_ROOT },
        )

        port.dispatch(tap)

        assertEquals(1, plain.actions.size)
    }
}

/** 可变时钟 —— 测试里要"把时间推到宽限之后"。 */
private class TestClock(var value: Long)

