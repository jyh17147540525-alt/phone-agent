package com.pocketagent.capabilitylogic

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 编排层 —— 把「裁决 → 执行 → 记账」串起来的那一段。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里守的是**顺序**和**归因**，不是"功能对不对"
 * ═══════════════════════════════════════════════════════════════
 *
 * 三个组件单独看都是对的（它们各有自己的测试），连起来之后的错误全在**接缝**上：
 *
 * | 接缝上写错 | 后果 | 会不会报错 |
 * |---|---|---|
 * | 记账提到执行之前 | 日志说"执行了"，实际没执行 | 不会 |
 * | 通道不可用记成"被拒绝" | 用户以为插件被拦了，其实是权限没配 | 不会 |
 * | 需要确认时也记一条 | 缓冲被"问了没人答"填满 | 不会 |
 *
 * 没有一条抛异常。所以下面用 `trace`（一个记录"谁按什么顺序被碰过"的列表）
 * 来断言顺序，而不是只看最终状态 —— 只看最终状态的话，"先执行后记账"与
 * "先记账后执行"产生的结果**完全一样**。
 */
class CapabilityRuntimeTest {

    private val catalog = CapabilityCatalog()

    /**
     * 谁被碰过、按什么顺序。
     *
     * ⚠️ 审计日志也往这里写 —— 只记通道调用的话，"先执行后记账"这个性质
     *    根本没有观察点。
     */
    private val trace = mutableListOf<String>()

    @Before
    fun clearTrace() {
        trace.clear()
    }

    private class TracingSettings(
        private val trace: MutableList<String>,
        private val readValue: String? = "旧值",
        private val writeResultFor: (key: String) -> ChannelResult = { ChannelResult.Succeeded() },
    ) : SettingsAccess {

        override suspend fun read(namespace: SettingNamespace, key: String): String? {
            trace += "settings.read"
            return readValue
        }

        override suspend fun write(
            namespace: SettingNamespace,
            key: String,
            value: String,
        ): ChannelResult {
            trace += "settings.write"
            return writeResultFor(key)
        }
    }

    private class TracingShell(
        private val trace: MutableList<String>,
        private val result: ChannelResult = ChannelResult.Succeeded(),
    ) : ShellRunner {

        override suspend fun run(argv: List<String>): ChannelResult {
            trace += "shell.run"
            return result
        }
    }

    /** 默认全部放行 —— 把"放行"这一维从大多数测试里消掉。 */
    private fun runtime(
        settings: SettingsAccess? = null,
        shell: ShellRunner? = null,
        isGranted: (Capability) -> Boolean = { true },
        now: Long = FIXED_NOW,
    ): Pair<CapabilityRuntime, CapabilityAuditLog> {
        val log = CapabilityAuditLog(sink = { trace += "audit" })
        val runtime = CapabilityRuntime(
            guard = CapabilityGuard(catalog, isGranted),
            runner = CapabilityRunner(settings = settings, shell = shell),
            auditLog = log,
            clock = { now },
        )
        return runtime to log
    }

    private fun brightness(level: String = "120") =
        CapabilityCall("display.brightness", mapOf("level" to level), confirmedByUser = true)

    // ══════════════════════════════════════════════════════════
    //  三个分支各自做什么
    // ══════════════════════════════════════════════════════════

    @Test
    fun `被拒绝时不碰任何通道，但仍然记了账`() = runTest {
        // ⚠️ 必须显式用 `isGranted = { false }` 才会走到拒绝分支。
        //    默认的「全部放行 + 已确认」会让 brightness（GUARDED）直接放行 ——
        //    第一版就是这么写的，于是这条断言的是 Allowed 而不是 Blocked，
        //    而失败信息只有一个 `AssertionError`，看不出"我搞错了前提"。
        val (runtime, log) = runtime(
            settings = TracingSettings(trace),
            isGranted = { false },
        )

        val outcome = runtime.execute(brightness())

        assertTrue(outcome is CapabilityOutcome.Blocked)
        assertEquals(
            "拒绝了就什么都不该碰，但账要记",
            listOf("audit"),
            trace,
        )
        assertEquals(1, log.events().size)
    }

