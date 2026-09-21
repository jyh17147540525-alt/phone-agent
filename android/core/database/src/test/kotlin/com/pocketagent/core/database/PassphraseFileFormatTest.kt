package com.pocketagent.core.database

import com.pocketagent.core.crypto.EncryptedBlob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [PassphraseFileFormat] 单元测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  为什么这个格式值得单独测
 * ═══════════════════════════════════════════════════════════════
 *
 * 这个文件是**数据库唯一的钥匙**。它坏掉的后果不是"报个错"，
 * 而是用户所有已保存的 API Key 变成一堆永远解不开的字节。
 *
 * 而且它的失败模式很隐蔽：`decode` 少校验一个长度，就会在
 * **某些**输入上返回一个"看起来正常但内容错位"的 Blob，
 * 拿去解密只会得到一个笼统的 AEAD 失败 —— 那时根本看不出
 * 是文件格式解析错了还是密钥不对。
 *
 * 所以这里对**每一种截断/篡改位置**都要有一条用例。
 */
class PassphraseFileFormatTest {

    private val iv = ByteArray(12) { it.toByte() }
    private val ciphertext = ByteArray(48) { (it * 7).toByte() }
    private val blob = EncryptedBlob(ciphertext = ciphertext, iv = iv)

    private fun assertRejected(bytes: ByteArray, label: String) {
        try {
            PassphraseFileFormat.decode(bytes)
            fail("[$label] 本该被拒绝，却解析成功了")
        } catch (e: PassphraseUnavailableException) {
            // 期望路径：必须抛这个专用异常，而不是 IllegalArgumentException
            // 或 ArrayIndexOutOfBounds —— 上层要靠类型区分"数据没了"和"代码 bug"
            assertTrue("[$label] 异常信息不该为空", e.message?.isNotBlank() == true)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  一、正常往返
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `编码后再解码，IV 与密文都原样还原`() {
        val restored = PassphraseFileFormat.decode(PassphraseFileFormat.encode(blob))

        assertArrayEquals("IV 应当一致", iv, restored.iv)
        assertArrayEquals("密文应当一致", ciphertext, restored.ciphertext)
    }

    @Test
    fun `编码结果以 PAKS 魔数开头，版本号为 1`() {
        val encoded = PassphraseFileFormat.encode(blob)

        assertEquals('P'.code.toByte(), encoded[0])
        assertEquals('A'.code.toByte(), encoded[1])
        assertEquals('K'.code.toByte(), encoded[2])
        assertEquals('S'.code.toByte(), encoded[3])
        assertEquals("版本号", 1.toByte(), encoded[4])
        assertEquals("IV 长度", 12.toByte(), encoded[5])
    }

    @Test
    fun `长度等于文件头的输入应当被拒绝`() {
        // 6 字节正好是头长度，后面没有 IV 也没有密文
        assertRejected(ByteArray(6), "只有文件头")
        assertRejected(ByteArray(0), "空文件")
        assertRejected(ByteArray(3), "比文件头还短")
    }

    // ═══════════════════════════════════════════════════════════
    //  二、篡改与损坏
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `魔数被改掉的文件应当被拒绝`() {
        val encoded = PassphraseFileFormat.encode(blob)
        encoded[0] = 'X'.code.toByte()

        assertRejected(encoded, "魔数不匹配")
    }

    @Test
    fun `版本号不认识时应当被拒绝，且信息里带出实际版本号`() {
        val encoded = PassphraseFileFormat.encode(blob)
        encoded[4] = 99

        try {
            PassphraseFileFormat.decode(encoded)
            fail("未知版本本该被拒绝")
        } catch (e: PassphraseUnavailableException) {
            // 版本不匹配是**可恢复**的（新旧版本冲突），
            // 与"文件损坏"（数据没了）是两回事，所以文案必须能区分。
            // 带上实际版本号，用户/开发者才知道该往哪个方向查。
            assertTrue("应当报出实际版本号 99：${e.message}", e.message!!.contains("99"))
        }
    }

    @Test
    fun `IV 长度为零的文件应当被拒绝`() {
        val encoded = PassphraseFileFormat.encode(blob)
        encoded[5] = 0

        assertRejected(encoded, "IV 长度为零")
    }

    @Test
    fun `IV 长度声称超出实际长度时应当被拒绝`() {
        val encoded = PassphraseFileFormat.encode(blob)
        // 声称 IV 有 200 字节，而整个文件都没那么长 ——
        // 不做这个校验的话，copyOfRange 会抛 ArrayIndexOutOfBounds，
        // 上层就只能看到一个越界异常，分不清是文件坏了还是代码错了
        encoded[5] = 200.toByte()

        assertRejected(encoded, "IV 长度超出文件实际长度")
    }

    @Test
    fun `恰好只剩 IV 而没有密文的文件应当被拒绝`() {
        // 头(6) + IV(12) = 18 字节，密文长度为 0。
        // GCM 密文至少含 16 字节认证标签，所以这一定是个坏文件
        val encoded = PassphraseFileFormat.encode(blob).copyOf(18)

        assertRejected(encoded, "没有密文")
    }

    @Test
    fun `密文被截断的文件应当被拒绝`() {
        val encoded = PassphraseFileFormat.encode(blob).copyOf(6 + 12 + 4)

        assertRejected(encoded, "密文只剩 4 字节")
    }

    @Test
    fun `密文短于 GCM 认证标签时被拒绝，理由是文件损坏而不是密钥失效`() {
        // ⚠️ 这条用例守的是**报错方向**。
        //
        //    密文短于 16 字节（GCM 标签长度）时解密必然失败，而那个失败
        //    会被 DatabaseKeyProvider 解读成"主密钥失效，数据没了"，
        //    进而建议用户去查指纹、恢复出厂设置 —— 排查方向完全错了。
        //
        //    格式层在这里拦住，才能给出"文件被截断"这个准确原因。
        val fifteenBytes = PassphraseFileFormat.encode(
            EncryptedBlob(ciphertext = ByteArray(15), iv = iv)
        )
        assertRejected(fifteenBytes, "密文 15 字节，差一个字节")

        // 边界另一侧：正好 16 字节应当通过（虽然内容是假的，但格式合法）
        val sixteenBytes = PassphraseFileFormat.encode(
            EncryptedBlob(ciphertext = ByteArray(16), iv = iv)
        )
        assertEquals(
            "正好 16 字节应当被接受",
            16,
            PassphraseFileFormat.decode(sixteenBytes).ciphertext.size,
        )
    }

    @Test
    fun `密文被追加垃圾字节时不应报错，但内容会不一致`() {
        // 这是刻意的行为记录：本格式**不做**完整性校验 ——
        // 完整性由 GCM 自己保证（多出来的字节会让认证标签校验失败）。
        // 在格式层再做一次校验只会重复劳动，还多一个可能出错的环节。
        val encoded = PassphraseFileFormat.encode(blob) + byteArrayOf(1, 2, 3)

        val restored = PassphraseFileFormat.decode(encoded)
        assertEquals("IV 不受影响", 12, restored.iv.size)
        assertEquals("多出来的字节会被当成密文的一部分", ciphertext.size + 3, restored.ciphertext.size)
    }

    // ═══════════════════════════════════════════════════════════
    //  三、编码侧的边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `IV 超过 255 字节时编码应当直接拒绝`() {
        // 密文给足 16 字节，让这条用例只考察 IV 长度这一件事 ——
        // 否则它可能因为密文太短而"碰巧"通过，掩盖真正的边界
        val tooLong = EncryptedBlob(ciphertext = ByteArray(16), iv = ByteArray(256))

        try {
            PassphraseFileFormat.encode(tooLong)
            fail("IV 长度超出单字节承载能力，本该拒绝")
        } catch (e: IllegalArgumentException) {
            // 这里用 IllegalArgumentException 而不是 PassphraseUnavailableException：
            // 它是**编码侧的编程错误**（不该出现的输入），不是"用户的文件坏了"。
            // 静默截断会写出一个自己都解不开的文件 —— 那才是真正的灾难。
            assertTrue(e.message?.isNotBlank() == true)
        }
    }

    @Test
    fun `恰好 255 字节的 IV 应当可以编码`() {
        val maxIv = EncryptedBlob(ciphertext = ByteArray(16), iv = ByteArray(255))

        val restored = PassphraseFileFormat.decode(PassphraseFileFormat.encode(maxIv))

        assertEquals(255, restored.iv.size)
    }
}
