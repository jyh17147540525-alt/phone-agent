package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileAccessDecider] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 判定表本身很短（读放行、写新的放行、覆盖/删除/移动要确认），
 * 但**它周围的顺序与边界**才是真正危险的地方。本文件按危险程度分了三组：
 *
 * 1. **确认不能绕过前置检查** —— 最重要。一个"用户点过确认就放行"的
 *    实现能通过其余全部测试。
 * 2. **MOVE 的目标必须和主路径过同样的关** —— 最容易漏，
 *    因为注意力都在主路径上。
 * 3. 各操作类型的裁决矩阵。
 */
class FileAccessDeciderTest {

    private val scope = FileScope(
        roots = listOf(
            ScopeRoot(
                id = "docs",
                displayName = "文档",
                path = "/storage/emulated/0/Documents",
                token = "content://tree/docs",
            ),
            ScopeRoot(
                id = "dl",
                displayName = "下载",
                path = "/storage/emulated/0/Download",
                token = "content://tree/dl",
            ),
        ),
    )

    private val decider = FileAccessDecider(scope)

    private fun decide(
        op: FileOp,
        path: String,
        targetExists: Boolean = false,
        isDirectory: Boolean = false,
        descendantCount: Int? = null,
        existingSizeBytes: Long? = null,
        existingModifiedAtText: String? = null,
        destinationPath: String? = null,
        confirmed: Boolean = false,
    ) = decider.decide(
        FileOperationRequest(
            op = op,
            path = path,
            targetExists = targetExists,
            isDirectory = isDirectory,
            descendantCount = descendantCount,
            existingSizeBytes = existingSizeBytes,
            existingModifiedAtText = existingModifiedAtText,
            destinationPath = destinationPath,
            confirmedByUser = confirmed,
        ),
    )

    private fun assertDenied(decision: FileAccessDecision, reason: DenyReason): FileAccessDecision.Denied {
        assertTrue("期望被拒绝（$reason），实际：$decision", decision is FileAccessDecision.Denied)
        val d = decision as FileAccessDecision.Denied
        assertEquals("拒绝原因不符（说明：${d.userMessage}）", reason, d.reason)
        return d
    }

    private fun assertConfirmed(
        decision: FileAccessDecision,
        reason: ConfirmReason,
    ): FileAccessDecision.RequireConfirmation {
        assertTrue("期望需要确认（$reason），实际：$decision", decision is FileAccessDecision.RequireConfirmation)
        val c = decision as FileAccessDecision.RequireConfirmation
        assertEquals("确认类型不符", reason, c.reason)
        return c
    }

    private fun assertAllowed(decision: FileAccessDecision, wasConfirmed: Boolean = false) {
        assertTrue("期望放行，实际：$decision", decision is FileAccessDecision.Allowed)
        assertEquals("wasConfirmed 不符", wasConfirmed, (decision as FileAccessDecision.Allowed).wasConfirmed)
    }

    // ─────────────────────────────────────────────────────────────
    //  组 1 ★★★ 确认不能绕过前置检查
    // ─────────────────────────────────────────────────────────────

    /*
     * ⚠️ 这一组的四条测试，是本文件存在的主要理由。
     *
     *    最自然的写法是：
     *
     *        if (request.confirmedByUser) return Allowed(...)
     *
     *    把"用户已确认"当作提前返回的理由。它读起来很合理 ——
     *    用户都点过确认了，还检查什么？
     *
     *    但那样一来，确认就变成了**万能钥匙**。真实路径：
     *
     *        1. agent 请求删除 /Documents/我的笔记.md
     *        2. 确认框弹出，用户看了，点了确认
     *        3. 执行之前，路径被改成 /Documents/.ssh/id_rsa
     *        4. confirmedByUser = true → 直接放行
     *
     *    第 3 步不需要恶意 —— 它可以是模型的一次"修正"、一次重试，
     *    或者插件在两次调用之间改了参数。
     *    **用户确认的是他看到的那个路径，不是函数收到的那个路径。**
     */