    @Test
    fun `未放行记的是「还没配置」而不是「被拒绝」`() = runTest {
        val (runtime, log) = runtime(settings = TracingSettings(trace), isGranted = { false })

        runtime.execute(brightness())

        assertEquals(
            "「去放行」和「被拦了」在界面上是两种完全不同的提示",
            CapabilityAuditOutcome.NOT_GRANTED,
            log.events().single().outcome,
        )
    }

    @Test
    fun `需要确认时既不执行也不记账`() = runTest {
        // ⚠️ 这条钉的是一个**刻意的省略**：日志里没有"请求确认"这个事件，
        //    只有 confirmationResolved（用户答了/超时了）。
        //    如果哪天有人"顺手补一条"，缓冲会被"问了没人答"填满 ——
        //    而那类记录没有任何可操作信息。
        val (runtime, log) = runtime(settings = TracingSettings(trace))

        val outcome = runtime.execute(
            CapabilityCall("display.brightness", mapOf("level" to "120")),
        )

        assertTrue(outcome is CapabilityOutcome.NeedsConfirmation)
        assertEquals("既没执行也没记账", emptyList<String>(), trace)
        assertEquals(0, log.events().size)
    }

    @Test
    fun `放行的动作先执行、后记账`() = runTest {
        // ★ 顺序断言。只看最终状态的话，"先记账后执行"与这一条**完全一样**。
        val (runtime, _) = runtime(settings = TracingSettings(trace))

        runtime.execute(brightness())

        assertEquals(
            "先读原值、再写、最后记账",
            listOf("settings.read", "settings.write", "audit"),
            trace,
        )
    }

    @Test
    fun `没有确认环节的执行记 EXECUTED`() = runTest {
        // ⚠️ 必须用一条 **SAFE** 能力（`display.auto_rotate`）—— 它不需要确认，
        //    所以 `wasConfirmed = false`，记的是 EXECUTED。
        //    用 brightness（GUARDED + confirmedByUser）拿到的是
        //    EXECUTED_AFTER_CONFIRM —— 那是下一条测试守的东西。
        //    ⚠️ 这两条测试的区别**只有能力不同**，所以它们也在守同一件事：
        //       「wasConfirmed 如实反映了这次放行是不是确认换来的」。
        val (runtime, log) = runtime(settings = TracingSettings(trace))

        val outcome = runtime.execute(
            CapabilityCall("display.auto_rotate", mapOf("enabled" to "1")),
        )

        assertTrue(outcome is CapabilityOutcome.Executed)
        assertEquals(CapabilityAuditOutcome.EXECUTED, log.events().single().outcome)
    }

    @Test
    fun `确认过的执行记 EXECUTED_AFTER_CONFIRM`() = runTest {
        // 审计日志要靠这个区分"用户确认过多少次这类操作"。
        val (runtime, log) = runtime(settings = TracingSettings(trace))

        runtime.execute(brightness())

        assertEquals(
            "「确认过」这件事必须能在日志里查出来",
            CapabilityAuditOutcome.EXECUTED_AFTER_CONFIRM,
            log.events().single().outcome,
        )
    }

    @Test
    fun `shell 类能力走 shell 通道，不经过设置通道`() = runTest {
        val (runtime, _) = runtime(
            settings = TracingSettings(trace),
            shell = TracingShell(trace),
        )

        runtime.execute(CapabilityCall("media.dispatch", mapOf("action" to "pause")))

        assertEquals(listOf("shell.run", "audit"), trace)
    }

    // ══════════════════════════════════════════════════════════
    //  通道不可用 —— 与"被拒绝"是两件事
    // ══════════════════════════════════════════════════════════

    @Test
    fun `通道没接上时记的是失败，不是被拒绝`() = runTest {
        // ★ 这条把「两套授权」的区分一路钉到日志里：
        //    "本体放行了这条能力"（裁决层）与"系统给了这个权限"（通道层）
        //    是彼此独立的。混成一个 Boolean 之后，用户看到的只有"失败"，
        //    而那个提示对"去跑 adb"和"命令写错了"**都是错的指引**。
        val (runtime, log) = runtime(settings = null)

        runtime.execute(brightness())

        assertEquals(
            CapabilityAuditOutcome.FAILED,
            log.events().single().outcome,
        )
        assertTrue(
            "日志要能看出是通道不可用，而不是命令出错",
            log.events().single().detail.contains("通道不可用"),
        )
    }

