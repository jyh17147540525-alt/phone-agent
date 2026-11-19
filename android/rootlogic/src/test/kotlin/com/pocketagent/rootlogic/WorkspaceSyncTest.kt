package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区 ↔ `/work` 的同步决策。
 *
 * 真机上会翻的三处车：
 *  · 时间戳相同还去复制 —— 一次对话白等十几秒；
 *  · 只同步了一部分就超限 —— "为什么这个文件在、那个不在"，比不同步更难解释；
 *  · 把"后写覆盖先写"说成"自动合并" —— 用户以为安全，其实丢了改动。
 */
class WorkspaceSyncTest {

    private fun stamp(path: String, mtime: Long, size: Long = 10) = FileStamp(path, size, mtime)

    @Test
    fun `只在工作区里有的文件要拉下来`() {
        val plan = WorkspaceSync.plan(
            local = emptyList(),
            remote = listOf(stamp("a.txt", 100)),
        )
        assertEquals(1, plan.size)
        assertEquals(SyncDirection.PULL, plan.first().second)
        assertEquals("a.txt", plan.first().first.path)
    }

    @Test
    fun `只在 work 里有的文件要推回去`() {
        val plan = WorkspaceSync.plan(
            local = listOf(stamp("b.txt", 100)),
            remote = emptyList(),
        )
        assertEquals(SyncDirection.PUSH, plan.first().second)
    }

    @Test
    fun `谁新谁赢`() {
        val push = WorkspaceSync.plan(
            local = listOf(stamp("x", 300)),
            remote = listOf(stamp("x", 200)),
        )
        assertEquals(SyncDirection.PUSH, push.single().second)

        val pull = WorkspaceSync.plan(
            local = listOf(stamp("x", 100)),
            remote = listOf(stamp("x", 500)),
        )
        assertEquals(SyncDirection.PULL, pull.single().second)
    }

    @Test
    fun `时间戳一样就什么都不做`() {
        val plan = WorkspaceSync.plan(
            local = listOf(stamp("same.txt", 42)),
            remote = listOf(stamp("same.txt", 42)),
        )
        assertTrue(plan.isEmpty())
        assertEquals("工作区与 /work 已经一致，没有要同步的。", WorkspaceSync.syncNote(plan))
    }

    @Test
    fun `动作顺序稳定，按路径排`() {
        val plan = WorkspaceSync.plan(
            local = listOf(stamp("z.txt", 1), stamp("m.txt", 1)),
            remote = listOf(stamp("a.txt", 1)),
        )
        assertEquals(listOf("a.txt", "m.txt", "z.txt"), plan.map { it.first.path })
    }

    @Test
    fun `超限时整次都不同步，并给出原因`() {
        assertNull(WorkspaceSync.limitNote(count = 10, totalBytes = 1024))
        // 单个大文件优先报：用户能直接去挪那一个
        val big = WorkspaceSync.limitNote(count = 2, totalBytes = 1024, biggestFile = WorkspaceSync.MAX_FILE_BYTES + 1)!!
        assertTrue(big.contains("单个文件"))
        val many = WorkspaceSync.limitNote(count = WorkspaceSync.MAX_FILES + 1, totalBytes = 1024)!!
        assertTrue(many.contains("${WorkspaceSync.MAX_FILES} 个的上限"))
        val heavy = WorkspaceSync.limitNote(count = 1, totalBytes = WorkspaceSync.MAX_BYTES + 1)!!
        assertTrue(heavy.contains("MB"))
    }

    @Test
    fun `同步结论要说清方向各多少个`() {
        val plan = WorkspaceSync.plan(
            local = listOf(stamp("p1", 100), stamp("p2", 100)),
            remote = listOf(stamp("q1", 100)),
        )
        val note = WorkspaceSync.syncNote(plan)
        assertTrue(note, note.contains("拉进 1 个"))
        assertTrue(note, note.contains("推回 2 个"))
    }

    @Test
    fun `冲突口径不许说成合并`() {
        val note = WorkspaceSync.conflictNote()
        assertTrue(note.contains("盖掉"))
        assertTrue(note.contains("不做三路合并"))
    }
}
