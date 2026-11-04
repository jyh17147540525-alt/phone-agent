package com.pocketagent.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **目标边界** —— 空清单必须什么都打不了。
 *
 * ⚠️ 这组测试保护的是一条**默认失败**的性质，不是某个功能：
 *    一个能做扫描/利用的模块，若"没配范围"时放行，等于默认指向全网。
 *    所以 `fail_closed` 那两条是这整个文件里最要紧的。
 */
class ScopeGuardTest {

    private fun allowed(guard: ScopeGuard, target: String): Boolean =
        guard.check(target) is ScopeDecision.Allowed

    // ═══════════════════════════════════════════════════════════
    //  ★★ 默认失败
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `清单为空时任何目标都被拒`() {
        val g = ScopeGuard(emptyList())
        assertTrue(g.isEmpty)
        for (t in listOf("192.0.2.10", "example.com", "https://example.com/x", "127.0.0.1")) {
            assertFalse("空清单不许放行：$t", allowed(g, t))
        }
    }

    @Test
    fun `注释与空行不算规则`() {
        val g = ScopeGuard(listOf("", "   ", "# 只测自己家", "  # 缩进注释"))
        assertTrue("注释不是授权", g.isEmpty)
        assertEquals(0, g.size)
        assertFalse(allowed(g, "192.0.2.10"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ IP / CIDR
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `单个 IP 精确命中`() {
        val g = ScopeGuard(listOf("192.0.2.10"))
        assertTrue(allowed(g, "192.0.2.10"))
        assertTrue("带端口也算同一台", allowed(g, "192.0.2.10:8080"))
        assertFalse(allowed(g, "192.0.2.11"))
    }

    @Test
    fun `C 段按前缀命中`() {
        val g = ScopeGuard(listOf("198.51.100.0/24"))
        assertTrue(allowed(g, "198.51.100.1"))
        assertTrue(allowed(g, "198.51.100.254"))
        assertFalse("段外不许", allowed(g, "198.51.101.1"))
        assertFalse("前缀相同但位数不同不许", allowed(g, "198.51.100.1") && allowed(g, "198.51.101.1"))
    }

    @Test
    fun `不当 CIDR 的位数被丢弃`() {
        assertEquals(0, ScopeGuard(listOf("10.0.0.0/33")).size)
        assertEquals(0, ScopeGuard(listOf("10.0.0.0/abc")).size)
        assertEquals(0, ScopeGuard(listOf("999.1.1.1")).size)
    }

    @Test
    fun `IPv6 前缀`() {
        val g = ScopeGuard(listOf("2001:db8::/32"))
        assertTrue(allowed(g, "2001:db8::1"))
        assertTrue(allowed(g, "[2001:db8::5]:443"))
        assertFalse(allowed(g, "2001:db9::1"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★★ 域名：标签边界，不是裸 endsWith
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `子域通配只吃真子域`() {
        val g = ScopeGuard(listOf("*.example.com"))
        assertTrue(allowed(g, "api.example.com"))
        assertTrue(allowed(g, "https://a.b.example.com:8443/x"))
        assertFalse("通配不含裸域本身", allowed(g, "example.com"))
        assertFalse("★ 关键：标签边界", allowed(g, "evilexample.com"))
        assertFalse("★ 关键：后缀伪装", allowed(g, "example.com.attacker.net"))
    }

    @Test
    fun `点开头等价于星号`() {
        val g = ScopeGuard(listOf(".example.com"))
        assertTrue(allowed(g, "api.example.com"))
        assertFalse(allowed(g, "evilexample.com"))
    }

    @Test
    fun `精确主机名不吃子域`() {
        val g = ScopeGuard(listOf("api.example.com"))
        assertTrue(allowed(g, "api.example.com"))
        assertTrue(allowed(g, "API.EXAMPLE.COM"))
        assertTrue(allowed(g, "https://api.example.com/v1/users?id=1"))
        assertFalse(allowed(g, "x.api.example.com"))
    }

    // ═══════════════════════════════════════════════════════════
    //  ★ 目标解析
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `各种写法都能抠出主机`() {
        val g = ScopeGuard(listOf("192.0.2.10"))
        assertTrue(allowed(g, "http://192.0.2.10/a/b?c=d"))
        assertTrue(allowed(g, "user:pass@192.0.2.10:22"))
    }

    @Test
    fun `空目标被拒且给得出理由`() {
        val d = ScopeGuard(listOf("192.0.2.10")).check("   ")
        assertTrue(d is ScopeDecision.Denied)
        assertTrue((d as ScopeDecision.Denied).reason.isNotBlank())
    }

    @Test
    fun `拒绝理由说清是哪种不许`() {
        val empty = ScopeGuard(emptyList()).check("192.0.2.10") as ScopeDecision.Denied
        assertTrue("空清单的理由要能指导下一步：${empty.reason}", empty.reason.contains("scope"))

        val outside = ScopeGuard(listOf("192.0.2.10")).check("203.0.113.9") as ScopeDecision.Denied
        assertTrue(outside.reason.contains("不在范围"))
    }
}
