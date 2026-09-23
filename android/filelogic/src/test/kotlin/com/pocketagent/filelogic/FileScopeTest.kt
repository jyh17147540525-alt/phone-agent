package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FileScope] / [ScopeRoot] / [DenyRules] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 两件事：
 *
 * 1. **范围模型自身不能是越权的。** 一个 `ScopeRoot("/")` 会让
 *    `isWithin` 对一切返回 true —— 于是"授权一个目录"变成"授权整台设备"，
 *    而用户在界面上看到的仍然是"你授权了 1 个目录"。
 *
 * 2. **黑名单的匹配必须是精确的，而且只能加严。**
 *    - 太宽（`data` 单独成规则）→ 用户 `/Documents/data/` 里的正常文件读不了
 *    - 太窄（用子串匹配）→ `my.ssh.backup` 被误伤
 *    - 可被用户删掉 → 一次"为了方便"的配置就解除了全部保护
 */
class FileScopeTest {

    private fun root(
        id: String = "r1",
        name: String = "文档",
        path: String = "/storage/emulated/0/Documents",
    ) = ScopeRoot(id = id, displayName = name, path = path, token = "content://tree/$id")

    // ─────────────────────────────────────────────────────────────
    //  ScopeRoot 的自我约束
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `根目录不能作为授权范围`() {
        // ★★ 这是本文件最重要的一条。
        //
        //    isWithin 对 ancestor="/" 恒返回 true（语义上根确实包含一切）。
        //    所以"授权 /"就等于"授权整块存储" —— 而用户在界面上
        //    只会看到"已授权 1 个目录"，没有任何线索提示范围是全盘。
        //
        //    这类"界面说的和实际发生的不一致"正是本项目最忌讳的。
        val e = runCatching { root(path = "/") }.exceptionOrNull()
        assertTrue("应当拒绝把 / 作为授权范围，实际：$e", e is IllegalArgumentException)
        assertTrue("拒绝理由应当说明原因，实际：${e?.message}", e?.message?.contains("根目录") == true)
    }

    @Test
    fun `未归一化的路径被拒绝`() {
        // ★ 未归一化的根会让 isWithin 的比较失去意义：
        //   "/a/b/" 与 "/a/b" 段级比较结果相同，但字符串不同 ——
        //   于是"这个根是哪一条"的判断会不稳定。
        val e = runCatching { root(path = "/storage/emulated/0/Documents/") }.exceptionOrNull()
        assertTrue("应当拒绝未归一化的路径，实际：$e", e is IllegalArgumentException)

        val e2 = runCatching { root(path = "/storage/emulated/0/../Documents") }.exceptionOrNull()
        assertTrue("含 .. 的路径也应当被拒绝，实际：$e2", e2 is IllegalArgumentException)
    }

