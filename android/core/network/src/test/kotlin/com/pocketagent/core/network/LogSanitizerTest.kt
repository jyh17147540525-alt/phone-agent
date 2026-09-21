package com.pocketagent.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogSanitizer] 单元测试。
 *
 * 这是安全红线组件，测试要同时守住两个方向：
 *
 *  1. **不能漏**（under-redaction）—— 任何形式的密钥/PII 进了日志就是事故。
 *     这一类用例是主要矛盾，覆盖各厂商真实 Key 格式。
 *  2. **不能滥**（over-redaction）—— 把所有东西都涂黑虽然"安全"，
 *     但日志就废了，出问题时无从排查。所以也要钉住"正常日志不该被改"。
 *
 * 新增厂商适配时，**先在这里加一条真实 Key 格式的用例**。
 */
class LogSanitizerTest {

    // ═══════════════════════════════════════════════════════════
    //  一、API Key 必须被脱敏
    // ═══════════════════════════════════════════════════════════

    private fun assertRedacted(raw: String, secret: String, label: String) {
        val out = LogSanitizer.sanitize(raw)
        assertFalse("[$label] 明文泄漏了：$out", out.contains(secret))
        assertTrue("[$label] 应留下掩码标记：$out", out.contains("****"))
    }

    @Test
    fun `OpenAI 风格的 sk- 密钥`() {
        assertRedacted(
            "Authorization: Bearer sk-proj-abcdefghijklmnopqrstuvwxyz0123456789",
            "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789",
            "openai",
        )
    }

    @Test
    fun `Anthropic 风格的 sk-ant- 密钥`() {
        assertRedacted(
            "key=sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
            "anthropic",
        )
    }

    @Test
    fun `Google 风格的 AIza 密钥`() {
        assertRedacted(
            "url?key=AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r",
            "AIzaSyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r",
            "google",
        )
    }

    @Test
    fun `火山方舟风格的 UUID 密钥`() {
        assertRedacted(
            "token 550e8400-e29b-41d4-a716-446655440000 已加载",
            "550e8400-e29b-41d4-a716-446655440000",
            "volc-uuid",
        )
    }

    @Test
    fun `大写 UUID 密钥也不能漏`() {
        // 回归用例：UUID 模式原先大小写敏感，全大写形式会直接放行
        assertRedacted(
            "token 550E8400-E29B-41D4-A716-446655440000 已加载",
            "550E8400-E29B-41D4-A716-446655440000",
            "volc-uuid-upper",
        )
    }

    @Test
    fun `小写长 hex 密钥`() {
        assertRedacted(
            "secret=0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef",
            "hex-lower",
        )
    }

    @Test
    fun `大写长 hex 密钥`() {
        // 回归用例：hex 模式原先大小写敏感，全大写密钥会被原样写进日志。
        // 这是本次修掉的一个真实漏洞。
        assertRedacted(
            "secret=0123456789ABCDEF0123456789ABCDEF",
            "0123456789ABCDEF0123456789ABCDEF",
            "hex-upper",
        )
    }

