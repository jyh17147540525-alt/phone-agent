package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PathNormalizer] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  这里守的是什么
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个对象只有一百多行，但它是**唯一**挡在"授权一个目录"与
 * "授权整块存储"之间的东西。它判错的每一种方式都不会报错：
 *
 * | 漏了什么 | 用户看到的现象 | 会去查的方向 |
 * |---|---|---|
 * | `..` 消解不彻底 | 什么都没发生，但相册被读了 | 不会去查 |
 * | 包含判定用字符串前缀 | 同上，只在特定目录名下暴露 | 不会去查 |
 * | 对路径做了 Unicode 归一化 | 一个全角句点的目录名变成越权入口 | 不会去查 |
 * | 大小写折叠 | `/Documents` 与 `/documents` 混为一谈 | 不会去查 |
 *
 * 四条都是**零反馈**的。所以这一组测试的价值不在于"验证正常路径能跑"，
 * 而在于把每一种"看起来对但其实越界"的构造都钉死。
 */
class PathNormalizerTest {

    private fun valid(raw: String): String {
        val r = PathNormalizer.normalize(raw)
        assertTrue("期望「$raw」归一化成功，实际被拒：$r", r is PathNormalizer.Result.Valid)
        return (r as PathNormalizer.Result.Valid).path
    }

    private fun rejected(raw: String): String {
        val r = PathNormalizer.normalize(raw)
        assertTrue("期望「$raw」被拒绝，实际通过了：$r", r is PathNormalizer.Result.Rejected)
        return (r as PathNormalizer.Result.Rejected).reason
    }

    // ─────────────────────────────────────────────────────────────
    //  正常路径
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `已经是规范形式的路径原样返回`() {
        assertEquals("/storage/emulated/0/Documents/a.md", valid("/storage/emulated/0/Documents/a.md"))
    }

    @Test
    fun `重复分隔符被折叠`() {
        // 用户手打路径、或者从别处复制粘贴时很容易带出多余的斜杠
        assertEquals("/a/b", valid("/a//b"))
        assertEquals("/a/b", valid("///a/b"))
    }

    @Test
    fun `单点段被丢弃`() {
        assertEquals("/a/b", valid("/a/./b"))
        assertEquals("/a/b", valid("/./a/./b"))
    }

    @Test
    fun `尾部斜杠被去掉`() {
        // ⚠️ 不去掉的话 `/Documents` 与 `/Documents/` 会被当成两个不同的字符串，
        //    而 isWithin 的段级比较对两者结果相同 —— 于是"相等"的判断会出错
        assertEquals("/a/b", valid("/a/b/"))
        assertEquals("/a/b", valid("/a/b///"))
    }

    @Test
    fun `根目录保持为一个斜杠`() {
        assertEquals("/", valid("/"))
        assertEquals("/", valid("///"))
        assertEquals("/", valid("/."))
    }

    @Test
    fun `反斜杠被当作分隔符统一`() {
        // 模型偶尔会输出 Windows 风格的路径
        assertEquals("/a/b", valid("/a\\b"))
        assertEquals("/a/b", valid("\\a\\b"))
    }

    @Test
    fun `首尾空白被去掉`() {
        // 模型输出里带空格是很常见的
        assertEquals("/a/b", valid("  /a/b  "))
    }

    @Test
    fun `路径中间的空格被保留`() {
        // ★ 与上一条互为反向保证：如果实现写成 `raw.trim()` 之后又对每段做 trim，
        //   一个叫「我的 报告.md」的文件就会指向不存在的路径 —— 而那是静默的
        assertEquals("/Documents/我的 报告.md", valid("/Documents/我的 报告.md"))
    }

    @Test
    fun `中文与空格文件名原样保留`() {
        assertEquals("/storage/emulated/0/文档/季度 总结.md", valid("/storage/emulated/0/文档/季度 总结.md"))
    }

    // ─────────────────────────────────────────────────────────────
    //  ★★ `..` 消解 —— 本文件的核心
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `向上一级是合法的归一化`() {
        // ⚠️ 这一条与下面"越根被拒"必须同时存在。
        //    只写"拒绝越根"的测试，一个"见到 `..` 就拒"的过度实现也能通过 ——
        //    而那会让 `/Documents/Work/../Notes` 这种完全正常的路径失效。
        assertEquals("/Documents/Notes", valid("/Documents/Work/../Notes"))
    }

    @Test
    fun `多个向上一级依次消解`() {
        assertEquals("/a", valid("/a/b/c/../../"))
        assertEquals("/Documents/Work", valid("/Documents/A/B/../../Work"))
    }

