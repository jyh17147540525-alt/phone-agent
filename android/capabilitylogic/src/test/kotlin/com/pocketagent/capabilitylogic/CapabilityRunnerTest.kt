package com.pocketagent.capabilitylogic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通道层 —— 派发、argv 过边界、先读后写、结果透传、审计映射。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★★ 这一层为什么值得单独测
 * ═══════════════════════════════════════════════════════════════
 *
 * [CapabilityGuard] 已经把「能不能做」判完了，所以通道层看起来只是
 * 「把对象转手交给端口」—— 一段不需要测的胶水。**但它不是**：
 *
 * | 写错的形态 | 后果 | 会不会报错 |
 * |---|---|---|
 * | argv 过边界时被拼成字符串 | 注入防护当场作废 | 不会 |
 * | 设置写入派给了 shell 端口 | 走错通道，权限模型失效 | 不会 |
 * | 先写后读原值 | 撤销把设置恢复成它刚变成的样子 | 不会 |
 * | 端口缺失时抛异常 | 用户看到「出错了」而不是「去配置」 | 会，但信息无用 |
 * | 读原值失败导致整个操作失败 | 为了记不上一个可有可无的值而没做事 | 不会 |
 *
 * 前四条**都不会报错**，只会在某一天安静地做错一件事。这正是本项目
 * 反复吃亏的那一类，所以这里断言的是**形状**（几项、顺序、是哪个分支），
 * 而不是「结果看起来对不对」。
 *
 * ⚠️ 两个桩都**只记录不判断** —— 一旦桩里出现 `if`，测的就是桩而不是被测对象。
 */
class CapabilityRunnerTest {

    // ══════════════════════════════════════════════════════════
    //  桩
    // ══════════════════════════════════════════════════════════

    private class FakeSettings(
        private val readResult: String? = null,
        private val readThrows: Exception? = null,
        private val writeResult: ChannelResult = ChannelResult.Succeeded(),
    ) : SettingsAccess {

        /** 调用序列。用来钉「先读后写」这个顺序。 */
        val calls = mutableListOf<String>()

        var written: Triple<SettingNamespace, String, String>? = null

        override suspend fun read(namespace: SettingNamespace, key: String): String? {
            calls += "read"
            readThrows?.let { throw it }
            return readResult
        }

        override suspend fun write(
            namespace: SettingNamespace,
            key: String,
            value: String,
        ): ChannelResult {
            calls += "write"
            written = Triple(namespace, key, value)
            return writeResult
        }
    }

    private class FakeShell(
        private val result: ChannelResult = ChannelResult.Succeeded(),
    ) : ShellRunner {

        var calls = 0
        var receivedArgv: List<String>? = null

        override suspend fun run(argv: List<String>): ChannelResult {
            calls++
            receivedArgv = argv
            return result
        }
    }

    // ══════════════════════════════════════════════════════════
    //  测试数据
    // ══════════════════════════════════════════════════════════

    private fun settingWrite(
        namespace: SettingNamespace = SettingNamespace.SYSTEM,
        key: String = "screen_brightness",
        value: String = "128",
    ) = PlannedExecution.SettingWrite(
        namespace = namespace,
        key = key,
        value = value,
        summary = "把屏幕亮度调到 $value",
    )

    private fun shellArgv(vararg argv: String) = PlannedExecution.ShellArgv(
        argv = argv.toList(),
        summary = "测试用命令",
    )

    // ══════════════════════════════════════════════════════════
    //  一、派发：哪一类动作走哪个端口
    // ══════════════════════════════════════════════════════════

