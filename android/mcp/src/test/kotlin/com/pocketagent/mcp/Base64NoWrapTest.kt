package com.pocketagent.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Base64 编码**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 手写编码器错了的表现是"图片打不开"，且**不报错**
 * ═══════════════════════════════════════════════════════════════
 *
 * `:mcp` 是纯 Kotlin 模块（零 Android 依赖），所以不能用
 * `android.util.Base64`，也不能用 API 26+ 的 `java.util.Base64`。
 *
 * ⚠️ 手写实现的风险是"跑得快但算错"：
 *    · 填充（`=`）位置错 ⇒ 客户端解码出半截字节
 *    · 高低位顺序反了 ⇒ 整张图是雪花
 *    · 输出带换行 ⇒ 某些 JSON 解析器直接把 data 字段截断
 *
 * 这三种都不会抛异常 —— 只是图片看不见。
 *
 * ★ 下面用**已知答案**（RFC 4648 的测试向量 + 长度完整性）双重验证。
 */
class Base64NoWrapTest {

    // ═══════════════════════════════════════════════════════════
    //  RFC 4648 的官方测试向量
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `RFC4648 测试向量`() {
        // ★ 这组是 Base64 规范里的标准向量。逐条验，而不是只测一两个 ——
        //   填充边界（1/2/3 字节余数）各有不同的错法。
        val cases = listOf(
            "" to "",
            "f" to "Zg==",
            "fo" to "Zm8=",
            "foo" to "Zm9v",
            "foob" to "Zm9vYg==",
            "fooba" to "Zm9vYmE=",
            "foobar" to "Zm9vYmFy",
        )
        for ((plain, expected) in cases) {
            assertEquals(
                "「$plain」应当编码成「$expected」",
                expected,
                Base64NoWrap.encode(plain.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    @Test
    fun `空数组编码成空串`() {
        assertEquals("", Base64NoWrap.encode(ByteArray(0)))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 三个填充边界（最容易写错的地方）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `余零字节不打填充`() {
        val out = Base64NoWrap.encode(ByteArray(3) { 0 })
        assertEquals("AAAA", out)
        assertFalse("不该有填充", out.contains('='))
    }

    @Test
    fun `余一字节打两个等号`() {
        val out = Base64NoWrap.encode(ByteArray(1) { 0 })
        assertEquals("AA==", out)
        assertTrue("必须有两个填充", out.endsWith("=="))
    }

    @Test
    fun `余二字节打一个等号`() {
        val out = Base64NoWrap.encode(ByteArray(2) { 0 })
        assertEquals("AAA=", out)
        assertTrue("必须有一个填充", out.endsWith("=") && !out.endsWith("=="))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 输出必须无换行（MCP image data 要求单行）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `大输入也不能带换行`() {
        // ⚠️ 很多 Base64 实现默认 76 字符换行（MIME 风格）。
        //    那会让某些 JSON 解析器把 data 字段截断 ⇒ 图片损坏。
        val big = ByteArray(5000) { (it % 256).toByte() }
        val out = Base64NoWrap.encode(big)
        assertFalse("不能有 \\n", out.contains('\n'))
        assertFalse("不能有 \\r", out.contains('\r'))
    }

    @Test
    fun `长度符合 base64 规则`() {
        // ★ 4 的倍数，且等于 ceil(n/3)*4
        for (n in listOf(0, 1, 2, 3, 4, 5, 6, 7, 100, 1000)) {
            val out = Base64NoWrap.encode(ByteArray(n))
            val expected = (n + 2) / 3 * 4
            assertEquals("n=$n 时长度应为 $expected（实际 ${out.length}）", expected, out.length)
            assertEquals("n=$n 时长度必须是 4 的倍数", 0, out.length % 4)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  字节序（高低位）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `字节序正确`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条防的是"高低位反了"
        // ══════════════════════════════════════════════════════
        // 位序错了编码出来的字符串长度和字符集都对，只是内容全错 ——
        // 解码出来是一张雪花图，而**没有任何报错**。
        //
        // 0x00 0x00 0x01 → "AAAB"
        // 若位序反了会变成 "AAAE"（因为 1 被放到了错误的 6 位组）
        assertEquals(
            "AAAB",
            Base64NoWrap.encode(byteArrayOf(0x00, 0x00, 0x01)),
        )
        // 4 字节 → 8 个字符，末尾带 `==` 填充
        // （我第一版这里写成 "AAEAAA" 漏了填充 —— 编码器是对的，测试错了）
        assertEquals(
            "AAEAAA==",
            Base64NoWrap.encode(byteArrayOf(0x00, 0x01, 0x00, 0x00)),
        )
    }

    @Test
    fun `高位字节不丢`() {
        // ⚠️ Kotlin 的 Byte 是**有符号**的。不 `and 0xFF` 的话，
        //    0x80 以上的字节会被当成负数 ⇒ 编码结果错得很隐蔽。
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertEquals("////", Base64NoWrap.encode(bytes))
    }

    @Test
    fun `三个位置的高位字节都不丢`() {
        // ══════════════════════════════════════════════════════
        //  ★★ 这条是变异验证逼出来的补充
        // ══════════════════════════════════════════════════════
        // 我最初只测了「三个字节全是 0xFF」—— 那种情况下，
        // 去掉 `and 0xFF` 的变异**依然能通过**（负数的左移和或运算
        // 在只剩低 24 位时结果相同）。
        //
        // ⇒ 必须**逐个位置**测：一个高位字节落在第 0 / 1 / 2 位时，
        //   不掩码会让那个字节的符号位污染相邻的 6 位组。
        //
        // 已知答案（用 Python 的 base64 独立算过）：
        //   [0x80]        → "gA=="
        //   [0x00,0x80]   → "AIA="
        //   [0x00,0x00,0x80] → "AACA"
        assertEquals("gA==", Base64NoWrap.encode(byteArrayOf(0x80.toByte())))
        assertEquals("AIA=", Base64NoWrap.encode(byteArrayOf(0x00, 0x80.toByte())))
        assertEquals(
            "AACA",
            Base64NoWrap.encode(byteArrayOf(0x00, 0x00, 0x80.toByte())),
        )
    }

    @Test
    fun `所有位模式都不崩`() {
        // 0x00..0xFF 全覆盖，确认不抛异常且长度正确。
        val all = ByteArray(256) { it.toByte() }
        val out = Base64NoWrap.encode(all)
        assertTrue("必须产出内容", out.isNotEmpty())
        assertEquals("长度应为 ceil(256/3)*4", 344, out.length)
    }

    // ═══════════════════════════════════════════════════════════
    //  字符集
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `只用标准字符集`() {
        val valid = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('+', '/', '=')
        val out = Base64NoWrap.encode(ByteArray(1000) { (it * 7).toByte() })
        for (ch in out) {
            assertTrue("「$ch」不在标准 Base64 字符集里", ch in valid)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  PNG 头（真实场景）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `PNG 魔数编码正确`() {
        // ★ PNG 文件头是 89 50 4E 47 0D 0A 1A 0A —— 用真实数据验一次，
        //   比抽象的字节数组更能说明"这个编码器能处理真图"。
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val out = Base64NoWrap.encode(pngHeader)
        // 已知答案（0x89504E47 0D0A1A0A 的 base64）
        assertEquals("iVBORw0KGgo=", out)
    }
}
