package com.pocketagent.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **密钥相关的不变量**。
 *
 * ═══════════════════════════════════════════════════════════════
 *  ★★ 2026-11-01：`:provider:api` 494 行、**零测试**
 * ═══════════════════════════════════════════════════════════════
 *
 * 它是所有 provider 的契约层。其中 `ProviderCredential` 有三条
 * **安全行为**从来没有被任何测试盯过：
 *
 * 1. `toString()` 不能带出密钥
 * 2. `clear()` 必须真的清零
 * 3. `keyLength` 只能给长度，不给内容
 *
 * ⚠️ 这类行为**回归时不会报错** —— 只会安安静静地泄漏。
 *    比如有人为了排查问题把 `toString` 改成
 *    `"ProviderCredential($apiKey)"`，编译通过、测试全绿、
 *    日志里从此带上明文 Key。
 *
 * ⇒ 这几条必须被钉住。
 */
class ProviderCredentialTest {

    private val key = "sk-secret-abcdefghijklmnop".toByteArray()

    @Test
    fun `toString 绝不含密钥`() {
        // ══════════════════════════════════════════════════════
        //  ★ 本文件最重要的一条
        // ══════════════════════════════════════════════════════
        val c = ProviderCredential(apiKey = key.copyOf())
        val s = c.toString()

        assertFalse(
            "toString 里出现了密钥明文 —— 日志/崩溃报告会把它带出去：$s",
            s.contains("sk-secret"),
        )
        assertTrue("脱敏后应当看得出是凭据对象：$s", s.contains("ProviderCredential"))
        assertTrue("应当有脱敏标记：$s", s.contains("***"))
    }

    @Test
    fun `toString 会带出 baseUrl 但不带密钥`() {
        // baseUrl 不是秘密，带出来有助于排查"连到了哪"
        val c = ProviderCredential(
            apiKey = key.copyOf(),
            baseUrlOverride = "https://example.com/v1",
        )
        val s = c.toString()
        assertTrue(s.contains("https://example.com/v1"))
        assertFalse(s.contains("sk-secret"))
    }

    @Test
    fun `clear 之后密钥字节全为 0`() {
        val bytes = key.copyOf()
        val c = ProviderCredential(apiKey = bytes)
        assertEquals("清空前应当有内容", key.size, bytes.size)

        c.clear()

        assertTrue(
            "clear() 必须真的把数组清零（不是换一个新数组）",
            bytes.all { it == 0.toByte() },
        )
    }

    @Test
    fun `clear 之后取不出原密钥`() {
        val bytes = key.copyOf()
        val c = ProviderCredential(apiKey = bytes)
        c.clear()
        assertNotEquals(
            "清零后不该还能取回原文",
            "sk-secret-abcdefghijklmnop",
            c.apiKeyForRequest(),
        )
    }

    @Test
    fun `apiKeyForRequest 返回原明文`() {
        val c = ProviderCredential(apiKey = key.copyOf())
        assertEquals("sk-secret-abcdefghijklmnop", c.apiKeyForRequest())
    }

    @Test
    fun `keyLength 只给长度`() {
        val c = ProviderCredential(apiKey = key.copyOf())
        assertEquals(key.size, c.keyLength)
        // ⚠️ 长度不是秘密 —— 可以用来提示"你是不是少贴了一段"
        assertTrue(c.keyLength > 0)
    }

    @Test
    fun `默认字段是安全的空值`() {
        // ⚠️ sessionId 默认 null 意味着"不发会话头" ——
        //    这比默认带一个空字符串安全：空串会被某些厂商当成有效值。
        val c = ProviderCredential(apiKey = key.copyOf())
        assertEquals(null, c.baseUrlOverride)
        assertEquals(null, c.sessionId)
        assertTrue(c.extraHeaders.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════
    //  ContentPart.Image 的字节比较
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `图片按字节内容相等 而不是引用`() {
        // ⚠️ `ByteArray` 的 `==` 是引用比较 —— 本项目已踩过两次
        val a = ContentPart.Image(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png")
        val b = ContentPart.Image(bytes = byteArrayOf(1, 2, 3), mimeType = "image/png")
        assertFalse("前置：不是同一个数组", a.bytes === b.bytes)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `图片 mimeType 不同就不相等`() {
        assertNotEquals(
            ContentPart.Image(bytes = byteArrayOf(1), mimeType = "image/png"),
            ContentPart.Image(bytes = byteArrayOf(1), mimeType = "image/webp"),
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  用量与费用
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `TokenUsage 的 total 是输入加输出`() {
        assertEquals(150, TokenUsage(inputTokens = 100, outputTokens = 50).total)
    }

    @Test
    fun `缓存 token 默认不计入 total`() {
        // ⚠️ `cachedInputTokens` 是**输入的子集**（命中缓存的那部分），
        //    不是额外增量。把它再加一次会让总量虚高。
        val u = TokenUsage(inputTokens = 100, outputTokens = 50, cachedInputTokens = 80)
        assertEquals("缓存不应被重复计入", 150, u.total)
    }

    @Test
    fun `Cost 格式化为六位小数`() {
        // ⚠️ `%.6f` 是**四舍五入**，不是截断：0.0123456 → 0.012346。
        //    我一开始期望 0.012345（截断），测试红了才对 —— 断言写错，不是实现错。
        assertEquals("$0.012346", Cost(0.0123456).toString())
    }

    @Test
    fun `零费用也打印得出来`() {
        assertEquals("$0.000000", Cost(0.0).toString())
    }

    // ═══════════════════════════════════════════════════════════
    //  消息构造的便捷方法
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `system user assistant 三个便捷构造给出正确的角色`() {
        assertEquals(ChatMessage.Role.SYSTEM, ChatMessage.system("a").role)
        assertEquals(ChatMessage.Role.USER, ChatMessage.user("b").role)
        assertEquals(ChatMessage.Role.ASSISTANT, ChatMessage.assistant("c").role)
    }

    @Test
    fun `便捷构造只包含一个文本片段`() {
        val m = ChatMessage.user("你好")
        assertEquals(1, m.content.size)
        assertEquals("你好", (m.content.first() as ContentPart.Text).text)
    }
}
