package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区模型与持久化的离线测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  本文件里最值钱的几条
 * ═══════════════════════════════════════════════════════════════
 *
 * 1. **损坏 → `Failed`，不是空列表**（`损坏的存储不能表现成空列表`）。
 *    这条如果红了，说明有人加了一个 `?: emptyList()` 的兜底 ——
 *    而那会让"文件坏了"与"用户还没添加过"在界面上长得一模一样。
 *
 * 2. **`WorkspacePolicy` 的默认值本身是被断言的**
 *    （`默认策略是能写不能删`）。`allowDelete = true` 的默认值意味着
 *    用户第一次用就允许助理删他的文件 —— 那是不可逆的。
 *
 * 3. **编码的确定性**。同一份列表两次编码必须产出同样的字节，
 *    否则"上次同步时间"之类的字段会让每次保存都产生 diff。
 */
class WorkspaceCodecTest {

    // ── 工具 ────────────────────────────────────────────────────

    private fun root(
        id: String = "w1",
        name: String = "文档",
        path: String = "/storage/emulated/0/Documents",
        token: String = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
    ) = ScopeRoot(id = id, displayName = name, path = path, token = token)

    private fun workspace(
        id: String = "w1",
        name: String = "文档",
        createdAt: Long = 1_000L,
        lastOpenedAt: Long = 1_000L,
        policy: WorkspacePolicy = WorkspacePolicy(),
    ) = Workspace(
        root = root(id = id, name = name),
        createdAt = createdAt,
        lastOpenedAt = lastOpenedAt,
        policy = policy,
    )

    private fun ok(outcome: WorkspaceDecodeOutcome): List<Workspace> {
        assertTrue("期望解码成功，实际是 $outcome", outcome is WorkspaceDecodeOutcome.Ok)
        return (outcome as WorkspaceDecodeOutcome.Ok).workspaces
    }

    // ── 往返 ────────────────────────────────────────────────────

    @Test
    fun `往返后内容一致`() {
        val original = listOf(workspace())

        val restored = ok(WorkspaceCodec.decode(WorkspaceCodec.encode(original)))

        assertEquals(1, restored.size)
        assertEquals("w1", restored[0].id)
        assertEquals("文档", restored[0].displayName)
        assertEquals("/storage/emulated/0/Documents", restored[0].path)
        // token 必须原样保留 —— 它是回到实际目录的唯一钥匙
        assertTrue("token 必须原样保留", restored[0].root.token.contains("primary%3ADocuments"))
        assertEquals(original[0].policy, restored[0].policy)
    }

    @Test
    fun `同样输入两次编码字节完全相同`() {
        val list = listOf(workspace(id = "a"), workspace(id = "b", lastOpenedAt = 2_000L))

        assertEquals(WorkspaceCodec.encode(list), WorkspaceCodec.encode(list))
    }

    @Test
    fun `编码结果不依赖输入顺序`() {
        val a = workspace(id = "a", lastOpenedAt = 1_000L)
        val b = workspace(id = "b", lastOpenedAt = 2_000L)

        // 排序后应当一致 —— 否则"列表顺序变了一下"会产生一份无意义的 diff
        assertEquals(
            WorkspaceCodec.encode(listOf(a, b)),
            WorkspaceCodec.encode(listOf(b, a)),
        )
    }

    @Test
    fun `解码后按最近打开降序`() {
        val older = workspace(id = "old", lastOpenedAt = 1_000L)
        val newer = workspace(id = "new", lastOpenedAt = 5_000L)

        val restored = ok(WorkspaceCodec.decode(WorkspaceCodec.encode(listOf(older, newer))))

        assertEquals("最近打开的应当排在最前", listOf("new", "old"), restored.map { it.id })
    }

    @Test
    fun `同一时刻按 id 升序保证顺序稳定`() {
        val list = listOf(
            workspace(id = "c", lastOpenedAt = 9L),
            workspace(id = "a", lastOpenedAt = 9L),
            workspace(id = "b", lastOpenedAt = 9L),
        )

        assertEquals(listOf("a", "b", "c"), ok(WorkspaceCodec.decode(WorkspaceCodec.encode(list))).map { it.id })
    }

    // ── ★★ 损坏 vs 空 ───────────────────────────────────────────

    @Test
    fun `空字符串解码成空列表而不是失败`() {
        // 空文件是**正常的初始状态**（还没存过），必须与"损坏"区分开
        assertEquals(emptyList<Workspace>(), ok(WorkspaceCodec.decode("")))
        assertEquals(emptyList<Workspace>(), ok(WorkspaceCodec.decode("   \n  ")))
    }

    @Test
    fun `损坏的存储不能表现成空列表`() {
        // ★★ 这条是本文件的核心。一个 `?: emptyList()` 的兜底会让
        //    "文件坏了"与"用户还没添加过"在界面上长得一模一样，
        //    而用户会重新添加一遍 —— 他以为数据没丢，其实丢了。
        val garbage = "{ 这不是 JSON"

        val outcome = WorkspaceCodec.decode(garbage)

        assertTrue("损坏必须报 Failed，不能兜底成空列表", outcome is WorkspaceDecodeOutcome.Failed)
        assertNotEquals(WorkspaceDecodeOutcome.Ok(emptyList<Workspace>()), outcome)
    }

    @Test
    fun `截断的 JSON 报失败`() {
        val full = WorkspaceCodec.encode(listOf(workspace()))
        val truncated = full.dropLast(full.length / 3)

        assertTrue(WorkspaceCodec.decode(truncated) is WorkspaceDecodeOutcome.Failed)
    }

