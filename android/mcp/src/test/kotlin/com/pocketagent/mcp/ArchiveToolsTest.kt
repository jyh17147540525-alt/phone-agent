package com.pocketagent.mcp

import com.pocketagent.filelogic.ZipReader
import com.pocketagent.filelogic.ZipWriter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩包三件套的对外行为。
 *
 * 用假 port（内存文件表），因为真 port 要 `Context`；安全规则本身在
 * `:filelogic` 的 `ArchivePlanTest` 里钉。
 */
class ArchiveToolsTest {

    private class FakePort(init: Map<String, ByteArray> = emptyMap()) : ArchivePort {
        val files = LinkedHashMap(init)
        var lastWrite: String? = null

        override suspend fun read(relativePath: String): ByteArray =
            files[relativePath] ?: throw java.io.FileNotFoundException(relativePath)

        override suspend fun write(relativePath: String, bytes: ByteArray) {
            lastWrite = relativePath
            files[relativePath] = bytes
        }

        override suspend fun collect(relativePaths: List<String>): List<Pair<String, ByteArray>> {
            val out = ArrayList<Pair<String, ByteArray>>()
            for (p in relativePaths) {
                val direct = files[p]
                if (direct != null) {
                    out += p to direct
                    continue
                }
                val prefix = "$p/"
                val inner = files.filterKeys { it.startsWith(prefix) }.toSortedMap()
                if (inner.isEmpty()) throw java.io.FileNotFoundException(p)
                for ((k, v) in inner) out += k.removePrefix(prefix) to v
            }
            return out
        }

        override suspend fun countUnder(relativeDir: String): Int =
            files.keys.count { it.startsWith("$relativeDir/") }
    }

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) {
            when (v) {
                is String -> put(k, v)
                is JsonArray -> put(k, v)
                else -> error("unsupported $v")
            }
        }
    }

    private fun JsonObject.text(key: String): String =
        (this[key] as JsonPrimitive).content

    // ── zip_create ────────────────────────────────────────────

    @Test
    fun `打包把目录里的文件收进 zip`() = runBlocking {
        val port = FakePort(
            mapOf(
                "src/a.txt" to "甲".toByteArray(),
                "src/b/c.txt" to "乙".toByteArray(),
            ),
        )
        val out = ZipCreateTool(port).call(
            args("zip" to "输出/包.zip", "paths" to JsonArray(listOf(JsonPrimitive("src")))),
        )
        assertTrue(out is ToolOutcome.Success)
        val text = (out as ToolOutcome.Success).text
        assertTrue(text.contains("2 个文件"))
        assertEquals("输出/包.zip", port.lastWrite)
        val names = ZipReader.list(port.files["输出/包.zip"]!!).map { it.name }
        assertEquals(listOf("a.txt", "b/c.txt"), names)
    }

    @Test
    fun `打包没给路径说人话`() = runBlocking {
        val out = ZipCreateTool(FakePort()).call(args("zip" to "a.zip"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("至少一个"))
    }

    @Test
    fun `打包不存在的路径如实报错`() = runBlocking {
        val out = ZipCreateTool(FakePort()).call(
            args("zip" to "a.zip", "paths" to JsonArray(listOf(JsonPrimitive("没有这个目录")))),
        )
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("不存在"))
    }

    // ── zip_list ──────────────────────────────────────────────

    @Test
    fun `看包能列出名字与大小`() = runBlocking {
        val zip = ZipWriter.create(listOf("a.txt" to "1".toByteArray(), "b/c.txt" to "22".toByteArray()))
        val out = ZipListTool(FakePort(mapOf("x.zip" to zip))).call(args("zip" to "x.zip"))
        assertTrue(out is ToolOutcome.Success)
        val text = (out as ToolOutcome.Success).text
        assertTrue(text.contains("a.txt"))
        assertTrue(text.contains("b/c.txt"))
        assertTrue(text.contains("2 项"))
    }

    @Test
    fun `看包不存在时提示路径`() = runBlocking {
        val out = ZipListTool(FakePort()).call(args("zip" to "没有.zip"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("没有「没有.zip」"))
    }

    // ── zip_extract ───────────────────────────────────────────

    @Test
    fun `解压落到指定目录`() = runBlocking {
        val zip = ZipWriter.create(listOf("a.txt" to "内容".toByteArray()))
        val port = FakePort(mapOf("x.zip" to zip))
        val out = ZipExtractTool(port).call(args("zip" to "x.zip", "to" to "输出/解开"))
        assertTrue(out is ToolOutcome.Success)
        assertEquals("内容", port.files["输出/解开/a.txt"]!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `解压目录不合规直接拒`() = runBlocking {
        val zip = ZipWriter.create(listOf("a.txt" to "x".toByteArray()))
        val out = ZipExtractTool(FakePort(mapOf("x.zip" to zip)))
            .call(args("zip" to "x.zip", "to" to "../跑到外面"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("不合规"))
    }

    @Test
    fun `不是 zip 的文件说清打不开`() = runBlocking {
        val out = ZipExtractTool(FakePort(mapOf("x.zip" to "这不是压缩包".toByteArray())))
            .call(args("zip" to "x.zip"))
        assertTrue(out is ToolOutcome.Failed)
        assertTrue((out as ToolOutcome.Failed).reason.contains("不是 zip"))
    }

    @Test
    fun `工具名与 schema 对得上`() {
        val port = FakePort()
        assertEquals("zip_list", ZipListTool(port).name)
        assertEquals("zip_create", ZipCreateTool(port).name)
        assertEquals("zip_extract", ZipExtractTool(port).name)
        assertEquals("zip_list", ZipListTool(port).inputSchema().text("type").let { "zip_list" })
        assertTrue(ZipExtractTool(port).inputSchema().toString().contains("\"zip\""))
    }
}
