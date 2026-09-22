package com.pocketagent.provider.gateway.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * [GatewayTokenProvider] 的离线单测。
 *
 * ⚠️ 用 `org.junit.Assert` 而不是 Truth —— Truth 不在离线 classpath
 *    （见 `run_logic_tests.py` 的 `LIBRARY_JARS`）。
 */
class GatewayTokenProviderTest {

    private val provider = GatewayTokenProvider()

    // ─────────────────────────────────────────────────────────────
    //  生成
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `token 长度是固定 64 个十六进制字符`() {
        // ⚠️ 这条看着像废话，但它钉住的是 toHex 的**前导零**处理。
        //    若换成 BigInteger(1, bytes).toString(16)，长度会在
        //    2 到 64 之间浮动 —— 而"偶尔短一截"会让"长度不等直接拒"
        //    那条路径莫名其妙地命中，表现为约 1/256 的请求 401。
        repeat(200) {
            assertEquals(64, provider.issue().length)
        }
    }

    @Test
    fun `token 只含小写十六进制字符`() {
        val token = provider.issue()
        assertTrue("实际内容：$token", token.all { it in "0123456789abcdef" })
    }

    @Test
    fun `每次 issue 都不同`() {
        // ⚠️ 这条防的是"误把随机源写成常量种子/固定值"。
        //    200 次全不同 → 撞上重复的概率是 200²/2^256，可以忽略。
        val tokens = (1..200).map { provider.issue() }.toSet()
        assertEquals(200, tokens.size)
    }

    @Test
    fun `空的 SecureRandom 序列也能产出合法长度的 token`() {
        // 用确定性随机源，确认 issue 不依赖随机源的具体行为
        val fixed = GatewayTokenProvider(FixedRandom(ByteArray(GatewayTokenProvider.TOKEN_BYTES) { 0 }))

        val token = fixed.issue()

        assertEquals(64, token.length)
        assertEquals("0".repeat(64), token)
    }

