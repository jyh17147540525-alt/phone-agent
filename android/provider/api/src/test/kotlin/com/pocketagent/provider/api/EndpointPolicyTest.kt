package com.pocketagent.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPolicyTest {

    @Test
    fun `没写协议时补 http`() {
        assertEquals("http://192.168.1.10:8091", EndpointPolicy.normalize("192.168.1.10:8091"))
        assertEquals("http://192.168.1.10:8091", EndpointPolicy.normalize(" 192.168.1.10:8091 "))
    }

    @Test
    fun `已经有协议的原样保留`() {
        assertEquals("https://api.breeze.blue", EndpointPolicy.normalize("https://api.breeze.blue"))
        assertEquals("http://10.0.0.5:8080", EndpointPolicy.normalize("http://10.0.0.5:8080"))
    }

    @Test
    fun `局域网地址拼路径`() {
        assertEquals(
            "http://192.168.1.10:8091/v1/audio/speech",
            EndpointPolicy.join("192.168.1.10:8091", "/v1/audio/speech"),
        )
    }

    @Test
    fun `base 已经带 v1 时不重复`() {
        assertEquals(
            "http://192.168.1.10:8091/v1/audio/speech",
            EndpointPolicy.join("http://192.168.1.10:8091/v1", "/v1/audio/speech"),
        )
        assertEquals(
            "http://192.168.1.10:8091/v1/audio/speech",
            EndpointPolicy.join("http://192.168.1.10:8091/v1/", "/v1/audio/speech"),
        )
    }

    @Test
    fun `粘贴整条端点时不再重复拼`() {
        assertEquals(
            "http://192.168.1.10:8091/v1/audio/speech",
            EndpointPolicy.join("http://192.168.1.10:8091/v1/audio/speech", "/v1/audio/speech"),
        )
    }

    @Test
    fun `末尾斜杠不影响结果`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointPolicy.join("https://api.example.com/", "/v1/chat/completions"),
        )
    }

    @Test
    fun `本机与局域网被认出来`() {
        listOf(
            "localhost", "127.0.0.1", "10.1.2.3", "192.168.43.1", "172.16.0.9",
            "169.254.1.1", "100.64.0.2", "::1", "fe80::1", "fd00::1", "my-pc.local",
        ).forEach { assertTrue(it, EndpointPolicy.isLanHost(it)) }
    }

    @Test
    fun `公网与非法段不被认成局域网`() {
        listOf(
            "8.8.8.8", "api.openai.com", "172.32.0.1", "172.15.0.1", "192.169.1.1",
            "169.253.1.1", "100.128.0.1", "256.1.1.1", "1.1.1.1",
        ).forEach { assertFalse(it, EndpointPolicy.isLanHost(it)) }
    }

    @Test
    fun `局域网明文放行`() {
        val verdict = EndpointPolicy.check("192.168.1.10:8091", "/v1/audio/speech")
        assertTrue(verdict is EndpointPolicy.Verdict.Ok)
        verdict as EndpointPolicy.Verdict.Ok
        assertTrue(verdict.lan)
        assertEquals("http://192.168.1.10:8091/v1/audio/speech", verdict.url)
    }

    @Test
    fun `公网明文被标出来`() {
        val verdict = EndpointPolicy.check("http://api.example.com", "/v1/chat/completions")
        assertTrue(verdict is EndpointPolicy.Verdict.CleartextPublic)
        assertEquals(
            "http://api.example.com/v1/chat/completions",
            (verdict as EndpointPolicy.Verdict.CleartextPublic).url,
        )
    }

    @Test
    fun `公网 https 放行`() {
        val verdict = EndpointPolicy.check("https://api.breeze.blue", "/v1/text-to-speech")
        assertTrue(verdict is EndpointPolicy.Verdict.Ok)
        assertFalse((verdict as EndpointPolicy.Verdict.Ok).lan)
    }

    @Test
    fun `空地址与怪协议都被拒`() {
        assertTrue(EndpointPolicy.check("") is EndpointPolicy.Verdict.Bad)
        assertTrue(EndpointPolicy.check("   ") is EndpointPolicy.Verdict.Bad)
        assertTrue(EndpointPolicy.check("ftp://1.2.3.4") is EndpointPolicy.Verdict.Bad)
    }

    @Test
    fun `主机名解析`() {
        assertEquals("192.168.1.10", EndpointPolicy.hostOf("http://192.168.1.10:8091/v1"))
        assertEquals("api.example.com", EndpointPolicy.hostOf("https://api.example.com/x?y=1"))
        assertEquals("http", EndpointPolicy.schemeOf("192.168.1.10:8091"))
    }
}
