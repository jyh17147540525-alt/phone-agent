package com.pocketagent.capabilitylogic

import com.pocketagent.filelogic.ChannelResult as FileChannelResult
import com.pocketagent.filelogic.DirEntry
import com.pocketagent.filelogic.EntryStat
import com.pocketagent.filelogic.FileChannel
import com.pocketagent.filelogic.FileChannelKind
import com.pocketagent.filelogic.FileOp
import com.pocketagent.filelogic.FileReadResult
import com.pocketagent.filelogic.FileScope
import com.pocketagent.filelogic.ScopeRoot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件操作走 [CapabilityRunner] 的那条路（T0-D）。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这一层为什么值得单独测
 * ═══════════════════════════════════════════════════════════════
 *
 * 文件裁决本身已经被 `:filelogic` 的测试覆盖了。这里守的是**接缝**：
 *
 * | 接缝上写错 | 后果 | 会不会报错 |
 * |---|---|---|
 * | 不给裁决器数子项 | 确认框说「要删除文件夹「X」」而**不提里面有 200 个文件** | 不会 |
 * | 数不出来时编一个 0 | 确认框说「空文件夹」—— 比不说更糟 | 不会 |
 * | 执行前不再探测 | 探测时不存在 → 判"新建无需确认" → 执行时已存在 → **静默覆盖** | 不会 |
 * | 把 `sizeBytes` 的 null 写成 0 | 确认框说「这个文件是空的」 | 不会 |
 * | 越界拒绝报成 `Failed` | 用户对着"操作失败"反复重试，而他要做的是**去授权** | 不会 |
 *
 * 五条**没有一条会抛异常**。这正是本项目反复吃亏的那一类，
 * 所以下面断言的是**确认框里到底写了什么**，而不是"调用返回了"。
 */
class CapabilityRunnerFileTest {

    // ══════════════════════════════════════════════════════════
    //  桩
    // ══════════════════════════════════════════════════════════

    /**
     * 文件通道的桩。
     *
     * ⚠️ 它**只记录不判断** —— 一旦桩里出现业务 `if`，测的就是桩而不是被测对象。
     *    唯一的例外是 [statProvider] 按**调用序号**返回不同结果：
     *    那是为了让"执行前重新探测"这个**顺序**变得可观察，
     *    而顺序本身没有别的观察点。
     */
    private class FakeFile(
        private val statProvider: (callIndex: Int) -> FileChannelResult<EntryStat> =
            { FileChannelResult.Ok(EntryStat(exists = false)) },
        private val listProvider: () -> FileChannelResult<List<DirEntry>> =
            { FileChannelResult.Ok(emptyList()) },
        private val readResult: FileChannelResult<FileReadResult> =
            FileChannelResult.Ok(FileReadResult.Text("")),
        private val writeResult: FileChannelResult<Unit> = FileChannelResult.Ok(Unit),
        private val deleteResult: FileChannelResult<Unit> = FileChannelResult.Ok(Unit),
        private val moveResult: FileChannelResult<Unit> = FileChannelResult.Ok(Unit),
    ) : FileChannel {

        override val kind: FileChannelKind = FileChannelKind.SAF

        override fun isAvailable(): Boolean = true

        override fun priority(): Int = 1

        val calls = mutableListOf<String>()

        private var statCalls = 0

        var writeCount = 0
        var written: String? = null

        override suspend fun stat(
            root: ScopeRoot,
            relativePath: String,
        ): FileChannelResult<EntryStat> {
            calls += "stat"
            return statProvider(statCalls++)
        }

        override suspend fun list(
            root: ScopeRoot,
            relativePath: String,
        ): FileChannelResult<List<DirEntry>> {
            calls += "list"
            return listProvider()
        }

        override suspend fun read(
            root: ScopeRoot,
            relativePath: String,
            maxBytes: Int,
        ): FileChannelResult<FileReadResult> {
            calls += "read"
            return readResult
        }

        override suspend fun write(
            root: ScopeRoot,
            relativePath: String,
            content: String,
        ): FileChannelResult<Unit> {
            calls += "write"
            writeCount++
            written = content
            return writeResult
        }

        override suspend fun delete(
            root: ScopeRoot,
            relativePath: String,
        ): FileChannelResult<Unit> {
            calls += "delete"
            return deleteResult
        }

        override suspend fun move(
            root: ScopeRoot,
            fromRelativePath: String,
            toRelativePath: String,
        ): FileChannelResult<Unit> {
            calls += "move"
            return moveResult
        }
    }