    @Test
    fun `确认之后越界路径仍然被拒`() {
        assertDenied(
            decide(FileOp.DELETE, "/etc/passwd", confirmed = true),
            DenyReason.OUT_OF_SCOPE,
        )
    }

    @Test
    fun `确认之后黑名单路径仍然被拒`() {
        assertDenied(
            decide(FileOp.READ, "/storage/emulated/0/Documents/.ssh/id_rsa", confirmed = true),
            DenyReason.DENIED_LOCATION,
        )
    }

    @Test
    fun `确认之后非法路径仍然被拒`() {
        // 归一化失败必须优先于一切 —— 连"要操作的是哪个文件"都确定不了
        //
        // ⚠️ 这里必须给**足够多**的 `..` 才能真正越根：
        //    `/storage/emulated/0/Documents` 有 4 段，只写两个 `..`
        //    只会退到 `/storage/emulated`，那是一个**合法**路径。
        assertDenied(
            decide(
                FileOp.DELETE,
                "/storage/emulated/0/Documents/../../../../../etc/passwd",
                confirmed = true,
            ),
            DenyReason.MALFORMED_PATH,
        )
        assertDenied(
            decide(FileOp.DELETE, "Documents/a.md", confirmed = true),
            DenyReason.MALFORMED_PATH,
        )
    }

    @Test
    fun `确认之后归一化到范围外的路径仍然被拒`() {
        // ★ 与上一条是**不同的情形**：路径本身完全合法（没有越根），
        //   只是归一化之后落在了授权范围之外。
        //   一个只检查"有没有越根"的实现会漏掉它 —— 而 `../..` 正是
        //   最常见的越权尝试写法。
        assertDenied(
            decide(FileOp.DELETE, "/storage/emulated/0/Documents/../../etc/passwd", confirmed = true),
            DenyReason.OUT_OF_SCOPE,
        )
    }

    @Test
    fun `确认之后移动目标越界仍然被拒`() {
        assertDenied(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/a.md",
                destinationPath = "/storage/emulated/0/Documents/.ssh/id_rsa",
                confirmed = true,
            ),
            DenyReason.DESTINATION_DENIED_LOCATION,
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  组 2 ★★ MOVE 的目标也要过同样的关
    // ─────────────────────────────────────────────────────────────

    /*
     * ⚠️ 这一步最容易漏，因为"目标路径"是请求里第二个路径字段，
     *    而人写代码时的注意力全在"主路径"上。
     *
     *    漏掉的后果很直接：
     *
     *        把 /Documents/a.md 移动到 /Documents/.ssh/id_rsa
     *
     *    主路径完全合法，于是私钥被覆盖。
     */

    @Test
    fun `移动缺少目标被拒`() {
        assertDenied(
            decide(FileOp.MOVE, "/storage/emulated/0/Documents/a.md"),
            DenyReason.MISSING_DESTINATION,
        )
        assertDenied(
            decide(FileOp.MOVE, "/storage/emulated/0/Documents/a.md", destinationPath = "   "),
            DenyReason.MISSING_DESTINATION,
        )
    }

    @Test
    fun `移动目标越界被拒`() {
        assertDenied(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/a.md",
                destinationPath = "/storage/emulated/0/DCIM/a.md",
            ),
            DenyReason.DESTINATION_OUT_OF_SCOPE,
        )
    }

