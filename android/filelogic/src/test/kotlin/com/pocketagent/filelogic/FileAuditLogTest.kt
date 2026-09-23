package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileAuditLog] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 审计日志是"全过程透明可见"的落地形式 —— 但它的价值完全取决于
 * **它记的东西是不是可信的**。三种让日志变得不可信的方式：
 *
 * | 问题 | 后果 |
 * |---|---|
 * | 路径里的换行没转义 | **伪造出一条不存在的记录**，真实记录被淹掉 |
 * | 没有上限 | 长任务跑一夜，内存被审计日志吃光 |
 * | 落库出口异常时整个操作失败 | 记日志失败导致删文件失败 —— 本末倒置 |
 *
 * 第一条最隐蔽：POSIX 允许文件名含换行，而 agent 完全可能被诱导去
 * 创建一个这样的文件名。
 */
class FileAuditLogTest {

    private val root = ScopeRoot(
        id = "docs",
        displayName = "文档",
        path = "/storage/emulated/0/Documents",
        token = "content://tree/docs",
    )

    private fun target(
        path: String = "/storage/emulated/0/Documents/a.md",
        op: FileOp = FileOp.DELETE,
        dest: String? = null,
    ) = ResolvedTarget(
        op = op,
        normalizedPath = path,
        relativePath = PathNormalizer.relativeTo(path, root.path) ?: "",
        root = root,
        destinationNormalizedPath = dest,
    )

    // ─────────────────────────────────────────────────────────────
    //  ★★ 消毒 —— 防日志伪造
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `路径里的换行被转义成可见形式`() {
        // ★★★ 这是本文件最重要的一条。
        //
        //    不转义的话，一条路径里带换行的操作会这样落进日志：
        //
        //        11:20:04 删除 /Documents/我
        //        11:20:05 删除 /Documents/工资单.xlsx
        //
        //    第二行看起来是一条**独立、真实**的记录，但它只是第一行文件名的一部分。
        //    用户回看时会以为"工资单被删了"，而那次操作根本没发生 ——
        //    同时真实的记录被这条假象挤掉了。
        //
        //    转义之后它占一行，伪造不出第二条。
        val log = FileAuditLog()
        log.record(
            FileAuditEvents.executed(
                timestamp = 1L,
                target = target(path = "/storage/emulated/0/Documents/我\n2026-09-23 删除 工资单.xlsx"),
                wasConfirmed = false,
            ),
        )

        val stored = log.events().single().path
        assertFalse("落库的路径里不该有真实换行", stored.contains('\n'))
        assertTrue("换行应当被转义成可见形式，实际：$stored", stored.contains("\\n"))
    }

    @Test
    fun `回车与制表符也被转义`() {
        assertEquals("a\\rb", FileAuditLog.sanitize("a\rb"))
        assertEquals("a\\tb", FileAuditLog.sanitize("a\tb"))
    }

    @Test
    fun `不可见的控制字符被转义`() {
        // ★ C0 / C1 控制字符在日志查看器里完全不可见，是最容易藏东西的地方
        assertEquals("a\\u0001b", FileAuditLog.sanitize("a\u0001b"))
        assertEquals("a\\u007fb", FileAuditLog.sanitize("a\u007Fb"))
    }

    @Test
    fun `正常的中文路径不被改动`() {
        assertEquals(
            "/storage/emulated/0/文档/季度总结.md",
            FileAuditLog.sanitize("/storage/emulated/0/文档/季度总结.md"),
        )
    }

    @Test
    fun `超长路径被截断`() {
        // ★ 不截断的话，一条 4000 字符的路径会挤掉几十条正常记录 ——
        //   而审计日志的用途是"让用户看出发生过什么"，不是取证
        val long = "/storage/emulated/0/Documents/" + "x".repeat(2000)

        val sanitized = FileAuditLog.sanitize(long)

        assertTrue(
            "应当被截断到 ${FileAuditLog.MAX_TEXT_LENGTH} 以内，实际 ${sanitized.length}",
            sanitized.length <= FileAuditLog.MAX_TEXT_LENGTH,
        )
        assertTrue("截断处应当有标记，实际末尾：${sanitized.takeLast(5)}", sanitized.endsWith("…"))
    }

    @Test
    fun `消毒同时作用于路径与说明`() {
        val log = FileAuditLog()
        log.record(
            FileAuditEvent(
                timestamp = 1L,
                op = FileOp.DELETE,
                path = "/a\nb",
                outcome = FileAuditOutcome.DENIED,
                detail = "原因\n第二行",
            ),
        )
        val e = log.events().single()
        assertFalse(e.path.contains('\n'))
        assertFalse(e.detail.contains('\n'))
    }

    // ─────────────────────────────────────────────────────────────
    //  上限
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `超出容量时丢弃最旧的`() {
        // ★ 没有上限的话，一个跑了一夜的长任务会把内存吃光 ——
        //   而"审计日志导致 OOM"是最难归因的一种崩溃
        val log = FileAuditLog(capacity = 3)

        repeat(5) { i ->
            log.record(
                FileAuditEvents.executed(
                    timestamp = i.toLong(),
                    target = target(path = "/storage/emulated/0/Documents/f$i.md"),
                    wasConfirmed = false,
                ),
            )
        }

        val events = log.events()
        assertEquals(3, events.size)
        assertEquals("应当保留最新的三条", listOf(2L, 3L, 4L), events.map { it.timestamp })
    }