    private class CountingSettings : SettingsAccess {
        var calls = 0
        override suspend fun read(namespace: SettingNamespace, key: String): String? {
            calls++
            return null
        }

        override suspend fun write(
            namespace: SettingNamespace,
            key: String,
            value: String,
        ): ChannelResult {
            calls++
            return ChannelResult.Succeeded()
        }
    }

    private class CountingShell : ShellRunner {
        var calls = 0
        override suspend fun run(argv: List<String>): ChannelResult {
            calls++
            return ChannelResult.Succeeded()
        }
    }

    // ══════════════════════════════════════════════════════════
    //  测试数据
    // ══════════════════════════════════════════════════════════

    private val docsRoot = ScopeRoot(
        id = "docs",
        displayName = "文档",
        path = "/storage/emulated/0/Documents",
        token = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
    )

    private val granted = FileScope(roots = listOf(docsRoot))

    private val noGrant = FileScope()

    private val filePath = "/storage/emulated/0/Documents/a.txt"

    private val dirPath = "/storage/emulated/0/Documents/项目"

    private fun intent(
        op: FileOp,
        path: String = filePath,
        content: String? = null,
        destinationPath: String? = null,
    ) = PlannedExecution.FileIntent(
        op = op,
        path = path,
        content = content,
        destinationPath = destinationPath,
        summary = "测试用文件操作",
    )

    private fun runner(
        file: FakeFile,
        scope: FileScope = granted,
    ) = CapabilityRunner(files = file, fileScope = { scope })

    private fun confirmed(result: ChannelResult): ChannelResult.NeedsConfirmation {
        assertTrue("应当是「要确认」，实际是 $result", result is ChannelResult.NeedsConfirmation)
        return result as ChannelResult.NeedsConfirmation
    }

    // ══════════════════════════════════════════════════════════
    //  一、派发与端口缺失
    // ══════════════════════════════════════════════════════════

    @Test
    fun `没有接文件通道时报不可用，而不是抛异常`() = runTest {
        // ⚠️ 报 Unavailable 而不是 Failed：这是**配置问题**，
        //    用户该做的是去配置，而不是"重试"。
        val result = CapabilityRunner().run(intent(FileOp.READ))
        assertTrue("实际是 $result", result is ChannelResult.Unavailable)
    }

    @Test
    fun `文件请求只到文件通道，设置与 shell 通道一次都不碰`() = runTest {
        val settings = CountingSettings()
        val shell = CountingShell()
        val file = FakeFile(readResult = FileChannelResult.Ok(FileReadResult.Text("x")))

        CapabilityRunner(
            settings = settings,
            shell = shell,
            files = file,
            fileScope = { granted },
        ).run(intent(FileOp.READ))

        assertEquals("设置通道不该被碰", 0, settings.calls)
        assertEquals("shell 通道不该被碰", 0, shell.calls)
    }

    // ══════════════════════════════════════════════════════════
    //  二、拒绝要分两类 —— 它们给用户的下一步完全不同
    // ══════════════════════════════════════════════════════════

    @Test
    fun `路径不在授权范围内时是「去授权」，不是「没做成」`() = runTest {
        val result = runner(FakeFile(), scope = noGrant).run(intent(FileOp.READ))
        assertTrue(
            "没授权任何目录 ≠ 这次没成 —— 前者要用户去选目录，后者要他改参数。实际是 $result",
            result is ChannelResult.Unavailable,
        )
    }

    @Test
    fun `路径非法时是「没做成」，不是「去授权」`() = runTest {
        // ⚠️ 相对路径会被 PathNormalizer 拒掉。此时**不该**说"去授权" ——
        //    用户就算授权了整块存储，这条路径依然非法。
        val result = runner(FakeFile()).run(intent(FileOp.READ, path = "Documents/a.txt"))
        assertTrue("实际是 $result", result is ChannelResult.Failed)
    }