    @Test
    fun `版本不认识时报失败而不是尽力解析`() {
        val text = """{"v":99,"items":[]}"""

        val outcome = WorkspaceCodec.decode(text)

        assertTrue(outcome is WorkspaceDecodeOutcome.Failed)
        assertTrue(
            "失败原因要提到版本，便于排查",
            (outcome as WorkspaceDecodeOutcome.Failed).reason.contains("版本"),
        )
    }

    @Test
    fun `重复 id 报失败`() {
        val dup = """{"v":1,"items":[
            {"root":{"id":"same","displayName":"A","path":"/storage/emulated/0/A","token":"t"},
             "createdAt":1,"lastOpenedAt":1,
             "policy":{"allowWrite":true,"allowDelete":false,"outputSubdir":"输出","maxFileBytes":4194304}},
            {"root":{"id":"same","displayName":"B","path":"/storage/emulated/0/B","token":"t"},
             "createdAt":2,"lastOpenedAt":2,
             "policy":{"allowWrite":true,"allowDelete":false,"outputSubdir":"输出","maxFileBytes":4194304}}
        ]}"""

        val outcome = WorkspaceCodec.decode(dup)

        assertTrue("重复 id 是损坏，不是'去重一下就行'", outcome is WorkspaceDecodeOutcome.Failed)
    }

    @Test
    fun `存储里含非法路径时在解码期就失败`() {
        // ★ 这是 @Serializable 加在 ScopeRoot 上的**好处**：
        //   init 校验会在反序列化时触发，一份被手工改坏的文件在这里就暴露，
        //   而不是在更晚的时刻表现为"某个工作区行为诡异"。
        val badPath = """{"v":1,"items":[
            {"root":{"id":"x","displayName":"X","path":"/","token":"t"},
             "createdAt":1,"lastOpenedAt":1,
             "policy":{"allowWrite":true,"allowDelete":false,"outputSubdir":"输出","maxFileBytes":4194304}}
        ]}"""

        val outcome = WorkspaceCodec.decode(badPath)

        assertTrue("授权根不能是 / —— 那等于授权整台设备", outcome is WorkspaceDecodeOutcome.Failed)
    }

    @Test
    fun `多出来的未知字段不影响读取`() {
        val withExtra = """{"v":1,"futureField":123,"items":[
            {"root":{"id":"x","displayName":"X","path":"/storage/emulated/0/X","token":"t","newField":1},
             "createdAt":1,"lastOpenedAt":1,"anotherNew":true,
             "policy":{"allowWrite":true,"allowDelete":false,"outputSubdir":"输出","maxFileBytes":4194304}}
        ]}"""

        assertEquals(listOf("x"), ok(WorkspaceCodec.decode(withExtra)).map { it.id })
    }

    // ── WorkspacePolicy ─────────────────────────────────────────

    @Test
    fun `默认策略是能写不能删`() {
        // ★ 这条断言的是**默认值本身**。
        //   allowDelete 默认 true 意味着用户第一次用就允许助理删他的文件 ——
        //   那是不可逆的。默认值也是产品决定，必须被钉住。
        val p = WorkspacePolicy()

        assertTrue("默认允许写入 —— 办公的核心就是产出文件", p.allowWrite)
        assertTrue("默认**不允许**删除 —— 它是这里唯一不可逆的操作", !p.allowDelete)
        assertEquals("输出", p.outputSubdir)
        assertEquals(4L * 1024 * 1024, p.maxFileBytes)
    }

    @Test
    fun `产出目录只有一段`() {
        // 多段路径需要在 SAF 里逐段创建，中途失败会留下半截目录树
        listOf("a/b", "a\\b", "/abs", "a/").forEach { bad ->
            val threw = try {
                WorkspacePolicy(outputSubdir = bad)
                false
            } catch (e: IllegalArgumentException) {
                true
            }
            assertTrue("产出目录「$bad」应当被拒绝（只能是一段）", threw)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `产出目录不能是点点`() {
        WorkspacePolicy(outputSubdir = "..")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `产出目录不能为空`() {
        WorkspacePolicy(outputSubdir = "  ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `单文件上限必须为正`() {
        WorkspacePolicy(maxFileBytes = 0)
    }

    // ── Workspace 本身 ──────────────────────────────────────────

    @Test
    fun `id 与展示名直接取自授权根`() {
        val w = workspace(id = "abc", name = "我的文档")

        // 不另造一个 id —— 两个真相来源迟早会不一致
        assertEquals("abc", w.id)
        assertEquals("我的文档", w.displayName)
        assertEquals("/storage/emulated/0/Documents", w.path)
    }

    @Test
    fun `从授权创建时创建时间与打开时间相同`() {
        val w = Workspace.fromGrant(root(), now = 12_345L)

        assertEquals(12_345L, w.createdAt)
        assertEquals(12_345L, w.lastOpenedAt)
    }

    @Test
    fun `标记打开只改打开时间且不修改原实例`() {
        val original = workspace(createdAt = 100L, lastOpenedAt = 100L)

        val opened = original.openedAt(999L)

        assertEquals("创建时间不能被打开动作改掉", 100L, opened.createdAt)
        assertEquals(999L, opened.lastOpenedAt)
        assertEquals("原实例必须保持不变", 100L, original.lastOpenedAt)
    }

    @Test
    fun `策略随工作区一起往返`() {
        val custom = WorkspacePolicy(allowWrite = false, allowDelete = true, outputSubdir = "产物", maxFileBytes = 1024L)

        val restored = ok(WorkspaceCodec.decode(WorkspaceCodec.encode(listOf(workspace(policy = custom)))))

        assertEquals(custom, restored[0].policy)
    }
}
