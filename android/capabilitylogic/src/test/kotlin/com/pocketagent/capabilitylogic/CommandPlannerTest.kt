package com.pocketagent.capabilitylogic

import com.pocketagent.filelogic.FileOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规划器 —— 重点是**证明"注入在结构上不可能"**，而不是证明"它算得对"。
 *
 * ⚠️ 这里最值得钉住的一条：[ExecutionRecipe.Shell] 的"用 argv 数组而不是
 *    拼字符串"是一个**架构承诺**。承诺写在注释里是没有约束力的 ——
 *    改坏它的人只需要把 `List<String>` 换成 `String`，而所有既有测试
 *    照样全绿（它们断言的命令内容一模一样）。
 *
 *    所以下面那几条测试断言的是**形状**（argv 有几项、元字符在不在同一项里），
 *    而不是内容 —— 形状变了就红。
 */
class CommandPlannerTest {

    /** 一条只做"回显"的测试用能力，参数接受任意非控制字符文本。 */
    private fun echoCapability() = Capability(
        id = "test.echo",
        channel = CapabilityChannel.SHELL,
        group = CapabilityGroup.DEVICE,
        risk = CapabilityRisk.SAFE,
        summary = "测试用",
        params = listOf(
            ParamSpec.Text(
                name = "v",
                maxLength = 64,
                allowed = Regex("""^[^\p{Cntrl}]+$"""),
                summary = "任意文本",
            ),
        ),
        recipe = ExecutionRecipe.Shell(
            listOf(
                ExecutionRecipe.Shell.Segment.Literal("cmd"),
                ExecutionRecipe.Shell.Segment.Literal("echo"),
                ExecutionRecipe.Shell.Segment.Param("v"),
            ),
        ),
    )

    // ══════════════════════════════════════════════════════════
    //  ★★★ 注入在结构上不可能
    // ══════════════════════════════════════════════════════════

    @Test
    fun `参数值里的 shell 元字符不会被拆成多个 argv 项`() {
        // ⚠️ 如果哪天有人把 argv 改成"先拼成字符串、再交给 shell"，
        //    这一行会从"一个参数"变成"两条命令" —— 而那种改动
        //    在只看命令内容的测试里是**完全看不出来**的。
        val execution = CommandPlanner.plan(
            echoCapability(),
            mapOf("v" to "a; rm -rf /"),
        ) as PlannedExecution.ShellArgv

        assertEquals(listOf("cmd", "echo", "a; rm -rf /"), execution.argv)
        assertEquals("元字符被拆开了，说明经过了 shell", 3, execution.argv.size)
    }

    @Test
    fun `参数值里的空格不会把一项拆成两项`() {
        // ⚠️ 这是"数组 vs 字符串"最日常的表现形态：一个含空格的通知正文
        //    如果被拆成多个 argv 项，`cmd notification post` 收到的参数个数
        //    就错了 —— 而它**不会报错**，只会发出一条内容不对的通知。
        val execution = CommandPlanner.plan(
            echoCapability(),
            mapOf("v" to "hello world"),
        ) as PlannedExecution.ShellArgv

        assertEquals(3, execution.argv.size)
        assertEquals("hello world", execution.argv[2])
    }

    @Test
    fun `参数值里的管道与反引号同样是普通字符`() {
        val execution = CommandPlanner.plan(
            echoCapability(),
            mapOf("v" to "x | cat /etc/passwd `id`"),
        ) as PlannedExecution.ShellArgv

        assertEquals(3, execution.argv.size)
        assertEquals("x | cat /etc/passwd `id`", execution.argv[2])
    }

    // ══════════════════════════════════════════════════════════
    //  内置能力的展开
    // ══════════════════════════════════════════════════════════

    @Test
    fun `内置 shell 能力产出预期的 argv`() {
        val capability = CapabilityCatalog().byId("media.dispatch")!!
        val execution = CommandPlanner.plan(capability, mapOf("action" to "pause"))
            as PlannedExecution.ShellArgv

        assertEquals(listOf("cmd", "media_session", "dispatch", "pause"), execution.argv)
    }