    @Test
    fun `越过根目录被拒绝`() {
        // ★★ 这是最关键的一条。它守的是：
        //    `/Documents/../../DCIM` 不该被当成"在 Documents 里"。
        rejected("/..")
        rejected("/../etc")
        rejected("/a/../..")
        rejected("/a/../../b")
    }

    @Test
    fun `越根时不会被夹到根`() {
        // ★★★ 这条比上一条更细，也更要紧。
        //
        //    一个"善意"的实现会把 `/a/../../Documents` 夹到根，得到 `/Documents`。
        //    如果用户恰好授权了 `/Documents`，这次**越界就静默变成了合法操作**。
        //
        //    而越根本身是攻击信号（只有构造出来的路径才会越根），
        //    正常使用永远不会产生它。所以必须拒绝，不能"修正"。
        val reason = rejected("/a/../../Documents")
        assertTrue("拒绝理由应当说明是越根，实际：$reason", reason.contains("根"))
    }

    @Test
    fun `点段与点段组合的边界`() {
        assertEquals("/a", valid("/a/."))
        assertEquals("/a", valid("/./a"))
        assertEquals("/a", valid("/a/b/.."))
    }

    // ─────────────────────────────────────────────────────────────
    //  ★★ 三个"故意不做"的行为 —— 每一条都是一个真实的安全边界
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `全角句点不被折成点段`() {
        // ★★★ 这条测试存在的唯一理由是**防止有人"顺手"给路径加上 NFKC 归一化**。
        //
        //    本项目在 SensitiveDetector 里对文本用了 NFKC（把全角「立即付款」
        //    与半角「立即付款」视作同一个词），那是**对的**。
        //    但把同一个手法用到路径上，就会**凭空造出一次路径穿越**：
        //    一个叫「．．」的目录名（U+FF0E ×2）会被折成 `..`，
        //    于是「/a/．．/b」变成了「/a/../b」=「/b」。
        //
        //    文件系统只认 ASCII 的 U+002E，所以这里必须逐字节比较。
        assertEquals("/a/．．/b", valid("/a/．．/b"))
    }

    @Test
    fun `大小写不被折叠`() {
        // ★★ Android 的 /sdcard 底下是 ext4 / f2fs，**大小写敏感**。
        //    折叠会让 /Documents/Secret 与 /documents/secret 混为一谈 ——
        //    那是**放行**方向。
        //
        //    ⚠️ 真机上如果遇到"明明授权了却说不匹配"，第一件事就是查大小写，
        //       而不是改这里的判定。
        assertEquals("/Documents/Secret.md", valid("/Documents/Secret.md"))
        assertFalse(
            "大小写不同的路径不该被视作同一个",
            PathNormalizer.isWithin("/documents/secret.md", "/Documents"),
        )
    }

    @Test
    fun `百分号编码不被解码`() {
        // ★★ 解码是 Android 层的责任，必须在调用本层**之前**完成。
        //    放在这里会引入"解一次"与"解两次"的差异（%252E → %2E → .），
        //    而那正是**双重解码攻击**的经典入口。
        //
        //    所以 `%2E%2E` 在这里就是一个普通文件名，不是 `..`。
        assertEquals("/a/%2E%2E/b", valid("/a/%2E%2E/b"))
    }

    // ─────────────────────────────────────────────────────────────
    //  非法输入
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `空路径被拒绝`() {
        rejected("")
        rejected("   ")
    }

    @Test
    fun `相对路径被拒绝`() {
        // 相对路径的语义取决于当前目录，而"当前目录"在 Android 上不是一个
        // 确定的东西（进程的工作目录恒为 /）。接受它等于接受一个
        // 我们无法解释的路径。
        rejected("Documents/a.md")
        rejected("./a.md")
        rejected("..")
    }

    @Test
    fun `含空字符的路径被拒绝`() {
        // ★ NUL 会让底层 C 调用在此处截断 —— 我们看到的路径与实际操作的
        //   不是同一个。Kotlin 的 File API 会拒绝，但拒绝的时机不可控。
        val reason = rejected("/Documents/a\u0000.md")
        assertTrue("拒绝理由应当提到非法字符，实际：$reason", reason.contains("非法字符"))
    }

    // ─────────────────────────────────────────────────────────────
    //  ★★ isWithin —— 段级比较
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `同名前缀的兄弟目录不在其内`() {
        // ★★★ 这是整个文件里最容易被写错的一条。
        //
        //    最自然的写法是 `child.startsWith(ancestor)`，而它对下面这个
        //    例子返回 true：
        //
        //        "/storage/emulated/0/Documents-evil/x".startsWith("/storage/emulated/0/Documents")
        //
        //    一个用 startsWith 的实现会**通过所有"正常路径"的测试**，
        //    只在攻击构造下暴露。所以这里必须用段级比较。
        assertFalse(
            "Documents-evil 不在 Documents 之内",
            PathNormalizer.isWithin("/storage/emulated/0/Documents-evil/x", "/storage/emulated/0/Documents"),
        )
        assertFalse(
            "Documents2 不在 Documents 之内",
            PathNormalizer.isWithin("/storage/emulated/0/Documents2", "/storage/emulated/0/Documents"),
        )
    }