    @Test
    fun `通道不可用时返回的是 Executed 而不是 Blocked`() = runTest {
        // ⚠️ 名字看起来别扭，但语义是对的：裁决**通过了**，执行环节没成。
        //    返回 Blocked 的话，界面会去显示"这次调用被拒绝" ——
        //    而用户该做的是去授权，不是去改调用。
        val (runtime, _) = runtime(settings = null)

        val outcome = runtime.execute(brightness())

        assertTrue(outcome is CapabilityOutcome.Executed)
        assertTrue(
            (outcome as CapabilityOutcome.Executed).result is ChannelResult.Unavailable,
        )
    }

    // ══════════════════════════════════════════════════════════
    //  连续同因合并
    // ══════════════════════════════════════════════════════════

    @Test
    fun `连续同因的通道失败只记第一条`() = runTest {
        // 不合并的后果：500 格的环形缓冲被同一句话填满，
        // 而那份日志是用户判断"这个插件想干什么"的唯一线索。
        val (runtime, log) = runtime(settings = null)

        repeat(5) { runtime.execute(brightness()) }

        assertEquals("同一件事重复 5 次，只该留一条", 1, log.events().size)
    }

    @Test
    fun `不同能力的同类失败各记一条`() = runTest {
        // ⚠️ 反向：即使原因文案一模一样，也不合并。
        //    它们的 targetDigest 不同 —— "想动哪个目标"是那份日志最主要的用途。
        val (runtime, log) = runtime(settings = null)

        runtime.execute(brightness())
        runtime.execute(
            CapabilityCall(
                "display.screen_off_timeout",
                mapOf("millis" to "60000"),
                confirmedByUser = true,
            ),
        )

        assertEquals(2, log.events().size)
        assertEquals(
            "两条的 targetDigest 必须不同，否则合并就等于把目标信息丢了",
            2,
            log.events().map { it.targetDigest }.toSet().size,
        )
    }

    @Test
    fun `中间夹了一次成功之后，同样的失败会再记一条`() = runTest {
        // 合并比的是**紧邻的上一条**，不是"历史上出现过没有"。
        // 写成后者的话，一个反复失败、偶尔成功的任务会被记成"只失败过一次"。
        val (runtime, log) = runtime(
            settings = TracingSettings(
                trace = trace,
                writeResultFor = { key ->
                    if (key == "accelerometer_rotation") ChannelResult.Succeeded()
                    else ChannelResult.Failed("写不进去")
                },
            ),
        )

        runtime.execute(brightness())                                   // 失败 → 记
        runtime.execute(                                                // 成功 → 记
            CapabilityCall("display.auto_rotate", mapOf("enabled" to "1")),
        )
        runtime.execute(brightness())                                   // 失败 → 再记

        assertEquals(3, log.events().size)
    }

    @Test
    fun `被拒绝的调用不会被合并`() = runTest {
        // ★ 反向断言。反复出现的 DENIED 正是最该看到的信号：
        //   "一个反复请求停用系统组件的插件"就是靠连续多条 DENIED 看出来的。
        //   把合并规则顺手套到拒绝上，等于把这条线索删掉。
        val (runtime, log) = runtime(settings = TracingSettings(trace), isGranted = { false })

        repeat(3) { runtime.execute(brightness()) }

        assertEquals("拒绝必须逐条留痕", 3, log.events().size)
    }

    @Test
    fun `成功执行也不会被合并`() = runTest {
        val (runtime, log) = runtime(settings = TracingSettings(trace))

        repeat(3) { runtime.execute(brightness()) }

        assertEquals(3, log.events().size)
    }

    // ══════════════════════════════════════════════════════════
    //  时间戳
    // ══════════════════════════════════════════════════════════

    @Test
    fun `时间戳来自注入的时钟`() = runTest {
        // 钉住"没有偷偷用 System.currentTimeMillis"—— 否则审计日志
        // 在测试里不可断言，而"任务是什么时候跑的"是它存在的理由之一。
        val (runtime, log) = runtime(settings = TracingSettings(trace), now = 1_234_567_890L)

        runtime.execute(brightness())

        assertEquals(1_234_567_890L, log.events().single().timestamp)
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