    @Test
    fun `内置设置能力产出设置写入`() {
        val capability = CapabilityCatalog().byId("display.brightness")!!
        val execution = CommandPlanner.plan(capability, mapOf("level" to "120"))
            as PlannedExecution.SettingWrite

        assertEquals(SettingNamespace.SYSTEM, execution.namespace)
        assertEquals("screen_brightness", execution.key)
        assertEquals("120", execution.value)
    }

    @Test
    fun `设置能力写的是配方里的字面量键，不受参数影响`() {
        // ⚠️ 这条钉住的是"键名不能由参数决定"。如果哪天有人给
        //    ExecutionRecipe.Setting 加一个 keyParam，这条测试不会红 ——
        //    所以它只是**必要不充分**的守卫。真正的守卫是那个类里
        //    "键是字面量"的注释与 CapabilityDenyRules 的键白名单。
        //    留这条是因为它至少能挡住"键被参数覆盖"这种最直接的写法。
        val capability = CapabilityCatalog().byId("display.auto_rotate")!!
        val execution = CommandPlanner.plan(capability, mapOf("enabled" to "1"))
            as PlannedExecution.SettingWrite

        assertEquals("accelerometer_rotation", execution.key)
        assertEquals(SettingNamespace.SYSTEM, execution.namespace)
    }

    // ══════════════════════════════════════════════════════════
    //  展示文案
    // ══════════════════════════════════════════════════════════

    @Test
    fun `shell 的展示文案是空格拼接的完整命令`() {
        val capability = CapabilityCatalog().byId("wifi.set_enabled")!!
        val execution = CommandPlanner.plan(capability, mapOf("state" to "disabled"))

        assertEquals("cmd wifi set-wifi-enabled disabled", execution.summary)
    }

    @Test
    fun `设置写入的展示文案能直接和系统设置对上号`() {
        val capability = CapabilityCatalog().byId("display.stay_awake_while_plugged")!!
        val execution = CommandPlanner.plan(capability, mapOf("mode" to "7"))

        assertEquals("global/stay_on_while_plugged_in = 7", execution.summary)
    }

    @Test
    fun `通知能力的展示文案会带上正文，供用户确认`() {
        // ⚠️ 这是刻意的：用户确认的就是"我要发出去的这句话"。
        //    代价是 summary 里含用户内容 —— 所以它**绝不能进审计日志**
        //    （见 CapabilityAuditLogTest 里那条对应的测试）。
        val capability = CapabilityCatalog().byId("notification.post")!!
        val execution = CommandPlanner.plan(
            capability,
            mapOf("tag" to "提醒", "text" to "给妈妈打个电话"),
        )

        assertTrue(
            "确认文案里应当能看到通知正文，实际是「${execution.summary}」",
            execution.summary.contains("给妈妈打个电话"),
        )
    }

    // ══════════════════════════════════════════════════════════
    //  契约违约（绕过 CapabilityGuard 直接调用）
    // ══════════════════════════════════════════════════════════

    @Test
    fun `缺少参数时抛出契约异常而不是静默取空串`() {
        // ⚠️ 静默取空串的后果很具体：`cmd media_session dispatch ""` ——
        //    命令语法完整、会执行、返回非零，而用户看到的是"什么都没发生"。
        val capability = CapabilityCatalog().byId("media.dispatch")!!
        val error = runCatching { CommandPlanner.plan(capability, emptyMap()) }.exceptionOrNull()

        assertTrue("应当抛 IllegalArgumentException，实际是 $error", error is IllegalArgumentException)
    }

    @Test
    fun `参数值存在但是空串时也抛异常`() {
        // ⚠️ 刻意与"缺键"分开测：`args[name] ?: throw` 这种写法会把
        //    两种情况混成一种，而"值为空串"恰恰是最容易在放松参数校验后
        //    漏进来的形态。
        val capability = CapabilityCatalog().byId("media.dispatch")!!
        val error = runCatching {
            CommandPlanner.plan(capability, mapOf("action" to ""))
        }.exceptionOrNull()

        assertTrue("应当抛 IllegalArgumentException，实际是 $error", error is IllegalArgumentException)
    }