    @Test
    fun `通道自己说不可用时原样上报，不在中间编一句话`() = runTest {
        // ⚠️ 只有通道实现知道它为什么不可用（授权被撤 / 目录被删 / 服务没跑）。
        //    中间层编一句话会把那些原因抹平。
        val file = FakeFile(statProvider = { FileChannelResult.Unavailable("这条目录授权已经失效了") })
        val result = runner(file).run(intent(FileOp.READ)) as ChannelResult.Unavailable
        assertEquals("这条目录授权已经失效了", result.detail)
    }

    // ══════════════════════════════════════════════════════════
    //  三、★★ 数子项 —— 确认框不能隐瞒"里面还有东西"
    // ══════════════════════════════════════════════════════════

    @Test
    fun `删除目录前会数一遍里面有多少子项`() = runTest {
        // ⚠️⚠️ 这条守的是一件很具体的事：`FileAccessDecider` 在
        //    `descendantCount == null` 时说的是「要删除文件夹「X」。」——
        //    **完全不提里面还有东西**。而用户在这一下要签掉的是
        //    "里面那些文件一起消失"。
        val file = FakeFile(
            statProvider = { FileChannelResult.Ok(EntryStat(exists = true, isDirectory = true)) },
            listProvider = {
                FileChannelResult.Ok(
                    listOf(
                        DirEntry("预算.xlsx", isDirectory = false),
                        DirEntry("草稿.md", isDirectory = false),
                        DirEntry("附件", isDirectory = true),
                    ),
                )
            },
        )

        val message = confirmed(runner(file).run(intent(FileOp.DELETE, path = dirPath))).userMessage

        assertTrue(
            "确认框必须说出里面有几个子项，实际是「$message」",
            message.contains("3"),
        )
        assertTrue(
            "还要说清它们会一起消失，实际是「$message」",
            message.contains("一起"),
        )
        assertEquals("问的时候不能已经删了", 0, file.calls.count { it == "delete" })
    }

    @Test
    fun `数不出来时确认框不编一个数字`() = runTest {
        // ⚠️ 数不出来就说"要删除文件夹「X」"，**不能**说"空文件夹" ——
        //    后者是一句技术上很具体、实际上在骗人的话。
        val file = FakeFile(
            statProvider = { FileChannelResult.Ok(EntryStat(exists = true, isDirectory = true)) },
            listProvider = { FileChannelResult.Failed("列不出来") },
        )

        val message = confirmed(runner(file).run(intent(FileOp.DELETE, path = dirPath))).userMessage

        assertFalse("不能编一个子项数，实际是「$message」", message.contains("个子项"))
        assertTrue("但必须说明要删的是个文件夹", message.contains("文件夹"))
    }

    @Test
    fun `读目录时不会为了数子项多列一次`() = runTest {
        // ⚠️ 每次 `list` 都是一轮真实的目录扫描（SAF 上可能好几秒）。
        //    为读操作数子项是纯粹的浪费，而且结果没人用。
        val file = FakeFile(
            statProvider = { FileChannelResult.Ok(EntryStat(exists = true, isDirectory = true)) },
            listProvider = { FileChannelResult.Ok(listOf(DirEntry("a", isDirectory = false))) },
        )

        runner(file).run(intent(FileOp.LIST, path = dirPath))

        assertEquals(
            "list 只该被调用一次（执行那一次）",
            1,
            file.calls.count { it == "list" },
        )
    }

