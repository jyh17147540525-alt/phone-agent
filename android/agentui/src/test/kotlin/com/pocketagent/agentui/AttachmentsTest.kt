package com.pocketagent.agentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **附件分类**的测试。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★ 这个文件修的是"模型看不了图片"
 * ═══════════════════════════════════════════════════════════════
 *
 * 根因：用户选的图被拼成一段**文字路径**塞进 prompt。
 * 而 provider 层其实早就能发 `ContentPart.Image`。
 *
 * 下面这些断言守的是"什么能发"这个判断 —— 判错的代价是
 * 安静地做错事（模型收到乱码，或用户以为功能坏了）。
 */
class AttachmentsTest {

    // ═══════════════════════════════════════════════════════════
    //  分类
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `图片 MIME 识别为图片`() {
        for (m in listOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/heic")) {
            assertEquals("「$m」应当识别为图片", Attachments.Kind.IMAGE, Attachments.fromMime(m))
        }
    }

    @Test
    fun `音频识别为音频`() {
        for (m in listOf("audio/mpeg", "audio/wav", "audio/mp4", "audio/aac")) {
            assertEquals("「$m」应当识别为音频", Attachments.Kind.AUDIO, Attachments.fromMime(m))
        }
    }

    @Test
    fun `其它类型识别为文件`() {
        for (m in listOf("application/pdf", "text/plain", "video/mp4", "application/zip")) {
            assertEquals("「$m」应当识别为文件", Attachments.Kind.FILE, Attachments.fromMime(m))
        }
    }

    @Test
    fun `读不到 MIME 时保守处理为文件`() {
        // ⚠️ 保守方向：宁可"这张图没发出去"，也不要"把二进制当图片发出去"。
        //    后者会让模型收到乱码并据此编造内容 —— 比拒绝糟得多。
        assertEquals(Attachments.Kind.FILE, Attachments.fromMime(null))
        assertEquals(Attachments.Kind.FILE, Attachments.fromMime(""))
        assertEquals(Attachments.Kind.FILE, Attachments.fromMime("   "))
    }

    @Test
    fun `大小写不敏感`() {
        assertEquals(Attachments.Kind.IMAGE, Attachments.fromMime("IMAGE/PNG"))
        assertEquals(Attachments.Kind.IMAGE, Attachments.fromMime("Image/Jpeg"))
    }

    @Test
    fun `前后空白被忽略`() {
        assertEquals(Attachments.Kind.IMAGE, Attachments.fromMime("  image/png  "))
    }

    // ═══════════════════════════════════════════════════════════
    //  视觉模型可接受的格式
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `主流格式被接受`() {
        for (m in listOf("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif")) {
            assertTrue("「$m」应当被接受", Attachments.isSupportedImageMime(m))
        }
    }

