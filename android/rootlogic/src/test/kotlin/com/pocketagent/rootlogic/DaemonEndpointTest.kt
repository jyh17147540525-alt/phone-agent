package com.pocketagent.rootlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 发现文件的解析。
 *
 * 这些用例守的是一条"静默失效"：文件被写坏（多一个空格、端口写成 0、
 * token 被截断）时，如果解析器"凑合着用"，客户端会拿错端口去连 ——
 * 连到别人的服务比连不上危险得多。所以这里一律**宁可返回 null**。
 */
class DaemonEndpointTest {

    @Test
    fun `正常一行能解析`() {
        val endpoint = DaemonEndpoint.parse("41234 0123456789abcdef0123456789abcdef")
        assertNotNull(endpoint)
        assertEquals(41234, endpoint!!.port)
        assertEquals("0123456789abcdef0123456789abcdef", endpoint.token)
    }

    @Test
    fun `多余空白被容忍`() {
        assertNotNull(DaemonEndpoint.parse("  41234   0123456789abcdef  \n"))
    }

    @Test
    fun `端口不是数字返回 null`() {
        assertNull(DaemonEndpoint.parse("port abcdef0123456789"))
    }

    @Test
    fun `端口越界返回 null`() {
        assertNull(DaemonEndpoint.parse("0 0123456789abcdef"))
        assertNull(DaemonEndpoint.parse("70000 0123456789abcdef"))
    }

    @Test
    fun `token 太短返回 null`() {
        assertNull(DaemonEndpoint.parse("41234 short"))
    }

    @Test
    fun `token 含奇怪字符返回 null`() {
        assertNull(DaemonEndpoint.parse("41234 0123456789abcdef;rm -rf /"))
    }

    @Test
    fun `字段数不对返回 null`() {
        assertNull(DaemonEndpoint.parse("41234"))
        assertNull(DaemonEndpoint.parse("41234 aaaaaaaaaaaaaaaa extra"))
        assertNull(DaemonEndpoint.parse(""))
    }

    @Test
    fun `文件不存在时返回 null 而不是抛`() {
        assertNull(DaemonEndpoint.read("/data/local/tmp/绝对不存在的-pocketagent.txt"))
        assertNull(DaemonEndpoint.open("/data/local/tmp/绝对不存在的-pocketagent.txt"))
    }

    @Test
    fun `真写一个临时文件能读回来`() {
        val file = File.createTempFile("pocketagent-daemon", ".txt")
        try {
            file.writeText("50505 0123456789abcdef\n")
            val endpoint = DaemonEndpoint.read(file.absolutePath)
            assertNotNull(endpoint)
            assertEquals(50505, endpoint!!.port)

            val port = DaemonEndpoint.open(file.absolutePath)
            assertNotNull(port)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `文件内容被写坏时 open 返回 null 不抛`() {
        val file = File.createTempFile("pocketagent-daemon-bad", ".txt")
        try {
            file.writeText("这不是一行合法的发现文件")
            assertNull(DaemonEndpoint.read(file.absolutePath))
            assertNull(DaemonEndpoint.open(file.absolutePath))
        } finally {
            file.delete()
        }
    }
}