    @Test
    fun `裸 Bearer token（JWT 形态）`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0"
        assertRedacted("Authorization: Bearer $jwt", jwt, "jwt")
    }

    @Test
    fun `x-api-key 头里的密钥`() {
        assertRedacted(
            """x-api-key: abcdefghijklmnopqrstuvwxyz123456""",
            "abcdefghijklmnopqrstuvwxyz123456",
            "x-api-key",
        )
    }

    // ═══════════════════════════════════════════════════════════
    //  二、图片数据必须被干掉
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `data URI 形态的截图被整体替换`() {
        val payload = "A".repeat(200)
        val out = LogSanitizer.sanitize("body: {\"url\":\"data:image/webp;base64,$payload\"}")

        assertFalse("base64 内容泄漏", out.contains(payload))
        assertTrue(out.contains("[IMAGE_DATA_REDACTED]"))
    }

    @Test
    fun `裸 base64 长串被替换`() {
        val payload = "QWxleGFuZGVy".repeat(30)   // 360 字符
        val out = LogSanitizer.sanitize("raw=$payload")

        assertFalse("base64 内容泄漏", out.contains(payload))
        assertTrue(out.contains("[LONG_BASE64_REDACTED]"))
    }

    // ═══════════════════════════════════════════════════════════
    //  三、PII 必须被脱敏
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `手机号`() {
        val out = LogSanitizer.sanitize("用户手机号 13812345678 已登录")

        assertFalse(out.contains("13812345678"))
        assertTrue(out.contains("[手机号:11位]"))
    }

    @Test
    fun `身份证号`() {
        val out = LogSanitizer.sanitize("证件 11010119900307123X")

        assertFalse(out.contains("11010119900307123X"))
        assertTrue(out.contains("[身份证:18位]"))
    }

    @Test
    fun `不带分隔符的银行卡号`() {
        val out = LogSanitizer.sanitize("卡号 6222021234567890123")

        assertFalse(out.contains("6222021234567890123"))
        assertTrue("应命中银行卡模式：$out", out.contains("[银行卡"))
    }

    @Test
    fun `带空格的银行卡号`() {
        val out = LogSanitizer.sanitize("卡号 6222 0212 3456 7890")

        assertFalse(out.contains("6222 0212 3456 7890"))
        assertTrue("应命中银行卡模式：$out", out.contains("[银行卡"))
    }

    @Test
    fun `邮箱地址`() {
        val out = LogSanitizer.sanitize("发往 zhangsan@example.com")

        assertFalse(out.contains("zhangsan@example.com"))
        assertTrue(out.contains("[邮箱:20位]"))
    }

    @Test
    fun `身份证优先于银行卡匹配，不会把 18 位号码标成银行卡`() {
        // 两个模式的数字位数有重叠，顺序错了会打出错误的标签
        val out = LogSanitizer.sanitize("110101199003071234")

        assertTrue("标签应为身份证：$out", out.contains("[身份证"))
        assertFalse(out.contains("[银行卡"))
    }

    // ═══════════════════════════════════════════════════════════
    //  四、截断
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `超长日志被截断并标注丢弃长度`() {
        val out = LogSanitizer.sanitize("日志 ".repeat(1000))   // 3000 字符

        assertTrue(out.length < 3000)
        assertTrue(out.contains("[TRUNCATED"))
    }

    @Test
    fun `短日志不被截断`() {
        val out = LogSanitizer.sanitize("这是一条普通日志")

        assertEquals("这是一条普通日志", out)
    }

    // ═══════════════════════════════════════════════════════════
    //  五、边界输入
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `null 与空串返回空串而不是抛异常`() {
        // 日志调用点遍布各处，这里崩了会把业务逻辑一起带崩
        assertEquals("", LogSanitizer.sanitize(null))
        assertEquals("", LogSanitizer.sanitize(""))
    }

    @Test
    fun `掩码保留前 4 位与总长度，便于定位是哪个 Key 出错`() {
        val out = LogSanitizer.sanitize("sk-abcdefghijklmnopqrstuvwxyz")

        // "sk-" 3 位 + 26 个小写字母 = 29
        assertTrue("应保留前 4 位：$out", out.startsWith("sk-a"))
        assertTrue("应标注总长度：$out", out.contains("(29位)"))
    }

    // ═══════════════════════════════════════════════════════════
    //  六、不能滥 —— 正常日志必须保持可读
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `普通中文日志原样保留`() {
        val line = "第 3 步：点击「发送」按钮，耗时 128ms"
        assertEquals(line, LogSanitizer.sanitize(line))
    }

    @Test
    fun `普通 URL 与状态码原样保留`() {
        val line = "GET https://api.openai.com/v1/models -> 200 OK in 128ms"
        assertEquals(line, LogSanitizer.sanitize(line))
    }

    @Test
    fun `短数字不会被误伤`() {
        val line = "重试 3 次，间隔 2000ms，已用 15 秒"
        assertEquals(line, LogSanitizer.sanitize(line))
    }

    /**
     * 已知的过度脱敏（记录行为，不是缺陷）。
     *
     * UUID 形态的请求 ID 会被当成火山方舟的 Key 涂黑。无法两全：
     * 两者字节形态完全一致，只能靠上下文区分，而脱敏器看不到上下文。
     *
     * 取舍是明确的 —— **宁可错杀请求 ID，不可放过密钥**。
     * 若将来确实需要排查请求 ID，正确做法是让日志调用方显式标注
     * 「这段是安全的」，而不是放松正则。
     */
    @Test
    fun `UUID 形态的请求 ID 会被一并脱敏（已知取舍）`() {
        val out = LogSanitizer.sanitize("request_id=550e8400-e29b-41d4-a716-446655440000")

        assertFalse(out.contains("550e8400-e29b-41d4-a716-446655440000"))
    }

    // ═══════════════════════════════════════════════════════════
    //  七、请求头脱敏 —— OkHttp 日志拦截器的主要入口
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Authorization 头被掩码`() {
        val out = LogSanitizer.sanitizeHeaders(
            mapOf(
                "Authorization" to listOf("Bearer sk-abcdefghijklmnopqrstuvwxyz"),
                "Content-Type" to listOf("application/json"),
            )
        )

        assertFalse("Authorization 泄漏：$out", out.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(out.contains("Authorization: "))
        assertTrue("普通头应保持可读：$out", out.contains("Content-Type: application/json"))
    }

    @Test
    fun `各类敏感头都不区分大小写地掩码`() {
        val out = LogSanitizer.sanitizeHeaders(
            mapOf(
                "AUTHORIZATION" to listOf("secret-value-abcdefghijklmnop"),
                "X-Api-Key" to listOf("another-secret-abcdefghijklmnop"),
                "api-key" to listOf("azure-secret-abcdefghijklmnop"),
                "Cookie" to listOf("session=abcdefghijklmnopqrstuvwxyz"),
            )
        )

        for (secret in listOf(
            "secret-value-abcdefghijklmnop",
            "another-secret-abcdefghijklmnop",
            "azure-secret-abcdefghijklmnop",
            "session=abcdefghijklmnopqrstuvwxyz",
        )) {
            assertFalse("头值泄漏：$secret", out.contains(secret))
        }
    }

    @Test
    fun `空的请求头返回空串`() {
        assertEquals("", LogSanitizer.sanitizeHeaders(emptyMap()))
    }
}
