package com.pocketagent.provider.api

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointHintsTest {

    @Test
    fun `拒绝连接时给出监听地址与防火墙的排查步骤`() {
        val text = EndpointHints.forFailure(ConnectException("Failed to connect to /192.168.1.10:8091"))
        assertTrue(text, text.contains("0.0.0.0"))
        assertTrue(text, text.contains("192.168.1.10"))
        assertTrue(text, text.contains("防火墙"))
    }

    @Test
    fun `超时也按局域网排查，而不是只报超时`() {
        val text = EndpointHints.forFailure(SocketTimeoutException("timeout"))
        assertTrue(text, text.contains("监听 0.0.0.0"))
    }

    @Test
    fun `主机名解析失败时说清是解析不到`() {
        val text = EndpointHints.forFailure(UnknownHostException("my-pc.local"))
        assertTrue(text, text.contains("解析不到"))
    }

    @Test
    fun `明文被拦时说清是系统拦截`() {
        val text = EndpointHints.forFailure(
            java.net.UnknownServiceException("CLEARTEXT communication to 192.168.1.10 not permitted"),
        )
        assertTrue(text, text.contains("明文"))
    }
}
