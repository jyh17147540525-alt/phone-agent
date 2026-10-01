package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区注册表的离线测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 本文件的核心是「列表损坏时拒绝写入」那一组
 * ═══════════════════════════════════════════════════════════════
 *
 * 一个想当然的实现：
 *
 * ```kotlin
 * val current = (store.load() as? Ok)?.workspaces ?: emptyList()   // ✗
 * store.save(current + newWorkspace)
 * ```
 *
 * 后果是：**存储损坏时用户加一个工作区，原有全部工作区被静默抹掉。**
 * 他以为自己只加了一个，实际丢了全部，而且没有任何提示。
 *
 * ⇒ 那组测试断言的不只是"返回了 Rejected"，还有
 *   **"原有数据还在"** —— 后者才是真正要守的东西。
 *   只断言前者的话，一个"先清空再拒绝"的实现也能通过。
 */
class WorkspaceRegistryTest {

    // ── 工具 ────────────────────────────────────────────────────

    private fun root(
        id: String,
        name: String = "文档",
        path: String = "/storage/emulated/0/Documents",
    ) = ScopeRoot(id = id, displayName = name, path = path, token = "content://tree/$id")

    private fun workspace(id: String, lastOpenedAt: Long = 1_000L) = Workspace(
        root = root(id),
        createdAt = 1_000L,
        lastOpenedAt = lastOpenedAt,
    )

    private fun expectOk(outcome: WorkspaceChangeOutcome) {
        assertEquals("期望成功，实际是 $outcome", WorkspaceChangeOutcome.Ok, outcome)
    }

    private fun idsIn(store: WorkspaceStore): List<String> =
        when (val loaded = store.load()) {
            is WorkspaceDecodeOutcome.Ok -> loaded.workspaces.map { it.id }
            is WorkspaceDecodeOutcome.Failed -> throw AssertionError("期望能读出列表，实际：${loaded.reason}")
        }

    // ── 基本增删改 ──────────────────────────────────────────────

    @Test
    fun `加一个工作区后能读回来`() {
        val store = InMemoryWorkspaceStore()
        val registry = WorkspaceRegistry(store)

        expectOk(registry.add(root("w1"), now = 500L))

        assertEquals(listOf("w1"), idsIn(store))
    }

    @Test
    fun `新加的工作区创建时间与打开时间一致`() {
        val store = InMemoryWorkspaceStore()

        expectOk(WorkspaceRegistry(store).add(root("w1"), now = 777L))

        val w = (store.load() as WorkspaceDecodeOutcome.Ok).workspaces.single()
        assertEquals(777L, w.createdAt)
        assertEquals(777L, w.lastOpenedAt)
    }

    @Test
    fun `重复添加同一个目录被拒绝`() {
        val store = InMemoryWorkspaceStore()
        val registry = WorkspaceRegistry(store)
        expectOk(registry.add(root("w1"), now = 1L))

        val outcome = registry.add(root("w1"), now = 2L)

        assertTrue("同一个目录加两次应当被拒绝，实际是 $outcome", outcome is WorkspaceChangeOutcome.Rejected)
        assertEquals("列表里仍应只有一个", 1, idsIn(store).size)
    }

    @Test
    fun `移除一个工作区`() {
        val store = InMemoryWorkspaceStore(listOf(workspace("a"), workspace("b")))
        val registry = WorkspaceRegistry(store)

        expectOk(registry.remove("a"))

        assertEquals(listOf("b"), idsIn(store))
    }

    @Test
    fun `移除不存在的被拒绝`() {
        val store = InMemoryWorkspaceStore(listOf(workspace("a")))

        val outcome = WorkspaceRegistry(store).remove("nope")

        assertTrue(outcome is WorkspaceChangeOutcome.Rejected)
        assertEquals("拒绝时不能动到原有数据", listOf("a"), idsIn(store))
    }

    @Test
    fun `touch 只改打开时间不改创建时间`() {
        val store = InMemoryWorkspaceStore(listOf(workspace("a", lastOpenedAt = 100L)))

        expectOk(WorkspaceRegistry(store).touch("a", now = 999L))

        val w = (store.load() as WorkspaceDecodeOutcome.Ok).workspaces.single()
        assertEquals(999L, w.lastOpenedAt)
        assertEquals("创建时间不能被打开动作改掉", 1_000L, w.createdAt)
    }

    @Test
    fun `updatePolicy 只改策略`() {
        val store = InMemoryWorkspaceStore(listOf(workspace("a")))
        val newPolicy = WorkspacePolicy(allowWrite = false, outputSubdir = "产物")

        expectOk(WorkspaceRegistry(store).updatePolicy("a", newPolicy))

        val w = (store.load() as WorkspaceDecodeOutcome.Ok).workspaces.single()
        assertEquals(newPolicy, w.policy)
        assertEquals("其它字段不该被动", 1_000L, w.createdAt)
    }

    // ── ★★ 核心：损坏时拒绝写入，且不动原有数据 ─────────────────

