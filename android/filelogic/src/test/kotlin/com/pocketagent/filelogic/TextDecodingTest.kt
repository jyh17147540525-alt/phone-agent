package com.pocketagent.filelogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextDecoding] 的单测。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这里守的是一条**沉默的**规则
 * ═══════════════════════════════════════════════════════════════
 *
 * 把字节变成字符串有两种写法，它们**在所有正常文件上给出完全一样的结果**：
 *
 * ```kotlin
 * String(bytes, Charsets.UTF_8)       // 非法字节 → U+FFFD（�），不报错
 * decoder.onMalformedInput(REPORT)    // 非法字节 → 抛异常
 * ```
 *
 * 差别只在**读到一个非文本文件**时才出现，而那时第一种写法会交出一份
 * "能打开、看着像文本、实际已经损坏"的字符串 —— 而 agent 会基于它
 * 去判断、去改用户的其他文件。
 *
 * ⇒ 所以"第二种写法确实在报错"这件事**必须被测试钉住**：
 *   它没有任何别的观察点（正常路径上两种写法一模一样），
 *   而任何后人"顺手简化"成 `String(bytes, UTF_8)` 都不会被发现。
 */
class TextDecodingTest {

    private fun textOf(bytes: ByteArray): String {
        val result = TextDecoding.decodeUtf8(bytes)
        assertTrue("期望是文本，实际是 $result", result is FileReadResult.Text)
        return (result as FileReadResult.Text).content
    }

    private fun reasonOf(bytes: ByteArray): String {
        val result = TextDecoding.decodeUtf8(bytes)
        assertTrue("期望解不出来，实际是 $result", result is FileReadResult.NotUtf8)
        return (result as FileReadResult.NotUtf8).reason
    }

    // ── 正常路径 ──────────────────────────────────────────────────

    @Test
    fun `普通 UTF-8 文本原样读出来`() {
        assertEquals("你好，世界", textOf("你好，世界".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `空字节数组是空文本，不是错误`() {
        // ⚠️ 一个 0 字节的文件是**合法的空文件**，不是"读不出来"。
        //    把这两件事混起来的后果是：用户新建了一个空笔记，
        //    而 agent 说"这个文件打不开"。
        assertEquals("", textOf(ByteArray(0)))
    }

    @Test
    fun `换行与制表符原样保留`() {
        // ⚠️ 刻意不"顺手规整"空白：那会篡改内容。
        assertEquals("a\nb\tc\r\nd", textOf("a\nb\tc\r\nd".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `emoji 这类四字节字符不会被拆坏`() {
        // UTF-8 的代理对边界是"尽力解码"最容易出问题的地方。
        assertEquals("🎉🚀", textOf("🎉🚀".toByteArray(Charsets.UTF_8)))
    }

    // ── BOM ───────────────────────────────────────────────────────

    @Test
    fun `开头的 UTF-8 BOM 被剥掉`() {
        // ⚠️ BOM 是**编码标记**，不是内容。留在正文开头会让 agent 把
        //    `\uFEFF` 当成文件的一部分 —— 表现是"第一个字符很奇怪"，
        //    而那是我们自己造成的。Windows 上的记事本存 UTF-8 就会带 BOM。
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "标题".toByteArray(Charsets.UTF_8)

        val text = textOf(bytes)

        assertEquals("标题", text)
        assertFalse("正文里不该还有 BOM", text.startsWith('\uFEFF'))
    }

    @Test
    fun `中间出现的 BOM 字节不动它`() {
        // ⚠️ 只剥**开头**的。中间出现的 `EF BB BF` 是真实的字符数据，
        //    动了它就是篡改内容 —— 而"篡改一点点"正是本项目最不接受的。
        val content = "a\uFEFFb"
        assertEquals(content, textOf(content.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `只有 BOM 的文件读出来是空文本`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("", textOf(bytes))
    }

    @Test
    fun `两个字节的残缺 BOM 不当成 BOM`() {
        // ⚠️ `EF BB` 后面接普通文本时，那不是一个 BOM，而是两个真实字节 ——
        //    而它们本身是**非法 UTF-8 起始字节**，所以应当如实报错。
        //    把"前两字节像 BOM"也剥掉的后果是：把一个损坏的文件
        //    读成一个少了两字节的正常文件。
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte()) + "x".toByteArray(Charsets.UTF_8)
        reasonOf(bytes)
    }

    // ── ★ 非法输入：必须报错，不能尽力解码 ─────────────────────────

    @Test
    fun `孤立的续接字节被拒绝，而不是变成替换字符`() {
        // ★ 这一条是**这个类存在的全部理由**。
        //   0x80 是"续接字节"却没有前导字节 —— 非法 UTF-8。
        val bytes = byteArrayOf(0x41, 0x80.toByte(), 0x42)

        val reason = reasonOf(bytes)

        assertTrue("要说明它不是文本，实际是「$reason」", reason.contains("不是 UTF-8"))
    }

    @Test
    fun `截断的多字节序列被拒绝`() {
        // 0xE4 0xBD 是一个三字节序列的前两个字节，缺了最后一个。
        // ⚠️ 这正是"进程写到一半被杀"会留下的形状 —— 而它如果被
        //    "尽力解码"，就会变成一个看起来正常的短文件。
        reasonOf(byteArrayOf(0xE4.toByte(), 0xBD.toByte()))
    }

    @Test
    fun `PNG 文件头被拒绝`() {
        // 真实场景：用户让 agent "看一下这个文件"，而它其实是张图。
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        reasonOf(png)
    }

    @Test
    fun `GBK 编码的中文被拒绝，而不是读成乱码`() {
        // ⚠️ 这是最容易被"尽力解码"蒙混过去的一类：GBK 的汉字是两个
        //    高位字节，UTF-8 解码器会把它们判为非法 —— 而替换成 � 之后
        //    得到的字符串**看起来像文本**，只是"乱码"。
        //    那比直接报错更糟：agent 会以为自己读到了一个正常文件。
        val gbk = "中文".toByteArray(charset("GBK"))
        reasonOf(gbk)
    }

    @Test
    fun `报错信息里不含文件内容`() {
        // ⚠️ 这条消息会一路走到界面上。"读不出来"这件事**不该顺带
        //    把读不出来的东西展示一遍**。
        val reason = reasonOf(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))

        assertFalse("不能回显内容", reason.contains("A"))
        assertFalse(reason.contains("\uFFFD"))
    }
}