    @Test
    fun `移动目标非法路径被拒`() {
        assertDenied(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/a.md",
                destinationPath = "/storage/emulated/0/Documents/../../../../../etc/x",
            ),
            DenyReason.MALFORMED_PATH,
        )
    }

    @Test
    fun `移动目标归一化到范围外被拒`() {
        // 与上一条互为反向保证：主路径合法、目标也合法，
        // 但目标归一化之后落在授权范围之外 —— 必须按"越界"拒，
        // 而不是当成"路径非法"（两者给用户的下一步不同）
        assertDenied(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/a.md",
                destinationPath = "/storage/emulated/0/Documents/../../etc/x",
            ),
            DenyReason.DESTINATION_OUT_OF_SCOPE,
        )
    }

    @Test
    fun `跨授权根的移动被拒`() {
        // ★ SAF 的 moveDocument 只在同一个 tree 内工作。跨 tree 要"复制 + 删除"，
        //   而那不是原子的 —— 中途失败会留下两个副本（用户以为文件重复了）
        //   或零个副本（用户以为文件丢了）。所以直接拒绝并让用户分两步做。
        val d = assertDenied(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/a.md",
                destinationPath = "/storage/emulated/0/Download/a.md",
            ),
            DenyReason.CROSS_ROOT_MOVE,
        )
        // 说明里要提到两条授权的名字，用户才知道该往哪看
        assertTrue("说明应当提到两个目录名，实际：${d.userMessage}", d.userMessage.contains("文档"))
        assertTrue("说明应当提到两个目录名，实际：${d.userMessage}", d.userMessage.contains("下载"))
    }

    @Test
    fun `同根内的移动放行到确认`() {
        // 与上一条互为反向保证：一个"见到 MOVE 就拒"的实现能过上一条，
        // 但会让重命名完全不可用 —— 而重命名是最高频的文件操作之一
        assertConfirmed(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/a.md",
                destinationPath = "/storage/emulated/0/Documents/b.md",
            ),
            ConfirmReason.MOVE_OR_RENAME,
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  组 3 ★ 裁决矩阵
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `没有授权范围时给出可操作的说明`() {
        val decision = FileAccessDecider(FileScope()).decide(
            FileOperationRequest(FileOp.READ, "/storage/emulated/0/Documents/a.md"),
        )
        val denied = assertDenied(decision, DenyReason.NO_SCOPE)
        // ★ 这不是"错误"，是"还没配置"。文案必须指向下一步动作，
        //   否则用户只会看到一句红色的"操作失败"，不知道要做什么。
        assertTrue("应当引导用户去授权，实际：${denied.userMessage}", denied.userMessage.contains("文件权限"))
    }

    @Test
    fun `读取与列目录不需要确认`() {
        // ★ 读取是可撤销的（不改变任何东西），不该打断用户。
        //   确认得太频繁，用户会养成"看见就点确定"的习惯 ——
        //   而那个习惯会让真正的危险确认也失效。
        assertAllowed(decide(FileOp.READ, "/storage/emulated/0/Documents/a.md"))
        assertAllowed(decide(FileOp.LIST, "/storage/emulated/0/Documents"))
    }

    @Test
    fun `新建文件不需要确认`() {
        // 否则"帮我建个笔记"这种最常见的请求会变成两次交互，
        // 而第二次交互里用户什么信息都没得到
        assertAllowed(
            decide(FileOp.WRITE, "/storage/emulated/0/Documents/新笔记.md", targetExists = false),
        )
    }

    @Test
    fun `覆盖已有文件需要确认`() {
        // ★★ 这是"静默数据丢失"的唯一一道闸。
        //    覆盖之后用户的原内容没了，而界面上如果只说"已写入"，
        //    用户永远不会知道发生过什么。
        assertConfirmed(
            decide(FileOp.WRITE, "/storage/emulated/0/Documents/旧笔记.md", targetExists = true),
            ConfirmReason.OVERWRITE_EXISTING,
        )
    }

    @Test
    fun `删除文件需要确认`() {
        assertConfirmed(
            decide(FileOp.DELETE, "/storage/emulated/0/Documents/a.md", targetExists = true),
            ConfirmReason.DELETE_FILE,
        )
    }

    @Test
    fun `删除目录需要确认并说明子项数`() {
        val c = assertConfirmed(
            decide(
                FileOp.DELETE,
                "/storage/emulated/0/Documents/Work",
                targetExists = true,
                isDirectory = true,
                descendantCount = 12,
            ),
            ConfirmReason.DELETE_DIRECTORY,
        )
        assertTrue("文案应当说明子项数，实际：${c.userMessage}", c.userMessage.contains("12"))
    }

    @Test
    fun `子项数未知时不说是空文件夹`() {
        // ★★ 这条守的是"宁可不说，也不要说错"。
        //
        //    探测不到子项数时（SAF 的 listFiles 可能失败），
        //    如果默认成 0，确认框会告诉用户"要删除空文件夹" ——
        //    他点下确认才发现丢了 200 个文件。
        //    **那一次之后，他不会再信任任何确认框。**
        val c = assertConfirmed(
            decide(
                FileOp.DELETE,
                "/storage/emulated/0/Documents/Work",
                targetExists = true,
                isDirectory = true,
                descendantCount = null,
            ),
            ConfirmReason.DELETE_DIRECTORY,
        )
        assertFalse(
            "子项数未知时不该声称是空文件夹，实际：${c.userMessage}",
            c.userMessage.contains("空文件夹"),
        )
    }

    @Test
    fun `子项数为零时才说是空文件夹`() {
        val c = assertConfirmed(
            decide(
                FileOp.DELETE,
                "/storage/emulated/0/Documents/Work",
                targetExists = true,
                isDirectory = true,
                descendantCount = 0,
            ),
            ConfirmReason.DELETE_DIRECTORY,
        )
        assertTrue("零子项应当说清是空文件夹，实际：${c.userMessage}", c.userMessage.contains("空文件夹"))
    }

    @Test
    fun `覆盖文案带上文件大小与修改时间`() {
        val c = assertConfirmed(
            decide(
                FileOp.WRITE,
                "/storage/emulated/0/Documents/报告.md",
                targetExists = true,
                existingSizeBytes = 12_800,
                existingModifiedAtText = "昨天 14:20",
            ),
            ConfirmReason.OVERWRITE_EXISTING,
        )
        assertTrue("应当说明大小，实际：${c.userMessage}", c.userMessage.contains("12.5 KB"))
        assertTrue("应当说明修改时间，实际：${c.userMessage}", c.userMessage.contains("昨天 14:20"))
        assertTrue("应当说清会发生什么，实际：${c.userMessage}", c.userMessage.contains("整体替换"))
    }

    @Test
    fun `拿不到元信息时文案不编造`() {
        // ★ 与上一条互为反向保证：如果实现写成"总是拼一段元信息"，
        //   拿不到时就会显示「0 B」—— 那是在说假话
        val c = assertConfirmed(
            decide(FileOp.WRITE, "/storage/emulated/0/Documents/报告.md", targetExists = true),
            ConfirmReason.OVERWRITE_EXISTING,
        )
        assertFalse("拿不到大小就不该提大小，实际：${c.userMessage}", c.userMessage.contains(" B"))
        assertFalse("拿不到时间就不该提时间，实际：${c.userMessage}", c.userMessage.contains("修改"))
    }

    @Test
    fun `同一目录内的移动被描述为重命名`() {
        // 用户对"重命名"和"移动"的心智模型不同，文案要跟上
        val c = assertConfirmed(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/a.md",
                destinationPath = "/storage/emulated/0/Documents/b.md",
            ),
            ConfirmReason.MOVE_OR_RENAME,
        )
        assertTrue("同目录应当是重命名，实际：${c.userMessage}", c.userMessage.contains("重命名"))
    }

    @Test
    fun `跨目录的移动被描述为移动`() {
        val c = assertConfirmed(
            decide(
                FileOp.MOVE,
                "/storage/emulated/0/Documents/Work/a.md",
                destinationPath = "/storage/emulated/0/Documents/Archive/a.md",
            ),
            ConfirmReason.MOVE_OR_RENAME,
        )
        assertTrue("跨目录应当是移动，实际：${c.userMessage}", c.userMessage.contains("移动"))
    }

    // ─────────────────────────────────────────────────────────────
    //  确认之后放行
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `确认之后放行并标记 wasConfirmed`() {
        // ★ wasConfirmed 记的是"这次放行是不是确认换来的"，不是"这个操作危不危险"。
        //   审计日志要靠它回答"用户一共确认过多少次删除" ——
        //   如果两者混在一起，那个数字就永远是错的。
        val d = decide(
            FileOp.DELETE,
            "/storage/emulated/0/Documents/a.md",
            targetExists = true,
            confirmed = true,
        )
        assertAllowed(d, wasConfirmed = true)
    }

    @Test
    fun `无需确认的操作 wasConfirmed 为假`() {
        assertAllowed(decide(FileOp.READ, "/storage/emulated/0/Documents/a.md"), wasConfirmed = false)
    }

    // ─────────────────────────────────────────────────────────────
    //  路径解析结果
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `放行结果带上相对路径与授权根`() {
        // ★ 通道实现需要 relativePath 才能从 tree 根逐段下走。
        //   让每个通道自己算会导致每个实现里都有一份（可能算错的）换算。
        val d = decide(FileOp.READ, "/storage/emulated/0/Documents/Work/a.md")
        assertAllowed(d)
        val t = (d as FileAccessDecision.Allowed).target
        assertEquals("docs", t.root.id)
        assertEquals("Work/a.md", t.relativePath)
        assertEquals("/storage/emulated/0/Documents/Work/a.md", t.normalizedPath)
    }

    @Test
    fun `授权根本身的操作相对路径为空串`() {
        val d = decide(FileOp.LIST, "/storage/emulated/0/Documents")
        assertAllowed(d)
        assertEquals("", (d as FileAccessDecision.Allowed).target.relativePath)
    }

    @Test
    fun `路径被归一化后再交给通道`() {
        // ★ 通道拿到的必须是规范形式 —— 否则它要自己处理 `//`、`..`，
        //   而每个通道各处理一遍必然有出入
        val d = decide(FileOp.READ, "/storage/emulated/0/Documents//Work/./a.md")
        assertAllowed(d)
        assertEquals(
            "/storage/emulated/0/Documents/Work/a.md",
            (d as FileAccessDecision.Allowed).target.normalizedPath,
        )
    }

    @Test
    fun `移动结果带上目标相对路径`() {
        val d = decide(
            FileOp.MOVE,
            "/storage/emulated/0/Documents/a.md",
            destinationPath = "/storage/emulated/0/Documents/sub/b.md",
            confirmed = true,
        )
        assertAllowed(d, wasConfirmed = true)
        val t = (d as FileAccessDecision.Allowed).target
        assertEquals("a.md", t.relativePath)
        assertEquals("sub/b.md", t.destinationRelativePath)
    }

    @Test
    fun `非移动操作没有目标路径`() {
        val d = decide(FileOp.READ, "/storage/emulated/0/Documents/a.md")
        assertNull((d as FileAccessDecision.Allowed).target.destinationNormalizedPath)
        assertNull(d.target.destinationRelativePath)
    }

    // ─────────────────────────────────────────────────────────────
    //  字节格式化
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `字节数格式化`() {
        assertEquals("512 B", FileAccessDecider.formatBytes(512))
        assertEquals("1.0 KB", FileAccessDecider.formatBytes(1024))
        assertEquals("1.5 MB", FileAccessDecider.formatBytes(1024L * 1024 * 3 / 2))
        assertEquals("2.0 GB", FileAccessDecider.formatBytes(1024L * 1024 * 1024 * 2))
    }

    @Test
    fun `字节格式化不用本地化的小数分隔符`() {
        // ★ 显式用 Locale.ROOT。某些区域（德、法）的小数分隔符是逗号，
        //   `%.1f` 会输出「1,5 MB」—— 而这条字符串会进测试断言与审计日志。
        //   跟着系统区域漂移的输出没法被稳定断言。
        val text = FileAccessDecider.formatBytes(1536)
        assertFalse("不该出现逗号小数点，实际：$text", text.contains(","))
    }

    @Test
    fun `负数大小不显示为负数`() {
        // 防御性：SAF 的 length() 在异常时可能给出负数
        assertEquals("未知大小", FileAccessDecider.formatBytes(-1))
    }
}