    @Test
    fun `记录按时间正序`() {
        val log = FileAuditLog()
        log.record(FileAuditEvents.executed(2L, target(path = "/storage/emulated/0/Documents/b.md"), false))
        log.record(FileAuditEvents.executed(1L, target(path = "/storage/emulated/0/Documents/a.md"), false))

        // 日志按写入顺序保留，不重排 —— 重排会让"顺序"这个信息消失，
        // 而"先删后建"与"先建后删"是两件不同的事
        assertEquals(listOf(2L, 1L), log.events().map { it.timestamp })
    }

    // ─────────────────────────────────────────────────────────────
    //  落库出口
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `落库出口收到消毒后的副本`() {
        val seen = mutableListOf<FileAuditEvent>()
        val log = FileAuditLog(sink = { seen += it })

        log.record(
            FileAuditEvents.executed(
                timestamp = 1L,
                target = target(path = "/a\nb"),
                wasConfirmed = false,
            ),
        )

        assertEquals(1, seen.size)
        assertFalse("出口拿到的也必须是消毒后的内容", seen.single().path.contains('\n'))
    }

    @Test
    fun `没有出口时也能工作`() {
        // 数据库还没就绪、或者单元测试里 —— 内存缓冲本身就够用
        val log = FileAuditLog(sink = null)
        log.record(FileAuditEvents.executed(1L, target(), false))
        assertEquals(1, log.events().size)
    }

    // ─────────────────────────────────────────────────────────────
    //  事件工厂
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `普通执行与确认后执行被区分`() {
        // ★ 审计要靠这个区分回答"用户一共确认过多少次删除" ——
        //   混在一起的话那个数字永远是错的
        val plain = FileAuditEvents.executed(1L, target(), wasConfirmed = false)
        val confirmed = FileAuditEvents.executed(1L, target(), wasConfirmed = true)

        assertEquals(FileAuditOutcome.EXECUTED, plain.outcome)
        assertEquals(FileAuditOutcome.EXECUTED_AFTER_CONFIRM, confirmed.outcome)
    }

    @Test
    fun `执行事件带上授权目录名与目标`() {
        val e = FileAuditEvents.executed(
            timestamp = 1L,
            target = target(dest = "/storage/emulated/0/Documents/b.md"),
            wasConfirmed = true,
        )
        assertEquals("docs", e.rootId)
        assertTrue("说明里应当有目录名，实际：${e.detail}", e.detail.contains("文档"))
        assertTrue("说明里应当有目标路径，实际：${e.detail}", e.detail.contains("b.md"))
    }

    @Test
    fun `拒绝事件只记原因码不记整段文案`() {
        // ★ 用户文案会随版本改，原因码不会。审计要靠原因码做统计。
        val e = FileAuditEvents.denied(
            timestamp = 1L,
            op = FileOp.DELETE,
            path = "/etc/passwd",
            reason = DenyReason.OUT_OF_SCOPE,
            userMessage = "「/etc/passwd」不在你授权的任何目录里",
        )
        assertEquals(FileAuditOutcome.DENIED, e.outcome)
        assertTrue("应当带原因码，实际：${e.detail}", e.detail.contains("OUT_OF_SCOPE"))
    }

    @Test
    fun `超时与主动拒绝被分开记`() {
        // ★★ 必须分开：用户主动拒绝说明他不同意这件事；
        //    超时说明他可能根本没看到。
        //    合并成一条会让"我明明没点过同意，怎么删了"这种质疑无从查起。
        val accepted = FileAuditEvents.confirmationResolved(1L, target(), ConfirmReason.DELETE_FILE, accepted = true)
        val declined = FileAuditEvents.confirmationResolved(1L, target(), ConfirmReason.DELETE_FILE, accepted = false)
        val timedOut = FileAuditEvents.confirmationResolved(
            1L, target(), ConfirmReason.DELETE_FILE, accepted = false, timedOut = true,
        )

        assertEquals(FileAuditOutcome.EXECUTED_AFTER_CONFIRM, accepted.outcome)
        assertEquals(FileAuditOutcome.CONFIRMATION_DECLINED, declined.outcome)
        assertEquals(FileAuditOutcome.CONFIRMATION_TIMEOUT, timedOut.outcome)
    }

    @Test
    fun `失败事件带上原因`() {
        val e = FileAuditEvents.failed(1L, target(), reason = "授权已被系统撤销")
        assertEquals(FileAuditOutcome.FAILED, e.outcome)
        assertEquals("授权已被系统撤销", e.detail)
    }

    // ─────────────────────────────────────────────────────────────
    //  筛选
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `按结论筛选`() {
        val log = FileAuditLog()
        log.record(FileAuditEvents.executed(1L, target(), false))
        log.record(
            FileAuditEvents.denied(
                2L, FileOp.DELETE, "/etc/passwd", DenyReason.OUT_OF_SCOPE, "越界",
            ),
        )
        log.record(FileAuditEvents.executed(3L, target(), true))

        val destructive = log.eventsWithOutcome(
            FileAuditOutcome.EXECUTED,
            FileAuditOutcome.EXECUTED_AFTER_CONFIRM,
        )
        assertEquals("应当筛出两条执行记录", 2, destructive.size)

        val denied = log.eventsWithOutcome(FileAuditOutcome.DENIED)
        assertEquals(1, denied.size)
    }

    @Test
    fun `clear 清空记录`() {
        val log = FileAuditLog()
        log.record(FileAuditEvents.executed(1L, target(), false))
        log.clear()
        assertTrue(log.events().isEmpty())
    }
}