    @Test
    fun `列表损坏时拒绝添加而不是覆盖`() {
        // ★★ 本文件的核心。
        //    如果实现写成了 `?: emptyList()`，这里会变成
        //    "原有 a 被抹掉、只剩 new" —— 而用户以为自己只是加了一个。
        val store = InMemoryWorkspaceStore(listOf(workspace("a")))
        val registry = WorkspaceRegistry(store)
        store.failNextLoadWith("文件被截断")

        val outcome = registry.add(root("new"), now = 100L)

        assertTrue("必须被拒绝，实际是 $outcome", outcome is WorkspaceChangeOutcome.Rejected)
        // ★ 关键：不只断言"拒绝了"，还要断言**原有数据还在** ——
        //   只断言前者的话，一个"先清空再拒绝"的实现也能通过。
        assertEquals("原有工作区不能被抹掉", listOf("a"), idsIn(store))
    }

    @Test
    fun `列表损坏时拒绝移除`() {
        val store = InMemoryWorkspaceStore(listOf(workspace("a")))
        val registry = WorkspaceRegistry(store)
        store.failNextLoadWith("文件被截断")

        val outcome = registry.remove("a") as WorkspaceChangeOutcome.Rejected

        // ⚠️ **不能只断言 `is Rejected`。**
        //    变异验证发现的：把 `readForWrite` 的失败分支改成返回空列表之后，
        //    `current.none { it.id == id }` 为真，于是这里**同样**返回 Rejected
        //    （原因变成"找不到这个工作区"）—— 测试照样通过，
        //    而那个 bug 的后果是"用户加一个工作区，原有全部被抹掉"。
        //
        //    ⇒ 必须断言**拒绝的原因指向存储问题**，而不是"找不到"。
        //    这是"断言拒绝"与"断言拒绝的理由"之间的区别，而后者才是有用的。
        assertTrue(
            "原因应指向存储问题而不是「找不到」，实际是「${outcome.reason}」",
            outcome.reason.contains("读不出来"),
        )
        assertEquals(listOf("a"), idsIn(store))
    }

    @Test
    fun `列表损坏时拒绝改策略`() {
        val store = InMemoryWorkspaceStore(listOf(workspace("a")))
        val registry = WorkspaceRegistry(store)
        store.failNextLoadWith("文件被截断")

        val outcome = registry.updatePolicy("a", WorkspacePolicy(allowDelete = true)) as WorkspaceChangeOutcome.Rejected

        // 同 `列表损坏时拒绝移除`：必须断言**拒绝的理由**，不能只断言被拒了。
        assertTrue(
            "原因应指向存储问题而不是「找不到」，实际是「${outcome.reason}」",
            outcome.reason.contains("读不出来"),
        )
        assertEquals(
            "策略不该被改",
            false,
            (store.load() as WorkspaceDecodeOutcome.Ok).workspaces.single().policy.allowDelete,
        )
    }

    @Test
    fun `拒绝原因里要带上存储的真实问题`() {
        // 只说"操作失败"会让用户不知道该怎么办；
        // 带上"列表读不出来"他至少知道该先处理那个问题。
        val store = InMemoryWorkspaceStore(listOf(workspace("a")))
        store.failNextLoadWith("文件被截断")

        val outcome = WorkspaceRegistry(store).add(root("new"), now = 1L) as WorkspaceChangeOutcome.Rejected

        assertTrue("原因里应包含存储层给的信息，实际是「${outcome.reason}」", outcome.reason.contains("文件被截断"))
    }

    @Test
    fun `list 不吞掉损坏`() {
        // 界面要能显示"读不出来"，而不是显示"还没有工作区"
        val store = InMemoryWorkspaceStore(listOf(workspace("a")))
        store.failNextLoadWith("文件被截断")

        val outcome = WorkspaceRegistry(store).list()

        assertTrue("list 必须把损坏如实交出去", outcome is WorkspaceDecodeOutcome.Failed)
    }

    // ── 保存失败 ────────────────────────────────────────────────

    @Test
    fun `保存失败时如实报告而不是假装成功`() {
        val store = InMemoryWorkspaceStore()
        val registry = WorkspaceRegistry(store)
        store.failNextSave()

        val outcome = registry.add(root("w1"), now = 1L)

        assertTrue("必须是 SaveFailed 而不是 Ok，实际是 $outcome", outcome is WorkspaceChangeOutcome.SaveFailed)
        assertEquals("没保存成功就不该有数据", emptyList<String>(), idsIn(store))
    }

    @Test
    fun `保存失败与业务拒绝是两种不同的结论`() {
        // 前者用户改什么都没用（该重试/排查），后者用户改一下就行。
        // 合并成一句"操作失败"会让用户对着一个他解决不了的问题反复尝试。
        val store = InMemoryWorkspaceStore()
        val registry = WorkspaceRegistry(store)

        store.failNextSave()
        val saveFail = registry.add(root("w1"), now = 1L)
        store.failNextLoadWith("坏了")
        val rejected = registry.add(root("w2"), now = 2L)

        assertTrue(saveFail is WorkspaceChangeOutcome.SaveFailed)
        assertTrue(rejected is WorkspaceChangeOutcome.Rejected)
    }
}