    @Test
    fun `全部内置能力都能用合法参数展开`() {
        // 一条"扫一遍目录"的测试：新增能力时如果配方与参数对不上，
        // 这里会红，而不是等到真机上某个功能点不动。
        val sampleArgs = mapOf(
            "action" to "pause",
            "stream" to "3",
            "index" to "5",
            "direction" to "raise",
            "mode" to "auto",
            "key" to "KEYCODE_SLEEP",
            "tag" to "t",
            "text" to "hello",
            "state" to "enabled",
            "packageName" to "com.example.app",
            "level" to "100",
            "millis" to "60000",
            "enabled" to "1",
            // ★ 文件能力的三个参数（2026-09-23 加）。
            //    ⚠️ 这里刻意用**绝对路径**：相对路径会被 PathNormalizer 拒掉，
            //    而这份对照表是"合法参数"的样本 —— 放个非法值进去会让
            //    上面那段"扫一遍目录"的测试测出错误的结论。
            "path" to "/sdcard/Documents/a.txt",
            "content" to "hello",
            "destination" to "/sdcard/Documents/b.txt",
        )

        for (capability in CapabilityCatalog.BUILT_IN) {
            val args = capability.params.associate { spec ->
                spec.name to when (spec) {
                    is ParamSpec.Choice -> spec.values.first()
                    is ParamSpec.IntIn -> spec.range.first.toString()
                    is ParamSpec.PackageName -> "com.example.app"
                    is ParamSpec.Text -> "x"
                }
            }
            val execution = CommandPlanner.plan(capability, args)
            assertTrue(
                "能力「${capability.id}」的展示文案为空",
                execution.summary.isNotBlank(),
            )
        }

        // sampleArgs 只是给人看的对照表，避免上面变成"凭空造参数"；
        // 它必须覆盖目录里出现过的每个参数名，否则说明有人加了新参数名
        // 却没更新这份对照 —— 而那意味着测试里的构造逻辑已经和目录脱节。
        val allParamNames = CapabilityCatalog.BUILT_IN.flatMap { c -> c.params.map { it.name } }.toSet()
        assertTrue(
            "对照表缺参数名：${allParamNames - sampleArgs.keys}",
            allParamNames.all { it in sampleArgs },
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  文件操作（2026-09-23 新增的执行模型）
    // ══════════════════════════════════════════════════════════════

    private val catalog = CapabilityCatalog()

    private fun planFile(id: String, args: Map<String, String>): PlannedExecution.FileIntent {
        val intent = CommandPlanner.plan(catalog.byId(id)!!, args)
        assertTrue("「$id」应当规划成 FileIntent，实际是 $intent", intent is PlannedExecution.FileIntent)
        return intent as PlannedExecution.FileIntent
    }

    @Test
    fun `文件能力的操作码来自配方而不是参数`() {
        // ⚠️ 这条钉的是**注入面**：如果 op 能从参数决定，
        //    那么「只读」的能力就能被一次调用变成「删除」——
        //    而参数是模型给的。op 必须是配方里的**字面量**。
        assertEquals(FileOp.LIST, planFile("file.list", mapOf("path" to "/sdcard/D")).op)
        assertEquals(FileOp.READ, planFile("file.read", mapOf("path" to "/sdcard/D/a.txt")).op)
        assertEquals(FileOp.WRITE, planFile("file.write", mapOf("path" to "/sdcard/D/a.txt", "content" to "hi")).op)
        assertEquals(FileOp.DELETE, planFile("file.delete", mapOf("path" to "/sdcard/D/a.txt")).op)
        assertEquals(FileOp.MOVE, planFile("file.move", mapOf("path" to "/sdcard/D/a.txt", "destination" to "/sdcard/D/b.txt")).op)
    }

    @Test
    fun `路径原样透传 —— 归一化留给 filelogic`() {
        // ⚠️ 这条契约很反直觉，但**必须**这样：
        //    `PathNormalizer` 在拒绝时会说「只接受绝对路径，收到的是「foo/bar」」——
        //    那句具体的话只有在**原始值还在**的时候才写得出来。
        //    如果这里先归一化一遍，非法路径会变成空串，
        //    用户看到的就只剩一句「路径非法」，而不知道到底是哪里非法。
        //
        //    ⇒ 归一化**只做一次**，在 `FileAccessDecider` 里。
        //      这里再做一遍的后果是两处判据可能不一致，而生效的是松的那一份。
        val intent = planFile("file.read", mapOf("path" to "/sdcard/D/../D/./a.txt"))
        assertEquals("/sdcard/D/../D/./a.txt", intent.path)
    }

    @Test
    fun `只有该带的字段才被填上`() {
        // ⚠️ 读操作**不能**带 content —— 一个"读文件"的请求里出现正文，
        //    意味着有人把两个配方的字段串了，而那可能让写入静默发生。
        val read = planFile("file.read", mapOf("path" to "/sdcard/D/a.txt"))
        assertNull(read.content)
        assertNull(read.destinationPath)

        val write = planFile("file.write", mapOf("path" to "/sdcard/D/a.txt", "content" to "hi"))
        assertEquals("hi", write.content)
        assertNull("写入不该有目标路径 —— 那是移动的字段", write.destinationPath)

        val move = planFile("file.move", mapOf("path" to "/sdcard/D/a.txt", "destination" to "/sdcard/D/b.txt"))
        assertEquals("/sdcard/D/b.txt", move.destinationPath)
        assertNull(move.content)
    }

    @Test
    fun `展示文案里必须出现路径 —— 用户要确认的是哪个文件`() {
        // ⚠️ 这不是"文案好看"的问题：文件操作会弹确认框，而
        //    `ChannelResult.NeedsConfirmation` 那句 `userMessage` 里
        //    **必须带着要动哪个文件** —— 否则用户是在盲签。
        val read = planFile("file.read", mapOf("path" to "/sdcard/D/a.txt"))
        assertTrue("读的文案里要有路径，实际是「${read.summary}」", read.summary.contains("/sdcard/D/a.txt"))

        val move = planFile("file.move", mapOf("path" to "/sdcard/D/a.txt", "destination" to "/sdcard/D/b.txt"))
        assertTrue("移动的文案里要有源路径", move.summary.contains("/sdcard/D/a.txt"))
        assertTrue("移动的文案里要有目标路径", move.summary.contains("/sdcard/D/b.txt"))
    }

    @Test
    fun `写入的文案只说字数 —— 不把正文抄进摘要`() {
        // ⚠️ 摘要会进界面、也可能进日志。正文是用户内容，
        //    按审计纪律"绝不记录参数原值"，它**不能**出现在摘要里。
        val intent = planFile(
            "file.write",
            mapOf("path" to "/sdcard/D/a.txt", "content" to "这是用户的私事"),
        )
        assertFalse("摘要里不能有正文", intent.summary.contains("这是用户的私事"))
        // 「这是用户的私事」是 7 个字 —— 摘要只说数量，不说内容。
        assertTrue("但要说清写了多少字，实际是「${intent.summary}」", intent.summary.contains("7 字"))
    }

    @Test
    fun `配方的参数引用是完整的 —— 漏一个会让 plan 抛异常`() {
        // `ExecutionRecipe.referencedParamNames()` 的用途是"配方碰了哪些参数"，
        // 漏报的后果是参数校验放行了一个配方其实要用的参数（值可能是空的）。
        assertEquals(
            setOf("path"),
            ExecutionRecipe.File(op = FileOp.READ, pathParam = "path").referencedParamNames(),
        )
        assertEquals(
            setOf("path", "content"),
            ExecutionRecipe.File(op = FileOp.WRITE, pathParam = "path", contentParam = "content")
                .referencedParamNames(),
        )
        assertEquals(
            setOf("path", "destination"),
            ExecutionRecipe.File(
                op = FileOp.MOVE, pathParam = "path", destinationParam = "destination",
            ).referencedParamNames(),
        )
    }
}