    @Test
    fun `HEIC 不被接受`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条是一个真实的坑
        // ══════════════════════════════════════════════════════
        // HEIC 是 iPhone 默认格式，很多服务商**不支持**。
        // 直接发会被 400 拒绝，而错误信息通常只说
        // "unsupported image format" —— 用户看不出是格式问题，
        // 只会觉得"发图这个功能坏了"。
        //
        // ⇒ 判成不支持，让 UI 层先本地转码成 JPEG。
        assertFalse("HEIC 不该直接发", Attachments.isSupportedImageMime("image/heic"))
        assertFalse("HEIF 同上", Attachments.isSupportedImageMime("image/heif"))
    }

    @Test
    fun `非图片 MIME 一律不接受`() {
        for (m in listOf("application/pdf", "text/plain", "audio/mpeg", null, "")) {
            assertFalse("「$m」不是图片", Attachments.isSupportedImageMime(m))
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  大小约束
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `上限是四兆`() {
        assertEquals(4 * 1024 * 1024, Attachments.MAX_IMAGE_BYTES)
    }

    @Test
    fun `长边上限一千五百六十八`() {
        // ★ Anthropic 文档给的"视觉精度不损失"档位，也是多数模型的有效上限。
        //   超过之后多发像素不会让它看得更清，只会更慢更贵。
        assertEquals(1568, Attachments.MAX_EDGE_PX)
    }

    @Test
    fun `压缩质量不低于八十`() {
        // ⚠️ 70 以下文字边缘会糊 —— 而用户发图很多时候就是
        //    "帮我看看截图里写了什么"。
        assertTrue("质量不能太低：${Attachments.JPEG_QUALITY}", Attachments.JPEG_QUALITY >= 80)
        assertTrue("质量不该到 100（体积白白翻倍）", Attachments.JPEG_QUALITY <= 95)
    }

    // ═══════════════════════════════════════════════════════════
    //  拒绝原因
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `非图片附件给出原因`() {
        val why = Attachments.rejectionReason("application/pdf", 1000)
        assertNotNull("非图片必须给原因", why)
        // ⚠️ `assertNotNull` 不做 smart cast（它是普通函数调用），
        //    所以要显式取一次非空值再断言内容。
        val text = why!!
        assertTrue("原因要说明只能发路径：$text", "路径" in text || "不是图片" in text)
    }

    @Test
    fun `不支持的图片格式给出原因`() {
        val why = Attachments.rejectionReason("image/heic", 1000)
        assertNotNull(why)
        val text = why!!
        assertTrue("要提到格式问题：$text", "格式" in text || "JPEG" in text)
    }

    @Test
    fun `空文件给出原因`() {
        val why = Attachments.rejectionReason("image/png", 0)
        assertNotNull("空文件必须拒", why)
    }

    @Test
    fun `正常图片不拒绝`() {
        assertNull(Attachments.rejectionReason("image/png", 100_000))
        assertNull(Attachments.rejectionReason("image/jpeg", 2_000_000))
    }

    @Test
    fun `超过上限的图片不算拒绝而是走压缩`() {
        // ══════════════════════════════════════════════════════
        //  ★ 这条很重要：超限 ≠ 拒绝
        // ══════════════════════════════════════════════════════
        // 手机拍的一张照片 4~8MB 是**常态**。如果直接拒，
        // 用户会觉得"发图功能不好用"。
        // ⇒ 超限应当压缩，只有**压缩后仍超限**才拒。
        assertNull(
            "超限的图不该直接被拒（要走压缩）",
            Attachments.rejectionReason("image/jpeg", 8_000_000),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  PreparedImage
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `PreparedImage 拒绝空字节`() {
        val r = runCatching { Attachments.PreparedImage(ByteArray(0), "image/jpeg") }
        assertTrue("空字节必须被拦下", r.isFailure)
    }

    @Test
    fun `PreparedImage 拒绝非图片 MIME`() {
        val r = runCatching { Attachments.PreparedImage(ByteArray(10), "text/plain") }
        assertTrue("非图片 MIME 必须被拦下", r.isFailure)
    }

    @Test
    fun `PreparedImage 拒绝超限字节`() {
        val tooBig = ByteArray(Attachments.MAX_IMAGE_BYTES + 1)
        val r = runCatching { Attachments.PreparedImage(tooBig, "image/jpeg") }
        assertTrue("超限字节必须被拦下（压缩阶段就该拦）", r.isFailure)
    }

    @Test
    fun `PreparedImage 接受合法输入`() {
        val p = Attachments.PreparedImage(ByteArray(1000), "image/jpeg")
        assertEquals(1000, p.bytes.size)
        assertEquals("image/jpeg", p.mimeType)
    }

    @Test
    fun `base64 体积估算正确`() {
        // ★ base64 膨胀 33%：每 3 字节 → 4 个字符。
        //   这个估算让 UI 能在**编码之前**就知道请求体大概多大。
        val p = Attachments.PreparedImage(ByteArray(3000), "image/jpeg")
        assertEquals(4000, p.base64Estimate)

        val p2 = Attachments.PreparedImage(ByteArray(1), "image/jpeg")
        assertEquals("不足 3 字节也要向上取整", 4, p2.base64Estimate)
    }

    @Test
    fun `压缩后的大小应当落在上限内`() {
        // 这条是"压缩参数是否合理"的间接验证：
        // 4MB 上限 / 1.33 base64 膨胀 ≈ 3MB 原始 —— 远小于手机原图的 8MB，
        // 所以压缩是**必需**的，不是可选的优化。
        val afterBase64 = Attachments.MAX_IMAGE_BYTES / 3 * 4
        assertTrue(
            "上限图 base64 后约 ${afterBase64 / 1024 / 1024}MB，仍在常见厂商限制内",
            afterBase64 <= 6 * 1024 * 1024,
        )
    }
}