    @Test
    fun `设置写入只到设置通道，shell 通道一次都不碰`() = runTest {
        val settings = FakeSettings()
        val shell = FakeShell()
        val runner = CapabilityRunner(settings = settings, shell = shell)

        runner.run(settingWrite())

        // ⚠️ 这里**只**断言「哪个端口被碰过」，不断言顺序 ——
        //    顺序由 `读与写的顺序不能反` 专门负责。
        //    第一版写成 `assertEquals(listOf("read", "write"), calls)`，
        //    于是「顺序反了」时这条也会变红，而它的名字与消息都在说端口：
        //    失败信息会指向错误的概念（与 check_kt_quotes 报错行号前移同类）。
        assertEquals(
            "设置通道该被用到，而且只有它被用到",
            setOf("read", "write"),
            settings.calls.toSet(),
        )
        assertEquals("shell 通道不该被碰到", 0, shell.calls)
    }

    @Test
    fun `shell 命令只到 shell 通道，设置通道一次都不碰`() = runTest {
        val settings = FakeSettings()
        val shell = FakeShell()
        val runner = CapabilityRunner(settings = settings, shell = shell)

        runner.run(shellArgv("cmd", "media_session", "volume", "--show"))

        assertEquals("shell 通道应当被调用一次", 1, shell.calls)
        assertEquals("设置通道不该被碰到", emptyList<String>(), settings.calls)
    }

    @Test
    fun `设置写入带上命名空间、键与值`() = runTest {
        val settings = FakeSettings()
        val runner = CapabilityRunner(settings = settings)

        runner.run(settingWrite(namespace = SettingNamespace.SECURE, key = "x", value = "y"))

        assertEquals(
            "命名空间必须单独传，不能拼进键",
            Triple(SettingNamespace.SECURE, "x", "y"),
            settings.written,
        )
    }

    // ══════════════════════════════════════════════════════════
    //  二、★★★ argv 必须以数组形式穿过边界
    // ══════════════════════════════════════════════════════════

    @Test
    fun `argv 原样穿过端口，一个项都不多不少`() = runTest {
        val shell = FakeShell()
        val runner = CapabilityRunner(shell = shell)
        val execution = shellArgv("cmd", "package", "disable", "com.example.app")

        runner.run(execution)

        assertEquals("端口收到的 argv 必须与规划结果完全一致", execution.argv, shell.receivedArgv)
    }

    @Test
    fun `含分号与管道的参数仍然是一个 argv 项`() = runTest {
        val shell = FakeShell()
        val runner = CapabilityRunner(shell = shell)

        // 这三个都是「如果实现方把 argv 拼成字符串再交给 shell」时
        // 会变成另一条命令的东西。
        runner.run(shellArgv("cmd", "notification", "post", "a; rm -rf /", "b | cat /etc/passwd"))

        val argv = shell.receivedArgv
        assertNotNull(argv)
        assertEquals("分号不能被当成命令分隔符切开", 5, argv!!.size)
        assertEquals("a; rm -rf /", argv[3])
        assertEquals("b | cat /etc/passwd", argv[4])
    }

    @Test
    fun `含反引号与命令替换的参数仍然是一个 argv 项`() = runTest {
        val shell = FakeShell()
        val runner = CapabilityRunner(shell = shell)

        runner.run(shellArgv("cmd", "notification", "post", "`id`", "\$(id)", "a b"))

        val argv = shell.receivedArgv
        assertNotNull(argv)
        assertEquals(6, argv!!.size)
        assertEquals("`id`", argv[3])
        assertEquals("\$(id)", argv[4])
        assertEquals("带空格的参数不能被拆开", "a b", argv[5])
    }

    // ══════════════════════════════════════════════════════════
    //  三、端口没接上 —— 返回结论，不抛异常
    // ══════════════════════════════════════════════════════════

    @Test
    fun `没有接设置通道时返回不可用而不是抛异常`() = runTest {
        val runner = CapabilityRunner(settings = null, shell = FakeShell())

        val result = runner.run(settingWrite())

        assertTrue("应当是 Unavailable，实际是 $result", result is ChannelResult.Unavailable)
        assertEquals(
            ChannelUnavailableReason.PORT_NOT_CONFIGURED,
            (result as ChannelResult.Unavailable).reason,
        )
    }