    @Test
    fun `相同路径视为在内`() {
        assertTrue(PathNormalizer.isWithin("/a/b", "/a/b"))
    }

    @Test
    fun `直接子项与深层子项都在内`() {
        assertTrue(PathNormalizer.isWithin("/a/b/c", "/a/b"))
        assertTrue(PathNormalizer.isWithin("/a/b/c/d/e.md", "/a/b"))
    }

    @Test
    fun `父目录不在子目录之内`() {
        assertFalse(PathNormalizer.isWithin("/a", "/a/b"))
        assertFalse(PathNormalizer.isWithin("/a/b", "/a/b/c"))
    }

    @Test
    fun `根目录包含一切`() {
        // ⚠️ 这个语义本身是对的（根确实包含所有路径）。
        //    真正的防线在 ScopeRoot：它**不允许**把 `/` 作为授权范围。
        //    两件事分开守，是因为把"根包含一切"改成 false 会让 parentOf 之类的
        //    逻辑出错，而那个错误比"有人构造 ScopeRoot(\"/\")"更可能发生。
        assertTrue(PathNormalizer.isWithin("/a/b", "/"))
    }

    // ─────────────────────────────────────────────────────────────
    //  relativeTo / parentOf / leafOf / extensionOf
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `relativeTo 给出相对路径`() {
        assertEquals("b/c.md", PathNormalizer.relativeTo("/a/b/c.md", "/a"))
        assertEquals("c.md", PathNormalizer.relativeTo("/a/b/c.md", "/a/b"))
    }

    @Test
    fun `relativeTo 对相同路径返回空串而不是 null`() {
        // ★ 空串 = "就是这个目录本身"，null = "不在其内"。
        //   两者是不同的事，调用方（比如"列出这个目录"）需要区分 ——
        //   合并成 null 会让"列出授权根"变成一个无法表达的操作。
        assertEquals("", PathNormalizer.relativeTo("/a/b", "/a/b"))
    }

    @Test
    fun `relativeTo 对不在其内的路径返回 null`() {
        assertNull(PathNormalizer.relativeTo("/x/y", "/a"))
        assertNull(PathNormalizer.relativeTo("/Documents-evil", "/Documents"))
    }

    @Test
    fun `parentOf 的边界`() {
        assertEquals("/a", PathNormalizer.parentOf("/a/b"))
        assertEquals("/", PathNormalizer.parentOf("/a"))
        // ★ 根的父目录是 null（不存在）。返回 "/" 会让"向上找授权根"的循环死循环。
        assertNull(PathNormalizer.parentOf("/"))
    }

    @Test
    fun `leafOf 取最后一段`() {
        assertEquals("c.md", PathNormalizer.leafOf("/a/b/c.md"))
        assertEquals("b", PathNormalizer.leafOf("/a/b"))
        assertEquals("", PathNormalizer.leafOf("/"))
    }

    @Test
    fun `extensionOf 取小写扩展名`() {
        assertEquals("md", PathNormalizer.extensionOf("/a/报告.MD"))
        assertEquals("tar", PathNormalizer.extensionOf("/a/x.tar"))
        assertEquals("", PathNormalizer.extensionOf("/a/README"))
    }

    @Test
    fun `隐藏文件没有扩展名`() {
        // ★ 这条容易被忽略：`.gitignore` 的 `lastIndexOf('.')` 是 0。
        //   把它的扩展名当成 "gitignore" 会让基于扩展名的规则误伤一整类文件
        //   （所有点开头的文件都会被当成有扩展名）。
        assertEquals("", PathNormalizer.extensionOf("/a/.gitignore"))
        assertEquals("", PathNormalizer.extensionOf("/a/.ssh"))
    }

    @Test
    fun `以点结尾的文件名没有扩展名`() {
        // `report.` 在 Linux 上是合法文件名，它的扩展名是空而不是 "report"
        assertEquals("", PathNormalizer.extensionOf("/a/report."))
    }

    @Test
    fun `segments 切分`() {
        assertEquals(listOf("a", "b"), PathNormalizer.segments("/a/b"))
        assertEquals(emptyList<String>(), PathNormalizer.segments("/"))
    }
}