    @Test
    fun `列目录时不给目录显示字节数`() = runTest {
        // ⚠️ 2026-09-23 真机目击：SAF 对目录返回的 `COLUMN_SIZE` 是**目录项本身的
        //    元数据大小**，与"里面有多少内容"毫无关系 —— 真机上 `sub` 报的是
        //    「3452 字节」，而它其实是个空目录。
        //
        //    显示它比不显示更糟：用户会拿它当"这个目录有多大"的答案。
        val file = FakeFile(
            statProvider = { FileChannelResult.Ok(EntryStat(exists = true, isDirectory = true)) },
            listProvider = {
                FileChannelResult.Ok(
                    listOf(
                        DirEntry(name = "sub", isDirectory = true, sizeBytes = 3452),
                        DirEntry(name = "a.txt", isDirectory = false, sizeBytes = 22),
                    ),
                )
            },
        )

        val result = runner(file).run(intent(FileOp.LIST, path = dirPath)) as ChannelResult.Succeeded
        val payload = requireNotNull(result.payload) { "列目录成功却没有 payload" }

        assertTrue("文件仍然要显示大小：$payload", payload.contains("a.txt  22 字节"))
        assertFalse("目录的「大小」是目录项元数据，不能显示：$payload", payload.contains("3452"))
        assertTrue("但目录这一行本身要在：$payload", payload.contains("目录  sub"))
    }

    // ══════════════════════════════════════════════════════════
    //  四、确认门
    // ══════════════════════════════════════════════════════════

