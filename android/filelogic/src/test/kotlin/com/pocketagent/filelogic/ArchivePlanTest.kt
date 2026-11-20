package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 压缩包纯逻辑。
 *
 * 钉住四条：①穿越/绝对路径/盘符一律进不来；②同输入同字节；
 * ③解压按**实际字节**判上限（不信压缩包自称的大小）；④越界条目要能被看见。
 */
class ArchivePlanTest {

    // ── 入口名 ────────────────────────────────────────────────

    @Test
    fun `正常相对路径放行并规范化`() {
        assertEquals("a/b.txt", ZipSafety.normalize("a/b.txt"))
        assertEquals("a/b.txt", ZipSafety.normalize("./a//b.txt"))
    }

    @Test
    fun `穿越与绝对路径全部拒`() {
        for (bad in listOf(
            "../etc/passwd",
            "a/../../b",
            "/etc/passwd",
            "C:/windows/system32",
            "a\\b.txt",
            "",
            ".",
            "a".repeat(ZipSafety.MAX_NAME_LENGTH + 1),
        )) {
            assertNull("「$bad」不该过", ZipSafety.normalize(bad))
        }
    }

    @Test
    fun `控制字符进不来`() {
        assertNull(ZipSafety.normalize("a\u0000b.txt"))
        assertNull(ZipSafety.normalize("a\u007fb.txt"))
    }

    @Test
    fun `isInside 用段边界而不是字符串前缀`() {
        assertTrue(ZipSafety.isInside("工作区", "工作区/a.txt"))
        assertTrue(ZipSafety.isInside("工作区", "工作区"))
        // 前缀相同但不是子目录，这种最容易写错
        assertFalse(ZipSafety.isInside("工作区", "工作区备份/a.txt"))
        assertFalse(ZipSafety.isInside("工作区", "工作区/x/../../evil.txt"))
    }

    // ── 打包 ──────────────────────────────────────────────────

    @Test
    fun `打包出来的字节可复现`() {
        val entries = listOf(
            "readme.txt" to "第一行".toByteArray(),
            "src/main.kt" to "fun main() {}".toByteArray(),
        )
        val a = ZipWriter.create(entries)
        val b = ZipWriter.create(entries)
        assertTrue("同样输入必须同样字节", a.contentEquals(b))
        assertTrue(a.size > 40)
    }

    @Test
    fun `名字不合规时抛异常而不是静默改名`() {
        try {
            ZipWriter.create(listOf("../evil.txt" to "x".toByteArray()))
            fail("应该抛异常")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不合规"))
        }
    }

    // ── 列表 / 解包 ───────────────────────────────────────────

    @Test
    fun `列表能看出名字与目录`() {
        val zip = ZipWriter.create(listOf("a.txt" to "1".toByteArray(), "b/c.txt" to "22".toByteArray()))
        val entries = ZipReader.list(zip)
        assertEquals(listOf("a.txt", "b/c.txt"), entries.map { it.name })
        assertTrue(entries.none { it.isDirectory })
    }

    @Test
    fun `解包拿回原内容`() {
        val zip = ZipWriter.create(listOf("a.txt" to "你好".toByteArray()))
        val result = ZipReader.extract(zip)
        assertEquals("你好", result.files["a.txt"]!!.toString(Charsets.UTF_8))
        assertEquals(6L, result.totalBytes)
        assertTrue(result.skipped.isEmpty())
        assertTrue(result.summary().contains("解出 1 个文件"))
    }

    @Test
    fun `条数上限按实际解出的算`() {
        val zip = ZipWriter.create((1..5).map { "f$it.txt" to "x".toByteArray() })
        val result = ZipReader.extract(zip, ArchiveLimits(maxFiles = 2))
        assertEquals(2, result.files.size)
        // 后面三个是"看到了但没解"，必须能说出来
        assertEquals(3, result.skipped.size)
        assertTrue(result.skipped.first().contains("超过 2 个"))
    }

    @Test
    fun `单文件上限按实际字节算而不是自称大小`() {
        val zip = ZipWriter.create(listOf("big.bin" to ByteArray(4_000)))
        val result = ZipReader.extract(zip, ArchiveLimits(maxFileBytes = 1_000))
        assertTrue(result.files.isEmpty())
        assertTrue(result.skipped.single().contains("单个超过"))
    }

    @Test
    fun `总量上限会拦住后面的条目`() {
        val zip = ZipWriter.create((1..4).map { "f$it.bin" to ByteArray(600) })
        val result = ZipReader.extract(zip, ArchiveLimits(maxTotalBytes = 1_500))
        assertEquals(2, result.files.size)
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun `空包说人话`() {
        val zip = ZipWriter.create(emptyList())
        val result = ZipReader.extract(zip)
        assertTrue(result.files.isEmpty())
        assertEquals("这个压缩包是空的。", result.summary())
    }

    @Test
    fun `上限说明只在真触发时出现`() {
        assertEquals("", ArchiveLimits().explain(0, 1_000))
        assertTrue(ArchiveLimits().explain(2, 1_000).contains("没解"))
    }
}