    @Test
    fun `没有接 shell 通道时返回不可用而不是抛异常`() = runTest {
        val runner = CapabilityRunner(settings = FakeSettings(), shell = null)

        val result = runner.run(shellArgv("cmd", "media_session", "volume", "--show"))

        assertTrue("应当是 Unavailable，实际是 $result", result is ChannelResult.Unavailable)
        assertEquals(
            ChannelUnavailableReason.PORT_NOT_CONFIGURED,
            (result as ChannelResult.Unavailable).reason,
        )
    }

    @Test
    fun `只接了设置通道时 shell 命令仍然报不可用`() = runTest {
        val runner = CapabilityRunner(settings = FakeSettings())

        val result = runner.run(shellArgv("cmd", "media_session", "volume", "--show"))

        assertFalse("不能因为另一个端口接上了就把这一类也当成可用", result.isSuccess)
        assertTrue(result is ChannelResult.Unavailable)
    }

    @Test
    fun `不可用的说明里说清了是哪一条通道`() = runTest {
        val runner = CapabilityRunner()

        val setting = runner.run(settingWrite()) as ChannelResult.Unavailable
        val shell = runner.run(shellArgv("cmd")) as ChannelResult.Unavailable

        assertTrue(
            "设置通道的说明应当提到设置：${setting.detail}",
            setting.detail.contains("设置"),
        )
        assertTrue(
            "shell 通道的说明应当提到 shell：${shell.detail}",
            shell.detail.contains("shell"),
        )
    }

    // ══════════════════════════════════════════════════════════
    //  四、★★ 先读后写
    // ══════════════════════════════════════════════════════════

    @Test
    fun `写入之前先读原值，并把原值带回来`() = runTest {
        val settings = FakeSettings(readResult = "200")
        val runner = CapabilityRunner(settings = settings)

        val result = runner.run(settingWrite(value = "128"))

        assertTrue(result is ChannelResult.Succeeded)
        assertEquals("原值必须被带回来，撤销账本靠它", "200", (result as ChannelResult.Succeeded).previousValue)
    }

    @Test
    fun `读与写的顺序不能反`() = runTest {
        val settings = FakeSettings(readResult = "200")
        val runner = CapabilityRunner(settings = settings)

        runner.run(settingWrite(value = "128"))

        // 反过来写不会报任何错，只会把新值当成旧值记下来 ——
        // 于是撤销会把设置「恢复」成它刚变成的那个样子。
        assertEquals("必须先读原值再写", listOf("read", "write"), settings.calls)
    }

    @Test
    fun `读不到原值时照样写入`() = runTest {
        val settings = FakeSettings(readResult = null)
        val runner = CapabilityRunner(settings = settings)

        val result = runner.run(settingWrite())

        assertTrue(result is ChannelResult.Succeeded)
        assertNull("读不到就是 null，不能编一个", (result as ChannelResult.Succeeded).previousValue)
        assertNotNull("读不到原值不能阻止写入", settings.written)
    }

    @Test
    fun `读抛异常时照样写入`() = runTest {
        val settings = FakeSettings(readThrows = IllegalStateException("遥控调用失败"))
        val runner = CapabilityRunner(settings = settings)

        val result = runner.run(settingWrite())

        assertTrue("读原值是可有可无的信息，不能因此让整个操作失败", result.isSuccess)
        assertNull((result as ChannelResult.Succeeded).previousValue)
        assertNotNull(settings.written)
    }

    @Test
    fun `读抛取消异常时原样抛出，且不发生写入`() = runTest {
        val settings = FakeSettings(readThrows = CancellationException("用户点了停止"))
        val runner = CapabilityRunner(settings = settings)

        var caught: CancellationException? = null
        try {
            runner.run(settingWrite())
        } catch (cancellation: CancellationException) {
            caught = cancellation
        }

        assertNotNull("取消必须原样抛出 —— 吞掉它会让「点了停止，任务还在跑」", caught)
        assertEquals("用户点了停止", caught!!.message)
        assertEquals("取消之后不该再写入", listOf("read"), settings.calls)
    }