    @Test
    fun `规范路径可以正常构造`() {
        val r = root()
        assertEquals("/storage/emulated/0/Documents", r.path)
        assertEquals("content://tree/r1", r.token)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★★ 黑名单匹配的精确度
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `密钥文件名命中`() {
        val rules = DenyRules()
        val hit = rules.matches("/storage/emulated/0/Documents/.ssh/id_rsa")
        assertTrue("id_rsa 应当被拦下，实际：$hit", hit is DenyHit.Name)
    }

    @Test
    fun `密钥扩展名命中`() {
        val rules = DenyRules()
        assertTrue(rules.matches("/a/server.pem") is DenyHit.Suffix)
        assertTrue(rules.matches("/a/release.keystore") is DenyHit.Suffix)
        assertTrue(rules.matches("/a/private.key") is DenyHit.Suffix)
    }

    @Test
    fun `密钥目录段命中`() {
        val rules = DenyRules()
        assertTrue(rules.matches("/home/u/.gnupg/secring.gpg") is DenyHit.Segment)
    }

    @Test
    fun `其他应用的私有数据命中`() {
        // ★ 为什么这条不能拆成"段名等于 data"：
        //   用户完全可能有 `/Documents/data/报表.csv`。见下一条测试。
        val rules = DenyRules()
        val hit = rules.matches("/storage/emulated/0/Android/data/com.example.app/cache")
        assertTrue("Android/data 应当被拦下，实际：$hit", hit is DenyHit.Run)
    }

    @Test
    fun `叫 data 的普通目录不被拦`() {
        // ★★ 与上一条互为反向保证。
        //    一个把 `data` 写进 deniedSegments 的实现会通过上一条测试，
        //    但会把用户 `/Documents/data/` 里的所有文件都变成不可读 ——
        //    而用户完全不知道为什么。
        val rules = DenyRules()
        assertNull(
            "普通目录名 data 不该被拦",
            rules.matches("/storage/emulated/0/Documents/data/报表.csv"),
        )
    }

    @Test
    fun `段名匹配是精确相等而不是子串`() {
        // ★★ 一条用 `contains(".ssh")` 的实现会拦下下面这个文件名，
        //    而它是一个完全正常的备份文件。
        //    "宁可多拦"在本项目里是成立的立场，但**多拦到无理由**会让
        //    用户学会"忽略这些提示"，那才是真正的损失。
        val rules = DenyRules()
        assertNull(
            "my.ssh.backup 不是 .ssh 目录",
            rules.matches("/storage/emulated/0/Documents/my.ssh.backup"),
        )
    }

    @Test
    fun `扩展名匹配要求以点开头`() {
        // `.key` 不该命中 `keyboard.txt`
        val rules = DenyRules()
        assertNull(rules.matches("/Documents/keyboard.txt"))
        assertNull(rules.matches("/Documents/monkey.md"))
    }

    @Test
    fun `应用私有目录命中`() {
        val rules = DenyRules()
        val hit = rules.matches("/data/data/com.pocketagent.debug/files/dsh/credentials.yaml")
        assertTrue("应用私有目录应当被拦下，实际：$hit", hit is DenyHit.Run)
    }

    @Test
    fun `命中详情带用户可读说明`() {
        // 按项目纪律，被拒时用户需要知道"为什么"，否则只会觉得软件坏了
        val hit = DenyRules().matches("/a/.ssh/id_rsa")
        assertNotNull(hit)
        assertTrue("说明里应当提到密钥，实际：${hit!!.userMessage}", hit.userMessage.contains("密钥"))
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 黑名单只能加严
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `用户追加的规则不会顶掉内置规则`() {
        // ★★ 这条守的是产品红线：**范围是用户给的，底线不是。**
        //
        //    如果用户把授权范围划大（直接选了整块内部存储）——
        //    那是完全正常的行为，但不该让 `.ssh` 里的私钥变得可读。
        //
        //    ⚠️ 这个测试只能验证"追加之后内置的还在"。真正的保证在**类型上**：
        //       DenyRules 没有、也不该有 removeSegment 之类的方法。
        //       将来如果有人加了，这条测试不会拦住他 —— 但 code review 会看到
        //       这里有一条测试在守着"不能删"。
        val custom = DenyRules().withUserRules(extraSegments = setOf("我的隐私"))

        assertTrue("内置规则必须还在", custom.deniedSegments.contains(".ssh"))
        assertTrue("用户规则应当加上", custom.deniedSegments.contains("我的隐私"))
        assertTrue("新规则应当生效", custom.matches("/Documents/我的隐私/x.txt") is DenyHit.Segment)
    }

    @Test
    fun `用户追加文件名与扩展名规则`() {
        val custom = DenyRules().withUserRules(
            extraSuffixes = setOf(".p12"),
            extraNames = setOf("工资单.xlsx"),
        )
        assertTrue(custom.matches("/Documents/工资单.xlsx") is DenyHit.Name)
        assertTrue(custom.matches("/Documents/a.p12") is DenyHit.Suffix)
    }

    // ─────────────────────────────────────────────────────────────
    //  ★ 授权根的查找
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `取最深的那条授权`() {
        // ★ 用户完全可能既授权了整块存储、又单独授权了某个子目录。
        //   这时应当用子目录那条 —— 相对路径更短，通道少走几层；
        //   而且如果外层那条将来被撤销，内层仍然有效。
        val outer = root(id = "outer", name = "内部存储", path = "/storage/emulated/0")
        val inner = root(id = "inner", name = "文档", path = "/storage/emulated/0/Documents")
        val scope = FileScope(roots = listOf(outer, inner))

        assertEquals("inner", scope.rootContaining("/storage/emulated/0/Documents/a.md")?.id)
        assertEquals("outer", scope.rootContaining("/storage/emulated/0/DCIM/a.jpg")?.id)
    }

    @Test
    fun `范围外返回 null`() {
        val scope = FileScope(roots = listOf(root()))
        assertNull(scope.rootContaining("/etc/passwd"))
        assertNull(scope.rootContaining("/storage/emulated/0/Documents-evil/x"))
    }

    @Test
    fun `没有授权时 isEmpty 为真`() {
        // 界面靠它区分"还没配置"与"操作失败" —— 前者该给一个"去选目录"的按钮
        assertTrue(FileScope().isEmpty)
        assertFalse(FileScope(roots = listOf(root())).isEmpty)
    }

    @Test
    fun `withUserDenies 保留原有授权根`() {
        val scope = FileScope(roots = listOf(root()))
            .withUserDenies(extraSegments = setOf("隐私"))

        assertEquals(1, scope.roots.size)
        assertTrue(scope.denyRules.deniedSegments.contains("隐私"))
        assertTrue(scope.denyRules.deniedSegments.contains(".ssh"))
    }
}