    @Test
    fun `覆盖已存在的文件时要确认，而且确认框里写着是哪个文件`() = runTest {
        val file = FakeFile(
            statProvider = {
                FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false, sizeBytes = 12))
            },
        )

        val result = confirmed(
            runner(file).run(intent(FileOp.WRITE, content = "新内容")),
        )

        assertTrue(
            "确认框里必须写出要动哪个文件 —— 否则用户是在盲签。实际是「${result.userMessage}」",
            result.userMessage.contains("a.txt"),
        )
        assertEquals("问的时候一个字都不能写", 0, file.writeCount)
    }

    @Test
    fun `确认过之后就不再问，直接写进去`() = runTest {
        val file = FakeFile(
            statProvider = {
                FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false, sizeBytes = 12))
            },
        )

        val result = runner(file).run(intent(FileOp.WRITE, content = "新内容"), operationConfirmedByUser = true)

        assertTrue("实际是 $result", result is ChannelResult.Succeeded)
        assertEquals("新内容", file.written)
    }

    @Test
    fun `新建文件不打断用户`() = runTest {
        // ⚠️ 目标不存在 → 没有覆盖风险 → 不该弹确认框。
        //    否则"帮我建个笔记"这种最常见的请求会变成两次交互，
        //    而第二次交互里用户什么信息都没得到。
        val file = FakeFile(statProvider = { FileChannelResult.Ok(EntryStat(exists = false)) })

        val result = runner(file).run(intent(FileOp.WRITE, content = "笔记"))

        assertTrue("实际是 $result", result is ChannelResult.Succeeded)
    }

    @Test
    fun `只读的操作不弹确认框`() = runTest {
        val file = FakeFile(
            statProvider = {
                FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false, sizeBytes = 3))
            },
            readResult = FileChannelResult.Ok(FileReadResult.Text("abc")),
        )

        val result = runner(file).run(intent(FileOp.READ))
        assertTrue("读不该被打断，实际是 $result", result is ChannelResult.Succeeded)
    }

    // ══════════════════════════════════════════════════════════
    //  五、★ TOCTOU：执行前必须重新探测
    // ══════════════════════════════════════════════════════════

    @Test
    fun `执行前重新探测，状态变了就放弃`() = runTest {
        // ⚠️⚠️ 这是**唯一**能拦住"探测时不存在 → 判为新建无需确认 →
        //    执行时它已经存在 → 静默覆盖"的地方。纯逻辑层做不到
        //    （它碰不到文件系统），所以它只能在这一层被钉住。
        val file = FakeFile(
            statProvider = { index ->
                if (index == 0) {
                    // 裁决时：存在，所以会问一次
                    FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false, sizeBytes = 5))
                } else {
                    // 执行前重探：不见了（用户在这两步之间删了它）
                    FileChannelResult.Ok(EntryStat(exists = false))
                }
            },
        )

        val result = runner(file).run(intent(FileOp.WRITE, content = "新内容"), operationConfirmedByUser = true)

        assertTrue("状态变了就该停下，实际是 $result", result is ChannelResult.Failed)
        assertEquals("绝不能动手", 0, file.writeCount)
        assertTrue(
            "要如实说清为什么停下，实际是「${(result as ChannelResult.Failed).reason}」",
            result.reason.contains("变了"),
        )
    }

    @Test
    fun `探测两次是刻意的，不是重复劳动`() = runTest {
        // ⚠️ 把第二次探测"优化"掉，就会静默丢掉上面那条保护 ——
        //    而所有其它测试照样全绿。所以这里把次数也钉住。
        val file = FakeFile(statProvider = { FileChannelResult.Ok(EntryStat(exists = false)) })

        runner(file).run(intent(FileOp.WRITE, content = "笔记"))

        assertEquals(
            "裁决前一次 + 执行前一次",
            2,
            file.calls.count { it == "stat" },
        )
    }

    // ══════════════════════════════════════════════════════════
    //  六、读回来的内容怎么走
    // ══════════════════════════════════════════════════════════

    @Test
    fun `读取成功时内容装在 payload 里，不是 previousValue`() = runTest {
        // ⚠️ 两者**不是一回事**：
        //    `previousValue` 是"执行前这个设置是什么"（用途是撤销）；
        //    `payload` 是"这次操作读到了什么"（用途是展示）。
        //    对一次读文件来说前者**恒为 null** —— 把它俩混起来的后果是
        //    界面拿到一个 null 然后显示"（没有内容）"，而文件明明读到了。
        val file = FakeFile(
            statProvider = {
                FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false, sizeBytes = 15))
            },
            readResult = FileChannelResult.Ok(FileReadResult.Text("你好，世界")),
        )

        val result = runner(file).run(intent(FileOp.READ)) as ChannelResult.Succeeded

        assertEquals("你好，世界", result.payload)
        assertNull("读文件没有「原来的值」这回事", result.previousValue)
    }

    @Test
    fun `读到空文件时给出一句说明，而不是一片空白`() = runTest {
        // ⚠️ 空串在界面上渲染成一片空白，用户分不清"这个文件本来就是空的"
        //    与"界面出错了" —— 而这两种情况的下一步动作完全不同
        //    （一个是「换个文件试试」，一个是「报个 bug」）。
        //
        //    `payload` 的不变量：**要么 null（这次不产出内容），要么非空文本**。
        val file = FakeFile(
            statProvider = {
                FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false, sizeBytes = 0))
            },
            readResult = FileChannelResult.Ok(FileReadResult.Text("")),
        )

        val result = runner(file).run(intent(FileOp.READ)) as ChannelResult.Succeeded
        val payload = requireNotNull(result.payload) { "读是成功的，payload 不该是 null" }

        assertTrue("不能把空串直接交给界面：'$payload'", payload.isNotEmpty())
        assertTrue("要说清是文件本身空的：$payload", payload.contains("空"))
    }

    @Test
    fun `读到太大的文件时说「至少有」，不给一个假装精确的数字`() = runTest {
        // ⚠️ `sizeBytes` 会从两条路径来：① 通道先读元数据（准确值）
        //    ② 通道读着读着才发现超限（**只是下界**）。
        //    「有」在 ② 里是假话，而假话会让用户以为"只超了一点点"。
        val file = FakeFile(
            statProvider = {
                FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false))
            },
            readResult = FileChannelResult.Ok(
                FileReadResult.TooLarge(sizeBytes = 262_145, limitBytes = 262_144),
            ),
        )

        val result = runner(file).run(intent(FileOp.READ)) as ChannelResult.Failed

        assertTrue("实际是「${result.reason}」", result.reason.contains("至少有"))
    }

    @Test
    fun `不是 UTF-8 的文件如实报出来，不尽力解码`() = runTest {
        // ⚠️ 把非法字节替换成 U+FFFD 会得到一份"看起来正常、实际已损坏"的文本，
        //    而 agent 会基于它做判断。
        val file = FakeFile(
            statProvider = {
                FileChannelResult.Ok(EntryStat(exists = true, isDirectory = false, sizeBytes = 4))
            },
            readResult = FileChannelResult.Ok(FileReadResult.NotUtf8("这不是文本")),
        )

        val result = runner(file).run(intent(FileOp.READ)) as ChannelResult.Failed

        assertTrue("实际是「${result.reason}」", result.reason.contains("这不是文本"))
    }
}