    // ══════════════════════════════════════════════════════════
    //  五、结果透传 —— 不美化、不伪造
    // ══════════════════════════════════════════════════════════

    @Test
    fun `写入失败时原样返回端口的结论`() = runTest {
        val failure = ChannelResult.Failed("写入被系统拒绝")
        val settings = FakeSettings(readResult = "200", writeResult = failure)
        val runner = CapabilityRunner(settings = settings)

        val result = runner.run(settingWrite())

        assertEquals("失败结论必须原样返回，不能包装成别的分支", failure, result)
    }

    @Test
    fun `写入报权限被拒时原样返回不可用`() = runTest {
        val unavailable = ChannelResult.Unavailable(
            ChannelUnavailableReason.PERMISSION_DENIED,
            "需要 WRITE_SECURE_SETTINGS",
        )
        val runner = CapabilityRunner(settings = FakeSettings(writeResult = unavailable))

        val result = runner.run(settingWrite())

        assertEquals(unavailable, result)
        assertEquals(
            "权限被拒不能被降级成「失败」—— 两者给用户的指引不同",
            ChannelUnavailableReason.PERMISSION_DENIED,
            (result as ChannelResult.Unavailable).reason,
        )
    }

    @Test
    fun `shell 失败时的退出码被保留`() = runTest {
        val runner = CapabilityRunner(
            shell = FakeShell(ChannelResult.Failed("命令返回非零", exitCode = 1)),
        )

        val result = runner.run(shellArgv("cmd", "package", "disable", "com.example.app"))

        assertEquals(1, (result as ChannelResult.Failed).exitCode)
    }

    @Test
    fun `isSuccess 只在成功时为真`() = runTest {
        assertTrue(ChannelResult.Succeeded().isSuccess)
        assertFalse(ChannelResult.Unavailable(ChannelUnavailableReason.SERVICE_NOT_RUNNING).isSuccess)
        assertFalse(ChannelResult.Failed("x").isSuccess)
    }

    // ══════════════════════════════════════════════════════════
    //  六、原值绝不外泄
    // ══════════════════════════════════════════════════════════

    @Test
    fun `原值不会出现在结果的 toString 里`() {
        val secret = "给妈妈打个电话"

        val text = ChannelResult.Succeeded(previousValue = secret).toString()

        assertFalse(
            "data class 自动生成的 toString 会把原值打出来 —— 一句 Log.d 就把它写进日志了",
            text.contains(secret),
        )
        assertTrue("但要说清有一个值被隐去了：$text", text.contains("已隐去"))
        assertTrue("并且说得出它有多长：$text", text.contains("${secret.length} 字符"))
        assertTrue(
            "没有原值时照实说 null，不能连这一支都省掉",
            ChannelResult.Succeeded().toString().contains("previousValue=null"),
        )
    }

    @Test
    fun `读到内容也不会出现在结果的 toString 里`() {
        // ⚠️ [payload] 比 [previousValue] **更**需要隐去：前者是一个设置值
        //    （几个字符），后者可能是一整份用户文件。同一个疏漏落在两者上，
        //    后果差好几个数量级。
        val fileContent = "这是用户的私事，不该出现在任何日志里"

        val text = ChannelResult.Succeeded(payload = fileContent).toString()

        assertFalse("用户文件正文被 toString 打出来了：$text", text.contains(fileContent))
        assertFalse("连片段都不行：$text", text.contains("用户的私事"))
        assertTrue("但要说清有一段内容被隐去了：$text", text.contains("已隐去"))
        assertTrue("并且说得出它有多长：$text", text.contains("${fileContent.length} 字符"))
        assertTrue(
            "没有内容时照实说 null，不能连这一支都省掉",
            ChannelResult.Succeeded().toString().contains("payload=null"),
        )
    }

    // ══════════════════════════════════════════════════════════
    //  七、审计映射
    // ══════════════════════════════════════════════════════════