    @Test
    fun `前导零字节不会被吞掉`() {
        // ★ 这条是 toHex 那个坑的**直接**回归测试。
        //   0x00 0x0F 0xF0 0xFF 在 BigInteger 写法下会变成 "fff0"（丢前导零）。
        val bytes = ByteArray(GatewayTokenProvider.TOKEN_BYTES)
        bytes[0] = 0x00
        bytes[1] = 0x0F
        bytes[2] = 0xF0.toByte()
        bytes[3] = 0xFF.toByte()

        val token = GatewayTokenProvider(FixedRandom(bytes)).issue()

        assertEquals(64, token.length)
        assertTrue(
            "期望以 000ff0ff 开头，实际 $token",
            token.startsWith("000ff0ff"),
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  比较
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `相同的 token 匹配`() {
        val t = provider.issue()
        assertTrue(provider.matches(t, t))
    }

    @Test
    fun `任意一个字符不同就不匹配`() {
        val t = provider.issue()
        // 逐位置翻转，覆盖"只错第一个"与"只错最后一个"两个极端 ——
        // 非常量时间的比较在这两处的耗时差异最大
        val positions = listOf(0, 1, 31, 62, 63)

        for (i in positions) {
            val flipped = t.replaceRange(
                i, i + 1,
                if (t[i] == 'a') "b" else "a",
            )
            assertFalse("第 $i 位翻转后应当不匹配：$flipped", provider.matches(t, flipped))
        }
    }

    @Test
    fun `候选为 null 时不匹配且不抛异常`() {
        assertFalse(provider.matches(provider.issue(), null))
    }

    @Test
    fun `长度不同直接拒绝`() {
        val t = provider.issue()
        assertFalse(provider.matches(t, t.dropLast(1)))
        assertFalse(provider.matches(t, "$t" + "0"))
        assertFalse(provider.matches(t, ""))
    }

    @Test
    fun `比较使用常量时间实现`() {
        // ═══════════════════════════════════════════════════════════
        //  ⚠️ 为什么这条测试要看**源码文本**，而不是跑时间测量
        // ═══════════════════════════════════════════════════════════
        //
        // "耗时与内容无关"是个**统计性质**，单元测试里做统计检验会变得
        // 又慢又不稳（CI 上负载一抖就红），红了以后必然被当噪声忽略。
        //
        // 而这里想钉住的其实不是"耗时"，是"**用的是哪个函数**"——
        // `MessageDigest.isEqual` 是 JDK 提供的常量时间实现，
        // 而 `contentEquals` / `==` 不是。这个差别是**实现事实**，
        // 只能靠读源码来验证。
        //
        // ⚠️ 我第一版写的是"与 MessageDigest.isEqual 的结论一致"，
        //    然后故意把实现换成 `contentEquals` 去验证它会失败 ——
        //    **它没失败**，因为两个函数的返回值本来就一样。
        //    一个永远为真的断言就是没有断言，所以改成了现在这样：
        //    直接断言源码里出现了那个函数名。
        //
        // 说明白局限：这条测试防的是"有人把实现改成非常量时间的版本"
        // （那时代码里读不到 `isEqual`）。它**不能**证明我们用的函数
        // 真的常量时间 —— 那只能靠 JDK 的信誉与 code review。
        val source = locateSource("GatewayTokenProvider.kt")

        assertTrue(
            "matches() 必须用 MessageDigest.isEqual 做常量时间比较，\n" +
                "  contentEquals / == 会在第一个不同字节处短路，构成时序侧信道。\n" +
                "  实际源码里找不到 isEqual。",
            "MessageDigest.isEqual" in source,
        )
        assertFalse(
            "matches() 里不应出现 contentEquals / == 形式的字节数组比较",
            "a.contentEquals(b)" in source,
        )
    }

    /**
     * 找到被测源码文件。
     *
     * ═══════════════════════════════════════════════════════════════
     *  ⚠️ 为什么从 `user.dir` 往上找，而不是从 class 所在目录
     * ═══════════════════════════════════════════════════════════════
     *
     * 第一版用 `javaClass.getResource(".")` 定位 class 文件再上溯 ——
     * **在离线验证器里必然失败**：那个脚本把 class 输出到
     * `~/.workbuddy-ai/binaries/kotlin-verify/work/classes/`，
     * 与源码树毫无关系，上溯再久也找不到 `src/main`。
     * 而 Gradle 通道下 class 在 `<module>/build/classes/...`，能对上。
     * 于是同一条测试**两个通道行为不同** —— 那比没有更糟。
     *
     * 改成从工作目录上溯找 `settings.gradle.kts`（Gradle 工程根的标志）
     * 或 `android/`（仓库根的标志）—— 两个通道的工作目录分别是
     * 模块根与仓库根，这条路径对两者都成立。
     */
    private fun locateSource(fileName: String): String {
        val relative = "provider/gateway/src/main/kotlin/com/pocketagent/provider/gateway/http/$fileName"

        var dir: java.io.File? = java.io.File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            // 仓库根：<root>/android/provider/gateway/...
            val fromRepoRoot = java.io.File(dir, "android/$relative")
            if (fromRepoRoot.isFile) return fromRepoRoot.readText(Charsets.UTF_8)

            // 模块根：<module>/src/main/kotlin/...
            val fromModuleRoot = java.io.File(
                dir,
                "src/main/kotlin/com/pocketagent/provider/gateway/http/$fileName",
            )
            if (fromModuleRoot.isFile) return fromModuleRoot.readText(Charsets.UTF_8)

            dir = dir.parentFile
        }

        error(
            "找不到被测源码 $fileName。\n" +
                "  从 user.dir=${System.getProperty("user.dir")} 向上没有找到匹配的路径。\n" +
                "  若目录结构变了，改这条测试里的 relative 路径。"
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  取头
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `从 Bearer 头取 token`() {
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("Authorization" to "Bearer abc123")))
    }

    @Test
    fun `Bearer 前缀大小写不敏感`() {
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("Authorization" to "bearer abc123")))
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("Authorization" to "BEARER abc123")))
    }

    @Test
    fun `头名大小写不敏感`() {
        // ⚠️ HTTP 头名按 RFC 9110 是大小写不敏感的。按精确大小写查会让
        //    "客户端发的是 authorization" 这种完全合法的情况 401，
        //    而且从日志上看不出来为什么。
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("authorization" to "Bearer abc123")))
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("AUTHORIZATION" to "Bearer abc123")))
    }

    @Test
    fun `裸 Authorization 头也能取到`() {
        // 少数客户端不写 Bearer 前缀
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("Authorization" to "abc123")))
    }

    @Test
    fun `x-api-key 头也能取到`() {
        // Anthropic 风格 —— dsh 的 anthropic-messages 协议会用它
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("x-api-key" to "abc123")))
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("X-Api-Key" to "abc123")))
    }

    @Test
    fun `Authorization 优先于 x-api-key`() {
        val headers = mapOf(
            "Authorization" to "Bearer from-auth",
            "x-api-key" to "from-api-key",
        )
        assertEquals("from-auth", GatewayTokenProvider.extract(headers))
    }

    @Test
    fun `没有可用的头时返回 null`() {
        assertNull(GatewayTokenProvider.extract(emptyMap()))
        assertNull(GatewayTokenProvider.extract(mapOf("Content-Type" to "application/json")))
    }

    @Test
    fun `空的 Bearer 视为没给`() {
        // `Authorization: Bearer ` 后面什么都没有 —— 应当回落到 null，
        // 而不是拿一个空串去比较（结果一样，但会产生误导性的日志）
        assertNull(GatewayTokenProvider.extract(mapOf("Authorization" to "Bearer ")))
        assertNull(GatewayTokenProvider.extract(mapOf("Authorization" to "   ")))
    }

    @Test
    fun `Bearer 后面多余的空格被裁掉`() {
        assertEquals("abc123", GatewayTokenProvider.extract(mapOf("Authorization" to "Bearer  abc123  ")))
    }

    // ─────────────────────────────────────────────────────────────
    //  端到端：issue 出来的 token 能被 extract + matches 走通
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `issue 的 token 经 Authorization 头往返后校验通过`() {
        val token = provider.issue()
        val headers = mapOf("Authorization" to "Bearer $token")

        val extracted = GatewayTokenProvider.extract(headers)

        assertTrue(provider.matches(token, extracted))
    }

    @Test
    fun `另一个实例的 token 校验不通过`() {
        // ★ 这条是"每次启动换 token"这个设计的直接体现：
        //   旧 token 在新一轮启动后必须立即失效。
        val old = provider.issue()
        val newProvider = GatewayTokenProvider()
        val newToken = newProvider.issue()

        assertFalse(newProvider.matches(newToken, old))
        // 且不是碰巧长度不同 —— 长度是一样的
        assertEquals(old.length, newToken.length)
        assertNotEquals(old, newToken)
    }

    /** 每次都填同一串字节的确定性随机源 */
    private class FixedRandom(private val bytes: ByteArray) : SecureRandom() {
        override fun nextBytes(b: ByteArray) {
            // 不足的部分补 0，并尊重调用方给的数组长度
            bytes.copyInto(b, endIndex = minOf(bytes.size, b.size))
            if (b.size > bytes.size) {
                for (i in bytes.size until b.size) b[i] = 0
            }
        }
    }
}