    @Test
    fun `不可用被记成失败，且说明里带上原因码`() {
        val event = CapabilityAuditEvents.channelFailed(
            timestamp = 7L,
            capabilityId = "display.brightness",
            origin = CallOrigin.AGENT,
            execution = settingWrite(),
            result = ChannelResult.Unavailable(
                ChannelUnavailableReason.PERMISSION_DENIED,
                "需要 WRITE_SECURE_SETTINGS",
            ),
        )

        assertEquals(CapabilityAuditOutcome.FAILED, event.outcome)
        assertTrue("原因码要留在说明里：${event.detail}", event.detail.contains("PERMISSION_DENIED"))
        assertTrue("端口给的说明也要留：${event.detail}", event.detail.contains("WRITE_SECURE_SETTINGS"))
        assertEquals("system/screen_brightness", event.targetDigest)
    }

    @Test
    fun `不可用没有补充说明时回落成原因的人话`() {
        val event = CapabilityAuditEvents.channelFailed(
            timestamp = 7L,
            capabilityId = "display.brightness",
            origin = CallOrigin.AGENT,
            execution = settingWrite(),
            result = ChannelResult.Unavailable(ChannelUnavailableReason.SERVICE_NOT_RUNNING),
        )

        assertTrue(
            "不能留一句空说明：${event.detail}",
            event.detail.contains(ChannelUnavailableReason.SERVICE_NOT_RUNNING.displayName),
        )
    }

    @Test
    fun `失败被记成失败，且说明里带上退出码`() {
        val event = CapabilityAuditEvents.channelFailed(
            timestamp = 7L,
            capabilityId = "app.set_enabled",
            origin = CallOrigin.SCHEDULE,
            execution = shellArgv("cmd", "package", "disable", "com.example.app"),
            result = ChannelResult.Failed("命令返回非零", exitCode = 1),
        )

        assertEquals(CapabilityAuditOutcome.FAILED, event.outcome)
        assertEquals(CallOrigin.SCHEDULE, event.origin)
        assertTrue("退出码是判别依据之一：${event.detail}", event.detail.contains("退出码 1"))
        assertEquals("com.example.app", event.targetDigest)
    }

    @Test
    fun `把成功当成失败上报会当场抛错`() {
        assertThrows(IllegalStateException::class.java) {
            CapabilityAuditEvents.channelFailed(
                timestamp = 7L,
                capabilityId = "display.brightness",
                origin = CallOrigin.AGENT,
                execution = settingWrite(),
                result = ChannelResult.Succeeded(),
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    //  八、与裁决层的接缝
    // ══════════════════════════════════════════════════════════

    @Test
    fun `放行的执行对象被原样送到端口，中间不重新规划`() = runTest {
        val guard = CapabilityGuard(CapabilityCatalog())
        val shell = FakeShell()
        val runner = CapabilityRunner(shell = shell)

        val allowed = guard.decide(
            CapabilityCall("media.dispatch", mapOf("action" to "play")),
        ) as CapabilityVerdict.Allowed

        runner.run(allowed.execution)

        val planned = allowed.execution as PlannedExecution.ShellArgv
        assertEquals(
            "端口收到的必须是裁决时那一个 argv —— 执行方没有自由度",
            planned.argv,
            shell.receivedArgv,
        )
    }

    @Test
    fun `被拒绝的调用根本没有可执行的对象，因此到不了通道层`() = runTest {
        val guard = CapabilityGuard(CapabilityCatalog())
        val shell = FakeShell()
        val runner = CapabilityRunner(shell = shell)

        // 参数非法 —— 裁决在第 3 步就停了，不会产出 PlannedExecution。
        val verdict = guard.decide(
            CapabilityCall("media.dispatch", mapOf("action" to "这不是一个动作")),
        )

        assertTrue(verdict is CapabilityVerdict.Blocked)
        assertEquals("既然没有执行对象，通道就不该被碰到", 0, shell.calls)
        // runner 只在拿到 Allowed.execution 时才会被调用 —— 这里没有那个对象可传。
        assertNull(shell.receivedArgv)
    }
}
